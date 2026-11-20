import { setTimeout as wait } from 'node:timers/promises'

/** Schedule from the original clock. Never turn overdue slots into a catch-up burst. */
export async function runStagingArrivals({ rate, concurrency, start, durationMs, stopped, issue }) {
  const planned = Math.ceil(durationMs * rate / 1000)
  const inFlight = new Set()
  let dispatched = 0
  let late = 0
  let atCapacity = 0
  let peakInFlight = 0
  let failure
  let slot = 0
  while (slot < planned && !stopped() && !failure) {
    const now = performance.now()
    const due = start + slot * 1000 / rate
    if (now < due) {
      await wait(Math.max(1, Math.ceil(due - now)))
      continue
    }
    // Scheduling granularity can cover several slots at very high rates.
    // Count anything over 25 ms late, without submitting it to the service.
    const firstOnTime = Math.min(planned, Math.max(slot, Math.ceil((now - start - 25) * rate / 1000)))
    if (firstOnTime > slot || now >= start + durationMs) {
      const next = now >= start + durationMs ? planned : firstOnTime
      late += next - slot
      slot = next
      continue
    }
    const index = slot++
    if (inFlight.size >= concurrency) {
      atCapacity++
      continue
    }
    dispatched++
    const task = Promise.resolve().then(() => issue(index)).catch(error => { failure = error })
      .finally(() => inFlight.delete(task))
    inFlight.add(task)
    peakInFlight = Math.max(peakInFlight, inFlight.size)
  }
  await Promise.all(inFlight)
  if (failure) throw failure
  return { planned, dispatched, late, atCapacity, notScheduledAfterStop: planned - slot, peakInFlight }
}
