const crypto = require('crypto')
const jwt = require('jsonwebtoken')

const POINTS_FLAG = 'LIVE_CHANNEL_POINTS_ENABLED'
const DEFAULT_INTERVAL_SECONDS = 300
const DEFAULT_POINTS_PER_INTERVAL = 10
const MAX_SESSION_SECONDS = 2 * 60 * 60

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function cleanText(value, maxLength) {
  return String(value || '').trim().slice(0, maxLength)
}

function validUuid(value) {
  const text = normalizeId(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function intBetween(value, min, max, fallback = min) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, parsed))
}

function dailyAwardAmount({ earnedToday, dailyCap, pointsPerInterval }) {
  const earned = Math.max(0, Number(earnedToday || 0))
  const cap = Math.max(0, Number(dailyCap || 0))
  const interval = Math.max(0, Number(pointsPerInterval || 0))
  return Math.max(0, Math.min(interval, cap - earned))
}

function rewardCooldownRemainingSeconds({ lastRedeemedAt, cooldownSeconds, now = Date.now() }) {
  const cooldown = Math.max(0, Number(cooldownSeconds || 0))
  if (!lastRedeemedAt || cooldown <= 0) return 0
  const last = new Date(lastRedeemedAt).getTime()
  if (!Number.isFinite(last)) return 0
  return Math.max(0, Math.ceil((last + cooldown * 1000 - Number(now)) / 1000))
}

function pointsEnabled() {
  return /^(1|true|yes|on)$/i.test(String(process.env[POINTS_FLAG] || '').trim())
}

function pointsSecret() {
  return String(process.env.LIVE_POINTS_SESSION_SECRET || '').trim()
}

function requirePointsFeature(res) {
  if (pointsEnabled()) return true
  res.status(404).json({ error: 'LIVE_CHANNEL_POINTS_DISABLED' })
  return false
}

function hashToken(token) {
  return crypto.createHash('sha256').update(String(token || '')).digest('hex')
}

function issueSessionToken({ liveId, userId, sessionNonce, now = Math.floor(Date.now() / 1000) }) {
  const secret = pointsSecret()
  if (!secret) return null
  return jwt.sign(
    {
      iss: 'novvin-live-points',
      sub: normalizeId(userId),
      liveId: normalizeId(liveId),
      sessionNonce,
      iat: now,
      nbf: now - 5,
      exp: now + MAX_SESSION_SECONDS,
    },
    secret,
    { algorithm: 'HS256' },
  )
}

async function ensureLivePointsTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_point_settings (
      live_id UUID PRIMARY KEY REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      creator_id TEXT NOT NULL,
      enabled BOOLEAN NOT NULL DEFAULT TRUE,
      interval_seconds INT NOT NULL DEFAULT ${DEFAULT_INTERVAL_SECONDS}
        CHECK (interval_seconds BETWEEN 60 AND 3600),
      points_per_interval INT NOT NULL DEFAULT ${DEFAULT_POINTS_PER_INTERVAL}
        CHECK (points_per_interval BETWEEN 1 AND 1000),
      daily_cap INT NOT NULL DEFAULT 2000 CHECK (daily_cap BETWEEN 1 AND 100000),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_channel_points_balances (
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      creator_id TEXT NOT NULL,
      viewer_id TEXT NOT NULL,
      balance INT NOT NULL DEFAULT 0 CHECK (balance >= 0),
      earned_total BIGINT NOT NULL DEFAULT 0 CHECK (earned_total >= 0),
      redeemed_total BIGINT NOT NULL DEFAULT 0 CHECK (redeemed_total >= 0),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (live_id, viewer_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_channel_points_earnings (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      viewer_id TEXT NOT NULL,
      event_type TEXT NOT NULL DEFAULT 'watch_interval',
      interval_id TEXT NOT NULL,
      points INT NOT NULL CHECK (points > 0),
      idempotency_key TEXT NOT NULL,
      occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (live_id, viewer_id, event_type, interval_id),
      UNIQUE (idempotency_key)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_channel_point_rewards (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      creator_id TEXT NOT NULL,
      title TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      cost INT NOT NULL CHECK (cost > 0),
      active BOOLEAN NOT NULL DEFAULT TRUE,
      stock INT CHECK (stock IS NULL OR stock >= 0),
      cooldown_seconds INT NOT NULL DEFAULT 0 CHECK (cooldown_seconds BETWEEN 0 AND 86400),
      version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_channel_point_rewards_live
    ON fallback_channel_point_rewards(live_id, active, cost, created_at)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_channel_point_sessions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      viewer_id TEXT NOT NULL,
      session_nonce TEXT NOT NULL UNIQUE,
      token_hash TEXT NOT NULL UNIQUE,
      device_hash TEXT,
      joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_rewarded_interval INT NOT NULL DEFAULT 0 CHECK (last_rewarded_interval >= 0),
      state TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'ended', 'expired')),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_points_active_session
    ON fallback_channel_point_sessions(live_id, lower(viewer_id))
    WHERE state = 'active'
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_channel_point_redemptions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      reward_id UUID NOT NULL REFERENCES fallback_channel_point_rewards(id) ON DELETE CASCADE,
      viewer_id TEXT NOT NULL,
      cost INT NOT NULL CHECK (cost > 0),
      status TEXT NOT NULL DEFAULT 'requested'
        CHECK (status IN ('requested', 'approved', 'rejected', 'completed', 'canceled')),
      request_nonce TEXT NOT NULL,
      refunded_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (live_id, request_nonce)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_points_redemptions_live
    ON fallback_channel_point_redemptions(live_id, status, created_at DESC)
  `)
}

async function liveForUser(pool, liveId, user, userIdentity) {
  const { rows } = await pool.query(
    `SELECT id::text, host_user_id, is_active, title
     FROM fallback_live_streams WHERE id = $1::uuid LIMIT 1`,
    [liveId],
  )
  const live = rows[0]
  if (!live) return { error: 'LIVE_NOT_FOUND' }
  const identity = userIdentity(user)
  return { live, identity, isHost: identity.ids.includes(normalizeId(live.host_user_id)) }
}

async function pointSettings(pool, liveId, creatorId) {
  const { rows } = await pool.query(
    `INSERT INTO fallback_live_point_settings(live_id, creator_id)
     VALUES ($1::uuid, $2)
     ON CONFLICT (live_id) DO UPDATE SET creator_id = EXCLUDED.creator_id
     RETURNING live_id::text, creator_id, enabled, interval_seconds, points_per_interval, daily_cap`,
    [liveId, creatorId],
  )
  return rows[0]
}

function decodeSessionToken(token, liveId, userId) {
  const secret = pointsSecret()
  if (!secret) return { error: 'LIVE_POINTS_NOT_CONFIGURED' }
  try {
    const claims = jwt.verify(token, secret, { algorithms: ['HS256'] })
    if (
      normalizeId(claims?.liveId) !== normalizeId(liveId) ||
      normalizeId(claims?.sub) !== normalizeId(userId) ||
      !cleanText(claims?.sessionNonce, 160)
    ) return { error: 'LIVE_POINTS_SESSION_INVALID' }
    return { claims }
  } catch (_error) {
    return { error: 'LIVE_POINTS_SESSION_INVALID' }
  }
}

function responseReward(row) {
  if (!row) return null
  return {
    id: row.id,
    live_id: row.live_id,
    creator_id: row.creator_id,
    title: row.title,
    description: row.description,
    cost: Number(row.cost || 0),
    active: Boolean(row.active),
    stock: row.stock === null || row.stock === undefined ? null : Number(row.stock),
    cooldown_seconds: Number(row.cooldown_seconds || 0),
    version: Number(row.version || 1),
    created_at: row.created_at,
    updated_at: row.updated_at,
  }
}

function registerLivePointsRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  app.post('/v1/data/live/:liveId/points/session', authRequired, async (req, res, next) => {
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      if (!access.live.is_active) return res.status(409).json({ error: 'LIVE_NOT_ACTIVE' })
      if (access.isHost) return res.status(403).json({ error: 'LIVE_POINTS_VIEWER_REQUIRED' })
      if (!pointsSecret()) return res.status(503).json({ error: 'LIVE_POINTS_NOT_CONFIGURED' })
      const settings = await pointSettings(pool, liveId, access.live.host_user_id)
      if (!settings.enabled) return res.status(409).json({ error: 'LIVE_POINTS_DISABLED_FOR_LIVE' })
      const sessionNonce = crypto.randomUUID()
      const token = issueSessionToken({
        liveId,
        userId: access.identity.displayUserId,
        sessionNonce,
      })
      if (!token) return res.status(503).json({ error: 'LIVE_POINTS_NOT_CONFIGURED' })
      const deviceId = cleanText(req.body?.deviceId || req.body?.device_id, 160)
      const deviceHash = deviceId ? hashToken(deviceId) : null
      await pool.query(
        `UPDATE fallback_channel_point_sessions
         SET state = 'ended', updated_at = NOW()
         WHERE live_id = $1::uuid AND lower(viewer_id) = lower($2) AND state = 'active'`,
        [liveId, access.identity.displayUserId],
      )
      await pool.query(
        `INSERT INTO fallback_channel_point_sessions(
           live_id, viewer_id, session_nonce, token_hash, device_hash
         ) VALUES ($1::uuid, $2, $3, $4, $5)`,
        [liveId, access.identity.displayUserId, sessionNonce, hashToken(token), deviceHash],
      )
      res.setHeader('Cache-Control', 'no-store')
      res.status(201).json({
        sessionToken: token,
        sessionNonce,
        intervalSeconds: Number(settings.interval_seconds),
        pointsPerInterval: Number(settings.points_per_interval),
        dailyCap: Number(settings.daily_cap),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/points/heartbeat', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      const token = cleanText(req.body?.sessionToken || req.body?.session_token, 4000)
      const decoded = decodeSessionToken(token, liveId, access.identity.displayUserId)
      if (decoded.error) return res.status(401).json({ error: decoded.error })
      client = await pool.connect()
      await client.query('BEGIN')
      const sessionResult = await client.query(
        `SELECT id::text, live_id::text, viewer_id, session_nonce,
                joined_at, last_heartbeat_at, last_rewarded_interval, state
         FROM fallback_channel_point_sessions
         WHERE live_id = $1::uuid AND lower(viewer_id) = lower($2)
           AND session_nonce = $3 AND token_hash = $4 AND state = 'active'
         FOR UPDATE`,
        [liveId, access.identity.displayUserId, decoded.claims.sessionNonce, hashToken(token)],
      )
      const session = sessionResult.rows[0]
      if (!session) {
        await client.query('ROLLBACK')
        return res.status(401).json({ error: 'LIVE_POINTS_SESSION_INVALID' })
      }
      const settingsResult = await client.query(
        `SELECT enabled, interval_seconds, points_per_interval, daily_cap
         FROM fallback_live_point_settings WHERE live_id = $1::uuid FOR UPDATE`,
        [liveId],
      )
      const settings = settingsResult.rows[0]
      if (!settings?.enabled) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_POINTS_DISABLED_FOR_LIVE' })
      }
      const visible = req.body?.visible !== false
      const foreground = req.body?.foreground !== false
      await client.query(
        `UPDATE fallback_channel_point_sessions
         SET last_heartbeat_at = NOW(), updated_at = NOW()
         WHERE id = $1::uuid`,
        [session.id],
      )
      if (!visible || !foreground || !access.live.is_active) {
        await client.query('COMMIT')
        return res.json({ awardedPoints: 0, balance: null, eligible: false, serverTime: Date.now() })
      }
      const elapsedSeconds = Math.max(0, Math.floor((Date.now() - new Date(session.joined_at).getTime()) / 1000))
      const targetInterval = Math.floor(elapsedSeconds / Number(settings.interval_seconds))
      if (targetInterval <= Number(session.last_rewarded_interval)) {
        await client.query('COMMIT')
        return res.json({ awardedPoints: 0, balance: null, eligible: true, serverTime: Date.now() })
      }
      const intervalId = String(Number(session.last_rewarded_interval) + 1)
      const idempotencyKey = `${session.session_nonce}:${intervalId}`
      const dailyResult = await client.query(
        `SELECT COALESCE(SUM(points), 0)::bigint AS earned_today
         FROM fallback_channel_points_earnings
         WHERE live_id = $1::uuid AND lower(viewer_id) = lower($2)
           AND occurred_at >= date_trunc('day', NOW() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'`,
        [liveId, access.identity.displayUserId],
      )
      const awardedPoints = dailyAwardAmount({
        earnedToday: dailyResult.rows[0]?.earned_today,
        dailyCap: settings.daily_cap,
        pointsPerInterval: settings.points_per_interval,
      })
      if (awardedPoints <= 0) {
        await client.query(
          `UPDATE fallback_channel_point_sessions
           SET last_rewarded_interval = $2, updated_at = NOW()
           WHERE id = $1::uuid`,
          [session.id, Number(intervalId)],
        )
        await client.query('COMMIT')
        return res.json({ awardedPoints: 0, balance: null, eligible: false, dailyCapReached: true, serverTime: Date.now() })
      }
      const earning = await client.query(
        `INSERT INTO fallback_channel_points_earnings(
           live_id, viewer_id, event_type, interval_id, points, idempotency_key
         ) VALUES ($1::uuid, $2, 'watch_interval', $3, $4, $5)
         ON CONFLICT (live_id, viewer_id, event_type, interval_id) DO NOTHING
         RETURNING id::text`,
        [liveId, access.identity.displayUserId, intervalId, awardedPoints, idempotencyKey],
      )
      if (!earning.rows[0]) {
        await client.query('COMMIT')
        return res.json({ awardedPoints: 0, balance: null, eligible: true, serverTime: Date.now() })
      }
      const balanceResult = await client.query(
        `INSERT INTO fallback_channel_points_balances(
           live_id, creator_id, viewer_id, balance, earned_total, updated_at
         ) VALUES ($1::uuid, $2, $3, $4, $4, NOW())
         ON CONFLICT (live_id, viewer_id)
         DO UPDATE SET balance = fallback_channel_points_balances.balance + EXCLUDED.balance,
                       earned_total = fallback_channel_points_balances.earned_total + EXCLUDED.earned_total,
                       updated_at = NOW()
         RETURNING balance`,
        [liveId, access.live.host_user_id, access.identity.displayUserId, awardedPoints],
      )
      await client.query(
        `UPDATE fallback_channel_point_sessions
         SET last_rewarded_interval = $2, updated_at = NOW()
         WHERE id = $1::uuid`,
        [session.id, Number(intervalId)],
      )
      await client.query('COMMIT')
      res.json({
        awardedPoints,
        balance: Number(balanceResult.rows[0]?.balance || 0),
        eligible: true,
        serverTime: Date.now(),
      })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.patch('/v1/data/live/:liveId/points/settings', authRequired, async (req, res, next) => {
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      if (!access.isHost) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      const current = await pointSettings(pool, liveId, access.live.host_user_id)
      const enabled = req.body?.enabled === undefined ? Boolean(current.enabled) : req.body.enabled === true
      const intervalSeconds = intBetween(
        req.body?.intervalSeconds ?? req.body?.interval_seconds,
        60,
        3600,
        Number(current.interval_seconds),
      )
      const pointsPerInterval = intBetween(
        req.body?.pointsPerInterval ?? req.body?.points_per_interval,
        1,
        1000,
        Number(current.points_per_interval),
      )
      const dailyCap = intBetween(
        req.body?.dailyCap ?? req.body?.daily_cap,
        1,
        100000,
        Number(current.daily_cap),
      )
      const { rows } = await pool.query(
        `UPDATE fallback_live_point_settings
         SET enabled = $2, interval_seconds = $3, points_per_interval = $4,
             daily_cap = $5, updated_at = NOW()
         WHERE live_id = $1::uuid
         RETURNING live_id::text, creator_id, enabled, interval_seconds, points_per_interval, daily_cap`,
        [liveId, enabled, intervalSeconds, pointsPerInterval, dailyCap],
      )
      res.json({ settings: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/points/balance', authRequired, async (req, res, next) => {
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      const settings = await pointSettings(pool, liveId, access.live.host_user_id)
      const { rows } = await pool.query(
        `SELECT live_id::text, creator_id, viewer_id, balance, earned_total, redeemed_total, updated_at
         FROM fallback_channel_points_balances
         WHERE live_id = $1::uuid AND lower(viewer_id) = lower($2)
         LIMIT 1`,
        [liveId, access.identity.displayUserId],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({
        balance: rows[0] || {
          live_id: liveId,
          creator_id: access.live.host_user_id,
          viewer_id: access.identity.displayUserId,
          balance: 0,
          earned_total: 0,
          redeemed_total: 0,
        },
        settings,
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/points/rewards', authRequired, async (req, res, next) => {
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      const { rows } = await pool.query(
        `SELECT id::text, live_id::text, creator_id, title, description, cost, active,
                stock, cooldown_seconds, version, created_at, updated_at
         FROM fallback_channel_point_rewards
         WHERE live_id = $1::uuid AND (active = TRUE OR lower(creator_id) = ANY($2::text[]))
         ORDER BY cost ASC, created_at ASC`,
        [liveId, access.identity.values],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ rewards: rows.map(responseReward) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/points/rewards', authRequired, async (req, res, next) => {
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      if (!access.isHost) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      const title = cleanText(req.body?.title, 80)
      const description = cleanText(req.body?.description, 240)
      const cost = intBetween(req.body?.cost, 1, 1_000_000, 0)
      if (!title || cost <= 0) return res.status(400).json({ error: 'LIVE_POINT_REWARD_INVALID' })
      const stockValue = req.body?.stock === null || req.body?.stock === undefined || req.body?.stock === ''
        ? null
        : intBetween(req.body.stock, 0, 1_000_000, -1)
      if (stockValue !== null && stockValue < 0) return res.status(400).json({ error: 'LIVE_POINT_REWARD_STOCK_INVALID' })
      const cooldownSeconds = intBetween(req.body?.cooldownSeconds ?? req.body?.cooldown_seconds, 0, 86400, 0)
      const { rows } = await pool.query(
        `INSERT INTO fallback_channel_point_rewards(
           live_id, creator_id, title, description, cost, stock, cooldown_seconds
         ) VALUES ($1::uuid, $2, $3, $4, $5, $6, $7)
         RETURNING id::text, live_id::text, creator_id, title, description, cost, active,
                   stock, cooldown_seconds, version, created_at, updated_at`,
        [liveId, access.identity.displayUserId, title, description, cost, stockValue, cooldownSeconds],
      )
      res.status(201).json({ reward: responseReward(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/points/redeem', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const rewardId = validUuid(req.body?.rewardId || req.body?.reward_id)
      const requestNonce = cleanText(req.body?.requestNonce || req.body?.request_nonce, 160)
      if (!liveId || !rewardId || !requestNonce) return res.status(400).json({ error: 'LIVE_POINT_REDEMPTION_INVALID' })
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      if (access.isHost) return res.status(403).json({ error: 'LIVE_POINTS_VIEWER_REQUIRED' })
      client = await pool.connect()
      await client.query('BEGIN')
      const existing = await client.query(
        `SELECT id::text, live_id::text, reward_id::text, viewer_id, cost, status,
                request_nonce, refunded_at, created_at, updated_at
         FROM fallback_channel_point_redemptions
         WHERE live_id = $1::uuid AND request_nonce = $2
         LIMIT 1`,
        [liveId, requestNonce],
      )
      if (existing.rows[0]) {
        await client.query('COMMIT')
        return res.json({ redemption: existing.rows[0], duplicate: true })
      }
      const rewardResult = await client.query(
        `SELECT id::text, live_id::text, creator_id, title, description, cost, active, stock,
                cooldown_seconds, version
         FROM fallback_channel_point_rewards
         WHERE id = $1::uuid AND live_id = $2::uuid
         FOR UPDATE`,
        [rewardId, liveId],
      )
      const reward = rewardResult.rows[0]
      if (!reward || !reward.active || (reward.stock !== null && Number(reward.stock) <= 0)) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_POINT_REWARD_UNAVAILABLE' })
      }
      const cooldownResult = await client.query(
        `SELECT created_at
         FROM fallback_channel_point_redemptions
         WHERE live_id = $1::uuid AND reward_id = $2::uuid
           AND lower(viewer_id) = lower($3)
           AND status NOT IN ('rejected', 'canceled')
         ORDER BY created_at DESC LIMIT 1`,
        [liveId, rewardId, access.identity.displayUserId],
      )
      const cooldownRemainingSeconds = rewardCooldownRemainingSeconds({
        lastRedeemedAt: cooldownResult.rows[0]?.created_at,
        cooldownSeconds: reward.cooldown_seconds,
      })
      if (cooldownRemainingSeconds > 0) {
        await client.query('ROLLBACK')
        return res.status(429).json({ error: 'LIVE_POINT_REWARD_COOLDOWN', cooldownRemainingSeconds })
      }
      const balanceResult = await client.query(
        `SELECT balance FROM fallback_channel_points_balances
         WHERE live_id = $1::uuid AND lower(viewer_id) = lower($2)
         FOR UPDATE`,
        [liveId, access.identity.displayUserId],
      )
      const balance = Number(balanceResult.rows[0]?.balance || 0)
      if (balance < Number(reward.cost)) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_POINT_BALANCE_INSUFFICIENT', balance })
      }
      const redemption = await client.query(
        `INSERT INTO fallback_channel_point_redemptions(
           live_id, reward_id, viewer_id, cost, request_nonce, status
         ) VALUES ($1::uuid, $2::uuid, $3, $4, $5, 'requested')
         RETURNING id::text, live_id::text, reward_id::text, viewer_id, cost, status,
                   request_nonce, refunded_at, created_at, updated_at`,
        [liveId, rewardId, access.identity.displayUserId, Number(reward.cost), requestNonce],
      )
      await client.query(
        `UPDATE fallback_channel_points_balances
         SET balance = balance - $3,
             redeemed_total = redeemed_total + $3,
             updated_at = NOW()
         WHERE live_id = $1::uuid AND lower(viewer_id) = lower($2)`,
        [liveId, access.identity.displayUserId, Number(reward.cost)],
      )
      if (reward.stock !== null) {
        await client.query(
          `UPDATE fallback_channel_point_rewards SET stock = stock - 1, version = version + 1, updated_at = NOW()
           WHERE id = $1::uuid`,
          [rewardId],
        )
      }
      await client.query('COMMIT')
      res.status(201).json({ redemption: redemption.rows[0], balance: balance - Number(reward.cost) })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_POINT_REDEMPTION_DUPLICATE' })
      next(error)
    } finally {
      client?.release()
    }
  })

  app.get('/v1/data/live/:liveId/points/redemptions', authRequired, async (req, res, next) => {
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      const { rows } = await pool.query(
        `SELECT r.id::text, r.live_id::text, r.reward_id::text, r.viewer_id, r.cost, r.status,
                r.request_nonce, r.refunded_at, r.created_at, r.updated_at,
                w.title AS reward_title
         FROM fallback_channel_point_redemptions r
         JOIN fallback_channel_point_rewards w ON w.id = r.reward_id
         WHERE r.live_id = $1::uuid
           AND ($2::boolean OR lower(r.viewer_id) = ANY($3::text[]))
         ORDER BY r.created_at DESC LIMIT 100`,
        [liveId, access.isHost, access.identity.values],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ redemptions: rows })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/live/:liveId/points/redemptions/:redemptionId', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requirePointsFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const redemptionId = validUuid(req.params.redemptionId)
      const status = normalizeId(req.body?.status)
      if (!liveId || !redemptionId || !['approved', 'rejected', 'completed', 'canceled'].includes(status)) {
        return res.status(400).json({ error: 'LIVE_POINT_REDEMPTION_STATUS_INVALID' })
      }
      const access = await liveForUser(pool, liveId, user, userIdentity)
      if (access.error) return res.status(404).json({ error: access.error })
      if (!access.isHost) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      client = await pool.connect()
      await client.query('BEGIN')
      const currentResult = await client.query(
        `SELECT r.id::text, r.live_id::text, r.viewer_id, r.cost, r.status, r.refunded_at
         FROM fallback_channel_point_redemptions r
         WHERE r.id = $1::uuid AND r.live_id = $2::uuid FOR UPDATE`,
        [redemptionId, liveId],
      )
      const current = currentResult.rows[0]
      if (!current) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'LIVE_POINT_REDEMPTION_NOT_FOUND' })
      }
      const allowed = {
        requested: new Set(['approved', 'rejected', 'canceled']),
        approved: new Set(['completed', 'canceled']),
        rejected: new Set(),
        completed: new Set(),
        canceled: new Set(),
      }
      if (!allowed[current.status]?.has(status)) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_POINT_REDEMPTION_TRANSITION_INVALID' })
      }
      const shouldRefund = ['rejected', 'canceled'].includes(status) && !current.refunded_at
      const updated = await client.query(
        `UPDATE fallback_channel_point_redemptions
         SET status = $3, refunded_at = CASE WHEN $4::boolean THEN NOW() ELSE refunded_at END, updated_at = NOW()
         WHERE id = $1::uuid AND live_id = $2::uuid
         RETURNING id::text, live_id::text, reward_id::text, viewer_id, cost, status,
                   request_nonce, refunded_at, created_at, updated_at`,
        [redemptionId, liveId, status, shouldRefund],
      )
      if (shouldRefund) {
        await client.query(
          `UPDATE fallback_channel_points_balances
           SET balance = balance + $3, redeemed_total = GREATEST(0, redeemed_total - $3), updated_at = NOW()
           WHERE live_id = $1::uuid AND lower(viewer_id) = lower($2)`,
          [liveId, current.viewer_id, Number(current.cost)],
        )
      }
      await client.query('COMMIT')
      res.json({ redemption: updated.rows[0], refunded: shouldRefund })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })
}

module.exports = {
  dailyAwardAmount,
  ensureLivePointsTables,
  issueSessionToken,
  rewardCooldownRemainingSeconds,
  pointsEnabled,
  registerLivePointsRoutes,
}
