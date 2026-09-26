const assert = require('node:assert/strict')
const http = require('node:http')
const { once } = require('node:events')
const test = require('node:test')
const { readJsonCheck, verifyImage, fetchVideoRange } = require('../scripts/verify-social-read-paths')

// Flush headers immediately, then stall the body: measuring fetch() alone
// used to report success even when the media transfer exceeded its budget.
test('read-path budgets include the body for JSON, avatars and video ranges', async (t) => {
  const server = http.createServer((req, res) => {
    const isImage = req.url.startsWith('/image')
    const isRange = req.url.startsWith('/video')
    res.writeHead(isRange ? 206 : 200, {
      'content-type': isImage ? 'image/webp' : isRange ? 'video/mp4' : 'application/json',
      ...(isRange ? { 'content-range': 'bytes 0-3/4', 'accept-ranges': 'bytes' } : {}),
    })
    res.flushHeaders()
    setTimeout(() => res.end(isImage || isRange ? 'body' : '{"items":[1]}'), 180)
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => { server.closeAllConnections(); server.close() })
  const baseUrl = `http://127.0.0.1:${server.address().port}`
  const checks = [
    (maxMs) => readJsonCheck({ baseUrl, pathname: '/json', maxMs, timeoutMs: 2_000, requireItems: true, name: 'json' }),
    (maxMs) => verifyImage({ sourceUrl: `${baseUrl}/image`, maxMs, timeoutMs: 2_000 }),
    (maxMs) => fetchVideoRange({ url: `${baseUrl}/video`, start: 0, end: 3, maxMs, timeoutMs: 2_000 }),
  ]
  for (const check of checks) {
    await assert.rejects(check(100), /exceeded 100 ms/)
    const result = await check(2_000)
    const evidence = result.evidence || result
    assert.ok(evidence.elapsedMs - evidence.ttfbMs >= 100, JSON.stringify(evidence))
  }
})
