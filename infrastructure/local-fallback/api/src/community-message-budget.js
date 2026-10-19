const { insertMessageOnce } = require('./message-insert-store')

const bounded = value => Math.max(0, Math.min(10000, Math.trunc(Number(value) || 0)))

/** Count and insert on one connection. Retries with the same ID never consume another slot. */
async function insertWithCommunityBudget(pool, input, { isAdmin = false, weeklyLimit = 0, dailyLimit = 0, inTransaction = false } = {}) {
  const weekly = bounded(weeklyLimit), daily = bounded(dailyLimit)
  if (input.kind !== 'community' || isAdmin || (!weekly && !daily)) return insertMessageOnce(pool, input)
  const db = inTransaction ? pool : await pool.connect()
  try {
    if (!inTransaction) await db.query('BEGIN')
    await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))',
      [`community-budget:${input.scopeId}:${input.message.sender_id}`])
    if (input.message.id) {
      const duplicate = await db.query(`SELECT id FROM fallback_room_messages
        WHERE id=$1::uuid AND room_kind='community' AND lower(room_id)=$2
          AND lower(sender_id)=ANY($3::text[])`,
      [input.message.id, input.scopeId, Array.from(input.actorIds)])
      if (duplicate.rows.length) {
        const saved = await insertMessageOnce(db, input)
        if (!inTransaction) await db.query('COMMIT')
        return saved
      }
    }
    const { rows } = await db.query(`SELECT
      COUNT(*) FILTER (WHERE created_at >= date_trunc('week', NOW() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')::int AS weekly_used,
      COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '24 hours')::int AS daily_used,
      (date_trunc('week', NOW() AT TIME ZONE 'UTC') + INTERVAL '1 week') AT TIME ZONE 'UTC' AS resets_at
      FROM fallback_room_messages WHERE room_kind='community' AND lower(room_id)=$1
        AND lower(sender_id)=ANY($2::text[])
        AND created_at >= LEAST(date_trunc('week', NOW() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC', NOW()-INTERVAL '24 hours')`,
    [input.scopeId, Array.from(input.actorIds)])
    const usage = rows[0] || {}
    const weeklyReached = weekly > 0 && Number(usage.weekly_used) >= weekly
    const dailyReached = !weekly && daily > 0 && Number(usage.daily_used) >= daily
    if (weeklyReached || dailyReached) {
      const code = weeklyReached ? 'COMMUNITY_WEEKLY_MESSAGE_LIMIT_REACHED' : 'COMMUNITY_DAILY_MESSAGE_LIMIT_REACHED'
      throw Object.assign(new Error(code), { code, status: 429,
        limit: weeklyReached ? weekly : daily, used: Number(weeklyReached ? usage.weekly_used : usage.daily_used),
        resetsAt: weeklyReached ? usage.resets_at : null })
    }
    const saved = await insertMessageOnce(db, input)
    if (!inTransaction) await db.query('COMMIT')
    return saved
  } catch (error) {
    if (!inTransaction) await db.query('ROLLBACK').catch(() => {})
    throw error
  } finally { if (!inTransaction) db.release() }
}

module.exports = { insertWithCommunityBudget }
