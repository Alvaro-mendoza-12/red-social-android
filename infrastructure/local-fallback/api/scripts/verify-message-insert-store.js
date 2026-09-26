const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Client } = require('pg')
const { insertMessageOnce } = require('../src/message-insert-store')

/** Tests only session-private tables. All DML is rolled back on exit. */
async function verifyMessageInsertStore(client) {
  let checks = 0
  await client.query('BEGIN')
  try {
    await client.query("SET LOCAL statement_timeout = '5s'")
    await client.query('SET LOCAL search_path = pg_temp, public')
    // No fixtures or writes in public: copy structure, defaults and unique indexes only.
    await client.query(`CREATE TEMP TABLE fallback_messages
      (LIKE public.fallback_messages INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP`)
    await client.query(`CREATE TEMP TABLE fallback_room_messages
      (LIKE public.fallback_room_messages INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP`)
    const relations = await client.query(`SELECT c.relpersistence FROM pg_class c
      WHERE c.oid IN ('fallback_messages'::regclass, 'fallback_room_messages'::regclass)`)
    assert.deepEqual(relations.rows.map((r) => r.relpersistence), ['t', 't'])

    for (const kind of ['dm', 'group', 'community']) {
      const table = kind === 'dm' ? 'pg_temp.fallback_messages' : 'pg_temp.fallback_room_messages'
      const owner = randomUUID()
      const alias = randomUUID()
      const id = randomUUID()
      const input = { kind, scopeId: randomUUID(), channelId: kind === 'community' ? randomUUID() : null,
        actorIds: new Set([owner, alias]), message: { id, sender_id: owner, content: 'first message',
          content_type: 'text', media_size: 0, is_forwarded: false } }
      const inserted = await insertMessageOnce(client, input)
      assert.equal(inserted.inserted, true)
      assert.equal(inserted.message.id, id)
      assert.equal(inserted.message.sender_id, owner)
      checks++

      const retry = { ...input, message: { ...input.message, content: 'stale retry', sender_id: alias } }
      const snapshot = async () => (await client.query(`SELECT * FROM ${table} WHERE id = $1`, [id])).rows[0]
      const assertUnchangedRetry = async () => {
        const before = await snapshot()
        const saved = await insertMessageOnce(client, retry)
        assert.equal(saved.inserted, false)
        assert.equal(saved.message.content, before.content)
        assert.equal(saved.message.status, before.status)
        assert.equal(saved.message.sender_id, owner)
        assert.equal(saved.message.deleted_for_all, before.deleted_for_all)
        assert.deepEqual(saved.message.deleted_by, before.deleted_by)
        assert.deepEqual(saved.message.reactions, before.reactions)
        assert.deepEqual(saved.message.read_at, before.read_at)
        assert.deepEqual(saved.message.delivered_at, before.delivered_at)
        assert.deepEqual(await snapshot(), before)
        checks++
      }
      await assertUnchangedRetry()
      await client.query(`UPDATE ${table} SET content = 'edited after send', is_edited = TRUE,
        status = 'read', delivered_at = NOW(), read_at = NOW(), reactions = $2::jsonb WHERE id = $1`,
      [id, JSON.stringify({ '👍': [alias] })])
      await assertUnchangedRetry()
      await client.query(`UPDATE ${table} SET deleted_for_all = TRUE, deleted_by = $2::jsonb WHERE id = $1`,
        [id, JSON.stringify([alias])])
      await assertUnchangedRetry()

      const conflict = async (attempt) => {
        const before = await snapshot()
        await assert.rejects(insertMessageOnce(client, attempt), { message: 'MESSAGE_ID_CONFLICT', status: 409 })
        assert.deepEqual(await snapshot(), before)
        checks++
      }
      const outsider = randomUUID()
      await conflict({ ...input, actorIds: [outsider], message: { ...input.message, sender_id: outsider } })
      await conflict({ ...input, scopeId: randomUUID() })
      if (kind !== 'dm') await conflict({ ...input, kind: kind === 'group' ? 'community' : 'group' })
      if (kind === 'community') {
        await conflict({ ...input, channelId: randomUUID() })
        await conflict({ ...input, channelId: null })
      }

      const arbitraryText = "'); DROP TABLE fallback_messages; --"
      const generated = await insertMessageOnce(client, { ...input,
        message: { ...input.message, id: null, content: arbitraryText } })
      assert.equal(generated.inserted, true)
      assert.match(generated.message.id, /^[0-9a-f-]{36}$/)
      assert.equal(generated.message.content, arbitraryText)
      checks++
    }
    return { ok: true, checks, storage: 'PostgreSQL temporary tables; rolled back' }
  } finally {
    await client.query('ROLLBACK')
  }
}

async function main() {
  if (!process.argv.includes('--temporary-tables')) throw new Error('Pass --temporary-tables explicitly')
  const connectionString = process.env.MESSAGE_INSERT_TEST_DATABASE_URL || process.env.DATABASE_URL
  if (!connectionString) throw new Error('Database connection is required')
  const client = new Client({ connectionString, connectionTimeoutMillis: 5000 })
  try {
    await client.connect()
    console.log(JSON.stringify(await verifyMessageInsertStore(client)))
  } finally {
    await client.end()
  }
}

if (require.main === module) main().catch((error) => {
  console.error('Message insertion verification failed:', error.code || error.name)
  process.exitCode = 1
})

module.exports = { verifyMessageInsertStore }
