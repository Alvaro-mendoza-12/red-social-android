/* Auth stays in memory and is scoped to the browser client that requested the media. */
const records = new Map()
const tokens = new Map()
const pending = new Map()
// Private image bytes live only in this worker's RAM, scoped to one client and
// identity generation. Never persist authenticated responses in Cache Storage.
const imageCache = new Map()
const imageCacheLimit = 24 * 1024 * 1024
const imageEntryLimit = 2 * 1024 * 1024
let imageCacheBytes = 0
function removeImage(key) {
  const entry = imageCache.get(key)
  if (entry) imageCacheBytes -= entry.bytes.byteLength
  imageCache.delete(key)
}
function clearClientImages(client) {
  for (const [key, entry] of imageCache) if (entry.client === client) removeImage(key)
}
async function rememberImage(response, key, record, auth) {
  const reader = response.body?.getReader()
  if (!reader) return
  const chunks = []
  let size = 0
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > imageEntryLimit || tokens.get(record.client) !== auth || records.get(key) !== record) return
      chunks.push(value)
    }
    if (!size || tokens.get(record.client) !== auth || records.get(key) !== record) return
    const bytes = new Uint8Array(size)
    let offset = 0
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
    removeImage(key)
    while (imageCache.size && (imageCacheBytes + size > imageCacheLimit || imageCache.size >= 128)) removeImage(imageCache.keys().next().value)
    imageCache.set(key, { client: record.client, record, auth, bytes, headers: [...response.headers], expires: Date.now() + 120000 })
    imageCacheBytes += size
  } catch { /* A failed preview read must not interrupt native media playback. */ }
  finally { void reader.cancel().catch(() => {}); reader.releaseLock() }
}
async function refresh(clientId, id) {
  const client = await self.clients.get(clientId)
  if (!client) return
  const key = `${clientId}/${id}`
  await new Promise(resolve => {
    let callbacks = pending.get(key)
    if (!callbacks) { callbacks = new Set(); pending.set(key, callbacks) }
    const done = () => { clearTimeout(timer); callbacks.delete(done); if (!callbacks.size) pending.delete(key); resolve() }
    const timer = setTimeout(done, 5000)
    callbacks.add(done)
    client.postMessage({ type: 'NOVVIN_MEDIA_REFRESH', id })
  })
}
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()))
function safeSource(source, base) {
  try {
    const url = new URL(source), origin = new URL(base)
    return url.origin === origin.origin && !url.username && !url.password && !url.hash &&
      /^\/storage\/v1\/(object|render\/image)\//.test(url.pathname) &&
      ![...url.searchParams.keys()].some(key => /token|authorization/i.test(key))
  } catch { return false }
}
self.addEventListener('message', event => {
  const client = event.source?.id, data = event.data
  if (!client || !data || typeof data !== 'object') return
  if (data.type === 'NOVVIN_MEDIA_CLEAR') {
    tokens.delete(client)
    clearClientImages(client)
    for (const [key, value] of records) if (value.client === client) records.delete(key)
    event.ports[0]?.postMessage({ ok: true }); return
  }
  if (data.type !== 'NOVVIN_MEDIA_REGISTER' || !/^[0-9a-f-]{36}$/i.test(data.id || '') ||
      typeof data.token !== 'string' || data.token.length < 30 || data.token.length > 12000 || !safeSource(data.source, data.base)) return
  const previous = tokens.get(client)
  if (previous && previous.generation === data.generation && previous.base === data.base) previous.token = data.token
  else { clearClientImages(client); tokens.set(client, { token: data.token, generation: data.generation, base: data.base }) }
  const key = `${client}/${data.id}`, existing = records.get(key)
  if (!existing || existing.source !== data.source || existing.generation !== data.generation)
    records.set(key, { client, source: data.source, generation: data.generation, downloadName: typeof data.downloadName === 'string' ? data.downloadName.replace(/[\u0000-\u001f\u007f/\\]/g,'_').slice(0,180) : '' })
  for (const done of [...(pending.get(`${client}/${data.id}`) || [])]) done()
  event.ports[0]?.postMessage({ ok: true })
})
async function stream(event, id) {
  const key = `${event.clientId}/${id}`
  if (!records.has(key)) {
    await refresh(event.clientId, id)
  }
  const record = records.get(key), auth = tokens.get(event.clientId)
  if (!record || !auth || record.generation !== auth.generation || !safeSource(record.source, auth.base)) return new Response('', { status: 404 })
  const headers = new Headers({ Authorization: `Bearer ${auth.token}` })
  const range = event.request.headers.get('Range')
  if (range) {
    if (!/^bytes=(\d+-\d*|-\d+)$/.test(range)) return new Response('', { status: 416 })
    headers.set('Range', range)
  }
  const cached = imageCache.get(key)
  if (!range && !record.downloadName && cached) {
    if (cached.record === record && cached.auth === auth && cached.expires > Date.now()) {
      imageCache.delete(key); imageCache.set(key, cached)
      return new Response(cached.bytes.slice(), { headers: cached.headers })
    }
    removeImage(key)
  }
  try {
    const oldToken = auth.token
    let response = await fetch(record.source, { headers, signal: event.request.signal, credentials: 'omit', cache: 'no-store', redirect: 'error' })
    if (response.status === 401) {
      await refresh(event.clientId, id)
      if (tokens.get(event.clientId) !== auth || records.get(key) !== record) return new Response('', { status: 403 })
      if (auth.token !== oldToken) {
        await response.body?.cancel()
        headers.set('Authorization', `Bearer ${auth.token}`)
        response = await fetch(record.source, { headers, signal: event.request.signal, credentials: 'omit', cache: 'no-store', redirect: 'error' })
      }
    }
    // Identity changes during a fetch discard its response before it reaches the DOM.
    if (tokens.get(event.clientId) !== auth || records.get(key) !== record) return new Response('', { status: 403 })
    if (!response.ok) { await response.body?.cancel(); return new Response('', { status: response.status }) }
    const output = new Headers(response.headers)
    if (output.has('Content-Encoding')) { output.delete('Content-Encoding'); output.delete('Content-Length') }
    if (record.downloadName) {
      output.set('Content-Type', 'application/octet-stream')
      output.set('Content-Disposition', "attachment; filename*=UTF-8''" + encodeURIComponent(record.downloadName))
      output.set('X-Content-Type-Options', 'nosniff')
    }
    output.set('Cache-Control', 'private, no-store')
    output.delete('Set-Cookie')
    const result = new Response(response.body, { status: response.status, statusText: response.statusText, headers: output })
    const length = output.get('Content-Length')
    if (!range && !record.downloadName && response.status === 200 &&
        /^image\/(png|apng|jpeg|webp|gif|avif|bmp)(?:;|$)/i.test(output.get('Content-Type') || '') &&
        /^\d+$/.test(length || '') && Number(length) <= imageEntryLimit) {
      const remembering = rememberImage(result.clone(), key, record, auth)
      event.waitUntil?.(remembering)
    }
    return result
  } catch { return new Response('', { status: 503 }) }
}
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url), match = url.pathname.match(/^\/__novvin_media\/([0-9a-f-]{36})$/i)
  if (url.origin !== self.location.origin || !match || event.request.method !== 'GET') return
  event.respondWith(stream(event, match[1]))
})
