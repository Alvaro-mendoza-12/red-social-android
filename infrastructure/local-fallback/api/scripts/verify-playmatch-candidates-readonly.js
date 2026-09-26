'use strict'

const { Client } = require('pg')
const { candidateBundles, candidateFitsCriteria } = require('../src/playmatch-routes')

async function verifyPlayMatchCandidates(client) {
  await client.query('BEGIN READ ONLY')
  try {
    await client.query("SET LOCAL statement_timeout = '5000ms'")
    const { rows } = await client.query('SELECT id,supabase_user_id FROM fallback_users ORDER BY created_at LIMIT 1')
    if (!rows[0]) return { ok: true, skipped: true, reason: 'NO_EXISTING_VIEWER', checks: 0 }
    const viewer = rows[0]
    const filters = [
      {}, { gameIds: ['novvin-nonexistent-regression-game'], platforms: ['pc'], roles: ['support'] },
      { regions: ['novvin-no-region'], languages: ['es'], styles: ['competitive'], voiceRequired: true },
      { excludeRecentMatches: true },
    ]
    let eligible = 0
    for (const criteria of filters) {
      const result = await candidateBundles(client, viewer, criteria)
      if (!result.every((candidate) => candidateFitsCriteria(candidate, criteria))) throw new Error('FILTER_CONTRACT_FAILED')
      if (criteria.gameIds && result.length) throw new Error('NONEXISTENT_GAME_MATCHED')
      if (criteria.regions && result.length) throw new Error('NONEXISTENT_REGION_MATCHED')
      if (!Object.keys(criteria).length) eligible = result.length
    }
    return { ok: true, skipped: false, checks: filters.length, eligibleCandidates: eligible,
      scope: 'Read-only candidate queries; no profiles, sessions, connections or messages created' }
  } finally {
    await client.query('ROLLBACK')
  }
}

if (require.main === module || process.argv[1] === '-') {
  ;(async () => {
    if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL_REQUIRED')
    const client = new Client({ connectionString: process.env.DATABASE_URL, connectionTimeoutMillis: 5000 })
    await client.connect()
    try { console.log(JSON.stringify(await verifyPlayMatchCandidates(client), null, 2)) }
    finally { await client.end() }
  })().catch(() => {
    console.error('PlayMatch read-only verification failed; no personal data logged.')
    process.exitCode = 1
  })
}

module.exports = { verifyPlayMatchCandidates }
