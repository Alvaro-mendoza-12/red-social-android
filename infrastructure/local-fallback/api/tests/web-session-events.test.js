const assert = require('node:assert/strict')
const { test } = require('node:test')
const { publishWebSessionsRevoked } = require('../src/web-session-events')

test('publishes private device ids without refresh tokens, addresses or pairing secrets', async () => {
  const sent = []
  await publishWebSessionsRevoked('OWNER', [{ id:'device-a', refresh_token_hash:'secret', ip_address:'private' }, { id:'device-a' }, { id:'device-b' }], async (...args) => { sent.push(args); return true })
  assert.deepEqual(sent, [['user:owner', { event:'web_sessions_revoked', sessionIds:['device-a','device-b'] }]])
})
test('does not publish an empty or ownerless revocation', async () => {
  const publish = () => { throw new Error('must not publish') }
  assert.equal(await publishWebSessionsRevoked('', [{id:'a'}], publish), false)
  assert.equal(await publishWebSessionsRevoked('owner', [], publish), false)
})
