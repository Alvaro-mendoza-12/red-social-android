'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const {
  findUniquePublicMention,
  profileMatchesQuery,
  publicMentionHandle,
  publicMentionHandles,
} = require('../src/data-routes')

test('public mention handles derive from display names without exposing emails', () => {
  assert.equal(publicMentionHandle('Keibér Joya'), 'keiber_joya')
  assert.equal(publicMentionHandle('joya@example.com'), '')
  assert.equal(publicMentionHandle('山田 太郎'), '山田_太郎')
  assert.equal(profileMatchesQuery({ display_name: 'Keibér Joya' }, 'keiber_joya'), true)
})

test('extracts unique public mentions without interpreting email addresses', () => {
  assert.deepEqual(
    publicMentionHandles('Hola @keiber_joya y @山田_太郎. @keiber_joya user@gmail.com'),
    ['keiber_joya', '山田_太郎'],
  )
  assert.deepEqual(publicMentionHandles('Hola @Keibér_Joya'), ['keiber_joya'])
})

test('resolves only a single matching public name, including profile overrides', async () => {
  const pool = {
    query: async (sql) => {
      if (sql.includes('information_schema.tables')) return { rows: [] }
      assert.match(sql, /fallback_profile_overrides/)
      return { rows: [{ id: 'user-1', display_name: 'Keibér Joya' }] }
    },
  }
  assert.equal((await findUniquePublicMention(pool, 'keiber_joya'))?.id, 'user-1')
  pool.query = async (sql) => sql.includes('information_schema.tables')
    ? { rows: [] }
    : { rows: [
      { id: 'user-1', display_name: 'Keiber Joya' },
      { id: 'user-2', display_name: 'Keibér Joya' },
    ] }
  assert.equal(await findUniquePublicMention(pool, 'keiber_joya'), null)
})
