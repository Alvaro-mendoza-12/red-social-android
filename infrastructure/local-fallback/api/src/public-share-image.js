'use strict'
const sharp = require('sharp')
const crypto = require('node:crypto')
const jwt = require('jsonwebtoken')
const { canonicalStorageUrl } = require('./storage-url-canonicalization')
const { GUEST_AUDIENCE } = require('./guest-mode-routes')

function publicShareStorageInternalOrigin() {
  // Accelerated reads return a grant header; the internal edge serves the bytes.
  if (String(process.env.STORAGE_ACCEL_PREFIX || '').trim().replace(/\/+$/, '')) return 'http://edge:8080'
  const port = Number(process.env.PORT || 3001)
  return Number.isInteger(port) && port > 0 && port <= 65535 ? `http://127.0.0.1:${port}` : undefined
}

/** Read owned public raster references; an optional guest never inherits a member session. */
async function readPublicShareImage(rawUrl, publicBaseUrl, options = {}) {
  try {
    const base = new URL(publicBaseUrl), source = new URL(String(rawUrl || ''), base)
    if (source.username || source.password || !source.pathname.includes('/storage/v1/object/public/')) return null
    // Legacy storage hosts are locators only; never contact their origin.
    const url = new URL(canonicalStorageUrl(rawUrl, publicBaseUrl))
    if (url.protocol !== 'https:' || url.origin !== base.origin || url.username || url.password ||
        !url.pathname.startsWith('/storage/v1/object/public/') || [...url.searchParams.keys()].some(key => key !== 'v')) return null
    // These are server-selected derivative parameters, never caller-supplied URLs.
    // Video avatars have a supported image poster on the existing storage route.
    if (options.thumbnail === 'avatar') {
      url.searchParams.set('width', '384')
      url.searchParams.set('quality', '86')
      url.searchParams.set('poster', '1')
    }
    let destination = url
    if (options.localStorageOrigin != null) {
      const origin = String(options.localStorageOrigin)
      const local = origin.match(/^http:\/\/127\.0\.0\.1:([0-9]{1,5})\/?$/)
      const edge = origin === 'http://edge:8080' || origin === 'http://edge:8080/'
      if (!edge && (!local || Number(local[1]) < 1 || Number(local[1]) > 65535)) return null
      // The original owned HTTPS reference is already validated. Keep the
      // ordinary storage authorization/visibility gate on the same API process.
      destination = new URL(url.pathname + url.search, options.localStorageOrigin)
    }
    const secret = options.guestSecret ?? process.env.JWT_SECRET
    const issuer = options.guestIssuer ?? 'conectate-local-fallback'
    const headers = options.guestAccess === true && secret ? {
      authorization: `Bearer ${jwt.sign({ sub: `guest_${crypto.randomUUID()}`, role: 'guest' }, secret,
        { issuer, audience: GUEST_AUDIENCE, expiresIn: 60 })}`,
    } : undefined
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 2500)
    try {
      const response = await fetch(destination, { redirect: 'error', signal: controller.signal, headers, credentials: 'omit' })
      if (!response.ok || !/^image\/(jpeg|png|webp)(?:;|$)/i.test(response.headers.get('content-type') || '') ||
          Number(response.headers.get('content-length') || 0) > 2_000_000 || !response.body) {
        await response.body?.cancel().catch(() => {})
        return null
      }
      const reader = response.body.getReader(), parts = []
      let bytes = 0
      try {
        while (true) {
          const chunk = await reader.read()
          if (chunk.done) break
          bytes += chunk.value.byteLength
          if (bytes > 2_000_000) { controller.abort(); return null }
          parts.push(Buffer.from(chunk.value))
        }
      } finally { await reader.cancel().catch(() => {}) }
      return await sharp(Buffer.concat(parts), { limitInputPixels: 12_000_000 }).rotate()
        .resize(1200, 1200, { fit: 'inside', withoutEnlargement: true }).jpeg({ quality: 85 }).toBuffer()
    } finally { clearTimeout(timeout) }
  } catch { return null }
}

module.exports = { readPublicShareImage, publicShareStorageInternalOrigin }
