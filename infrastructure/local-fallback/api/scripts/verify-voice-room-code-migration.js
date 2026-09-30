'use strict'
// Runs entirely in a rollback-only, isolated schema; never changes real rooms.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Client } = require('pg')
const { ensureVoiceRoomCodes } = require('../src/voice-room-identity')

async function verify() {
  const client = new Client({ connectionString: process.env.DATABASE_URL })
  await client.connect()
  const schema = `voice_code_qa_${crypto.randomBytes(8).toString('hex')}`
  try {
    await client.query('BEGIN')
    await client.query(`CREATE SCHEMA "${schema}"`)
    await client.query(`SET LOCAL search_path TO "${schema}"`)
    await client.query(`CREATE TABLE fallback_voice_rooms(
      id UUID PRIMARY KEY, readable_id TEXT, creator_id TEXT,
      agora_channel_name TEXT NOT NULL DEFAULT '', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`)
    const ids = Array.from({ length: 4 }, () => crypto.randomUUID())
    await client.query(`INSERT INTO fallback_voice_rooms(id, readable_id, creator_id) VALUES
      ($1, 'old-room-alias', 'owner'), ($2, NULL, 'owner'),
      ($3, '777777', 'owner'), ($4, '777777', 'owner')`, ids)
    await ensureVoiceRoomCodes(client)
    const before = (await client.query('SELECT * FROM fallback_voice_rooms ORDER BY id')).rows
    assert.equal(new Set(before.map((row) => row.readable_id)).size, 4)
    assert.ok(before.every((row) => /^[1-9][0-9]{5,6}$/.test(row.readable_id)))
    assert.equal(before.find((row) => row.id === ids[0]).legacy_readable_id, 'old-room-alias')
    assert.equal(before.filter((row) => row.readable_id === '777777').length, 1)
    await ensureVoiceRoomCodes(client)
    assert.deepEqual((await client.query('SELECT * FROM fallback_voice_rooms ORDER BY id')).rows, before)
    const created = (await client.query(`INSERT INTO fallback_voice_rooms(id, creator_id)
      VALUES ($1, 'owner') RETURNING readable_id`, [crypto.randomUUID()])).rows[0]
    assert.match(created.readable_id, /^[1-9][0-9]{5,6}$/)
    assert.ok(!before.some((row) => row.readable_id === created.readable_id))
    await client.query('SAVEPOINT duplicate_code')
    await assert.rejects(client.query(`INSERT INTO fallback_voice_rooms(id, readable_id)
      VALUES ($1, $2)`, [crypto.randomUUID(), created.readable_id]), { code: '23505' })
    await client.query('ROLLBACK TO SAVEPOINT duplicate_code')
    console.log(JSON.stringify({ migration: 'passed', numericCodes: 5, unique: true,
      idempotent: true, legacyAliasPreserved: true, scope: 'Isolated schema rolled back' }))
  } finally {
    await client.query('ROLLBACK')
    await client.end()
  }
}
verify().catch((error) => { console.error(error.message); process.exitCode = 1 })
