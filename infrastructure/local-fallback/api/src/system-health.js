const fs = require('fs')
const path = require('path')

const DEFAULT_TIMEOUT_MS = 2_500

function positiveInteger(value, fallback, minimum = 250, maximum = 15_000) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.max(minimum, Math.min(Math.trunc(parsed), maximum))
}

function normalizedBaseUrl(value) {
  const url = String(value || '').trim().replace(/\/+$/, '')
  return /^https?:\/\//i.test(url) ? url : ''
}

function releaseMetadataFromText(text) {
  return String(text || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .reduce((result, line) => {
      const separator = line.indexOf('=')
      if (separator <= 0) return result
      const key = line.slice(0, separator).trim()
      const value = line.slice(separator + 1).trim()
      if (key && value) result[key] = value
      return result
    }, {})
}

async function loadReleaseMetadata({
  metadataPath = process.env.RELEASE_METADATA_FILE || '/run/novvin-release',
  fileSystem = fs,
} = {}) {
  const fromEnvironment = {
    release: String(process.env.APP_RELEASE || '').trim(),
    commit: String(process.env.APP_COMMIT_SHA || '').trim(),
    sourceDigest: String(process.env.APP_SOURCE_DIGEST || '').trim(),
    deployedAtUtc: String(process.env.APP_DEPLOYED_AT_UTC || '').trim(),
  }
  const file = await fileSystem.promises.readFile(metadataPath, 'utf8')
    .then(releaseMetadataFromText)
    .catch(() => ({}))
  return {
    release: fromEnvironment.release || file.release || 'unknown',
    commit: fromEnvironment.commit || file.commit || 'unknown',
    sourceDigest: fromEnvironment.sourceDigest || file.source_digest || null,
    deployedAtUtc: fromEnvironment.deployedAtUtc || file.deployed_at_utc || null,
    environment: String(process.env.APP_ENVIRONMENT || process.env.NODE_ENV || 'unknown').trim(),
  }
}

async function timedComponent(check, { timeoutMs = DEFAULT_TIMEOUT_MS, now = Date.now } = {}) {
  const startedAt = now()
  let timer
  try {
    const result = await Promise.race([
      Promise.resolve().then(check),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('HEALTH_CHECK_TIMEOUT')), timeoutMs)
        timer.unref?.()
      }),
    ])
    return {
      ok: result?.ok !== false,
      status: result?.status || 'operational',
      latencyMs: Math.max(0, now() - startedAt),
      ...(result?.message ? { message: String(result.message).slice(0, 160) } : {}),
      ...(result?.value !== undefined ? { value: result.value } : {}),
    }
  } catch (error) {
    return {
      ok: false,
      status: error?.message === 'HEALTH_CHECK_TIMEOUT' ? 'timeout' : 'unavailable',
      latencyMs: Math.max(0, now() - startedAt),
      message: error?.message === 'HEALTH_CHECK_TIMEOUT'
        ? 'La comprobación excedió el tiempo permitido.'
        : 'El componente no respondió correctamente.',
    }
  } finally {
    if (timer) clearTimeout(timer)
  }
}

async function fetchHealth(url, {
  fetchImpl = global.fetch,
  timeoutMs = DEFAULT_TIMEOUT_MS,
} = {}) {
  const baseUrl = normalizedBaseUrl(url)
  if (!baseUrl) throw new Error('SERVICE_URL_NOT_CONFIGURED')
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), timeoutMs)
  timeout.unref?.()
  try {
    const response = await fetchImpl(`${baseUrl}/health`, {
      headers: { accept: 'application/json' },
      signal: controller.signal,
    })
    if (!response.ok) throw new Error(`SERVICE_HTTP_${response.status}`)
    const payload = await response.json()
    if (payload?.ok !== true) throw new Error('SERVICE_NOT_HEALTHY')
    return payload
  } finally {
    clearTimeout(timeout)
  }
}

async function collectSystemHealth({
  pool,
  storageRoot = process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'),
  realtimeUrl = process.env.REALTIME_INTERNAL_URL || '',
  signalingUrl = process.env.SIGNALING_INTERNAL_URL || '',
  fetchImpl = global.fetch,
  fileSystem = fs,
  now = Date.now,
  timeoutMs = positiveInteger(process.env.SYSTEM_HEALTH_TIMEOUT_MS, DEFAULT_TIMEOUT_MS),
  service = 'conectate-local-fallback',
  version = 'unknown',
  releaseMetadataPath,
} = {}) {
  if (!pool?.query) throw new Error('SYSTEM_HEALTH_POOL_REQUIRED')

  const [database, storage, realtime, signaling, release] = await Promise.all([
    timedComponent(async () => {
      await pool.query('SELECT 1 AS healthy')
      return { ok: true }
    }, { timeoutMs, now }),
    timedComponent(async () => {
      await fileSystem.promises.access(storageRoot, fs.constants.R_OK | fs.constants.W_OK)
      return { ok: true }
    }, { timeoutMs, now }),
    timedComponent(async () => {
      await fetchHealth(realtimeUrl, { fetchImpl, timeoutMs })
      return { ok: true }
    }, { timeoutMs, now }),
    timedComponent(async () => {
      const payload = await fetchHealth(signalingUrl, { fetchImpl, timeoutMs })
      return { ok: true, value: payload }
    }, { timeoutMs, now }),
    loadReleaseMetadata({ metadataPath: releaseMetadataPath, fileSystem }),
  ])

  const signalingPayload = signaling.value
  delete signaling.value
  const hasVoiceNamespace = signalingPayload?.namespaces?.voice === '/'
  const hasLiveNamespace = signalingPayload?.namespaces?.live === '/live'
  const turnConfigured = signalingPayload?.turnConfigured === true ||
    signalingPayload?.turn?.configured === true

  const voice = {
    ...signaling,
    ok: signaling.ok && hasVoiceNamespace && turnConfigured,
    status: signaling.ok && hasVoiceNamespace && turnConfigured
      ? 'operational'
      : signaling.ok && !turnConfigured
        ? 'turn_not_configured'
        : signaling.status,
    message: signaling.ok && !turnConfigured
      ? 'La señalización responde, pero TURN no está configurado para redes restrictivas.'
      : signaling.message,
  }
  const live = {
    ...signaling,
    ok: signaling.ok && hasLiveNamespace && turnConfigured,
    status: signaling.ok && hasLiveNamespace && turnConfigured
      ? 'operational'
      : signaling.ok && !turnConfigured
        ? 'turn_not_configured'
        : signaling.status,
    message: signaling.ok && !turnConfigured
      ? 'Directos responde, pero TURN no está configurado para redes restrictivas.'
      : signaling.message,
  }

  const components = {
    api: { ok: true, status: 'operational', latencyMs: 0 },
    database,
    storage,
    realtime,
    voice,
    live,
  }
  const ok = Object.values(components).every((component) => component.ok)
  return {
    ok,
    status: ok ? 'operational' : 'degraded',
    service,
    version,
    generatedAt: new Date(now()).toISOString(),
    release,
    components,
  }
}

module.exports = {
  collectSystemHealth,
  fetchHealth,
  loadReleaseMetadata,
  normalizedBaseUrl,
  positiveInteger,
  releaseMetadataFromText,
  timedComponent,
}
