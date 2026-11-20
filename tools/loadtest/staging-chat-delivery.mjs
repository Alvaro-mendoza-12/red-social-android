import { createRequire } from 'node:module'

const gatewayRequire = createRequire(new URL('../../infrastructure/local-fallback/realtime-gateway/package.json', import.meta.url))

// Each observer represents one recipient device and listens to one chat channel.
// Keep only messages currently awaiting delivery, including events racing the HTTP ACK.
async function observeChat(url, pair) {
  const WebSocket = gatewayRequire('ws')
  const channel = 'chat:' + pair.id
  const ws = new WebSocket(url.replace(/^http/, 'ws'), {
    headers: { Authorization: 'Bearer ' + pair.b.token },
    handshakeTimeout: 5000,
  })
  const pending = new Map()
  let ready = false
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Staging chat subscription timed out')), 5000)
      function failed(message) {
        clearTimeout(timer)
        ready = false
        reject(new Error(message))
        for (const finish of pending.values()) finish(null)
      }
      ws.on('error', () => failed('Staging chat connection error'))
      ws.on('close', code => failed(`Staging chat connection closed (${code})`))
      ws.on('message', raw => {
        let event
        try { event = JSON.parse(raw) } catch { return }
        if (event.type === 'connected' && event.userId === pair.b.id) {
          ws.send(JSON.stringify({ type: 'subscribe', channel }))
        } else if (event.type === 'subscribed' && event.channel === channel) {
          clearTimeout(timer)
          ready = true
          resolve()
        } else if (event.type === 'error' && !ready) {
          const reason = ['FORBIDDEN_CHANNEL', 'SUBSCRIPTION_LIMIT', 'AUTHENTICATION_REQUIRED'].includes(event.error)
            ? event.error : 'GATEWAY_ERROR'
          failed('Staging chat subscription rejected: ' + reason)
        } else if (event.type === 'event' && event.channel === channel) {
          pending.get(event.payload?.message?.id)?.(performance.now())
        }
      })
    })
  } catch (error) {
    ws.terminate()
    throw error
  }
  return {
    expect(id, started) {
      if (!ready) return Promise.resolve(null)
      return new Promise(resolve => {
        function finish(received) {
          clearTimeout(timer)
          pending.delete(id)
          resolve(received === null ? null : received - started)
        }
        const timer = setTimeout(() => finish(null), 3000)
        pending.set(id, finish)
      })
    },
    cancel(id) { pending.get(id)?.(null) },
    close() { ws.terminate() },
  }
}

export async function observeStagingChats(urls, pairs, count) {
  const observers = new Map()
  function close() {
    for (const group of observers.values()) for (const observer of group) observer.close()
  }
  try {
    for (let i = 0; i < count; i++) {
      const pair = pairs[i % pairs.length]
      const observer = await observeChat(urls[i % urls.length], pair)
      if (!observers.has(pair.id)) observers.set(pair.id, [])
      observers.get(pair.id).push(observer)
    }
  } catch (error) {
    close()
    throw error
  }
  return {
    expect(pairId, messageId) {
      const started = performance.now()
      const group = observers.get(pairId) || []
      return {
        received: Promise.all(group.map(observer => observer.expect(messageId, started))),
        cancel() { for (const observer of group) observer.cancel(messageId) },
      }
    },
    close,
  }
}
