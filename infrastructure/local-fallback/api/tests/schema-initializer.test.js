const assert = require('assert/strict')
const {
  boundedTimeout,
  withSchemaInitializationLock,
} = require('../src/schema-initializer')

function fakePool({ failUnlock = false } = {}) {
  const calls = []
  const releases = []
  const client = {
    async query(sql, params = []) {
      calls.push({ params, sql: String(sql) })
      if (failUnlock && String(sql).includes('pg_advisory_unlock')) {
        throw new Error('unlock failed')
      }
      return { rows: [] }
    },
    release(error) {
      releases.push(error)
    },
  }
  return {
    calls,
    client,
    pool: { async connect() { return client } },
    releases,
  }
}

async function run() {
  assert.equal(boundedTimeout('1000'), 30_000)
  assert.equal(boundedTimeout('99999999'), 15 * 60_000)
  assert.equal(boundedTimeout('invalid'), 180_000)

  const success = fakePool()
  const result = await withSchemaInitializationLock({
    pool: success.pool,
    task: async (client) => {
      assert.equal(client, success.client)
      await client.query('CREATE TABLE test_table(id integer)')
      return 'ready'
    },
  })
  assert.equal(result, 'ready')
  assert.match(success.calls[0].sql, /set_config/)
  assert.match(success.calls[1].sql, /pg_advisory_lock/)
  assert.match(success.calls.at(-2).sql, /pg_advisory_unlock/)
  assert.match(success.calls.at(-1).sql, /RESET statement_timeout/)
  assert.deepEqual(success.releases, [undefined])

  const taskFailure = fakePool()
  const expected = new Error('schema failed')
  await assert.rejects(
    withSchemaInitializationLock({
      pool: taskFailure.pool,
      task: async () => { throw expected },
    }),
    (error) => error === expected,
  )
  assert.match(taskFailure.calls.at(-2).sql, /pg_advisory_unlock/)
  assert.deepEqual(taskFailure.releases, [undefined])

  const unlockFailure = fakePool({ failUnlock: true })
  await assert.rejects(
    withSchemaInitializationLock({ pool: unlockFailure.pool, task: async () => 'done' }),
    /unlock failed/,
  )
  assert.equal(unlockFailure.releases[0]?.message, 'unlock failed')

  console.log('schema initializer tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exit(1)
})
