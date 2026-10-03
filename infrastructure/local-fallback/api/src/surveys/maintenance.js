'use strict'

// Answers stop being readable at expires_at even if this worker is unavailable.
// A minimal account marker remains to enforce one response per campaign.
async function runSurveyMaintenance(pool) {
  const db = await pool.connect()
  try {
    await db.query('BEGIN')
    const lock = await db.query("SELECT pg_try_advisory_xact_lock(hashtext('surveys:maintenance')) AS acquired")
    if (!lock.rows[0].acquired) { await db.query('COMMIT'); return { expired: 0, busy: true } }
    const campaigns = (await db.query(`SELECT c.id,c.schema FROM survey_campaigns c
      WHERE EXISTS(SELECT 1 FROM survey_responses r WHERE r.campaign_id=c.id AND r.removed_at IS NULL AND r.expires_at<=now())
      ORDER BY c.id LIMIT 20 FOR UPDATE OF c SKIP LOCKED`)).rows
    let expired = 0
    for (const c of campaigns) {
      const responses = (await db.query(`SELECT id,answers FROM survey_responses WHERE campaign_id=$1
        AND removed_at IS NULL AND expires_at<=now() ORDER BY expires_at,id LIMIT 100 FOR UPDATE SKIP LOCKED`, [c.id])).rows
      if (!responses.length) continue
      const assets = responses.flatMap(r => c.schema.questions.filter(q => q.type === 'file').flatMap(q => r.answers[q.id] || []))
      if (assets.length) await db.query('DELETE FROM survey_assets WHERE campaign_id=$1 AND id=ANY($2::uuid[])', [c.id, assets])
      await db.query(`UPDATE survey_responses SET answers='{}',removed_at=now(),edit_locked_at=now(),updated_at=now()
        WHERE id=ANY($1::uuid[])`, [responses.map(r => r.id)])
      await db.query("INSERT INTO survey_audit(campaign_id,action,details) VALUES($1,'responses.expired',$2)", [c.id, { count: responses.length }])
      expired += responses.length
    }
    // Abandoned participant uploads are private and can never become a schema image.
    await db.query(`DELETE FROM survey_assets a WHERE a.id IN (
      SELECT a.id FROM survey_assets a WHERE a.campaign_id IS NOT NULL AND a.created_at<now()-interval '48 hours'
      AND NOT EXISTS(SELECT 1 FROM survey_responses r WHERE r.campaign_id=a.campaign_id AND r.removed_at IS NULL
        AND r.answers::text LIKE '%'||a.id::text||'%') ORDER BY a.created_at LIMIT 100)`)
    await db.query('COMMIT')
    return { expired, busy: false }
  } catch (error) { await db.query('ROLLBACK'); throw error } finally { db.release() }
}

module.exports = { runSurveyMaintenance }
