'use strict'

const { Pool } = require('pg')
const { ensureAlgorithmTables } = require('./algorithm-routes')
const { backfillTrustedPublicContentClassifications } = require('./algorithm-content-ingestion')

async function main() {
  const databaseUrl = String(process.env.DATABASE_URL || '').trim()
  if (!databaseUrl) throw new Error('DATABASE_URL is required')
  const pool = new Pool({ connectionString: databaseUrl })
  try {
    await ensureAlgorithmTables(pool)
    const result = await backfillTrustedPublicContentClassifications(pool, {
      limit: Number(process.env.USER_ALGORITHM_CLASSIFICATION_BACKFILL_LIMIT || 2_000),
    })
    // Only aggregate counts are emitted. Content, identities and secrets are
    // intentionally absent from command output.
    console.log(JSON.stringify({ ok: result.failed === 0, ...result }))
    if (result.failed > 0) process.exitCode = 1
  } finally {
    await pool.end()
  }
}

if (require.main === module) {
  main().catch((error) => {
    console.error(String(error?.message || 'BACKFILL_FAILED'))
    process.exitCode = 1
  })
}

module.exports = { main }
