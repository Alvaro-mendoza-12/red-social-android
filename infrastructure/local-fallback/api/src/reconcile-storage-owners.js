const { Pool } = require('pg')

async function reconcileStorageOwners(pool) {
  const before = await pool.query(
    `SELECT COUNT(*)::int AS count
     FROM fallback_storage_objects
     WHERE owner_id IS NULL`,
  )
  const updated = await pool.query(`
    WITH matches AS (
      SELECT o.id AS object_id, u.id AS local_user_id
      FROM fallback_storage_objects o
      CROSS JOIN LATERAL regexp_matches(
        o.name,
        '([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})',
        'gi'
      ) AS path_match
      JOIN fallback_users u
        ON lower(path_match[1]) = lower(u.id::text)
        OR lower(path_match[1]) = lower(COALESCE(u.supabase_user_id::text, ''))
      WHERE o.owner_id IS NULL
      GROUP BY o.id, u.id
    ), unique_matches AS (
      SELECT object_id, MIN(local_user_id::text)::uuid AS local_user_id
      FROM matches
      GROUP BY object_id
      HAVING COUNT(*) = 1
    )
    UPDATE fallback_storage_objects o
    SET owner_id = matches.local_user_id,
        updated_at = NOW()
    FROM unique_matches matches
    WHERE o.id = matches.object_id
      AND o.owner_id IS NULL
    RETURNING o.id
  `)
  const after = await pool.query(
    `SELECT COUNT(*)::int AS count
     FROM fallback_storage_objects
     WHERE owner_id IS NULL`,
  )
  return {
    before: before.rows[0]?.count || 0,
    reconciled: updated.rowCount || 0,
    unresolved: after.rows[0]?.count || 0,
  }
}

async function main() {
  const databaseUrl = String(process.env.DATABASE_URL || '').trim()
  if (!databaseUrl) throw new Error('DATABASE_URL is required')
  const pool = new Pool({ connectionString: databaseUrl })
  try {
    const result = await reconcileStorageOwners(pool)
    process.stdout.write(JSON.stringify({ ok: true, ...result }))
  } finally {
    await pool.end()
  }
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })
}

module.exports = { reconcileStorageOwners }
