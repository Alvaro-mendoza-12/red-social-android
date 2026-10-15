'use strict'

function scanEnforcementEnabled() {
  const mode = String(process.env.STORAGE_SCAN_ENFORCE || '').trim().toLowerCase()
  if (!['', 'false', 'true'].includes(mode)) throw new Error('STORAGE_SCAN_MODE_INVALID')
  return mode === 'true'
}
function trustedStorageUrl(raw) {
  try {
    const url = new URL(String(raw || ''))
    if (url.username || url.password || url.hash || (!['https:'].includes(url.protocol) && process.env.NODE_ENV !== 'test')) return false
    const configured = String(process.env.STORAGE_MEDIA_ORIGINS || process.env.PUBLIC_BASE_URL || '').split(',').map(value => value.trim()).filter(Boolean)
    const origins = new Set(configured.map(value => { const parsed = new URL(value); if (parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) throw Error('origin'); return parsed.origin }))
    if (!origins.has(url.origin) || !url.pathname.startsWith('/storage/v1/object/public/')) return false
    const parts = url.pathname.slice('/storage/v1/object/public/'.length).split('/').map(decodeURIComponent)
    return parts.length >= 2 && parts.every(part => part && part !== '.' && part !== '..' && !/[\\/\u0000-\u001f]/.test(part))
  } catch { return false }
}
function readyStorageSql() {
  return scanEnforcementEnabled() ? "AND scan_state = 'clean' AND object_revision IS NOT NULL AND scan_sha256 ~ '^[a-f0-9]{64}$' AND scan_bytes = size_bytes AND scan_at IS NOT NULL AND scan_engine IS NOT NULL AND scan_signature_version IS NOT NULL AND scan_policy_sha256 ~ '^[a-f0-9]{64}$'" : ''
}
module.exports = { scanEnforcementEnabled, trustedStorageUrl, readyStorageSql }
