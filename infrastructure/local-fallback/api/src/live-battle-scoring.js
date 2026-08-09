function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function positiveInteger(value) {
  const parsed = Math.trunc(Number(value) || 0)
  return parsed > 0 ? parsed : 0
}

async function applyGiftToActiveBattle(client, {
  liveId,
  giftTransactionId,
  recipientUserId,
  purchasedCoins,
}) {
  const live = normalizeId(liveId)
  const eventId = normalizeId(giftTransactionId)
  const recipient = normalizeId(recipientUserId)
  const points = positiveInteger(purchasedCoins)
  if (!live || !eventId || !recipient || points <= 0) return null

  const battleResult = await client.query(
    `SELECT id::text, live_id::text, left_user_id, right_user_id, status
     FROM fallback_live_battles
     WHERE live_id = $1::uuid AND status = 'active'
     ORDER BY started_at DESC
     LIMIT 1
     FOR UPDATE`,
    [live],
  )
  const battle = battleResult.rows[0]
  if (!battle) return null
  const side = recipient === normalizeId(battle.left_user_id)
    ? 'left_score'
    : recipient === normalizeId(battle.right_user_id) ? 'right_score' : null
  if (!side) return null

  const inserted = await client.query(
    `INSERT INTO fallback_live_battle_events(
       battle_id, event_id, source, event_type, actor_user_id, points
     ) VALUES ($1::uuid, $2, 'gift_ledger', 'virtual_gift', $3, $4)
     ON CONFLICT (battle_id, event_id) DO NOTHING
     RETURNING id::text`,
    [battle.id, `gift:${eventId}`, recipient, points],
  )
  if (!inserted.rows[0]) return null
  const updated = await client.query(
    `UPDATE fallback_live_battles
     SET ${side} = ${side} + $2,
         score_version = score_version + 1,
         updated_at = NOW()
     WHERE id = $1::uuid AND status = 'active'
     RETURNING id::text, live_id::text, left_user_id, right_user_id, status,
               score_version, left_score, right_score, winner_user_id,
               started_at, ended_at, created_at, updated_at`,
    [battle.id, points],
  )
  return updated.rows[0] || null
}

module.exports = {
  applyGiftToActiveBattle,
  positiveInteger,
}
