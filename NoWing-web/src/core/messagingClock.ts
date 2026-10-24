type Sample = { serverMillis: number; monotonicMillis: number }
const samples = new Map<string, Sample>()
const monotonicNow = () => performance.now()
const MAX_SAMPLE_AGE = 24 * 60 * 60 * 1000

/** Only called for the readiness response of an already trusted backend. */
export function recordMessagingClock(backend: string, databaseTime: unknown, started: number, finished: number) {
  if (typeof databaseTime !== 'string' || !/T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})$/i.test(databaseTime)) return
  const instant = Date.parse(databaseTime), elapsed = finished - started
  if (!backend || !Number.isFinite(instant) || !Number.isFinite(elapsed) || elapsed < 0 || elapsed > 5000) return
  if (samples.size >= 4 && !samples.has(backend)) samples.delete(samples.keys().next().value!)
  samples.set(backend, { serverMillis: instant + elapsed / 2, monotonicMillis: finished })
}

/** Pending messages use database time too; a corrected PC clock cannot double-apply an offset. */
export function messagingNowIso(backend: string): string {
  const sample = samples.get(backend)
  const elapsed = sample ? monotonicNow() - sample.monotonicMillis : -1
  return new Date(sample && elapsed >= 0 && elapsed < MAX_SAMPLE_AGE
    ? sample.serverMillis + elapsed : Date.now()).toISOString()
}
