const { Pool } = require('pg')
const { synchronizeGameCatalog } = require('../src/playmatch-catalog-sync')

function argument(name, fallback = null) {
  const prefix = `--${name}=`
  const match = process.argv.slice(2).find((value) => value.startsWith(prefix))
  return match ? match.slice(prefix.length) : fallback
}

async function main() {
  const databaseUrl = String(process.env.DATABASE_URL || '').trim()
  if (!databaseUrl) throw new Error('DATABASE_URL_REQUIRED')
  const provider = String(argument('provider', process.env.PLAYMATCH_CATALOG_PROVIDER || 'wikidata')).trim().toLowerCase()
  const pageSize = Math.max(1, Math.min(Number(argument('page-size', 100)) || 100, 250))
  const maxPages = Math.max(1, Math.min(Number(argument('max-pages', 1)) || 1, 100))
  const restart = /^(1|true|yes)$/i.test(String(argument('restart', 'false')))
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 2,
    connectionTimeoutMillis: 10_000,
    options: '-c statement_timeout=90000 -c lock_timeout=10000 -c idle_in_transaction_session_timeout=30000',
  })
  try {
    const state = restart
      ? { rows: [] }
      : await pool.query(`SELECT next_cursor FROM fallback_playmatch_catalog_provider_state WHERE provider=$1`, [provider])
    const result = await synchronizeGameCatalog(pool, {
      provider,
      cursor: state.rows[0]?.next_cursor || null,
      maxPages,
      pageSize,
      requestedBy: 'server-cli',
    })
    process.stdout.write(`${JSON.stringify(result)}\n`)
  } finally {
    await pool.end()
  }
}

main().catch((error) => {
  process.stderr.write(`${String(error?.message || 'PLAYMATCH_CATALOG_SYNC_FAILED').slice(0, 160)}\n`)
  process.exitCode = 1
})
