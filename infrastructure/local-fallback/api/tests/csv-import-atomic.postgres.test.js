'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const { randomUUID } = require('node:crypto')
const { promisify } = require('node:util')
const execFile = promisify(require('node:child_process').execFile)
const { Client } = require('pg')

const adminUrl = process.env.CSV_IMPORT_TEST_DATABASE_URL
test('CSV replacement, retries and profile updates are atomic per file', { skip: !adminUrl }, async t => {
  const url = new URL(adminUrl)
  assert.ok(['localhost', '127.0.0.1'].includes(url.hostname) && url.pathname === '/novvin_csv_test')
  const admin = new Client({ connectionString: url.href })
  await admin.connect()
  const name = 'novvin_csv_' + randomUUID().replaceAll('-', '')
  try {
    await admin.query(`CREATE DATABASE "${name}"`)
  } catch (error) {
    await admin.end()
    throw error
  }
  t.after(async () => {
    try { await admin.query(`DROP DATABASE "${name}"`) } finally { await admin.end() }
  })
  url.pathname = '/' + name
  const db = new Client({ connectionString: url.href })
  await db.connect()
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-csv-'))
  try {
    const schema = await fs.readFile(path.join(__dirname, '../../db/init/001_schema.sql'), 'utf8')
    await db.query(schema)
    const importCsv = folder => execFile(process.execPath, [path.join(__dirname, '../src/import-all-csv.js'), folder],
      { env: { ...process.env, DATABASE_URL: url.href } })
    const file = path.join(dir, 'profiles.csv')
    const writeProfiles = data => fs.writeFile(file, 'email,display_name\n' + data)
    const emails = async table => (await db.query(`SELECT email FROM ${table} ORDER BY email`)).rows.map(row => row.email)
    await writeProfiles('old@example.test,Old\n')
    await importCsv(dir)
    await db.query("ALTER TABLE fallback_users ADD CHECK (display_name <> 'On hold')")
    await writeProfiles('a@example.test,A\nb@example.test,On hold\n')
    await assert.rejects(importCsv(dir))
    assert.deepEqual(await emails('fallback_users'), ['old@example.test'])
    assert.deepEqual(await emails('supabase_import.profiles'), ['old@example.test'])
    await writeProfiles('a@example.test,A\nb@example.test,B\n')
    await importCsv(dir)
    assert.deepEqual(await emails('supabase_import.profiles'), ['a@example.test', 'b@example.test'])
    await fs.unlink(file)
    const catalog = path.join(dir, 'catalog.csv')
    await fs.writeFile(catalog, 'id\noriginal\n')
    await importCsv(dir)
    await fs.writeFile(catalog, 'id\npartial\ninvalid\u0000text\n')
    await assert.rejects(importCsv(dir))
    assert.deepEqual((await db.query('SELECT id FROM supabase_import.catalog')).rows, [{ id: 'original' }])
    const other = path.join(dir, 'second')
    await fs.mkdir(other)
    await fs.writeFile(catalog, 'id\nx1\nx2\n')
    await fs.writeFile(path.join(other, 'catalog.csv'), 'id\ny1\ny2\n')
    await Promise.all([importCsv(dir), importCsv(other)])
    const final = (await db.query('SELECT id FROM supabase_import.catalog ORDER BY id')).rows
    assert.ok(['x1,x2', 'y1,y2'].includes(final.map(row => row.id).join(',')))
  } finally {
    await db.end()
    await fs.rm(dir, { recursive: true, force: true })
  }
})
