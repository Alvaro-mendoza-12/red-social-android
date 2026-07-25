const { Pool } = require('pg')

const args = new Set(process.argv.slice(2))
const URL_PREFIX_PATTERN = 'https?://[^/]+/storage/v1/object/public/'

function trimTrailingSlash(value) {
  return String(value || '').trim().replace(/\/+$/, '')
}

function quoteIdent(value) {
  return `"${String(value).replace(/"/g, '""')}"`
}

function splitList(value) {
  return String(value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function qualifiedTable(column) {
  return `${quoteIdent(column.table_schema)}.${quoteIdent(column.table_name)}`
}

function isJsonColumn(column) {
  return column.udt_name === 'jsonb' || column.udt_name === 'json'
}

async function candidateColumns(client, schemas) {
  const { rows } = await client.query(`
    SELECT c.table_schema, c.table_name, c.column_name, c.udt_name
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

async function countColumnMatches(client, column, targetPrefix) {
  const field = quoteIdent(column.column_name)
  const expression = isJsonColumn(column) ? `${field}::text` : field
  const { rows } = await client.query(
    `SELECT COUNT(*)::int AS count
     FROM ${qualifiedTable(column)}
     WHERE replace(${expression}, $2, '') ~* $1`,
    [URL_PREFIX_PATTERN, targetPrefix],
  )
  return Number(rows[0]?.count || 0)
}

async function updateColumnMatches(client, column, targetPrefix) {
  const field = quoteIdent(column.column_name)
  const table = qualifiedTable(column)
  if (isJsonColumn(column)) {
    const cast = column.udt_name === 'json' ? 'json' : 'jsonb'
    const { rowCount } = await client.query(
      `UPDATE ${table}
       SET ${field} = regexp_replace(${field}::text, $1, $2, 'gi')::${cast}
       WHERE replace(${field}::text, $2, '') ~* $1`,
      [URL_PREFIX_PATTERN, targetPrefix],
    )
    return rowCount || 0
  }
  const { rowCount } = await client.query(
    `UPDATE ${table}
     SET ${field} = regexp_replace(${field}, $1, $2, 'gi')
     WHERE replace(${field}, $2, '') ~* $1`,
    [URL_PREFIX_PATTERN, targetPrefix],
  )
  return rowCount || 0
}

async function canonicalizeStoredUrls(client, { apply = false, baseUrl, schemas }) {
  const targetPrefix = `${trimTrailingSlash(baseUrl)}/storage/v1/object/public/`
  const summary = []
  for (const column of await candidateColumns(client, schemas)) {
    const matches = await countColumnMatches(client, column, targetPrefix)
    if (matches === 0) continue
    summary.push({
      column: column.column_name,
      matches,
      table: `${column.table_schema}.${column.table_name}`,
      updated: apply ? await updateColumnMatches(client, column, targetPrefix) : 0,
    })
  }
  return summary
}

async function main() {
  const apply = args.has('--apply')
  const databaseUrl = String(process.env.DATABASE_URL || '').trim()
  const baseUrl = trimTrailingSlash(process.env.PUBLIC_BASE_URL || process.env.LOCAL_PUBLIC_BASE_URL)
  const schemas = splitList(process.env.STORAGE_URL_CANONICAL_SCHEMAS || 'public,supabase_import')
  if (!databaseUrl || !baseUrl || schemas.length === 0) {
    throw new Error('DATABASE_URL, PUBLIC_BASE_URL, and at least one schema are required')
  }

  const pool = new Pool({ connectionString: databaseUrl })
  const client = await pool.connect()
  try {
    if (apply) await client.query('BEGIN')
    const summary = await canonicalizeStoredUrls(client, { apply, baseUrl, schemas })
    if (apply) await client.query('COMMIT')
    process.stdout.write(JSON.stringify({
      ok: true,
      apply,
      matchedRows: summary.reduce((total, item) => total + item.matches, 0),
      updatedRows: summary.reduce((total, item) => total + item.updated, 0),
      summary,
    }))
  } catch (error) {
    if (apply) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
    await pool.end()
  }
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })
}

module.exports = {
  URL_PREFIX_PATTERN,
  canonicalizeStoredUrls,
  countColumnMatches,
  updateColumnMatches,
}
