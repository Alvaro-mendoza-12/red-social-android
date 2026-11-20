const assert = require('node:assert/strict')
const { test } = require('node:test')
const { publishRealtimeEvent, publishRealtimeEvents, publishChatMessageEvent } = require('../src/realtime-publish')

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

test('group and community fan-out batches over 5 recipients into a single publish-batch call', async () => {
  const original = global.fetch
  const requests = []
  global.fetch = async (url, options) => {
    requests.push({ url: String(url), body: JSON.parse(options.body) })
    return { ok: true, status: 200 }
  }
  try {
    await publishChatMessageEvent({
      chatId: 'room:group:g1',
      recipientIds: ['u1', 'u2', 'u3', 'u4', 'u5', 'u6', 'u6'],
      message: { text: 'hola grupo' },
    })
    assert.equal(requests.length, 1)
    assert.ok(requests[0].url.endsWith('/internal/publish-batch'))
    assert.equal(requests[0].body.events.length, 7)
  } finally { global.fetch = original }
})

test('publishRealtimeEvents falls back to single publish when gateway lacks batch endpoint', async () => {
  const original = global.fetch
  const urls = []
  global.fetch = async (url) => {
    urls.push(String(url))
    if (String(url).endsWith('/internal/publish-batch')) return { ok: false, status: 404 }
    return { ok: true, status: 200 }
  }
  try {
    const ok = await publishRealtimeEvents([
      { channel: 'user:a', payload: { event: 'message' }, excludeSessionId: 'sess-1' },
      { channel: 'user:b', payload: { event: 'message' } },
    ])
    assert.equal(ok, true)
    assert.equal(urls.filter((u) => u.endsWith('/internal/publish')).length, 2)
  } finally { global.fetch = original }
})

test('publishRealtimeEvent forwards excludeSessionId when provided', async () => {
  const original = global.fetch
  let sentBody = null
  global.fetch = async (_url, options) => {
    sentBody = JSON.parse(options.body)
    return { ok: true, status: 200 }
  }
  try {
    assert.equal(await publishRealtimeEvent('user:u1', { action: 'answered_elsewhere' }, { excludeSessionId: 'sid-a' }), true)
    assert.equal(sentBody.excludeSessionId, 'sid-a')
  } finally { global.fetch = original }
})

test('failed delivery returns false so callers can recover from persisted state', async () => {
  const original = global.fetch
  global.fetch = async () => ({ ok: false })
  try { assert.equal(await publishRealtimeEvent('feed:live', {}), false) }
  finally { global.fetch = original }
})
