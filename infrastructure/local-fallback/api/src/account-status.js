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
            m.suspension_reason, m.updated_at,
            d.id::text AS decision_id,
            d.rule_code,
            d.rule_title,
            d.explanation,
            d.evidence,
            d.severity,
            d.appeal_deadline,
            appeal.id::text AS appeal_id,
            appeal.status AS appeal_status,
            appeal.reviewer_note AS appeal_reviewer_note
     FROM fallback_users u
     INNER JOIN fallback_profile_moderation m
       ON lower(m.user_id) = lower(u.id::text)
       OR lower(m.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
     LEFT JOIN fallback_moderation_decisions d ON d.id = m.decision_id
     LEFT JOIN LATERAL (
       SELECT a.id, a.status, a.reviewer_note
       FROM fallback_moderation_appeals a
       WHERE a.decision_id = d.id
       ORDER BY a.created_at DESC
       LIMIT 1
     ) appeal ON TRUE
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
  const evidence = Array.isArray(row?.evidence)
    ? row.evidence.map((entry) => ({
      source: String(entry?.source || 'manual'),
      label: String(entry?.label || ''),
    })).filter((entry) => entry.label)
    : []
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
    decision_id: row?.decision_id || null,
    rule_code: row?.rule_code || 'community_standards_other',
    rule_title: row?.rule_title || 'Normas de la comunidad',
    explanation: row?.explanation || reason,
    evidence,
    severity: row?.severity || 'high',
    appeal_deadline: row?.appeal_deadline || null,
    appeal_id: row?.appeal_id || null,
    appeal_status: row?.appeal_status || null,
    appeal_reviewer_note: row?.appeal_reviewer_note || null,
  }
}

module.exports = {
  accountSuspendedPayload,
  findActiveSuspension,
  isActiveSuspension,
  suspensionEnd,
}
