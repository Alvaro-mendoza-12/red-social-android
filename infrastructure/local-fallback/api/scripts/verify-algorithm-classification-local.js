const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Client } = require('pg')
const { upsertTrustedContentClassification } = require('../src/algorithm-routes')

// Use real PostgreSQL semantics with temporary tables. This catches multiple
// updates to the same row in a CTE, which a mocked query client cannot detect.
async function main() {
  const client = new Client({ connectionString: process.env.DATABASE_URL, connectionTimeoutMillis: 5000 })
  await client.connect()
  try {
    await client.query('SET search_path = pg_temp, public')
    for (const table of ['fallback_algorithm_content_classifications', 'fallback_algorithm_content_topics']) {
      await client.query(`CREATE TEMP TABLE ${table} (LIKE public.${table} INCLUDING ALL)`)
    }
    const input = { contentKind: 'video', contentId: randomUUID(), creatorId: randomUUID(),
      languageCode: 'es', formatKey: 'short-video', safetyState: 'allowed', classifierVersion: 'isolated-qa',
      topics: [{ topicKey: 'juegos', confidence: 0.95 }] }
    const first = await upsertTrustedContentClassification(client, input)
    assert.ok(first.id, first.error)
    assert.equal((await client.query('SELECT safety_state FROM fallback_algorithm_content_classifications')).rows[0].safety_state, 'allowed')
    const second = await upsertTrustedContentClassification(client, { ...input,
      topics: [{ topicKey: 'juegos', confidence: 0.75 }, { topicKey: 'tecnologia', confidence: 0.8 }] })
    assert.equal(second.id, first.id)
    const topics = (await client.query('SELECT topic_key,confidence FROM fallback_algorithm_content_topics ORDER BY topic_key')).rows
    assert.deepEqual(topics.map(row => row.topic_key), ['juegos', 'tecnologia'])
    assert.equal(Number(topics[0].confidence), 0.75)
    const restricted = await upsertTrustedContentClassification(client, { ...input, safetyState: 'blocked', topics: [] })
    assert.ok(restricted.id, restricted.error)
    assert.equal((await client.query('SELECT safety_state FROM fallback_algorithm_content_classifications')).rows[0].safety_state, 'blocked')
    assert.equal((await client.query('SELECT * FROM fallback_algorithm_content_topics')).rows.length, 0)
    console.log(JSON.stringify({ ok: true, classificationCreated: true, rewriteSameTopic: true,
      finalSafetyState: 'blocked', removedStaleTopics: true, productionWrites: 0 }))
  } finally { await client.end() }
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
