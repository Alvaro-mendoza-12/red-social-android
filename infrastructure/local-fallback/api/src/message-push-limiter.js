'use strict'

// A message push holds a PostgreSQL connection while FCM is accepting it, so
// reserve a slot before acquiring that connection. Keep capacity for ordinary
// API requests rather than starting one transaction per room participant.
const MESSAGE_PUSH_CONCURRENCY = 3

function createMessagePushLimiter(concurrency = MESSAGE_PUSH_CONCURRENCY) {
  if (!Number.isInteger(concurrency) || concurrency < 1) throw new TypeError('INVALID_PUSH_CONCURRENCY')
  let active = 0
  let queue = []
  let next = 0
  let scheduled = false

  function schedule() {
    if (scheduled || active >= concurrency || next >= queue.length) return
    scheduled = true
    // Yield between batches, including immediately resolved/suppressed pushes,
    // so a large room fanout cannot monopolize the request loop.
    setImmediate(drain)
  }

  function drain() {
    scheduled = false
    while (active < concurrency && next < queue.length) {
      const { task, resolve, reject } = queue[next++]
      active++
      Promise.resolve().then(task).then(resolve, reject).finally(() => {
        active--
        schedule()
      })
    }
    if (next === queue.length) {
      queue = []
      next = 0
    }
  }

  return function limitMessagePush(task) {
    if (typeof task !== 'function') return Promise.reject(new TypeError('PUSH_TASK_REQUIRED'))
    return new Promise((resolve, reject) => {
      queue.push({ task, resolve, reject })
      schedule()
    })
  }
}

// Shared across direct messages, groups, communities and deletion retries in
// this API process. Waiting items keep their promise; none are dropped.
const limitMessagePush = createMessagePushLimiter()

module.exports = { MESSAGE_PUSH_CONCURRENCY, createMessagePushLimiter, limitMessagePush }
