'use strict'
const { deniedPublicCreatorIds } = require('./public-creator-visibility')

async function publicShareProfileAllowed(pool, profile, ownerId = profile.id) {
  const { rows } = await pool.query('SELECT id::text,supabase_user_id::text FROM fallback_users WHERE id::text=$1 OR supabase_user_id::text=$1', [String(ownerId)])
  const owners = [ownerId, profile.id, ...rows.flatMap(row => [row.id, row.supabase_user_id])].filter(Boolean)
  return !(await deniedPublicCreatorIds(pool, { profiles: [profile], ownerIds: owners })).size
}
module.exports = { publicShareProfileAllowed }
