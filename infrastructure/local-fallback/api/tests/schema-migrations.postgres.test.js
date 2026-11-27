'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { createHash, randomBytes } = require('node:crypto')
const { readFileSync, readdirSync } = require('node:fs')
const path = require('node:path')
const { Client } = require('pg')
const { ensureDirectMessageRequestPolicy } = require('../src/direct-message-request-policy')

const connectionString = process.env.NOVVIN_SCHEMA_TEST_DATABASE_URL
const root = path.resolve(__dirname, '../..')

test('PostgreSQL applies each migration and its checksum atomically', { skip: !connectionString }, async () => {
  const url = new URL(connectionString)
  assert(['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))
  const runner = readFileSync(path.join(root, 'scripts/apply-schema-migration.sql'), 'utf8')
  const name = 'novvin_migration_test_' + randomBytes(10).toString('hex')
  const admin = new Client({ connectionString })
  await admin.connect()
  const clients = []
  let created = false
  try {
    await admin.query('CREATE DATABASE ' + name)
    created = true
    url.pathname = '/' + name
    for (let i = 0; i < 2; i++) {
      const client = new Client({ connectionString: url.href })
      await client.connect()
      clients.push(client)
      await client.query(runner)
      if (i === 0) await client.query('GRANT INSERT ON fallback_schema_migrations TO PUBLIC')
    }
    const [first, second] = clients
    const publicGrants = await first.query(`
      SELECT acl.privilege_type FROM pg_class c, LATERAL aclexplode(c.relacl) acl
      WHERE c.oid = 'public.fallback_schema_migrations'::regclass AND acl.grantee = 0
    `)
    assert.deepEqual(publicGrants.rows, [])
    async function apply(client, filename, bytes) {
      const result = await client.query('SELECT pg_temp.apply_schema_migration($1, $2) AS outcome', [filename, Buffer.from(bytes)])
      return result.rows[0].outcome
    }

    await first.query(readFileSync(path.join(root, 'db/init/001_schema.sql'), 'utf8'))
    const directory = path.join(root, 'db/migrations')
    const files = readdirSync(directory).filter(file => file.endsWith('.sql')).sort()
    assert(files.length > 0)
    for (const filename of files) {
      const bytes = readFileSync(path.join(directory, filename))
      assert.equal(await apply(first, filename, bytes), 'MIGRATION_APPLIED=' + filename)
      const { rows } = await first.query('SELECT checksum_sha256 FROM fallback_schema_migrations WHERE filename = $1', [filename])
      assert.equal(rows[0].checksum_sha256, createHash('sha256').update(bytes).digest('hex'))
      assert.equal(await apply(second, filename, bytes), 'MIGRATION_ALREADY_APPLIED=' + filename)
    }

    const superseded = '026_pending_friendship_allows_conversation.sql'
    const historicalSource = readFileSync(path.join(directory, superseded), 'utf8').replace(/\r\n/g, '\n')
    const readGuard = async () => (await first.query(
      "SELECT pg_get_functiondef('public.fallback_guard_introductory_message()'::regprocedure) AS definition",
    )).rows[0].definition
    await ensureDirectMessageRequestPolicy(first)
    const currentGuard = await readGuard()
    assert.match(currentGuard, /MESSAGE_REQUEST_PENDING/)
    // Model an existing API-managed database where this old migration is absent from the ledger.
    await first.query('DELETE FROM fallback_schema_migrations WHERE filename = $1', [superseded])
    for (const bytes of [historicalSource, historicalSource.replace(/\n/g, '\r\n')]) {
      assert.equal(await apply(second, superseded, bytes), 'MIGRATION_SUPERSEDED=' + superseded)
    }
    await assert.rejects(apply(second, superseded, historicalSource + '\nSELECT 1;'), /checksum/i)
    assert.equal(await readGuard(), currentGuard)
    assert.equal((await first.query('SELECT 1 FROM fallback_schema_migrations WHERE filename = $1', [superseded])).rowCount, 0)

    for (const historical of ['002_sync_support.sql', '003_password_migration_notice.sql']) {
      const lf = readFileSync(path.join(directory, historical), 'utf8').replace(/\r\n/g, '\n')
      const crlf = lf.replace(/\n/g, '\r\n')
      for (const [installed, supplied] of [[crlf, lf], [lf, crlf]]) {
        const recorded = createHash('sha256').update(installed).digest('hex')
        await first.query('UPDATE fallback_schema_migrations SET checksum_sha256 = $2 WHERE filename = $1', [historical, recorded])
        assert.equal(await apply(second, historical, supplied), 'MIGRATION_ALREADY_APPLIED=' + historical)
        await assert.rejects(apply(second, historical, supplied + '\nSELECT 1;'), /checksum/i)
        const { rows } = await first.query('SELECT checksum_sha256 FROM fallback_schema_migrations WHERE filename = $1', [historical])
        assert.equal(rows[0].checksum_sha256, recorded)
      }
    }

    await first.query('CREATE TABLE migration_fixture (value int NOT NULL)')
    const insert = 'INSERT INTO migration_fixture VALUES (1)'
    const filename = "fixture's migration.sql"
    const outcomes = await Promise.all([first, second].map(client => apply(client, filename, 'SELECT pg_sleep(0.1); ' + insert)))
    assert.deepEqual(outcomes.sort(), ['MIGRATION_ALREADY_APPLIED=' + filename, 'MIGRATION_APPLIED=' + filename].sort())
    await assert.rejects(apply(first, filename, insert), /checksum/i)

    await first.query("ALTER TABLE fallback_schema_migrations ADD CHECK (filename <> 'fixture-ledger-failure.sql')")
    await assert.rejects(apply(first, 'fixture-ledger-failure.sql', insert), { code: '23514' })
    await assert.rejects(apply(first, 'fixture-sql-failure.sql', insert + '; SELECT 1/0'), { code: '22012' })
    await assert.rejects(apply(first, 'fixture-early-commit.sql', insert + '; COMMIT'), { code: '0A000' })
    assert.equal((await first.query('SELECT count(*)::int AS n FROM migration_fixture')).rows[0].n, 1)
    const ledger = await first.query("SELECT filename FROM fallback_schema_migrations WHERE filename LIKE 'fixture%'")
    assert.deepEqual(ledger.rows, [{ filename }])
  } finally {
    try {
      await Promise.all(clients.map(client => client.end()))
      if (created) await admin.query('DROP DATABASE ' + name)
    } finally {
      await admin.end()
    }
  }
})
