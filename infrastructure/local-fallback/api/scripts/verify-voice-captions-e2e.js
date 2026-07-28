const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Pool } = require('pg')
const { io } = require('../../../../Conectate-web/node_modules/socket.io-client')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const signalingUrl = String(process.env.E2E_SIGNALING_URL || baseUrl).replace(/\/+$/, '')

const pool = databaseUrl ? new Pool({ connectionString: databaseUrl, max: 2 }) : null
const suffix = crypto.randomUUID()
const ownerEmail = `voice-caption-owner-${suffix}@novvin.invalid`
const memberEmail = `voice-caption-member-${suffix}@novvin.invalid`
const lateEmail = `voice-caption-late-${suffix}@novvin.invalid`
const fixtureEmails = [ownerEmail, memberEmail, lateEmail]
const password = `VoiceCaption-${suffix}!`
const roomIds = []
const sockets = []
const fixtureAccounts = []

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
  return { body, response }
}

async function register(email, displayName) {
  const result = await jsonRequest('/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, displayName }),
  })
  assert.equal(result.response.status, 201, JSON.stringify(result.body))
  assert.ok(result.body?.accessToken)
  assert.ok(result.body?.user?.id)
  const account = {
    id: String(result.body.user.id).toLowerCase(),
    token: String(result.body.accessToken),
    email,
    name: displayName,
  }
  fixtureAccounts.push(account)
  return account
}

async function createRoom(account) {
  const id = crypto.randomUUID()
  const result = await jsonRequest('/v1/data/voice/rooms', {
    method: 'POST',
    headers: { authorization: `Bearer ${account.token}` },
    body: JSON.stringify({
      id,
      name: 'Subtítulos B16',
      description: 'Fixture B16 subtítulos consentidos',
      roomMode: 'casual',
      isPublic: true,
    }),
  })
  assert.ok(result.response.ok, JSON.stringify(result.body))
  assert.equal(String(result.body?.room?.id).toLowerCase(), id)
  roomIds.push(id)
  return id
}

async function joinRoomApi(account, roomId) {
  const result = await jsonRequest(`/v1/data/voice/rooms/${roomId}/join`, {
    method: 'POST',
    headers: { authorization: `Bearer ${account.token}` },
    body: JSON.stringify({
      userName: account.name,
      socketId: crypto.randomUUID(),
      isMuted: false,
    }),
  })
  assert.ok(result.response.ok, JSON.stringify(result.body))
}

function connect(account) {
  return new Promise((resolve, reject) => {
    const socket = io(signalingUrl, {
      auth: { token: account.token },
      transports: ['websocket'],
      forceNew: true,
      reconnection: false,
      timeout: 15_000,
    })
    sockets.push(socket)
    const timeout = setTimeout(() => {
      socket.disconnect()
      reject(new Error(`Socket connection timeout for ${account.name}`))
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

async function joinRoom(socket, account, roomId) {
  const joined = waitForEvent(
    socket,
    'room-joined',
    (payload) => String(payload?.roomId).toLowerCase() === roomId,
  )
  socket.emit('join-room', {
    roomId,
    userId: account.id,
    name: account.name,
    isMuted: false,
  })
  await joined
}

async function cleanup() {
  sockets.forEach((socket) => {
    socket.removeAllListeners()
    socket.disconnect()
  })

  if (!pool) {
    for (const account of fixtureAccounts) {
      const removed = await jsonRequest('/rest/v1/rpc/delete_own_account', {
        method: 'POST',
        headers: { authorization: `Bearer ${account.token}` },
        body: '{}',
      })
      assert.ok(removed.response.ok, `Self cleanup failed: ${JSON.stringify(removed.body)}`)

      const login = await jsonRequest('/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email: account.email, password }),
      })
      assert.ok(
        [401, 403, 404].includes(login.response.status),
        `Deleted fixture can still sign in: ${JSON.stringify(login.body)}`,
      )
    }
    console.log(JSON.stringify({
      cleanup: true,
      mode: 'authenticated-self-delete',
      deletedAccounts: fixtureAccounts.length,
    }))
    return
  }

  await pool.query('BEGIN')
  try {
    if (roomIds.length > 0) {
      await pool.query(
        'DELETE FROM fallback_voice_room_participants WHERE lower(room_id) = ANY($1::text[])',
        [roomIds],
      )
      await pool.query(
        'DELETE FROM fallback_voice_rooms WHERE lower(id::text) = ANY($1::text[])',
        [roomIds],
      )
    }
    for (const email of fixtureEmails) {
      const { rows } = await pool.query(
        'SELECT id::text FROM fallback_users WHERE email = $1 LIMIT 1',
        [email],
      )
      if (!rows[0]) continue
      const userId = rows[0].id
      await pool.query('DELETE FROM fallback_notifications WHERE lower(recipient_id) = lower($1)', [userId])
      await pool.query('DELETE FROM fallback_profile_moderation WHERE lower(user_id) = lower($1)', [userId])
      await pool.query('DELETE FROM fallback_profile_overrides WHERE user_id = $1::uuid', [userId])
      await pool.query('DELETE FROM fallback_password_reset_tokens WHERE user_id = $1::uuid', [userId])
      await pool.query('DELETE FROM fallback_users WHERE id = $1::uuid', [userId])
    }
    await pool.query('COMMIT')

    const audit = await pool.query(
      `SELECT
         (SELECT COUNT(*)::int FROM fallback_users
          WHERE email LIKE 'voice-caption-%@novvin.invalid') AS users,
         (SELECT COUNT(*)::int FROM fallback_voice_rooms
          WHERE description LIKE 'Fixture B16%') AS rooms`,
    )
    assert.deepEqual(audit.rows[0], { users: 0, rooms: 0 })
    console.log(JSON.stringify({ cleanup: true, ...audit.rows[0] }))
  } catch (error) {
    await pool.query('ROLLBACK')
    throw error
  }
}

async function run() {
  try {
    const owner = await register(ownerEmail, 'Orador B16')
    const member = await register(memberEmail, 'Oyente B16')
    const late = await register(lateEmail, 'Ingreso tardío B16')
    const roomId = await createRoom(owner)
    await joinRoomApi(owner, roomId)
    await joinRoomApi(member, roomId)
    await joinRoomApi(late, roomId)
    const ownerSocket = await connect(owner)
    const memberSocket = await connect(member)
    await joinRoom(ownerSocket, owner, roomId)
    await joinRoom(memberSocket, member, roomId)

    const denied = waitForEvent(
      ownerSocket,
      'caption-error',
      (payload) => payload?.error === 'VOICE_CAPTION_CONSENT_REQUIRED',
    )
    ownerSocket.emit('live-caption', {
      roomId,
      userId: owner.id,
      captionId: 'without-consent',
      text: 'Esto no debe compartirse',
      isFinal: true,
    })
    await denied

    const ownerConsent = waitForEvent(
      ownerSocket,
      'caption-consent',
      (payload) => payload?.userId === owner.id && payload?.enabled === true,
    )
    const memberSawConsent = waitForEvent(
      memberSocket,
      'caption-consent',
      (payload) => payload?.userId === owner.id && payload?.enabled === true,
    )
    ownerSocket.emit('caption-consent', { roomId, userId: owner.id, enabled: true })
    await Promise.all([ownerConsent, memberSawConsent])

    const expectedText = 'Subtítulo final con espacios normalizados'
    const ownerCaption = waitForEvent(
      ownerSocket,
      'live-caption',
      (payload) => payload?.id === 'caption-final' && payload?.isFinal === true,
    )
    const memberCaption = waitForEvent(
      memberSocket,
      'live-caption',
      (payload) => payload?.id === 'caption-final' && payload?.isFinal === true,
    )
    ownerSocket.emit('live-caption', {
      roomId,
      userId: owner.id,
      captionId: 'caption-final',
      text: '  Subtítulo   final con espacios   normalizados  ',
      isFinal: true,
    })
    const [[canonicalOwner], [canonicalMember]] = await Promise.all([ownerCaption, memberCaption])
    assert.equal(canonicalOwner.text, expectedText)
    assert.equal(canonicalMember.text, expectedText)
    assert.equal(canonicalMember.userName, owner.name)

    const lateSocket = await connect(late)
    const currentParticipants = waitForEvent(
      lateSocket,
      'current-participants',
      (participants) => Array.isArray(participants),
    )
    await joinRoom(lateSocket, late, roomId)
    const [participants] = await currentParticipants
    assert.ok(
      participants.some((participant) => (
        participant.userId === owner.id && participant.captionsEnabled === true
      )),
      'Late joiner did not receive active caption consent state',
    )

    const disabled = waitForEvent(
      memberSocket,
      'caption-consent',
      (payload) => payload?.userId === owner.id && payload?.enabled === false,
    )
    ownerSocket.emit('caption-consent', { roomId, userId: owner.id, enabled: false })
    await disabled

    const deniedAfterDisable = waitForEvent(
      ownerSocket,
      'caption-error',
      (payload) => payload?.error === 'VOICE_CAPTION_CONSENT_REQUIRED',
    )
    ownerSocket.emit('live-caption', {
      roomId,
      userId: owner.id,
      captionId: 'after-disable',
      text: 'Tampoco debe compartirse',
      isFinal: true,
    })
    await deniedAfterDisable

    const summaryWithoutAcceptance = waitForEvent(
      memberSocket,
      'voice-summary-error',
      (payload) => payload?.error === 'VOICE_SUMMARY_ACCEPTANCE_REQUIRED',
    )
    memberSocket.emit('voice-summary-request', {
      roomId,
      requestId: 'summary-without-acceptance',
      accepted: false,
    })
    await summaryWithoutAcceptance

    const summaryResult = waitForEvent(
      memberSocket,
      'voice-summary-result',
      (payload) => payload?.requestId === 'summary-accepted',
    )
    memberSocket.emit('voice-summary-request', {
      roomId,
      requestId: 'summary-accepted',
      accepted: true,
    })
    const [summary] = await summaryResult
    assert.equal(summary.kind, 'extractive')
    assert.equal(summary.segmentCount, 1)
    assert.equal(summary.participantCount, 1)
    assert.deepEqual(summary.bullets, [`${owner.name}: ${expectedText}`])

    console.log(JSON.stringify({
      ok: true,
      consentRequired: true,
      canonicalCaptionBroadcast: true,
      lateJoinConsentState: true,
      disableStopsPublishing: true,
      summaryRequiresAcceptance: true,
      privateExtractiveSummary: true,
      rawAudioStoredByNovvin: false,
    }))
  } finally {
    await cleanup()
    if (pool) await pool.end()
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
