const assert = require('node:assert/strict')
const { test } = require('node:test')
const { publishRealtimeEvent, publishChatMessageEvent } = require('../src/realtime-publish')

test('realtime delivery has a timeout and deduplicates recipient notifications', async () => {
  const original = global.fetch
  const calls = []
  global.fetch = async (_url, options) => {
    assert.ok(options.signal instanceof AbortSignal)
    calls.push(JSON.parse(options.body))
    return { ok: true }
  }
  try {
    await publishChatMessageEvent({ chatId: 'chat', recipientIds: ['one', 'one', 'two'], message: { text: 'hi' } })
    assert.deepEqual(calls.map((call) => call.channel).sort(), ['chat:chat', 'user:one', 'user:two'])
  } finally { global.fetch = original }
})

test('failed delivery returns false so callers can recover from persisted state', async () => {
  const original = global.fetch
  global.fetch = async () => ({ ok: false })
  try { assert.equal(await publishRealtimeEvent('feed:live', {}), false) }
  finally { global.fetch = original }
})
