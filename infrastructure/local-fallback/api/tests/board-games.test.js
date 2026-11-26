const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { once } = require('node:events')
const { test } = require('node:test')
const express = require('express')
const { PGlite } = require('@electric-sql/pglite')
const { Pool } = require('pg')
const { ensureBoardGameTables, registerBoardGameRoutes } = require('../src/board-games/routes')

const id = {
  Ana: '00000000-0000-4000-8000-000000000001',
  Bruno: '00000000-0000-4000-8000-000000000002',
  Carla: '00000000-0000-4000-8000-000000000003',
  blocked: '00000000-0000-4000-8000-000000000004',
}
const oldAna = '00000000-0000-4000-9000-000000000001'
const oldCarla = '00000000-0000-4000-9000-000000000003'

async function fixture() {
  const native = process.env.NOVVIN_BOARD_TEST_DATABASE_URL
  const db = native ? new Pool({ connectionString: native }) : new PGlite()
  const pool = native ? db : {
    query: (...args) => db.query(...args),
    connect: async () => ({ query: (...args) => db.query(...args), release() {} }),
  }
  await pool.query('CREATE TABLE fallback_users(id uuid PRIMARY KEY, supabase_user_id uuid, display_name text)')
  await pool.query('CREATE TABLE fallback_user_blocks(blocker_id text, blocked_id text)')
  await pool.query('CREATE TABLE fallback_friends(sender_id text, receiver_id text, status text)')
  for (const [name, userId] of Object.entries(id)) {
    await pool.query('INSERT INTO fallback_users VALUES($1,$2,$3)', [userId, name === 'Ana' ? oldAna : name === 'Carla' ? oldCarla : null, name])
  }
  await pool.query("INSERT INTO fallback_friends VALUES($1,$2,'accepted')", [oldAna, oldCarla])
  await pool.query('INSERT INTO fallback_user_blocks VALUES($1,$2)', [oldAna, id.blocked])
  await ensureBoardGameTables(pool)
  await pool.query(`ALTER TABLE fallback_board_rooms DROP CONSTRAINT fallback_board_rooms_kind_check,
    ADD CHECK (kind IN ('chess', 'checkers', 'ludo'))`)
  await ensureBoardGameTables(pool)
  const app = express()
  app.use(express.json())
  registerBoardGameRoutes(app, {
    pool,
    authRequired: (req, res, next) => {
      if (!Object.values(id).includes(req.get('Authorization'))) return res.sendStatus(401)
      next()
    },
    getIdentity: req => req.get('Authorization'),
    rollDie: () => 6,
  })
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const base = 'http://127.0.0.1:' + server.address().port + '/v1/data/playmatch/board-games'
  return {
    pool,
    native,
    async request(who, path, status = 200, body) {
      const response = await fetch(base + path, {
        method: body ? 'POST' : 'GET',
        headers: { Authorization: id[who] || '', 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined,
      })
      const raw = await response.text()
      assert.ok([status].flat().includes(response.status), `HTTP ${response.status}: ${raw}`)
      return response.status === 401 ? null : JSON.parse(raw)
    },
    async close() {
      await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
      if (native) await db.end()
      else await db.close()
    },
  }
}

test('authenticated board games persists matches, private invites, turns, reactions and Bogotá weekly ranking', async t => {
  const env = await fixture()
  t.after(() => env.close())
  const { pool, request } = env
  await request('', '/rooms', 401)
  const start = (who, game, visibility = 'public', capacity = 2, requestId = randomUUID()) =>
    request(who, visibility === 'public' ? '/match' : '/rooms', 200, { game, visibility, capacity, requestId })
  const roomPath = room => '/rooms/' + room.id

  const joinRequests = ['Ana', 'Bruno'].map(who => () => start(who, 'chess'))
  const [waiting, match] = env.native ?
    (await Promise.all(joinRequests.map(run => run()))).sort((a, b) => a.room.count - b.room.count) :
    [await joinRequests[0](), await joinRequests[1]()]
  assert.equal(waiting.room.id, match.room.id)
  assert.equal(match.room.status, 'playing')
  assert.deepEqual(match.room.players.map(({ id }) => id).sort(), [id.Bruno, oldAna].sort())
  const path = roomPath(match.room)
  const players = match.room.seat === 1 && (await request('Ana', path)).room.seat === 0 ? ['Ana', 'Bruno'] : ['Bruno', 'Ana']
  let revision = match.room.revision
  const send = (who, from, to, status = 200, turnRevision = revision) =>
    request(who, path + '/actions', status, { type: 'move', from, to, revision: turnRevision })
  assert.equal((await send(players[1], 'a7', 'a6', 409)).error, 'BOARD_OUT_OF_TURN')
  assert.equal((await send(players[0], ['f2'], 'f3', 422)).error, 'BOARD_ILLEGAL_ACTION')
  assert.equal((await request('Carla', path, 404)).error, 'BOARD_ROOM_NOT_FOUND')
  for (const [index, [from, to]] of [['f2', 'f3'], ['e7', 'e5'], ['g2', 'g4'], ['d8', 'h4']].entries()) {
    const { room } = await send(players[index % 2], from, to)
    assert.equal(room.revision, ++revision)
    if (index === 0) {
      assert.equal((await send(players[0], from, to, 409, revision - 1)).error, 'BOARD_REVISION_CHANGED')
      const { reactions } = await request(players[0], path + '/reactions', 200, { emoji: '🎉' })
      assert.equal(reactions[0].emoji, '🎉')
      assert.equal((await request(players[1], path)).room.reactions[0].seat, 0)
      await request(players[0], path + '/reactions', 429, { emoji: '🎉' })
    }
    if (index === 3) assert.equal(room.status, 'finished')
  }
  const rank = await request('Ana', '/ranking')
  assert.deepEqual(rank.top, [{ name: players[1], wins: 1 }])
  assert.deepEqual(rank.podium, [])
  assert.match(rank.startsAt, /T05:00:00/)
  assert.equal(Date.parse(rank.endsAt) - Date.parse(rank.startsAt), 7 * 24 * 60 * 60 * 1000)
  await pool.query("UPDATE fallback_board_rooms SET finished_at=$2::timestamptz - interval '1 minute' WHERE id=$1", [match.room.id, rank.startsAt])
  const nextRank = await request('Ana', '/ranking')
  assert.deepEqual(nextRank.top, [])
  assert.deepEqual(nextRank.podium, [{ name: players[1], wins: 1 }])

  const privateId = randomUUID()
  const { room: privateRoom } = await start('Ana', 'ludo', 'private', 2, privateId)
  assert.equal((await start('Ana', 'ludo', 'private', 2, privateId)).room.id, privateRoom.id)
  const privatePath = roomPath(privateRoom)
  assert.deepEqual((await request('Ana', '/friends')).friends.map(friend => friend.name), ['Carla'])
  await request('Carla', privatePath + '/join', 404, {})
  await request('Ana', privatePath + '/invite', 404, { recipientId: id.Bruno })
  await request('Ana', privatePath + '/invite', 200, { recipientId: id.Carla })
  assert.equal((await request('Carla', '/rooms')).rooms.find(room => room.id === privateRoom.id).invited, true)
  const { room: ludo } = await request('Carla', privatePath + '/join', 200, {})
  const { room: rolled } = await request('Ana', privatePath + '/actions', 200, { type: 'roll', roll: 2, revision: ludo.revision })
  assert.deepEqual(rolled.actions, [{ type: 'roll', roll: 6 }])
  await request('Ana', privatePath + '/actions', 200, { type: 'move', token: 3, revision: rolled.revision })

  const { room: publicRoom } = await start('Ana', 'checkers')
  assert.equal((await request('blocked', '/rooms')).rooms.some(room => room.id === publicRoom.id), false)
  await request('blocked', roomPath(publicRoom) + '/join', 404, {})
  const checkersPath = roomPath(publicRoom)
  const { room: checkers } = await request('Carla', checkersPath + '/join', 200, {})
  const move = { type: 'move', from: 40, to: 33, revision: checkers.revision }
  if (env.native) {
    const concurrent = await Promise.all(Array.from({ length: 12 }, () =>
      request('Ana', checkersPath + '/actions', [200, 409], move)))
    assert.equal(concurrent.filter(result => result.room).length, 1)
    assert.equal(concurrent.filter(result => result.error === 'BOARD_REVISION_CHANGED').length, 11)
  } else {
    await request('Ana', checkersPath + '/actions', 200, move)
  }
  assert.deepEqual((await request('Carla', checkersPath)).room.actions, [{ type: 'move', from: 40, to: 33 }])
  assert.equal((await request('Ana', checkersPath + '/close', 200, {})).room.status, 'closed')
  assert.equal((await request('Carla', checkersPath)).room.status, 'closed')
  assert.equal((await request('Ana', '/rooms')).rooms.some(room => room.id === publicRoom.id), false)

  for (const [kind, moves] of [['tic_tac_toe', [0, 3, 1, 4, 2]], ['connect_four', [35, 36, 28, 29, 21, 22, 14]]]) {
    const { room: open } = await start('Ana', kind, 'private')
    const target = roomPath(open)
    await request('Ana', target + '/invite', 200, { recipientId: id.Carla })
    let { room } = await request('Carla', target + '/join', 200, {})
    assert.equal(room.status, 'playing')
    for (const [index, position] of moves.entries()) {
      const result = await request(index % 2 ? 'Carla' : 'Ana', target + '/actions', 200,
        { type: 'move', position, revision: room.revision })
      room = result.room
    }
    assert.equal(room.status, 'finished')
    assert.deepEqual(room.actions.map(action => action.position), moves)
  }
})
