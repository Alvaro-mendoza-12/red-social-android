'use strict'

const fs = require('node:fs')
const net = require('node:net')
const crypto = require('node:crypto')
const { once } = require('node:events')

const LIMIT = 500 * 1024 * 1024
const error = (code, status = 503) => Object.assign(new Error(code), { status, code })

function parseDaemonVersion(value, now, maxAgeMs) {
  if (typeof value !== 'string' || /[\r\n\0]/.test(value)) throw error('STORAGE_SCAN_VERSION_UNVERIFIED')
  const match = /^ClamAV ([0-9.]+)\/(\d+)\/([A-Za-z]{3} [A-Za-z]{3} +\d{1,2} \d{2}:\d{2}:\d{2} \d{4})(?:\|? COMMANDS: (.+))?$/.exec(value)
  if (!match) throw error('STORAGE_SCAN_VERSION_UNVERIFIED')
  // The dedicated daemon uses TZ=UTC. A VERSION timestamp has no zone of its own.
  const updatedAt = Date.parse(match[3] + ' UTC')
  if (!Number.isFinite(updatedAt) || updatedAt > now + 60_000 || now - updatedAt > maxAgeMs) throw error('STORAGE_SCAN_SIGNATURES_STALE')
  const commands = new Set((match[4] || '').split(/[ ,]+/))
  if (!commands.has('INSTREAM') || !commands.has('VERSIONCOMMANDS')) throw error('STORAGE_SCAN_COMMANDS_UNVERIFIED')
  return { engine: `ClamAV/${match[1]}`, signatureVersion: match[2], signatureUpdatedAt: new Date(updatedAt).toISOString() }
}

function createStorageClamd({ host = process.env.STORAGE_CLAMD_HOST || 'storage-antivirus', port = Number(process.env.STORAGE_CLAMD_PORT || 3310),
  maxBytes = LIMIT, timeoutMs = 180_000, maxSignatureAgeMs = 24 * 60 * 60 * 1000,
  attestationFile = process.env.STORAGE_CLAMD_POLICY_FILE, policySha256 = process.env.STORAGE_CLAMD_POLICY_SHA256,
  daemonConfigFile = process.env.STORAGE_CLAMD_CONFIG_FILE,
  connect = net.createConnection, now = Date.now } = {}) {
  if (!host || !Number.isInteger(port) || port < 1 || port > 65535 || !Number.isSafeInteger(maxBytes) || maxBytes < 1 || maxBytes > LIMIT ||
      !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 180_000 || !Number.isSafeInteger(maxSignatureAgeMs) || maxSignatureAgeMs < 1) throw error('STORAGE_SCAN_CONFIGURATION_INVALID')

  function socketCommand(command) {
    return new Promise((resolve, reject) => {
      let socket, done = false, response = Buffer.alloc(0)
      const timer = setTimeout(() => finish(error('STORAGE_SCAN_TIMEOUT')), Math.min(timeoutMs, 3500))
      function finish(cause, value) { if (done) return; done = true; clearTimeout(timer); socket?.destroy(); cause ? reject(cause) : resolve(value) }
      try {
        socket = connect({ host, port })
        socket.on('error', () => finish(error('STORAGE_SCAN_UNAVAILABLE')))
        socket.on('connect', () => socket.write(Buffer.from(`z${command}\0`)))
        socket.on('data', chunk => {
          if (response.length + chunk.length > 4096) return finish(error('STORAGE_SCAN_REPLY_INVALID'))
          response = Buffer.concat([response, chunk])
          const end = response.indexOf(0)
          if (end >= 0) {
            if (end !== response.length - 1) return finish(error('STORAGE_SCAN_REPLY_INVALID'))
            finish(null, response.subarray(0, end).toString('utf8'))
          }
        })
        socket.on('end', () => { if (!done) finish(error('STORAGE_SCAN_REPLY_INCOMPLETE')) })
        socket.on('close', () => { if (!done) finish(error('STORAGE_SCAN_REPLY_INCOMPLETE')) })
      } catch { finish(error('STORAGE_SCAN_UNAVAILABLE')) }
    })
  }

  async function readiness() {
    if (!attestationFile || !/^[a-f0-9]{64}$/.test(policySha256 || '')) throw error('STORAGE_SCAN_POLICY_MISSING')
    let policy, daemonConfig
    try {
      const bytes = await fs.promises.readFile(attestationFile)
      if (bytes.length > 16_384 || crypto.createHash('sha256').update(bytes).digest('hex') !== policySha256) throw Error('policy')
      policy = JSON.parse(bytes)
      const configBytes = await fs.promises.readFile(daemonConfigFile)
      if (configBytes.length > 16_384 || crypto.createHash('sha256').update(configBytes).digest('hex') !== policy.configSha256) throw Error('config')
      daemonConfig = configBytes.toString('utf8')
    } catch { throw error('STORAGE_SCAN_POLICY_UNVERIFIED') }
    // This artifact is mounted read-only from a deploy-controlled daemon config.
    // It is not a claim submitted by an uploader, nor discoverable using PING alone.
    if (policy.schema !== 1 || policy.timezone !== 'UTC' || policy.maxFileBytes < maxBytes || policy.streamMaxBytes < maxBytes ||
        policy.maxScanBytes < maxBytes * 2 || policy.alertExceedsMax !== true || policy.alertEncrypted !== true ||
        policy.maxThreads !== 2 || policy.maxQueue !== 4 || policy.scanArchive !== true || policy.scanPE !== true || policy.scanOLE2 !== true ||
        !/^[a-f0-9]{64}$/.test(policy.configSha256 || '')) throw error('STORAGE_SCAN_POLICY_INSUFFICIENT')
    const required = { StreamMaxLength: '500M', MaxFileSize: '500M', MaxScanSize: '1000M', MaxThreads: '2', MaxQueue: '4',
      AlertExceedsMax: 'yes', AlertEncrypted: 'yes', ScanArchive: 'yes', ScanPE: 'yes', ScanOLE2: 'yes' }
    for (const [key, value] of Object.entries(required)) {
      const lines = daemonConfig.split(/\r?\n/).map(line => line.trim()).filter(line => !line.startsWith('#')).filter(line => line.startsWith(key + ' '))
      if (lines.length !== 1 || lines[0] !== key + ' ' + value) throw error('STORAGE_SCAN_CONFIG_INSUFFICIENT')
    }
    const version = parseDaemonVersion(await socketCommand('VERSIONCOMMANDS'), now(), maxSignatureAgeMs)
    return { ...version, policySha256 }
  }

  async function scan(filePath, { expectedSize } = {}) {
    const version = await readiness()
    const stat = await fs.promises.lstat(filePath)
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size < 1 || stat.size > maxBytes || stat.size !== expectedSize) throw error('STORAGE_SCAN_FILE_LIMIT', 413)
    return new Promise((resolve, reject) => {
      let socket, input, done = false, finishedWriting = false, sentBytes = 0, reply = Buffer.alloc(0)
      const cancel = new AbortController()
      const digest = crypto.createHash('sha256')
      const timer = setTimeout(() => finish(error('STORAGE_SCAN_TIMEOUT')), timeoutMs)
      function finish(cause) {
        if (done) return
        done = true; clearTimeout(timer); cancel.abort(); input?.destroy(); socket?.destroy()
        cause ? reject(cause) : resolve({ verdict: 'clean', bytesScanned: sentBytes, sha256: digest.digest('hex'), ...version, scannedAt: new Date(now()).toISOString() })
      }
      async function write(bytes) {
        if (done) throw error('STORAGE_SCAN_CANCELLED')
        if (!socket.write(bytes)) await once(socket, 'drain', { signal: cancel.signal })
      }
      try {
        socket = connect({ host, port })
        socket.on('error', () => finish(error('STORAGE_SCAN_UNAVAILABLE')))
        socket.on('end', () => { if (!done) finish(error('STORAGE_SCAN_REPLY_INCOMPLETE')) })
        socket.on('close', () => { if (!done) finish(error('STORAGE_SCAN_REPLY_INCOMPLETE')) })
        socket.on('data', chunk => {
          if (done) return
          if (reply.length + chunk.length > 4096) return finish(error('STORAGE_SCAN_REPLY_INVALID'))
          reply = Buffer.concat([reply, chunk])
          const delimiter = reply.indexOf(0)
          if (delimiter < 0) return
          const result = reply.subarray(0, delimiter).toString('utf8')
          if (!finishedWriting || delimiter !== reply.length - 1 || sentBytes !== stat.size) return finish(error('STORAGE_SCAN_REPLY_INVALID'))
          if (result === 'stream: OK') return finish()
          if (/^stream: .+ FOUND$/.test(result)) return finish(error('STORAGE_FILE_REJECTED', 415))
          finish(error('STORAGE_SCAN_NOT_COMPLETE'))
        })
        socket.on('connect', async () => {
          try {
            await write(Buffer.from('zINSTREAM\0'))
            input = fs.createReadStream(filePath, { highWaterMark: 64 * 1024 })
            for await (const chunk of input) {
              sentBytes += chunk.length
              if (sentBytes > maxBytes || sentBytes > stat.size) throw error('STORAGE_SCAN_FILE_LIMIT', 413)
              digest.update(chunk)
              const header = Buffer.alloc(4); header.writeUInt32BE(chunk.length)
              await write(Buffer.concat([header, chunk]))
            }
            if (sentBytes !== stat.size) throw error('STORAGE_SCAN_FILE_CHANGED')
            // Set only after every file frame drained; a final daemon result may arrive immediately.
            finishedWriting = true
            await write(Buffer.alloc(4))
          } catch (cause) { finish(cause?.code?.startsWith('STORAGE_') ? cause : error('STORAGE_SCAN_UNAVAILABLE')) }
        })
      } catch { finish(error('STORAGE_SCAN_UNAVAILABLE')) }
    })
  }
  return { readiness, scan, maxBytes, timeoutMs }
}

module.exports = { createStorageClamd, parseDaemonVersion, STORAGE_SCAN_MAX_BYTES: LIMIT }
