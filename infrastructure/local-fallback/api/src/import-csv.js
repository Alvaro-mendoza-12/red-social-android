const fs = require('fs')
const { parse } = require('csv-parse/sync')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL

if (!databaseUrl) {
  throw new Error('DATABASE_URL is required')
}

const pool = new Pool({ connectionString: databaseUrl })

function normalizeEmail(value) {
  return String(value || '').trim().toLowerCase()
}

function cleanText(value, maxLength = 160) {
  const text = String(value || '').trim()
  return text ? text.slice(0, maxLength) : ''
}

function isValidEmail(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

function pick(row, names) {
  for (const name of names) {
    if (row[name] != null && String(row[name]).trim() !== '') {
      return row[name]
    }
  }
  return ''
}

function parseJson(value) {
  if (!value) return {}
  if (typeof value === 'object') return value
  try {
    return JSON.parse(String(value))
  } catch {
    return {}
  }
}

function getUserMetadata(row) {
  return parseJson(
    pick(row, [
      'raw_user_meta_data',
      'user_metadata',
      'metadata',
      'raw_app_meta_data',
    ]),
  )
}

function getDisplayName(row) {
  const metadata = getUserMetadata(row)
  const email = normalizeEmail(pick(row, ['email', 'user_email']))
  return cleanText(
    pick(row, ['display_name', 'full_name', 'name', 'username']) ||
      metadata.full_name ||
      metadata.display_name ||
      metadata.name ||
      metadata.username ||
      email.split('@')[0],
    80,
  )
}

function getAvatarUrl(row) {
  const metadata = getUserMetadata(row)
  return cleanText(
    pick(row, ['avatar_url', 'picture', 'photo_url']) ||
      metadata.avatar_url ||
      metadata.picture ||
      metadata.photo_url,
    500,
  ) || null
}

async function importAuthUsers(records) {
  let imported = 0
  let skipped = 0

  for (const row of records) {
    const email = normalizeEmail(pick(row, ['email', 'user_email']))
    const supabaseUserId = cleanText(pick(row, ['id', 'user_id', 'supabase_user_id']), 80) || null

    if (!isValidEmail(email)) {
      skipped += 1
      continue
    }

    await pool.query(
      `INSERT INTO fallback_users(
          email,
          password_hash,
          password_enabled,
          display_name,
          avatar_url,
          supabase_user_id,
          sync_source,
          sync_status,
          supabase_synced_at
       )
       VALUES ($1, NULL, FALSE, $2, $3, $4, 'supabase', 'needs_local_password', NOW())
       ON CONFLICT (email)
       DO UPDATE SET
          supabase_user_id = COALESCE(fallback_users.supabase_user_id, EXCLUDED.supabase_user_id),
          display_name = CASE
            WHEN fallback_users.display_name IS NULL OR fallback_users.display_name = ''
            THEN EXCLUDED.display_name
            ELSE fallback_users.display_name
          END,
          avatar_url = COALESCE(fallback_users.avatar_url, EXCLUDED.avatar_url),
          sync_source = CASE
            WHEN fallback_users.password_hash IS NULL THEN 'supabase'
            ELSE 'both'
          END,
          sync_status = CASE
            WHEN fallback_users.password_hash IS NULL THEN 'needs_local_password'
            ELSE 'synced'
          END,
          sync_error = NULL,
          supabase_synced_at = NOW()`,
      [email, getDisplayName(row), getAvatarUrl(row), supabaseUserId],
    )
    imported += 1
  }

  return { imported, skipped }
}

async function importProfiles(records) {
  let imported = 0
  let skipped = 0

  for (const row of records) {
    const email = normalizeEmail(pick(row, ['email', 'user_email', 'primary_email']))
    const supabaseUserId = cleanText(pick(row, ['id', 'user_id', 'profile_id', 'supabase_user_id']), 80) || null
    const displayName = getDisplayName(row)
    const avatarUrl = getAvatarUrl(row)

    if (isValidEmail(email)) {
      await pool.query(
        `INSERT INTO fallback_users(
            email,
            password_hash,
            password_enabled,
            display_name,
            avatar_url,
            supabase_user_id,
            sync_source,
            sync_status,
            supabase_synced_at
         )
         VALUES ($1, NULL, FALSE, $2, $3, $4, 'supabase', 'needs_local_password', NOW())
         ON CONFLICT (email)
         DO UPDATE SET
            display_name = COALESCE(NULLIF(EXCLUDED.display_name, ''), fallback_users.display_name),
            avatar_url = COALESCE(EXCLUDED.avatar_url, fallback_users.avatar_url),
            supabase_user_id = COALESCE(fallback_users.supabase_user_id, EXCLUDED.supabase_user_id),
            supabase_synced_at = NOW()`,
        [email, displayName, avatarUrl, supabaseUserId],
      )
      imported += 1
      continue
    }

    if (supabaseUserId) {
      const result = await pool.query(
        `UPDATE fallback_users
         SET display_name = COALESCE(NULLIF($2, ''), display_name),
             avatar_url = COALESCE($3, avatar_url),
             supabase_synced_at = NOW()
         WHERE supabase_user_id = $1`,
        [supabaseUserId, displayName, avatarUrl],
      )
      if (result.rowCount > 0) imported += 1
      else skipped += 1
      continue
    }

    skipped += 1
  }

  return { imported, skipped }
}

function readCsv(filePath) {
  const content = fs.readFileSync(filePath, 'utf8')
  return parse(content, {
    bom: true,
    columns: true,
    skip_empty_lines: true,
    trim: true,
  })
}

async function main() {
  const [, , entity, filePath] = process.argv

  if (!entity || !filePath) {
    console.error('Usage: node src/import-csv.js <auth-users|profiles> /imports/file.csv')
    process.exit(2)
  }

  if (!fs.existsSync(filePath)) {
    console.error(`CSV file not found: ${filePath}`)
    process.exit(2)
  }

  const records = readCsv(filePath)
  let result

  if (entity === 'auth-users' || entity === 'users') {
    result = await importAuthUsers(records)
  } else if (entity === 'profiles') {
    result = await importProfiles(records)
  } else {
    console.error(`Unsupported entity: ${entity}`)
    process.exit(2)
  }

  await pool.query(
    `INSERT INTO sync_jobs(job_type, status, totals, started_at, finished_at)
     VALUES ($1, 'completed', $2, NOW(), NOW())`,
    [`csv_${entity}`, result],
  )

  console.log(JSON.stringify({ ok: true, entity, records: records.length, ...result }))
}

main()
  .catch((error) => {
    console.error(error)
    process.exit(1)
  })
  .finally(async () => {
    await pool.end()
  })
