const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const suffix = crypto.randomUUID()
const ownerEmail = `community-owner-${suffix}@novvin.invalid`
const memberEmail = `community-member-${suffix}@novvin.invalid`
const password = `Community-${suffix}!`
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
      await pool.query(
        `DELETE FROM fallback_room_messages
         WHERE room_kind = 'community' AND lower(room_id) = lower($1)`,
        [roomId],
      )
      await pool.query(
        `DELETE FROM fallback_collection_members
         WHERE room_kind = 'community' AND lower(room_id) = lower($1)`,
        [roomId],
      )
      await pool.query(
        `DELETE FROM fallback_collection_rooms
         WHERE room_kind = 'community' AND lower(id::text) = lower($1)`,
        [roomId],
      )
    }
    for (const email of [ownerEmail, memberEmail]) {
      const { rows } = await pool.query(
        'SELECT id::text FROM fallback_users WHERE email = $1 LIMIT 1',
        [email],
      )
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
    const owner = await register(ownerEmail, 'Propietario ficticio')
    const member = await register(memberEmail, 'Miembro ficticio')

    const created = await jsonRequest('/v1/data/messages/communities', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({
        name: `Comunidad de roles ${suffix}`,
        description: 'Fixture temporal B08-B09',
        category: 'Pruebas',
        isPublic: true,
      }),
    })
    roomId = String(created.body?.id || created.body?.room?.id || '').toLowerCase()
    if (!created.response.ok || !roomId) {
      throw new Error(`Community creation failed: ${created.response.status} ${JSON.stringify(created.body)}`)
    }

    const joined = await jsonRequest(`/v1/data/messages/communities/${roomId}/join`, {
      method: 'POST',
      headers: { authorization: `Bearer ${member.token}` },
      body: '{}',
    })
    if (!joined.response.ok) {
      throw new Error(`Member join failed: ${joined.response.status} ${JSON.stringify(joined.body)}`)
    }

    const moderator = await jsonRequest(`/v1/data/messages/communities/${roomId}/join`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ userId: member.id, role: 'moderator' }),
    })
    if (!moderator.response.ok) {
      throw new Error(`Moderator assignment failed: ${moderator.response.status} ${JSON.stringify(moderator.body)}`)
    }

    const invalidRole = await jsonRequest(`/v1/data/messages/communities/${roomId}/join`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ userId: member.id, role: 'owner' }),
    })
    if (invalidRole.response.status !== 400 || invalidRole.body?.error !== 'ROLE_INVALID') {
      throw new Error(`Owner assignment must be rejected: ${invalidRole.response.status} ${JSON.stringify(invalidRole.body)}`)
    }

    const ownerCatalog = await jsonRequest(`/v1/data/messages/communities/${roomId}/roles`, {
      headers: { authorization: `Bearer ${owner.token}` },
    })
    const roles = ownerCatalog.body?.roles || []
    const moderatorCapabilities = roles.find((role) => role.role === 'moderator')
    if (
      !ownerCatalog.response.ok ||
      ownerCatalog.body?.currentRole !== 'owner' ||
      roles.map((role) => role.role).join(',') !== 'owner,admin,moderator,member' ||
      moderatorCapabilities?.canModerateMessages !== true ||
      moderatorCapabilities?.canManageRoles !== false
    ) {
      throw new Error(`Owner role catalog is invalid: ${JSON.stringify(ownerCatalog.body)}`)
    }

    const memberDenied = await jsonRequest(`/v1/data/messages/communities/${roomId}/roles`, {
      headers: { authorization: `Bearer ${member.token}` },
    })
    if (memberDenied.response.status !== 403) {
      throw new Error(`Moderator must not preview role configuration: ${memberDenied.response.status}`)
    }

    const promoted = await jsonRequest(`/v1/data/messages/communities/${roomId}/join`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ userId: member.id, role: 'admin' }),
    })
    if (!promoted.response.ok) {
      throw new Error(`Admin assignment failed: ${promoted.response.status} ${JSON.stringify(promoted.body)}`)
    }

    const adminCatalog = await jsonRequest(`/v1/data/messages/communities/${roomId}/roles`, {
      headers: { authorization: `Bearer ${member.token}` },
    })
    if (!adminCatalog.response.ok || adminCatalog.body?.currentRole !== 'admin') {
      throw new Error(`Admin role preview failed: ${JSON.stringify(adminCatalog.body)}`)
    }

    console.log(JSON.stringify({
      ok: true,
      predefinedRoles: true,
      ownerRoleImmutable: true,
      moderatorPersisted: true,
      moderatorCannotManageRoles: true,
      ownerPreview: true,
      adminPreview: true,
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
