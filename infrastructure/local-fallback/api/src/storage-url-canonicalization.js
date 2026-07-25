const PUBLIC_STORAGE_MARKER = '/storage/v1/object/public/'

function normalizeBaseUrl(value) {
  return String(value || '').trim().replace(/\/+$/, '')
}

function canonicalStorageUrl(value, rawBaseUrl) {
  if (typeof value !== 'string') return value
  const raw = value.trim()
  const baseUrl = normalizeBaseUrl(rawBaseUrl)
  if (!raw || !baseUrl) return value
  const markerIndex = raw.toLowerCase().indexOf(PUBLIC_STORAGE_MARKER)
  if (markerIndex < 0) return value
  const prefix = raw.slice(0, markerIndex)
  if (prefix && !/^https?:\/\//i.test(prefix)) return value
  return `${baseUrl}${raw.slice(markerIndex)}`
}

function canonicalizeStorageUrls(value, baseUrl, seen = new WeakSet()) {
  if (typeof value === 'string') return canonicalStorageUrl(value, baseUrl)
  if (value == null || typeof value !== 'object') return value
  if (seen.has(value)) return value
  if (Array.isArray(value)) {
    seen.add(value)
    return value.map((item) => canonicalizeStorageUrls(item, baseUrl, seen))
  }
  const prototype = Object.getPrototypeOf(value)
  if (prototype !== Object.prototype && prototype !== null) return value
  seen.add(value)
  return Object.fromEntries(
    Object.entries(value).map(([key, item]) => [key, canonicalizeStorageUrls(item, baseUrl, seen)]),
  )
}

function requestPublicBaseUrl(req) {
  const configured = normalizeBaseUrl(process.env.PUBLIC_BASE_URL || process.env.LOCAL_PUBLIC_BASE_URL)
  if (configured) return configured
  const protocol = String(req.headers?.['x-forwarded-proto'] || req.protocol || 'http').split(',')[0].trim()
  const host = String(req.headers?.['x-forwarded-host'] || req.get?.('host') || '').split(',')[0].trim()
  return host ? `${protocol}://${host}` : ''
}

function canonicalStorageResponseMiddleware(req, res, next) {
  const originalJson = res.json.bind(res)
  res.json = (body) => originalJson(canonicalizeStorageUrls(body, requestPublicBaseUrl(req)))
  next()
}

module.exports = {
  PUBLIC_STORAGE_MARKER,
  canonicalStorageResponseMiddleware,
  canonicalStorageUrl,
  canonicalizeStorageUrls,
  requestPublicBaseUrl,
}
