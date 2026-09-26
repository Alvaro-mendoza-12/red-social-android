const assert = require('assert')
const http = require('http')
const {
  callAiService,
  createFixedWindowRateLimiter,
  handleAiError,
  publicAiErrorCode,
  sanitizeContext,
  sanitizeFeedback,
} = require('../src/ai-routes')

async function run() {
  let now = 1_000
  const consume = createFixedWindowRateLimiter({ limit: 2, windowMs: 1_000, now: () => now })
  assert.strictEqual(consume('user-1').allowed, true)
  assert.strictEqual(consume('user-1').allowed, true)
  assert.strictEqual(consume('user-1').allowed, false)
  assert.strictEqual(consume('user-2').allowed, true)
  now += 1_001
  assert.strictEqual(consume('user-1').allowed, true)

  const context = sanitizeContext({
    conversationId: 'conversation-1',
    history: `old-${'x'.repeat(7000)}`,
    screen: 'videos',
    locale: 'es-CO',
    detailLevel: 'expanded',
    ignoredProviderFlag: true,
    ignoredPrivateField: 'must-not-cross-the-service-boundary',
  }, 'user-1')
  assert.deepStrictEqual(Object.keys(context), [
    'userId',
    'conversationId',
    'history',
    'screen',
    'locale',
    'detailLevel',
  ])
  assert.strictEqual(context.userId, 'user-1')
  assert.strictEqual(context.history.length, 6000)
  assert.ok(!context.history.startsWith('old-'))
  assert.strictEqual(context.detailLevel, 'expanded')
  assert.ok(!Object.hasOwn(context, 'ignoredProviderFlag'))

  const standardContext = sanitizeContext({
    detailLevel: 'anything-else',
  }, 'user-1')
  assert.strictEqual(standardContext.detailLevel, 'standard')
  assert.strictEqual(publicAiErrorCode({ payload: { error: 'INVALID_AI_INPUT' } }), 'INVALID_AI_INPUT')
  assert.strictEqual(publicAiErrorCode({ payload: { error: 'SQL failed at /run/secrets/key' } }), 'AI_SERVICE_ERROR')

  const feedback = sanitizeFeedback({
    message: 'm'.repeat(2000),
    answer: 'a'.repeat(2000),
    ignored: 'not-forwarded',
  }, 'user-1')
  assert.strictEqual(feedback.message.length, 1000)
  assert.strictEqual(feedback.answer.length, 1500)
  assert.ok(!Object.hasOwn(feedback, 'ignored'))

  const server = http.createServer((req, res) => {
    if (req.url === '/slow') {
      return setTimeout(() => {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end('{"ok":true}')
      }, 400)
    }
    if (req.url === '/bad') {
      res.writeHead(422, { 'content-type': 'application/json' })
      return res.end('{"error":"INVALID_AI_INPUT"}')
    }
    res.writeHead(200, { 'content-type': 'application/json' })
    return res.end('{"ok":true}')
  })

  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const previousUrl = process.env.AI_INTERNAL_URL
  process.env.AI_INTERNAL_URL = `http://127.0.0.1:${server.address().port}`
  try {
    assert.deepStrictEqual(await callAiService('/ok', { message: 'hola' }), { ok: true })

    await assert.rejects(
      () => callAiService('/bad', null),
      (error) => error.status === 422 && error.payload.error === 'INVALID_AI_INPUT',
    )
    await assert.rejects(
      () => callAiService('/slow', null, 250),
      (error) => error.status === 504 && error.code === 'AI_SERVICE_TIMEOUT',
    )

    const sent = {}
    const logged = []
    const originalConsoleError = console.error
    console.error = (...args) => logged.push(args)
    const response = {
      status(value) {
        sent.status = value
        return this
      },
      json(value) {
        sent.body = value
        return this
      },
    }
    try {
      handleAiError(Object.assign(new Error('offline'), {
        status: 503,
        code: 'AI_SERVICE_UNAVAILABLE',
      }), response, () => assert.fail('known AI errors must not reach the generic 500 handler'))
      assert.deepStrictEqual(sent, {
        status: 503,
        body: { error: 'AI_SERVICE_UNAVAILABLE' },
      })
      assert.strictEqual(logged.length, 1)
      assert.strictEqual(logged[0][1].code, 'AI_SERVICE_UNAVAILABLE')
      assert.ok(!JSON.stringify(logged).includes('offline'))

      handleAiError(Object.assign(new Error('private upstream failure'), {
        status: 502,
        payload: { error: 'private path /run/secrets/ai-key' },
      }), response, () => assert.fail('known AI errors must not reach the generic 500 handler'))
      assert.deepStrictEqual(sent, {
        status: 502,
        body: { error: 'AI_SERVICE_ERROR' },
      })
      assert.ok(!JSON.stringify(logged).includes('/run/secrets/ai-key'))
    } finally {
      console.error = originalConsoleError
    }
  } finally {
    if (previousUrl == null) delete process.env.AI_INTERNAL_URL
    else process.env.AI_INTERNAL_URL = previousUrl
    await new Promise((resolve) => server.close(resolve))
  }
}

run()
  .then(() => console.log('ai-routes tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
