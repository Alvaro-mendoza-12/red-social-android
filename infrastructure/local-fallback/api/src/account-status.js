function normalizeIdentityValue(value) {
  return String(value || '').trim().toLowerCase()
}

function suspensionEnd(row) {
  return row?.suspended_until || row?.suspension_end_date || null
}

function isActiveSuspension(row, now = Date.now()) {
  if (row?.is_suspended !== true) return false
  const end = suspensionEnd(row)
  if (!end) return true
  const endTime = new Date(end).getTime()
  return Number.isFinite(endTime) && endTime > now
}

async function findActiveSuspension(pool, rawLocalUserId) {
  const localUserId = normalizeIdentityValue(rawLocalUserId)
  if (!localUserId) return null

  const { rows } = await pool.query(
    `SELECT m.is_suspended, m.suspended_until, m.suspension_end_date,
            m.suspension_reason, m.updated_at
     FROM fallback_users u
     INNER JOIN fallback_profile_moderation m
       ON lower(m.user_id) = lower(u.id::text)
       OR lower(m.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
     WHERE lower(u.id::text) = $1
       AND m.is_suspended = TRUE
       AND (
         COALESCE(m.suspended_until, m.suspension_end_date) IS NULL
         OR COALESCE(m.suspended_until, m.suspension_end_date) > NOW()
       )
     ORDER BY (COALESCE(m.suspended_until, m.suspension_end_date) IS NULL) DESC,
              m.updated_at DESC
     LIMIT 1`,
    [localUserId],
  )

  const row = rows[0] || null
  return isActiveSuspension(row) ? row : null
}

function accountSuspendedPayload(row) {
  const until = suspensionEnd(row)
  const reason = String(row?.suspension_reason || '').trim() || null
  return {
    error: 'ACCOUNT_SUSPENDED',
    message: until
      ? 'ACCOUNT_SUSPENDED: Tu cuenta esta suspendida temporalmente.'
      : 'ACCOUNT_SUSPENDED: Tu cuenta esta suspendida permanentemente.',
    is_suspended: true,
    suspended: true,
    reason,
    suspension_reason: reason,
    until,
    suspended_until: until,
    suspension_end_date: until,
  }
}

module.exports = {
  accountSuspendedPayload,
  findActiveSuspension,
  isActiveSuspension,
  suspensionEnd,
}
