const element = id => document.getElementById(id)
const android = new URLSearchParams(location.search).get('mode') === 'android'
const invitation = new URLSearchParams(location.search).get('invite')
const boardRoomId = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
const requests = new Map()
let nextRequest = 0
let nativePort
let webToken
let refresh

function takeRequest(id) {
  const pending = requests.get(id)
  if (!pending) return
  requests.delete(id)
  clearTimeout(pending.timer)
  return pending
}

if (android) window.addEventListener('message', event => {
  if (event.data !== 'novvin-board' || event.ports.length !== 1) return
  nativePort?.close()
  const port = event.ports[0]
  nativePort = port
  for (const id of requests.keys()) takeRequest(id).reject(new Error('BOARD_UNAVAILABLE'))
  port.onmessage = message => {
    if (nativePort !== port) return
    let reply
    try { reply = JSON.parse(message.data) } catch { return }
    if (!reply || typeof reply !== 'object') return
    const pending = takeRequest(reply.id)
    if (!pending) return
    if (typeof reply.error === 'string') {
      pending.reject(new Error(reply.error))
      return
    }
    try { pending.resolve(JSON.parse(reply.payload)) }
    catch { pending.reject(new Error('BOARD_UNAVAILABLE')) }
  }
  window.dispatchEvent(new Event('novvin-board-ready'))
})

async function request(path, body, retry = true) {
  if (android) {
    if (!nativePort) throw new Error('BOARD_UNAVAILABLE')
    return new Promise((resolve, reject) => {
      const id = ++nextRequest
      const timer = setTimeout(() => {
        takeRequest(id)?.reject(new Error('BOARD_UNAVAILABLE'))
      }, 12000)
      requests.set(id, { resolve, reject, timer })
      try { nativePort.postMessage(JSON.stringify({ id, method: body ? 'POST' : 'GET', path, body })) }
      catch { takeRequest(id)?.reject(new Error('BOARD_UNAVAILABLE')) }
    })
  }
  if (!webToken) {
    refresh ??= fetch('/v1/auth/web-session/refresh', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Novvin-Web-Session': 'web-v1' },
      body: JSON.stringify({ client: 'web' }),
    }).then(async response => {
      if (!response.ok) throw new Error(response.status === 401 ? 'BOARD_SIGN_IN' : 'BOARD_UNAVAILABLE')
      const payload = await response.json()
      if (!payload.accessToken) throw new Error('BOARD_SIGN_IN')
      webToken = payload.accessToken
    }).finally(() => { refresh = null })
    await refresh
  }
  const response = await fetch('/v1/data/playmatch/board-games' + path, {
    method: body ? 'POST' : 'GET',
    headers: { Authorization: 'Bearer ' + webToken, 'Content-Type': 'application/json', 'X-Novvin-Board-Protocol': '2' },
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  })
  if (response.status === 401 && retry) {
    webToken = null
    return request(path, body, false)
  }
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.error || 'BOARD_UNAVAILABLE')
  return payload
}

export const boardNames = { chess: 'Ajedrez', checkers: 'Damas', ludo: 'Ludo',
  tic_tac_toe: 'Tres en raya', connect_four: 'Cuatro en línea', reversi: 'Reversi', gomoku: 'Gomoku' }
const emojis = [['👏', 'Aplaudir'], ['😄', 'Sonreír'], ['🔥', 'Enviar fuego'], ['🤝', 'Dar la mano'], ['🎉', 'Celebrar'], ['😮', 'Sorprenderse']]
const messages = {
  BOARD_SIGN_IN: 'Inicia sesión en Novvin y vuelve a abrir Juegos.',
  AUTH_REQUIRED: 'Inicia sesión en Novvin y vuelve a abrir Juegos.',
  BOARD_ROOM_NOT_FOUND: 'Esta sala ya no está disponible.',
  BOARD_ROOM_FULL: 'La sala se llenó o cerró. Elige otra partida.',
  BOARD_ROOM_LIMIT: 'Tienes varias salas abiertas. Sal de una para crear otra.',
  BOARD_REVISION_CHANGED: 'La partida cambió. El tablero se está actualizando.',
  BOARD_OUT_OF_TURN: 'Es el turno de otra persona.',
  BOARD_ILLEGAL_ACTION: 'Esta jugada no es válida.',
  BOARD_REACTION_LIMIT: 'Espera un momento antes de enviar otra reacción.',
  BOARD_PLAYER_NOT_FOUND: 'La invitación no está disponible para esta persona.',
  BOARD_INVITES_LIMIT: 'Alcanzaste el máximo de invitaciones de esta sala.',
  BOARD_ROOM_NOT_PLAYING: 'La partida ya cambió de estado.',
  BOARD_ROOM_INVALID: 'Este juego todavía no está disponible en línea. Puedes jugar en este dispositivo.',
  BOARD_CHAT_LIMIT: 'Espera un momento antes de enviar otro mensaje.',
  BOARD_CHAT_INVALID: 'Escribe un mensaje de hasta 240 caracteres.',
  BOARD_REMATCH_UNAVAILABLE: 'La revancha estará disponible al terminar la partida.',
  BOARD_SOCIAL_UNAVAILABLE: 'El chat o la revancha ya no están disponibles en esta mesa.',
}
const messageFor = error => messages[error.message] || 'No se pudo conectar con Juegos en línea. Inténtalo nuevamente.'
const connectionErrors = new Set(['BOARD_UNAVAILABLE', 'BACKEND_UNAVAILABLE', 'PLAYMATCH_REQUEST_FAILED'])
const connectionLost = error => connectionErrors.has(error.message) || error instanceof TypeError || error instanceof SyntaxError
const staleMoveErrors = new Set(['BOARD_REVISION_CHANGED', 'BOARD_ROOM_NOT_PLAYING', 'BOARD_OUT_OF_TURN'])
const moveMessages = {
  sending: 'Enviando la jugada…',
  verifying: 'Verificando la jugada…',
  retry: 'Tablero actualizado. Puedes volver a intentar la jugada.',
}
function actionButton(label, handler) {
  const button = document.createElement('button')
  button.type = 'button'
  button.textContent = label
  button.addEventListener('click', handler)
  return button
}

export function onlineBoard({ selection, onRoom, clearBoard, onRooms = () => {}, onClock = () => {} }) {
  let room
  let mode = 'offline'
  let timer
  let clock
  let generation = 0
  let actionVersion = 0
  let loading = false
  let reconciling = false
  let lastReaction = 0
  let friendsLoadedFor
  let friendsLoadingFor
  let lobbyNotice = ''
  let requestedRoomId = null
  let roomToRestore = null
  let moveStatus = null
  let roomsStamp
  let focusedInvitation = false
  let chatDraft
  let chatStamp
  const invited = new Set()
  const pendingCreations = new Map()
  const url = (suffix = '') => '/rooms/' + room.id + suffix
  const moveControls = [element('board'), document.querySelector('.turn-controls')]
  const movePending = () => moveStatus?.phase === 'sending' || moveStatus?.phase === 'verifying'
  const status = message => {
    const inTable = room != null && room.status !== 'waiting'
    const lobbyStatus = element('online-status')
    lobbyStatus.hidden = inTable
    lobbyStatus.textContent = inTable ? '' : message
    const connection = element('table-connection')
    connection.textContent = inTable ? message : ''
    connection.hidden = !inTable || !message
    for (const control of moveControls) {
      if (movePending()) control.setAttribute('aria-disabled', 'true')
      else control.removeAttribute('aria-disabled')
    }
  }

  function show(next) {
    if (next !== mode) {
      generation++
      clearTimeout(timer)
      clearInterval(clock)
      room = null
      roomToRestore = null
      moveStatus = null
      loading = false
      reconciling = false
      lastReaction = 0
      friendsLoadedFor = undefined
      friendsLoadingFor = undefined
      invited.clear()
      status('')
      clearBoard()
    }
    mode = next
    element('setup').hidden = mode !== 'offline'
    element('online').hidden = mode !== 'online'
    element('ranking').hidden = mode !== 'ranking'
    element('online-reactions').hidden = true
    for (const id of ['offline', 'online', 'ranking']) element('open-' + id).setAttribute('aria-pressed', String(mode === id))
  }

  const normalized = value => value.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLocaleLowerCase('es').trim()

  function personLabel(name) {
    const badge = document.createElement('span')
    badge.className = 'person-avatar'
    badge.setAttribute('aria-hidden', 'true')
    badge.textContent = Array.from(name.trim())[0]?.toLocaleUpperCase('es') || 'J'
    return badge
  }

  function filterFriends() {
    const query = normalized(element('friend-filter').value)
    const rows = [...element('friends').children]
    rows.forEach(row => { row.hidden = !row.dataset.search.includes(query) })
    const empty = element('friends-empty')
    empty.hidden = rows.some(row => !row.hidden)
    empty.textContent = rows.length ? 'No hay amigos que coincidan con tu búsqueda.' : 'Aquí aparecen tus amistades aceptadas.'
  }

  function displayRooms(rooms) {
    onRooms(rooms)
    const chosen = selection().kind
    const visible = rooms.filter(item => item.status !== 'closed' && (item.game === chosen || item.invited || item.seat >= 0))
    const requested = visible.find(item => item.id === requestedRoomId && (item.invited || item.seat >= 0))
    if (requested) visible.sort((left, right) => Number(right === requested) - Number(left === requested))
    const summary = element('lobby-summary')
    const results = rooms.filter(item => item.seat >= 0 && item.result)
    summary.hidden = !results.length
    if (results.length) {
      const wins = results.filter(item => item.result === 'win').length
      const draws = results.filter(item => item.result === 'draw').length
      summary.textContent = `Resultados recientes · Victorias ${wins} · Empates ${draws} · Derrotas ${results.length - wins - draws}`
    }
    const stamp = JSON.stringify(visible)
    if (stamp !== roomsStamp) element('rooms').replaceChildren(...visible.map(item => {
      const label = item.seat >= 0 ? item.status === 'finished' ? 'Ver resultado' : 'Volver a la sala' :
        item.invited ? 'Aceptar invitación' : 'Unirme'
      const row = document.createElement('div')
      row.className = 'room-row'
      row.dataset.roomKind = item.invited ? 'invite' : item.status === 'playing' ? 'active' : 'other'
      row.dataset.requested = String(item === requested)
      const info = document.createElement('div')
      const title = document.createElement('strong')
      title.textContent = boardNames[item.game]
      const details = document.createElement('small')
      const phase = item.invited ? 'Invitación' : item.status === 'playing' ? 'En curso' : item.status === 'finished' ? 'Finalizada' : 'En espera'
      details.textContent = `${phase} · ${item.count}/${item.capacity} · ${item.visibility === 'private' ? 'Privada' : 'Pública'}` +
        (item.clockMinutes ? ` · ${item.clockMinutes} min` : '')
      info.append(title, details)
      const open = actionButton(label, () => run(async isCurrent => {
        const result = item.seat >= 0 ? await request('/rooms/' + item.id) : await request('/rooms/' + item.id + '/join', {})
        if (isCurrent()) displayRoom(result.room)
      }))
      if (item === requested) open.className = 'primary'
      row.append(info, open)
      return row
    }))
    roomsStamp = stamp
    if (requested && !focusedInvitation) {
      const action = element('rooms').querySelector('[data-requested="true"] button')
      action?.focus({ preventScroll: true })
      action?.scrollIntoView?.({ block: 'nearest' })
      focusedInvitation = true
    }
    const inviteNotice = requestedRoomId && !requested ? 'Esta invitación ya no está disponible.' : ''
    status(lobbyNotice || inviteNotice || (visible.length ? '' : 'No hay salas de este juego disponibles.'))
  }

  function updateSocial(value, enteringRoom) {
    const panel = element('table-chat')
    if (enteringRoom) {
      panel.open = false
      chatStamp = undefined
      chatDraft = undefined
      element('table-message').value = ''
      lastReaction = 0
      element('reaction-status').textContent = ''
    }
    panel.hidden = !Array.isArray(value.chat)
    element('table-chat-form').hidden = value.status !== 'playing'
    if (Array.isArray(value.chat)) {
      const stamp = value.chat.map(message => message.id).join(',')
      if (stamp !== chatStamp) {
        const list = element('table-messages')
        const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 32
        list.replaceChildren(...value.chat.map(message => {
          const item = document.createElement('li')
          const author = value.players?.find(player => player.seat === message.seat)?.name || 'Jugador ' + (message.seat + 1)
          item.textContent = `${author}: ${message.text}`
          return item
        }))
        if (nearBottom) list.scrollTop = list.scrollHeight
        chatStamp = stamp
      }
    }
    const rematch = element('request-rematch')
    const message = element('rematch-status')
    const data = value.rematch
    rematch.hidden = !data || value.status !== 'finished'
    message.hidden = rematch.hidden || !data.seats.length
    if (rematch.hidden) return
    rematch.disabled = !data.roomId && data.seats.includes(value.seat)
    rematch.textContent = data.roomId ? 'Entrar a la revancha' : data.seats.length && !rematch.disabled ? 'Aceptar revancha' : 'Solicitar revancha'
    if (!message.hidden) message.textContent = data.roomId ? 'La nueva mesa está lista.' : `${data.seats.length} de ${value.capacity} aceptaron la revancha.`
  }

  function displayRoom(value) {
    if (room?.id === value.id && value.revision < room.revision) return
    roomToRestore = null
    const key = value.game + ':' + value.capacity + ':' + (value.clockMinutes || 0)
    if (pendingCreations.get(key) === value.id) pendingCreations.delete(key)
    if (value.status === 'closed') {
      lobby('La partida se cerró.')
      return
    }
    const enteringRoom = room?.id !== value.id
    const changed = enteringRoom || room?.revision !== value.revision
    const clock = value.clock
    const newerClock = !changed && clock?.startedAt != null && clock.serverNow > (room.clock?.serverNow ?? -Infinity)
    room = changed || newerClock || !room?.clock ? value : { ...value, clock: room.clock }
    if (value.status !== 'playing' || (moveStatus?.phase === 'retry' && moveStatus.revision !== value.revision)) moveStatus = null
    element('hub').hidden = true
    element('catalog').hidden = true
    element('rooms').hidden = true
    element('lobby-summary').hidden = true
    element('room-controls').hidden = true
    if (value.status !== 'waiting') {
      status(moveMessages[moveStatus?.phase] || '')
      element('waiting').hidden = true
      element('online-reactions').hidden = value.status !== 'playing'
      if (changed) onRoom(value)
      else if (newerClock) onClock(clock)
      updateSocial(value, enteringRoom)
      const reaction = value.reactions?.at(-1)
      if (reaction && reaction.at > lastReaction && Date.now() - reaction.at < 30000) {
        lastReaction = reaction.at
        const author = value.players?.find(player => player.seat === reaction.seat)?.name
        const feedback = element('reaction-status')
        feedback.textContent = (author || 'Jugador ' + (reaction.seat + 1)) + ' · ' + reaction.emoji
        if (!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
          feedback.animate?.([{ transform: 'translateY(5px)', opacity: .35 }, { transform: 'none', opacity: 1 }], { duration: 260, easing: 'ease-out' })
        }
      }
      return
    }
    status('')
    element('waiting').hidden = false
    element('waiting-art').dataset.cover = value.game
    element('waiting-title').textContent = boardNames[value.game] + (value.visibility === 'private' ? ' · Sala privada' : ' · Sala pública')
    element('waiting-description').textContent = value.count + ' de ' + value.capacity + ' jugadores. La mesa se abre al completar la sala.' +
      (value.clockMinutes ? ` ${value.clockMinutes} minutos por jugador.` : '')
    if (changed) element('waiting-seats').replaceChildren(...Array.from({ length: value.capacity }, (_, seat) => {
      const slot = document.createElement('li')
      if (seat >= value.count) {
        slot.className = 'vacant'
        slot.textContent = 'Puesto libre'
      } else {
        const player = value.players?.find(candidate => candidate.seat === seat)
        const name = player?.name || (seat === value.seat ? 'Tú' : 'Jugador ' + (seat + 1))
        slot.append(personLabel(name), document.createTextNode(name))
      }
      return slot
    }))
    if (enteringRoom) element('waiting-title').focus()
    element('player-search').hidden = !(value.host && value.visibility === 'private' && Array.isArray(value.chat))
    void loadFriends()
  }

  async function loadFriends() {
    if (room?.status !== 'waiting' || !room.host || room.visibility !== 'private') return
    const id = room.id
    if (friendsLoadedFor === id || friendsLoadingFor === id) return
    const ticket = generation
    const isCurrent = () => ticket === generation && room?.id === id && room.status === 'waiting'
    friendsLoadingFor = id
    element('friends-empty').textContent = 'Cargando amistades…'
    element('friends-empty').hidden = false
    element('retry-friends').hidden = true
    try {
      const { friends } = await request('/friends')
      if (!isCurrent()) return
      renderFriends(friends)
      friendsLoadedFor = id
    } catch {
      if (!isCurrent()) return
      element('friends-empty').textContent = 'No pudimos cargar tus amistades.'
      element('retry-friends').hidden = false
    } finally {
      if (ticket === generation && friendsLoadingFor === id) friendsLoadingFor = undefined
    }
  }

  function renderFriends(friends, panel = 'friends') {
    element(panel).replaceChildren(...friends.map(friend => {
      const row = document.createElement('div')
      row.className = 'friend-row'
      row.dataset.search = normalized(friend.name)
      const name = document.createElement('span')
      name.textContent = friend.name
      const wasInvited = friend.invited || invited.has(friend.id)
      const invite = actionButton(wasInvited ? 'Enviada' : 'Invitar', () => run(async current => {
        await request(url('/invite'), { recipientId: friend.id })
        if (!current()) return
        invited.add(friend.id)
        for (const control of document.querySelectorAll('[data-invite]')) {
          if (control.dataset.invite !== friend.id) continue
          control.textContent = 'Enviada'
          control.setAttribute('aria-label', 'Invitación enviada a ' + friend.name)
          control.disabled = true
        }
      }))
      invite.dataset.invite = friend.id
      invite.disabled = wasInvited
      invite.setAttribute('aria-label', invite.disabled ? 'Invitación enviada a ' + friend.name : 'Invitar a ' + friend.name)
      row.append(personLabel(friend.name), name, invite)
      return row
    }))
    if (panel === 'friends') {
      element('friend-filter-label').hidden = friends.length === 0
      filterFriends()
    }
  }

  async function poll() {
    if (mode !== 'online') return
    const ticket = generation
    const action = actionVersion
    const isCurrent = () => ticket === generation && action === actionVersion && !loading
    if (!document.hidden && !loading && !reconciling) {
      reconciling = true
      const monitored = room?.id || roomToRestore
      try {
        if (roomToRestore || room && (['waiting', 'playing'].includes(room.status) || room.status === 'finished' && room.rematch && !room.rematch.roomId)) {
          const { room: latest } = await request('/rooms/' + monitored)
          if (isCurrent() && (!room || latest.revision >= room.revision)) {
            if (moveStatus?.phase === 'verifying') {
              moveStatus = latest.revision === moveStatus.revision ? { ...moveStatus, phase: 'retry' } : null
            }
            displayRoom(latest)
          }
        } else if (!room) {
          const { rooms } = await request('/rooms')
          if (isCurrent() && !room) displayRooms(rooms)
        }
      } catch (error) {
        if (isCurrent()) {
          const authNeeded = ['AUTH_REQUIRED', 'BOARD_SIGN_IN'].includes(error.message)
          const verifyingMove = moveStatus?.phase === 'verifying'
          if (((roomToRestore || verifyingMove) && authNeeded) || (monitored && error.message === 'BOARD_ROOM_NOT_FOUND')) lobby(messageFor(error))
          else if (verifyingMove) status(moveMessages.verifying)
          else status(roomToRestore || (monitored && connectionLost(error)) ? 'Reconectando con la mesa…' : messageFor(error))
        }
      } finally {
        if (ticket === generation) reconciling = false
      }
    }
    if (ticket === generation) timer = setTimeout(poll, room?.status === 'playing' ? 1800 : 5000)
  }

  async function run(task) {
    if (loading) return
    actionVersion++
    const ticket = generation
    const isCurrent = () => ticket === generation
    loading = true
    try { await task(isCurrent) }
    catch (error) {
      if (isCurrent()) {
        if (mode === 'ranking') element('ranking-period').textContent = messageFor(error)
        else status(messageFor(error))
      }
    }
    finally { if (isCurrent()) loading = false }
  }

  async function move(event) {
    if (room?.status !== 'playing' || movePending()) return
    await run(async current => {
      const revision = room.revision
      moveStatus = { revision, phase: 'sending' }
      status(moveMessages.sending)
      try {
        const { room: updated } = await request(url('/actions'), { ...event, revision })
        if (current()) {
          moveStatus = null
          displayRoom(updated)
        }
      } catch (error) {
        if (!current()) return
        if (!connectionLost(error) && !staleMoveErrors.has(error.message)) {
          moveStatus = null
          throw error
        }
        moveStatus = { revision, phase: 'verifying' }
        status(moveMessages.verifying)
      }
    })
    if (moveStatus?.phase === 'verifying') refreshOnReturn()
  }

  function lobby(notice = '', { invitationId = null, resumeId = null } = {}) {
    if (mode !== 'online') show('online')
    else {
      generation++
      clearTimeout(timer)
      loading = false
      reconciling = false
    }
    room = null
    moveStatus = null
    invited.clear()
    lastReaction = 0
    friendsLoadedFor = undefined
    friendsLoadingFor = undefined
    lobbyNotice = notice
    requestedRoomId = invitationId
    roomToRestore = resumeId
    roomsStamp = undefined
    focusedInvitation = false
    clearBoard()
    element('online-reactions').hidden = true
    element('reaction-status').textContent = ''
    element('waiting').hidden = true
    element('friends').replaceChildren()
    element('players-found').replaceChildren()
    element('player-search-status').hidden = true
    element('player-query').value = ''
    element('friends-empty').hidden = true
    element('retry-friends').hidden = true
    element('friend-filter-label').hidden = true
    element('friend-filter').value = ''
    element('rooms').hidden = false
    element('rooms').replaceChildren()
    element('lobby-summary').hidden = true
    element('room-controls').hidden = false
    status(notice || (roomToRestore ? 'Recuperando tu partida…' : 'Buscando salas…'))
    void poll()
  }

  async function start(visibility, isCurrent) {
    const { kind, capacity, clockMinutes = 0 } = selection()
    const key = kind + ':' + capacity + ':' + clockMinutes
    const privateRoom = visibility === 'private'
    // Private creation uses this ID as the room ID. Keep it until the server
    // confirms the write, so a lost reply cannot create a second room.
    const requestId = (privateRoom && pendingCreations.get(key)) || crypto.randomUUID()
    if (privateRoom) pendingCreations.set(key, requestId)
    let result
    try {
      result = await request(privateRoom ? '/rooms' : '/match', { game: kind, capacity, visibility, requestId, ...(clockMinutes ? { clockMinutes } : {}) })
    } catch (error) {
      if (privateRoom && isCurrent() && ['BOARD_ROOM_LIMIT', 'BOARD_ROOM_INVALID'].includes(error.message)) {
        pendingCreations.delete(key)
      }
      throw error
    }
    if (isCurrent()) displayRoom(result.room)
  }

  const calendar = new Intl.DateTimeFormat('es', { dateStyle: 'medium', timeZone: 'America/Bogota' })
  function loadRanking() {
    return run(async isCurrent => {
      const ranking = await request('/ranking')
      if (!isCurrent()) return
      const render = (id, rows, empty) => element(id).replaceChildren(...(rows.length ? rows : [{ name: empty }]).map(row => {
        const item = document.createElement('li')
        item.textContent = row.wins === undefined ? row.name : row.name + ' · ' + row.wins + (row.wins === 1 ? ' victoria' : ' victorias')
        return item
      }))
      render('previous-podium', ranking.podium, 'Todavía no hay podio.')
      render('weekly-top', ranking.top, 'Todavía no hay victorias.')
      const offset = Date.parse(ranking.serverNow) - Date.now()
      const end = Date.parse(ranking.endsAt)
      function tick() {
        const remaining = Math.max(0, end - Date.now() - offset)
        if (remaining > 300000) {
          element('ranking-period').textContent = 'Cierre: ' + calendar.format(end) + ' a medianoche (Bogotá).'
          return
        }
        const seconds = Math.ceil(remaining / 1000)
        element('ranking-period').textContent = 'La semana termina en ' + Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0')
        if (!seconds && !loading) { clearInterval(clock); void loadRanking() }
      }
      tick()
      clock = setInterval(tick, 1000)
    })
  }

  for (const control of moveControls) control.addEventListener('click', event => {
    if (movePending()) {
      event.preventDefault()
      event.stopPropagation()
    }
  }, { capture: true })
  element('open-offline').addEventListener('click', () => show('offline'))
  element('open-online').addEventListener('click', () => lobby())
  element('open-activity').addEventListener('click', () => lobby())
  element('open-friends').addEventListener('click', () => {
    lobby()
    void run(current => start('private', current))
  })
  element('open-ranking').addEventListener('click', () => {
    if (mode !== 'ranking') show('ranking')
    clearInterval(clock)
    if (!loading) {
      element('ranking-period').textContent = 'Cargando clasificación…'
      void loadRanking()
    }
  })
  element('refresh-rooms').addEventListener('click', () => lobby('', { invitationId: requestedRoomId, resumeId: roomToRestore }))
  element('find-room').addEventListener('click', () => run(current => start('public', current)))
  element('create-private').addEventListener('click', () => run(current => start('private', current)))
  element('friend-filter').addEventListener('input', filterFriends)
  element('retry-friends').addEventListener('click', () => void loadFriends())
  element('player-search').addEventListener('submit', event => {
    event.preventDefault()
    const query = element('player-query').value.trim()
    if (query.length < 2 || room?.status !== 'waiting' || !room.host) return
    const id = room.id
    const ticket = generation
    const output = element('player-search-status')
    const submit = event.currentTarget.querySelector('button')
    submit.disabled = true
    element('players-found').replaceChildren()
    output.hidden = false
    output.textContent = 'Buscando jugadores…'
    const current = () => ticket === generation && room?.id === id && room.status === 'waiting'
    void request('/rooms/' + id + '/players', { query }).then(({ friends }) => {
      if (!current()) return
      output.hidden = friends.length > 0
      output.textContent = 'No encontramos jugadores con ese nombre.'
      renderFriends(friends, 'players-found')
    }).catch(() => {
      if (current()) output.textContent = 'No se pudo buscar. Vuelve a intentarlo.'
    }).finally(() => { submit.disabled = false })
  })
  element('leave-waiting').addEventListener('click', () => run(async current => {
    await request(url('/close'), {})
    if (current()) lobby()
  }))
  element('reaction-buttons').replaceChildren(...emojis.map(([emoji, label]) => {
    const control = actionButton(emoji, () => run(async current => {
      const { reactions } = await request(url('/reactions'), { emoji })
      if (current()) displayRoom({ ...room, reactions })
    }))
    control.setAttribute('aria-label', label)
    return control
  }))
  element('table-chat-form').addEventListener('submit', event => {
    event.preventDefault()
    const input = element('table-message')
    const text = input.value.trim()
    if (!room || room.status !== 'playing' || !Array.isArray(room.chat) || !text) return
    void run(async current => {
      if (chatDraft?.text !== text) chatDraft = { text, messageId: crypto.randomUUID() }
      const { room: updated } = await request(url('/chat'), chatDraft)
      if (current()) {
        chatDraft = undefined
        if (input.value.trim() === text) input.value = ''
        displayRoom(updated)
      }
    })
  })
  element('request-rematch').addEventListener('click', () => run(async current => {
    if (!room?.rematch || room.status !== 'finished') return
    if (room.rematch.roomId) {
      const { room: next } = await request('/rooms/' + room.rematch.roomId)
      if (current()) displayRoom(next)
    } else {
      const { room: updated } = await request(url('/rematch'), {})
      if (current()) displayRoom(updated)
    }
  }))
  let readingAvailability = false
  async function readAvailability() {
    if (readingAvailability || mode !== 'offline' || !element('table').hidden) return
    readingAvailability = true
    const ticket = generation
    try {
      const { rooms } = await request('/rooms')
      if (ticket === generation) onRooms(rooms)
    } catch { /* Offline play remains available and the lobby reports connection failures. */ }
    finally { readingAvailability = false }
  }
  show('offline')
  const resumeId = android ? new URLSearchParams(location.search).get('resume') : null
  const whenReady = action => {
    if (!android || nativePort) queueMicrotask(action)
    else window.addEventListener('novvin-board-ready', action, { once: true })
  }
  if (resumeId && boardRoomId.test(resumeId)) {
    whenReady(() => {
      if (mode === 'offline' && element('table').hidden) lobby('', { resumeId: resumeId.toLowerCase() })
    })
  } else if (invitation && boardRoomId.test(invitation)) whenReady(() => {
    if (mode === 'offline' && element('table').hidden) lobby('', { invitationId: invitation.toLowerCase() })
  })
  else whenReady(readAvailability)
  function refreshOnReturn() {
    if (document.hidden) return
    if (mode === 'online' && !reconciling) {
      clearTimeout(timer)
      void poll()
    } else if (mode === 'offline') void readAvailability()
  }
  document.addEventListener('visibilitychange', refreshOnReturn)
  window.addEventListener('online', refreshOnReturn)

  return {
    mode: () => mode,
    lobby,
    renderPlayers: (currentRoom, turn, label, color = seat => seat) => {
      const panel = element('opponents')
      panel.hidden = !currentRoom
      if (!currentRoom) { panel.replaceChildren(); return }
      const roster = currentRoom.players || Array.from({ length: currentRoom.count ?? currentRoom.capacity }, (_, seat) => ({ seat }))
      panel.replaceChildren(...roster.map(player => {
        const row = document.createElement('article')
        row.className = 'match-player'
        row.dataset.turn = String(player.seat === turn)
        row.dataset.color = String(color(player.seat))
        const self = currentRoom.seat === player.seat
        const name = player.name || (self ? 'Tú' : 'Jugador ' + (player.seat + 1))
        const identity = document.createElement('div')
        identity.className = 'player-identity'
        const summary = document.createElement('div')
        const title = document.createElement('strong')
        title.textContent = self && player.name ? name + ' (tú)' : name
        const role = document.createElement('small')
        role.textContent = label(player.seat) + (player.seat === turn ? ' · En turno' : '')
        summary.append(title, role)
        identity.append(personLabel(name), summary)
        row.append(identity)
        if (!self && player.id && android && nativePort) {
          const actions = document.createElement('div')
          actions.className = 'player-actions'
          for (const [action, text] of [['profile', 'Perfil'], ['chat', 'Chat y llamadas']]) {
            actions.append(actionButton(text, () => nativePort.postMessage(JSON.stringify({
              action, opponentId: player.id, roomId: currentRoom.id,
            }))))
          }
          row.append(actions)
        }
        return row
      }))
    },
    move,
  }
}
