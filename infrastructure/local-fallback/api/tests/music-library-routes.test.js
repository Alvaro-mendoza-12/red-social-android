'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')
const { libraryCursor, listOwnMusic, removeOwnMusic, registerMusicLibraryRoutes } = require('../src/music-library-routes')

const owner = '11111111-1111-4111-8111-111111111111'
const id = '22222222-2222-4222-8222-222222222222'
const at = '2026-09-20T13:00:00.123456Z'
const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url')

test('history cursor preserves microseconds and rejects malformed or impossible dates', () => {
  assert.deepEqual(libraryCursor(encode({ id, at })), { id, at })
  assert.equal(libraryCursor(null), null)
  for (const value of ['bad', 'a'.repeat(321), encode(null), encode({ id: 'bad', at }),
    encode({ id, at: '2026-02-30T13:00:00.123456Z' }), encode({ id, at: '2026-09-20T13:00:00.123Z' })]) {
    assert.throws(() => libraryCursor(value), { status: 400 })
  }
})

test('history restricts owner and removed rows while paging pending and rejected uploads with precise cursor', async () => {
  const calls = []
  const pool = { query: async (sql, params) => {
    calls.push({ sql, params })
    return { rows: [{ id, history_cursor_at: at, moderation_state: 'REJECTED' }, { id: owner }] }
  } }
  const first = await listOwnMusic(pool, owner, { limit: 1 }, (row) => ({ id: row.id, status: row.moderation_state }))
  assert.deepEqual(first.items, [{ id, status: 'REJECTED' }])
  assert.deepEqual(libraryCursor(first.nextCursor), { id, at })
  assert.deepEqual(calls[0].params, [owner, null, null, 2])
  assert.match(calls[0].sql, /owner_id=\$1::uuid AND t.removed_at IS NULL/)
  assert.doesNotMatch(calls[0].sql, /moderation_state\s*=/)
  await listOwnMusic(pool, owner, { cursor: first.nextCursor, limit: 100000 }, () => ({}))
  assert.deepEqual(calls[1].params, [owner, at, id, 101])
})

function transaction(track, failUpdate = false) {
  const calls = []
  let released = false
  return { calls, get released() { return released }, connect: async () => ({
    query: async (sql, params) => {
      calls.push({ sql, params })
      if (sql.startsWith('SELECT')) return { rows: track ? [track] : [] }
      if (failUpdate && sql.startsWith('UPDATE')) throw new Error('database unavailable')
      return { rows: [] }
    }, release: () => { released = true },
  }) }
}

test('owner removal locks ownership and hides reuse atomically without deleting media used by moments', async () => {
  const pool = transaction({ id, moderation_state: 'APPROVED', removed_at: null })
  const result = await removeOwnMusic(pool, owner, id)
  assert.deepEqual(result, { id: `community:${id}`, deleted: true, alreadyDeleted: false, existingMomentsPreserved: true })
  assert.deepEqual(pool.calls[1].params, [id, owner])
  assert.match(pool.calls[1].sql, /owner_id=\$2::uuid FOR UPDATE/)
  assert.match(pool.calls[2].sql, /allow_use_in_moments=FALSE/)
  assert.match(pool.calls[3].sql, /fallback_music_moderation_events/)
  assert.equal(pool.calls.at(-1).sql, 'COMMIT')
  assert.ok(pool.calls.every(({ sql }) => !/DELETE FROM|fallback_moments|storage_objects/.test(sql)))
  assert.equal(pool.released, true)
})

test('removal cannot touch another owner and retries are idempotent', async () => {
  const unauthorized = transaction(null)
  assert.equal(await removeOwnMusic(unauthorized, owner, id), null)
  assert.equal(unauthorized.calls.at(-1).sql, 'ROLLBACK')
  assert.equal(unauthorized.calls.length, 3)
  const removed = transaction({ id, removed_at: at })
  assert.equal((await removeOwnMusic(removed, owner, id)).alreadyDeleted, true)
  assert.equal(removed.calls.length, 3)
  assert.equal(removed.calls.at(-1).sql, 'COMMIT')
})

test('failed mutation rolls back and releases its database connection without reporting success', async () => {
  const pool = transaction({ id, moderation_state: 'PENDING_REVIEW' }, true)
  await assert.rejects(removeOwnMusic(pool, owner, id), /database unavailable/)
  assert.equal(pool.calls.at(-1).sql, 'ROLLBACK')
  assert.equal(pool.released, true)
})

test('both library endpoints require an identity before querying or mutating', async () => {
  const routes = []
  const auth = () => {}
  const app = Object.fromEntries(['get', 'delete'].map((method) => [method, (path, middleware, handler) => {
    assert.equal(middleware, auth)
    routes.push({ path, handler })
  }]))
  registerMusicLibraryRoutes(app, { pool: { query: assert.fail, connect: assert.fail }, authRequired: auth,
    identity: async () => null, rawTrackId: () => id, mapTrack: (row) => row })
  for (const route of routes) {
    const res = { status(code) { this.code = code; return this }, json(body) { this.body = body } }
    await route.handler({ params: { trackId: id } }, res, assert.fail)
    assert.equal(res.code, 401)
    assert.equal(res.body.error, 'AUTH_REQUIRED')
  }
})
