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

const legacyQueueStorageKey = 'conectate-web-deferred-mutations-v1'
const queueStoragePrefix = 'conectate-web-deferred-mutations-v2:'
const maxMutationAttempts = 8

function queueStorageKey(accountId: string): string {
  const normalized = accountId.trim().toLowerCase()
  if (!normalized) throw new Error('DEFERRED_MUTATION_ACCOUNT_REQUIRED')
  return `${queueStoragePrefix}${encodeURIComponent(normalized)}`
}

function createMutationId(): string {
  const cryptoApi = globalThis.crypto
  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID()
  if (typeof cryptoApi?.getRandomValues === 'function') {
    const bytes = cryptoApi.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x40
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  // This identifier is only local queue bookkeeping, never an auth or
  // idempotency credential. Keep older browsers functional without weakening
  // any server-side security boundary.
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

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

function readQueue(accountId: string): DeferredMutation[] {
  if (typeof window === 'undefined') return []
  try {
    // The old queue had no owner and cannot be replayed safely after account
    // switching. Drop it rather than guessing who created its mutations.
    window.localStorage.removeItem(legacyQueueStorageKey)
    const raw = window.localStorage.getItem(queueStorageKey(accountId))
    if (!raw) return []
    return normalizeQueue(JSON.parse(raw))
  } catch {
    return []
  }
}

function writeQueue(accountId: string, queue: DeferredMutation[]) {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(queueStorageKey(accountId), JSON.stringify(queue))
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

export function getDeferredMutationCount(accountId: string): number {
  return readQueue(accountId).length
}

export function enqueueDeferredMutation<TType extends DeferredMutationType>(
  type: TType,
  payload: DeferredMutationPayloadMap[TType],
  accountId: string,
): number {
  const dedupeKey = createMutationDedupeKey(type, payload)
  const queue = readQueue(accountId)
  if (queue.some((item) => item.dedupeKey === dedupeKey)) {
    return queue.length
  }
  const next: DeferredMutation = {
    id: createMutationId(),
    type,
    payload: payload as DeferredMutationPayloadMap[DeferredMutationType],
    dedupeKey,
    attempts: 0,
    createdAt: new Date().toISOString(),
    lastError: '',
  }
  const nextQueue = [...queue, next]
  writeQueue(accountId, nextQueue)
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

const activeFlushes = new Map<string, Promise<FlushDeferredQueueResult>>()

export function flushDeferredMutations(
  executor: FlushMutationExecutor,
  accountId: string,
  maxPerRun = 50,
  shouldContinue: () => boolean = () => true,
): Promise<FlushDeferredQueueResult> {
  const owner = accountId.trim().toLowerCase()
  const key = queueStorageKey(owner)
  const active = activeFlushes.get(key)
  if (active) return active
  const run = () => flushDeferredQueue(executor, owner, maxPerRun, shouldContinue)
  // Serialize between tabs when available; in-process sharing also covers
  // browsers without Web Locks and overlapping lifecycle/poll callbacks.
  const pending = Promise.resolve().then(async (): Promise<FlushDeferredQueueResult> => {
    if (typeof navigator !== 'undefined' && navigator.locks) {
      return await navigator.locks.request(key, { ifAvailable: true }, async lock => lock
        ? await run()
        : { processed: 0, failed: 0, remaining: getDeferredMutationCount(owner) })
    }
    return run()
  }).finally(() => {
      if (activeFlushes.get(key) === pending) activeFlushes.delete(key)
    })
  activeFlushes.set(key, pending)
  return pending
}

async function flushDeferredQueue(
  executor: FlushMutationExecutor,
  accountId: string,
  maxPerRun: number,
  shouldContinue: () => boolean,
): Promise<FlushDeferredQueueResult> {
  const queue = readQueue(accountId)
  if (queue.length === 0) {
    return { processed: 0, failed: 0, remaining: 0 }
  }

  let processed = 0
  let failed = 0

  const limit = Number.isFinite(maxPerRun) ? Math.max(0, Math.floor(maxPerRun)) : 50
  for (const mutation of queue.slice(0, limit)) {
    if (!shouldContinue()) break
    // A different tab may have already completed an item from our snapshot.
    if (!readQueue(accountId).some(item => item.id === mutation.id)) continue
    let replacement: DeferredMutation | undefined
    try {
      await executor(mutation)
      processed += 1
    } catch (error) {
      // Leaving the account is not a failed delivery attempt. Keep this item.
      if (!shouldContinue()) break
      failed += 1
      const attempts = mutation.attempts + 1
      if (attempts < maxMutationAttempts) {
        replacement = {
          ...mutation,
          attempts,
          lastError: stableErrorMessage(error),
        }
      }
    }
    // Reconcile only the attempted item against the CURRENT queue. Replacing
    // the original snapshot here would erase actions enqueued during await.
    writeQueue(accountId, readQueue(accountId).flatMap(item => item.id === mutation.id
      ? replacement ? [replacement] : []
      : [item]))
  }

  return {
    processed,
    failed,
    remaining: getDeferredMutationCount(accountId),
  }
}
