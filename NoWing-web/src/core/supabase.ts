import { createClient } from '@supabase/supabase-js'
import { isFallbackAuthConfigured } from './auth/fallbackAuth'
import { getRuntimeBackendUrl, rewriteRuntimeStorageUrl } from './runtimeBackend'

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL?.trim()
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY?.trim()

export const isSupabaseConfigured = !isFallbackAuthConfigured && Boolean(supabaseUrl && supabaseAnonKey)

export const supabase = isSupabaseConfigured
  ? createClient(supabaseUrl!, supabaseAnonKey!, {
      auth: {
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: true,
      },
      realtime: {
        params: {
          eventsPerSecond: 8,
        },
      },
    })
  : null

type RealtimeChannelLike = {
  subscribe: (callback?: (status: string, error?: unknown) => void) => unknown
  unsubscribe?: () => unknown
}

type SupabaseRestrictionSnapshot = {
  active: boolean
  reason: string
  message: string
  pausedUntil: number
}

const restrictionEventName = 'conectate-web-supabase-restriction'
const realtimePauseMillis = 5 * 60 * 1000

let realtimePausedUntil = 0
let restrictionReason = ''
let restrictionMessage = ''
let lastRestrictionLogAt = 0

function errorText(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message
  if (typeof error === 'string') return error
  try {
    return JSON.stringify(error)
  } catch {
    return String(error ?? '')
  }
}

export function sanitizeSupabaseError(error: unknown): string {
  return errorText(error)
    .replace(/Bearer\s+[A-Za-z0-9._~-]+/gi, 'Bearer [redacted]')
    .replace(/apikey=([A-Za-z0-9._~-]+)/gi, 'apikey=[redacted]')
    .replace(/access_token=([A-Za-z0-9._~-]+)/gi, 'access_token=[redacted]')
    .slice(0, 280)
}

export function isSupabaseServiceRestrictionError(error: unknown): boolean {
  const text = errorText(error).toLowerCase()
  return (
    text.includes('exceed_storage_size_quota') ||
    text.includes('storage size quota') ||
    text.includes('quota exceeded') ||
    text.includes('exceeded quota') ||
    text.includes('project has been restricted') ||
    text.includes('service restricted') ||
    text.includes('billing') ||
    text.includes('payment required') ||
    text.includes('402')
  )
}

export function getSupabaseRestrictionSnapshot(): SupabaseRestrictionSnapshot {
  const active = Date.now() < realtimePausedUntil
  return {
    active,
    reason: active ? restrictionReason : '',
    message: active ? restrictionMessage : '',
    pausedUntil: active ? realtimePausedUntil : 0,
  }
}

function notifyRestrictionChanged() {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(restrictionEventName, { detail: getSupabaseRestrictionSnapshot() }))
}

export function onSupabaseRestrictionChanged(callback: (snapshot: SupabaseRestrictionSnapshot) => void) {
  if (typeof window === 'undefined') return () => {}
  const handler = () => callback(getSupabaseRestrictionSnapshot())
  window.addEventListener(restrictionEventName, handler)
  return () => window.removeEventListener(restrictionEventName, handler)
}

export function reportSupabaseServiceRestrictionIfPresent(reason: string, error: unknown): boolean {
  if (!isSupabaseServiceRestrictionError(error)) return false

  realtimePausedUntil = Date.now() + realtimePauseMillis
  restrictionReason = reason
  restrictionMessage =
    'Supabase restringió temporalmente el proyecto. La web queda usable, pero pausamos Realtime para evitar loops y errores de cuota.'

  const now = Date.now()
  if (now - lastRestrictionLogAt > 30_000) {
    lastRestrictionLogAt = now
    console.warn(`Supabase restriction detected [${reason}]: ${sanitizeSupabaseError(error)}`)
  }
  notifyRestrictionChanged()
  return true
}

export function subscribeRealtimeChannel(
  channel: RealtimeChannelLike,
  options?: {
    reason?: string
    onStatus?: (status: string, error?: unknown) => void
  },
): RealtimeChannelLike | null {
  const reason = options?.reason ?? 'realtime'
  if (Date.now() < realtimePausedUntil) {
    console.warn(`Realtime paused [${reason}]: ${restrictionMessage}`)
    try {
      void channel.unsubscribe?.()
    } catch {
      // Best effort cleanup.
    }
    return null
  }

  try {
    channel.subscribe((status, error) => {
      if ((status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') && error) {
        reportSupabaseServiceRestrictionIfPresent(reason, error)
      }
      options?.onStatus?.(status, error)
    })
    return channel
  } catch (caught) {
    reportSupabaseServiceRestrictionIfPresent(reason, caught)
    return null
  }
}

export async function removeRealtimeChannel(channel: RealtimeChannelLike | null | undefined) {
  if (!channel) return
  try {
    if (supabase) {
      await supabase.removeChannel(channel as never)
      return
    }
  } catch {
    // Fall through to channel-level unsubscribe.
  }
  try {
    await channel.unsubscribe?.()
  } catch {
    // Best effort cleanup.
  }
}

export function resolveSupabasePublicUrl(bucket: string, pathOrUrl: string): string {
  if (!pathOrUrl) return ''
  let trimmed = pathOrUrl.trim()
  if (!trimmed) return ''

  const rewrittenRuntimeUrl = rewriteRuntimeStorageUrl(trimmed)
  if (rewrittenRuntimeUrl !== trimmed) return rewrittenRuntimeUrl
  if (trimmed.includes('/storage/v1/object/') && /^https?:\/\//i.test(trimmed)) {
    try {
      const source = new URL(trimmed)
      if (getRuntimeBackendUrl() && !source.hostname.endsWith('.supabase.co')) {
        return rewrittenRuntimeUrl
      }
    } catch {
      // Continue with path normalization.
    }
  }
  
  const marker = '/storage/v1/object/public/' + bucket + '/'
  if (trimmed.includes(marker)) {
    trimmed = trimmed.substring(trimmed.indexOf(marker) + marker.length)
  } else if (trimmed.includes('/' + bucket + '/')) {
    const parts = trimmed.split('/' + bucket + '/')
    trimmed = parts[parts.length - 1]
  }
  
  if (
    trimmed.startsWith('http://') ||
    trimmed.startsWith('https://') ||
    trimmed.startsWith('blob:') ||
    trimmed.startsWith('data:')
  ) {
    return trimmed
  }
  
  const prefix1 = bucket + '/'
  const prefix2 = 'public/' + bucket + '/'
  
  if (trimmed.startsWith(prefix1)) {
    trimmed = trimmed.substring(prefix1.length)
  } else if (trimmed.startsWith(prefix2)) {
    trimmed = trimmed.substring(prefix2.length)
  }

  const knownBuckets = [
    'avatars',
    'covers',
    'thumbnails',
    'videos',
    'post-media',
    'moments',
    'groups',
    'group-media',
    'community-media',
    'chat-media',
    'moment-media',
    'communities',
    'stickers',
  ]
  const embeddedBucket = knownBuckets.find((candidate) => trimmed.startsWith(`${candidate}/`))
  const resolvedBucket = embeddedBucket || bucket
  if (embeddedBucket) {
    trimmed = trimmed.substring(embeddedBucket.length + 1)
  }
  
  if (!supabase) {
    const runtime = getRuntimeBackendUrl()
    if (!runtime) return trimmed
    const objectPath = trimmed
      .split('/')
      .map((segment) => encodeURIComponent(segment))
      .join('/')
    return `${runtime}/storage/v1/object/public/${encodeURIComponent(resolvedBucket)}/${objectPath}`
  }
  return supabase.storage.from(resolvedBucket).getPublicUrl(trimmed).data.publicUrl
}
