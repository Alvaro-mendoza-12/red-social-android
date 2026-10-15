'use strict'

// Review-only candidate. Capacity is per process; a shared disk/job budget is separate.
function capacityError(code) {
  return Object.assign(new Error(code), { status: 503, retryAfterSeconds: 2 })
}

function boundedWorkQueue({ concurrency = 2, maxQueued = 16, queueWaitMs = 5000 } = {}) {
  for (const value of [concurrency, maxQueued, queueWaitMs]) {
    if (!Number.isInteger(value) || value < 1) throw new Error('INVALID_WORK_QUEUE_CAPACITY')
  }
  let active = 0
  const queued = []
  function start(entry) {
    clearTimeout(entry.timer)
    active += 1
    // Capacity is released only when the actual task settles, never on a Promise.race timeout.
    Promise.resolve().then(entry.task).then(entry.resolve, entry.reject).finally(() => {
      active -= 1
      while (active < concurrency && queued.length) start(queued.shift())
    })
  }
  return {
    run(task) {
      if (typeof task !== 'function') return Promise.reject(new TypeError('WORK_TASK_REQUIRED'))
      if (active >= concurrency && queued.length >= maxQueued) return Promise.reject(capacityError('WORK_QUEUE_FULL'))
      return new Promise((resolve, reject) => {
        const entry = { task, resolve, reject, timer: null }
        if (active < concurrency) start(entry)
        else {
          entry.timer = setTimeout(() => {
            const index = queued.indexOf(entry)
            if (index < 0) return
            queued.splice(index, 1)
            reject(capacityError('WORK_QUEUE_WAIT_EXPIRED'))
          }, queueWaitMs)
          queued.push(entry)
        }
      })
    },
    snapshot() { return Object.freeze({ active, queued: queued.length, concurrency, maxQueued }) },
  }
}

module.exports = { boundedWorkQueue }
