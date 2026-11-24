const element = id => document.getElementById(id)
const android = new URLSearchParams(location.search).get('mode') === 'android'
const requests = new Map()
let nextRequest = 0
let nativePort
let webToken
let refresh

if (android) window.addEventListener('message', event => {
  if (event.data !== 'novvin-board' || event.ports.length !== 1) return
  nativePort?.close()
  nativePort = event.ports[0]
  nativePort.onmessage = message => {
    const { id, payload, error } = JSON.parse(message.data)
    const pending = requests.get(id)
    if (!pending) return
    requests.delete(id)
    clearTimeout(pending.timer)
    if (error) pending.reject(new Error(error))
    else pending.resolve(JSON.parse(payload))
  }
})

async function request(path, body, retry = true) {
  if (android) {
    if (!nativePort) throw new Error('BOARD_UNAVAILABLE')
    return new Promise((resolve, reject) => {
      const id = ++nextRequest
      const timer = setTimeout(() => {
        requests.delete(id)
        reject(new Error('BOARD_UNAVAILABLE'))
      }, 12000)
      requests.set(id, { resolve, reject, timer })
      nativePort.postMessage(JSON.stringify({ id, method: body ? 'POST' : 'GET', path, body }))
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
    headers: { Authorization: 'Bearer ' + webToken, 'Content-Type': 'application/json' },
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

const names = { chess: 'Ajedrez', checkers: 'Damas', ludo: 'Ludo' }
const emojis = ['👏', '😄', '🔥', '🤝', '🎉', '😮']
const messages = {
  BOARD_SIGN_IN: 'Inicia sesión en Novvin y vuelve a abrir Juegos.',
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
}
const messageFor = error => messages[error.message] || 'No se pudo conectar con Juegos en línea. Inténtalo nuevamente.'
function actionButton(label, handler) {
  const button = document.createElement('button')
  button.type = 'button'
  button.textContent = label
  button.addEventListener('click', handler)
  return button
}

export function onlineBoard({ selection, onRoom, clearBoard }) {
  let room
  let mode = 'offline'
  let timer
  let clock
  let generation = 0
  let loading = false
  let reconciling = false
  let lastReaction = 0
  let friendsLoadedFor
  let lobbyNotice = ''
  const invited = new Set()
  const pendingCreations = new Map()
  const url = (suffix = '') => '/rooms/' + room.id + suffix
  const status = message => { element('online-status').textContent = message }

  function show(next) {
    if (next !== mode) {
      generation++
      clearTimeout(timer)
      clearInterval(clock)
      room = null
      loading = false
      reconciling = false
      lastReaction = 0
      friendsLoadedFor = undefined
      invited.clear()
      clearBoard()
    }
    mode = next
    element('setup').hidden = mode !== 'offline'
    element('online').hidden = mode !== 'online'
    element('ranking').hidden = mode !== 'ranking'
    element('online-reactions').hidden = true
    for (const id of ['offline', 'online', 'ranking']) element('open-' + id).setAttribute('aria-pressed', String(mode === id))
  }

  function displayRooms(rooms) {
    const chosen = selection().kind
    const visible = rooms.filter(item => item.status !== 'closed' && (item.game === chosen || item.invited || item.seat >= 0))
    element('rooms').replaceChildren(...visible.map(item => {
      const label = item.seat >= 0 ? item.status === 'finished' ? 'Ver resultado' : 'Volver a la sala' :
        item.invited ? 'Aceptar invitación' : 'Unirme'
      const row = document.createElement('div')
      row.className = 'room-row'
      const title = document.createElement('span')
      title.textContent = names[item.game] + ' · ' + item.count + '/' + item.capacity +
        (item.invited ? ' · Invitación' : item.visibility === 'private' ? ' · Privada' : ' · Pública')
      row.append(title, actionButton(label, () => run(async isCurrent => {
        const result = item.seat >= 0 ? await request('/rooms/' + item.id) : await request('/rooms/' + item.id + '/join', {})
        if (isCurrent()) await displayRoom(result.room, isCurrent)
      })))
      return row
    }))
    status(lobbyNotice || (visible.length ? '' : 'No hay salas de este juego disponibles.'))
  }

  async function displayRoom(value, isCurrent) {
    if (room?.id === value.id && value.revision < room.revision) return
    const key = value.game + ':' + value.capacity
    if (pendingCreations.get(key) === value.id) pendingCreations.delete(key)
    if (value.status === 'closed') {
      lobby('La partida se cerró.')
      return
    }
    const changed = room?.id !== value.id || room?.revision !== value.revision
    room = value
    element('rooms').hidden = true
    element('room-controls').hidden = true
    if (value.status !== 'waiting') {
      status('')
      element('waiting').hidden = true
      element('online-reactions').hidden = value.status !== 'playing'
      if (changed) onRoom(value)
      const reaction = value.reactions.at(-1)
      if (reaction && reaction.at > lastReaction && Date.now() - reaction.at < 30000) {
        lastReaction = reaction.at
        element('reaction-status').textContent = 'Jugador ' + (reaction.seat + 1) + ' · ' + reaction.emoji
      }
      return
    }
    status('')
    element('waiting').hidden = false
    element('waiting-title').textContent = names[value.game] + (value.visibility === 'private' ? ' · Sala privada' : ' · Sala pública')
    element('waiting-description').textContent = value.count + ' de ' + value.capacity + ' jugadores. La partida empieza al completar la sala.'
    if (!value.host || friendsLoadedFor === value.id) return
    const { friends } = await request('/friends')
    if (!isCurrent() || room?.id !== value.id || room.status !== 'waiting') return
    friendsLoadedFor = value.id
    element('friends').replaceChildren(...friends.map(friend => actionButton(
      invited.has(friend.id) ? 'Invitación enviada a ' + friend.name : 'Invitar a ' + friend.name,
      event => run(async current => {
        await request(url('/invite'), { recipientId: friend.id })
        if (!current()) return
        invited.add(friend.id)
        event.target.textContent = 'Invitación enviada a ' + friend.name
        event.target.disabled = true
      }),
    )))
  }

  async function poll() {
    if (mode !== 'online') return
    const ticket = generation
    const isCurrent = () => ticket === generation && !loading
    if (!document.hidden && !loading && !reconciling) {
      reconciling = true
      const monitored = room?.id
      try {
        if (room && ['waiting', 'playing'].includes(room.status)) {
          const { room: latest } = await request(url())
          if (isCurrent() && monitored === room?.id) await displayRoom(latest, isCurrent)
        } else if (!room) {
          const { rooms } = await request('/rooms')
          if (isCurrent() && !room) displayRooms(rooms)
        }
      } catch (error) {
        if (isCurrent()) {
          if (monitored && monitored === room?.id && error.message === 'BOARD_ROOM_NOT_FOUND') lobby(messageFor(error))
          else status(messageFor(error))
        }
      } finally {
        if (ticket === generation) reconciling = false
      }
    }
    if (ticket === generation) timer = setTimeout(poll, room?.status === 'playing' ? 1800 : 5000)
  }

  async function run(task) {
    if (loading) return
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

  function lobby(notice = '') {
    if (mode !== 'online') show('online')
    else {
      generation++
      clearTimeout(timer)
      loading = false
      reconciling = false
    }
    room = null
    invited.clear()
    lastReaction = 0
    friendsLoadedFor = undefined
    lobbyNotice = notice
    clearBoard()
    element('online-reactions').hidden = true
    element('reaction-status').textContent = ''
    element('waiting').hidden = true
    element('friends').replaceChildren()
    element('rooms').hidden = false
    element('rooms').replaceChildren()
    element('room-controls').hidden = false
    status(lobbyNotice || 'Buscando salas…')
    void poll()
  }

  async function start(visibility, isCurrent) {
    const { kind, capacity } = selection()
    const key = kind + ':' + capacity
    const privateRoom = visibility === 'private'
    // Private creation uses this ID as the room ID. Keep it until the server
    // confirms the write, so a lost reply cannot create a second room.
    const requestId = (privateRoom && pendingCreations.get(key)) || crypto.randomUUID()
    if (privateRoom) pendingCreations.set(key, requestId)
    let result
    try {
      result = await request(privateRoom ? '/rooms' : '/match', { game: kind, capacity, visibility, requestId })
    } catch (error) {
      if (privateRoom && isCurrent() && ['BOARD_ROOM_LIMIT', 'BOARD_ROOM_INVALID'].includes(error.message)) {
        pendingCreations.delete(key)
      }
      throw error
    }
    if (isCurrent()) await displayRoom(result.room, isCurrent)
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

  element('open-offline').addEventListener('click', () => show('offline'))
  element('open-online').addEventListener('click', () => lobby())
  element('open-ranking').addEventListener('click', () => {
    if (mode !== 'ranking') show('ranking')
    clearInterval(clock)
    if (!loading) {
      element('ranking-period').textContent = 'Cargando clasificación…'
      void loadRanking()
    }
  })
  element('refresh-rooms').addEventListener('click', () => lobby())
  element('find-room').addEventListener('click', () => run(current => start('public', current)))
  element('create-private').addEventListener('click', () => run(current => start('private', current)))
  element('leave-waiting').addEventListener('click', () => run(async current => {
    await request(url('/close'), {})
    if (current()) lobby()
  }))
  element('reaction-buttons').replaceChildren(...emojis.map(emoji => actionButton(emoji, () => run(async current => {
    const { reactions } = await request(url('/reactions'), { emoji })
    if (current()) await displayRoom({ ...room, reactions }, current)
  }))))
  show('offline')
  return {
    mode: () => mode,
    lobby,
    move: event => run(async current => {
      const { room: updated } = await request(url('/actions'), { ...event, revision: room.revision })
      if (current()) await displayRoom(updated, current)
    }),
  }
}
