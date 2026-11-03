#!/usr/bin/env node
'use strict'
const { Pool } = require('pg')
const { configuration, safeError } = require('../src/storage-r2-mirror')
const { SCHEMA, batch } = require('../src/storage-r2-journal')
const { createRemote } = require('../src/storage-r2-remote')

async function main(args = process.argv.slice(2)) {
  const flags = new Set(args)
  if (flags.size !== args.length || args.some(arg => !['--inventory','--status','--migrate','--once','--run'].includes(arg)) || flags.size !== 1) {
    throw Object.assign(new Error('R2_EXACTLY_ONE_MODE_REQUIRED'), { code: 'R2_EXACTLY_ONE_MODE_REQUIRED' })
  }
  if (!String(process.env.DATABASE_URL || '').trim()) throw Object.assign(new Error('R2_DATABASE_URL_REQUIRED'), { code: 'R2_DATABASE_URL_REQUIRED' })
  const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 1, connectionTimeoutMillis: 10_000, query_timeout: 30_000 })
  let remote, stopping = false
  const stop = () => { stopping = true }
  process.once('SIGTERM', stop); process.once('SIGINT', stop)
  try {
    if (flags.has('--inventory')) {
      const result = await pool.query(`SELECT bucket_id,scan_state,count(*)::int AS objects,sum(size_bytes)::text AS bytes
        FROM fallback_storage_objects GROUP BY bucket_id,scan_state ORDER BY bucket_id,scan_state`)
      console.log(JSON.stringify({ inventory: result.rows, note: 'Registry inventory only; files and remote copies are not verified by this command.' }))
      return
    }
    if (flags.has('--migrate')) {
      // Explicit migration; never create schemas implicitly in the serving API.
      const db = await pool.connect()
      try {
        await db.query('BEGIN')
        await db.query("SELECT pg_advisory_xact_lock(hashtextextended('novvin-r2-mirror-schema-v1',0))")
        await db.query(SCHEMA)
        await db.query('COMMIT')
      } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error }
      finally { db.release() }
      console.log(JSON.stringify({ schema: 'ready' }))
      return
    }
    if (flags.has('--status')) {
      const result = await pool.query(`SELECT target,state,count(*)::int AS objects,sum(size_bytes)::text AS bytes,
        max(verified_at) AS last_verification,max(last_error) AS sample_error FROM fallback_storage_r2_mirrors
        GROUP BY target,state ORDER BY target,state`)
      console.log(JSON.stringify({ mirrors: result.rows }))
      return
    }
    const config = configuration()
    if (!config.enabled) throw Object.assign(new Error('R2_MIRROR_NOT_ENABLED'), { code: 'R2_MIRROR_NOT_ENABLED' })
    remote = createRemote(config)
    do {
      const db = await pool.connect()
      try { console.log(JSON.stringify({ mirror: await batch(db, config, remote) })) }
      finally { db.release() }
      if (flags.has('--once') || stopping) break
      // Short sleep increments allow prompt shutdown while idle.
      for (let elapsed = 0; elapsed < config.intervalMs && !stopping; elapsed += 250) await new Promise(resolve => setTimeout(resolve, 250))
    } while (!stopping)
  } finally { remote?.close(); await pool.end(); process.removeListener('SIGTERM', stop); process.removeListener('SIGINT', stop) }
}
if (require.main === module) main().catch(error => { console.error(JSON.stringify({ error: safeError(error) })); process.exitCode = 1 })
module.exports = { main }
