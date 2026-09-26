'use strict'

// Read-only preflight. Outputs aggregate counts/types, never IDs, URLs or message content.
const { Pool } = require('pg')

async function main() {
  if (!process.env.DATABASE_URL) throw Object.assign(new Error('Database configuration required'), { code: 'CONFIG_REQUIRED' })
  const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 1, connectionTimeoutMillis: 5000 })
  let client
  try {
    client = await pool.connect()
    await client.query('BEGIN READ ONLY')
    await client.query("SET LOCAL statement_timeout = '5s'")
    const local = await client.query(`
      SELECT count(*)::int AS rows,
             count(*) FILTER (WHERE created_at IS NULL)::int AS created_missing,
             count(*) FILTER (WHERE expires_at IS NULL)::int AS expiry_missing,
             pg_typeof(min(created_at))::text AS created_type,
             pg_typeof(min(expires_at))::text AS expiry_type
      FROM fallback_moments`)
    const exists = await client.query("SELECT to_regclass('supabase_import.moments') IS NOT NULL AS present")
    let imported = null
    if (exists.rows[0].present) {
      const timestampShape = '^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,9})?(Z|[+-][0-9]{2}(:?[0-9]{2})?)$'
      imported = (await client.query(`
        WITH timestamps AS (
          SELECT to_jsonb(t)->>'created_at' AS created, to_jsonb(t)->>'expires_at' AS expiry
          FROM supabase_import.moments t
        )
        SELECT count(*)::int AS rows,
               count(*) FILTER (WHERE created IS NULL OR btrim(created) = '')::int AS created_missing,
               count(*) FILTER (WHERE expiry IS NULL OR btrim(expiry) = '')::int AS expiry_missing,
               count(*) FILTER (WHERE created IS NOT NULL AND btrim(created) <> ''
                 AND btrim(created) !~* $1)::int AS created_unrecognized_shape,
               count(*) FILTER (WHERE expiry IS NOT NULL AND btrim(expiry) <> ''
                 AND btrim(expiry) !~* $1)::int AS expiry_unrecognized_shape
        FROM timestamps`, [timestampShape])).rows[0]
    }
    console.log(JSON.stringify({ readOnly: true, local: local.rows[0], imported }, null, 2))
  } finally {
    if (client) {
      await client.query('ROLLBACK').catch(() => {})
      client.release()
    }
    await pool.end()
  }
}

main().catch(error => {
  console.error(JSON.stringify({ error: 'MOMENT_TIMESTAMP_AUDIT_FAILED', code: error.code || 'UNKNOWN' }))
  process.exitCode = 1
})
