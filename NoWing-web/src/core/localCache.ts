type CacheEnvelope<T> = {
  value: T
  savedAt: number
}

const cachePrefix = 'conectate-web-cache-v1:'

function keyOf(key: string): string {
  return `${cachePrefix}${key.trim()}`
}

export function readCachedValue<T>(key: string, maxAgeMs?: number): T | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(keyOf(key))
    if (!raw) return null
    const parsed = JSON.parse(raw) as CacheEnvelope<T>
    if (!parsed || typeof parsed !== 'object') return null
    if (typeof parsed.savedAt !== 'number' || !Number.isFinite(parsed.savedAt)) return null
    if (maxAgeMs && maxAgeMs > 0) {
      const age = Date.now() - parsed.savedAt
      if (age > maxAgeMs) return null
    }
    return (parsed.value ?? null) as T | null
  } catch {
    return null
  }
}

export function writeCachedValue<T>(key: string, value: T): void {
  if (typeof window === 'undefined') return
  try {
    const payload: CacheEnvelope<T> = {
      value,
      savedAt: Date.now(),
    }
    window.localStorage.setItem(keyOf(key), JSON.stringify(payload))
  } catch {
    // Ignore storage write errors.
  }
}

export function clearCachedValue(key: string): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.removeItem(keyOf(key))
  } catch {
    // Ignore storage failures.
  }
}
