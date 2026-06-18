const { Pool } = require('pg')

const args = new Set(process.argv.slice(2))

function trimTrailingSlash(value) {
  return String(value || '').trim().replace(/\/+$/, '')
}

function boolEnv(name, defaultValue = false) {
  const raw = process.env[name]
  if (raw == null || raw === '') return defaultValue
  return /^(1|true|yes|on)$/i.test(String(raw).trim())
}

function splitList(value) {
  return String(value || '')
    .split(',')
    .map((entry) => trimTrailingSlash(entry))
    .filter(Boolean)
}

function quoteIdent(value) {
  return `"${String(value).replace(/"/g, '""')}"`
}

function qualifiedTable(column) {
  return `${quoteIdent(column.table_schema)}.${quoteIdent(column.table_name)}`
}

function isJsonColumn(column) {
  return column.udt_name === 'jsonb' || column.udt_name === 'json'
}

function printUsage() {
  console.log(`Usage:
  node src/rewrite-supabase-storage-urls.js --dry-run
  node src/rewrite-supabase-storage-urls.js --apply

Required environment:
  DATABASE_URL
  PUBLIC_BASE_URL
  SUPABASE_URL or SUPABASE_STORAGE_SOURCE_BASE_URLS

Optional environment:
  SUPABASE_STORAGE_REWRITE_APPLY=true
  SUPABASE_STORAGE_REWRITE_SCHEMAS=public,supabase_import`)
}

async function getCandidateColumns(pool, schemas) {
  const { rows } = await pool.query(`
    SELECT c.table_schema, c.table_name, c.column_name, c.data_type, c.udt_name
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema
     AND t.table_name = c.table_name
     AND t.table_type = 'BASE TABLE'
    WHERE c.table_schema = ANY($1::text[])
      AND (
        c.data_type IN ('text', 'character varying', 'character')
        OR c.udt_name IN ('citext', 'jsonb', 'json')
      )
    ORDER BY c.table_schema, c.table_name, c.ordinal_position
  `, [schemas])
  return rows
}

async function countMatches(pool, column, sourcePrefix) {
  const table = qualifiedTable(column)
  const col = quoteIdent(column.column_name)
  const expression = isJsonColumn(column) ? `${col}::text` : col
  const { rows } = await pool.query(
    `SELECT COUNT(*)::int AS count
     FROM ${table}
     WHERE ${expression} LIKE $1`,
    [`%${sourcePrefix}%`],
  )
  return Number(rows[0]?.count || 0)
}

async function updateMatches(pool, column, sourcePrefix, targetPrefix) {
  const table = qualifiedTable(column)
  const col = quoteIdent(column.column_name)

  if (isJsonColumn(column)) {
    const castType = column.udt_name === 'json' ? 'json' : 'jsonb'
    const { rowCount } = await pool.query(
      `UPDATE ${table}
       SET ${col} = replace(${col}::text, $1, $2)::${castType}
       WHERE ${col}::text LIKE $3`,
      [sourcePrefix, targetPrefix, `%${sourcePrefix}%`],
    )
    return rowCount
  }

  const { rowCount } = await pool.query(
    `UPDATE ${table}
     SET ${col} = replace(${col}, $1, $2)
     WHERE ${col} LIKE $3`,
    [sourcePrefix, targetPrefix, `%${sourcePrefix}%`],
  )
  return rowCount
}

const help = args.has('--help') || args.has('-h')
const apply = args.has('--apply') || boolEnv('SUPABASE_STORAGE_REWRITE_APPLY', false)
const dryRun = args.has('--dry-run') || !apply
const databaseUrl = String(process.env.DATABASE_URL || '').trim()
const publicBaseUrl = trimTrailingSlash(process.env.PUBLIC_BASE_URL || process.env.LOCAL_PUBLIC_BASE_URL)
const rewriteSchemas = splitList(process.env.SUPABASE_STORAGE_REWRITE_SCHEMAS || 'public,supabase_import')
const sourceBases = splitList(process.env.SUPABASE_STORAGE_SOURCE_BASE_URLS)
const supabaseUrl = trimTrailingSlash(process.env.SUPABASE_URL)
if (supabaseUrl && !sourceBases.includes(supabaseUrl)) sourceBases.push(supabaseUrl)

async function main() {
  if (help) {
    printUsage()
    return
  }

  if (!databaseUrl || !publicBaseUrl || sourceBases.length === 0) {
    printUsage()
    throw new Error('DATABASE_URL, PUBLIC_BASE_URL, and SUPABASE_URL are required')
  }

  const sourcePrefixes = sourceBases.map((baseUrl) => `${baseUrl}/storage/v1/object/public/`)
  const targetPrefix = `${publicBaseUrl}/storage/v1/object/public/`

  console.log(`Supabase storage URL rewrite ${dryRun ? '(dry-run)' : '(apply)'}`)
  console.log(`Schemas: ${rewriteSchemas.join(', ')}`)
  console.log(`Target: ${targetPrefix}`)

  const pool = new Pool({ connectionString: databaseUrl })
  const summary = []

  try {
    const columns = await getCandidateColumns(pool, rewriteSchemas)

    for (const sourcePrefix of sourcePrefixes) {
      console.log(`Source: ${sourcePrefix}`)

      for (const column of columns) {
        const matches = await countMatches(pool, column, sourcePrefix)
        if (matches === 0) continue

        const item = {
          table: `${column.table_schema}.${column.table_name}`,
          column: column.column_name,
          type: column.udt_name,
          matches,
          updated: 0,
        }

        if (!dryRun) {
          item.updated = await updateMatches(pool, column, sourcePrefix, targetPrefix)
        }

        summary.push(item)
        console.log(`${dryRun ? 'would update' : 'updated'} ${item.table}.${item.column}: ${matches}`)
      }
    }
  } finally {
    await pool.end()
  }

  console.log('Summary:')
  console.log(JSON.stringify(summary, null, 2))

  if (dryRun) {
    console.log('Dry-run finished. Run with --apply after the storage import is complete.')
  }
}

main().catch((error) => {
  console.error(error.message)
  process.exitCode = 1
})
