const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const suffix = crypto.randomUUID()
const ownerEmail = `voice-stage-owner-${suffix}@novvin.invalid`
const memberEmail = `voice-stage-member-${suffix}@novvin.invalid`
const password = `VoiceStage-${suffix}!`
const roomIds = []

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
  if (!result.response.ok || !result.body?.accessToken || !result.body?.user?.id) {
    throw new Error(`Registration failed: ${result.response.status} ${JSON.stringify(result.body)}`)
  }
  return {
    id: String(result.body.user.id).toLowerCase(),
    token: String(result.body.accessToken),
  }
}

async function createRoom(token, roomMode, description) {
  const id = crypto.randomUUID()
  const result = await jsonRequest('/v1/data/voice/rooms', {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({
      id,
      name: roomMode === 'stage' ? 'Escenario B14' : 'Sala casual B14',
      description,
      roomMode,
      isPublic: true,
    }),
  })
  const roomId = String(result.body?.room?.id || '').toLowerCase()
  if (!result.response.ok || roomId !== id) {
    throw new Error(`Room creation failed: ${JSON.stringify(result.body)}`)
  }
  roomIds.push(roomId)
  return result.body.room
}

async function join(token, roomId, isMuted = false) {
  return jsonRequest(`/v1/data/voice/rooms/${roomId}/join`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ userName: 'Fixture B14', socketId: crypto.randomUUID(), isMuted }),
  })
}

function participant(body, userId) {
  return (body?.participants || []).find(
    (item) => String(item?.user_id || item?.id || '').toLowerCase() === userId,
  )
}

async function cleanup() {
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
    for (const email of [ownerEmail, memberEmail]) {
      const { rows } = await pool.query('SELECT id::text FROM fallback_users WHERE email = $1 LIMIT 1', [email])
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
          WHERE email LIKE 'voice-stage-%@novvin.invalid') AS users,
         (SELECT COUNT(*)::int FROM fallback_voice_rooms
          WHERE description LIKE 'Fixture B14%') AS rooms,
         (SELECT COUNT(*)::int FROM fallback_voice_room_participants
          WHERE lower(room_id) = ANY($1::text[])) AS participants`,
      [roomIds.length > 0 ? roomIds : ['00000000-0000-0000-0000-000000000000']],
    )
    const counts = audit.rows[0] || {}
    if (Object.values(counts).some((value) => Number(value) !== 0)) {
      throw new Error(`E2E cleanup left orphan rows: ${JSON.stringify(counts)}`)
    }
    console.log(JSON.stringify({ cleanup: true, ...counts }))
  } catch (error) {
    await pool.query('ROLLBACK')
    throw error
  }
}

async function run() {
  try {
    const owner = await register(ownerEmail, 'Moderador B14')
    const member = await register(memberEmail, 'Audiencia B14')

    const invalidMode = await jsonRequest('/v1/data/voice/rooms', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ name: 'Inválida', roomMode: 'unknown' }),
    })
    if (invalidMode.response.status !== 400 || invalidMode.body?.error !== 'VOICE_ROOM_MODE_INVALID') {
      throw new Error(`Invalid room mode must be rejected: ${JSON.stringify(invalidMode.body)}`)
    }

    const stageRoom = await createRoom(owner.token, 'stage', 'Fixture B14 escenario')
    if (stageRoom.room_mode !== 'stage') {
      throw new Error(`Stage mode was not persisted: ${JSON.stringify(stageRoom)}`)
    }

    const ownerJoin = await join(owner.token, stageRoom.id)
    const ownerParticipant = participant(ownerJoin.body, owner.id)
    if (!ownerJoin.response.ok || ownerParticipant?.role !== 'moderator' || ownerParticipant?.is_muted) {
      throw new Error(`Creator must join as moderator: ${JSON.stringify(ownerJoin.body)}`)
    }

    const memberJoin = await join(member.token, stageRoom.id)
    const audience = participant(memberJoin.body, member.id)
    if (!memberJoin.response.ok || audience?.role !== 'audience' || audience?.is_muted !== true) {
      throw new Error(`Member must join muted audience: ${JSON.stringify(memberJoin.body)}`)
    }

    const raised = await jsonRequest(`/v1/data/voice/rooms/${stageRoom.id}/stage/request`, {
      method: 'PATCH',
      headers: { authorization: `Bearer ${member.token}` },
      body: JSON.stringify({ handRaised: true }),
    })
    if (!raised.response.ok || participant(raised.body, member.id)?.hand_raised !== true) {
      throw new Error(`Raise hand failed: ${JSON.stringify(raised.body)}`)
    }

    const selfPromotion = await jsonRequest(
      `/v1/data/voice/rooms/${stageRoom.id}/stage/participants/${member.id}`,
      {
        method: 'PATCH',
        headers: { authorization: `Bearer ${member.token}` },
        body: JSON.stringify({ role: 'speaker' }),
      },
    )
    if (
      selfPromotion.response.status !== 403
      || selfPromotion.body?.error !== 'VOICE_STAGE_MODERATION_DENIED'
    ) {
      throw new Error(`Audience self-promotion must be denied: ${JSON.stringify(selfPromotion.body)}`)
    }

    const promoted = await jsonRequest(
      `/v1/data/voice/rooms/${stageRoom.id}/stage/participants/${member.id}`,
      {
        method: 'PATCH',
        headers: { authorization: `Bearer ${owner.token}` },
        body: JSON.stringify({ role: 'speaker' }),
      },
    )
    const speaker = participant(promoted.body, member.id)
    if (!promoted.response.ok || speaker?.role !== 'speaker' || speaker?.hand_raised) {
      throw new Error(`Promotion failed: ${JSON.stringify(promoted.body)}`)
    }

    const speakerCannotRaise = await jsonRequest(`/v1/data/voice/rooms/${stageRoom.id}/stage/request`, {
      method: 'PATCH',
      headers: { authorization: `Bearer ${member.token}` },
      body: JSON.stringify({ handRaised: true }),
    })
    if (
      speakerCannotRaise.response.status !== 409
      || speakerCannotRaise.body?.error !== 'VOICE_STAGE_ALREADY_SPEAKER'
    ) {
      throw new Error(`Speaker raise hand must be rejected: ${JSON.stringify(speakerCannotRaise.body)}`)
    }

    const muted = await jsonRequest(
      `/v1/data/voice/rooms/${stageRoom.id}/stage/participants/${member.id}`,
      {
        method: 'PATCH',
        headers: { authorization: `Bearer ${owner.token}` },
        body: JSON.stringify({ isMuted: true }),
      },
    )
    if (!muted.response.ok || participant(muted.body, member.id)?.is_muted !== true) {
      throw new Error(`Moderator mute failed: ${JSON.stringify(muted.body)}`)
    }

    const demoted = await jsonRequest(
      `/v1/data/voice/rooms/${stageRoom.id}/stage/participants/${member.id}`,
      {
        method: 'PATCH',
        headers: { authorization: `Bearer ${owner.token}` },
        body: JSON.stringify({ role: 'audience' }),
      },
    )
    const backInAudience = participant(demoted.body, member.id)
    if (!demoted.response.ok || backInAudience?.role !== 'audience' || backInAudience?.is_muted !== true) {
      throw new Error(`Demotion failed: ${JSON.stringify(demoted.body)}`)
    }

    const casualRoom = await createRoom(owner.token, 'casual', 'Fixture B14 casual')
    const casualJoin = await join(member.token, casualRoom.id)
    const casualParticipant = participant(casualJoin.body, member.id)
    if (
      casualRoom.room_mode !== 'casual'
      || !casualJoin.response.ok
      || casualParticipant?.role !== 'speaker'
      || casualParticipant?.is_muted
    ) {
      throw new Error(`Casual room behavior regressed: ${JSON.stringify({ casualRoom, body: casualJoin.body })}`)
    }

    console.log(JSON.stringify({
      ok: true,
      casualModePreserved: true,
      audienceStartsMuted: true,
      selfPromotionDenied: true,
      requestToSpeak: true,
      moderatorControls: true,
    }))
  } finally {
    await cleanup()
    await pool.end()
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
