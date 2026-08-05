const assert = require('assert')
const jwt = require('jsonwebtoken')
const {
  ensureLiveTables,
  issueLiveSfuToken,
  liveSfuConfiguration,
} = require('../src/live-routes')
const fs = require('fs')

async function testCollaborationSchema() {
  const statements = []
  const pool = {
    async query(statement) {
      statements.push(String(statement).replace(/\s+/g, ' ').trim())
      return { rows: [] }
    },
  }
  await ensureLiveTables(pool)
  const schema = statements.join('\n')
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_guest_invitations/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_participants/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_battles/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_battle_events/)
  assert.match(schema, /UNIQUE INDEX IF NOT EXISTS idx_fallback_live_active_battle/)
  assert.match(schema, /UNIQUE \(battle_id, event_id\)/)
}

async function testSfuTokenIsScopedAndShortLived() {
  const configuration = {
    enabled: true,
    url: 'wss://live.example.test',
    apiKey: 'novvin-test-key',
    apiSecret: 'novvin-test-secret',
    maxGuests: 4,
  }
  const now = 1_700_000_000
  const issued = issueLiveSfuToken({
    liveId: '11111111-1111-4111-8111-111111111111',
    userId: '22222222-2222-4222-8222-222222222222',
    displayName: 'Invitado',
    role: 'guest',
    configuration,
    now,
  })
  const claims = jwt.verify(issued.token, configuration.apiSecret, { clockTimestamp: now })
  assert.strictEqual(claims.iss, configuration.apiKey)
  assert.strictEqual(claims.sub, '22222222-2222-4222-8222-222222222222')
  assert.strictEqual(claims.video.room, 'novvin-live-11111111-1111-4111-8111-111111111111')
  assert.strictEqual(claims.video.roomJoin, true)
  assert.strictEqual(claims.video.canPublish, true)
  assert.strictEqual(claims.video.canSubscribe, true)
  assert.strictEqual(issued.expiresAt - claims.iat, 600)
}

async function testSfuConfigurationDefaultsDisabled() {
  const previousFlag = process.env.LIVE_SFU_ENABLED
  const previousUrl = process.env.LIVEKIT_URL
  delete process.env.LIVE_SFU_ENABLED
  delete process.env.LIVEKIT_URL
  const configuration = liveSfuConfiguration()
  if (previousFlag === undefined) delete process.env.LIVE_SFU_ENABLED
  else process.env.LIVE_SFU_ENABLED = previousFlag
  if (previousUrl === undefined) delete process.env.LIVEKIT_URL
  else process.env.LIVEKIT_URL = previousUrl
  assert.strictEqual(configuration.enabled, false)
  assert.strictEqual(configuration.url, '')
}

async function testIdentityMembershipUsesSetApi() {
  const source = fs.readFileSync(require.resolve('../src/live-routes'), 'utf8')
  assert.doesNotMatch(source, /identity\.ids\.includes\(/)
  assert.match(source, /identity\.ids\.has\(/)
}

Promise.resolve()
  .then(testCollaborationSchema)
  .then(testSfuTokenIsScopedAndShortLived)
  .then(testSfuConfigurationDefaultsDisabled)
  .then(testIdentityMembershipUsesSetApi)
  .then(() => console.log('live collaboration route tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
