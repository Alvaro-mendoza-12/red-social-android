'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const {
  normalizedReaction, normalizedPresentation, visibleMusicUrl, profileMusicKey, reactionSummary,
  registerProfileMusicRoutes, setReaction, ensureProfileMusicTables,
} = require('../src/profile-music-routes')

test('music revisions survive storage signature/origin rotation but change for a new object/version/owner', () => {
  const a = 'https://one.example/storage/v1/object/sign/audios/song.mp3?token=old&v=2'
  const b = 'https://two.example/storage/v1/object/public/audios/song.mp3?v=2&token=new'
  assert.equal(profileMusicKey('Owner', a), profileMusicKey('owner', b))
  assert.notEqual(profileMusicKey('owner', a), profileMusicKey('owner', b.replace('v=2', 'v=3')))
  assert.notEqual(profileMusicKey('owner', a), profileMusicKey('other', a))
  assert.notEqual(profileMusicKey('owner', a), profileMusicKey('owner', a.replace('song.mp3', 'new.mp3')))
  for (const url of ['http://example.com/a.mp3', 'file:///private', 'https://user:pass@example.com/a'])
    assert.throws(() => profileMusicKey('owner', url), { code: 'PROFILE_MUSIC_UNAVAILABLE' })
})

test('a camelCase alias cannot undo hidden music section visibility', () => {
  const raw = { favoriteMusicUrl: 'https://example.com/private.mp3' }
  assert.equal(visibleMusicUrl(raw, { viewerIsOwner: false }, p => ({ ...p, favorite_music_url: '' })), '')
  assert.equal(visibleMusicUrl(raw, { viewerIsOwner: true }, p => p), raw.favoriteMusicUrl)
})

test('reactions and presentation accept only explicit supported choices', () => {
  assert.equal(normalizedReaction(' love '), 'LOVE')
  assert.equal(normalizedReaction(null), null)
  for (const value of [undefined, {}, 'invalid']) assert.throws(() => normalizedReaction(value), { status: 400 })
  assert.equal(normalizedPresentation('disc'), 'disc')
  assert.throws(() => normalizedPresentation('arbitrary'), { status: 400 })
  assert.deepEqual(reactionSummary([{ reaction: 'LOVE', count: '3', mine: true }, { reaction: 'SAD', count: -5 }]),
    { counts: { LIKE: 0, LOVE: 3, FIRE: 0, HAHA: 0, WOW: 0, SAD: 0 }, reaction: 'LOVE', total: 3 })
})

// Route tests exercise real HTTP, parsing, auth gates and revision validation.
// Transaction tests below exercise idempotency/rollback; PostgreSQL syntax is a separate integration check.
async function withServer(t, overrides = {}) {
  const app = express(); app.use(express.json())
  const queries = []
  const pool = { query: async (sql, values) => {
    queries.push({ sql, values })
    return { rows: sql.startsWith('SELECT reaction') ? [{ reaction: 'LOVE', count: 3, mine: true }] : [] }
  } }
  const context = { ownerId: 'owner', musicUrl: 'https://example.com/song.mp3', viewerIsOwner: false, ownerIds: ['owner'] }
  registerProfileMusicRoutes(app, { pool,
    authRequired: (req, res, next) => req.headers.authorization ? next() : res.status(401).end(),
    getCurrentUser: async () => ({ id: 'actor' }), loadAccess: async () => context,
    createNotification: async () => null, dispatchNotification: () => {}, onChanged: () => {}, ...overrides })
  app.use((error, req, res, next) => res.status(error.status || 500).json({ error: error.message }))
  const server = app.listen(0, '127.0.0.1')
  await new Promise(resolve => server.once('listening', resolve))
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve) }))
  const base = `http://127.0.0.1:${server.address().port}/v1/data/profiles/owner/music`
  const send = (path, body) => fetch(base + path, { method: body ? 'PUT' : 'GET',
    headers: { Authorization: 'Bearer fixture', 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) })
  return { base, send, queries, context }
}

test('summary is private and includes my exclusive reaction and counts', async t => {
  const { send, base } = await withServer(t)
  assert.equal((await fetch(base + '/reactions')).status, 401)
  const response = await send('/reactions')
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('cache-control'), 'private, no-store')
  const summary = await response.json()
  assert.equal(summary.total, 3); assert.equal(summary.reaction, 'LOVE')
  assert.equal(summary.canEditPresentation, false)
})

test('hidden, blocked, removed and unavailable music never expose counts or permit writes', async t => {
  const { send, queries } = await withServer(t, { loadAccess: async () => null })
  assert.equal((await send('/reactions')).status, 404)
  assert.equal((await send('/reactions', { musicKey: 'a'.repeat(64), reaction: 'LOVE' })).status, 404)
  assert.equal(queries.length, 0)
})

test('only the owner may persist presentation; stale song writes are rejected before SQL', async t => {
  const { send, queries, context } = await withServer(t)
  assert.equal((await send('/presentation', { musicKey: profileMusicKey('owner', context.musicUrl), presentation: 'disc' })).status, 403)
  assert.equal((await send('/reactions', { musicKey: 'a'.repeat(64), reaction: 'LOVE' })).status, 409)
  assert.equal((await send('/reactions', { reaction: 'LOVE' })).status, 400)
  assert.equal(queries.length, 0)
  context.viewerIsOwner = true
  assert.equal((await send('/presentation', { musicKey: profileMusicKey('owner', context.musicUrl), presentation: 'disc' })).status, 200)
  assert.equal(queries[0].values[2], 'disc')
})

function transactionFixture() {
  let row = null
  let snapshot = null
  let releases = 0
  const statements = []
  const db = { query: async (sql, values) => {
    statements.push(sql)
    if (sql === 'BEGIN') snapshot = row && { ...row }
    if (sql.startsWith('SELECT reaction,notified_at')) return { rows: row ? [{ ...row }] : [] }
    if (sql.startsWith('INSERT INTO fallback_profile_music_reactions'))
      row = { reaction: values[3], notified_at: values[4] ? new Date() : row?.notified_at ?? null }
    if (sql === 'ROLLBACK') row = snapshot
    return { rows: [] }
  }, release: () => { releases++ } }
  return { pool: { connect: async () => db }, statements, get row() { return row }, get releases() { return releases } }
}

test('retries are idempotent, a change replaces the reaction, removal works, notifications do not spam', async () => {
  const fixture = transactionFixture()
  const context = { ownerId: 'owner', actorId: 'actor', musicKey: 'key', viewerIsOwner: false }
  let notifications = 0
  const notify = async () => { notifications++; return { id: 'notice' } }
  assert.equal((await setReaction(fixture.pool, context, 'LOVE', notify)).changed, true)
  assert.equal((await setReaction(fixture.pool, context, 'LOVE', notify)).changed, false)
  assert.equal((await setReaction(fixture.pool, context, 'FIRE', notify)).changed, true)
  assert.equal(fixture.row.reaction, 'FIRE')
  await setReaction(fixture.pool, context, null, notify)
  assert.equal(fixture.row.reaction, null)
  await setReaction(fixture.pool, context, 'LOVE', notify)
  assert.equal(notifications, 1)
  assert.equal(fixture.releases, 5)
  assert.equal(fixture.statements.filter(sql => sql.includes('pg_advisory_xact_lock')).length, 5)
})

test('a notification write failure rolls back the reaction and releases the transaction', async () => {
  const fixture = transactionFixture()
  await assert.rejects(() => setReaction(fixture.pool, { ownerId: 'o', actorId: 'a', musicKey: 'k', viewerIsOwner: false },
    'LOVE', async () => { throw Error('transaction failed') }), /transaction failed/)
  assert.equal(fixture.row, null)
  assert.equal(fixture.statements.at(-1), 'ROLLBACK')
  assert.equal(fixture.releases, 1)
})

test('owner reactions never notify the owner', async () => {
  const fixture = transactionFixture()
  await setReaction(fixture.pool, { ownerId: 'o', actorId: 'o', musicKey: 'k', viewerIsOwner: true }, 'LOVE', () => { assert.fail() })
  assert.equal(fixture.row.reaction, 'LOVE')
})

test('schema and real PostgreSQL reaction summary', { skip: !process.env.TEST_DATABASE_URL }, async () => {
  const { Pool } = require('pg')
  const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL })
  const ownerId = `qa-music-${require('node:crypto').randomUUID()}`
  let schemaReady = false
  try {
    await ensureProfileMusicTables(pool)
    schemaReady = true
    const context = { ownerId, actorId: 'qa-actor', musicKey: 'qa-song', viewerIsOwner: true }
    await setReaction(pool, context, 'LOVE', () => null)
    const result = await pool.query('SELECT reaction FROM fallback_profile_music_reactions WHERE owner_id=$1', [ownerId])
    assert.deepEqual(result.rows, [{ reaction: 'LOVE' }])
    assert.equal((await setReaction(pool, context, 'LOVE', () => null)).changed, false)
    await setReaction(pool, context, null, () => null)
  } finally {
    try {
      if (schemaReady) await pool.query('DELETE FROM fallback_profile_music_reactions WHERE owner_id=$1', [ownerId])
    } finally { await pool.end() }
  }
})
