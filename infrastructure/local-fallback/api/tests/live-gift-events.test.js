'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { giftEvent, registerLiveGiftRoutes } = require('../src/live-gift-events')
const liveId = '11111111-1111-4111-8111-111111111111'
const identity = { values: ['viewer'] }
const receipt = {
  id: 'transaction-1', live_stream_id: liveId, status: 'committed', is_public: true,
  sender_id: 'sender', sender_name: 'Sender', receiver_id: 'host', gift_id: 'gift', gift_name: 'Gift',
  gift_image_url: 'conectate://gift-assets/pulse', gift_animation_url: 'conectate://gift-animations/pulse',
  amount_diamonds: 100, created_at: '2026-09-12T20:00:00.000Z', sender_balance_after: 9900,
}

test('receipt recovery exposes committed artwork but no wallet or earning details', () => {
  const event = giftEvent(receipt, liveId, identity)
  assert.equal(event.transactionId, receipt.id)
  assert.equal(event.gift.animationUrl, receipt.gift_animation_url)
  assert.equal(event.gift.priceDiamonds, 100)
  assert.equal(event.createdAtMs, Date.parse(receipt.created_at))
  assert.equal(event.sender_balance_after, undefined)
})

test('private gifts are visible only to their sender and receiver', () => {
  const privateReceipt = { ...receipt, is_public: false }
  assert.equal(giftEvent(privateReceipt, liveId, identity), null)
  assert.ok(giftEvent(privateReceipt, liveId, { values: ['host'] }))
  assert.ok(giftEvent(privateReceipt, liveId, { values: ['sender'] }))
  assert.equal(giftEvent({ ...receipt, is_public: undefined }, liveId, identity), null)
})

test('uncommitted, foreign, malformed and reversed receipts never trigger celebrations', () => {
  for (const override of [
    { status: 'pending' }, { status: 'refunded' }, { live_stream_id: 'other' },
    { amount_diamonds: 0 }, { amount_diamonds: NaN }, { created_at: 'invalid' },
  ]) assert.equal(giftEvent({ ...receipt, ...override }, liveId, identity), null)
})

async function request({ visible = true, active = true, owner = false } = {}) {
  let handler
  let queried = false
  const authRequired = () => {}
  const app = { get: (path, auth, route) => { assert.equal(path, '/v1/data/live/:liveId/gifts'); assert.equal(auth, authRequired); handler = route } }
  const response = { statusCode: 200, headers: {}, status(code) { this.statusCode = code; return this }, json(value) { this.body = value; return this }, setHeader(key, value) { this.headers[key] = value } }
  registerLiveGiftRoutes(app, {
    authRequired,
    pool: { query: async (sql, params) => {
      queried = true
      assert.deepEqual(params, [liveId, ['viewer']])
      assert.match(sql, /LIMIT 256/)
      assert.match(sql, /row->>'live_stream_id' = \$1/)
      return { rows: [
        { row: { ...receipt, id: 'second', created_at: '2026-09-12T20:01:00Z' } },
        { row: receipt },
      ] }
    } },
    getCurrentUser: async () => ({}), userIdentity: () => identity, validUuid: value => value,
    streamById: async () => ({ is_active: active }), canViewStream: async () => visible, ownsStream: () => owner,
  })
  await handler({ params: { liveId } }, response, error => { throw error })
  return { response, queried }
}

test('inaccessible or ended live rejects gift history before querying the ledger', async () => {
  for (const options of [{ visible: false }, { active: false }]) {
    const result = await request(options)
    assert.equal(result.response.statusCode, 404)
    assert.equal(result.queried, false)
  }
})

test('host can recover ended session and clients receive stable oldest-first IDs', async () => {
  const { response } = await request({ active: false, owner: true })
  assert.equal(response.statusCode, 200)
  assert.equal(response.headers['Cache-Control'], 'private, no-store')
  assert.deepEqual(response.body.items.map(it => it.transactionId), ['transaction-1', 'second'])
})
