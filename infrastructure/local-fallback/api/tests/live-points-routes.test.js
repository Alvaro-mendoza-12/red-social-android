const assert = require('assert')
const jwt = require('jsonwebtoken')
const {
  ensureLivePointsTables,
  issueSessionToken,
} = require('../src/live-points-routes')

async function testPointsSchema() {
  const statements = []
  const pool = {
    async query(statement) {
      statements.push(String(statement).replace(/\s+/g, ' ').trim())
      return { rows: [] }
    },
  }
  await ensureLivePointsTables(pool)
  const schema = statements.join('\n')
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_point_settings/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_channel_points_balances/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_channel_points_earnings/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_channel_point_rewards/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_channel_point_sessions/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_channel_point_redemptions/)
  assert.match(schema, /UNIQUE \(idempotency_key\)/)
  assert.match(schema, /UNIQUE INDEX IF NOT EXISTS idx_fallback_points_active_session/)
}

async function testSignedSessionTokenScope() {
  const previousSecret = process.env.LIVE_POINTS_SESSION_SECRET
  process.env.LIVE_POINTS_SESSION_SECRET = 'novvin-points-test-secret'
  const now = 1_700_000_000
  const token = issueSessionToken({
    liveId: '11111111-1111-4111-8111-111111111111',
    userId: '22222222-2222-4222-8222-222222222222',
    sessionNonce: 'session-1',
    now,
  })
  const claims = jwt.verify(token, process.env.LIVE_POINTS_SESSION_SECRET, { clockTimestamp: now })
  assert.strictEqual(claims.iss, 'novvin-live-points')
  assert.strictEqual(claims.liveId, '11111111-1111-4111-8111-111111111111')
  assert.strictEqual(claims.sessionNonce, 'session-1')
  assert.strictEqual(claims.exp - claims.iat, 2 * 60 * 60)
  if (previousSecret === undefined) delete process.env.LIVE_POINTS_SESSION_SECRET
  else process.env.LIVE_POINTS_SESSION_SECRET = previousSecret
}

Promise.resolve()
  .then(testPointsSchema)
  .then(testSignedSessionTokenScope)
  .then(() => console.log('live points route tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
