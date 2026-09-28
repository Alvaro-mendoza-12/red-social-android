'use strict'

const USERNAME_PATTERN = /^[A-Za-z0-9_.-]{3,24}$/
const RESERVED_USERNAMES = new Set([
  'admin', 'administrator', 'administrador', 'administradora', 'soporte',
  'support', 'system', 'sistema', 'moderator', 'moderador', 'moderadora',
  'official', 'oficial', 'root', 'novvin', 'conectate', 'security',
  'seguridad', 'help', 'ayuda', 'api', 'staff', 'team', 'equipo',
  'null', 'undefined', 'guest', 'invitado',
])
const BLOCKED_USERNAME_PARTS = ['porn', 'porno', 'nazi', 'puto', 'puta', 'mierda']

function cleanUsername(value) {
  return typeof value === 'string' ? value.trim().replace(/^@/, '') : ''
}

function usernameProblem(value) {
  const username = cleanUsername(value)
  if (!USERNAME_PATTERN.test(username)) return 'USERNAME_INVALID'
  const lower = username.toLowerCase()
  if (RESERVED_USERNAMES.has(lower) || BLOCKED_USERNAME_PARTS.some((part) => lower.includes(part))) {
    return 'USERNAME_RESERVED'
  }
  return null
}

function usernameSeed(displayName) {
  const seed = String(displayName || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '')
    .slice(0, 24)
  if (seed.length >= 3 && !usernameProblem(seed)) return seed
  const fallback = `usuario${seed}`.slice(0, 24)
  return usernameProblem(fallback) ? 'usuario' : fallback
}

function usernameCandidates(displayName, requested = '') {
  const base = usernameSeed(displayName)
  const root = (cleanUsername(requested).toLowerCase().replace(/[^a-z0-9]/g, '') || base).slice(0, 20)
  const candidates = [base]
  for (let number = 2; number <= 40; number += 1) {
    const suffix = String(number)
    candidates.push(`${root.slice(0, 24 - suffix.length)}${suffix}`)
  }
  return [...new Set(candidates)].filter((value) => !usernameProblem(value))
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

module.exports = {
  USERNAME_PATTERN,
  cleanUsername,
  usernameCandidates,
  usernameOwnedByAnother,
  usernameProblem,
  usernameSeed,
}
