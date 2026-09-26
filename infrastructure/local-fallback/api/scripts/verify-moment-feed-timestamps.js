'use strict'

const { Client } = require('pg')
const jwt = require('jsonwebtoken')

function timestampSummary(items) {
  const summary = { items: items.length, publicationMissing: 0, malformedPublication: 0, malformedExpiry: 0 }
  const isIso = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value) &&
    Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value
  for (const item of items) {
    if (!item.created_at) summary.publicationMissing++
    else if (!isIso(item.created_at)) summary.malformedPublication++
    if (item.expires_at && !isIso(item.expires_at)) summary.malformedExpiry++
  }
  return summary
}

async function main() {
  if (!process.env.JWT_SECRET || !process.env.DATABASE_URL) throw new Error('MISSING_RUNTIME_CONFIGURATION')
  const client = new Client({ connectionString: process.env.DATABASE_URL, connectionTimeoutMillis: 5000 })
  await client.connect()
  let token
  try {
    await client.query('BEGIN READ ONLY')
    await client.query("SET LOCAL statement_timeout = '5s'")
    const { rows } = await client.query(`SELECT id, email FROM fallback_users
      ORDER BY last_login_at DESC NULLS LAST, created_at ASC LIMIT 1`)
    if (!rows[0]) throw new Error('NO_USER_FOR_READ_CHECK')
    token = jwt.sign({ email: rows[0].email, provider: 'local-fallback', client: 'moment-timestamp-smoke' },
      process.env.JWT_SECRET, { subject: rows[0].id, issuer: 'conectate-local-fallback',
        audience: 'conectate-clients', expiresIn: '2m' })
  } finally {
    await client.query('ROLLBACK').catch(() => {})
    await client.end()
  }
  const base = process.env.SMOKE_API_BASE_URL || 'http://127.0.0.1:3001'
  const target = new URL('/v1/data/moments/feed', base)
  if (target.protocol !== 'https:' && !(target.protocol === 'http:' && target.hostname === '127.0.0.1')) {
    throw new Error('UNSAFE_TARGET')
  }
  const start = Date.now()
  const response = await fetch(target, { headers: { authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15000) })
  if (!response.ok) throw new Error(`HTTP_${response.status}`)
  const body = await response.json()
  if (!Array.isArray(body.items)) throw new Error('INVALID_FEED_SHAPE')
  const summary = timestampSummary(body.items)
  // No identifiers, content, URLs, JWTs or secret configuration in diagnostic output.
  console.log(JSON.stringify({ check: 'moment-feed-timestamps', status: response.status, elapsedMs: Date.now() - start,
    ...summary, liveRowsChecked: body.items.length > 0 }))
  if (summary.malformedPublication || summary.malformedExpiry) throw new Error('INVALID_TIMESTAMP_CONTRACT')
}

if (require.main === module || process.argv[1] === '-') main().catch(error => {
  console.error('MOMENT_TIMESTAMP_CHECK_FAILED', /^[A-Z_0-9]+$/.test(error.message) ? error.message : error.name)
  if (/^[A-Z_0-9]+$/.test(error.cause?.code || '')) console.error('CAUSE_CODE', error.cause.code)
  process.exitCode = 1
})
module.exports = { timestampSummary }
