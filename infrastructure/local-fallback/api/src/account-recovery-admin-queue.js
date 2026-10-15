'use strict'

const STATUSES = Object.freeze(['all', 'pending', 'in_review', 'issued', 'completed', 'rejected'])
class RecoveryQueueError extends Error {
  constructor(code, status = 400) { super(code); this.code = code; this.status = status; this.expose = true }
}
function recoveryQueueInput(query = {}) {
  const status = query.status ?? 'pending'
  if (!STATUSES.includes(status)) throw new RecoveryQueueError('RECOVERY_STATUS_INVALID')
  const text = query.q ?? ''
  if (typeof text !== 'string' || text.length > 120) throw new RecoveryQueueError('RECOVERY_SEARCH_INVALID')
  function integer(value, fallback, min, max) {
    if (value == null) return fallback
    if (!/^\d+$/.test(String(value))) throw new RecoveryQueueError('RECOVERY_PAGE_INVALID')
    const parsed = Number(value)
    if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) throw new RecoveryQueueError('RECOVERY_PAGE_INVALID')
    return parsed
  }
  return { status, query: text.trim(), limit: integer(query.limit, 20, 1, 50), offset: integer(query.offset, 0, 0, 1_000_000) }
}
const JOINS = `FROM fallback_account_recovery_cases c
  LEFT JOIN fallback_users u ON u.id=COALESCE(c.user_id,c.candidate_user_id)
  LEFT JOIN fallback_profile_overrides o ON o.user_id=u.id`
const SEARCH = `($2='' OR c.identifier ILIKE $3 OR c.alternate_contact ILIKE $3
  OR c.id::text ILIKE $3 OR u.display_name ILIKE $3 OR o.profile->>'username' ILIKE $3)`
const FILTER = `($1='all' OR c.status=$1) AND ${SEARCH}`
async function loadRecoveryCasePage(pool, input) {
  const options = recoveryQueueInput(input)
  const params = [options.status, options.query, '%' + options.query.replace(/[\\%_]/g, '\\$&') + '%']
  const client = await pool.connect()
  try {
    await client.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY')
    await client.query("SET LOCAL statement_timeout='10s'")
    const statuses = (await client.query(`SELECT c.status,count(*)::int count ${JOINS} WHERE ${SEARCH} AND $1::text IS NOT NULL GROUP BY c.status`, params)).rows
    const total = (await client.query(`SELECT count(*)::int total ${JOINS} WHERE ${FILTER}`, params)).rows[0].total
    // Clamp a stale last page after another administrator resolves its final case.
    const offset = total ? Math.min(options.offset, Math.floor((total - 1) / options.limit) * options.limit) : 0
    const cases = (await client.query(`SELECT c.id,c.identifier,c.alternate_contact,c.description,c.status,c.user_id,
      c.reviewed_by,c.verification_ref,c.two_factor_reset,c.created_at,c.reviewed_at,c.contact_type,c.profile_hint,
      COALESCE(c.user_id,c.candidate_user_id) candidate_user_id,c.contact_verified_at,
      COALESCE((SELECT json_agg(json_build_object('id',e.id,'name',e.name,'mime',e.mime,'size',e.size) ORDER BY e.created_at)
        FROM fallback_recovery_evidence e WHERE e.case_id=c.id AND e.expires_at>NOW()),'[]'::json) evidence,
      u.email candidate_email,u.display_name candidate_name,o.profile->>'username' candidate_username
      ${JOINS} WHERE ${FILTER}
      ORDER BY CASE c.status WHEN 'pending' THEN 0 WHEN 'in_review' THEN 1 WHEN 'issued' THEN 2 ELSE 3 END,
        c.created_at ASC,c.id ASC LIMIT $4 OFFSET $5`, [...params,options.limit,offset])).rows
    await client.query('COMMIT')
    return { cases,total,offset,limit:options.limit,statusCounts:Object.fromEntries(statuses.map(r=>[r.status,r.count])),pageVersion:1 }
  } catch (error) { await client.query('ROLLBACK').catch(()=>{}); throw error }
  finally { client.release() }
}
const TRANSITIONS = Object.freeze({ pending: ['in_review','rejected'], in_review: ['pending','rejected'], issued: ['rejected'] })
async function changeRecoveryReviewState(pool,{caseId,status,actor,reason=''}) {
  if (!['pending','in_review','rejected'].includes(status)) throw new RecoveryQueueError('RECOVERY_STATUS_INVALID')
  if (!actor || typeof reason !== 'string' || reason.length > 240 || (status==='rejected' && reason.trim().length<8)) {
    throw new RecoveryQueueError('REVIEWER_AND_REASON_REQUIRED')
  }
  const client=await pool.connect()
  try {
    await client.query('BEGIN')
    await client.query("SET LOCAL lock_timeout='3s'")
    const row=(await client.query('SELECT id,status FROM fallback_account_recovery_cases WHERE id=$1 FOR UPDATE',[caseId])).rows[0]
    if (!row) throw new RecoveryQueueError('RECOVERY_CASE_NOT_FOUND',404)
    if (!(TRANSITIONS[row.status]||[]).includes(status)) throw new RecoveryQueueError('RECOVERY_STATUS_CONFLICT',409)
    await client.query(`UPDATE fallback_account_recovery_cases SET status=$2,reviewed_by=$3,reviewed_at=NOW(),
      verification_ref=CASE WHEN $4='' THEN verification_ref ELSE $4 END,
      contact_code_hash=CASE WHEN $2='rejected' THEN NULL ELSE contact_code_hash END,
      contact_code_expires_at=CASE WHEN $2='rejected' THEN NULL ELSE contact_code_expires_at END WHERE id=$1`,[caseId,status,actor,reason.trim()])
    if(status==='rejected') await client.query('UPDATE fallback_password_reset_tokens SET consumed_at=NOW() WHERE recovery_case_id=$1 AND consumed_at IS NULL',[caseId])
    await client.query(`INSERT INTO fallback_audit_events(event_name,metadata) VALUES ($1,$2::jsonb)`,
      ['admin_account_recovery_status_changed',JSON.stringify({caseId,previousStatus:row.status,status,reviewedBy:actor,reason:reason.trim()})])
    await client.query('COMMIT')
    return {ok:true,status}
  } catch(error) { await client.query('ROLLBACK').catch(()=>{});throw error }
  finally {client.release()}
}
module.exports={STATUSES,TRANSITIONS,RecoveryQueueError,recoveryQueueInput,loadRecoveryCasePage,changeRecoveryReviewState}
