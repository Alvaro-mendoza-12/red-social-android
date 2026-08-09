'use strict'

const COIN_SOURCE_TYPES = Object.freeze({
  PURCHASED: 'PURCHASED',
  PROMOTIONAL: 'PROMOTIONAL',
  ADJUSTMENT: 'ADJUSTMENT',
})

const SPEND_PRIORITY = Object.freeze({
  [COIN_SOURCE_TYPES.PROMOTIONAL]: 0,
  [COIN_SOURCE_TYPES.ADJUSTMENT]: 1,
  [COIN_SOURCE_TYPES.PURCHASED]: 2,
})

function nonNegativeInteger(value) {
  return Math.max(0, Math.trunc(Number(value) || 0))
}

function normalizeCoinSource(value) {
  const normalized = String(value || '').trim().toUpperCase()
  return Object.prototype.hasOwnProperty.call(COIN_SOURCE_TYPES, normalized)
    ? COIN_SOURCE_TYPES[normalized]
    : null
}

function normalizeCoinLot(row) {
  const sourceType = normalizeCoinSource(row?.source_type)
  if (!sourceType) return null
  const originalAmount = nonNegativeInteger(row?.original_amount_diamonds)
  const availableAmount = Math.min(originalAmount, nonNegativeInteger(row?.available_amount_diamonds))
  const reversedAmount = Math.min(originalAmount, nonNegativeInteger(row?.reversed_amount_diamonds))
  const spentAmount = Math.min(
    originalAmount,
    Math.max(nonNegativeInteger(row?.spent_amount_diamonds), originalAmount - availableAmount - reversedAmount),
  )
  return {
    ...row,
    source_type: sourceType,
    original_amount_diamonds: originalAmount,
    available_amount_diamonds: availableAmount,
    spent_amount_diamonds: spentAmount,
    reversed_amount_diamonds: reversedAmount,
  }
}

function summarizeCoinLots(rows) {
  const summary = { PURCHASED: 0, PROMOTIONAL: 0, ADJUSTMENT: 0, total: 0 }
  for (const row of Array.isArray(rows) ? rows : []) {
    const lot = normalizeCoinLot(row)
    if (!lot || lot.available_amount_diamonds <= 0) continue
    summary[lot.source_type] += lot.available_amount_diamonds
    summary.total += lot.available_amount_diamonds
  }
  return summary
}

function allocateCoinLots(rows, requestedAmount) {
  const amount = nonNegativeInteger(requestedAmount)
  if (amount <= 0) return { updatedLots: [], allocations: [], breakdown: summarizeCoinLots([]) }
  const lots = (Array.isArray(rows) ? rows : [])
    .map(normalizeCoinLot)
    .filter((lot) => lot && lot.available_amount_diamonds > 0)
    .sort((left, right) => {
      const priority = SPEND_PRIORITY[left.source_type] - SPEND_PRIORITY[right.source_type]
      if (priority !== 0) return priority
      const time = Date.parse(String(left.created_at || '')) - Date.parse(String(right.created_at || ''))
      if (Number.isFinite(time) && time !== 0) return time
      return String(left.id || '').localeCompare(String(right.id || ''))
    })
  if (summarizeCoinLots(lots).total < amount) {
    const error = new Error('COIN_LEDGER_INSUFFICIENT')
    error.code = 'COIN_LEDGER_INSUFFICIENT'
    throw error
  }
  let remaining = amount
  const allocations = []
  const updatedLots = []
  for (const lot of lots) {
    const applied = Math.min(remaining, lot.available_amount_diamonds)
    const updated = applied > 0
      ? {
          ...lot,
          available_amount_diamonds: lot.available_amount_diamonds - applied,
          spent_amount_diamonds: lot.spent_amount_diamonds + applied,
        }
      : lot
    updatedLots.push(updated)
    if (applied > 0) {
      allocations.push({
        lot_id: String(lot.id || ''),
        source_type: lot.source_type,
        source_reference: lot.source_reference || null,
        amount_diamonds: applied,
      })
      remaining -= applied
    }
  }
  const breakdown = { PURCHASED: 0, PROMOTIONAL: 0, ADJUSTMENT: 0, total: amount }
  for (const allocation of allocations) breakdown[allocation.source_type] += allocation.amount_diamonds
  return { updatedLots, allocations, breakdown }
}

function netWithdrawableGiftAmount(gift, reversedPurchasedAmount = null) {
  const purchased = nonNegativeInteger(
    gift?.purchased_amount_diamonds ??
      gift?.withdrawable_amount_diamonds ??
      gift?.amount_diamonds ??
      gift?.price_diamonds,
  )
  const reversed = reversedPurchasedAmount == null
    ? nonNegativeInteger(gift?.reversed_purchased_amount_diamonds)
    : nonNegativeInteger(reversedPurchasedAmount)
  return Math.max(0, purchased - reversed)
}

module.exports = {
  COIN_SOURCE_TYPES,
  allocateCoinLots,
  netWithdrawableGiftAmount,
  nonNegativeInteger,
  normalizeCoinLot,
  normalizeCoinSource,
  summarizeCoinLots,
}
