'use strict'

const { timingSafeEqual } = require('node:crypto')
const { identity } = require('./storage-r2-mirror')

const PROBE = 'x-novvin-r2-probe'

function edgeConfiguration(env = process.env) {
  const secret = env.R2_EDGE_SECRET || ''
  const target = env.R2_EDGE_TARGET || ''
  if (!secret && !target) return null
  if (!/^[a-f0-9]{64}$/.test(secret) ||
      !/^https:\/\/[a-f0-9]{32}(?:\.(?:eu|us|fedramp))?\.r2\.cloudflarestorage\.com\/[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(target)) {
    throw new Error('R2_EDGE_CONFIGURATION_INVALID')
  }
  return { secret: Buffer.from(secret), target }
}

function createR2EdgeDelivery(config, onFailure = () => {}) {
  if (!config) return null
  return async ({ pool, req, res, row, admission, size, requestedVariant }) => {
    if (admission?.allowed !== true || admission.viewOnce || requestedVariant || req.query?.lease ||
        req.method !== 'GET' || Object.keys(req.headers).some(key => key.startsWith('if-'))) return false
    const provided = req.headers[PROBE]
    if (typeof provided !== 'string' || !/^[a-f0-9]{64}$/.test(provided) ||
        !timingSafeEqual(Buffer.from(provided), config.secret)) return false
    let replica
    try {
      const object = identity(row)
      if (object.bytes !== size) return false
      const result = await pool.query({
        text: `SELECT 1 FROM fallback_storage_r2_mirrors
          WHERE target=$1 AND remote_key=$2 AND state='ready' AND verified_at IS NOT NULL`,
        values: [config.target, object.key], query_timeout: 1000,
      })
      if (!result.rows.length) return false
      replica = object.key
    } catch (error) {
      onFailure(error)
      return false
    }
    res.setHeader('Cache-Control', 'private, no-store')
    res.setHeader('X-Novvin-R2-Key', replica)
    res.setHeader('X-Novvin-R2-SHA256', row.scan_sha256)
    res.setHeader('X-Novvin-R2-Size', String(size))
    // 204 strips representation headers in Express. Keep the exact authorized
    // type in a private header for the Worker to restore.
    res.setHeader('X-Novvin-R2-Type', res.getHeader('Content-Type'))
    res.status(204).end()
    return true
  }
}

module.exports = { createR2EdgeDelivery, edgeConfiguration }
