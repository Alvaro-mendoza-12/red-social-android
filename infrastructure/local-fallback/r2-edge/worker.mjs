const probe = 'x-novvin-r2-probe'
const prefix = 'x-novvin-r2-'

function originalHeaders(request) {
  const headers = new Headers(request.headers)
  for (const key of [...headers.keys()]) if (key.startsWith(prefix)) headers.delete(key)
  return headers
}

function fromOrigin(request, headers, fetchOrigin) {
  // Pass credentials only to the configured route origin, never follow a redirect.
  return fetchOrigin(new Request(request, { headers, redirect: 'manual' }), { cache: 'no-store' })
}

function parseRange(value, size) {
  if (value === null) return null
  const match = /^bytes=(\d*)-(\d*)$/.exec(value)
  if (!match || (!match[1] && !match[2])) return false
  const first = match[1] ? Number(match[1]) : null
  const last = match[2] ? Number(match[2]) : null
  if ((first !== null && !Number.isSafeInteger(first)) || (last !== null && !Number.isSafeInteger(last))) return false
  if (first === null) return last > 0 ? { offset: Math.max(0, size - last), length: Math.min(size, last) } : false
  const end = last === null ? size - 1 : Math.min(last, size - 1)
  return first <= end ? { offset: first, length: end - first + 1 } : false
}

function stripInternal(headers) {
  const cleaned = new Headers(headers)
  for (const key of [...cleaned.keys()]) if (key.startsWith(prefix)) cleaned.delete(key)
  return cleaned
}

async function cloudBody(response, request, media) {
  const key = response.headers.get(`${prefix}key`)
  const sha = response.headers.get(`${prefix}sha256`)
  const sizeText = response.headers.get(`${prefix}size`)
  const size = Number(sizeText)
  const type = response.headers.get(`${prefix}type`)
  if (!/^novvin\/v1\/[a-f0-9-]{36}\/[a-f0-9]{64}$/.test(key || '') || !/^[a-f0-9]{64}$/.test(sha || '') ||
      !/^[1-9]\d*$/.test(sizeText || '') || !Number.isSafeInteger(size) || !type) return null
  const range = parseRange(request.headers.get('range'), size)
  if (range === false) return null
  const object = await media.get(key, range ? { range } : undefined)
  const matchesRange = !range || (object?.range?.offset === range.offset && object.range.length === range.length)
  if (!object?.body || object.size !== size || object.customMetadata?.sha256 !== sha || !matchesRange) {
    await object?.body?.cancel().catch(() => {})
    return null
  }
  const headers = stripInternal(response.headers)
  headers.set('Cache-Control', 'private, no-store')
  headers.set('Content-Type', type)
  const length = range?.length ?? size
  headers.set('Content-Length', String(length))
  if (range) headers.set('Content-Range', `bytes ${range.offset}-${range.offset + range.length - 1}/${size}`)
  let body = object.body
  if (typeof FixedLengthStream === 'function') {
    const fixed = new FixedLengthStream(length)
    body.pipeTo(fixed.writable).catch(() => {})
    body = fixed.readable
  }
  return new Response(body, { status: range ? 206 : 200, headers })
}

export async function deliver(request, env, fetchOrigin = fetch) {
  const url = new URL(request.url)
  const headers = originalHeaders(request)
  const fallback = () => fromOrigin(request, headers, fetchOrigin)
  if (!url.pathname.startsWith('/storage/v1/object/') || request.method !== 'GET' ||
      url.searchParams.has('lease') || !env.MEDIA || !/^[a-f0-9]{64}$/.test(env.R2_EDGE_SECRET || '')) return fallback()
  const probeHeaders = new Headers(headers)
  probeHeaders.set(probe, env.R2_EDGE_SECRET)
  const response = await fromOrigin(request, probeHeaders, fetchOrigin)
  if (!response.headers.has(`${prefix}key`)) return response
  if (response.status === 204) {
    try {
      const accelerated = await cloudBody(response, request, env.MEDIA)
      if (accelerated) return accelerated
    } catch {
      // An unavailable replica is a delivery miss. Retry through the original route.
    }
  }
  await response.body?.cancel().catch(() => {})
  return fallback()
}

export default { fetch: (request, env) => deliver(request, env) }
