const crypto = require('crypto')
const { Pool } = require('pg')
const { processDueCommunityEventReminders } = require('../src/data-routes')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const suffix = crypto.randomUUID()
const ownerEmail = `profile-event-owner-${suffix}@novvin.invalid`
const memberEmail = `profile-event-member-${suffix}@novvin.invalid`
const password = `ProfileEvent-${suffix}!`
let roomId = ''
let eventId = ''
let memberId = ''

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
    if (eventId) {
      await pool.query('DELETE FROM fallback_community_event_attendees WHERE event_id = $1::uuid', [eventId])
      await pool.query('DELETE FROM fallback_notifications WHERE lower(target_id) = lower($1)', [eventId])
    }
    if (roomId) {
      await pool.query(
        'DELETE FROM fallback_community_event_attendees WHERE event_id IN (SELECT id FROM fallback_community_events WHERE lower(community_id) = lower($1))',
        [roomId],
      )
      await pool.query('DELETE FROM fallback_community_events WHERE lower(community_id) = lower($1)', [roomId])
      await pool.query('DELETE FROM fallback_community_member_profiles WHERE lower(community_id) = lower($1)', [roomId])
      await pool.query('DELETE FROM fallback_community_channels WHERE lower(community_id) = lower($1)', [roomId])
      await pool.query('DELETE FROM fallback_collection_members WHERE room_kind = $1 AND lower(room_id) = lower($2)', ['community', roomId])
      await pool.query('DELETE FROM fallback_collection_rooms WHERE room_kind = $1 AND lower(id::text) = lower($2)', ['community', roomId])
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
          WHERE email LIKE 'profile-event-%@novvin.invalid') AS users,
         (SELECT COUNT(*)::int FROM fallback_collection_rooms
          WHERE description = 'Fixture temporal B11-B12') AS rooms,
         (SELECT COUNT(*)::int FROM fallback_community_member_profiles
          WHERE bio = 'Ciencia dentro de esta comunidad') AS profiles,
         (SELECT COUNT(*)::int FROM fallback_community_events
          WHERE description = 'Validación de asistencia y recordatorios') AS events,
         (SELECT COUNT(*)::int FROM fallback_notifications
          WHERE type = 'community_event_reminder'
            AND content = 'Próximamente: Encuentro B12') AS notifications`,
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
    const owner = await register(ownerEmail, 'Propietario de eventos')
    const member = await register(memberEmail, 'Miembro de eventos')
    memberId = member.id
    const created = await jsonRequest('/v1/data/messages/communities', {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({
        name: `Perfiles y eventos ${suffix}`,
        description: 'Fixture temporal B11-B12',
        category: 'Pruebas',
        isPublic: true,
      }),
    })
    roomId = String(created.body?.id || created.body?.room?.id || '').toLowerCase()
    if (!created.response.ok || !roomId) {
      throw new Error(`Community creation failed: ${JSON.stringify(created.body)}`)
    }

    const joined = await jsonRequest(`/v1/data/messages/communities/${roomId}/join`, {
      method: 'POST',
      headers: { authorization: `Bearer ${member.token}` },
      body: '{}',
    })
    if (!joined.response.ok) throw new Error(`Member join failed: ${JSON.stringify(joined.body)}`)

    const fullProfile = await jsonRequest(`/v1/data/messages/communities/${roomId}/member-profile`, {
      method: 'PATCH',
      headers: { authorization: `Bearer ${member.token}` },
      body: JSON.stringify({
        alias: 'Ada',
        bio: 'Ciencia dentro de esta comunidad',
        distinctive: '🔭',
      }),
    })
    if (!fullProfile.response.ok || fullProfile.body?.profile?.alias !== 'Ada') {
      throw new Error(`Community profile creation failed: ${JSON.stringify(fullProfile.body)}`)
    }

    const aliasOnly = await jsonRequest(`/v1/data/messages/communities/${roomId}/member-profile`, {
      method: 'PATCH',
      headers: { authorization: `Bearer ${member.token}` },
      body: JSON.stringify({ alias: 'Ada Lovelace' }),
    })
    const partiallyUpdatedProfile = aliasOnly.body?.profile
    if (
      !aliasOnly.response.ok
      || partiallyUpdatedProfile?.alias !== 'Ada Lovelace'
      || partiallyUpdatedProfile?.bio !== 'Ciencia dentro de esta comunidad'
      || partiallyUpdatedProfile?.distinctive !== '🔭'
    ) {
      throw new Error(`Partial profile update erased data: ${JSON.stringify(aliasOnly.body)}`)
    }

    const deniedEvent = await jsonRequest(`/v1/data/messages/communities/${roomId}/events`, {
      method: 'POST',
      headers: { authorization: `Bearer ${member.token}` },
      body: JSON.stringify({
        title: 'No permitido',
        startsAt: new Date(Date.now() + 60 * 60_000).toISOString(),
      }),
    })
    if (deniedEvent.response.status !== 403 || deniedEvent.body?.error !== 'EVENT_MANAGE_DENIED') {
      throw new Error(`Member event creation must be denied: ${JSON.stringify(deniedEvent.body)}`)
    }

    const eventCreated = await jsonRequest(`/v1/data/messages/communities/${roomId}/events`, {
      method: 'POST',
      headers: { authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({
        title: 'Encuentro B12',
        description: 'Validación de asistencia y recordatorios',
        startsAt: new Date(Date.now() + 16 * 60_000).toISOString(),
        location: 'Sala de pruebas',
      }),
    })
    eventId = String(eventCreated.body?.event?.id || '').toLowerCase()
    if (!eventCreated.response.ok || !eventId) {
      throw new Error(`Event creation failed: ${JSON.stringify(eventCreated.body)}`)
    }

    const attendance = await jsonRequest(
      `/v1/data/messages/communities/${roomId}/events/${eventId}/attendance`,
      {
        method: 'PUT',
        headers: { authorization: `Bearer ${member.token}` },
        body: JSON.stringify({ status: 'going', reminderMinutes: 15 }),
      },
    )
    if (
      !attendance.response.ok
      || attendance.body?.event?.currentAttendance !== 'going'
      || attendance.body?.event?.attendanceCount !== 1
      || attendance.body?.event?.reminderMinutes !== 15
    ) {
      throw new Error(`Attendance failed: ${JSON.stringify(attendance.body)}`)
    }

    await pool.query(
      `UPDATE fallback_community_events
       SET starts_at = NOW() + INTERVAL '10 minutes'
       WHERE id = $1::uuid`,
      [eventId],
    )
    const firstReminderRun = await processDueCommunityEventReminders(pool)
    const secondReminderRun = await processDueCommunityEventReminders(pool)
    const notificationCount = await pool.query(
      `SELECT COUNT(*)::int AS count
       FROM fallback_notifications
       WHERE lower(recipient_id) = lower($1)
         AND lower(target_id) = lower($2)
         AND type = 'community_event_reminder'`,
      [memberId, eventId],
    )
    if (
      firstReminderRun.processed !== 1
      || secondReminderRun.processed !== 0
      || notificationCount.rows[0]?.count !== 1
    ) {
      throw new Error(
        `Reminder worker is not idempotent: ${JSON.stringify({
          firstReminderRun,
          secondReminderRun,
          notificationCount: notificationCount.rows[0]?.count,
        })}`,
      )
    }

    console.log(JSON.stringify({
      ok: true,
      profilePersistence: true,
      partialProfileUpdate: true,
      eventRolePermissions: true,
      eventAttendance: true,
      reminderDeliveredOnce: true,
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
