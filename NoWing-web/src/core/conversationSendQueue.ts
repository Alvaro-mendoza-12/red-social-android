/** Keep each conversation ordered while allowing unrelated uploads to progress. */
export class ConversationSendQueue {
  private jobs = new Map<string, Promise<void>>()
  private waiting: Array<() => void> = []
  private running = 0
  private readonly concurrency: number

  constructor(concurrency = 3) { this.concurrency = concurrency }

  has(key: string) { return this.jobs.has(key) }

  schedule(key: string, run: () => Promise<void>): Promise<void> {
    const existing = this.jobs.get(key)
    if (existing) return existing
    let resolve!: () => void, reject!: (error: unknown) => void
    const job = new Promise<void>((done, fail) => { resolve = done; reject = fail })
    this.jobs.set(key, job)
    this.waiting.push(() => {
      this.running += 1
      const finish = () => {
        this.jobs.delete(key)
        this.running -= 1
        this.pump()
      }
      void Promise.resolve().then(run).then(() => { finish(); resolve() }, error => { finish(); reject(error) })
    })
    this.pump()
    return job
  }

  private pump() {
    while (this.running < this.concurrency && this.waiting.length) this.waiting.shift()!()
  }
}
