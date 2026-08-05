const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const suffix = crypto.randomUUID()
const password = `Gift-${suffix}!`
const senderEmail = `gift-sender-${suffix}@novvin.invalid`
const receiverEmail = `gift-receiver-${suffix}@novvin.invalid`
const giftId = '7a100001-0000-4000-8000-000000000001'

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

async function setWallet(userId, balance) {
  const row = {
    id: userId,
    user_id: userId,
    balance_diamonds: balance,
    balance_credits: 0,
    total_spent: 0,
  }
  await pool.query(
    `INSERT INTO fallback_compat_rows(table_name, row_id, row)
     VALUES ('wallets', $1, $2::jsonb)
     ON CONFLICT (table_name, row_id)
     DO UPDATE SET row = EXCLUDED.row, updated_at = NOW()`,
    [userId, JSON.stringify(row)],
  )
}

async function sendGift(token, senderId, receiverId, liveId, idempotencyKey, selectedGiftId = giftId) {
  return jsonRequest('/rest/v1/rpc/send_virtual_gift', {
    token,
    method: 'POST',
    body: JSON.stringify({
      p_sender_id: senderId,
      p_receiver_id: receiverId,
      p_gift_id: selectedGiftId,
      p_live_stream_id: liveId,
      p_idempotency_key: idempotencyKey,
      p_is_public: true,
      p_message: null,
    }),
  })
}

async function ledgerSummary(senderId) {
  const { rows } = await pool.query(
    `SELECT table_name, count(*)::int AS count
       FROM fallback_compat_rows
      WHERE table_name IN ('gift_transactions', 'creator_earnings', 'wallet_transactions')
        AND (
          lower(COALESCE(row->>'sender_id', '')) = lower($1)
          OR lower(COALESCE(row->>'user_id', '')) = lower($1)
        )
      GROUP BY table_name`,
    [senderId],
  )
  return Object.fromEntries(rows.map(row => [row.table_name, row.count]))
}

async function walletBalance(userId) {
  const { rows } = await pool.query(
    `SELECT COALESCE((row->>'balance_diamonds')::int, 0) AS balance
       FROM fallback_compat_rows
      WHERE table_name = 'wallets' AND row_id = $1
      LIMIT 1`,
    [userId],
  )
  return Number(rows[0]?.balance || 0)
}

async function cleanup(senderId, receiverId, liveId) {
  const ids = [senderId, receiverId].filter(Boolean).map(value => value.toLowerCase())
  if (ids.length) {
    await pool.query(
      `DELETE FROM fallback_compat_rows
        WHERE lower(COALESCE(row->>'sender_id', '')) = ANY($1::text[])
           OR lower(COALESCE(row->>'receiver_id', '')) = ANY($1::text[])
           OR lower(COALESCE(row->>'creator_id', '')) = ANY($1::text[])
           OR lower(COALESCE(row->>'user_id', '')) = ANY($1::text[])
           OR lower(row_id) = ANY($1::text[])`,
      [ids],
    )
  }
  if (liveId) await pool.query('DELETE FROM fallback_live_streams WHERE id = $1::uuid', [liveId])
  await pool.query('DELETE FROM fallback_users WHERE email = ANY($1::text[])', [[senderEmail, receiverEmail]])
}

async function run() {
  let senderId = ''
  let receiverId = ''
  let liveId = ''
  try {
    const sender = await register(senderEmail, 'Gift Sender E2E')
    const receiver = await register(receiverEmail, 'Gift Receiver E2E')
    senderId = sender.id
    receiverId = receiver.id
    await setWallet(senderId, 20)

    const createLive = await jsonRequest('/v1/data/live', {
      token: receiver.token,
      method: 'POST',
      body: JSON.stringify({ title: 'Gift E2E', source: 'camera', hashtags: [] }),
    })
    if (createLive.response.status !== 201 || !createLive.body?.stream?.id) {
      throw new Error(`Live creation failed with HTTP ${createLive.response.status}`)
    }
    liveId = String(createLive.body.stream.id)
    const startLive = await jsonRequest(`/v1/data/live/${encodeURIComponent(liveId)}/state`, {
      token: receiver.token,
      method: 'PATCH',
      body: JSON.stringify({ isActive: true }),
    })
    if (!startLive.response.ok || startLive.body?.stream?.is_active !== true) {
      throw new Error(`Live activation failed with HTTP ${startLive.response.status}`)
    }

    const receiptId = crypto.randomUUID()
    const valid = await sendGift(sender.token, senderId, receiverId, liveId, receiptId)
    if (!valid.response.ok || valid.body?.sender_balance_diamonds !== 15) {
      throw new Error(`Valid gift failed with HTTP ${valid.response.status}: ${valid.body?.error || 'INVALID_RECEIPT'}`)
    }
    const duplicate = await sendGift(sender.token, senderId, receiverId, liveId, receiptId)
    if (!duplicate.response.ok || duplicate.body?.idempotent_replay !== true) {
      throw new Error(`Duplicate gift was not idempotent: HTTP ${duplicate.response.status}`)
    }

    const selfGift = await sendGift(sender.token, senderId, senderId, liveId, crypto.randomUUID())
    if (selfGift.response.status !== 409 || selfGift.body?.error !== 'SELF_GIFT_NOT_ALLOWED') {
      throw new Error(`Self gift returned an unexpected result: HTTP ${selfGift.response.status}`)
    }
    const missingGift = await sendGift(
      sender.token,
      senderId,
      receiverId,
      liveId,
      crypto.randomUUID(),
      '7a100099-0000-4000-8000-000000000099',
    )
    if (missingGift.response.status !== 404 || missingGift.body?.error !== 'GIFT_NOT_FOUND') {
      throw new Error(`Missing gift returned an unexpected result: HTTP ${missingGift.response.status}`)
    }

    await jsonRequest(`/v1/data/live/${encodeURIComponent(liveId)}/state`, {
      token: receiver.token,
      method: 'PATCH',
      body: JSON.stringify({ isActive: false }),
    })
    const ended = await sendGift(sender.token, senderId, receiverId, liveId, crypto.randomUUID())
    if (ended.response.status !== 409 || ended.body?.error !== 'LIVE_NOT_ACTIVE') {
      throw new Error(`Ended live returned an unexpected result: HTTP ${ended.response.status}`)
    }
    await jsonRequest(`/v1/data/live/${encodeURIComponent(liveId)}/state`, {
      token: receiver.token,
      method: 'PATCH',
      body: JSON.stringify({ isActive: true }),
    })

    await setWallet(senderId, 5)
    const simultaneous = await Promise.all([
      sendGift(sender.token, senderId, receiverId, liveId, crypto.randomUUID()),
      sendGift(sender.token, senderId, receiverId, liveId, crypto.randomUUID()),
    ])
    const statuses = simultaneous.map(result => result.response.status).sort((a, b) => a - b)
    const rejected = simultaneous.find(result => !result.response.ok)
    if (statuses[0] !== 200 || statuses[1] !== 409 || rejected?.body?.error !== 'INSUFFICIENT_BALANCE') {
      throw new Error(`Concurrent gifts were not serialized: HTTP ${statuses.join(',')}`)
    }

    const summary = await ledgerSummary(senderId)
    const finalBalance = await walletBalance(senderId)
    if (summary.gift_transactions !== 2 || summary.creator_earnings !== 2 || summary.wallet_transactions !== 2) {
      throw new Error('Gift ledger counts are inconsistent')
    }
    if (finalBalance !== 0) throw new Error('Concurrent gift test produced an incorrect balance')

    console.log(JSON.stringify({
      ok: true,
      endpoint: '/rest/v1/rpc/send_virtual_gift',
      validGift: true,
      exactDebit: true,
      idempotentReplay: true,
      selfGiftCode: selfGift.body.error,
      missingGiftCode: missingGift.body.error,
      endedLiveCode: ended.body.error,
      concurrentSerialization: true,
      singleGiftSingleEarning: true,
      finalBalance,
    }))
  } finally {
    await cleanup(senderId, receiverId, liveId).catch(() => {})
    await pool.end()
  }
}

run().catch(error => {
  console.error(String(error?.message || error).replace(/Bearer\s+\S+/gi, 'Bearer [REDACTED]'))
  process.exitCode = 1
})
