const fs = require('fs')
const path = require('path')
const { parse } = require('csv-parse/sync')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const importsDir = process.argv[2] || '/imports'

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

function sanitizeIdentifier(value) {
  const cleaned = String(value || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 55)

  return /^[a-z_]/.test(cleaned) ? cleaned : `c_${cleaned || 'column'}`
}

function quoteIdentifier(value) {
  return `"${String(value).replace(/"/g, '""')}"`
}

function pick(row, names) {
  for (const name of names) {
    if (row[name] != null && String(row[name]).trim() !== '') return row[name]
  }
  return ''
}

function parseJson(value) {
  if (!value) return {}
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
  const email = normalizeEmail(pick(row, ['email', 'user_email', 'primary_email']))
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

function tableNameFromFile(fileName) {
  return sanitizeIdentifier(
    path
      .basename(fileName, '.csv')
      .replace(/\s+\(\d+\)$/g, '')
      .replace(/_rows$/i, ''),
  )
}

function readCsv(filePath) {
  const content = fs.readFileSync(filePath, 'utf8')
  if (!content.trim()) return { records: [], columns: [] }

  const records = parse(content, {
    bom: true,
    columns: true,
    skip_empty_lines: true,
    trim: true,
    relax_column_count: true,
  })

  const headerLine = content.split(/\r?\n/, 1)[0] || ''
  const columns = parse(headerLine, {
    bom: true,
    relax_column_count: true,
  })[0] || []

  return { records, columns }
}

async function ensureRawSchema() {
  await pool.query('CREATE SCHEMA IF NOT EXISTS supabase_import')
}

async function importRawTable(filePath) {
  const fileName = path.basename(filePath)
  const tableName = tableNameFromFile(fileName)
  const { records, columns } = readCsv(filePath)

  if (columns.length === 0) {
    return { fileName, tableName, records: 0, columns: 0, skipped: true, reason: 'NO_COLUMNS' }
  }

  const sanitizedColumns = []
  const seen = new Map()
  for (const original of columns) {
    const base = sanitizeIdentifier(original)
    const count = seen.get(base) || 0
    seen.set(base, count + 1)
    sanitizedColumns.push({
      original,
      name: count === 0 ? base : `${base}_${count + 1}`,
    })
  }

  const fullTableName = `supabase_import.${quoteIdentifier(tableName)}`
  const columnSql = sanitizedColumns
    .map((column) => `${quoteIdentifier(column.name)} TEXT`)
    .join(',\n    ')

  await pool.query(`DROP TABLE IF EXISTS ${fullTableName}`)
  await pool.query(`
    CREATE TABLE ${fullTableName} (
      __source_file TEXT NOT NULL,
      __imported_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      ${columnSql}
    )
  `)

  if (records.length === 0) {
    return { fileName, tableName, records: 0, columns: sanitizedColumns.length, skipped: false }
  }

  const dbColumns = ['__source_file', ...sanitizedColumns.map((column) => column.name)]
  const quotedColumns = dbColumns.map(quoteIdentifier).join(', ')
  const placeholders = dbColumns.map((_, index) => `$${index + 1}`).join(', ')
  const insertSql = `INSERT INTO ${fullTableName} (${quotedColumns}) VALUES (${placeholders})`

  for (const row of records) {
    const values = [
      fileName,
      ...sanitizedColumns.map((column) =>
        row[column.original] == null ? null : String(row[column.original]),
      ),
    ]
    await pool.query(insertSql, values)
  }

  return { fileName, tableName, records: records.length, columns: sanitizedColumns.length, skipped: false }
}

async function importProfilesIntoFallback(filePath) {
  const { records } = readCsv(filePath)
  let imported = 0
  let skipped = 0

  for (const row of records) {
    const email = normalizeEmail(pick(row, ['email', 'user_email', 'primary_email']))
    const supabaseUserId = cleanText(pick(row, ['id', 'user_id', 'profile_id', 'supabase_user_id']), 80) || null
    const displayName = getDisplayName(row)
    const avatarUrl = getAvatarUrl(row)

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
      [email, displayName, avatarUrl, supabaseUserId],
    )
    imported += 1
  }

  return { imported, skipped }
}

async function main() {
  if (!fs.existsSync(importsDir)) {
    throw new Error(`Imports folder not found: ${importsDir}`)
  }

  await ensureRawSchema()

  const csvFiles = fs
    .readdirSync(importsDir)
    .filter((file) => file.toLowerCase().endsWith('.csv'))
    .sort()

  const results = []
  let fallbackProfiles = null

  for (const file of csvFiles) {
    const fullPath = path.join(importsDir, file)
    const rawResult = await importRawTable(fullPath)
    results.push(rawResult)

    if (tableNameFromFile(file) === 'profiles') {
      fallbackProfiles = await importProfilesIntoFallback(fullPath)
    }
  }

  const totals = {
    files: results.length,
    rows: results.reduce((sum, result) => sum + result.records, 0),
    fallbackProfiles,
    tables: results,
  }

  await pool.query(
    `INSERT INTO sync_jobs(job_type, status, totals, started_at, finished_at)
     VALUES ('csv_import_all', 'completed', $1, NOW(), NOW())`,
    [totals],
  )

  console.log(JSON.stringify({ ok: true, ...totals }, null, 2))
}

main()
  .catch((error) => {
    console.error(error)
    process.exit(1)
  })
  .finally(async () => {
    await pool.end()
  })
