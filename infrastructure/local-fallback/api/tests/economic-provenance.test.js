'use strict'

const assert = require('node:assert/strict')
const {
  allocateCoinLots,
  netWithdrawableGiftAmount,
  summarizeCoinLots,
} = require('../src/economic-provenance')

function lot(id, sourceType, available, createdAt) {
  return {
    id,
    source_type: sourceType,
    source_reference: id,
    original_amount_diamonds: available,
    available_amount_diamonds: available,
    spent_amount_diamonds: 0,
    reversed_amount_diamonds: 0,
    created_at: createdAt,
  }
}

{
  const result = allocateCoinLots([
    lot('paid', 'PURCHASED', 70, '2026-01-01T00:00:00Z'),
    lot('promo', 'PROMOTIONAL', 20, '2026-01-02T00:00:00Z'),
    lot('adjustment', 'ADJUSTMENT', 10, '2026-01-03T00:00:00Z'),
  ], 35)
  assert.deepEqual(result.allocations.map(({ lot_id, amount_diamonds }) => [lot_id, amount_diamonds]), [
    ['promo', 20],
    ['adjustment', 10],
    ['paid', 5],
  ])
  assert.deepEqual(result.breakdown, { PURCHASED: 5, PROMOTIONAL: 20, ADJUSTMENT: 10, total: 35 })
}

{
  assert.throws(
    () => allocateCoinLots([lot('promo', 'PROMOTIONAL', 5, '2026-01-01T00:00:00Z')], 6),
    (error) => error?.code === 'COIN_LEDGER_INSUFFICIENT',
  )
}

{
  const summary = summarizeCoinLots([
    lot('paid', 'PURCHASED', 7, '2026-01-01T00:00:00Z'),
    lot('promo', 'PROMOTIONAL', 2, '2026-01-01T00:00:00Z'),
  ])
  assert.deepEqual(summary, { PURCHASED: 7, PROMOTIONAL: 2, ADJUSTMENT: 0, total: 9 })
}

{
  assert.equal(netWithdrawableGiftAmount({ purchased_amount_diamonds: 40 }, 15), 25)
  assert.equal(netWithdrawableGiftAmount({ amount_diamonds: 40, reversed_purchased_amount_diamonds: 40 }), 0)
}

console.log('economic provenance tests passed')
