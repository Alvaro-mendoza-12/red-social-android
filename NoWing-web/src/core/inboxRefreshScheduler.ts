/** Coalesce bursts without postponing discovery forever or overlapping inbox reads. */
export function inboxRefreshScheduler(refresh: () => Promise<void>, onError: (error: unknown) => void, delay = 120) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let running = false
  let pending = false
  let stopped = false
  const request = () => {
    if (stopped) return
    pending = true
    if (running || timer !== undefined) return
    timer = setTimeout(() => {
      timer = undefined
      pending = false
      running = true
      void refresh().catch(onError).finally(() => {
        running = false
        if (pending) request()
      })
    }, delay)
  }
  return { request, stop: () => { stopped = true; pending = false; clearTimeout(timer) } }
}
