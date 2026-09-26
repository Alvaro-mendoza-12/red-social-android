'use strict'

// Read-only smoke check: no messages, pack writes, favorites or user content in output.
const { Client } = require('pg')
const jwt = require('jsonwebtoken')

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
    token = jwt.sign({ email: rows[0].email, provider: 'local-fallback', client: 'sticker-catalog-smoke' },
      process.env.JWT_SECRET, { subject: rows[0].id, issuer: 'conectate-local-fallback',
        audience: 'conectate-clients', expiresIn: '2m' })
  } finally {
    await client.query('ROLLBACK').catch(() => {})
    await client.end()
  }
  const origins = [['internal', 'http://127.0.0.1:3001'], ['public', process.env.PUBLIC_BASE_URL]]
  for (const [scope, base] of origins) {
    if (!base) throw new Error('MISSING_PUBLIC_ORIGIN')
    const target = new URL('/rest/v1/rpc/secure_search_stickers', base)
    if (target.username || target.password ||
      (target.protocol !== 'https:' && !(scope === 'internal' && target.origin === 'http://127.0.0.1:3001'))) {
      throw new Error('UNSAFE_TARGET')
    }
    const start = Date.now()
    const response = await fetch(target, { method: 'POST', redirect: 'error',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: JSON.stringify({ p_query: '', p_limit: 10 }), signal: AbortSignal.timeout(15000) })
    if (!response.ok) throw new Error(`HTTP_${response.status}`)
    const body = await response.json()
    if (!Array.isArray(body)) throw new Error('INVALID_CATALOG_SHAPE')
    console.log(JSON.stringify({ check: 'sticker-catalog-read', scope, status: response.status,
      elapsedMs: Date.now() - start, items: body.length, liveRowsChecked: body.length > 0 }))
  }
}
main().catch(error => {
  console.error('STICKER_CATALOG_CHECK_FAILED', /^[A-Z_0-9]+$/.test(error.message) ? error.message : error.name)
  process.exitCode = 1
})
