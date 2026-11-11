const { test } = require('node:test')
const assert = require('node:assert/strict')
const { _test } = require('../src/postgrest-compat-routes')

test('account cleanup expands JSONB once and converts only identity fields', async () => {
  let captured
  const deleted = await _test.purgeCompatIdentityRows({ async query(sql, params) {
    captured = { sql, params }
    return { rowCount: 4 }
  } }, ['own-identity'])
  assert.equal(deleted, 4)
  assert.equal((captured.sql.match(/FROM jsonb_each\(c.row\)/g) || []).length, 1)
  assert.doesNotMatch(captured.sql, /jsonb_each_text/)
  assert.match(captured.sql, /field.value #>> '\{\}'/)
  assert.match(captured.sql, /field.key = ANY\(\$3::text\[\]\)/)
  assert.deepEqual(captured.params[0], ['own-identity'])
  assert.deepEqual(captured.params[3], ['%own-identity%'])
  assert.ok(captured.params[1].includes('user_id'))
  assert.ok(captured.params[2].includes('participants'))
  assert.ok(!captured.params[1].includes('body'))
})

test('account transaction purges friendship state after sources and before identity deletion',()=>{
  const source=require('node:fs').readFileSync(require.resolve('../src/postgrest-compat-routes'),'utf8')
  const body=source.slice(source.indexOf('async function deleteOwnAccountRpc('))
  const transaction=body.indexOf('runInTransaction(pool, async (client)')
  const imported=body.indexOf('await purgeImportedIdentityRows(client, identityValues)')
  const compat=body.indexOf('await purgeCompatIdentityRows(client, identityValues)')
  const state=body.indexOf('await purgeFriendRequestIdentity(client, identityValues)')
  const user=body.indexOf('DELETE FROM fallback_users WHERE id')
  assert.ok(transaction>=0 && imported>transaction && compat>imported && state>compat && user>state)
})
