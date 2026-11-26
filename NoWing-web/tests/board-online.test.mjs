import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import test from 'node:test'

const require = createRequire(import.meta.url)
const { JSDOM } = createRequire(require.resolve('jest-environment-jsdom'))('jsdom')
const markup = await readFile(new URL('../public/board-games/board-games.html', import.meta.url), 'utf8')
const settle = () => new Promise(resolve => setImmediate(resolve))
const malformedPayload = Symbol('malformed native payload')
const waitingRoom = {
  id: '00000000-0000-4000-8000-000000000001',
  revision: 1,
  game: 'chess',
  visibility: 'private',
  status: 'waiting',
  count: 1,
  capacity: 2,
  seat: 0,
  host: true,
}

async function openLobby(t, respond, { resumeId, inviteId, delayPort = false, onRoom = () => {} } = {}) {
  const query = new URLSearchParams({ mode: 'android' })
  if (resumeId) query.set('resume', resumeId)
  if (inviteId) query.set('invite', inviteId)
  const dom = new JSDOM(markup, {
    url: 'https://appassets.androidplatform.net/board-games.html?' + query,
    pretendToBeVisual: true,
  })
  t.mock.timers.enable({ apis: ['setTimeout'] })
  for (const key of ['window', 'document', 'location', 'Event']) {
    Object.defineProperty(globalThis, key, { value: dom.window[key], configurable: true })
  }
  t.after(() => {
    for (const key of ['window', 'document', 'location', 'Event']) delete globalThis[key]
    dom.window.close()
  })
  const makePort = () => ({
    postMessage(message) {
      const request = JSON.parse(message)
      Promise.resolve(respond(request)).then(response => {
        if (response === undefined) return
        const reply = response === malformedPayload ? { payload: '{' } : response instanceof Error
          ? { error: response.message } : { payload: JSON.stringify(response) }
        try { this.onmessage({ data: JSON.stringify({ id: request.id, ...reply }) }) }
        catch (error) { wireErrors.push(error) }
      })
    },
    close() {},
  })
  const wireErrors = []
  const { onlineBoard } = await import('../public/board-games/board-online.js?' + encodeURIComponent(t.name))
  const connectPort = () => window.dispatchEvent(new window.MessageEvent('message', { data: 'novvin-board', ports: [makePort()] }))
  if (!delayPort) connectPort()
  const controller = onlineBoard({
    selection: () => ({ kind: 'chess', capacity: 2 }),
    onRoom,
    clearBoard() {},
  })
  if (!resumeId && !inviteId) controller.lobby()
  await settle()
  return {
    controller,
    wireErrors,
    async reconnect() {
      connectPort()
      await settle()
    },
    get: id => document.getElementById(id),
    async click(id) {
      document.getElementById(id).click()
      await settle()
    },
    async tick(ms) {
      t.mock.timers.tick(ms)
      await settle()
    },
  }
}

test('an Android notice waits for the native channel and presents the specified invitation for consent', async t => {
  const invited = { ...waitingRoom, game: 'gomoku', invited: true, seat: -1, host: false }
  const unrelated = { ...invited, id: '00000000-0000-4000-8000-000000000002', game: 'ludo' }
  const played = { ...invited, count: 2, seat: 1, status: 'playing', actions: [], reactions: [] }
  const requests = []
  const opened = []
  const lobby = await openLobby(t, ({ path, method }) => {
    requests.push([method, path])
    if (path === '/rooms') return { rooms: [unrelated, invited] }
    if (path === `/rooms/${invited.id}/join`) return { room: played }
  }, { inviteId: invited.id, delayPort: true, onRoom: room => opened.push(room.id) })
  assert.deepEqual(requests, [])
  await lobby.reconnect()
  assert.equal(lobby.get('online').hidden, false)
  const row = lobby.get('rooms').firstElementChild
  assert.match(row.textContent, /Gomoku/)
  assert.equal(row.dataset.requested, 'true')
  assert.equal(document.activeElement, row.querySelector('button'))
  assert.deepEqual(requests, [['GET', '/rooms']])
  await lobby.tick(5000)
  assert.equal(document.activeElement, row.querySelector('button'))
  row.querySelector('button').click()
  await settle()
  assert.deepEqual(requests.at(-1), ['POST', `/rooms/${invited.id}/join`])
  assert.deepEqual(opened, [invited.id])
})

test('an unavailable board invitation leaves the regular lobby usable', async t => {
  const unrelated = { ...waitingRoom, id: '00000000-0000-4000-8000-000000000002' }
  const lobby = await openLobby(t, ({ path }) => {
    if (path === '/rooms') return { rooms: [unrelated] }
  }, { inviteId: waitingRoom.id })
  assert.match(lobby.get('online-status').textContent, /invita.*ya no est/i)
  assert.equal(lobby.get('room-controls').hidden, false)
  assert.match(lobby.get('rooms').textContent, /Ajedrez/)
  await lobby.click('open-online')
  assert.equal(lobby.get('online-status').textContent, '')
})

test('opening the same board notice after accepting reads the match without another join', async t => {
  const accepted = { ...waitingRoom, game: 'reversi', seat: 1, count: 2, invited: false, status: 'playing', actions: [], reactions: [] }
  const requests = []
  const lobby = await openLobby(t, ({ path, method }) => {
    requests.push([method, path])
    return path === '/rooms' ? { rooms: [accepted] } : { room: accepted }
  }, { inviteId: accepted.id })
  const button = lobby.get('rooms').querySelector('button')
  assert.equal(button.textContent, 'Volver a la sala')
  button.click()
  await settle()
  assert.deepEqual(requests, [['GET', '/rooms'], ['GET', `/rooms/${accepted.id}`]])
})

test('a waiting host can invite friends after a transient lookup failure at the same room revision', async t => {
  let reads = 0
  const lobby = await openLobby(t, ({ path, method }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms' || path === '/rooms/' + waitingRoom.id) return { room: waitingRoom }
    if (path === '/friends') {
      if (++reads === 1) return new Error('BOARD_UNAVAILABLE')
      return { friends: [{ id: 'friend', name: 'Ana' }] }
    }
  })
  await lobby.click('create-private')
  assert.equal(reads, 1)
  assert.equal(lobby.get('waiting').hidden, false)
  await lobby.tick(5000)
  assert.equal(reads, 2)
  assert.equal(lobby.get('friends').querySelector('button').getAttribute('aria-label'), 'Invitar a Ana')
})

test('a slow friends lookup leaves the waiting room free to open the table', async t => {
  let deliverFriends
  const slowFriends = new Promise(resolve => { deliverFriends = resolve })
  const playing = { ...waitingRoom, status: 'playing', revision: 2, count: 2, reactions: [] }
  const opened = []
  const lobby = await openLobby(t, ({ path, method }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms') return { room: waitingRoom }
    if (path === '/rooms/' + waitingRoom.id) return { room: playing }
    if (path === '/friends') return slowFriends
  }, { onRoom: room => opened.push(room.id) })
  await lobby.click('create-private')
  await lobby.tick(5000)
  assert.deepEqual(opened, [waitingRoom.id])
  assert.equal(lobby.get('waiting').hidden, true)
  deliverFriends({ friends: [{ id: 'friend', name: 'Ana' }] })
  await settle()
  assert.equal(lobby.get('friends').children.length, 0)
})

test('the host can close a room while friends are loading', async t => {
  let closed = false
  const lobby = await openLobby(t, ({ path, method }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms') return { room: waitingRoom }
    if (path === '/rooms/' + waitingRoom.id + '/close') { closed = true; return {} }
  })
  await lobby.click('create-private')
  await lobby.click('leave-waiting')
  assert.equal(closed, true)
  assert.equal(lobby.get('waiting').hidden, true)
  assert.equal(lobby.get('room-controls').hidden, false)
})

test('a host can retry a failed friends lookup without leaving the room', async t => {
  let attempts = 0
  const lobby = await openLobby(t, ({ path, method }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms') return { room: waitingRoom }
    if (path === '/friends') {
      if (++attempts === 1) return new Error('BOARD_UNAVAILABLE')
      return { friends: [{ id: 'friend', name: 'Ana' }] }
    }
  })
  await lobby.click('create-private')
  assert.match(lobby.get('friends-empty').textContent, /No pudimos cargar tus amistades/)
  await lobby.click('retry-friends')
  assert.equal(attempts, 2)
  assert.equal(lobby.get('friends').querySelector('button').getAttribute('aria-label'), 'Invitar a Ana')
  assert.equal(lobby.get('retry-friends').hidden, true)
})

test('returning from the Android opponent profile opens the same table and shows participant names', async t => {
  const players = [{ id: 'own', seat: 0, name: 'Ana' }, { id: 'opponent', seat: 1, name: 'Bruno' }]
  const playing = { ...waitingRoom, revision: 2, status: 'playing', count: 2, players, reactions: [] }
  const opened = []
  const lobby = await openLobby(t, ({ path }) => {
    if (path === '/rooms') return { rooms: [] }
    if (path === '/rooms/' + waitingRoom.id) return { room: playing }
  }, { resumeId: waitingRoom.id, onRoom: room => opened.push(room) })
  assert.deepEqual(opened.map(room => room.id), [waitingRoom.id])
  lobby.controller.renderPlayers(opened[0], 0, seat => ['Blancas', 'Negras'][seat])
  const panel = lobby.get('opponents')
  assert.match(panel.textContent, /Ana \(tú\).*Bruno/)
  assert.equal(panel.querySelectorAll('button').length, 2)
  assert.equal(panel.querySelector('[data-turn="true"] .player-identity strong').textContent, 'Ana (tú)')
})

test('Android retries the same room after a temporary error while returning from chat', async t => {
  const playing = { ...waitingRoom, revision: 2, status: 'playing', count: 2, reactions: [] }
  const requests = []
  const opened = []
  const roomPath = '/rooms/' + playing.id
  const lobby = await openLobby(t, ({ path, method }) => {
    requests.push([method, path])
    if (path !== roomPath) return { rooms: [] }
    if (requests.length === 1) return new Error('BACKEND_UNAVAILABLE')
    if (requests.length === 2) return new Error('BOARD_UNAVAILABLE')
    return { room: playing }
  }, { resumeId: playing.id, onRoom: room => opened.push(room) })
  assert.match(lobby.get('online-status').textContent, /Reconectando con la mesa/)
  assert.deepEqual(opened, [])
  await lobby.tick(5000)
  assert.deepEqual(opened, [])
  await lobby.click('refresh-rooms')
  assert.deepEqual(opened, [playing])
  assert.deepEqual(requests, Array.from({ length: 3 }, () => ['GET', roomPath]))
})

test('unavailable Android resume rooms give a durable explanation and leave the lobby usable', async t => {
  const other = { ...waitingRoom, id: '00000000-0000-4000-8000-000000000002' }
  let reads = 0
  const lobby = await openLobby(t, ({ path }) => {
    if (path === '/rooms/' + waitingRoom.id) {
      reads++
      return new Error('BOARD_ROOM_NOT_FOUND')
    }
    return { rooms: [other] }
  }, { resumeId: waitingRoom.id })
  await lobby.tick(5000)
  assert.equal(reads, 1)
  assert.match(lobby.get('online-status').textContent, /Esta sala ya no está disponible/)
  assert.equal(lobby.get('room-controls').hidden, false)
  assert.match(lobby.get('rooms').textContent, /Volver a la sala/)
})

test('restored connectivity checks the active table immediately and never replays a move', async t => {
  const playing = { ...waitingRoom, revision: 2, status: 'playing', count: 2, reactions: [] }
  const requests = []
  const opened = []
  let available = true
  let latest = playing
  const roomPath = '/rooms/' + playing.id
  const lobby = await openLobby(t, ({ path, method }) => {
    requests.push([method, path])
    if (path === roomPath) return available ? { room: latest } : new Error('BOARD_UNAVAILABLE')
    return { rooms: [] }
  }, { resumeId: playing.id, onRoom: room => opened.push(room.revision) })
  available = false
  await lobby.tick(5000)
  assert.deepEqual(opened, [2])
  assert.equal(lobby.get('online-status').hidden, true)
  assert.equal(lobby.get('table-connection').hidden, false)
  latest = { ...playing, revision: 3 }
  available = true
  window.dispatchEvent(new window.Event('online'))
  await settle()
  assert.deepEqual(opened, [2, 3])
  assert.equal(lobby.get('table-connection').hidden, true)
  assert.deepEqual(requests, Array.from({ length: 3 }, () => ['GET', roomPath]))
})

test('leaving Android recovery for local play discards a late resume reply', async t => {
  const playing = { ...waitingRoom, revision: 2, status: 'playing', count: 2, reactions: [] }
  let deliver
  const response = new Promise(resolve => { deliver = resolve })
  const opened = []
  const lobby = await openLobby(t, ({ path }) => {
    return path === '/rooms/' + playing.id ? response : { rooms: [] }
  }, { resumeId: playing.id, onRoom: room => opened.push(room) })
  await lobby.click('open-offline')
  deliver({ room: playing })
  await settle()
  assert.equal(lobby.controller.mode(), 'offline')
  assert.deepEqual(opened, [])
})

test('a slow move is announced while Android waits for the server response', async t => {
  const playing = { ...waitingRoom, revision: 2, status: 'playing', count: 2, actions: [], reactions: [] }
  const updated = { ...playing, revision: 3 }
  const opened = []
  let acknowledge
  const reply = new Promise(resolve => { acknowledge = resolve })
  const lobby = await openLobby(t, ({ path, method }) => {
    if (method === 'POST') return reply
    return path === '/rooms/' + playing.id ? { room: playing } : { rooms: [] }
  }, { resumeId: playing.id, onRoom: room => opened.push(room.revision) })
  const move = lobby.controller.move({ type: 'move', from: 'e2', to: 'e4' })
  await settle()
  assert.match(lobby.get('table-connection').textContent, /Enviando la jugada/)
  assert.equal(lobby.get('board').getAttribute('aria-disabled'), 'true')
  acknowledge({ room: updated })
  await move
  assert.deepEqual(opened, [2, 3])
  assert.equal(lobby.get('table-connection').hidden, true)
  assert.equal(lobby.get('board').hasAttribute('aria-disabled'), false)
})

test('a timed-out move stays locked while recovery reads fail and is never sent again by the client', async t => {
  const playing = { ...waitingRoom, game: 'ludo', revision: 2, status: 'playing', count: 2, actions: [], reactions: [] }
  const updated = { ...playing, revision: 3, actions: [{ type: 'roll', value: 6 }] }
  const requests = []
  const opened = []
  let deliver
  const check = new Promise(resolve => { deliver = resolve })
  const roomPath = '/rooms/' + playing.id
  const lobby = await openLobby(t, ({ path, method, body }) => {
    requests.push({ path, method, body })
    if (method === 'POST') return
    if (path !== roomPath) return { rooms: [] }
    if (requests.length === 1) return { room: playing }
    if (requests.length === 3) return new Error('BACKEND_UNAVAILABLE')
    return check
  }, { resumeId: playing.id, onRoom: room => opened.push(room.revision) })
  const roll = lobby.controller.move({ type: 'roll' })
  await lobby.tick(12000)
  await roll
  assert.match(lobby.get('table-connection').textContent, /Verificando la jugada/)
  await lobby.controller.move({ type: 'roll' })
  window.dispatchEvent(new window.Event('online'))
  await settle()
  await lobby.controller.move({ type: 'roll' })
  assert.deepEqual(requests.map(({ method }) => method), ['GET', 'POST', 'GET', 'GET'])
  assert.equal(requests[1].body.revision, 2)
  deliver({ room: updated })
  await settle()
  assert.deepEqual(opened, [2, 3])
  assert.equal(lobby.get('table-connection').hidden, true)
})

test('an unchanged server snapshot lets the player retry explicitly after a lost response', async t => {
  const playing = { ...waitingRoom, revision: 2, status: 'playing', count: 2, actions: [], reactions: [] }
  const actions = []
  const lobby = await openLobby(t, ({ path, method, body }) => {
    if (method === 'POST') {
      actions.push(body)
      return actions.length === 1 ? new Error('BOARD_UNAVAILABLE') : { room: { ...playing, revision: 3 } }
    }
    return path === '/rooms/' + playing.id ? { room: playing } : { rooms: [] }
  }, { resumeId: playing.id })
  await lobby.controller.move({ type: 'move', from: 'e2', to: 'e4' })
  await settle()
  assert.equal(actions.length, 1)
  assert.match(lobby.get('table-connection').textContent, /volver a intentar la jugada/)
  await lobby.controller.move({ type: 'move', from: 'e2', to: 'e4' })
  assert.deepEqual(actions.map(action => action.revision), [2, 2])
  assert.equal(lobby.get('table-connection').hidden, true)
})

test('a server revision conflict immediately fetches the updated table without replaying the action', async t => {
  const playing = { ...waitingRoom, revision: 2, status: 'playing', count: 2, actions: [], reactions: [] }
  const opened = []
  const requests = []
  const roomPath = '/rooms/' + playing.id
  const lobby = await openLobby(t, ({ path, method }) => {
    requests.push([method, path])
    if (method === 'POST') return new Error('BOARD_REVISION_CHANGED')
    if (path === roomPath) return { room: requests.length === 1 ? playing : { ...playing, revision: 3 } }
    return { rooms: [] }
  }, { resumeId: playing.id, onRoom: room => opened.push(room.revision) })
  await lobby.controller.move({ type: 'move', from: 'e2', to: 'e4' })
  await settle()
  assert.deepEqual(opened, [2, 3])
  assert.deepEqual(requests, [['GET', roomPath], ['POST', roomPath + '/actions'], ['GET', roomPath]])
})

test('the friends shortcut makes a private room and filters invitations by name', async t => {
  let created
  const lobby = await openLobby(t, ({ path, method, body }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms') { created = body; return { room: waitingRoom } }
    if (path === '/friends') return { friends: [{ id: 'friend', name: 'Ángela' }] }
  })
  await lobby.click('open-friends')
  assert.equal(created.visibility, 'private')
  assert.equal(lobby.get('hub').hidden, true)
  assert.equal(lobby.get('waiting').hidden, false)
  const search = lobby.get('friend-filter')
  search.value = 'angela'
  search.dispatchEvent(new window.Event('input'))
  assert.equal(lobby.get('friends').firstElementChild.hidden, false)
  search.value = 'bruno'
  search.dispatchEvent(new window.Event('input'))
  assert.equal(lobby.get('friends').firstElementChild.hidden, true)
  assert.equal(lobby.get('friends-empty').hidden, false)
})

test('retrying after a lost native reply reuses the room creation request ID', async t => {
  const requestIds = []
  const lobby = await openLobby(t, ({ path, method, body }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms') {
      requestIds.push(body.requestId)
      if (requestIds.length === 2) return { room: { ...waitingRoom, id: body.requestId } }
    }
    if (path === '/friends') return { friends: [] }
  })
  await lobby.click('create-private')
  await lobby.tick(12000)
  await lobby.click('create-private')
  assert.equal(requestIds.length, 2)
  assert.equal(requestIds[0], requestIds[1])
  assert.equal(lobby.get('waiting').hidden, false)
})

test('a malformed native reply releases the action and a retry keeps the private room request ID', async t => {
  const ids = []
  const lobby = await openLobby(t, ({ path, method, body }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms') {
      ids.push(body.requestId)
      return ids.length === 1 ? malformedPayload : { room: { ...waitingRoom, id: body.requestId } }
    }
    if (path === '/friends') return { friends: [] }
  })
  await lobby.click('create-private')
  await lobby.click('create-private')
  assert.equal(ids.length, 2)
  assert.equal(ids[0], ids[1])
  assert.equal(lobby.get('waiting').hidden, false)
  assert.deepEqual(lobby.wireErrors, [])
})

test('replacing the native channel releases a pending action immediately', async t => {
  const ids = []
  const lobby = await openLobby(t, ({ path, method, body }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms') {
      ids.push(body.requestId)
      if (ids.length === 2) return { room: { ...waitingRoom, id: body.requestId } }
    }
    if (path === '/friends') return { friends: [] }
  })
  await lobby.click('create-private')
  await lobby.reconnect()
  await lobby.click('create-private')
  assert.equal(ids.length, 2)
  assert.equal(ids[0], ids[1])
  assert.equal(lobby.get('waiting').hidden, false)
})

test('a room closed by another player returns the user to usable lobby controls', async t => {
  const closed = { ...waitingRoom, status: 'closed', revision: 2 }
  let entered = false
  const lobby = await openLobby(t, ({ path, method }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: entered ? [closed] : [] }
    if (path === '/rooms') {
      entered = true
      return { room: waitingRoom }
    }
    if (path === '/rooms/' + waitingRoom.id) return { room: closed }
    if (path === '/friends') return { friends: [] }
  })
  await lobby.click('create-private')
  await lobby.tick(5000)
  assert.equal(lobby.get('waiting').hidden, true)
  assert.equal(lobby.get('room-controls').hidden, false)
  assert.match(lobby.get('online-status').textContent, /La partida se cerró/)
  assert.doesNotMatch(lobby.get('rooms').textContent, /Volver a la sala/)
})

test('an unavailable room can be left without restarting the game screen', async t => {
  const lobby = await openLobby(t, ({ path, method }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: [] }
    if (path === '/rooms') return { room: waitingRoom }
    if (path === '/rooms/' + waitingRoom.id) return new Error('BOARD_ROOM_NOT_FOUND')
    if (path === '/friends') return { friends: [] }
  })
  await lobby.click('create-private')
  await lobby.tick(5000)
  assert.equal(lobby.get('room-controls').hidden, false)
  assert.match(lobby.get('online-status').textContent, /Esta sala ya no está disponible/)
})

test('opening a recovered room from the lobby completes the previous creation', async t => {
  const ids = []
  let saved
  const lobby = await openLobby(t, ({ path, method, body }) => {
    if (path === '/rooms' && method === 'GET') return { rooms: saved ? [saved] : [] }
    if (path === '/rooms') {
      ids.push(body.requestId)
      saved = { ...waitingRoom, id: body.requestId }
      if (ids.length > 1) return { room: saved }
    }
    if (path === '/rooms/' + saved?.id) return { room: saved }
    if (path === '/friends') return { friends: [] }
  })
  await lobby.click('create-private')
  await lobby.tick(12000)
  await lobby.click('refresh-rooms')
  lobby.get('rooms').querySelector('button').click()
  await settle()
  assert.equal(lobby.get('waiting').hidden, false)
  await lobby.click('open-online')
  await lobby.click('create-private')
  assert.equal(ids.length, 2)
  assert.notEqual(ids[0], ids[1])
})
