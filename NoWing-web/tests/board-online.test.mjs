import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import test from 'node:test'

const require = createRequire(import.meta.url)
const { JSDOM } = createRequire(require.resolve('jest-environment-jsdom'))('jsdom')
const markup = await readFile(new URL('../public/board-games/board-games.html', import.meta.url), 'utf8')
const settle = () => new Promise(resolve => setImmediate(resolve))
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

async function openLobby(t, respond, { resumeId, onRoom = () => {} } = {}) {
  const dom = new JSDOM(markup, {
    url: 'https://appassets.androidplatform.net/board-games.html?mode=android' + (resumeId ? '&resume=' + resumeId : ''),
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
  const port = {
    postMessage(message) {
      const request = JSON.parse(message)
      const response = respond(request)
      if (response === undefined) return
      const reply = response instanceof Error ? { error: response.message } : { payload: JSON.stringify(response) }
      queueMicrotask(() => this.onmessage({ data: JSON.stringify({ id: request.id, ...reply }) }))
    },
    close() {},
  }
  const { onlineBoard } = await import('../public/board-games/board-online.js?' + encodeURIComponent(t.name))
  window.dispatchEvent(new window.MessageEvent('message', { data: 'novvin-board', ports: [port] }))
  const controller = onlineBoard({
    selection: () => ({ kind: 'chess', capacity: 2 }),
    onRoom,
    clearBoard() {},
  })
  if (!resumeId) controller.lobby()
  await settle()
  return {
    controller,
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
