const assert = require('node:assert/strict')
const http = require('node:http')
const { once } = require('node:events')
const test = require('node:test')
const { verifyScheduledMessageRead } = require('../scripts/verify-scheduled-message-read')

async function fixture(t, responseBody, delay = 0) {
  const calls = []
  const server = http.createServer(async (req, res) => {
    let body = ''
    for await (const chunk of req) body += chunk
    calls.push({ path: req.url, method: req.method, authorization: req.headers.authorization, body: JSON.parse(body) })
    res.writeHead(200, { 'content-type': 'application/json' })
    res.flushHeaders()
    setTimeout(() => res.end(JSON.stringify(responseBody)), delay)
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => { server.closeAllConnections(); server.close() })
  return { calls, baseUrl: `http://127.0.0.1:${server.address().port}` }
}

test('scheduled monitor exercises only the read RPC and accepts a genuinely empty list', async (t) => {
  const { calls, baseUrl } = await fixture(t, [])
  const evidence = await verifyScheduledMessageRead({ baseUrl, token: 'test-token', chatId: 'chat-id', maxMs: 2000, timeoutMs: 3000 })
  assert.equal(evidence.items, 0)
  assert.equal(evidence.status, 200)
  assert.deepEqual(calls, [{ path: '/rest/v1/rpc/secure_get_chat_scheduled_jobs', method: 'POST', authorization: 'Bearer test-token', body: { p_scope_type: 'dm', p_target_id: 'chat-id' } }])
})

test('scheduled monitor rejects malformed success payload instead of reporting no jobs', async (t) => {
  const { baseUrl } = await fixture(t, { error: 'not-a-list' })
  await assert.rejects(verifyScheduledMessageRead({ baseUrl, token: 'test-token', chatId: 'chat-id', maxMs: 2000, timeoutMs: 3000 }), /invalid list/)
})

test('scheduled monitor measures completion of a slow body', async (t) => {
  const { baseUrl } = await fixture(t, [], 180)
  await assert.rejects(verifyScheduledMessageRead({ baseUrl, token: 'test-token', chatId: 'chat-id', maxMs: 100, timeoutMs: 3000 }), /exceeded 100 ms/)
})
