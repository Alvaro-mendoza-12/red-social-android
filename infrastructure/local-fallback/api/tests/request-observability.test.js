const assert = require('assert/strict')
const express = require('express')
const {
  createRequestObservabilityMiddleware,
  routeName,
  safeErrorLogDetails,
} = require('../src/request-observability')

async function run() {
  const warnings = []
  const privateDiagnostic = 'storage path /private/users/42/token-secret failed'
  const productionLog = safeErrorLogDetails(new Error(privateDiagnostic), {
    production: true,
    fallbackCode: 'INTERNAL_ERROR',
  })
  assert.equal(productionLog.code, 'INTERNAL_ERROR')
  assert.match(productionLog.messageRef, /^[a-f0-9]{24}$/)
  assert.equal(Object.hasOwn(productionLog, 'message'), false)
  assert.equal(JSON.stringify(productionLog).includes(privateDiagnostic), false)

  const developmentLog = safeErrorLogDetails(new Error(privateDiagnostic), { production: false })
  assert.equal(developmentLog.message, privateDiagnostic)
  assert.equal(developmentLog.messageRef, productionLog.messageRef)

  const app = express()
  app.use((req, _res, next) => {
    req.requestId = 'request-observability-test'
    next()
  })
  app.use(createRequestObservabilityMiddleware({
    slowRequestMs: 100,
    logger: { warn: (...args) => warnings.push(args) },
  }))
  app.get('/items/:id', async (_req, res) => {
    await new Promise((resolve) => setTimeout(resolve, 120))
    res.json({ ok: true })
  })
  const server = await new Promise((resolve) => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
  })
  try {
    const address = server.address()
    const response = await fetch(`http://127.0.0.1:${address.port}/items/private-value?secret=hidden`)
    assert.equal(response.status, 200)
    assert.match(response.headers.get('server-timing'), /^app;dur=\d+\.\d$/)
    assert.match(response.headers.get('x-response-time'), /^\d+\.\dms$/)
    assert.equal(warnings.length, 1)
    assert.equal(warnings[0][1].route, '/items/:id')
    assert.equal(JSON.stringify(warnings).includes('private-value'), false)
    assert.equal(JSON.stringify(warnings).includes('hidden'), false)
    assert.equal(routeName({ baseUrl: '', route: { path: '/users/:id' } }), '/users/:id')
  } finally {
    await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())))
  }
  console.log('request-observability tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
