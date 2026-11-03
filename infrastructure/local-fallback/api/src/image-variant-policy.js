'use strict'

// Canonical profiles retain compatibility with legacy width/quality query inputs.
const WIDTHS = Object.freeze([64, 160, 320, 384, 480, 640, 720, 960, 1080, 1280, 1600, 1920])
const QUALITIES = Object.freeze([60, 78, 86])

function invalid() { return Object.assign(new Error('INVALID_IMAGE_VARIANT'), { status: 400 }) }
function normalizeImageVariant(query, contentType, allowIdentityVariants = false) {
  const rawWidth = String(query?.width || '').trim()
  const poster = allowIdentityVariants && String(query?.poster) === '1' && String(contentType).startsWith('video/')
  const animated = allowIdentityVariants && String(query?.animated) === '1' && ['image/jpeg', 'image/png', 'image/webp', 'image/gif'].includes(contentType)
  if (!rawWidth || (!poster && !animated && !['image/jpeg', 'image/png', 'image/webp'].includes(contentType))) return null
  if (!/^\d{2,4}$/.test(rawWidth)) throw invalid()
  const width = Number(rawWidth)
  const quality = Number(String(query?.quality || '78').trim())
  const format = String(query?.format || 'webp').trim().toLowerCase()
  if (width < 64 || width > 1920 || !Number.isInteger(quality) || quality < 40 || quality > 90 || format !== 'webp') throw invalid()
  const canonicalWidth = WIDTHS.find(value => value >= width)
  const canonicalQuality = QUALITIES.reduce((best, value) => Math.abs(value - quality) < Math.abs(best - quality) ? value : best)
  return { width: canonicalWidth, quality: canonicalQuality, format: 'webp', ...(poster ? { poster: true } : {}), ...(animated ? { animated: true } : {}) }
}

module.exports = { WIDTHS, QUALITIES, normalizeImageVariant }
