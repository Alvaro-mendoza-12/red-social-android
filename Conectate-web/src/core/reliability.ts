export type DeferredMutationType =
  | 'notification_mark_read'
  | 'notification_mark_all'
  | 'chat_mark_read'

export type DeferredMutationPayloadMap = {
  notification_mark_read: { notificationId: string }
  notification_mark_all: Record<string, never>
  chat_mark_read: { chatId: string }
}

export type DeferredMutation = {
  id: string
  type: DeferredMutationType
  payload: DeferredMutationPayloadMap[DeferredMutationType]
  dedupeKey: string
  attempts: number
  createdAt: string
  lastError: string
}

type RetryOptions = {
  maxRetries?: number
  baseDelayMs?: number
  maxDelayMs?: number
  shouldRetry?: (error: unknown) => boolean
}

const queueStorageKey = 'conectate-web-deferred-mutations-v1'
const maxMutationAttempts = 8

function jitter(ms: number): number {
  return Math.round(ms + Math.random() * (ms * 0.25))
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

function stableErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim().length > 0) return error.message.trim()
  return String(error ?? 'unknown_error')
}

function normalizeQueue(raw: unknown): DeferredMutation[] {
  if (!Array.isArray(raw)) return []
  return raw
    .filter((item) => item && typeof item === 'object')
    .map((item) => {
      const row = item as Record<string, unknown>
      return {
        id: String(row.id ?? ''),
        type: String(row.type ?? '') as DeferredMutationType,
        payload: (row.payload ?? {}) as DeferredMutationPayloadMap[DeferredMutationType],
        dedupeKey: String(row.dedupeKey ?? ''),
        attempts: Number(row.attempts ?? 0),
        createdAt: String(row.createdAt ?? ''),
        lastError: String(row.lastError ?? ''),
      } satisfies DeferredMutation
    })
    .filter(
      (item) =>
        item.id.length > 0 &&
        item.dedupeKey.length > 0 &&
        item.createdAt.length > 0 &&
        (item.type === 'notification_mark_read' ||
          item.type === 'notification_mark_all' ||
          item.type === 'chat_mark_read'),
    )
}

function readQueue(): DeferredMutation[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(queueStorageKey)
    if (!raw) return []
    return normalizeQueue(JSON.parse(raw))
  } catch {
    return []
  }
}

function writeQueue(queue: DeferredMutation[]) {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(queueStorageKey, JSON.stringify(queue))
    window.dispatchEvent(new Event('conectate-web-deferred-queue-updated'))
  } catch {
    // Ignore storage write failures.
  }
}

function createMutationDedupeKey<TType extends DeferredMutationType>(
  type: TType,
  payload: DeferredMutationPayloadMap[TType],
): string {
  if (type === 'notification_mark_read') {
    return `${type}:${String((payload as DeferredMutationPayloadMap['notification_mark_read']).notificationId)}`
  }
  if (type === 'chat_mark_read') {
    return `${type}:${String((payload as DeferredMutationPayloadMap['chat_mark_read']).chatId)}`
  }
  return type
}

export function getDeferredMutationCount(): number {
  return readQueue().length
}

export function enqueueDeferredMutation<TType extends DeferredMutationType>(
  type: TType,
  payload: DeferredMutationPayloadMap[TType],
): number {
  const dedupeKey = createMutationDedupeKey(type, payload)
  const queue = readQueue()
  if (queue.some((item) => item.dedupeKey === dedupeKey)) {
    return queue.length
  }
  const next: DeferredMutation = {
    id: crypto.randomUUID(),
    type,
    payload: payload as DeferredMutationPayloadMap[DeferredMutationType],
    dedupeKey,
    attempts: 0,
    createdAt: new Date().toISOString(),
    lastError: '',
  }
  const nextQueue = [...queue, next]
  writeQueue(nextQueue)
  return nextQueue.length
}

export function isBrowserOnline(): boolean {
  if (typeof navigator === 'undefined') return true
  return navigator.onLine
}

export function isLikelyTransientError(error: unknown): boolean {
  const message = stableErrorMessage(error).toLowerCase()
  return (
    message.includes('timeout') ||
    message.includes('timed out') ||
    message.includes('network') ||
    message.includes('failed to fetch') ||
    message.includes('fetch failed') ||
    message.includes('connection') ||
    message.includes('429') ||
    message.includes('rate limit') ||
    message.includes('503') ||
    message.includes('502') ||
    message.includes('500')
  )
}

export async function runWithRetry<T>(
  operation: () => Promise<T>,
  options: RetryOptions = {},
): Promise<T> {
  const maxRetries = Math.max(0, options.maxRetries ?? 2)
  const baseDelayMs = Math.max(100, options.baseDelayMs ?? 280)
  const maxDelayMs = Math.max(baseDelayMs, options.maxDelayMs ?? 4000)
  const shouldRetry = options.shouldRetry ?? isLikelyTransientError

  let attempt = 0
  let lastError: unknown

  while (attempt <= maxRetries) {
    try {
      return await operation()
    } catch (error) {
      lastError = error
      if (attempt >= maxRetries || !shouldRetry(error)) {
        throw error
      }
      const delayMs = Math.min(maxDelayMs, baseDelayMs * 2 ** attempt)
      await wait(jitter(delayMs))
      attempt += 1
    }
  }

  throw lastError
}

export type FlushMutationExecutor = (mutation: DeferredMutation) => Promise<void>

export type FlushDeferredQueueResult = {
  processed: number
  failed: number
  remaining: number
}

export async function flushDeferredMutations(
  executor: FlushMutationExecutor,
  maxPerRun = 50,
): Promise<FlushDeferredQueueResult> {
  const queue = readQueue()
  if (queue.length === 0) {
    return { processed: 0, failed: 0, remaining: 0 }
  }

  const survivors: DeferredMutation[] = []
  let processed = 0
  let failed = 0

  for (const mutation of queue.slice(0, maxPerRun)) {
    try {
      await executor(mutation)
      processed += 1
    } catch (error) {
      failed += 1
      const attempts = mutation.attempts + 1
      if (attempts < maxMutationAttempts) {
        survivors.push({
          ...mutation,
          attempts,
          lastError: stableErrorMessage(error),
        })
      }
    }
  }

  const untouched = queue.slice(maxPerRun)
  writeQueue([...survivors, ...untouched])
  return {
    processed,
    failed,
    remaining: survivors.length + untouched.length,
  }
}
