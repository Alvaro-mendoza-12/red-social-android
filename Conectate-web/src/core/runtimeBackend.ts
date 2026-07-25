const storageKey = 'conectate:runtime-backend-url'

function normalize(value: unknown): string {
  return String(value ?? '').trim().replace(/\/+$/, '')
}

function isAllowed(value: string): boolean {
  if (value.startsWith('https://')) return true
  return import.meta.env.DEV && value.startsWith('http://')
}

function readStoredUrl(): string {
  if (typeof window === 'undefined') return ''
  try {
    return normalize(window.localStorage.getItem(storageKey))
  } catch {
    return ''
  }
}

const storedUrl = readStoredUrl()
const configuredUrl = normalize(import.meta.env.VITE_FALLBACK_API_URL)
let currentUrl = [storedUrl, configuredUrl].find(isAllowed) || ''
const listeners = new Set<(url: string) => void>()

export function getRuntimeBackendUrl(): string {
  return currentUrl
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

function updateRuntimeBackend(url: string) {
  const normalized = normalize(url)
  if (!isAllowed(normalized) || normalized === currentUrl) return
  currentUrl = normalized
  try {
    window.localStorage.setItem(storageKey, normalized)
  } catch {
    // Runtime discovery still works when storage is unavailable.
  }
  listeners.forEach((listener) => listener(normalized))
}

async function probeBackend(url: string): Promise<boolean> {
  try {
    const response = await fetch(`${url}/ready`, {
      cache: 'no-store',
      headers: { 'ngrok-skip-browser-warning': 'true' },
    })
    return response.ok
  } catch {
    return false
  }
}

async function readDnsEndpoint(): Promise<string> {
  try {
    const host = String(import.meta.env.VITE_BACKEND_DISCOVERY_HOST || 'conectate-alvaro.duckdns.org').trim()
    if (!host) return ''
    const response = await fetch(
      `https://dns.google/resolve?name=${encodeURIComponent(host)}&type=TXT`,
      { cache: 'no-store' },
    )
    if (!response.ok) return ''
    const payload = (await response.json()) as { Answer?: Array<{ data?: string }> }
    return normalize(
      payload.Answer?.map((answer) => String(answer.data || '').replace(/^"|"$/g, ''))
        .find((value) => isAllowed(normalize(value))) || '',
    )
  } catch {
    return ''
  }
}

export async function initializeRuntimeBackend(): Promise<string> {
  const dnsEndpoint = await readDnsEndpoint()
  if (isAllowed(dnsEndpoint) && (await probeBackend(dnsEndpoint))) {
    updateRuntimeBackend(dnsEndpoint)
    return currentUrl
  }
  try {
    const response = await fetch('/backend-config.json', { cache: 'no-store' })
    if (!response.ok) return currentUrl
    const payload = (await response.json()) as { backendUrl?: string; apiUrl?: string }
    const candidate = normalize(payload.backendUrl || payload.apiUrl)
    if (isAllowed(candidate) && (await probeBackend(candidate))) {
      updateRuntimeBackend(candidate)
    }
  } catch (error) {
    console.warn('[runtime-backend] se conserva el ultimo endpoint valido', error)
  }
  return currentUrl
}

/** Re-resolves a rotated tunnel after a request fails and returns only a new URL. */
export async function refreshRuntimeBackend(failedUrl = ''): Promise<string> {
  const before = currentUrl
  if (failedUrl && before && !normalize(failedUrl).startsWith(before)) return ''
  await initializeRuntimeBackend()
  return currentUrl && currentUrl !== before ? currentUrl : ''
}
