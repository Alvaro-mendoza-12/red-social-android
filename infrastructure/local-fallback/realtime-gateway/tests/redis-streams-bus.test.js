'use strict'

const assert = require('node:assert/strict')
const {
  encodeRespCommand,
  parseRedisConnectionConfig,
  parseRespValue,
} = require('../src/redis-resp-client')

async function run() {
  const cmd = encodeRespCommand(['XADD', 'novvin:realtime:events', 'MAXLEN', '~', '20000', '*', 'origin', 'n1', 'payload', '{"a":"é"}'])
  assert.ok(Buffer.isBuffer(cmd))
  assert.ok(cmd.toString('utf8').startsWith('*10\r\n$4\r\nXADD\r\n'))

  // Nested XREAD response with 2 entries
  const xreadReply = Buffer.from(
    '*1\r\n*2\r\n$22\r\nnovvin:realtime:events\r\n*2\r\n'
    + '*2\r\n$15\r\n1700000000001-0\r\n*4\r\n$6\r\norigin\r\n$2\r\nn1\r\n$7\r\nchannel\r\n$6\r\nchat:1\r\n'
    + '*2\r\n$15\r\n1700000000002-0\r\n*4\r\n$6\r\norigin\r\n$2\r\nn2\r\n$7\r\nchannel\r\n$6\r\nchat:2\r\n',
  )
  const parsed = parseRespValue(xreadReply, 0)
  assert.equal(parsed.value[0][0], 'novvin:realtime:events')
  assert.equal(parsed.value[0][1].length, 2)
  assert.equal(parsed.value[0][1][0][0], '1700000000001-0')
  assert.equal(parsed.value[0][1][1][0], '1700000000002-0')
  assert.deepEqual(parsed.value[0][1][1][1], ['origin', 'n2', 'channel', 'chat:2'])

  // Null XREAD timeout reply (*-1\r\n)
  const timeoutReply = parseRespValue(Buffer.from('*-1\r\n'), 0)
  assert.equal(timeoutReply.value, null)

  // Config validation
  const cfg = parseRedisConnectionConfig({ url: 'redis://127.0.0.1:6379', password: 'secret' })
  assert.equal(cfg.host, '127.0.0.1')
  assert.equal(cfg.port, 6379)
  assert.equal(cfg.password, 'secret')
  assert.throws(() => parseRedisConnectionConfig({ url: 'ws://127.0.0.1:6379' }), /redis:\/\/ or rediss:\/\//)

  console.log('realtime gateway redis streams RESP2 tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exit(1)
})
