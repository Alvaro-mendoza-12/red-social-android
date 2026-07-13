import { io } from 'socket.io-client'

const baseUrl = String(process.env.API_URL || '').replace(/\/+$/, '')
const runId = String(Date.now())
const password = 'Conectate123!'

if (!baseUrl.startsWith('https://')) {
  throw new Error('API_URL https es obligatorio')
}

const sockets = []
let roomId = ''
let actor
let recipient

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      'content-type': 'application/json',
      'ngrok-skip-browser-warning': '1',
      ...(options.headers || {}),
    },
    signal: AbortSignal.timeout(20_000),
  })
  const text = await response.text()
  const body = text ? JSON.parse(text) : {}
  if (!response.ok) throw new Error(`${options.method || 'GET'} ${path} -> ${response.status}: ${text}`)
  return body
}

function auth(token) {
  return { authorization: `Bearer ${token}` }
}

function waitFor(socket, event, predicate = () => true, timeoutMs = 12_000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.off(event, handler)
      reject(new Error(`Timeout esperando ${event}`))
    }, timeoutMs)
    const handler = (...args) => {
      if (!predicate(...args)) return
      clearTimeout(timeout)
      socket.off(event, handler)
      resolve(args)
    }
    socket.on(event, handler)
  })
}

async function connect(namespace, session) {
  const socket = io(`${baseUrl}${namespace}`, {
    autoConnect: false,
    forceNew: true,
    reconnection: false,
    transports: ['websocket'],
    auth: { token: session.accessToken },
    extraHeaders: { authorization: `Bearer ${session.accessToken}` },
  })
  sockets.push(socket)
  const connected = waitFor(socket, 'connect')
  const failed = waitFor(socket, 'connect_error').then(([error]) => {
    throw error
  })
  socket.connect()
  await Promise.race([connected, failed])
  return socket
}

async function register(label) {
  return request('/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({
      email: `codex.signaling.${label}.${runId}@example.local`,
      password,
      displayName: `Codex ${label}`,
    }),
  })
}

async function verifyUnauthorizedConnection() {
  const socket = io(baseUrl, {
    autoConnect: false,
    forceNew: true,
    reconnection: false,
    transports: ['websocket'],
  })
  sockets.push(socket)
  const [error] = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Socket sin token no fue rechazado')), 12_000)
    socket.once('connect', () => {
      clearTimeout(timeout)
      reject(new Error('Socket sin token fue aceptado'))
    })
    socket.once('connect_error', (value) => {
      clearTimeout(timeout)
      resolve([value])
    })
    socket.connect()
  })
  return String(error?.message || error)
}

try {
  actor = await register('actor')
  recipient = await register('recipient')

  const createdRoom = await request('/v1/data/voice/rooms', {
    method: 'POST',
    headers: auth(actor.accessToken),
    body: JSON.stringify({
      name: `Codex signaling ${runId}`,
      description: 'Prueba E2E desechable',
      isPublic: false,
      maxParticipants: 4,
    }),
  })
  roomId = createdRoom.room?.id
  if (!roomId) throw new Error('La API no devolvio room.id')

  const voiceA = await connect('', actor)
  const voiceB = await connect('', recipient)
  const joinedA = waitFor(voiceA, 'room-joined', (payload) => payload?.roomId === roomId)
  voiceA.emit('join-room', { roomId, userId: actor.user.id, name: 'Actor' })
  await joinedA
  const joinedB = waitFor(voiceB, 'room-joined', (payload) => payload?.roomId === roomId)
  voiceB.emit('join-room', { roomId, userId: recipient.user.id, name: 'Recipient' })
  await joinedB

  const chatStartedAt = Date.now()
  const chatReceived = waitFor(voiceB, 'chat-message', (payload) => payload?.text === `chat-${runId}`)
  voiceA.emit('chat-message', {
    roomId,
    userId: actor.user.id,
    userName: 'Actor',
    text: `chat-${runId}`,
    timestamp: chatStartedAt,
  })
  const [chatPayload] = await chatReceived
  const voiceChatLatencyMs = Date.now() - chatStartedAt

  const mismatchAuthError = waitFor(voiceB, 'auth-error', (payload) =>
    String(payload?.error || '').toLowerCase().includes('authenticated account')
  )
  voiceB.emit('chat-message', {
    roomId,
    userId: actor.user.id,
    userName: 'Recipient claiming actor',
    text: `mismatch-${runId}`,
    timestamp: Date.now(),
  })
  const [mismatchPayload] = await mismatchAuthError

  const voiceOfferReceived = waitFor(voiceB, 'offer', (payload) => payload?.offer?.sdp === `voice-sdp-${runId}`)
  voiceA.emit('offer', {
    target: voiceB.id,
    userId: actor.user.id,
    offer: { type: 'offer', sdp: `voice-sdp-${runId}` },
  })
  const [voiceOffer] = await voiceOfferReceived

  const callId = `codex-call-${runId}`
  const callA = await connect('/call', actor)
  const callB = await connect('/call', recipient)
  const callJoinedA = waitFor(callA, 'call-joined', (payload) => payload?.callId === callId)
  callA.emit('join-call', { callId, userId: actor.user.id, name: 'Actor' })
  await callJoinedA
  const callJoinedB = waitFor(callB, 'call-joined', (payload) => payload?.callId === callId)
  callB.emit('join-call', { callId, userId: recipient.user.id, name: 'Recipient' })
  await callJoinedB

  const callOfferReceived = waitFor(callB, 'offer', (payload) => payload?.offer?.sdp === `call-sdp-${runId}`)
  callA.emit('offer', {
    target: callB.id,
    userId: actor.user.id,
    offer: { type: 'offer', sdp: `call-sdp-${runId}` },
  })
  const [callOffer] = await callOfferReceived

  const iceReceived = waitFor(callA, 'ice-candidate', (payload) => payload?.candidate?.candidate === `candidate-${runId}`)
  callB.emit('ice-candidate', {
    target: callA.id,
    userId: recipient.user.id,
    candidate: { sdpMid: '0', sdpMLineIndex: 0, candidate: `candidate-${runId}` },
  })
  const [icePayload] = await iceReceived
  const unauthorizedError = await verifyUnauthorizedConnection()

  process.stdout.write(`${JSON.stringify({
    ok: true,
    runId,
    actorUserId: actor.user.id,
    recipientUserId: recipient.user.id,
    roomId,
    voiceChatLatencyMs,
    chatSender: chatPayload.userId,
    mismatchRejected: mismatchPayload.error,
    voiceOfferSender: voiceOffer.sender,
    callOfferSender: callOffer.sender,
    callIceSender: icePayload.sender,
    unauthorizedError,
  }, null, 2)}\n`)
} finally {
  for (const socket of sockets) socket.disconnect()
  if (roomId && actor?.accessToken) {
    await request(`/v1/data/voice/rooms/${encodeURIComponent(roomId)}`, {
      method: 'DELETE',
      headers: auth(actor.accessToken),
    }).catch(() => {})
  }
}
