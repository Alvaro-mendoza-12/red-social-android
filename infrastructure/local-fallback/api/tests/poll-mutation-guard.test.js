const assert = require('node:assert/strict')
const test = require('node:test')
const { _test } = require('../src/postgrest-compat-routes')

const identity = { ids: new Set(['qa-local', 'qa-public']), values: ['qa-local', 'qa-public'],
  localUserId: 'qa-local', preferredUserId: 'qa-public' }
const noQueries = { async query() { assert.fail('A rejected generic vote mutation must not touch the database') } }

test('poll votes use only the transactional RPC rather than generic table mutations', () => {
  assert.equal(_test.SERVER_MUTATION_ONLY_TABLES.has('message_poll_votes'), true)
})
test('generic insertion cannot bypass closed polls or single-choice validation', async () => {
  await assert.rejects(_test.prepareInsertRow(noQueries, 'message_poll_votes', identity, {
    id: 'qa-vote', poll_id: 'qa-poll', option_id: 'qa-option', user_id: 'qa-public',
  }), (error) => error.status === 403 && error.message === 'SERVER_MANAGED_RESOURCE')
})
for (const action of ['update', 'delete']) {
  test(`generic ${action} cannot bypass transactional vote changes`, async () => {
    const permitted = await _test.authorizeMutation(noQueries, 'message_poll_votes', identity,
      { id: 'qa-vote', poll_id: 'qa-poll', option_id: 'qa-option', user_id: 'qa-public' },
      { option_id: 'qa-other' }, action)
    assert.equal(permitted, false)
  })
}
