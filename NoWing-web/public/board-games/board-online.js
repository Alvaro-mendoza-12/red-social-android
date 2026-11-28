const element = id => document.getElementById(id)
const android = new URLSearchParams(location.search).get('mode') === 'android'
const invitation = new URLSearchParams(location.search).get('invite')
const boardRoomId = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
const requests = new Map()
let nextRequest = 0
let nativePort
let webToken
let refresh
const requestTimeout = 12000

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

async function fetchJson(path, options) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), requestTimeout)
  try {
    const response = await fetch(path, { ...options, signal: controller.signal })
    return { status: response.status, ok: response.ok, payload: response.status === 401 ? null : await response.json() }
  } catch (error) {
    if (controller.signal.aborted) throw new Error('BOARD_UNAVAILABLE')
    throw error
  } finally {
    clearTimeout(timer)
  }
}

async function request(path, body, retry = true) {
  if (android) {
    if (!nativePort) throw new Error('BOARD_UNAVAILABLE')
    return new Promise((resolve, reject) => {
      const id = ++nextRequest
      const timer = setTimeout(() => {
        takeRequest(id)?.reject(new Error('BOARD_UNAVAILABLE'))
      }, requestTimeout)
      requests.set(id, { resolve, reject, timer })
      try { nativePort.postMessage(JSON.stringify({ id, method: body ? 'POST' : 'GET', path, body })) }
      catch { takeRequest(id)?.reject(new Error('BOARD_UNAVAILABLE')) }
    })
  }
  if (!webToken) {
    refresh ??= fetchJson('/v1/auth/web-session/refresh', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Novvin-Web-Session': 'web-v1' },
      body: JSON.stringify({ client: 'web' }),
    }).then(({ status, ok, payload }) => {
      if (!ok) throw new Error(status === 401 ? 'BOARD_SIGN_IN' : 'BOARD_UNAVAILABLE')
      if (!payload.accessToken) throw new Error('BOARD_SIGN_IN')
      webToken = payload.accessToken
    }).finally(() => { refresh = null })
    await refresh
  }
  const { status, ok, payload } = await fetchJson('/v1/data/playmatch/board-games' + path, {
    method: body ? 'POST' : 'GET',
    headers: { Authorization: 'Bearer ' + webToken, 'Content-Type': 'application/json', 'X-Novvin-Board-Protocol': '2' },
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  })
  if (status === 401 && retry) {
    webToken = null
    return request(path, body, false)
  }
  if (status === 401) throw new Error('BOARD_SIGN_IN')
  if (!ok) throw new Error(payload.error || 'BOARD_UNAVAILABLE')
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
  BOARD_REACTION_INVALID: 'Solo puedes reaccionar mientras la partida está en juego.',
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
const checkingJoin = 'Verificando tu entrada a la mesa…'
const unconfirmedJoin = 'No pudimos confirmar tu entrada. Si la sala sigue disponible, inténtalo de nuevo.'
const roomKey = (visibility, game, capacity, minutes) => `${visibility}:${game}:${capacity}:${minutes}`
const rematchMessages = {
  saving: 'Guardando tu respuesta…',
  checking: 'Verificando si se guardó tu respuesta…',
  opening: 'Abriendo la revancha…',
  unavailable: 'La revancha ya no está disponible.',
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
  let rankingTimer
  let searchTimer
  let searchVersion = 0
  let generation = 0
  let actionVersion = 0
  let loading = false
  let reconciling = false
  let seenReactions = new Set()
  let reactionSending = false
  let rivalReactionTimer
  let sentReactionTimer
  let friendsLoadedFor
  let friendsLoadingFor
  let lobbyNotice = ''
  let requestedRoomId = null
  let roomRecovery = null
  let moveStatus = null
  let roomsStamp
  let focusedInvitation = false
  let chatDraft
  let chatStamp
  let rematchPhase = ''
  let rematchNotice = ''
  const invited = new Set()
  const pendingCreations = new Map()
  const url = (suffix = '') => '/rooms/' + room.id + suffix
  const moveControls = [element('board'), document.querySelector('.turn-controls')]
  const roomButtons = ['find-room', 'create-private', 'refresh-rooms'].map(element)
  const reactionButtons = element('reaction-buttons')
  const reactionStatus = element('reaction-status')
  const reactionSendStatus = element('reaction-send-status')
  const rematchControl = element('request-rematch')
  const chatForm = element('table-chat-form')
  const chatInput = element('table-message')
  const chatSend = chatForm.querySelector('button')
  const chatFeedback = element('table-chat-status')
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

  function setRoomBusy(busy) {
    element('room-controls').setAttribute('aria-busy', String(busy))
    for (const button of roomButtons) button.disabled = busy
    for (const button of element('rooms').querySelectorAll('button')) button.disabled = busy
  }

  function setReactionBusy(busy) {
    reactionSending = busy
    for (const button of reactionButtons.children) button.disabled = busy
  }

  function resetReactions() {
    clearTimeout(rivalReactionTimer)
    clearTimeout(sentReactionTimer)
    seenReactions.clear()
    reactionStatus.textContent = ''
    reactionSendStatus.textContent = ''
    setReactionBusy(false)
  }

  function show(next) {
    if (next !== mode) {
      generation++
      resetPlayerSearch()
      clearTimeout(timer)
      clearTimeout(rankingTimer)
      room = null
      roomRecovery = null
      moveStatus = null
      rematchPhase = ''
      rematchNotice = ''
      loading = false
      chatSend.disabled = false
      reconciling = false
      setRoomBusy(false)
      resetReactions()
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
    for (const id of ['online', 'ranking']) element('open-' + id).setAttribute('aria-pressed', String(mode === id))
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
      const open = actionButton(label, () => run(isCurrent => openListedRoom(item, open, isCurrent)))
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

  async function openListedRoom(item, button, isCurrent) {
    const joining = item.seat < 0
    const path = '/rooms/' + item.id
    lobbyNotice = ''
    status(joining ? (item.invited ? 'Aceptando invitación…' : 'Entrando a la mesa…') : 'Abriendo tu partida…')
    setRoomBusy(true)
    try {
      const { room: selected } = joining ? await request(path + '/join', {}) : await request(path)
      if (isCurrent()) displayRoom(selected)
    } catch (error) {
      if (!isCurrent()) return
      if (joining && connectionLost(error)) {
        lobby('', { invitationId: requestedRoomId, resumeId: item.id, verifyingJoin: true })
      } else if (['BOARD_ROOM_FULL', 'BOARD_ROOM_NOT_FOUND'].includes(error.message)) {
        lobby(messageFor(error), { invitationId: requestedRoomId })
      } else {
        lobbyNotice = messageFor(error)
        throw error
      }
    } finally {
      if (isCurrent()) {
        setRoomBusy(false)
        if (!room && button.isConnected) button.focus({ preventScroll: true })
      }
    }
  }

  function updateSocial(value) {
    const panel = element('table-chat')
    panel.hidden = !Array.isArray(value.chat)
    chatForm.hidden = value.status !== 'playing'
    if (Array.isArray(value.chat)) {
      if (chatDraft && value.chat.some(message => message.id === chatDraft.messageId && message.seat === value.seat)) {
        if (chatInput.value.trim() === chatDraft.text) chatInput.value = ''
        chatDraft = undefined
        chatFeedback.textContent = 'Mensaje enviado.'
      } else if (chatDraft && value.status === 'finished') {
        chatDraft = undefined
        chatFeedback.textContent = 'La partida terminó antes de que pudiéramos confirmar el mensaje.'
      }
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
  }

  function renderRematch() {
    const message = element('rematch-status')
    const data = room.rematch
    rematchControl.hidden = !data || room.status !== 'finished'
    if (rematchControl.hidden) {
      message.hidden = true
      return
    }
    const ready = Boolean(data.roomId)
    const alreadyVoted = data.seats.includes(room.seat)
    rematchControl.disabled = Boolean(rematchPhase) || (!ready && alreadyVoted)
    if (ready) rematchControl.textContent = 'Entrar a la revancha'
    else if (alreadyVoted) rematchControl.textContent = room.count > 2 ? 'Esperando a los demás' : 'Esperando al rival'
    else rematchControl.textContent = data.seats.length ? 'Aceptar revancha' : 'Solicitar revancha'
    const progress = ready ? 'La nueva mesa está lista.' : data.seats.length ? `${data.seats.length} de ${room.count} aceptaron la revancha.` : ''
    const feedback = rematchMessages[rematchPhase] || rematchNotice || progress
    if (message.textContent !== feedback) message.textContent = feedback
    message.hidden = !feedback
  }

  function showRivalReaction(value, enteringRoom) {
    if (value.status !== 'playing' || !Array.isArray(value.reactions)) return
    const current = new Set()
    let newest
    for (const reaction of value.reactions) {
      const key = `${reaction.seat}:${reaction.at}:${reaction.emoji}`
      current.add(key)
      if (!enteringRoom && reaction.seat !== value.seat && !seenReactions.has(key)) newest = reaction
    }
    seenReactions = current
    if (!newest) return
    const author = value.players?.find(player => player.seat === newest.seat)?.name
    reactionStatus.textContent = `${author || 'Jugador ' + (newest.seat + 1)} · ${newest.emoji}`
    if (!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      reactionStatus.animate?.([{ transform: 'translateY(5px)', opacity: .35 }, { transform: 'none', opacity: 1 }], { duration: 260, easing: 'ease-out' })
    }
    clearTimeout(rivalReactionTimer)
    rivalReactionTimer = setTimeout(() => { reactionStatus.textContent = '' }, 5000)
  }

  function displayRoom(value) {
    if (room?.id === value.id && value.revision < room.revision) return
    if (roomRecovery?.fromJoin) setRoomBusy(false)
    roomRecovery = null
    const key = roomKey(value.visibility, value.game, value.capacity, value.clockMinutes || 0)
    if (pendingCreations.get(key) === value.id) pendingCreations.delete(key)
    if (value.status === 'closed') {
      lobby('La partida se cerró.')
      return
    }
    const enteringRoom = room?.id !== value.id
    if (enteringRoom) {
      rematchPhase = ''
      rematchNotice = ''
      element('table-chat').open = false
      chatStamp = undefined
      chatDraft = undefined
      chatInput.value = ''
      chatFeedback.textContent = ''
      resetReactions()
    }
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
      updateSocial(value)
      renderRematch()
      if (value.status === 'finished') resetReactions()
      else showRivalReaction(value, enteringRoom)
      return
    }
    status('')
    element('waiting').hidden = false
    element('waiting-art').dataset.cover = value.game
    element('waiting-title').textContent = boardNames[value.game] + (value.visibility === 'private' ? ' · Sala privada' : ' · Sala pública')
    const waitingText = value.visibility === 'private'
      ? 'Invita a alguien y espera a que acepte la partida.'
      : 'Buscando rival. La partida comienza cuando alguien se una.'
    element('waiting-description').textContent = `${value.count} de ${value.capacity} jugadores. ${waitingText}` +
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
      const monitored = room?.id || roomRecovery?.id
      try {
        if (roomRecovery || room && (['waiting', 'playing'].includes(room.status) || room.status === 'finished' && room.rematch && !room.rematch.roomId)) {
          const { room: latest } = await request('/rooms/' + monitored)
          if (isCurrent() && (!room || latest.revision >= room.revision)) {
            if (moveStatus?.phase === 'verifying') {
              moveStatus = latest.revision === moveStatus.revision ? { ...moveStatus, phase: 'retry' } : null
            }
            if (rematchPhase === 'checking') {
              rematchPhase = ''
              const voted = latest.rematch?.seats.includes(latest.seat)
              if (!voted && !latest.rematch?.roomId) rematchNotice = 'No pudimos confirmar tu respuesta. Puedes volver a intentarlo.'
            } else if (latest.rematch?.roomId && !room?.rematch?.roomId) rematchNotice = ''
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
          if (roomRecovery?.fromJoin && error.message === 'BOARD_ROOM_NOT_FOUND') {
            lobby(unconfirmedJoin, { invitationId: requestedRoomId })
          } else if (((roomRecovery || verifyingMove) && authNeeded) || (monitored && error.message === 'BOARD_ROOM_NOT_FOUND')) lobby(messageFor(error))
          else if (verifyingMove) status(moveMessages.verifying)
          else if (rematchPhase === 'checking') {
            if (authNeeded) {
              rematchPhase = ''
              rematchNotice = messageFor(error)
              renderRematch()
            }
          }
          else if (roomRecovery?.fromJoin) status(checkingJoin)
          else status(roomRecovery || (monitored && connectionLost(error)) ? 'Reconectando con la mesa…' : messageFor(error))
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
    chatSend.disabled = true
    try { await task(isCurrent) }
    catch (error) {
      if (isCurrent()) status(messageFor(error))
    }
    finally {
      if (isCurrent()) {
        loading = false
        chatSend.disabled = false
      }
    }
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

  function lobby(notice = '', { invitationId = null, resumeId = null, verifyingJoin = false } = {}) {
    if (mode !== 'online') show('online')
    else {
      generation++
      resetPlayerSearch()
      clearTimeout(timer)
      loading = false
      chatSend.disabled = false
      reconciling = false
    }
    room = null
    moveStatus = null
    rematchPhase = ''
    rematchNotice = ''
    invited.clear()
    setRoomBusy(verifyingJoin)
    resetReactions()
    friendsLoadedFor = undefined
    friendsLoadingFor = undefined
    lobbyNotice = notice
    requestedRoomId = invitationId
    roomRecovery = resumeId ? { id: resumeId, fromJoin: verifyingJoin } : null
    roomsStamp = undefined
    focusedInvitation = false
    clearBoard()
    element('online-reactions').hidden = true
    element('waiting').hidden = true
    element('friends').replaceChildren()
    element('friends-empty').hidden = true
    element('retry-friends').hidden = true
    element('friend-filter-label').hidden = true
    element('friend-filter').value = ''
    element('rooms').hidden = false
    element('rooms').replaceChildren()
    element('lobby-summary').hidden = true
    element('room-controls').hidden = false
    status(notice || (verifyingJoin ? checkingJoin : resumeId ? 'Recuperando tu partida…' : 'Buscando salas…'))
    void poll()
  }

  async function start(visibility, isCurrent) {
    const { kind, capacity, clockMinutes = 0 } = selection()
    const key = roomKey(visibility, kind, capacity, clockMinutes)
    const privateRoom = visibility === 'private'
    lobbyNotice = ''
    status(privateRoom ? 'Abriendo la sala para invitar…' : 'Buscando una partida disponible…')
    setRoomBusy(true)
    // When the server creates a room, this request ID also identifies it.
    const requestId = pendingCreations.get(key) || crypto.randomUUID()
    pendingCreations.set(key, requestId)
    let result
    try {
      result = await request(privateRoom ? '/rooms' : '/match', { game: kind, capacity, visibility, requestId, ...(clockMinutes ? { clockMinutes } : {}) })
    } catch (error) {
      if (isCurrent() && ['BOARD_ROOM_LIMIT', 'BOARD_ROOM_INVALID'].includes(error.message)) {
        pendingCreations.delete(key)
      }
      if (isCurrent()) lobbyNotice = messageFor(error)
      throw error
    } finally {
      if (isCurrent()) setRoomBusy(false)
    }
    if (isCurrent()) {
      pendingCreations.delete(key)
      displayRoom(result.room)
    }
  }

  const calendar = new Intl.DateTimeFormat('es', { dateStyle: 'medium', timeZone: 'America/Bogota' })
  const closingWeek = 'La semana terminó. Actualizando la clasificación…'
  const countdownWindow = 5 * 60000
  const rankingRetryDelay = 5000

  function rankingStatus(message, seconds = null, retry = false) {
    const period = element('ranking-period')
    if (period.textContent !== message) period.textContent = message
    const countdown = element('ranking-countdown')
    countdown.hidden = seconds === null
    countdown.textContent = seconds === null ? '' : Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0')
    element('retry-ranking').hidden = !retry
  }

  function scheduleRanking(callback, delay) {
    clearTimeout(rankingTimer)
    rankingTimer = setTimeout(callback, delay)
  }

  function renderRanking(id, rows) {
    const list = element(id)
    list.hidden = !rows.length
    element(id + '-empty').hidden = Boolean(rows.length)
    list.replaceChildren(...rows.map(row => {
      const item = document.createElement('li')
      item.textContent = row.name + ' · ' + row.wins + (row.wins === 1 ? ' victoria' : ' victorias')
      return item
    }))
  }

  function loadRanking() {
    if (mode !== 'ranking' || loading || document.hidden) return
    clearTimeout(rankingTimer)
    return run(async isCurrent => {
      try {
        const ranking = await request('/ranking')
        if (!isCurrent()) return
        const end = Date.parse(ranking.endsAt)
        const duration = end - Date.parse(ranking.serverNow)
        if (!Number.isFinite(duration)) throw new Error('BOARD_UNAVAILABLE')
        if (duration <= 0) {
          rankingStatus(closingWeek)
          scheduleRanking(loadRanking, rankingRetryDelay)
          return
        }
        const receivedAt = performance.now()
        renderRanking('previous-podium', ranking.podium)
        renderRanking('weekly-top', ranking.top)

        function tick() {
          if (!isCurrent() || document.hidden) return
          const remaining = duration - (performance.now() - receivedAt)
          if (remaining <= 0) {
            rankingStatus(closingWeek)
            scheduleRanking(loadRanking, 0)
          } else if (remaining > countdownWindow) {
            rankingStatus('Cierre: ' + calendar.format(end) + ' a medianoche (Bogotá).')
            scheduleRanking(tick, remaining - countdownWindow)
          } else {
            const seconds = Math.ceil(remaining / 1000)
            rankingStatus('La semana termina en', seconds)
            scheduleRanking(tick, Math.max(1, remaining - (seconds - 1) * 1000))
          }
        }
        tick()
      } catch (error) {
        if (!isCurrent()) return
        const retry = connectionLost(error)
        rankingStatus(retry ? 'No se pudo actualizar la clasificación. Reintentando…' : messageFor(error), null, true)
        if (retry) scheduleRanking(loadRanking, rankingRetryDelay)
      }
    })
  }

  function refreshRanking() {
    if (loading) return
    rankingStatus('Actualizando la clasificación…')
    void loadRanking()
  }

  for (const control of moveControls) control.addEventListener('click', event => {
    if (movePending()) {
      event.preventDefault()
      event.stopPropagation()
    }
  }, { capture: true })
  function chooseOffline(value) {
    show('offline')
    const selector = element('mode')
    selector.value = value
    selector.dispatchEvent(new Event('change'))
    element('setup').scrollIntoView?.({ block: 'nearest' })
    element('setup').querySelector('[type="submit"]').focus({ preventScroll: true })
  }
  element('open-offline').addEventListener('click', () => chooseOffline('local'))
  element('open-bot').addEventListener('click', () => chooseOffline('bot'))
  element('open-online').addEventListener('click', () => lobby())
  element('open-activity').addEventListener('click', () => lobby())
  element('open-friends').addEventListener('click', () => {
    if (mode === 'online' && loading) return
    lobby()
    void run(current => start('private', current))
  })
  element('open-ranking').addEventListener('click', () => {
    if (mode !== 'ranking') {
      show('ranking')
      const title = element('ranking-title')
      title.focus({ preventScroll: true })
      title.scrollIntoView?.({ block: 'start' })
    }
    refreshRanking()
  })
  element('retry-ranking').addEventListener('click', () => {
    element('ranking-title').focus({ preventScroll: true })
    refreshRanking()
  })
  element('refresh-rooms').addEventListener('click', () => lobby('', { invitationId: requestedRoomId, resumeId: roomRecovery?.id }))
  element('find-room').addEventListener('click', () => run(current => start('public', current)))
  element('create-private').addEventListener('click', () => run(current => start('private', current)))
  element('friend-filter').addEventListener('input', filterFriends)
  element('retry-friends').addEventListener('click', () => void loadFriends())
  function resetPlayerSearch() {
    clearTimeout(searchTimer)
    searchVersion++
    element('players-found').replaceChildren()
    element('player-search-status').hidden = true
    element('player-query').value = ''
  }

  function searchPlayers(delay) {
    clearTimeout(searchTimer)
    const version = ++searchVersion
    const query = element('player-query').value.trim()
    const output = element('player-search-status')
    element('players-found').replaceChildren()
    output.hidden = true
    if (query.length < 2 || room?.status !== 'waiting' || room.visibility !== 'private' || !room.host) return
    const id = room.id
    const ticket = generation
    output.hidden = false
    output.textContent = 'Buscando jugadores…'
    const current = () => ticket === generation && version === searchVersion && room?.id === id && room.status === 'waiting'
    const send = async () => {
      if (!current()) return
      try {
        const { friends } = await request('/rooms/' + id + '/players', { query })
        if (!current()) return
        output.hidden = friends.length > 0
        output.textContent = 'No encontramos jugadores con ese nombre.'
        renderFriends(friends, 'players-found')
      } catch {
        if (current()) output.textContent = 'No se pudo buscar. Vuelve a intentarlo.'
      }
    }
    if (delay) searchTimer = setTimeout(send, delay)
    else void send()
  }

  element('player-query').addEventListener('input', () => searchPlayers(350))
  element('player-search').addEventListener('submit', event => {
    event.preventDefault()
    searchPlayers(0)
  })
  element('leave-waiting').addEventListener('click', () => run(async current => {
    await request(url('/close'), {})
    if (current()) lobby()
  }))
  async function sendReaction(emoji) {
    if (room?.status !== 'playing' || reactionSending) return
    const ticket = generation
    const roomId = room.id
    const isCurrent = () => ticket === generation && room?.id === roomId && room.status === 'playing'
    setReactionBusy(true)
    clearTimeout(sentReactionTimer)
    reactionSendStatus.textContent = 'Enviando reacción…'
    try {
      await request(url('/reactions'), { emoji })
      if (!isCurrent()) return
      reactionSendStatus.textContent = `Reacción enviada ${emoji}`
      sentReactionTimer = setTimeout(() => { reactionSendStatus.textContent = '' }, 5000)
    } catch (error) {
      if (isCurrent()) reactionSendStatus.textContent = connectionLost(error)
        ? 'No pudimos confirmar la reacción. Puedes volver a intentarlo.'
        : messageFor(error)
    } finally {
      if (isCurrent()) setReactionBusy(false)
    }
  }

  reactionButtons.replaceChildren(...emojis.map(([emoji, label]) => {
    const control = actionButton(emoji, () => void sendReaction(emoji))
    control.setAttribute('aria-label', label)
    return control
  }))
  async function submitChat(event) {
    event.preventDefault()
    const text = chatInput.value.trim()
    if (!room || room.status !== 'playing' || !Array.isArray(room.chat) || !text) return
    let verify = false
    await run(async current => {
      if (chatDraft?.text !== text) chatDraft = { text, messageId: crypto.randomUUID() }
      chatFeedback.textContent = 'Enviando mensaje…'
      try {
        const { room: updated } = await request(url('/chat'), chatDraft)
        if (current()) displayRoom(updated)
      } catch (error) {
        if (!current()) return
        verify = connectionLost(error)
        chatFeedback.textContent = verify ? 'No pudimos confirmar el mensaje. Puedes volver a enviarlo.' : messageFor(error)
      }
    })
    if (verify) refreshOnReturn()
  }
  chatForm.addEventListener('submit', event => void submitChat(event))
  async function requestRematch() {
    if (!room?.rematch || room.status !== 'finished' || rematchControl.disabled) return
    await run(async current => {
      const nextId = room.rematch.roomId
      rematchPhase = nextId ? 'opening' : 'saving'
      rematchNotice = ''
      renderRematch()
      try {
        const { room: result } = nextId ? await request('/rooms/' + nextId) : await request(url('/rematch'), {})
        if (!current()) return
        if (nextId && result.status === 'closed') {
          rematchPhase = 'unavailable'
          renderRematch()
          return
        }
        rematchPhase = ''
        displayRoom(result)
      } catch (error) {
        if (!current()) return
        if (!nextId && connectionLost(error)) rematchPhase = 'checking'
        else if (nextId && error.message === 'BOARD_ROOM_NOT_FOUND') rematchPhase = 'unavailable'
        else {
          rematchPhase = ''
          rematchNotice = messageFor(error)
        }
        renderRematch()
      }
    })
    if (rematchPhase === 'checking') refreshOnReturn()
  }

  rematchControl.addEventListener('click', () => void requestRematch())
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
    } else if (mode === 'ranking') void loadRanking()
    else if (mode === 'offline') void readAvailability()
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
