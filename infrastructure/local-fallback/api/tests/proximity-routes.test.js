const assert = require('assert')
const {
  approximateDistanceKm,
  bottleCandidateAllowed,
  bottleOfferRespondable,
  ensureProximityTables,
  haversineKm,
  mutuallyCompatible,
  registerProximityRoutes,
} = require('../src/proximity-routes')

const bogotaToNearby = haversineKm(4.711, -74.072, 4.721, -74.072)
assert.ok(bogotaToNearby > 1 && bogotaToNearby < 1.2)
assert.strictEqual(approximateDistanceKm(0.04), 0.5)
assert.strictEqual(approximateDistanceKm(1.24), 1)
assert.strictEqual(approximateDistanceKm(1.26), 1.5)

assert.strictEqual(
  mutuallyCompatible(
    { gender: 'man', looking_for: 'woman' },
    { gender: 'woman', looking_for: 'man' },
  ),
  true,
)
assert.strictEqual(
  mutuallyCompatible(
    { gender: 'man', looking_for: 'woman' },
    { gender: 'man', looking_for: 'any' },
  ),
  false,
)
assert.strictEqual(
  mutuallyCompatible(
    { gender: 'nonbinary', looking_for: 'any' },
    { gender: 'woman', looking_for: 'any' },
  ),
  true,
)

const candidate = { user_id: 'candidate', gender: 'woman', looking_for: 'man', sender_gender: 'man', sender_looking_for: 'woman' }
assert.strictEqual(bottleCandidateAllowed({
  senderId: 'sender',
  candidate,
  attemptedIds: new Set(),
  blockedIds: new Set(),
  existingPeerIds: new Set(),
  busyRecipientIds: new Set(),
}), true)
assert.strictEqual(bottleCandidateAllowed({
  senderId: 'sender',
  candidate,
  attemptedIds: new Set(['candidate']),
  blockedIds: new Set(),
  existingPeerIds: new Set(),
  busyRecipientIds: new Set(),
}), false)
assert.strictEqual(bottleCandidateAllowed({
  senderId: 'sender',
  candidate,
  attemptedIds: new Set(),
  blockedIds: new Set(),
  existingPeerIds: new Set(),
  busyRecipientIds: new Set(),
  saturatedUserIds: new Set(['candidate']),
}), false)

const freshOffer = {
  status: 'offered',
  current_recipient_id: 'candidate',
  offer_started_at: '2026-08-21T12:00:00.000Z',
  expires_at: '2026-08-21T12:10:00.000Z',
}
assert.strictEqual(
  bottleOfferRespondable(freshOffer, new Set(['candidate']), Date.parse('2026-08-21T12:00:44.999Z')),
  true,
)
assert.strictEqual(
  bottleOfferRespondable(freshOffer, new Set(['candidate']), Date.parse('2026-08-21T12:00:45.000Z')),
  false,
)
assert.strictEqual(
  bottleOfferRespondable(freshOffer, new Set(['someone-else']), Date.parse('2026-08-21T12:00:10.000Z')),
  false,
)
assert.strictEqual(bottleCandidateAllowed({
  senderId: 'sender',
  candidate,
  attemptedIds: new Set(),
  blockedIds: new Set(['candidate']),
  existingPeerIds: new Set(),
  busyRecipientIds: new Set(),
}), false)

const registered = []
const app = {}
for (const verb of ['get', 'post', 'put', 'patch', 'delete']) {
  app[verb] = (route) => registered.push(`${verb.toUpperCase()} ${route}`)
}
registerProximityRoutes(app, {
  pool: {},
  authRequired: () => {},
  getCurrentUser: async () => null,
  userIdentity: () => ({ values: [], ids: new Set() }),
})
for (const route of [
  'POST /v1/data/proximity/random/availability',
  'DELETE /v1/data/proximity/random/availability',
  'POST /v1/data/proximity/random/bottles',
  'GET /v1/data/proximity/random/bottles/outgoing',
  'GET /v1/data/proximity/random/bottles/incoming',
  'POST /v1/data/proximity/random/bottles/:bottleId/respond',
  'DELETE /v1/data/proximity/random/bottles/:bottleId',
]) {
  assert.ok(registered.includes(route), `missing bottle route: ${route}`)
}

;(async () => {
  const statements = []
  await ensureProximityTables({ query: async (sql) => { statements.push(String(sql)); return { rows: [] } } })
  const schema = statements.join('\n')
  assert.match(schema, /fallback_random_bottle_availability/)
  assert.match(schema, /fallback_random_chat_bottles/)
  console.log('proximity route tests passed')
})().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
