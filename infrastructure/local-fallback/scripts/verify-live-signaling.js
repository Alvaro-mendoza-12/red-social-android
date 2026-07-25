const assert = require('node:assert/strict')
const { io } = require('../../../Conectate-web/node_modules/socket.io-client')

const apiUrl = String(process.argv[2] || process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const runId = Date.now().toString()
const password = 'Conectate123!'

async function request(path, { token, method = 'GET', body, expected = 200 } = {}) {
  const response = await fetch(`${apiUrl}${path}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body != null ? { 'content-type': 'application/json' } : {}),
    },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `${method} ${path}: ${response.status} ${text}`)
  return payload
}

async function register(label) {
  const auth = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: {
      email: `module-live-socket.${label}.${runId}@example.local`,
      password,
      displayName: `Live socket ${label}`,
    },
  })
  return {
    id: auth.user.supabaseUserId || auth.user.id,
    token: auth.accessToken,
  }
}

function connectLive(account) {
  return new Promise((resolve, reject) => {
    const socket = io(`${apiUrl}/live`, {
      auth: { token: account.token },
      transports: ['websocket'],
      forceNew: true,
      reconnection: false,
      timeout: 15_000,
    })
    const timeout = setTimeout(() => {
      socket.disconnect()
      reject(new Error('Socket /live connection timeout'))
    }, 20_000)
    socket.once('connect', () => {
      clearTimeout(timeout)
      resolve(socket)
    })
    socket.once('connect_error', (error) => {
      clearTimeout(timeout)
      socket.disconnect()
      reject(error)
    })
  })
}

function waitForEvent(socket, eventName, predicate = () => true, timeoutMs = 15_000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.off(eventName, listener)
      reject(new Error(`Socket event timeout: ${eventName}`))
    }, timeoutMs)
    const listener = (...args) => {
      if (!predicate(...args)) return
      clearTimeout(timeout)
      socket.off(eventName, listener)
      resolve(args)
    }
    socket.on(eventName, listener)
  })
}

async function waitUntil(check, label, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      const value = await check()
      if (value) return value
    } catch (error) {
      lastError = error
    }
    await new Promise((resolve) => setTimeout(resolve, 350))
  }
  throw new Error(`${label} timeout${lastError ? `: ${lastError.message}` : ''}`)
}

async function main() {
  const owner = await register('owner')
  const viewer = await register('viewer')
  const created = await request('/v1/data/live', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: { title: `Socket live ${runId}`, source: 'camera', hashtags: ['socket'] },
  })
  const liveId = created.stream.id
  let hostSocket
  let viewerSocket

  try {
    hostSocket = await connectLive(owner)
    const hostCount = waitForEvent(
      hostSocket,
      'viewer-count',
      (payload) => payload?.liveId === liveId && payload?.viewerCount === 0,
    )
    hostSocket.emit('join-live', {
      liveId,
      userId: owner.id,
      name: 'Live socket owner',
      role: 'host',
      source: 'camera',
      isPaused: false,
      videoEnabled: true,
      micEnabled: true,
    })
    await hostCount

    await waitUntil(async () => {
      const detail = await request(`/v1/data/live/${liveId}`, { token: owner.token })
      return detail.stream.is_active === true
    }, 'Signaling activation')
    const presence = await request(`/live-active-ids?ids=${encodeURIComponent(liveId)}`)
    assert.ok(presence.liveIds.includes(liveId))

    viewerSocket = await connectLive(viewer)
    const hostInfo = waitForEvent(viewerSocket, 'host-info')
    const viewerCount = waitForEvent(
      hostSocket,
      'viewer-count',
      (payload) => payload?.liveId === liveId && payload?.viewerCount === 1,
    )
    viewerSocket.emit('join-live', {
      liveId,
      userId: viewer.id,
      name: 'Live socket viewer',
      role: 'viewer',
    })
    await hostInfo
    await viewerCount
    await waitUntil(async () => {
      const detail = await request(`/v1/data/live/${liveId}`, { token: owner.token })
      return detail.stream.viewer_count === 1
    }, 'Viewer count persistence')

    const reaction = waitForEvent(hostSocket, 'reaction', (payload) => payload?.emoji === '🔥')
    viewerSocket.emit('send-reaction', { liveId, userId: viewer.id, emoji: '🔥' })
    await reaction

    const liveEnded = waitForEvent(viewerSocket, 'live-ended')
    hostSocket.emit('end-live', { liveId, userId: owner.id })
    await liveEnded
    await waitUntil(async () => {
      const detail = await request(`/v1/data/live/${liveId}`, { token: owner.token })
      return detail.stream.is_active === false && detail.stream.viewer_count === 0
    }, 'Signaling termination')
  } finally {
    viewerSocket?.disconnect()
    hostSocket?.disconnect()
  }

  await request(`/v1/data/live/${liveId}`, { token: owner.token, method: 'DELETE' })
  process.stdout.write(JSON.stringify({
    ok: true,
    websocketAuthenticated: true,
    signalingActivatedPersistence: true,
    viewerCountPersisted: true,
    reactionsDelivered: true,
    signalingEndedPersistence: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
