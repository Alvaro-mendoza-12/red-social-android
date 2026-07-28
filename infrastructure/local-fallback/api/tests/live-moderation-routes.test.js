const assert = require('assert')
const {
  cleanBlockedWords,
  containsBlockedWord,
  ensureLiveTables,
} = require('../src/live-routes')

async function testBlockedWordNormalization() {
  assert.deepStrictEqual(
    cleanBlockedWords([' Estafa ', 'estafa', 'x', 'ODIO', '', null]),
    ['estafa', 'odio'],
  )
  assert.strictEqual(containsBlockedWord('Esto parece una estafa total', ['estafa']), true)
  assert.strictEqual(containsBlockedWord('No confundir estafador con la palabra exacta', ['estafa']), false)
  assert.strictEqual(containsBlockedWord('Mensaje con ÓDIO explícito', ['odio']), true)
  assert.strictEqual(containsBlockedWord('Comentario permitido', ['odio']), false)
}

async function testDurableModerationSchema() {
  const statements = []
  const pool = {
    async query(statement) {
      statements.push(String(statement).replace(/\s+/g, ' ').trim())
      return { rows: [] }
    },
  }
  await ensureLiveTables(pool)
  const schema = statements.join('\n')
  assert.match(schema, /chat_paused BOOLEAN NOT NULL DEFAULT FALSE/)
  assert.match(schema, /blocked_words JSONB NOT NULL DEFAULT '\[\]'::jsonb/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_moderators/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_bans/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_live_moderation_audit/)
  assert.match(schema, /REFERENCES fallback_live_streams\(id\) ON DELETE CASCADE/)
}

Promise.resolve()
  .then(testBlockedWordNormalization)
  .then(testDurableModerationSchema)
  .then(() => console.log('live moderation route tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
