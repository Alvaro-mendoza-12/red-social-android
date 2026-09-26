const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const { insertMessageOnce } = require('../src/message-insert-store')
const { verifyMessageInsertStore } = require('./verify-message-insert-store')

async function verifyLocal() {
  const connectionString = process.env.MESSAGE_INSERT_TEST_DATABASE_URL
  if (!connectionString) throw new Error('MESSAGE_INSERT_TEST_DATABASE_URL is required')
  const url = new URL(connectionString)
  assert.ok(['127.0.0.1', 'localhost'].includes(url.hostname), 'Local test database only')
  assert.equal(url.pathname, '/nowi_message_insert_test', 'Dedicated test database only')
  const pool = new Pool({ connectionString, max: 6, connectionTimeoutMillis: 5000,
    options: '-c statement_timeout=5000' })
  const client = await pool.connect()
  try {
    // Bootstrap from the same DDL used in production, without importing user rows.
    const source = fs.readFileSync(path.join(__dirname, '../src/data-routes.js'), 'utf8')
    for (const table of ['fallback_messages', 'fallback_room_messages']) {
      const ddl = source.match(new RegExp('CREATE TABLE IF NOT EXISTS ' + table + ' \\([\\s\\S]*?\\n    \\)'))
      assert.ok(ddl, `Missing authoritative DDL for ${table}`)
      await client.query(ddl[0])
    }
    const sequential = await verifyMessageInsertStore(client)
    let concurrentChecks = 0
    for (const kind of ['dm', 'group', 'community']) {
      const sender = randomUUID()
      const input = { kind, scopeId: randomUUID(), actorIds: [sender],
        channelId: kind === 'community' ? randomUUID() : null,
        message: { id: randomUUID(), sender_id: sender, content: 'original',
          content_type: 'text', media_size: 0, is_forwarded: false } }
      const results = await Promise.all(Array.from({ length: 12 }, () => insertMessageOnce(pool, input)))
      assert.equal(results.filter((r) => r.inserted).length, 1)
      assert.equal(new Set(results.map((r) => r.message.id)).size, 1)
      concurrentChecks++

      // Exercise a real uncommitted unique-key conflict in another connection.
      const writer = await pool.connect()
      const competing = await pool.connect()
      let replay
      try {
        await writer.query('BEGIN')
        const raceInput = { ...input, message: { ...input.message, id: randomUUID() } }
        const first = await insertMessageOnce(writer, raceInput)
        const pid = (await competing.query('SELECT pg_backend_pid() AS pid')).rows[0].pid
        replay = insertMessageOnce(competing, raceInput)
        let blocked = false
        const deadline = Date.now() + 3000
        while (Date.now() < deadline) {
          const { rows } = await client.query('SELECT wait_event_type FROM pg_stat_activity WHERE pid = $1', [pid])
          if (rows[0]?.wait_event_type === 'Lock') { blocked = true; break }
          await new Promise((resolve) => setTimeout(resolve, 20))
        }
        assert.equal(blocked, true, 'Competing insert must wait for the first transaction')
        await writer.query('COMMIT')
        const second = await replay
        assert.equal(first.inserted, true)
        assert.equal(second.inserted, false)
        assert.equal(second.message.id, first.message.id)
        concurrentChecks++
      } finally {
        await writer.query('ROLLBACK')
        if (replay) await replay.catch(() => {})
        writer.release()
        competing.release()
      }
    }
    const version = (await client.query('SHOW server_version')).rows[0].server_version
    return { ...sequential, concurrentChecks, version, database: 'disposable local PostgreSQL' }
  } finally {
    client.release()
    await pool.end()
  }
}

if (require.main === module) verifyLocal().then((result) => console.log(JSON.stringify(result))).catch((error) => {
  console.error(error)
  process.exitCode = 1
})

module.exports = { verifyLocal }
