const { cleanStorageRow } = require('./storage-scan-coordinator')
'use strict'

const { clippedMusicLyrics } = require('./moment-music-lyrics')
const crypto = require('crypto')
const dns = require('dns').promises
const fs = require('fs')
const https = require('https')
const os = require('os')
const path = require('path')
const ipaddr = require('ipaddr.js')
const { spawn } = require('child_process')
const { Readable, Transform } = require('stream')
const { pipeline } = require('stream/promises')
const {
  parseStorageObjectUrl,
  storedObjectFilePath,
  upsertStoredObject,
} = require('./storage-routes')

const MAX_SOURCE_BYTES = 80 * 1024 * 1024
const DOWNLOAD_TIMEOUT_MS = 25_000
const TRANSCODE_TIMEOUT_MS = 45_000
const MAX_SOURCE_REDIRECTS = 4

function rendererError(code, statusCode = 503, cause = null) {
  const error = new Error(code, cause ? { cause } : undefined)
  error.statusCode = statusCode
  return error
}

function isPublicHttpsUrl(value) {
  try {
    const url = new URL(String(value || '').trim())
    if (url.protocol !== 'https:' || url.username || url.password) return false
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '')
    if (!host || host === 'localhost' || host.endsWith('.local') || host.endsWith('.internal')) return false
    if (ipaddr.isValid(host) && !isPublicIpAddress(host)) return false
    return true
  } catch (_) {
    return false
  }
}

function isPublicIpAddress(value) {
  try {
    let address = ipaddr.parse(String(value || '').trim())
    if (address.kind() === 'ipv6' && address.isIPv4MappedAddress()) {
      address = address.toIPv4Address()
    }
    return address.range() === 'unicast'
  } catch (_) {
    return false
  }
}

async function resolvePublicAddresses(hostname) {
  const host = String(hostname || '').trim().toLowerCase()
  if (!host) throw rendererError('MOMENT_MUSIC_SOURCE_DNS_INVALID', 400)
  if (ipaddr.isValid(host)) {
    if (!isPublicIpAddress(host)) throw rendererError('MOMENT_MUSIC_SOURCE_DNS_PRIVATE', 400)
    const parsed = ipaddr.parse(host)
    return [{ address: host, family: parsed.kind() === 'ipv4' ? 4 : 6 }]
  }
  let addresses
  try {
    addresses = await dns.lookup(host, { all: true, verbatim: true })
  } catch (error) {
    throw rendererError('MOMENT_MUSIC_SOURCE_DNS_UNAVAILABLE', 503, error)
  }
  const unique = Array.from(new Map(addresses.map((entry) => [entry.address, entry])).values())
  if (unique.length === 0) throw rendererError('MOMENT_MUSIC_SOURCE_DNS_UNAVAILABLE', 503)
  if (unique.some((entry) => !isPublicIpAddress(entry.address))) {
    throw rendererError('MOMENT_MUSIC_SOURCE_DNS_PRIVATE', 400)
  }
  return unique
}

function pinnedLookup(addresses) {
  let cursor = 0
  return (_hostname, options, callback) => {
    const requestedFamily = Number(options?.family || 0)
    const candidates = requestedFamily === 4 || requestedFamily === 6
      ? addresses.filter((entry) => entry.family === requestedFamily)
      : addresses
    if (candidates.length === 0) {
      callback(Object.assign(new Error('No approved address for requested family'), { code: 'ENOTFOUND' }))
      return
    }
    if (options?.all) {
      callback(null, candidates.map((entry) => ({ ...entry })))
      return
    }
    const selected = candidates[cursor % candidates.length]
    cursor += 1
    callback(null, selected.address, selected.family)
  }
}

async function requestPinnedHttps(sourceUrl, signal) {
  const parsed = new URL(sourceUrl)
  const addresses = await resolvePublicAddresses(parsed.hostname)
  return new Promise((resolve, reject) => {
    const request = https.get(parsed, {
      headers: { 'User-Agent': 'Novvin-Server/3.3.0 (Moment audio compatibility renderer)' },
      lookup: pinnedLookup(addresses),
      signal,
    }, resolve)
    request.once('error', reject)
  })
}

async function downloadPinnedSource(audioUrl, destination) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), DOWNLOAD_TIMEOUT_MS)
  let currentUrl = audioUrl
  try {
    for (let redirectCount = 0; redirectCount <= MAX_SOURCE_REDIRECTS; redirectCount += 1) {
      if (!isPublicHttpsUrl(currentUrl)) throw rendererError('MOMENT_MUSIC_SOURCE_URL_INVALID', 400)
      const response = await requestPinnedHttps(currentUrl, controller.signal)
      const statusCode = Number(response.statusCode || 0)
      if (statusCode >= 300 && statusCode <= 399) {
        response.resume()
        if (redirectCount >= MAX_SOURCE_REDIRECTS) {
          throw rendererError('MOMENT_MUSIC_SOURCE_REDIRECT_LIMIT', 502)
        }
        const location = String(response.headers.location || '').trim()
        if (!location) throw rendererError('MOMENT_MUSIC_SOURCE_REDIRECT_INVALID', 502)
        currentUrl = new URL(location, currentUrl).toString()
        continue
      }
      if (statusCode < 200 || statusCode > 299) {
        response.resume()
        throw rendererError(`MOMENT_MUSIC_SOURCE_HTTP_${statusCode || 502}`)
      }
      const declaredLength = Number(response.headers['content-length'] || 0)
      if (declaredLength > MAX_SOURCE_BYTES) {
        response.resume()
        throw rendererError('MOMENT_MUSIC_SOURCE_TOO_LARGE', 413)
      }
      let received = 0
      const limiter = new Transform({
        transform(chunk, _encoding, callback) {
          received += chunk.length
          if (received > MAX_SOURCE_BYTES) callback(rendererError('MOMENT_MUSIC_SOURCE_TOO_LARGE', 413))
          else callback(null, chunk)
        },
      })
      await pipeline(response, limiter, fs.createWriteStream(destination, { flags: 'wx' }))
      return
    }
    throw rendererError('MOMENT_MUSIC_SOURCE_REDIRECT_LIMIT', 502)
  } catch (error) {
    await fs.promises.rm(destination, { force: true }).catch(() => {})
    if (error?.statusCode) throw error
    throw rendererError('MOMENT_MUSIC_SOURCE_UNAVAILABLE', 503, error)
  } finally {
    clearTimeout(timeout)
  }
}

function momentMusicTranscodeArgs({ inputPath, outputPath, startMs, durationMs }) {
  const startSeconds = (Math.max(0, Number(startMs) || 0) / 1000).toFixed(3)
  const durationSeconds = (Math.max(1000, Number(durationMs) || 6000) / 1000).toFixed(3)
  return [
    '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
    '-ss', startSeconds,
    // A six-second image moment must not become silent when a source track is
    // shorter than the requested clip or the crop starts near its end. Loop
    // the already downloaded/owned source and still cap the output with -t.
    // This keeps the published AAC clip both audible and deterministic.
    '-stream_loop', '-1',
    '-i', inputPath,
    '-t', durationSeconds,
    '-map', '0:a:0',
    '-vn',
    // Plain apad is available on older FFmpeg builds; -t supplies the exact
    // endpoint. The whole_dur option is absent on some supported hosts.
    '-af', 'apad',
    '-c:a', 'aac',
    '-profile:a', 'aac_low',
    '-ar', '44100',
    '-ac', '2',
    '-b:a', '160k',
    '-movflags', '+faststart',
    '-f', 'ipod',
    outputPath,
  ]
}

async function runCommand(command, args, timeoutMs) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, { shell: false, windowsHide: true })
    let stderr = ''
    const timer = setTimeout(() => {
      child.kill('SIGKILL')
      reject(rendererError('MOMENT_MUSIC_RENDER_TIMEOUT'))
    }, timeoutMs)
    child.stderr.on('data', (chunk) => {
      if (stderr.length < 8_000) stderr += chunk.toString('utf8')
    })
    child.once('error', (error) => {
      clearTimeout(timer)
      reject(rendererError('MOMENT_MUSIC_RENDER_UNAVAILABLE', 503, error))
    })
    child.once('close', (code) => {
      clearTimeout(timer)
      if (code === 0) resolve()
      else reject(rendererError(`MOMENT_MUSIC_RENDER_FAILED:${stderr.trim().slice(0, 500)}`))
    })
  })
}

async function localStorageSource(pool, audioUrl) {
  const location = parseStorageObjectUrl(audioUrl)
  if (!location) return null
  const { rows } = await pool.query(
    `SELECT *
       FROM fallback_storage_objects
      WHERE bucket_id=$1 AND name=$2
      LIMIT 1`,
    [location.bucket, location.objectPath],
  )
  const row = rows[0]
  if (!row) return null
  if (String(process.env.STORAGE_SCAN_ENFORCE || '').toLowerCase() === 'true' && !cleanStorageRow(row)) throw rendererError('MOMENT_MUSIC_SOURCE_NOT_SCANNED', 409)
  if (Number(row.size_bytes || 0) > MAX_SOURCE_BYTES) {
    throw rendererError('MOMENT_MUSIC_SOURCE_TOO_LARGE', 413)
  }
  const filePath = storedObjectFilePath(row, location.bucket, location.objectPath)
  const stat = await fs.promises.stat(filePath)
  if (!stat.isFile() || stat.size <= 0 || stat.size > MAX_SOURCE_BYTES) {
    throw rendererError('MOMENT_MUSIC_SOURCE_INVALID', stat.size > MAX_SOURCE_BYTES ? 413 : 400)
  }
  return filePath
}

async function downloadApprovedSource(audioUrl, destination, fetchImpl = fetch) {
  if (!isPublicHttpsUrl(audioUrl)) throw rendererError('MOMENT_MUSIC_SOURCE_URL_INVALID', 400)
  if (fetchImpl === fetch) return downloadPinnedSource(audioUrl, destination)
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), DOWNLOAD_TIMEOUT_MS)
  try {
    const response = await fetchImpl(audioUrl, {
      headers: { 'User-Agent': 'Novvin-Server/3.3.0 (Moment audio compatibility renderer)' },
      redirect: 'follow',
      signal: controller.signal,
    })
    if (!response.ok || !response.body || !isPublicHttpsUrl(response.url || audioUrl)) {
      throw rendererError(`MOMENT_MUSIC_SOURCE_HTTP_${response.status || 502}`)
    }
    const declaredLength = Number(response.headers?.get?.('content-length') || 0)
    if (declaredLength > MAX_SOURCE_BYTES) throw rendererError('MOMENT_MUSIC_SOURCE_TOO_LARGE', 413)
    let received = 0
    const limiter = new Transform({
      transform(chunk, _encoding, callback) {
        received += chunk.length
        if (received > MAX_SOURCE_BYTES) callback(rendererError('MOMENT_MUSIC_SOURCE_TOO_LARGE', 413))
        else callback(null, chunk)
      },
    })
    await pipeline(Readable.fromWeb(response.body), limiter, fs.createWriteStream(destination, { flags: 'wx' }))
  } catch (error) {
    if (error?.statusCode) throw error
    throw rendererError('MOMENT_MUSIC_SOURCE_UNAVAILABLE', 503, error)
  } finally {
    clearTimeout(timeout)
  }
}

/**
 * Produces one local AAC-LC/M4A clip for the published Moment. This removes
 * provider reachability and OEM codec differences from viewer playback.
 */
async function renderCompatibleMomentMusic({ pool, req, user, approvedMusic, fetchImpl = fetch }) {
  if (!approvedMusic) return null
  const durationMs = Math.max(1_000, Math.min(Number(approvedMusic.durationMs || 6_000), 60_000))
  const tempRoot = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'novvin-moment-music-'))
  const downloadedInput = path.join(tempRoot, 'source-audio')
  const outputPath = path.join(tempRoot, 'compatible.m4a')
  try {
    let inputPath = await localStorageSource(pool, approvedMusic.audioUrl)
    if (!inputPath) {
      await downloadApprovedSource(approvedMusic.audioUrl, downloadedInput, fetchImpl)
      inputPath = downloadedInput
    }
    await runCommand('ffmpeg', momentMusicTranscodeArgs({
      inputPath,
      outputPath,
      startMs: approvedMusic.startMs,
      durationMs,
    }), TRANSCODE_TIMEOUT_MS)
    const stat = await fs.promises.stat(outputPath)
    if (!stat.isFile() || stat.size <= 0) throw rendererError('MOMENT_MUSIC_RENDER_EMPTY')
    // The storage volume can be a different filesystem from /tmp inside the
    // production container. Supplying a buffer avoids an EXDEV rename failure
    // after ffmpeg has already produced a valid clip.
    const renderedBuffer = await fs.promises.readFile(outputPath)

    const objectPath = `${user.id}/moments/rendered/${crypto.randomUUID()}.m4a`
    const stored = await upsertStoredObject(pool, req, {
      bucket: 'community-music',
      objectPath,
      fallbackName: objectPath,
      ownerId: user.id,
      buffer: renderedBuffer,
      sizeBytes: stat.size,
      contentType: 'audio/mp4',
      allowOverwrite: false,
      metadata: {
        source: 'moment-aac-compatibility-render',
        sourceTrackId: String(approvedMusic.id || ''),
        durationMs,
      },
    })
    return {
      ...approvedMusic,
      audioUrl: stored.public_url,
      startMs: 0,
      lyrics: clippedMusicLyrics(approvedMusic.lyrics, approvedMusic.startMs, durationMs),
      durationMs,
      endMs: durationMs,
      renderedCodec: 'aac-lc',
    }
  } finally {
    await fs.promises.rm(tempRoot, { recursive: true, force: true }).catch(() => {})
  }
}

module.exports = {
  isPublicIpAddress,
  isPublicHttpsUrl,
  momentMusicTranscodeArgs,
  resolvePublicAddresses,
  renderCompatibleMomentMusic,
}
