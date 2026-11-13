'use strict'
const sharp = require('sharp')
const { canonicalStorageUrl } = require('./storage-url-canonicalization')

/** Only fetch anonymous public raster objects on our own configured storage origin. */
async function readPublicShareImage(rawUrl, publicBaseUrl) {
  try {
    const base = new URL(publicBaseUrl), source = new URL(String(rawUrl || ''), base)
    if (source.username || source.password || !source.pathname.includes('/storage/v1/object/public/')) return null
    // Legacy storage hosts are locators only; never contact their origin.
    const url = new URL(canonicalStorageUrl(rawUrl, publicBaseUrl))
    if (url.protocol !== 'https:' || url.origin !== base.origin || url.username || url.password ||
        !url.pathname.startsWith('/storage/v1/object/public/') || [...url.searchParams.keys()].some(key => key !== 'v')) return null
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 2500)
    try {
      const response = await fetch(url, { redirect: 'error', signal: controller.signal })
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

module.exports = { readPublicShareImage }
