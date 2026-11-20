'use strict'

const assert = require('node:assert/strict')
const { randomBytes } = require('node:crypto')
const { EventEmitter, once } = require('node:events')
const test = require('node:test')
const { setTimeout: wait } = require('node:timers/promises')
const redis = require('../src/redis-resp-client')

test('a transient stream read error resumes at the last cursor on the same connection', async t => {
  const observed = new EventEmitter()
  const cursors = []
  let initialQueries = 0
  let resumeRead
  let unblock
  t.mock.method(redis, 'createRedisRespClient', () => {
    const client = new EventEmitter()
    client.isReady = () => true
    client.connect = async () => { client.emit('ready') }
    client.close = async () => { unblock?.() }
    client.sendCommand = async command => {
      if (command[0] === 'XREVRANGE') {
        initialQueries++
        return [['10-0', []]]
      }
      assert.equal(command[0], 'XREAD')
      cursors.push(command.at(-1))
      if (cursors.length === 1) {
        await wait(20)
        throw new Error('Redis is loading its dataset')
      }
      if (cursors.length === 2) {
        await new Promise(resolve => { resumeRead = resolve; observed.emit('retrying') })
        return [['events', [['11-0', ['origin', 'peer', 'channel', 'live:fixture', 'payload', '{}']]]]]
      }
      observed.emit('resumed')
      return new Promise(resolve => { unblock = resolve })
    }
    return client
  })
  process.env.REALTIME_REDIS_URL = 'redis://127.0.0.1:6379'
  process.env.JWT_SECRET = randomBytes(32).toString('hex')
  process.env.REALTIME_INTERNAL_SECRET = randomBytes(32).toString('hex')
  const controller = new AbortController()
  const resumed = once(observed, 'resumed', { signal: controller.signal })
  const retrying = once(observed, 'retrying', { signal: controller.signal })
  const { server, redisBus } = require('../src/server')
  t.after(() => redisBus.close())
  // Bound reader recovery independently of cold Node module loading.
  const deadline = setTimeout(() => controller.abort(), 4000)
  t.after(() => clearTimeout(deadline))
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => server.close())
  const url = 'http://127.0.0.1:' + server.address().port
  await retrying
  try {
    const liveness = await fetch(url + '/health')
    const before = await fetch(url + '/ready')
    assert.equal(liveness.status, 200)
    assert.equal(before.status, 503)
    assert.equal((await before.json()).redisConnected, true)
  } finally {
    resumeRead()
  }
  await resumed
  const after = await fetch(url + '/ready')
  assert.equal(after.status, 200)
  assert.equal((await after.json()).redisReaderReady, true)
  assert.equal(initialQueries, 1)
  assert.deepEqual(cursors, ['10-0', '10-0', '11-0'])
})
