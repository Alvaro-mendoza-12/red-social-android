const assert = require('node:assert/strict')
const { test } = require('node:test')
const { upsertOwnedCollectionRoom } = require('../src/collection-room-create-store')

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
