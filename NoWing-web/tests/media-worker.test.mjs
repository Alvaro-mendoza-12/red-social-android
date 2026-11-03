import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import fs from 'node:fs'

const code = fs.readFileSync(new URL('../public/novvin-media-worker.js', import.meta.url), 'utf8')
const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const token = 'synthetic-media-token-never-production'
const source = 'https://api.example.invalid/storage/v1/object/public/videos/clip.mp4'
function fixture(fetcher = async () => new Response('ab', { status: 206, headers: { 'Content-Range': 'bytes 0-1/6', 'Content-Type': 'video/mp4' } })) {
  const handlers = {}, requests = [], tasks = []
  const self = { location: { origin: 'https://web.example.invalid' }, addEventListener: (name, callback) => { handlers[name] = callback }, clients: { get: async () => null, claim: async () => {} }, skipWaiting() {} }
  vm.runInNewContext(code, { self, URL, Headers, Response, Request, Map, setTimeout, clearTimeout, fetch: async (...args) => { requests.push(args); return fetcher(...args) } })
  const register = (client = 'a', extra = {}) => handlers.message({ source: { id: client }, ports: [], data: { type: 'NOVVIN_MEDIA_REGISTER', id, source, base: 'https://api.example.invalid', token, generation: 1, ...extra } })
  const read = async (client = 'a', range = 'bytes=0-1') => {
    let response
    handlers.fetch({ clientId: client, request: new Request(`https://web.example.invalid/__novvin_media/${id}`, { headers: range ? { Range: range } : {} }), respondWith: value => { response = value }, waitUntil: task => tasks.push(task) })
    return await response
  }
  return { handlers, requests, register, read, self, settle: () => Promise.all(tasks) }
}
test('authorized native media streaming preserves Range without URL tokens', async () => {
  const f = fixture(); f.register()
  const response = await f.read()
  assert.equal(response.status, 206)
  assert.equal(response.headers.get('content-range'), 'bytes 0-1/6')
  assert.equal(response.headers.get('cache-control'), 'private, no-store')
  assert.equal(f.requests[0][0], source)
  assert.equal(f.requests[0][1].headers.get('Authorization'), `Bearer ${token}`)
  assert.equal(f.requests[0][1].headers.get('Range'), 'bytes=0-1')
  assert.equal(f.requests[0][1].redirect, 'error')
})

test('media edge preserves the range and falls back to the canonical origin on service errors', async () => {
  const edge = source.replace('api.example.invalid', 'media.example.invalid')
  const f = fixture(async url => url === edge ? new Response('', { status: 503 }) : new Response('ab', { status: 206 }))
  f.register('a', { edge })
  assert.equal((await f.read()).status, 206)
  assert.deepEqual(f.requests.map(([url]) => url), [edge, source])
  for (const [, request] of f.requests) {
    assert.equal(request.headers.get('Authorization'), `Bearer ${token}`)
    assert.equal(request.headers.get('Range'), 'bytes=0-1')
    assert.equal(request.redirect, 'error')
  }
})

test('edge denial is preserved and credentials cannot follow a mismatched media path', async () => {
  const edge = source.replace('api.example.invalid', 'media.example.invalid')
  const denied = fixture(async () => new Response('', { status: 403 }))
  denied.register('a', { edge })
  assert.equal((await denied.read()).status, 403)
  assert.deepEqual(denied.requests.map(([url]) => url), [edge])
  const mismatch = fixture()
  mismatch.register('a', { edge: `${edge}?other=true` })
  assert.equal((await mismatch.read()).status, 206)
  assert.deepEqual(mismatch.requests.map(([url]) => url), [source])
})

test('document downloads stream as inert attachments in the same authenticated client', async () => {
  const f=fixture(async()=>new Response('<html>document</html>',{headers:{'Content-Type':'text/html'}}));f.register('a',{downloadName:'Carpeta.zip'})
  const response=await f.read('a')
  assert.equal(response.headers.get('content-type'),'application/octet-stream')
  assert.equal(response.headers.get('content-disposition'),"attachment; filename*=UTF-8''Carpeta.zip")
  assert.equal(response.headers.get('x-content-type-options'),'nosniff')
  assert.equal(await response.text(),'<html>document</html>')
})
test('a second browser client cannot reuse another client media handle', async () => {
  const f = fixture(); f.register('a')
  assert.equal((await f.read('b')).status, 404)
  assert.equal(f.requests.length, 0)
})
test('untrusted URL, URL tokens and multiple ranges do not reach the network', async () => {
  for (const value of ['https://attacker.example/storage/v1/object/public/videos/a', source + '?access_token=secret']) {
    const f = fixture(); f.register('a', { source: value })
    assert.equal((await f.read()).status, 404); assert.equal(f.requests.length, 0)
  }
  const f = fixture(); f.register()
  assert.equal((await f.read('a', 'bytes=0-1,3-4')).status, 416); assert.equal(f.requests.length, 0)
})
test('identity invalidation discards an outstanding media response', async () => {
  let finish
  const f = fixture(() => new Promise(resolve => { finish = resolve })); f.register()
  const response = f.read()
  f.handlers.message({ source: { id: 'a' }, data: { type: 'NOVVIN_MEDIA_CLEAR' }, ports: [] })
  finish(new Response('private media', { status: 200 }))
  assert.equal((await response).status, 403)
})
test('registering the same source twice does not invalidate an outstanding response', async () => {
  let finish
  const f = fixture(() => new Promise(resolve => { finish = resolve })); f.register()
  const response = f.read(); f.register()
  finish(new Response('ab', { status: 206 }))
  assert.equal((await response).status, 206)
})
test('concurrent reads after a worker restart all resume when the client restores its handle', async () => {
  const f = fixture()
  f.self.clients.get = async () => ({ postMessage() { setTimeout(() => f.register(), 0) } })
  const responses = await Promise.all([f.read(), f.read(), f.read()])
  assert(responses.every(response => response.status === 206))
  assert.equal(f.requests.length, 3)
})
test('an expired bearer is refreshed once and the original Range is retained', async () => {
  const f = fixture(async (_url, options) => options.headers.get('Authorization') === `Bearer ${token}` ? new Response('', { status: 401 }) : new Response('ab', { status: 206 }))
  f.register()
  f.self.clients.get = async () => ({ postMessage() { setTimeout(() => f.register('a', { token: token + '-refreshed' }), 0) } })
  assert.equal((await f.read()).status, 206)
  assert.equal(f.requests.length, 2)
  assert.equal(f.requests[1][1].headers.get('Range'), 'bytes=0-1')
})
test('server error bodies are not rendered into a media element', async () => {
  const f = fixture(async () => new Response('internal diagnostic and URL', { status: 403 }))
  f.register(); const response = await f.read()
  assert.equal(response.status, 403); assert.equal(await response.text(), '')
})

test('small avatar bytes are reused in RAM within their authenticated client', async () => {
  const f = fixture(async () => new Response('image-bytes', { headers: { 'Content-Type': 'image/webp', 'Content-Length': '11' } }))
  f.register(); assert.equal(await (await f.read('a', null)).text(), 'image-bytes'); await f.settle()
  assert.equal(await (await f.read('a', null)).text(), 'image-bytes')
  assert.equal(f.requests.length, 1)
  assert.equal((await f.read('b', null)).status, 404)
  f.handlers.message({ source: { id: 'a' }, data: { type: 'NOVVIN_MEDIA_CLEAR' }, ports: [] })
  f.register(); await f.read('a', null)
  assert.equal(f.requests.length, 2)
})

test('cached avatars are discarded when the client identity generation changes', async () => {
  const f = fixture(async () => new Response('ab', { headers: { 'Content-Type': 'image/png', 'Content-Length': '2' } }))
  f.register(); await f.read('a', null); await f.settle()
  f.register('a', { generation: 2 }); await f.read('a', null)
  assert.equal(f.requests.length, 2)
})

test('small streamed images without Content-Length are also reused on reentry', async () => {
  const f = fixture(async () => new Response('streamed-avatar', { headers: { 'Content-Type': 'image/webp' } }))
  f.register(); await f.read('a', null); await f.settle(); await f.read('a', null)
  assert.equal(f.requests.length, 1)
})

test('unknown-length images exceeding the byte cap are never retained', async () => {
  const f = fixture(async () => new Response(new Uint8Array(2097153), { headers: { 'Content-Type': 'image/webp' } }))
  f.register(); await (await f.read('a', null)).arrayBuffer(); await f.settle(); await f.read('a', null)
  assert.equal(f.requests.length, 2)
})

test('videos, documents, oversized images and Range requests never enter the image cache', async () => {
  for (const headers of [
    { 'Content-Type': 'video/mp4', 'Content-Length': '2' },
    { 'Content-Type': 'text/html', 'Content-Length': '2' },
    { 'Content-Type': 'image/png', 'Content-Length': '2097153' },
  ]) {
    const f = fixture(async () => new Response('ab', { headers }))
    f.register(); await f.read('a', null); await f.settle(); await f.read('a', null)
    assert.equal(f.requests.length, 2)
  }
})
