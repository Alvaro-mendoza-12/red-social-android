import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { prepareIndexNow, retryDelay } from '../scripts/indexnow-notifier.mjs'
const origin = 'https://red-social-android.vercel.app'
function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-indexnow-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  const requests = [], waits = [], statuses = []
  let body = '<link rel="canonical" href="' + origin + '/"><meta name="robots" content="index, follow"><h1>Novvin</h1>'
  const options = { stateFile: path.join(directory, 'state.json'), routes: ['/'], now: () => 1800000000000, wait: async ms => waits.push(ms), fetchFn: async (url, init = {}) => {
    requests.push({ url, method: init.method || 'GET' })
    if (url.includes('api.indexnow.org')) return new Response('', { status: statuses.shift() || 200, headers: { 'Retry-After': '1' } })
    if (url.endsWith('/indexnow-key.txt')) return new Response('public-proof-12345678')
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/html' } })
  } }
  return { options, requests, waits, statuses, setBody: value => { body = value } }
}
test('default preparation never POSTs or writes a receipt ledger', async t => {
  const f = fixture(t), result = await prepareIndexNow(f.options)
  assert.equal(result.dryRun, true); assert.equal(result.submitted, false)
  assert.equal(f.requests.some(request => request.method === 'POST'), false)
  assert.equal(fs.existsSync(f.options.stateFile), false)
})
test('accepted unchanged pages are not submitted again, even across executions', async t => {
  const f = fixture(t)
  assert.equal((await prepareIndexNow({ ...f.options, submit: true })).status, 200)
  const second = await prepareIndexNow({ ...f.options, submit: true })
  assert.equal(second.submitted, false); assert.deepEqual(second.skipped, ['/'])
  assert.equal(f.requests.filter(request => request.method === 'POST').length, 1)
})
test('temporary rejection retries within bounds and distinguishes HTTP 202', async t => {
  const f = fixture(t); f.statuses.push(429, 202)
  const result = await prepareIndexNow({ ...f.options, submit: true })
  assert.equal(result.validationPending, true); assert.deepEqual(f.waits, [1000])
  assert.equal(retryDelay('Wed, 28 Oct 2026 00:00:00 GMT', Date.parse('2026-10-27T23:59:00Z')), 60000)
})
test('noncanonical, noindex and private content are rejected before notification', async t => {
  const f = fixture(t)
  await assert.rejects(prepareIndexNow({ ...f.options, routes: ['/app/messages'] }), /eligible public route/)
  f.setBody('<h1>Novvin</h1>'); await assert.rejects(prepareIndexNow(f.options), /canonical/)
  f.setBody('<meta name="robots" content="noindex"><h1>Novvin</h1>'); await assert.rejects(prepareIndexNow(f.options), /noindex/)
  assert.equal(f.requests.some(request => request.method === 'POST'), false)
})
test('permanent rejection does not save success and releases its concurrency lock', async t => {
  const f = fixture(t); f.statuses.push(403)
  await assert.rejects(prepareIndexNow({ ...f.options, submit: true }), /403/)
  assert.deepEqual(JSON.parse(fs.readFileSync(f.options.stateFile, 'utf8')).pages, {})
  assert.equal(fs.existsSync(f.options.stateFile + '.lock'), false)
})
test('concurrent batches and frequent changed batches cannot double-submit', async t => {
  const f = fixture(t)
  fs.writeFileSync(f.options.stateFile + '.lock', '')
  await assert.rejects(prepareIndexNow({ ...f.options, submit: true }), /already|running/)
  fs.unlinkSync(f.options.stateFile + '.lock')
  await prepareIndexNow({ ...f.options, submit: true })
  f.setBody('<link rel="canonical" href="' + origin + '/"><h1>Changed</h1>')
  await assert.rejects(prepareIndexNow({ ...f.options, submit: true }), /Rate limit/)
})
test('deletion notifications require a previous public receipt plus confirmed removal', async t => {
  const f = fixture(t)
  await assert.rejects(prepareIndexNow({ ...f.options, deleted: true }), /previous public receipt/)
  await prepareIndexNow({ ...f.options, submit: true })
  await assert.rejects(prepareIndexNow({ ...f.options, deleted: true }), /404\/410/)
  const result = await prepareIndexNow({ ...f.options, deleted: true, fetchFn: async () => new Response('', { status: 410 }) })
  assert.equal(result.deleted, true); assert.deepEqual(result.routes, ['/'])
})
