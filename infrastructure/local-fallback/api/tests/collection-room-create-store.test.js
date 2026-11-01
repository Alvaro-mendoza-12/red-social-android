const assert = require('node:assert/strict')
const { test } = require('node:test')
const { upsertOwnedCollectionRoom, createOwnedCollectionRoom, createOwnedCollectionRoomOnce } = require('../src/collection-room-create-store')

const room = { id: '11111111-1111-4111-8111-111111111111', kind: 'group', name: 'QA group',
  image: null, description: null, category: null, isPublic: false, creatorId: 'owner' }
const actors = new Set(['owner', 'legacy-owner'])

test('creation uses an atomic owner and kind predicate without changing creator or resurrecting a room', async () => {
  let query
  const row = { id: room.id, creator_id: 'owner', room_kind: 'group' }
  assert.equal(await upsertOwnedCollectionRoom({ query: async (sql, params) => {
    query = { sql, params }; return { rows: [row] }
  } }, room, actors, async () => false), row)
  assert.match(query.sql, /WHERE fallback_collection_rooms.room_kind = EXCLUDED.room_kind/)
  assert.match(query.sql, /lower\(fallback_collection_rooms.creator_id\) = ANY\(\$9::text\[\]\)/)
  assert.match(query.sql, /AND fallback_collection_rooms.is_deleted = FALSE/)
  const updates = query.sql.split('DO UPDATE SET')[1].split('WHERE')[0]
  assert.doesNotMatch(updates, /creator_id\s*=|room_kind\s*=|is_deleted\s*=/)
  assert.deepEqual(query.params.at(-1), ['owner', 'legacy-owner'])
})

test('a rejected local conflict returns no room data and does not acknowledge creation', async () => {
  await assert.rejects(upsertOwnedCollectionRoom({ query: async () => ({ rows: [] }) }, room, actors),
    { message: 'ROOM_ID_CONFLICT', status: 409 })
})

for (const collidingKind of ['group', 'community']) {
  test(`an existing imported ${collidingKind} cannot be shadowed using its ID`, async () => {
    const checked = []
    await assert.rejects(upsertOwnedCollectionRoom({ query: async () => assert.fail('No overwrite allowed') },
      room, actors, async (kind, id) => {
        checked.push([kind, id]); return kind === collidingKind
      }), { message: 'ROOM_ID_CONFLICT', status: 409 })
    assert.deepEqual(checked, [['group', room.id], ['community', room.id]])
  })
}

test('unknown collection kinds and a forged creator never reach storage', async () => {
  const pool = { query: async () => assert.fail('Unauthorized storage access') }
  await assert.rejects(upsertOwnedCollectionRoom(pool, { ...room, kind: 'arbitrary' }, actors),
    { message: 'ROOM_ID_CONFLICT', status: 409 })
  await assert.rejects(upsertOwnedCollectionRoom(pool, { ...room, creatorId: 'other' }, actors),
    { message: 'ROOM_ID_CONFLICT', status: 409 })
})

test('server-generated new IDs skip imported-ID lookup', async () => {
  const row = { id: room.id }
  assert.equal(await upsertOwnedCollectionRoom({ query: async (_sql, params) => {
    assert.equal(params[0], null); return { rows: [row] }
  } }, { ...room, id: null }, actors, async () => assert.fail('No ID to check')), row)
})

test('database and imported lookup failures propagate without acknowledging creation', async () => {
  const failure = new Error('database unavailable')
  await assert.rejects(upsertOwnedCollectionRoom({ query: async () => { throw failure } }, room, actors),
    (actual) => actual === failure)
  await assert.rejects(upsertOwnedCollectionRoom({ query: async () => assert.fail('Lookup did not pass') },
    room, actors, async () => { throw failure }), (actual) => actual === failure)
})

function transactionalPool(fail = () => false, row = { id: room.id, creator_id: 'owner', room_kind: 'group' }) {
  const calls = []
  let released = 0
  const client = {
    query: async (sql, params) => {
      calls.push({ sql, params })
      if (fail(sql, params)) throw new Error('fixture failure')
      return { rows: sql.startsWith('INSERT INTO fallback_collection_rooms') ? [row] : [] }
    }, release: () => { released++ },
  }
  return { pool: { connect: async () => client, query: () => assert.fail('Must use the reserved client') },
    client, calls, released: () => released }
}

test('room and initial members commit in one transaction and release the client', async () => {
  const f = transactionalPool()
  const row = await createOwnedCollectionRoom(f.pool, room, actors, ['friend', 'owner', 'friend'], async () => false)
  assert.equal(row.id, room.id)
  assert.equal(f.calls[0].sql, 'BEGIN')
  assert.equal(f.calls[1].sql, "SET LOCAL lock_timeout = '5s'")
  assert.equal(f.calls.at(-1).sql, 'COMMIT')
  const members = f.calls.filter(call => call.sql.includes('INSERT INTO fallback_collection_members'))
  assert.deepEqual(members.map(call => call.params), [
    ['group', room.id, 'owner', 'owner'], ['group', room.id, 'friend', 'member'],
  ])
  assert.equal(f.released(), 1)
})

test('member failure rolls back the already inserted room and never acknowledges creation', async () => {
  const f = transactionalPool(sql => sql.includes('INSERT INTO fallback_collection_members'))
  await assert.rejects(createOwnedCollectionRoom(f.pool, room, actors, ['friend']), /fixture failure/)
  assert.ok(f.calls.some(call => call.sql.startsWith('INSERT INTO fallback_collection_rooms')))
  assert.equal(f.calls.at(-1).sql, 'ROLLBACK')
  assert.ok(!f.calls.some(call => call.sql === 'COMMIT'))
  assert.equal(f.released(), 1)
})

test('a migrated creator retries with one stable owner membership for all of their aliases', async () => {
  const f = transactionalPool()
  await createOwnedCollectionRoom(f.pool, { ...room, creatorId: 'legacy-owner' }, actors,
    ['legacy-owner', 'OWNER', 'friend'], async (client) => {
      assert.equal(client, f.client); return false
    })
  const members = f.calls.filter(call => call.sql.includes('INSERT INTO fallback_collection_members'))
  assert.deepEqual(members.map(call => call.params.slice(2)), [['owner', 'owner'], ['friend', 'member']])
})

test('imported conflict rolls back before inserting memberships', async () => {
  const f = transactionalPool()
  await assert.rejects(createOwnedCollectionRoom(f.pool, room, actors, ['friend'], async client => {
    assert.equal(client, f.client); return true
  }), { message: 'ROOM_ID_CONFLICT', status: 409 })
  assert.equal(f.calls.at(-1).sql, 'ROLLBACK')
  assert.ok(!f.calls.some(call => call.sql.includes('INSERT INTO fallback_collection_members')))
  assert.equal(f.released(), 1)
})

test('failure to begin releases the client without issuing a spurious rollback', async () => {
  const f = transactionalPool(sql => sql === 'BEGIN')
  await assert.rejects(createOwnedCollectionRoom(f.pool, room, actors, []), /fixture failure/)
  assert.deepEqual(f.calls.map(call => call.sql), ['BEGIN'])
  assert.equal(f.released(), 1)
})

test('commit failure is rolled back and never reported as success', async () => {
  const f = transactionalPool(sql => sql === 'COMMIT')
  await assert.rejects(createOwnedCollectionRoom(f.pool, room, actors, []), /fixture failure/)
  assert.equal(f.calls.at(-1).sql, 'ROLLBACK')
  assert.equal(f.released(), 1)
})

test('rollback failure still releases the connection and preserves the original failure', async () => {
  const f = transactionalPool(sql => sql === 'ROLLBACK' || sql.includes('INSERT INTO fallback_collection_members'))
  await assert.rejects(createOwnedCollectionRoom(f.pool, room, actors, []), /fixture failure/)
  assert.equal(f.released(), 1)
})

function oncePool({ existing, failMember = false, departed = false } = {}) {
  const calls = []
  const actualMembers = [{ user_id: 'owner', role: 'owner', joined_at: '2026-11-01T12:00:00Z' }]
  let released = 0
  const current = existing || { ...room, creator_id: 'owner', room_kind: 'group', is_deleted: false }
  const client = { query: async (sql, params) => {
    calls.push({ sql, params })
    if (failMember && sql.includes('INSERT INTO fallback_collection_members')) throw new Error('member failed')
    if (sql.startsWith('INSERT INTO fallback_collection_rooms')) return { rows: existing ? [] : [current] }
    if (sql.startsWith('SELECT * FROM fallback_collection_rooms')) return { rows: [current] }
    if (sql.includes('SELECT 1 AS departed')) return { rows: departed ? [{ departed: 1 }] : [] }
    if (sql.includes('SELECT user_id, role, joined_at')) return { rows: actualMembers }
    return { rows: [] }
  }, release: () => { released++ } }
  return { pool: { connect: async () => client }, calls, current, actualMembers, released: () => released }
}

test('opt-in creation inserts initial members only when it really creates the room', async () => {
  const f = oncePool()
  const result = await createOwnedCollectionRoomOnce(f.pool, room, actors, ['friend'])
  assert.equal(result.created, true)
  assert.equal(result.room, f.current)
  assert.equal(result.initialMembers, f.actualMembers)
  assert.equal(f.calls.filter(call => call.sql.includes('INSERT INTO fallback_collection_members')).length, 2)
  assert.equal(f.calls.at(-1).sql, 'COMMIT')
  assert.equal(f.released(), 1)
})

test('an old opt-in retry returns the current room and members without reverting later edits or removals', async () => {
  const f = oncePool({ existing: { id: room.id, creator_id: 'owner', room_kind: 'group', is_deleted: false, name: 'Edited later' } })
  const result = await createOwnedCollectionRoomOnce(f.pool, room, actors, ['removed-friend'])
  assert.equal(result.created, false)
  assert.equal(result.room.name, 'Edited later')
  assert.equal(result.initialMembers, f.actualMembers)
  assert.ok(f.calls.some(call => call.sql.endsWith('FOR UPDATE')))
  assert.ok(!f.calls.some(call => call.sql.includes('INSERT INTO fallback_collection_members')))
  assert.ok(!f.calls.some(call => /DO UPDATE|UPDATE fallback_collection_rooms/.test(call.sql)))
  assert.equal(f.released(), 1)
})

for (const difference of [{ creator_id: 'other' }, { room_kind: 'community' }, { is_deleted: true }]) {
  test(`opt-in replay rejects conflicting room state ${JSON.stringify(difference)}`, async () => {
    const f = oncePool({ existing: { id: room.id, creator_id: 'owner', room_kind: 'group', is_deleted: false, ...difference } })
    await assert.rejects(createOwnedCollectionRoomOnce(f.pool, room, actors, []), { message: 'ROOM_ID_CONFLICT', status: 409 })
    assert.equal(f.calls.at(-1).sql, 'ROLLBACK')
    assert.ok(!f.calls.some(call => call.sql.includes('INSERT INTO fallback_collection_members')))
    assert.equal(f.released(), 1)
  })
}

test('initial opt-in member failure rolls back the room instead of leaving an uneditable partial intent', async () => {
  const f = oncePool({ failMember: true })
  await assert.rejects(createOwnedCollectionRoomOnce(f.pool, room, actors, []), /member failed/)
  assert.equal(f.calls.at(-1).sql, 'ROLLBACK')
  assert.equal(f.released(), 1)
})

test('opt-in creation requires a stable ID and a valid creator before acquiring a connection', async () => {
  const pool = { connect: () => assert.fail('Invalid creation must not start') }
  await assert.rejects(createOwnedCollectionRoomOnce(pool, { ...room, id: null }, actors, []), /ROOM_ID_CONFLICT/)
  await assert.rejects(createOwnedCollectionRoomOnce(pool, { ...room, creatorId: 'other' }, actors, []), /ROOM_ID_CONFLICT/)
})

test('a sole creator who voluntarily left cannot regain membership by replaying an old creation', async () => {
  const f = oncePool({ existing: { id: room.id, creator_id: 'owner', room_kind: 'group', is_deleted: false }, departed: true })
  await assert.rejects(createOwnedCollectionRoomOnce(f.pool, room, actors, []), { message: 'ROOM_ID_CONFLICT', status: 409 })
  assert.equal(f.calls.at(-1).sql, 'ROLLBACK')
  assert.ok(!f.calls.some(call => call.sql.includes('INSERT INTO fallback_collection_members')))
  assert.ok(f.calls.findIndex(call => call.sql.includes('pg_advisory_xact_lock')) < f.calls.findIndex(call => call.sql.startsWith('INSERT INTO fallback_collection_rooms')))
  assert.equal(f.released(), 1)
})
