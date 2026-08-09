const assert = require('assert')
const { applyGiftToActiveBattle } = require('../src/live-battle-scoring')

async function run() {
  const statements = []
  let inserted = true
  const client = {
    async query(sql, params) {
      const compact = String(sql).replace(/\s+/g, ' ').trim()
      statements.push({ sql: compact, params })
      if (compact.startsWith('SELECT id::text')) {
        return { rows: [{ id: 'battle-1', left_user_id: 'host-1', right_user_id: 'guest-1', status: 'active' }] }
      }
      if (compact.startsWith('INSERT INTO fallback_live_battle_events')) {
        return { rows: inserted ? [{ id: 'event-1' }] : [] }
      }
      if (compact.startsWith('UPDATE fallback_live_battles')) {
        return { rows: [{ id: 'battle-1', left_score: 70, right_score: 0, score_version: 1 }] }
      }
      return { rows: [] }
    },
  }
  const result = await applyGiftToActiveBattle(client, {
    liveId: '11111111-1111-4111-8111-111111111111',
    giftTransactionId: '22222222-2222-4222-8222-222222222222',
    recipientUserId: 'HOST-1',
    purchasedCoins: 70,
  })
  assert.strictEqual(result.left_score, 70)
  assert.match(statements[2].sql, /SET left_score = left_score \+ \$2/)
  assert.strictEqual(statements[1].params[1], 'gift:22222222-2222-4222-8222-222222222222')

  inserted = false
  const duplicate = await applyGiftToActiveBattle(client, {
    liveId: '11111111-1111-4111-8111-111111111111',
    giftTransactionId: '22222222-2222-4222-8222-222222222222',
    recipientUserId: 'host-1',
    purchasedCoins: 70,
  })
  assert.strictEqual(duplicate, null)

  const promotional = await applyGiftToActiveBattle(client, {
    liveId: '11111111-1111-4111-8111-111111111111',
    giftTransactionId: '33333333-3333-4333-8333-333333333333',
    recipientUserId: 'host-1',
    purchasedCoins: 0,
  })
  assert.strictEqual(promotional, null)
  console.log('live battle scoring tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
