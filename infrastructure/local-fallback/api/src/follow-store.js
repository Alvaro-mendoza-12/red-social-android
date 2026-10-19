const normalize = value => String(value || '').trim().toLowerCase()
const values = user => [...new Set([user.id, user.supabase_user_id].map(normalize).filter(Boolean))]
const preferred = user => normalize(user.supabase_user_id || user.id)

async function ensureFollowState(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_follow_overrides (
    follower_id TEXT NOT NULL, following_id TEXT NOT NULL, active BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(follower_id,following_id))`)
  const compatibility = await pool.query("SELECT to_regclass('public.fallback_compat_rows') AS oid")
  if (!compatibility.rows[0]?.oid) return
  // Previous app versions wrote follows through the compatibility endpoint.
  // Backfill only rows tied to real accounts and never override a later unfollow.
  await pool.query(`INSERT INTO fallback_follows(follower_id,following_id)
    SELECT DISTINCT lower(COALESCE(NULLIF(a.supabase_user_id::text,''),a.id::text)),
      lower(COALESCE(NULLIF(b.supabase_user_id::text,''),b.id::text))
    FROM fallback_compat_rows c JOIN fallback_users a ON lower(c.row->>'follower_id') IN (lower(a.id::text),lower(COALESCE(a.supabase_user_id::text,'')))
    JOIN fallback_users b ON lower(c.row->>'following_id') IN (lower(b.id::text),lower(COALESCE(b.supabase_user_id::text,'')))
    WHERE c.table_name='follows' AND COALESCE(c.row->>'is_deleted','false') <> 'true' AND a.id<>b.id
      AND NOT EXISTS (SELECT 1 FROM fallback_follow_overrides o WHERE o.follower_id=lower(c.row->>'follower_id') AND o.following_id=lower(c.row->>'following_id'))
    ON CONFLICT DO NOTHING`)
}

/** Authoritative local overrides prevent an old imported follow from returning after unfollow. */
async function visibleFollowRows(pool, rows) {
  if (!rows.length) return rows
  const { rows: states } = await pool.query(`SELECT follower_id,following_id,active FROM fallback_follow_overrides
    WHERE follower_id=ANY($1::text[]) AND following_id=ANY($2::text[])`, [
    [...new Set(rows.map(row => normalize(row.follower_id || row.user_id)))],
    [...new Set(rows.map(row => normalize(row.following_id || row.target_user_id)))],
  ])
  const removed = new Set(states.filter(row => !row.active).map(row => `${row.follower_id}/${row.following_id}`))
  return rows.filter(row => !removed.has(`${normalize(row.follower_id || row.user_id)}/${normalize(row.following_id || row.target_user_id)}`))
}

async function setFollowState(pool, actor, target, following) {
  const actorIds = values(actor), targetIds = values(target)
  if (actorIds.some(id => targetIds.includes(id))) throw Object.assign(new Error('CANNOT_FOLLOW_SELF'), { status: 400 })
  const follower = preferred(actor), followed = preferred(target)
  const db = await pool.connect()
  try {
    await db.query('BEGIN')
    await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`follow:${follower}:${followed}`])
    const previous = await db.query(`SELECT active FROM fallback_follow_overrides WHERE follower_id=$1 AND following_id=$2`, [follower, followed])
    const existing = await db.query(`DELETE FROM fallback_follows WHERE lower(follower_id)=ANY($1::text[]) AND lower(following_id)=ANY($2::text[]) RETURNING id`, [actorIds,targetIds])
    for (const source of actorIds) for (const destination of targetIds) {
      await db.query(`INSERT INTO fallback_follow_overrides(follower_id,following_id,active) VALUES($1,$2,$3)
        ON CONFLICT(follower_id,following_id) DO UPDATE SET active=EXCLUDED.active,updated_at=NOW()`, [source,destination,following && source===follower && destination===followed])
    }
    if (following) await db.query(`INSERT INTO fallback_follows(follower_id,following_id) VALUES($1,$2) ON CONFLICT DO NOTHING`, [follower,followed])
    await db.query('COMMIT')
    return { changed: previous.rows[0] ? previous.rows[0].active !== following : (following ? existing.rows.length === 0 : existing.rows.length > 0), followerId: follower, followingId: followed, aliases: [...actorIds,...targetIds] }
  } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error }
  finally { db.release() }
}

async function publishFollowChange(result) {
  const { publishRealtimeEvent } = require('./realtime-publish')
  await Promise.all([...new Set(result.aliases)].map(profileId => publishRealtimeEvent(`profile-counts:${profileId}`, { event: 'profile_counts_updated', profileId })))
}
module.exports = { ensureFollowState, visibleFollowRows, setFollowState, publishFollowChange }
