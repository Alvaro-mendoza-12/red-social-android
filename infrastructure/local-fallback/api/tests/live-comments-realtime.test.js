const assert = require('node:assert/strict')
const { test } = require('node:test')
const events = []
require.cache[require.resolve('../src/realtime-publish')] = { id: require.resolve('../src/realtime-publish'), filename: require.resolve('../src/realtime-publish'), loaded: true, exports: { publishRealtimeEvent: async (channel, payload) => { events.push({ channel, payload }); return true } } }
const { registerLiveRoutes } = require('../src/live-routes')
const liveId = '11111111-1111-4111-8111-111111111111'
const commentId = '22222222-2222-4222-8222-222222222222'
function fixture() {
  const stored = new Map(), handlers = new Map()
  let total = 0
  const pool = { async query(sql, params) {
    if (sql.includes('FROM fallback_live_streams')) return { rows: [{ id: liveId, host_user_id: 'host', is_active: true }] }
    if (sql.includes('fallback_profile_overrides')) return { rows: [{ profile: { display_name: 'Ana' } }] }
    if (sql.includes('WITH inserted AS')) {
      const [live_id, user_id, user_name, avatar_url, content, id] = params
      if (stored.has(id)) return { rows: [] }
      const comment = { id, live_id, user_id, user_name, avatar_url, content, created_at: new Date().toISOString() }
      stored.set(id, comment); total++; return { rows: [comment] }
    }
    if (sql.includes('FROM fallback_live_comments WHERE id=')) {
      const [id, live, actor, content] = params, comment = stored.get(id)
      return { rows: comment && comment.live_id === live && comment.user_id === actor && comment.content === content ? [comment] : [] }
    }
    throw new Error('Unexpected query: ' + sql.slice(0, 80))
  } }
  const app = Object.fromEntries(['get', 'post', 'patch', 'delete', 'put'].map(method => [method, (path, ...chain) => handlers.set(method + ' ' + path, chain.at(-1))]))
  registerLiveRoutes(app, { pool, authRequired: () => {}, getCurrentUser: async () => ({ id: 'host' }), userIdentity: () => ({ ids: new Set(['host']), values: ['host'], displayUserId: 'host' }) })
  return { total: () => total, send: async body => {
    const res = { statusCode: 200, status(code) { this.statusCode = code; return this }, json(value) { this.body = value; return this } }
    await handlers.get('post /v1/data/live/:liveId/comments')({ params: { liveId }, body }, res, error => { throw error })
    return res
  } }
}
test('publishes the complete saved comment immediately and retries never double count', async () => {
  events.length = 0
  const f = fixture(), request = { content: 'Hola desde Android', clientCommentId: commentId }
  const first = await f.send(request)
  assert.equal(first.statusCode, 201)
  assert.equal(events.length, 1)
  assert.equal(events[0].channel, 'live-comments:' + liveId)
  assert.deepEqual(events[0].payload.comment, first.body.comment)
  assert.equal((await f.send(request)).statusCode, 200)
  assert.equal(f.total(), 1)
  assert.equal(events.length, 1)
  assert.equal((await f.send({ ...request, content: 'Changed content' })).statusCode, 409)
})
test('rejects malformed client IDs before creating a comment', async () => {
  const f = fixture()
  assert.equal((await f.send({ content: 'Hola', clientCommentId: 'invalid' })).statusCode, 400)
  assert.equal(f.total(), 0)
})
