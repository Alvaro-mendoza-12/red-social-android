'use strict'

const USERNAME_PATTERN = /^[A-Za-z0-9_.-]{3,24}$/

function cleanUsername(value) {
  return typeof value === 'string' ? value.trim().replace(/^@/, '') : ''
}

async function usernameOwnedByAnother(pool, username, ownerIds = []) {
  const clean = cleanUsername(username).toLowerCase()
  const ids = ownerIds.map((id) => String(id || '').trim().toLowerCase()).filter(Boolean)
  if (!clean) return false
  const { rows } = await pool.query(
    `SELECT 1 FROM fallback_profile_overrides
     WHERE lower(trim(profile->>'username')) = $1 AND lower(user_id::text) <> ALL($2::text[])
     UNION ALL
     SELECT 1 FROM fallback_compat_rows
     WHERE table_name = 'profiles' AND lower(trim(row->>'username')) = $1
       AND lower(row_id) <> ALL($2::text[])
     LIMIT 1`,
    [clean, ids],
  )
  if (rows.length > 0) return true
  const imported = await pool.query("SELECT to_regclass('supabase_import.profiles') AS table_oid")
  if (!imported.rows[0]?.table_oid) return false
  const legacy = await pool.query(
    `SELECT 1 FROM supabase_import.profiles AS profile
     WHERE lower(trim(COALESCE(to_jsonb(profile)->>'username', ''))) = $1
       AND lower(COALESCE(to_jsonb(profile)->>'id', '')) <> ALL($2::text[])
     LIMIT 1`,
    [clean, ids],
  )
  return legacy.rows.length > 0
}

module.exports = { USERNAME_PATTERN, cleanUsername, usernameOwnedByAnother }
