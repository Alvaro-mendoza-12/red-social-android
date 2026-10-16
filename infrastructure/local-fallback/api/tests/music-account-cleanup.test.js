const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { deleteAccountMusic } = require('../src/music-library-routes')
const owner = '00000000-0000-4000-8000-000000000042'

test('account music deletion is bound to the authenticated local owner', async () => {
  const queries = []
  const count = await deleteAccountMusic({ async query(sql, params) { queries.push({sql,params}); return {rowCount:2} } }, owner)
  assert.equal(count, 2)
  assert.deepEqual(queries, [{sql:'DELETE FROM fallback_music_tracks WHERE owner_id=$1::uuid', params:[owner]}])
  assert.doesNotMatch(queries[0].sql, /fallback_moment_music|fallback_music_provider_cache/)
})

test('account music deletion rejects missing, broad or injected identities before querying', async () => {
  let calls = 0
  for (const value of [null, undefined, '', '*', "' OR TRUE --", [owner]]) {
    await assert.rejects(deleteAccountMusic({query(){calls++;return {rowCount:1}}}, value), TypeError)
  }
  assert.equal(calls, 0)
})

test('music deletion propagates database failure to the surrounding account transaction', async () => {
  const failure = new Error('database unavailable')
  await assert.rejects(deleteAccountMusic({async query(){throw failure}}, owner), failure)
})

test('the account transaction removes music before storage and the account row', () => {
  const source = readFileSync(require.resolve('../src/postgrest-compat-routes'), 'utf8')
  const body = source.slice(source.indexOf('async function deleteOwnAccountRpc('))
  const transaction = body.indexOf('runInTransaction(pool, async (client)')
  const music = body.indexOf('await deleteAccountMusic(client, localUserId)')
  const storage = body.indexOf('DELETE FROM fallback_storage_objects WHERE owner_id')
  const user = body.indexOf('DELETE FROM fallback_users WHERE id')
  assert.ok(transaction >= 0 && transaction < music && music < storage && storage < user)
  assert.match(body, /JSON.stringify\(\{ importedRowsDeleted, compatRowsDeleted, musicTracksDeleted \}\)/)
})
