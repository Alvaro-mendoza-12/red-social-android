const assert = require('assert')
const jwt = require('jsonwebtoken')
const {
  ensureLiveTables,
  issueLiveSfuToken,
  liveSupporterRanking,
  liveSfuConfiguration,
  liveSupporterLevel,
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
  assert.match(schema, /initiated_by TEXT NOT NULL DEFAULT 'host'/)
  assert.match(schema, /allow_guest_requests BOOLEAN NOT NULL DEFAULT FALSE/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_participants/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_battles/)
  assert.match(schema, /ADD COLUMN IF NOT EXISTS opponent_live_id/)
  assert.match(schema, /ADD COLUMN IF NOT EXISTS room_name TEXT/)
  assert.match(schema, /ADD COLUMN IF NOT EXISTS invitation_expires_at TIMESTAMPTZ/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_battle_events/)
  assert.match(schema, /UNIQUE INDEX IF NOT EXISTS idx_fallback_live_active_battle/)
  assert.match(schema, /UNIQUE INDEX IF NOT EXISTS idx_fallback_live_active_battle_opponent/)
  assert.match(schema, /UNIQUE \(battle_id, event_id\)/)
}

function testSupporterLevelsAreMonotonic() {
  assert.deepStrictEqual(liveSupporterLevel(0), { level: 1, badge: 'Brisa' })
  assert.strictEqual(liveSupporterLevel(100).level, 2)
  assert.strictEqual(liveSupporterLevel(50_000).badge, 'Galaxia')
  assert.strictEqual(liveSupporterLevel(150_000).level, 8)
}

function testSupporterRankingUsesCommittedGiftAmount() {
  const liveId = '11111111-1111-4111-8111-111111111111'
  const otherLiveId = '99999999-9999-4999-8999-999999999999'
  const ranking = liveSupporterRanking([
    {
      status: 'committed',
      live_stream_id: liveId,
      sender_id: 'USER-A',
      sender_name: 'Ana',
      sender_avatar_url: 'https://cdn.example.test/a.png',
      amount_diamonds: 90,
    },
    {
      status: 'committed',
      live_stream_id: liveId,
      sender_id: 'user-b',
      sender_name: 'Bruno',
      price_diamonds: 40,
    },
    {
      status: 'committed',
      live_stream_id: otherLiveId,
      sender_id: 'user-a',
      sender_name: 'Ana',
      amount_diamonds: 20,
    },
    {
      status: 'committed',
      live_stream_id: liveId,
      sender_id: 'user-c',
      sender_name: 'Cata',
      amount_diamonds: 500,
      refunded_at: '2026-08-08T00:00:00Z',
    },
    {
      status: 'pending',
      live_stream_id: liveId,
      sender_id: 'user-d',
      amount_diamonds: 999,
    },
  ], liveId)

  assert.deepStrictEqual(ranking.map((item) => item.user_id), ['user-a', 'user-b'])
  assert.strictEqual(ranking[0].coins_in_live, 90)
  assert.strictEqual(ranking[0].lifetime_coins, 110)
  assert.strictEqual(ranking[0].rank, 1)
  assert.strictEqual(ranking[0].avatar_url, 'https://cdn.example.test/a.png')
  assert.strictEqual(ranking[1].coins_in_live, 40)
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

async function testCrossLiveTokenUsesTheAuthoritativeBattleRoom() {
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
    roomName: 'novvin-battle-33333333-3333-4333-8333-333333333333',
    userId: '22222222-2222-4222-8222-222222222222',
    displayName: 'Creador rival',
    role: 'host',
    configuration,
    now,
  })
  const claims = jwt.verify(issued.token, configuration.apiSecret, { clockTimestamp: now })
  assert.strictEqual(claims.video.room, 'novvin-battle-33333333-3333-4333-8333-333333333333')
  assert.strictEqual(claims.video.roomJoin, true)
  assert.strictEqual(claims.video.canPublish, true)
  assert.strictEqual(claims.video.canSubscribe, true)
}

async function testCrossLiveRoutesRequireInvitationAcceptance() {
  const source = fs.readFileSync(require.resolve('../src/live-routes'), 'utf8')
  assert.match(source, /\/battles\/candidates/)
  assert.match(source, /\/battles\/:battleId\/respond/)
  assert.match(source, /status='expired'/)
  assert.match(source, /status IN \('invited', 'active'\)/)
  assert.match(source, /novvin-battle-/)
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
  .then(testCrossLiveTokenUsesTheAuthoritativeBattleRoom)
  .then(testCrossLiveRoutesRequireInvitationAcceptance)
  .then(testSfuConfigurationDefaultsDisabled)
  .then(testIdentityMembershipUsesSetApi)
  .then(testSupporterLevelsAreMonotonic)
  .then(testSupporterRankingUsesCommittedGiftAmount)
  .then(() => console.log('live collaboration route tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
