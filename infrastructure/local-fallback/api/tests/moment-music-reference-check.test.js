'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { verifyMomentMusicReferences } = require('../scripts/verify-moment-music-references')

test('real-catalog diagnostic remains read-only and does not expose track metadata', async () => {
  const id = 'e0323c78-89ee-41bc-bf19-ae0587a2b001'
  const audioUrl = 'https://old.example/storage/v1/object/public/community-music/owner/track.m4a'
  const queries = []
  const client = { query: async (sql) => {
    queries.push(sql)
    if (sql.includes('SELECT id,audio_url')) return { rows: [{ id, audio_url: audioUrl }] }
    if (sql.includes('WHERE id=$1::uuid')) return { rows: [{ id, audio_url: audioUrl,
      duration_ms: 12_000, title: 'Private metadata', owner_id: 'owner' }] }
    return { rows: [] }
  } }
  const result = await verifyMomentMusicReferences(client, 'https://current.example')
  assert.equal(result.verifiedTracks, 1)
  assert.equal(result.observedHostRotations, 1)
  assert.equal(result.skipped, false)
  assert.equal(queries[0], 'BEGIN READ ONLY')
  assert.equal(queries.at(-1), 'ROLLBACK')
  assert.equal(queries.some((sql) => /\b(INSERT|UPDATE|DELETE|CREATE|COMMIT)\b/.test(sql)), false)
  assert.doesNotMatch(JSON.stringify(result), /Private metadata|old\.example|owner|e0323c78/)
})

test('empty catalog is explicitly skipped rather than a publishing pass', async () => {
  const result = await verifyMomentMusicReferences({ query: async () => ({ rows: [] }) }, 'https://current.example')
  assert.equal(result.skipped, true)
  assert.equal(result.verifiedTracks, 0)
})

test('catalog validation failure still rolls back and invalid origins never reach the database', async () => {
  const queries = []
  const client = { query: async (sql) => {
    queries.push(sql)
    if (sql.includes('SELECT id,audio_url')) throw new Error('fixture failure')
    return { rows: [] }
  } }
  await assert.rejects(verifyMomentMusicReferences(client, 'https://current.example'), /fixture failure/)
  assert.equal(queries.at(-1), 'ROLLBACK')
  queries.length = 0
  for (const origin of ['http://current.example', 'https://login:secret@current.example', 'https://current.example/path']) {
    await assert.rejects(verifyMomentMusicReferences(client, origin), /HTTPS_ROOT_ORIGIN_REQUIRED/)
  }
  assert.equal(queries.length, 0)
})
