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
  const email = `module-webrtc.${label}.${runId}@example.local`
  const auth = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: { email, password, displayName: `WebRTC ${label}` },
  })
  return {
    email,
    id: auth.user.supabaseUserId || auth.user.id,
    token: auth.accessToken,
  }
}

function connect(namespace, account) {
  return new Promise((resolve, reject) => {
    const socket = io(`${apiUrl}${namespace}`, {
      auth: { token: account.token },
      transports: ['websocket'],
      forceNew: true,
      reconnection: false,
      timeout: 15_000,
    })
    const timeout = setTimeout(() => {
      socket.disconnect()
      reject(new Error(`Socket ${namespace || '/'} connection timeout`))
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

async function verifySignalExchange(first, second, firstAccount, secondAccount) {
  const offer = { type: 'offer', sdp: 'v=0\r\na=ice-options:trickle\r\n' }
  const answer = { type: 'answer', sdp: 'v=0\r\na=ice-options:trickle\r\n' }
  const candidate = {
    sdpMid: '0',
    sdpMLineIndex: 0,
    candidate: 'candidate:1 1 UDP 2122260223 192.0.2.1 54321 typ host',
  }

  const offered = waitForEvent(second, 'offer', (data) => data?.sender === first.id && data?.offer?.sdp === offer.sdp)
  first.emit('offer', { target: second.id, offer, userId: firstAccount.id, name: 'first' })
  await offered

  const answered = waitForEvent(first, 'answer', (data) => data?.sender === second.id && data?.answer?.sdp === answer.sdp)
  second.emit('answer', { target: first.id, answer, userId: secondAccount.id, name: 'second' })
  await answered

  const iced = waitForEvent(second, 'ice-candidate', (data) => (
    data?.sender === first.id && data?.candidate?.candidate === candidate.candidate
  ))
  first.emit('ice-candidate', { target: second.id, candidate, userId: firstAccount.id, name: 'first' })
  await iced
}

async function verifyPairNamespace(namespace, joinEvent, joinedEvent, scopeField, accountA, accountB) {
  let first = await connect(namespace, accountA)
  const second = await connect(namespace, accountB)
  const scopeId = `${namespace.replace('/', '') || 'voice'}-${runId}`
  try {
    const firstJoined = waitForEvent(first, joinedEvent)
    first.emit(joinEvent, { [scopeField]: scopeId, userId: accountA.id, name: 'WebRTC A' })
    await firstJoined

    const secondJoined = waitForEvent(second, joinedEvent)
    const firstSawSecond = waitForEvent(first, 'user-joined', (data) => data?.userId === accountB.id)
    second.emit(joinEvent, { [scopeField]: scopeId, userId: accountB.id, name: 'WebRTC B' })
    await secondJoined
    await firstSawSecond
    await verifySignalExchange(first, second, accountA, accountB)

    const previousSocketId = first.id
    const secondSawDisconnect = waitForEvent(second, 'user-left', (socketId) => socketId === previousSocketId)
    first.disconnect()
    await secondSawDisconnect

    first = await connect(namespace, accountA)
    const rejoined = waitForEvent(first, joinedEvent, (data) => data?.[scopeField] === scopeId)
    const rebuiltParticipants = waitForEvent(
      first,
      'current-participants',
      (participants) => (
        Array.isArray(participants)
        && participants.some((participant) => (
          participant?.socketId === second.id
          && participant?.userId === accountB.id
        ))
      ),
    )
    const secondSawReconnect = waitForEvent(
      second,
      'user-joined',
      (data) => data?.userId === accountA.id && data?.socketId === first.id,
    )
    first.emit(joinEvent, { [scopeField]: scopeId, userId: accountA.id, name: 'WebRTC A reconnected' })
    await rejoined
    await rebuiltParticipants
    await secondSawReconnect
    await verifySignalExchange(first, second, accountA, accountB)
  } finally {
    second.disconnect()
    first.disconnect()
  }
}

async function verifyLive(accountA, accountB) {
  const created = await request('/v1/data/live', {
    token: accountA.token,
    method: 'POST',
    expected: 201,
    body: { title: `WebRTC live ${runId}`, source: 'camera', hashtags: ['webrtc'] },
  })
  const liveId = created.stream.id
  let host = await connect('/live', accountA)
  const viewer = await connect('/live', accountB)
  try {
    const initialCount = waitForEvent(host, 'viewer-count', (data) => data?.liveId === liveId && data?.viewerCount === 0)
    host.emit('join-live', {
      liveId,
      userId: accountA.id,
      name: 'WebRTC host',
      role: 'host',
      source: 'camera',
      isPaused: false,
      videoEnabled: true,
      micEnabled: true,
    })
    await initialCount

    // An authenticated viewer must never be able to replace the real host.
    const rejectedTakeover = waitForEvent(viewer, 'live-ended')
    viewer.emit('join-live', { liveId, userId: accountB.id, name: 'Intruder', role: 'host' })
    await rejectedTakeover

    const hostInfo = waitForEvent(viewer, 'host-info', (data) => data?.userId === accountA.id)
    const viewerJoined = waitForEvent(host, 'viewer-joined', (data) => data?.userId === accountB.id)
    viewer.emit('join-live', { liveId, userId: accountB.id, name: 'WebRTC viewer', role: 'viewer' })
    await hostInfo
    await viewerJoined
    await verifySignalExchange(host, viewer, accountA, accountB)

    const previousHostSocketId = host.id
    host.disconnect()

    host = await connect('/live', accountA)
    assert.notEqual(host.id, previousHostSocketId)
    const restoredViewerList = waitForEvent(
      host,
      'current-viewers',
      (viewers) => (
        Array.isArray(viewers)
        && viewers.some((currentViewer) => (
          currentViewer?.socketId === viewer.id
          && currentViewer?.userId === accountB.id
        ))
      ),
    )
    const viewerSawRestoredHost = waitForEvent(
      viewer,
      'host-info',
      (data) => data?.socketId === host.id && data?.userId === accountA.id,
    )
    host.emit('join-live', {
      liveId,
      userId: accountA.id,
      name: 'WebRTC host reconnected',
      role: 'host',
      source: 'camera',
      isPaused: false,
      videoEnabled: true,
      micEnabled: true,
    })
    await restoredViewerList
    await viewerSawRestoredHost
    await verifySignalExchange(host, viewer, accountA, accountB)

    const ended = waitForEvent(viewer, 'live-ended')
    host.emit('end-live', { liveId, userId: accountA.id })
    await ended
  } finally {
    viewer.disconnect()
    host.disconnect()
    await request(`/v1/data/live/${liveId}`, { token: accountA.token, method: 'DELETE' })
  }
}

async function deleteOwnAccount(account) {
  if (!account?.token) return
  await request('/rest/v1/rpc/delete_own_account', {
    token: account.token,
    method: 'POST',
    body: {},
  })
}

async function main() {
  let accountA
  let accountB
  try {
    accountA = await register('a')
    accountB = await register('b')
    const turn = await request('/turn-credentials', { token: accountA.token })
    const turnUrls = turn.iceServers.flatMap((server) => server.urls || [])
    assert.equal(turn.provider, 'coturn-rest')
    assert.ok(turn.expiresAtEpochSeconds > Math.floor(Date.now() / 1000))
    assert.ok(turnUrls.includes('turns:turn.198-251-72-174.sslip.io:443?transport=tcp'))
    assert.ok(turnUrls.includes('turn:turn.198-251-72-174.sslip.io:3478?transport=udp'))
    assert.ok(turnUrls.includes('turn:turn.198-251-72-174.sslip.io:3478?transport=tcp'))

    await verifyPairNamespace('', 'join-room', 'room-joined', 'roomId', accountA, accountB)
    await verifyPairNamespace('/call', 'join-call', 'call-joined', 'callId', accountA, accountB)
    await verifyLive(accountA, accountB)

    process.stdout.write(JSON.stringify({
      ok: true,
      runId,
      testAccountEmails: [accountA.email, accountB.email],
      turnCredentials: true,
      voiceRoomSignaling: true,
      voiceRoomReconnection: true,
      callSignaling: true,
      callReconnection: true,
      liveHostSignaling: true,
      liveHostReconnection: true,
      liveViewerSignaling: true,
      liveHostTakeoverRejected: true,
    }))
  } finally {
    await deleteOwnAccount(accountB)
    await deleteOwnAccount(accountA)
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
