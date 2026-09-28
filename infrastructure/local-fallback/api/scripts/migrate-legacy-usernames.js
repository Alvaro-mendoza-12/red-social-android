'use strict'

// Run without --apply to preview counts. Back up PostgreSQL before --apply.
const { Client } = require('pg')
const { usernameSeed, usernameProblem } = require('../src/username-policy')
const OFFICIAL_NOVVIN_ACCOUNT_ID = '7e3737e5-8826-4096-8973-089f0661462a'

function allocateLegacyHandle(displayName, owner, owners) {
  const seed = usernameSeed(displayName)
  for (let suffix = 1; suffix <= 100000; suffix += 1) {
    const digits = suffix === 1 ? '' : String(suffix)
    const candidate = `${seed.slice(0, 24 - digits.length)}${digits}`
    const occupied = owners.get(candidate)
    if (!usernameProblem(candidate) && (!occupied || (occupied.size === 1 && occupied.has(owner)))) {
      return candidate
    }
  }
  throw new Error('USERNAME_NAMESPACE_EXHAUSTED')
}

function reserve(owners, handle, owner) {
  const key = String(handle || '').trim().toLowerCase()
  if (!key) return
  if (!owners.has(key)) owners.set(key, new Set())
  owners.get(key).add(owner)
}

function handleForLegacyUser(user, displayName, owners) {
  // The existing official account owns the reserved brand name. Keep the
  // reservation for all other users and for all future registrations.
  if (user.id === OFFICIAL_NOVVIN_ACCOUNT_ID && displayName.trim().toLowerCase() === 'novvin') {
    const occupied = owners.get('novvin')
    if (!occupied || (occupied.size === 1 && occupied.has(user.id))) return 'novvin'
  }
  return allocateLegacyHandle(displayName, user.id, owners)
}

async function migrate(client, apply = false) {
  await client.query('BEGIN')
  try {
    // A short exclusive lock keeps registration and profile writes from racing
    // with the name allocation. The unique index is an additional safeguard.
    await client.query('LOCK TABLE fallback_users, fallback_profile_overrides IN SHARE ROW EXCLUSIVE MODE')
    const users = (await client.query(`SELECT u.id::text AS id, u.supabase_user_id::text AS imported_id,
      u.display_name, o.profile
      FROM fallback_users u LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
      ORDER BY u.created_at, u.id`)).rows
    const aliases = new Map()
    for (const user of users) {
      aliases.set(user.id, user.id)
      if (user.imported_id) aliases.set(user.imported_id, user.id)
    }
    const owners = new Map()
    for (const user of users) reserve(owners, user.profile?.username, user.id)
    const compat = (await client.query(`SELECT row_id, row->>'username' AS username
      FROM fallback_compat_rows WHERE table_name = 'profiles'`)).rows
    for (const row of compat) reserve(owners, row.username, aliases.get(row.row_id) || row.row_id)
    const hasImport = (await client.query("SELECT to_regclass('supabase_import.profiles') AS table_oid")).rows[0]?.table_oid
    const imported = hasImport
      ? (await client.query('SELECT to_jsonb(p) AS profile FROM supabase_import.profiles p')).rows.map((row) => row.profile)
      : []
    for (const profile of imported) {
      reserve(owners, profile.username, aliases.get(profile.id) || profile.id)
    }
    let assigned = 0
    let preserved = 0
    let suffixed = 0
    for (const user of users) {
      if (String(user.profile?.username || '').trim()) { preserved += 1; continue }
      const displayName = user.profile?.display_name || user.display_name || 'Usuario'
      const handle = handleForLegacyUser(user, displayName, owners)
      if (handle !== usernameSeed(displayName) && handle !== 'novvin') suffixed += 1
      reserve(owners, handle, user.id)
      if (apply) {
        await client.query(`INSERT INTO fallback_profile_overrides (user_id, profile, updated_at)
          VALUES ($1::uuid, jsonb_build_object('username', $2::text, 'username_origin', 'migrated_name'), NOW())
          ON CONFLICT (user_id) DO UPDATE SET
            profile = fallback_profile_overrides.profile || EXCLUDED.profile,
            updated_at = NOW()`, [user.id, handle])
      }
      assigned += 1
    }
    if (apply) {
      const result = await client.query(`SELECT count(*)::int AS total,
        count(*) FILTER (WHERE NULLIF(profile->>'username', '') IS NOT NULL)::int AS handles,
        count(DISTINCT lower(profile->>'username'))::int AS distinct_handles
        FROM fallback_profile_overrides`)
      if (result.rows[0].handles !== users.length || result.rows[0].distinct_handles !== users.length) {
        throw new Error('USERNAME_MIGRATION_VERIFICATION_FAILED')
      }
      await client.query('COMMIT')
    } else {
      await client.query('ROLLBACK')
    }
    return { mode: apply ? 'applied' : 'dry_run', accounts: users.length, assigned, preserved, suffixed,
      importedOnly: imported.filter((profile) => !aliases.has(profile.id)).length }
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    throw error
  }
}

async function main() {
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required')
  const client = new Client({ connectionString: process.env.DATABASE_URL })
  await client.connect()
  try { process.stdout.write(`${JSON.stringify(await migrate(client, process.argv.includes('--apply')))}\n`) }
  finally { await client.end() }
}

if (require.main === module) main().catch((error) => {
  process.stderr.write(`${error.message}\n`)
  process.exitCode = 1
})

module.exports = { allocateLegacyHandle, handleForLegacyUser, migrate }
