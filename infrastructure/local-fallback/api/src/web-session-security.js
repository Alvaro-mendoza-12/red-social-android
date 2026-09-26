const WEB_SESSION_MARKER_HEADER = 'x-novvin-web-session'
const WEB_SESSION_MARKER_VALUE = 'web-v1'
const PRODUCTION_COOKIE_NAME = '__Secure-novvin-web-session'
const DEVELOPMENT_COOKIE_NAME = 'novvin-web-session'

function parseCookieHeader(value) {
  const cookies = new Map()
  for (const part of String(value || '').split(';')) {
    const separator = part.indexOf('=')
    if (separator <= 0) continue
    const name = part.slice(0, separator).trim()
    const rawValue = part.slice(separator + 1).trim()
    if (!name || cookies.has(name)) continue
    try {
      cookies.set(name, decodeURIComponent(rawValue))
    } catch {
      cookies.set(name, rawValue)
    }
  }
  return cookies
}

function webSessionCookieName({ production = false } = {}) {
  return production ? PRODUCTION_COOKIE_NAME : DEVELOPMENT_COOKIE_NAME
}

function readWebSessionCookie(req, options = {}) {
  return parseCookieHeader(req?.headers?.cookie).get(webSessionCookieName(options)) || ''
}

function serializeWebSessionCookie(value, {
  production = false,
  maxAgeSeconds = 0,
} = {}) {
  const encodedValue = encodeURIComponent(String(value || ''))
  const attributes = [
    `${webSessionCookieName({ production })}=${encodedValue}`,
    'Path=/v1/auth/web-session',
    'HttpOnly',
    `Max-Age=${Math.max(0, Math.floor(Number(maxAgeSeconds) || 0))}`,
  ]
  if (production) {
    attributes.push('Secure', 'SameSite=None', 'Partitioned')
  } else {
    attributes.push('SameSite=Lax')
  }
  return attributes.join('; ')
}

function normalizedOrigin(value) {
  try {
    const parsed = new URL(String(value || '').trim())
    if (!['http:', 'https:'].includes(parsed.protocol)) return ''
    return parsed.origin
  } catch {
    return ''
  }
}

function isTrustedWebSessionRequest(req, {
  allowedOrigins,
  production = false,
} = {}) {
  const marker = String(req?.headers?.[WEB_SESSION_MARKER_HEADER] || '').trim()
  if (marker !== WEB_SESSION_MARKER_VALUE) return false

  const origin = normalizedOrigin(req?.headers?.origin)
  if (!origin) return !production
  if (allowedOrigins === '*') return !production
  return Array.isArray(allowedOrigins) && allowedOrigins.includes(origin)
}

module.exports = {
  DEVELOPMENT_COOKIE_NAME,
  PRODUCTION_COOKIE_NAME,
  WEB_SESSION_MARKER_HEADER,
  WEB_SESSION_MARKER_VALUE,
  isTrustedWebSessionRequest,
  parseCookieHeader,
  readWebSessionCookie,
  serializeWebSessionCookie,
  webSessionCookieName,
}
