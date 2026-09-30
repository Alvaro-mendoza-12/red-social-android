'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { runReadLoad } = require('../scripts/verify-bounded-read-load')

test('limits in-flight requests and consumes the exact requested count', async () => {
  let active = 0, peak = 0, count = 0
  const report = await runReadLoad({ baseUrl: 'https://example.invalid', requests: 31, concurrency: 3,
    fetchImpl: async () => {
      count++; peak = Math.max(peak, ++active)
      await new Promise(resolve => setTimeout(resolve, 2)); active--
      return new Response('{"ok":true}', { headers: { 'x-response-time': '2.5ms' } })
    } })
  assert.equal(count, 31); assert.equal(peak, 3); assert.equal(report.successes, 31)
  assert.equal(report.maxServerMs, 2.5); assert.equal(report.ok, true)
})
test('does not call failed responses successful and records rejected fetches', async () => {
  let count = 0
  const report = await runReadLoad({ baseUrl: 'http://127.0.0.1', requests: 4, concurrency: 1,
    fetchImpl: async () => { if (++count === 1) throw new Error('offline'); return new Response('{}', { status: 503 }) } })
  assert.equal(report.failures, 4); assert.equal(report.ok, false)
})
test('rejects writes, credentials and unbounded test inputs', async () => {
  for (const options of [{ path: '/v1/auth/register' }, { concurrency: 25 }, { requests: 201 },
    { timeoutMs: 0 }, { baseUrl: 'https://user:secret@example.invalid' }]) {
    await assert.rejects(runReadLoad({ baseUrl: 'https://example.invalid', ...options }))
  }
})
