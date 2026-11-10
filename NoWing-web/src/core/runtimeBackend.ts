import {
  canonicalBackendBaseUrl,
  isExplicitlyTrustedBackend,
  verifyBackendDiscoveryEnvelope,
} from './backendTrust'
import { recordMessagingClock } from './messagingClock'
import { publicBackendTransportUrl } from './publicBackendTransport'

const storageKey = 'conectate:runtime-backend-url:v2'
const legacyStorageKey = 'conectate:runtime-backend-url'
const allowedClockSkewSeconds = 5 * 60
const requiredCapabilities = new Set([
  'anonymous_bottles_v1',
  'community_music_v1',
  'moment_music_v1',
  'moment_music_aac_lc_v1',
  'image_moments_exact_6s_v1',
  'video_sound_identity_v1',
  'video_feed_session_v1',
  'video_feed_live_v1',
])

type StoredBackend = {
  schemaVersion: 2
  url: string
  discoveryEnvelope?: string
  expiresAtEpochSeconds?: number
}

type TrustedCandidate = {
  url: string
  discoveryEnvelope: string
  expiresAtEpochSeconds: number | null
}

const allowHttp = import.meta.env.DEV
const configuredUrl = canonicalBackendBaseUrl(import.meta.env.VITE_FALLBACK_API_URL, allowHttp)
const discoveryPublicKey = String(import.meta.env.VITE_BACKEND_DISCOVERY_PUBLIC_KEY || '').trim()
let currentUrl = configuredUrl
let currentExpiresAtEpochSeconds: number | null = null
let initialization: Promise<string> | undefined
const listeners = new Set<(url: string) => void>()

function nowEpochSeconds(): number {
  return Math.floor(Date.now() / 1000)
}

function activeUrl(): string {
  if (
    currentExpiresAtEpochSeconds !== null &&
    currentExpiresAtEpochSeconds <= nowEpochSeconds() - allowedClockSkewSeconds
  ) {
    currentUrl = configuredUrl
    currentExpiresAtEpochSeconds = null
  }
  return currentUrl
}

export function getRuntimeBackendUrl(): string {
  return publicBackendTransportUrl(activeUrl())
}

export function rewriteRuntimeStorageUrl(value: string): string {
  const clean = String(value ?? '').trim()
  if (!clean || clean.startsWith('blob:') || clean.startsWith('data:')) return clean

  const storageMarker = '/storage/v1/object/'
  const markerIndex = clean.indexOf(storageMarker)
  if (markerIndex < 0) return clean

  const backendUrl = getRuntimeBackendUrl()
  if (!backendUrl) return clean
  if (clean.startsWith(storageMarker)) return `${backendUrl}${clean}`

  try {
    const source = new URL(clean)
    if (source.hostname.endsWith('.supabase.co')) return clean
    return `${backendUrl}${clean.slice(markerIndex)}`
  } catch {
    return clean
  }
}

export function runtimeStoragePreviewUrl(value: string, width = 1080, quality = 78): string {
  const rewritten = rewriteRuntimeStorageUrl(value)
  const backendUrl = getRuntimeBackendUrl()
  if (!backendUrl || !rewritten.startsWith(`${backendUrl}/storage/v1/object/public/`)) return rewritten
  try {
    const preview = new URL(rewritten)
    preview.searchParams.set('width', String(Math.min(1920, Math.max(64, Math.round(width)))))
    preview.searchParams.set('quality', String(Math.min(90, Math.max(40, Math.round(quality)))))
    preview.searchParams.set('format', 'webp')
    return preview.toString()
  } catch {
    return rewritten
  }
}

export function subscribeRuntimeBackend(listener: (url: string) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function persistCandidate(candidate: TrustedCandidate) {
  if (typeof window === 'undefined') return
  const payload: StoredBackend = {
    schemaVersion: 2,
    url: candidate.url,
    discoveryEnvelope: candidate.discoveryEnvelope || undefined,
    expiresAtEpochSeconds: candidate.expiresAtEpochSeconds ?? undefined,
  }
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(payload))
    window.localStorage.removeItem(legacyStorageKey)
  } catch {
    // Runtime discovery remains usable when storage is unavailable.
  }
}

function updateRuntimeBackend(candidate: TrustedCandidate) {
  const changed = candidate.url !== currentUrl
  currentUrl = candidate.url
  currentExpiresAtEpochSeconds = candidate.expiresAtEpochSeconds
  persistCandidate(candidate)
  if (changed) listeners.forEach((listener) => listener(getRuntimeBackendUrl()))
}

async function trustCandidate(
  candidateValue: unknown,
  discoveryEnvelope: unknown = '',
): Promise<TrustedCandidate | null> {
  const candidate = canonicalBackendBaseUrl(candidateValue, allowHttp)
  if (!candidate) return null
  if (isExplicitlyTrustedBackend(candidate, configuredUrl, allowHttp)) {
    return { url: candidate, discoveryEnvelope: '', expiresAtEpochSeconds: null }
  }
  if (allowHttp || !discoveryPublicKey) return null
  const verified = await verifyBackendDiscoveryEnvelope(discoveryEnvelope, discoveryPublicKey)
  if (!verified || verified.url !== candidate) return null
  return {
    url: verified.url,
    discoveryEnvelope: verified.envelope,
    expiresAtEpochSeconds: verified.expiresAtEpochSeconds,
  }
}

async function restoreStoredCandidate(): Promise<TrustedCandidate | null> {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(storageKey)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<StoredBackend>
      if (parsed.schemaVersion === 2) {
        return await trustCandidate(parsed.url, parsed.discoveryEnvelope)
      }
    }
    const legacy = window.localStorage.getItem(legacyStorageKey)
    if (legacy && isExplicitlyTrustedBackend(legacy, configuredUrl, allowHttp)) {
      return await trustCandidate(legacy)
    }
  } catch {
    return null
  }
  return null
}

async function probeBackend(url: string): Promise<boolean> {
  try {
    const started = performance.now()
    const transport = publicBackendTransportUrl(url)
    const response = await fetch(`${transport}/ready`, {
      cache: 'no-store',
      headers: { 'ngrok-skip-browser-warning': 'true' },
      signal: AbortSignal.timeout(5_000),
    })
    if (!response.ok) return false
    const payload = (await response.json()) as { ok?: boolean; capabilities?: unknown; databaseTime?: string }
    if (payload.ok !== true || !Array.isArray(payload.capabilities)) return false
    const advertised = new Set(
      payload.capabilities.map((value) => String(value ?? '').trim()).filter(Boolean),
    )
    const compatible = [...requiredCapabilities].every((capability) => advertised.has(capability))
    if (compatible) recordMessagingClock(transport, payload.databaseTime, started, performance.now())
    return compatible
  } catch {
    return false
  }
}

function unwrapDnsTxt(value: unknown): string {
  return String(value ?? '')
    .trim()
    .replace(/^"|"$/g, '')
    .replace(/"\s+"/g, '')
}

async function readDnsCandidate(): Promise<TrustedCandidate | null> {
  try {
    const host = String(
      import.meta.env.VITE_BACKEND_DISCOVERY_HOST || 'conectate-alvaro.duckdns.org',
    ).trim()
    if (!host || !discoveryPublicKey) return null
    const response = await fetch(
      `https://dns.google/resolve?name=${encodeURIComponent(host)}&type=TXT`,
      { cache: 'no-store', signal: AbortSignal.timeout(5_000) },
    )
    if (!response.ok) return null
    const payload = (await response.json()) as { Answer?: Array<{ data?: string }> }
    for (const answer of payload.Answer ?? []) {
      const envelope = unwrapDnsTxt(answer.data)
      const verified = await verifyBackendDiscoveryEnvelope(envelope, discoveryPublicKey)
      if (verified) {
        return {
          url: verified.url,
          discoveryEnvelope: verified.envelope,
          expiresAtEpochSeconds: verified.expiresAtEpochSeconds,
        }
      }
    }
  } catch {
    return null
  }
  return null
}

async function readConfigCandidate(): Promise<TrustedCandidate | null> {
  try {
    const response = await fetch('/backend-config.json', {
      cache: 'no-store',
      signal: AbortSignal.timeout(5_000),
    })
    if (!response.ok) return null
    const payload = (await response.json()) as {
      backendUrl?: string
      apiUrl?: string
      discoveryEnvelope?: string
    }
    return await trustCandidate(
      payload.backendUrl || payload.apiUrl,
      payload.discoveryEnvelope,
    )
  } catch {
    return null
  }
}

async function selectReachableCandidate(
  candidates: Array<TrustedCandidate | null>,
  skippedUrl = '',
) {
  const visited = new Set<string>()
  for (const candidate of candidates) {
    if (
      candidate &&
      candidate.url !== skippedUrl &&
      !visited.has(candidate.url) &&
      (await probeBackend(candidate.url))
    ) {
      return candidate
    }
    if (candidate) visited.add(candidate.url)
  }
  return null
}

async function resolveRuntimeBackend(skippedUrl = ''): Promise<TrustedCandidate | null> {
  // The configured/cached endpoint is tried first. DNS-over-HTTPS and the
  // config document stay off the hot startup path while the active origin is healthy.
  const localCandidate = await selectReachableCandidate([
    await restoreStoredCandidate(),
    await trustCandidate(configuredUrl),
  ], skippedUrl)
  if (localCandidate) return localCandidate

  const [dnsCandidate, configCandidate] = await Promise.all([
    readDnsCandidate(),
    readConfigCandidate(),
  ])
  return await selectReachableCandidate([dnsCandidate, configCandidate], skippedUrl)
}

export function initializeRuntimeBackend(): Promise<string> {
  if (!initialization) {
    const operation = (async () => {
      const candidate = await resolveRuntimeBackend()
      if (candidate) updateRuntimeBackend(candidate)
      return getRuntimeBackendUrl()
    })()
    initialization = operation
    void operation.finally(() => { if (initialization === operation) initialization = undefined }).catch(() => {})
  }
  return initialization
}

/** Re-resolves a rotated endpoint and returns only a newly trusted URL. */
export async function refreshRuntimeBackend(failedUrl = ''): Promise<string> {
  const before = getRuntimeBackendUrl()
  if (failedUrl && before && !String(failedUrl).trim().startsWith(before)) return ''
  const candidate = await resolveRuntimeBackend(activeUrl())
  if (candidate) updateRuntimeBackend(candidate)
  const after = getRuntimeBackendUrl()
  return after && after !== before ? after : ''
}

export const backendDiscoveryTrustConfigured = discoveryPublicKey.length > 0
