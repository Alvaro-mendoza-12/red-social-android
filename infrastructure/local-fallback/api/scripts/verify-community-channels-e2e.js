const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const suffix = crypto.randomUUID()
const ownerEmail = `channel-owner-${suffix}@novvin.invalid`
const moderatorEmail = `channel-moderator-${suffix}@novvin.invalid`
const password = `Channel-${suffix}!`
let roomId = ''

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

async function cleanup() {
  await pool.query('BEGIN')
  try {
    if (roomId) {
      await pool.query('DELETE FROM fallback_room_messages WHERE room_kind = $1 AND lower(room_id) = lower($2)', ['community', roomId])
      await pool.query('DELETE FROM fallback_community_channels WHERE lower(community_id) = lower($1)', [roomId])
      await pool.query('DELETE FROM fallback_collection_members WHERE room_kind = $1 AND lower(room_id) = lower($2)', ['community', roomId])
      await pool.query('DELETE FROM fallback_collection_rooms WHERE room_kind = $1 AND lower(id::text) = lower($2)', ['community', roomId])
    }
    for (const email of [ownerEmail, moderatorEmail]) {
      const { rows } = await pool.query('SELECT id::text FROM fallback_users WHERE email = $1 LIMIT 1', [email])
      if (!rows[0]) continue
      const userId = rows[0].id
      await pool.query('DELETE FROM fallback_profile_moderation WHERE lower(user_id) = lower($1)', [userId])
      await pool.query('DELETE FROM fallback_profile_overrides WHERE user_id = $1::uuid', [userId])
      await pool.query('DELETE FROM fallback_password_reset_tokens WHERE user_id = $1::uuid', [userId])
      await pool.query('DELETE FROM fallback_users WHERE id = $1::uuid', [userId])
    }
    await pool.query('COMMIT')
  } catch (error) {
    await pool.query('ROLLBACK')
    throw error
  }
}

async function run() {
  try {
    const owner = await register(ownerEmail, 'Propietario de canales')
    const moderator = await register(moderatorEmail, 'Moderador de canales')
    const created = await jsonRequest('/v1/data/messages/communities', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({
        name: `Canales ${suffix}`,
        description: 'Fixture temporal B10-B13',
        category: 'Pruebas',
        isPublic: true,
      }),
    })
    roomId = String(created.body?.id || created.body?.room?.id || '').toLowerCase()
    if (!created.response.ok || !roomId) throw new Error(`Community creation failed: ${JSON.stringify(created.body)}`)

    const joined = await jsonRequest(`/v1/data/messages/communities/${roomId}/join`, {
      method: 'POST',
      headers: { authorization: `Bearer ${moderator.token}` },
      body: '{}',
    })
    if (!joined.response.ok) throw new Error(`Moderator join failed: ${JSON.stringify(joined.body)}`)
    const assigned = await jsonRequest(`/v1/data/messages/communities/${roomId}/join`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ userId: moderator.id, role: 'moderator' }),
    })
    if (!assigned.response.ok) throw new Error(`Moderator assignment failed: ${JSON.stringify(assigned.body)}`)

    const initial = await jsonRequest(`/v1/data/messages/communities/${roomId}/channels`, {
      headers: { authorization: `Bearer ${owner.token}` },
    })
    const general = initial.body?.channels?.find((channel) => channel.isDefault)
    if (!initial.response.ok || !general || general.name !== 'General') {
      throw new Error(`Default channel invalid: ${JSON.stringify(initial.body)}`)
    }

    const createdMedia = await jsonRequest(`/v1/data/messages/communities/${roomId}/channels`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ name: 'Multimedia', description: 'Fotos y videos', type: 'media' }),
    })
    const media = createdMedia.body?.channel
    if (!createdMedia.response.ok || media?.type !== 'media') {
      throw new Error(`Media channel creation failed: ${JSON.stringify(createdMedia.body)}`)
    }

    const moderatorCreateDenied = await jsonRequest(`/v1/data/messages/communities/${roomId}/channels`, {
      method: 'POST',
      headers: { authorization: `Bearer ${moderator.token}` },
      body: JSON.stringify({ name: 'No permitido', type: 'text' }),
    })
    if (moderatorCreateDenied.response.status !== 403 || moderatorCreateDenied.body?.error !== 'CHANNEL_MANAGE_DENIED') {
      throw new Error(`Moderator channel creation must be denied: ${JSON.stringify(moderatorCreateDenied.body)}`)
    }

    const paused = await jsonRequest(`/v1/data/messages/communities/${roomId}/channels/${media.id}/pause`, {
      method: 'POST',
      headers: { authorization: `Bearer ${moderator.token}` },
      body: JSON.stringify({ paused: true, minutes: 30, reason: 'Moderación temporal' }),
    })
    if (!paused.response.ok || paused.body?.channel?.isPaused !== true) {
      throw new Error(`Moderator pause failed: ${JSON.stringify(paused.body)}`)
    }

    const blockedMessage = await jsonRequest(`/v1/data/messages/communities/${roomId}/messages`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ channelId: media.id, content: 'Debe bloquearse', contentType: 'text' }),
    })
    if (blockedMessage.response.status !== 423 || blockedMessage.body?.error !== 'CHANNEL_PAUSED') {
      throw new Error(`Paused channel accepted a message: ${JSON.stringify(blockedMessage.body)}`)
    }

    const resumed = await jsonRequest(`/v1/data/messages/communities/${roomId}/channels/${media.id}/pause`, {
      method: 'POST',
      headers: { authorization: `Bearer ${moderator.token}` },
      body: JSON.stringify({ paused: false, pausedUntil: null }),
    })
    if (!resumed.response.ok || resumed.body?.channel?.isPaused !== false) {
      throw new Error(`Channel resume failed: ${JSON.stringify(resumed.body)}`)
    }
    const delivered = await jsonRequest(`/v1/data/messages/communities/${roomId}/messages`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ channelId: media.id, content: 'Mensaje del canal', contentType: 'text' }),
    })
    if (!delivered.response.ok || delivered.body?.message?.channel_id !== media.id) {
      throw new Error(`Channel message was not persisted: ${JSON.stringify(delivered.body)}`)
    }

    const voiceCreated = await jsonRequest(`/v1/data/messages/communities/${roomId}/channels`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ name: 'Voz', type: 'voice' }),
    })
    if (!voiceCreated.response.ok) throw new Error(`Voice channel creation failed: ${JSON.stringify(voiceCreated.body)}`)
    const voiceMessage = await jsonRequest(`/v1/data/messages/communities/${roomId}/messages`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ channelId: voiceCreated.body.channel.id, content: 'No permitido', contentType: 'text' }),
    })
    if (voiceMessage.response.status !== 409 || voiceMessage.body?.error !== 'VOICE_CHANNEL_DOES_NOT_ACCEPT_MESSAGES') {
      throw new Error(`Voice channel accepted a text message: ${JSON.stringify(voiceMessage.body)}`)
    }

    console.log(JSON.stringify({
      ok: true,
      defaultChannel: true,
      channelTypes: true,
      rolePermissions: true,
      moderatorPause: true,
      serverReadOnlyEnforced: true,
      channelMessagePersisted: true,
      voiceChannelProtected: true,
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
