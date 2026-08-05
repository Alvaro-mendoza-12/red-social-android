const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 1 })
const suffix = crypto.randomUUID()
const password = `Collaboration-${suffix}!`
const hostEmail = `live-host-${suffix}@novvin.invalid`
const guestEmail = `live-guest-${suffix}@novvin.invalid`

async function jsonRequest(path, { token, ...options } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      accept: 'application/json',
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  })
  const text = await response.text()
  let body = {}
  try { body = text ? JSON.parse(text) : {} } catch { body = { error: 'NON_JSON_RESPONSE' } }
  return { response, body }
}

async function register(email, displayName) {
  const result = await jsonRequest('/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, displayName }),
  })
  if (!result.response.ok || !result.body?.accessToken || !result.body?.user?.id) {
    throw new Error(`Fixture registration failed with HTTP ${result.response.status}`)
  }
  return { token: String(result.body.accessToken), id: String(result.body.user.id) }
}

async function cleanup(userIds, liveId) {
  if (liveId) await pool.query('DELETE FROM fallback_live_streams WHERE id = $1::uuid', [liveId])
  if (userIds.length) {
    await pool.query(
      `DELETE FROM fallback_compat_rows
        WHERE lower(row_id) = ANY($1::text[])
           OR lower(COALESCE(row->>'user_id', '')) = ANY($1::text[])`,
      [userIds.map(value => value.toLowerCase())],
    )
  }
  await pool.query('DELETE FROM fallback_users WHERE email = ANY($1::text[])', [[hostEmail, guestEmail]])
}

async function run() {
  const userIds = []
  let liveId = ''
  try {
    const host = await register(hostEmail, 'Host Collaboration E2E')
    const guest = await register(guestEmail, 'Guest Collaboration E2E')
    userIds.push(host.id, guest.id)

    const capabilities = await jsonRequest('/v1/data/live/capabilities', { token: host.token })
    if (!capabilities.response.ok || !capabilities.body?.guestsBattlesEnabled || !capabilities.body?.sfuAvailable) {
      throw new Error(`Live collaboration is not available: HTTP ${capabilities.response.status}`)
    }

    const created = await jsonRequest('/v1/data/live', {
      token: host.token,
      method: 'POST',
      body: JSON.stringify({ title: 'Collaboration E2E', source: 'audio', hashtags: [] }),
    })
    if (created.response.status !== 201 || !created.body?.stream?.id) {
      throw new Error(`Live creation failed with HTTP ${created.response.status}`)
    }
    liveId = String(created.body.stream.id)
    const activated = await jsonRequest(`/v1/data/live/${liveId}/state`, {
      token: host.token,
      method: 'PATCH',
      body: JSON.stringify({ isActive: true }),
    })
    if (!activated.response.ok) throw new Error(`Live activation failed with HTTP ${activated.response.status}`)

    const viewerSfu = await jsonRequest(`/v1/data/live/${liveId}/sfu-token`, {
      token: guest.token,
      method: 'POST',
      body: JSON.stringify({ role: 'viewer' }),
    })
    if (!viewerSfu.response.ok || viewerSfu.body?.role !== 'viewer' || !viewerSfu.body?.token) {
      throw new Error(`Viewer SFU token failed with HTTP ${viewerSfu.response.status}`)
    }

    const invitationNonce = crypto.randomUUID()
    const invited = await jsonRequest(`/v1/data/live/${liveId}/guests/invitations`, {
      token: host.token,
      method: 'POST',
      body: JSON.stringify({ inviteeUserId: guest.id, requestNonce: invitationNonce }),
    })
    if (![200, 201].includes(invited.response.status) || !invited.body?.invitation?.id) {
      throw new Error(`Guest invitation failed with HTTP ${invited.response.status}`)
    }
    const invitationId = String(invited.body.invitation.id)

    const accepted = await jsonRequest(`/v1/data/live/${liveId}/guests/invitations/${invitationId}`, {
      token: guest.token,
      method: 'PATCH',
      body: JSON.stringify({ status: 'accepted' }),
    })
    if (!accepted.response.ok || accepted.body?.invitation?.status !== 'accepted') {
      throw new Error(`Guest acceptance failed with HTTP ${accepted.response.status}`)
    }

    const hostSfu = await jsonRequest(`/v1/data/live/${liveId}/sfu-token`, {
      token: host.token,
      method: 'POST',
      body: JSON.stringify({ role: 'host' }),
    })
    const guestSfu = await jsonRequest(`/v1/data/live/${liveId}/sfu-token`, {
      token: guest.token,
      method: 'POST',
      body: JSON.stringify({ role: 'guest' }),
    })
    if (!hostSfu.response.ok || hostSfu.body?.role !== 'host' || !hostSfu.body?.token) {
      throw new Error(`Host SFU token failed with HTTP ${hostSfu.response.status}`)
    }
    if (!guestSfu.response.ok || guestSfu.body?.role !== 'guest' || !guestSfu.body?.token) {
      throw new Error(`Guest SFU token failed with HTTP ${guestSfu.response.status}`)
    }

    const started = await jsonRequest(`/v1/data/live/${liveId}/battles`, {
      token: host.token,
      method: 'POST',
      body: JSON.stringify({ opponentUserId: guest.id }),
    })
    if (started.response.status !== 201 || started.body?.battle?.status !== 'active') {
      throw new Error(`Battle start failed with HTTP ${started.response.status}`)
    }
    const battleId = String(started.body.battle.id)

    const duplicate = await jsonRequest(`/v1/data/live/${liveId}/battles`, {
      token: host.token,
      method: 'POST',
      body: JSON.stringify({ opponentUserId: guest.id }),
    })
    if (duplicate.response.status !== 409 || duplicate.body?.error !== 'LIVE_BATTLE_ALREADY_ACTIVE') {
      throw new Error(`Duplicate battle was not rejected: HTTP ${duplicate.response.status}`)
    }

    const ended = await jsonRequest(`/v1/data/live/${liveId}/battles/${battleId}/end`, {
      token: host.token,
      method: 'POST',
      body: JSON.stringify({}),
    })
    if (!ended.response.ok || ended.body?.battle?.status !== 'ended') {
      throw new Error(`Battle end failed with HTTP ${ended.response.status}`)
    }

    console.log(JSON.stringify({
      ok: true,
      invitation: 'accepted',
      hostSfuSession: true,
      guestSfuSession: true,
      viewerSfuSession: true,
      battle: 'ended',
      duplicateBattleRejected: true,
      secretsPrinted: false,
    }))
  } finally {
    await cleanup(userIds, liveId)
    await pool.end()
  }
}

run().catch(error => {
  console.error(JSON.stringify({ ok: false, error: error.message }))
  process.exitCode = 1
})
