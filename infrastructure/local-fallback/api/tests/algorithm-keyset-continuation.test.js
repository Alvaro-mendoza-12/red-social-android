'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { createAlgorithmLocalFallbackCandidateSource, VIDEO_CANDIDATE_SQL, SOCIAL_CANDIDATE_SQL } = require('../src/algorithm-local-fallback-candidate-source')
const { createAlgorithmCandidateProvider } = require('../src/algorithm-candidate-provider')
const { registerAlgorithmRecommendationRoutes, decodeOpaqueCursor, encodeOpaqueCursor, CURSOR_VERSION, CURSOR_TTL_MS } = require('../src/algorithm-recommendation-routes')
const NOW = Date.parse('2026-10-08T12:00:00Z')
const SECRET = 'keyset-audit-test-secret-no-production-credentials'

function row(index, topic = 'nature') {
  const microseconds = String(index).padStart(6, '0')
  return {
    id: 'video-' + String(index).padStart(5, '0'), source_creator_id: 'creator',
    local_creator_id: 'creator', public_creator_id: 'creator', display_name: 'Creator',
    title: 'Public video ' + index, video_url: 'https://fixture.example.invalid/' + index + '.mp4',
    created_at: '2026-10-08T10:00:00Z', updated_at: '2026-10-08T10:00:00.' + microseconds + 'Z',
    pagination_updated_at: '2026-10-08T10:00:00.' + microseconds + 'Z',
    duration_seconds: 20, classification_topics: [{ topic_key: topic, subtopic_key: 'one', confidence: 1 }],
    format_key: 'short-video', language_code: 'es', is_followed_creator: false,
  }
}
function fixture(rows, { preferences = [] } = {}) {
  const queries = [], calls = []
  let clock = NOW, owner = 'viewer', revision = 1
  const pool = {
    async query(sql, params) {
      queries.push({ sql, params })
      if (sql.includes('to_regclass')) return { rows: [] }
      if (sql.includes('information_schema.columns')) return { rows: [] }
      if (sql.includes('WITH interactions(source')) return { rows: [] }
      if (sql.includes('FROM fallback_user_blocks')) return { rows: [] }
      if (sql.includes('FROM fallback_videos video')) {
        const after = params[3], id = params[4]
        return { rows: rows.filter(r => !after || r.pagination_updated_at < after ||
          (r.pagination_updated_at === after && r.id < id))
          .sort((a, b) => a.pagination_updated_at === b.pagination_updated_at
            ? (a.id < b.id ? 1 : -1) : (a.pagination_updated_at < b.pagination_updated_at ? 1 : -1))
          .slice(0, params[2]) }
      }
      throw new Error('Unexpected fixture query')
    },
  }
  const source = createAlgorithmLocalFallbackCandidateSource({ pool, now: () => clock })
  const provider = createAlgorithmCandidateProvider(source)
  let handler
  registerAlgorithmRecommendationRoutes({ get(_path, ...handlers) { handler = handlers.at(-1) } }, {
    hydrateItems: async ({ items }) => items,
    pool, authRequired: (_q, _r, next) => next(), getCurrentUser: async () => ({ id: owner }),
    userIdentity: user => ({ localUserId: user.id }),
    findOwnedProfile: async () => ({ id: 'profile', is_enabled: true, revision }),
    loadAlgorithmConfiguration: async () => ({ preferences }),
    algorithmFeatureFlags: () => ({ core: true }), cursorSecret: SECRET, now: () => clock,
    loadCandidates: async input => { calls.push(input); return provider(input) },
  })
  async function request(query = {}) {
    const res = { statusCode: 200, status(v) { this.statusCode = v; return this }, setHeader() {}, json(v) { this.body = v; return this } }
    await handler({ query: { surface: 'video', refreshId: 'stable', ...query } }, res, error => { throw error })
    return res
  }
  return { request, calls, queries, source, setOwner: v => { owner = v }, setClock: v => { clock = v }, setRevision: v => { revision = v } }
}

test('bounded keysets traverse 405 videos with microsecond boundaries and changing page sizes', async () => {
  const rows = Array.from({ length: 405 }, (_, i) => row(i + 1)), f = fixture(rows), ids = []
  let cursor = null, pages = 0
  do {
    const res = await f.request({ limit: pages % 2 ? 60 : 37, ...(cursor ? { cursor } : {}) })
    assert.equal(res.statusCode, 200)
    ids.push(...res.body.items.map(x => x.id)); cursor = res.body.page.nextCursor
    assert.equal(res.body.page.hasMore, Boolean(cursor))
    assert.ok(++pages < 20, 'bounded fixture must finish')
  } while (cursor)
  assert.equal(ids.length, 405); assert.equal(new Set(ids).size, 405)
  assert.deepEqual(new Set(ids), new Set(rows.map(x => x.id)))
  const candidateQueries = f.queries.filter(x => x.sql === VIDEO_CANDIDATE_SQL)
  assert.ok(candidateQueries.every(x => x.params[2] === 201))
  assert.ok(candidateQueries.some(x => x.params[3]?.endsWith('000206Z')), 'exact last-row microseconds retained')
  assert.ok(candidateQueries.every(x => x.params[1] === new Date(NOW).toISOString()))
})

test('keyset timestamp ties use canonical IDs without repeated or skipped items', async () => {
  const rows = Array.from({ length: 205 }, (_, i) => ({ ...row(i + 1), pagination_updated_at: '2026-10-08T10:00:00.000001Z', updated_at: '2026-10-08T10:00:00.000001Z' }))
  const f = fixture(rows), ids = []; let cursor = null
  do { const res = await f.request({ limit: 60, ...(cursor ? { cursor } : {}) }); assert.equal(res.statusCode, 200); ids.push(...res.body.items.map(x => x.id)); cursor = res.body.page.nextCursor } while (cursor)
  assert.equal(ids.length, 205); assert.equal(new Set(ids).size, 205)
})

test('fully negative windows advance to older eligible videos without weakening preferences', async () => {
  const rows = Array.from({ length: 405 }, (_, i) => row(i + 1, i >= 5 ? 'gaming' : 'nature'))
  const f = fixture(rows, { preferences: [{ surface: 'video', dimension: 'topic', subjectKey: 'gaming', score: -100 }] })
  const res = await f.request({ limit: 20 })
  assert.equal(res.statusCode, 200); assert.equal(res.body.items.length, 5)
  assert.ok(res.body.items.every(x => rows.find(r => r.id === x.id).classification_topics[0].topic_key === 'nature'))
  assert.equal(res.body.page.hasMore, false); assert.equal(f.calls.length, 3)
})

test('filtered search has a work budget and returns continuation instead of false exhaustion', async () => {
  const f = fixture(Array.from({ length: 805 }, (_, i) => row(i + 1, 'gaming')), { preferences: [{ surface: 'video', dimension: 'topic', subjectKey: 'gaming', score: -100 }] })
  const first = await f.request()
  assert.equal(first.statusCode, 200); assert.deepEqual(first.body.items, [])
  assert.equal(first.body.page.hasMore, true); assert.equal(f.calls.length, 3)
  const decoded = decodeOpaqueCursor(first.body.page.nextCursor, SECRET, NOW)
  assert.equal(decoded.continueBatch, true); assert.equal(decoded.position, 0)
  const next = await f.request({ cursor: first.body.page.nextCursor })
  assert.equal(next.statusCode, 200); assert.deepEqual(next.body.items, []); assert.equal(next.body.page.hasMore, false)
})

test('batch-boundary cursors remain owner/revision/time bound and authenticated', async () => {
  const f = fixture(Array.from({ length: 201 }, (_, i) => row(i + 1)))
  let cursor
  for (let i = 0; i < 4; i++) { const res = await f.request({ limit: 60, ...(cursor ? { cursor } : {}) }); cursor = res.body.page.nextCursor }
  const decoded = decodeOpaqueCursor(cursor, SECRET, NOW); assert.equal(decoded.continueBatch, true)
  f.setOwner('foreign'); assert.equal((await f.request({ cursor })).statusCode, 400)
  f.setOwner('viewer'); f.setRevision(2); assert.equal((await f.request({ cursor })).statusCode, 409)
  f.setRevision(1); f.setClock(NOW + CURSOR_TTL_MS + 1); assert.equal((await f.request({ cursor })).statusCode, 400)
  f.setClock(NOW); assert.equal((await f.request({ cursor: cursor.slice(0, -4) + 'xxxx' })).statusCode, 400)
  assert.throws(() => encodeOpaqueCursor({ ...decoded, position: 1 }, SECRET))
  assert.equal(CURSOR_VERSION, 3)
})

test('changed current batch still rejects a stale in-batch cursor', async () => {
  const rows = Array.from({ length: 30 }, (_, i) => row(i + 1)), f = fixture(rows)
  const first = await f.request({ limit: 5 }); rows[0].title = 'Changed public title'
  assert.equal((await f.request({ cursor: first.body.page.nextCursor })).statusCode, 409)
})

test('selectors parameterize keysets, require finite dates and use matching C ID order', () => {
  for (const sql of [VIDEO_CANDIDATE_SQL, SOCIAL_CANDIDATE_SQL]) {
    assert.match(sql, /isfinite\(/); assert.match(sql, /\$4::timestamptz/); assert.match(sql, /\$5::text COLLATE "C"/)
    assert.match(sql, /SS\.US/); assert.match(sql, /id::text COLLATE "C" DESC/)
  }
})

test('block checks include every creator alias in a full 200-candidate window', async () => {
  const candidates = Array.from({ length: 200 }, (_, i) => ({
    id: 'v-' + i, surface: 'video', creatorId: 'creator-' + i,
    creatorAliases: Array.from({ length: 32 }, (_, j) => 'alias-' + i + '-' + j),
    safety: { contentPublic: true, contentEligible: true, creatorVisible: true, creatorHidden: false, creatorSuspended: false },
    item: { id: 'v-' + i },
  }))
  // CreatorId itself consumes one of the 32 reviewed alias positions.
  const lastAlias = 'alias-199-30'
  let aliases
  const provider = createAlgorithmCandidateProvider({
    loadRawCandidates: async () => ({ snapshotId: 'all-aliases', candidates }),
    loadBlockEdges: async input => {
      aliases = input.creatorIds
      return input.creatorIds.includes(lastAlias) ? [{ blockerId: 'viewer', blockedId: lastAlias }] : []
    },
  })
  const result = await provider({ owner: { userId: 'viewer' }, surface: 'video' })
  assert.equal(aliases.length, 6400); assert.ok(aliases.includes(lastAlias))
  assert.equal(result.candidates.length, 199); assert.ok(!result.candidates.some(x => x.id === 'v-199'))
})

test('block-edge work budget fails closed instead of silently dropping edges', async () => {
  let parameters
  const source = createAlgorithmLocalFallbackCandidateSource({ pool: { async query(sql, args) {
    if (sql.includes('to_regclass')) return { rows: [] }
    parameters = args
    return { rows: Array.from({ length: 4097 }, () => ({ blocker_id: 'viewer', blocked_id: 'creator' })) }
  } } })
  await assert.rejects(() => source.loadBlockEdges({ viewerIds: ['viewer'], creatorIds: Array.from({ length: 600 }, (_, i) => 'creator-' + i) }), /RECOMMENDATION_PROVIDER_BLOCKS_UNAVAILABLE/)
  assert.equal(parameters[1].length, 600); assert.equal(parameters[2], 4097)
})
