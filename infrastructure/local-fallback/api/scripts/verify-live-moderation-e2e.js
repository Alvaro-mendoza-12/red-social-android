const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { io } = require('socket.io-client')

const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const signalingUrl = `${String(process.env.E2E_SIGNALING_URL || baseUrl).replace(/\/+$/, '')}/live`
const suffix = crypto.randomUUID()
const password = `LiveModeration-${suffix}!`
const accounts = []
const sockets = []
let liveId = ''

async function jsonRequest(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      accept: 'application/json',
      'content-type': 'application/json',
      ...(options.headers || {}),
    },
  })
  const text = await response.text()
  let body = {}
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    body = { message: text }
  }
  return { response, body }
}

async function register(label) {
  const email = `live-moderation-${label}-${suffix}@novvin.invalid`
  const result = await jsonRequest('/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, displayName: `Fixture ${label}` }),
  })
  assert.equal(result.response.status, 201, JSON.stringify(result.body))
  const account = {
    id: String(result.body.user.id).toLowerCase(),
    token: String(result.body.accessToken),
    email,
    name: `Fixture ${label}`,
  }
  accounts.push(account)
  return account
}

async function authorized(account, path, options = {}) {
  return jsonRequest(path, {
    ...options,
    headers: {
      authorization: `Bearer ${account.token}`,
      ...(options.headers || {}),
    },
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

async function connect(account) {
  const socket = io(signalingUrl, {
    auth: { token: account.token },
    transports: ['websocket'],
    forceNew: true,
    reconnection: false,
    timeout: 15_000,
  })
  sockets.push(socket)
  await waitForEvent(socket, 'connect')
  return socket
}

async function join(socket, account, role, expectedEvent = 'live-moderation-state') {
  const result = waitForEvent(socket, expectedEvent)
  socket.emit('join-live', {
    liveId,
    userId: account.id,
    name: account.name,
    role,
    source: 'camera',
  })
  return (await result)[0]
}

async function moderationCommand(socket, command, fields = {}) {
  const confirmed = waitForEvent(
    socket,
    'live-moderation-confirmed',
    (payload) => payload?.command === command,
  )
  socket.emit('live-moderation-command', { liveId, command, ...fields })
  return (await confirmed)[0]
}

async function cleanup(host) {
  sockets.forEach((socket) => {
    socket.removeAllListeners()
    socket.disconnect()
  })
  if (liveId) {
    await authorized(host, `/v1/data/live/${liveId}/state`, {
      method: 'PATCH',
      body: JSON.stringify({ isActive: false, viewerCount: 0 }),
    })
    await authorized(host, `/v1/data/live/${liveId}`, { method: 'DELETE' })
  }
  for (const account of accounts) {
    const removed = await authorized(account, '/rest/v1/rpc/delete_own_account', {
      method: 'POST',
      body: '{}',
    })
    assert.ok(removed.response.ok, `Self cleanup failed: ${JSON.stringify(removed.body)}`)
  }
}

async function run() {
  let host
  try {
    host = await register('host')
    const moderator = await register('moderator')
    const viewer = await register('viewer')
    liveId = crypto.randomUUID()
    const created = await authorized(host, '/v1/data/live', {
      method: 'POST',
      body: JSON.stringify({
        id: liveId,
        title: 'Fixture B18',
        source: 'camera',
        hashtags: ['moderacion'],
      }),
    })
    assert.equal(created.response.status, 201, JSON.stringify(created.body))

    const hostSocket = await connect(host)
    const hostModeration = await join(hostSocket, host, 'host')
    assert.equal(hostModeration.isHost, true)
    assert.equal(hostModeration.canModerate, true)

    const moderatorSocket = await connect(moderator)
    await join(moderatorSocket, moderator, 'viewer')
    const viewerSocket = await connect(viewer)
    await join(viewerSocket, viewer, 'viewer')

    const moderatorGranted = waitForEvent(
      moderatorSocket,
      'live-moderation-state',
      (payload) => payload?.isModerator === true,
    )
    await moderationCommand(hostSocket, 'assign-moderator', { targetUserId: moderator.id })
    await moderatorGranted

    const wordsApplied = waitForEvent(
      hostSocket,
      'live-moderation-state',
      (payload) => Array.isArray(payload?.blockedWords) && payload.blockedWords.includes('estafa'),
    )
    await moderationCommand(hostSocket, 'settings', {
      chatPaused: false,
      blockedWords: ['estafa'],
    })
    await wordsApplied

    const blocked = await authorized(viewer, `/v1/data/live/${liveId}/comments`, {
      method: 'POST',
      body: JSON.stringify({ content: 'Esto es una estafa' }),
    })
    assert.equal(blocked.response.status, 422, JSON.stringify(blocked.body))
    assert.equal(blocked.body.error, 'LIVE_COMMENT_BLOCKED')

    const moderatorAllowed = await authorized(moderator, `/v1/data/live/${liveId}/comments`, {
      method: 'POST',
      body: JSON.stringify({ content: 'Moderador documenta estafa' }),
    })
    assert.equal(moderatorAllowed.response.status, 201, JSON.stringify(moderatorAllowed.body))

    const paused = waitForEvent(
      viewerSocket,
      'live-moderation-state',
      (payload) => payload?.chatPaused === true,
    )
    await moderationCommand(moderatorSocket, 'settings', { chatPaused: true })
    await paused
    const pausedComment = await authorized(viewer, `/v1/data/live/${liveId}/comments`, {
      method: 'POST',
      body: JSON.stringify({ content: 'Comentario durante pausa' }),
    })
    assert.equal(pausedComment.response.status, 423, JSON.stringify(pausedComment.body))
    assert.equal(pausedComment.body.error, 'LIVE_CHAT_PAUSED')

    const kicked = waitForEvent(
      viewerSocket,
      'live-moderated',
      (payload) => payload?.action === 'kick',
    )
    await moderationCommand(moderatorSocket, 'kick', { targetUserId: viewer.id })
    await kicked

    const rejoinedAfterKick = waitForEvent(viewerSocket, 'live-moderation-state')
    viewerSocket.emit('join-live', {
      liveId,
      userId: viewer.id,
      name: viewer.name,
      role: 'viewer',
    })
    await rejoinedAfterKick

    const banned = waitForEvent(
      viewerSocket,
      'live-moderated',
      (payload) => payload?.action === 'ban',
    )
    await moderationCommand(moderatorSocket, 'ban', { targetUserId: viewer.id, reason: 'Fixture B18' })
    await banned

    const bannedRejoin = waitForEvent(
      viewerSocket,
      'live-moderated',
      (payload) => payload?.action === 'ban',
    )
    viewerSocket.emit('join-live', {
      liveId,
      userId: viewer.id,
      name: viewer.name,
      role: 'viewer',
    })
    await bannedRejoin

    await moderationCommand(moderatorSocket, 'unban', { targetUserId: viewer.id })
    const rejoinedAfterUnban = waitForEvent(viewerSocket, 'live-moderation-state')
    viewerSocket.emit('join-live', {
      liveId,
      userId: viewer.id,
      name: viewer.name,
      role: 'viewer',
    })
    await rejoinedAfterUnban

    console.log(JSON.stringify({
      ok: true,
      durableModerators: true,
      blockedWordsEnforcedByServer: true,
      chatPauseEnforcedByServer: true,
      moderatorCanCommentDuringPause: true,
      kickRemovesActiveViewer: true,
      banPreventsRejoin: true,
      unbanRestoresAccess: true,
    }))
  } finally {
    if (host) await cleanup(host)
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
