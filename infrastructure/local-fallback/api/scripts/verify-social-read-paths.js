const crypto = require('crypto')
const jwt = require('jsonwebtoken')
const { Client } = require('pg')

const serviceName = 'conectate-local-fallback'
const defaultBaseUrl = 'http://127.0.0.1:3001'
const defaultTimeoutMs = 15_000
const defaultMaxJsonMs = 3_000
const defaultMaxImageMs = 4_000
const defaultMaxVideoRangeMs = 6_000
const videoRangeBytes = 1024 * 1024

function positiveInteger(value, fallback) {
  const parsed = Number.parseInt(String(value || ''), 10)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}

function truthy(value, fallback = false) {
  const normalized = String(value ?? '').trim().toLowerCase()
  if (!normalized) return fallback
  return ['1', 'true', 'yes', 'on'].includes(normalized)
}

function cleanBaseUrl(value) {
  const normalized = String(value || defaultBaseUrl).trim().replace(/\/+$/, '')
  const url = new URL(normalized)
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('SMOKE_API_BASE_URL must use HTTP or HTTPS')
  return url.toString().replace(/\/+$/, '')
}

function collectHttpUrls(value, key = '', output = []) {
  if (typeof value === 'string' && /^https?:\/\//i.test(value.trim())) {
    output.push({ key: String(key || ''), url: value.trim() })
    return output
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => collectHttpUrls(item, `${key}[${index}]`, output))
    return output
  }
  if (value && typeof value === 'object') {
    Object.entries(value).forEach(([childKey, item]) => collectHttpUrls(item, childKey, output))
  }
  return output
}

function uniqueUrls(candidates) {
  const seen = new Set()
  return candidates.filter(({ url }) => {
    if (seen.has(url)) return false
    seen.add(url)
    return true
  })
}

function selectVideoUrl(value) {
  const candidates = uniqueUrls(collectHttpUrls(value))
  return candidates.find(({ key, url }) => (
    /(?:video|playback|stream).*(?:url|uri)|^(?:video_url|videoUrl)$/i.test(key) &&
    !/(?:thumbnail|cover|poster)/i.test(key) &&
    /\.(?:mp4|webm)(?:[?#]|$)/i.test(url)
  ))?.url || candidates.find(({ url }) => /\.(?:mp4|webm)(?:[?#]|$)/i.test(url))?.url || ''
}

function selectImageUrls(value, limit = 3) {
  const candidates = uniqueUrls(collectHttpUrls(value)).filter(({ key, url }) => (
    /(?:avatar|image|photo|thumbnail|cover|poster)/i.test(key) &&
    /\.(?:jpe?g|png|webp)(?:[?#]|$)/i.test(url)
  ))
  return candidates.slice(0, Math.max(1, limit)).map(({ url }) => url)
}

function responseItems(body) {
  if (Array.isArray(body)) return body
  for (const key of ['items', 'videos', 'posts', 'profiles', 'data']) {
    if (Array.isArray(body?.[key])) return body[key]
  }
  return []
}

function parseContentRange(value) {
  const match = /^bytes\s+(\d+)-(\d+)\/(\d+|\*)$/i.exec(String(value || '').trim())
  if (!match) return null
  return {
    start: Number(match[1]),
    end: Number(match[2]),
    total: match[3] === '*' ? null : Number(match[3]),
  }
}

function mediaRef(value) {
  try {
    const url = new URL(value)
    return crypto.createHash('sha256').update(url.pathname).digest('hex').slice(0, 16)
  } catch (_) {
    return 'invalid'
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function timedFetch(url, options, timeoutMs, label = 'request') {
  const started = Date.now()
  try {
    const response = await fetch(url, {
      ...options,
      signal: AbortSignal.timeout(timeoutMs),
    })
    return { response, startedAtMs: started, elapsedMs: Date.now() - started }
  } catch (error) {
    const reason = String(error?.cause?.code || error?.name || error?.message || 'UNKNOWN')
    throw new Error(`${label} fetch failed (${reason})`, { cause: error })
  }
}

async function readJsonCheck({ baseUrl, pathname, token, maxMs, timeoutMs, requireItems, name, requestBody }) {
  const { response, startedAtMs, elapsedMs: ttfbMs } = await timedFetch(`${baseUrl}${pathname}`, {
    method: requestBody === undefined ? 'GET' : 'POST',
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(requestBody === undefined ? {} : { 'content-type': 'application/json' }),
    },
    ...(requestBody === undefined ? {} : { body: JSON.stringify(requestBody) }),
  }, timeoutMs, name)
  const text = await response.text()
  const elapsedMs = Date.now() - startedAtMs
  let body
  try {
    body = text ? JSON.parse(text) : {}
  } catch (_) {
    throw new Error(`${name} returned non-JSON content (${response.status})`)
  }
  assert(response.ok, `${name} returned ${response.status}: ${String(body?.error || 'HTTP_ERROR')}`)
  assert(elapsedMs <= maxMs, `${name} exceeded ${maxMs} ms (${elapsedMs} ms)`)
  const items = responseItems(body)
  if (requireItems) assert(items.length > 0, `${name} returned no items`)
  return {
    body,
    evidence: {
      name,
      status: response.status,
      elapsedMs,
      ttfbMs,
      bytes: Buffer.byteLength(text),
      ...(requireItems ? { items: items.length } : {}),
    },
  }
}

async function verifyImage({ sourceUrl, timeoutMs, maxMs }) {
  const optimizedUrl = new URL(sourceUrl)
  optimizedUrl.searchParams.set('width', '384')
  optimizedUrl.searchParams.set('quality', '84')
  optimizedUrl.searchParams.set('format', 'webp')
  const ref = mediaRef(sourceUrl)
  const { response, startedAtMs, elapsedMs: ttfbMs } = await timedFetch(
    optimizedUrl,
    {},
    timeoutMs,
    `optimized image ${ref}`,
  )
  const bytes = Buffer.from(await response.arrayBuffer()).length
  const elapsedMs = Date.now() - startedAtMs
  const contentType = String(response.headers.get('content-type') || '').split(';')[0].toLowerCase()
  assert(response.ok, `optimized image ${ref} returned ${response.status}`)
  assert(contentType.startsWith('image/'), `optimized image ${ref} returned ${contentType || 'no content type'}`)
  assert(bytes > 0, `optimized image ${ref} returned an empty body`)
  assert(elapsedMs <= maxMs, `optimized image ${ref} exceeded ${maxMs} ms (${elapsedMs} ms)`)
  return { ref, status: response.status, contentType, bytes, elapsedMs, ttfbMs }
}

async function fetchVideoRange({ url, start, end, timeoutMs, maxMs }) {
  const { response, startedAtMs, elapsedMs: ttfbMs } = await timedFetch(url, {
    headers: { range: `bytes=${start}-${end}` },
  }, timeoutMs, `video range ${start}-${end}`)
  const body = Buffer.from(await response.arrayBuffer())
  const elapsedMs = Date.now() - startedAtMs
  const contentType = String(response.headers.get('content-type') || '').split(';')[0].toLowerCase()
  const contentRange = parseContentRange(response.headers.get('content-range'))
  assert(response.status === 206, `video range ${start}-${end} returned ${response.status}`)
  assert(contentType.startsWith('video/'), `video range returned ${contentType || 'no content type'}`)
  assert(String(response.headers.get('accept-ranges') || '').toLowerCase() === 'bytes', 'video response does not advertise byte ranges')
  assert(contentRange?.start === start, `video range started at ${contentRange?.start ?? 'unknown'}, expected ${start}`)
  assert(body.length === contentRange.end - contentRange.start + 1, 'video range body length does not match Content-Range')
  assert(elapsedMs <= maxMs, `video range ${start}-${end} exceeded ${maxMs} ms (${elapsedMs} ms)`)
  return {
    start: contentRange.start,
    end: contentRange.end,
    total: contentRange.total,
    bytes: body.length,
    elapsedMs,
    ttfbMs,
    status: response.status,
    contentType,
  }
}

async function issueDiagnosticToken() {
  const jwtSecret = String(process.env.JWT_SECRET || '').trim()
  assert(jwtSecret, 'JWT_SECRET is required inside the API runtime')
  const client = new Client({ connectionString: process.env.DATABASE_URL })
  await client.connect()
  try {
    const requestedUserId = String(process.env.SMOKE_USER_ID || '').trim()
    const result = requestedUserId
      ? await client.query('SELECT id, email FROM fallback_users WHERE id = $1::uuid LIMIT 1', [requestedUserId])
      : await client.query(
        `SELECT id, email
         FROM fallback_users
         ORDER BY last_login_at DESC NULLS LAST, created_at ASC
         LIMIT 1`,
      )
    const user = result.rows[0]
    assert(user, requestedUserId ? 'SMOKE_USER_ID was not found' : 'no fallback user exists for read-path verification')
    // Read-path checks use the same revocable session policy as every app client.
    // The random refresh hash is never returned or logged; the session is removed
    // after the check and also expires automatically if the process is killed.
    const refreshHash = crypto.createHash('sha256').update(crypto.randomBytes(48)).digest('hex')
    const session = await client.query(
      `INSERT INTO fallback_auth_sessions(user_id, refresh_token_hash, client, user_agent, expires_at)
       VALUES ($1, $2, 'read-path-smoke', 'internal-read-path-smoke', NOW() + INTERVAL '5 minutes')
       RETURNING id`,
      [user.id, refreshHash],
    )
    return jwt.sign(
      { email: user.email, provider: 'local-fallback', client: 'read-path-smoke', sid: session.rows[0].id },
      jwtSecret,
      {
        subject: user.id,
        issuer: serviceName,
        audience: 'conectate-clients',
        expiresIn: '5m',
      },
    )
  } finally {
    await client.end()
  }
}

async function revokeDiagnosticToken(token) {
  const claims = jwt.decode(token)
  if (claims?.client !== 'read-path-smoke' || !claims?.sid || !claims?.sub) return
  const client = new Client({ connectionString: process.env.DATABASE_URL })
  await client.connect()
  try {
    await client.query(
      `DELETE FROM fallback_auth_sessions
       WHERE id=$1::uuid AND user_id=$2::uuid AND client='read-path-smoke'`,
      [claims.sid, claims.sub],
    )
  } finally {
    await client.end()
  }
}

async function withReadPathToken(work) {
  const suppliedToken = String(process.env.SMOKE_ACCESS_TOKEN || '').trim()
  const token = suppliedToken || await issueDiagnosticToken()
  try {
    return await work(token)
  } finally {
    if (!suppliedToken) await revokeDiagnosticToken(token)
  }
}

async function main() {
  const baseUrl = cleanBaseUrl(process.env.SMOKE_API_BASE_URL)
  const timeoutMs = positiveInteger(process.env.SMOKE_TIMEOUT_MS, defaultTimeoutMs)
  const maxJsonMs = positiveInteger(process.env.SMOKE_MAX_JSON_MS, defaultMaxJsonMs)
  const maxImageMs = positiveInteger(process.env.SMOKE_MAX_IMAGE_MS, defaultMaxImageMs)
  const maxVideoRangeMs = positiveInteger(process.env.SMOKE_MAX_VIDEO_RANGE_MS, defaultMaxVideoRangeMs)
  const requireContent = truthy(process.env.SMOKE_REQUIRE_CONTENT, true)
  return withReadPathToken(async (token) => {
  const checks = []

  for (const [name, pathname] of [['health', '/health'], ['ready', '/ready']]) {
    const result = await readJsonCheck({
      baseUrl,
      pathname,
      token: '',
      maxMs: maxJsonMs,
      timeoutMs,
      requireItems: false,
      name,
    })
    assert(result.body?.ok === true, `${name} did not report ok=true`)
    checks.push(result.evidence)
  }

  const feed = await readJsonCheck({
    baseUrl,
    pathname: '/v1/data/feed?limit=50',
    token,
    maxMs: maxJsonMs,
    timeoutMs,
    requireItems: requireContent,
    name: 'social-feed',
  })
  checks.push(feed.evidence)

  const videos = await readJsonCheck({
    baseUrl,
    pathname: '/v1/data/videos?limit=50',
    token,
    maxMs: maxJsonMs,
    timeoutMs,
    requireItems: requireContent,
    name: 'video-feed',
  })
  checks.push(videos.evidence)

  for (const [name, pathname] of [
    ['video-watch-later', '/v1/data/videos/watch-later/list'],
    ['video-collections', '/v1/data/videos/collections'],
  ]) {
    const result = await readJsonCheck({
      baseUrl, pathname, token, maxMs: maxJsonMs, timeoutMs,
      requireItems: false, name,
    })
    assert(Array.isArray(result.body?.items), `${name} did not return an item list`)
    checks.push({ ...result.evidence, items: result.body.items.length })
  }

  const profiles = await readJsonCheck({
    baseUrl,
    pathname: '/v1/data/profiles/search?q=a&limit=20',
    token,
    maxMs: maxJsonMs,
    timeoutMs,
    requireItems: requireContent,
    name: 'profile-search',
  })
  checks.push(profiles.evidence)

  const imageUrls = selectImageUrls([profiles.body, feed.body], 3)
  if (requireContent) assert(imageUrls.length > 0, 'profiles/feed returned no image URL to verify')
  const images = []
  for (const sourceUrl of imageUrls) {
    images.push(await verifyImage({ sourceUrl, timeoutMs, maxMs: maxImageMs }))
  }

  const videoUrl = selectVideoUrl(videos.body)
  if (requireContent) assert(videoUrl, 'video feed returned no playable MP4/WebM URL')
  const videoRanges = []
  if (videoUrl) {
    const first = await fetchVideoRange({
      url: videoUrl,
      start: 0,
      end: videoRangeBytes - 1,
      timeoutMs,
      maxMs: maxVideoRangeMs,
    })
    videoRanges.push(first)
    if (first.total && first.total > videoRangeBytes) {
      videoRanges.push(await fetchVideoRange({
        url: videoUrl,
        start: videoRangeBytes,
        end: Math.min((2 * videoRangeBytes) - 1, first.total - 1),
        timeoutMs,
        maxMs: maxVideoRangeMs,
      }))
    }
  }

  console.log(JSON.stringify({
    ok: true,
    target: new URL(baseUrl).host,
    requireContent,
    thresholdsMs: { json: maxJsonMs, image: maxImageMs, videoRange: maxVideoRangeMs },
    checks,
    images,
    video: videoUrl ? { ref: mediaRef(videoUrl), ranges: videoRanges } : null,
  }, null, 2))
  })
}

// SMOKE_FORCE_RUN lets the host stream this verifier through stdin without
// copying files into the API container. It creates and removes one short-lived
// diagnostic session; content reads remain non-mutating.
if (require.main === module || truthy(process.env.SMOKE_FORCE_RUN)) {
  main().catch((error) => {
    console.error(JSON.stringify({ ok: false, error: error.message }, null, 2))
    process.exitCode = 1
  })
}

module.exports = {
  assert,
  cleanBaseUrl,
  collectHttpUrls,
  fetchVideoRange,
  issueDiagnosticToken,
  revokeDiagnosticToken,
  withReadPathToken,
  mediaRef,
  parseContentRange,
  positiveInteger,
  readJsonCheck,
  responseItems,
  selectImageUrls,
  selectVideoUrl,
  timedFetch,
  truthy,
  verifyImage,
}
