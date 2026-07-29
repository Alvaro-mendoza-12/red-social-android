const crypto = require('crypto')
const { Pool } = require('pg')
const { processBirthdayNotifications } = require('../src/data-routes')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 3 })
const suffix = crypto.randomUUID()
const password = `Birthday-${suffix}!`
const birthdayEmail = `birthday-owner-${suffix}@novvin.invalid`
const friendEmail = `birthday-friend-${suffix}@novvin.invalid`

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
  if (!result.response.ok) {
    throw new Error(`Registration failed: ${result.response.status} ${JSON.stringify(result.body)}`)
  }
  const id = String(result.body?.user?.id || '')
  const accessToken = String(result.body?.accessToken || '')
  if (!id || !accessToken) throw new Error('Registration returned an incomplete session')
  return { id, accessToken }
}

async function cleanup(ids) {
  const normalized = ids.filter(Boolean).map((id) => id.toLowerCase())
  if (normalized.length === 0) return
  await pool.query(
    `DELETE FROM fallback_birthday_notification_deliveries
      WHERE lower(birthday_user_id) = ANY($1::text[])
         OR lower(recipient_id) = ANY($1::text[])`,
    [normalized],
  )
  await pool.query(
    `DELETE FROM fallback_notifications
      WHERE lower(recipient_id) = ANY($1::text[])
         OR lower(COALESCE(actor_id, '')) = ANY($1::text[])`,
    [normalized],
  )
  await pool.query(
    `DELETE FROM fallback_friends
      WHERE lower(sender_id) = ANY($1::text[])
         OR lower(receiver_id) = ANY($1::text[])`,
    [normalized],
  )
  await pool.query(
    `DELETE FROM fallback_users
      WHERE lower(id::text) = ANY($1::text[])
         OR lower(COALESCE(supabase_user_id::text, '')) = ANY($1::text[])`,
    [normalized],
  )
}

async function run() {
  const ids = []
  try {
    const birthdayUser = await register(birthdayEmail, 'Cumpleañera de prueba')
    const friendUser = await register(friendEmail, 'Amistad de prueba')
    ids.push(birthdayUser.id, friendUser.id)

    const today = new Date()
    const localDateParts = Object.fromEntries(
      new Intl.DateTimeFormat('en-CA', {
        timeZone: process.env.BIRTHDAY_TIME_ZONE || 'America/Bogota',
        month: '2-digit',
        day: '2-digit',
      }).formatToParts(today)
        .filter((part) => part.type !== 'literal')
        .map((part) => [part.type, part.value]),
    )
    const birthDate = `2008-${localDateParts.month}-${localDateParts.day}`
    await pool.query(
      `INSERT INTO fallback_profile_overrides(user_id, profile, updated_at)
       VALUES ($1::uuid, $2::jsonb, NOW())
       ON CONFLICT (user_id) DO UPDATE
       SET profile = fallback_profile_overrides.profile || EXCLUDED.profile,
           updated_at = NOW()`,
      [birthdayUser.id, JSON.stringify({ birth_date: birthDate, gender: 'woman' })],
    )
    await pool.query(
      `INSERT INTO fallback_friends(sender_id, receiver_id, status)
       VALUES ($1, $2, 'accepted')
       ON CONFLICT (sender_id, receiver_id) DO UPDATE SET status = 'accepted'`,
      [birthdayUser.id, friendUser.id],
    )

    const firstRun = await processBirthdayNotifications(pool, { now: today, limit: 20 })
    const notifications = await jsonRequest('/v1/data/notifications?limit=50', {
      headers: { authorization: `Bearer ${friendUser.accessToken}` },
    })
    if (!notifications.response.ok) {
      throw new Error(`Notification read failed: ${notifications.response.status} ${JSON.stringify(notifications.body)}`)
    }
    const items = Array.isArray(notifications.body?.items) ? notifications.body.items : []
    const birthdayNotification = items.find((item) =>
      item?.type === 'birthday' &&
      String(item?.actor_id || '').toLowerCase() === birthdayUser.id.toLowerCase()
    )
    if (!birthdayNotification) {
      throw new Error(`Birthday notification was not delivered: ${JSON.stringify(notifications.body)}`)
    }

    const secondRun = await processBirthdayNotifications(pool, { now: today, limit: 20 })
    const duplicateCount = await pool.query(
      `SELECT COUNT(*)::int AS total
         FROM fallback_notifications
        WHERE type = 'birthday'
          AND lower(recipient_id) = lower($1)
          AND lower(actor_id) = lower($2)`,
      [friendUser.id, birthdayUser.id],
    )
    if (Number(duplicateCount.rows[0]?.total) !== 1) {
      throw new Error(`Birthday notification was duplicated: ${duplicateCount.rows[0]?.total}`)
    }

    console.log(JSON.stringify({
      ok: true,
      birthDatePersisted: true,
      acceptedFriendsNotified: true,
      notificationOpensProfile: birthdayNotification.target_type === 'profile',
      duplicateProtection: secondRun.delivered === 0,
      firstRun,
      secondRun,
    }))
  } finally {
    await cleanup(ids)
    await pool.end()
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
