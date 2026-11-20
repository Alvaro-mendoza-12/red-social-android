'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const express = require('express')

const { registerMusicRoutes } = require('../src/music-routes')
const { identityHasId, registerLivePointsRoutes } = require('../src/live-points-routes')
const { createGooglePlayRtdnHandler } = require('../src/google-play-rtdn')
const { processDueScheduledMessageJobs, scheduleMessageJob } = require('../src/scheduled-message-jobs')
const { advanceAnonymousProgress, registerProximityRoutes } = require('../src/proximity-routes')
const { registerMomentsRoutes } = require('../src/moments-routes')

async function withServer(app, fn) {
  const server = app.listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  const baseUrl = `http://127.0.0.1:${server.address().port}`
  try {
    return await fn(baseUrl)
  } finally {
    server.closeAllConnections?.()
    await new Promise((resolve) => server.close(resolve))
  }
}

test('music report auto-takedown requires 3 DISTINCT reporters and returns 404 for unknown tracks', async () => {
  const trackId = crypto.randomUUID()
  const reporter1 = crypto.randomUUID()
  const reporter2 = crypto.randomUUID()
  const reporter3 = crypto.randomUUID()
  const reports = []
  let takedowns = 0

  const pool = {
    async query(sql, params = []) {
      const text = String(sql)
      if (text.includes('SELECT 1 FROM fallback_music_tracks')) {
        return { rows: params[0] === trackId ? [{ '?column?': 1 }] : [] }
      }
      if (text.includes('INSERT INTO fallback_music_reports')) {
        const [tid, rid, reason] = params
        if (!reports.some((r) => r.tid === tid && r.rid === rid && r.reason === reason)) {
          reports.push({ tid, rid, reason, status: 'OPEN' })
        }
        return { rows: [] }
      }
      if (text.includes('COUNT(DISTINCT reporter_id)')) {
        const distinct = new Set(reports.filter((r) => r.tid === params[0] && r.status === 'OPEN').map((r) => r.rid))
        return { rows: [{ count: distinct.size }] }
      }
      if (text.includes("SET moderation_state='TAKEDOWN_PENDING'")) {
        takedowns += 1
        return { rows: [] }
      }
      throw new Error(`Unexpected SQL: ${text}`)
    },
  }

  let currentActor = reporter1
  const app = express()
  app.use(express.json())
  registerMusicRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: currentActor, display_name: 'Tester' }),
    userIdentity: (u) => ({ ids: new Set([u.id]), values: [u.id], displayUserId: u.id }),
  })

  await withServer(app, async (baseUrl) => {
    // Unknown track returns 404 (never 500 FK violation)
    const missing = await fetch(`${baseUrl}/v1/data/music/${crypto.randomUUID()}/report`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ reason: 'spam' }),
    })
    assert.equal(missing.status, 404)

    // Same reporter submitting 3 different reasons MUST NOT trigger takedown
    for (const reason of ['spam', 'copyright', 'abuse']) {
      const res = await fetch(`${baseUrl}/v1/data/music/${trackId}/report`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ reason }),
      })
      assert.equal(res.status, 201)
      const body = await res.json()
      assert.equal(body.underReview, false)
    }
    assert.equal(takedowns, 0)

    // 2nd distinct reporter still does not trigger takedown
    currentActor = reporter2
    let res = await fetch(`${baseUrl}/v1/data/music/${trackId}/report`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ reason: 'copyright' }),
    })
    assert.equal((await res.json()).underReview, false)
    assert.equal(takedowns, 0)

    // 3rd distinct reporter triggers auto-takedown
    currentActor = reporter3
    res = await fetch(`${baseUrl}/v1/data/music/${trackId}/report`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ reason: 'copyright' }),
    })
    assert.equal((await res.json()).underReview, true)
    assert.equal(takedowns, 1)
  })
})

test('music listen completion is strictly idempotent and prevents play-event inflation', async () => {
  const sessionId = crypto.randomUUID()
  const trackId = crypto.randomUUID()
  const userId = crypto.randomUUID()
  let completed = false
  let playEvents = 0

  const pool = {
    async query(sql) {
      const text = String(sql)
      if (text.includes('UPDATE fallback_music_listens') && text.includes('completed_at IS NULL')) {
        if (completed) return { rows: [] }
        completed = true
        return { rows: [{ counted: true, track_id: trackId, context: 'catalog' }] }
      }
      if (text.includes('SELECT counted FROM fallback_music_listens')) {
        return { rows: [{ counted: true }] }
      }
      if (text.includes('INSERT INTO fallback_music_play_events')) {
        playEvents += 1
        return { rows: [] }
      }
      throw new Error(`Unexpected SQL: ${text}`)
    },
  }

  const app = express()
  app.use(express.json())
  registerMusicRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: userId }),
    userIdentity: (u) => ({ ids: new Set([u.id]), values: [u.id], displayUserId: u.id }),
  })

  await withServer(app, async (baseUrl) => {
    const first = await fetch(`${baseUrl}/v1/data/music/listens/${sessionId}/complete`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ listenedMs: 12000 }),
    })
    assert.equal(first.status, 200)
    assert.deepEqual(await first.json(), { counted: true })
    assert.equal(playEvents, 1)

    const replay = await fetch(`${baseUrl}/v1/data/music/listens/${sessionId}/complete`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ listenedMs: 12000 }),
    })
    assert.equal(replay.status, 200)
    assert.deepEqual(await replay.json(), { counted: true, alreadyCompleted: true })
    assert.equal(playEvents, 1)
  })
})

test('live-points routes work with Set-based production userIdentity (no TypeError)', async () => {
  const liveId = crypto.randomUUID()
  const hostId = crypto.randomUUID()
  const viewerId = crypto.randomUUID()
  const prevFlag = process.env.LIVE_CHANNEL_POINTS_ENABLED
  process.env.LIVE_CHANNEL_POINTS_ENABLED = 'true'

  assert.equal(identityHasId({ ids: new Set([hostId]), values: [hostId], displayUserId: hostId }, hostId), true)
  assert.equal(identityHasId({ ids: [hostId], values: [hostId], displayUserId: hostId }, hostId), true)
  assert.equal(identityHasId({ ids: new Set([viewerId]), values: [viewerId], displayUserId: viewerId }, hostId), false)

  const pool = {
    async query(sql) {
      const text = String(sql)
      if (text.includes('FROM fallback_live_streams')) {
        return { rows: [{ id: liveId, host_user_id: hostId, is_active: true, title: 'Live' }] }
      }
      if (text.includes('INSERT INTO fallback_live_point_settings')) {
        return { rows: [{ live_id: liveId, creator_id: hostId, enabled: true, interval_seconds: 300, points_per_interval: 10, daily_cap: 2000 }] }
      }
      if (text.includes('FROM fallback_channel_points_balances')) {
        return { rows: [] }
      }
      throw new Error(`Unexpected SQL: ${text}`)
    },
  }

  const app = express()
  app.use(express.json())
  registerLivePointsRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: viewerId }),
    userIdentity: (u) => ({ ids: new Set([u.id]), values: [u.id], displayUserId: u.id }),
  })
  app.use((err, _req, res, _next) => res.status(500).json({ error: err.message }))

  try {
    await withServer(app, async (baseUrl) => {
      const res = await fetch(`${baseUrl}/v1/data/live/${liveId}/points/balance`)
      assert.equal(res.status, 200)
      const body = await res.json()
      assert.equal(body.balance.balance, 0)
    })
  } finally {
    if (prevFlag === undefined) delete process.env.LIVE_CHANNEL_POINTS_ENABLED
    else process.env.LIVE_CHANNEL_POINTS_ENABLED = prevFlag
  }
})

test('Google Play RTDN concurrent duplicate delivery never clobbers processing or processed status', async () => {
  const events = new Map()
  const pool = {
    async query(sql, params = []) {
      const text = String(sql)
      if (text.includes('INSERT INTO google_play_rtdn_events')) {
        if (events.has(params[0])) return { rows: [] }
        events.set(params[0], { status: 'processing', attempts: 1 })
        return { rows: [{ message_id: params[0] }] }
      }
      if (text.includes("SET status = 'processing'")) return { rows: [] }
      if (text.startsWith('SELECT status FROM google_play_rtdn_events')) {
        return { rows: events.has(params[0]) ? [events.get(params[0])] : [] }
      }
      if (text.includes("SET status = 'processed'")) {
        events.set(params[0], { status: 'processed' })
        return { rows: [] }
      }
      if (text.includes("SET status = 'failed'")) {
        const cur = events.get(params[0])
        if (cur?.status === 'processing') events.set(params[0], { status: 'failed', last_error: params[1] })
        return { rows: [] }
      }
      throw new Error(`Unexpected SQL: ${text}`)
    },
  }
  const config = {
    enabled: true,
    ready: true,
    audience: 'https://api.example.test/webhooks/google-play/rtdn',
    pushServiceAccountEmail: 'push@example.iam.gserviceaccount.com',
    verificationToken: '',
    pendingRefundPreference: 'NEUTRAL',
  }
  const oauthClient = {
    async verifyIdToken() {
      return {
        getPayload: () => ({
          iss: 'https://accounts.google.com',
          email: 'push@example.iam.gserviceaccount.com',
          email_verified: true,
        }),
      }
    },
  }
  let releaseFirst
  let onEnter = () => {}
  const enteredPromise = new Promise((resolve) => { onEnter = resolve })
  const handler = createGooglePlayRtdnHandler({
    pool,
    packageName: 'conectate.com',
    configProvider: () => config,
    oauthClient,
    processNotification: async () => {
      onEnter()
      await new Promise((resolve) => { releaseFirst = resolve })
      return { ok: true }
    },
  })
  const req = {
    headers: { authorization: 'Bearer valid' },
    query: {},
    body: {
      message: {
        messageId: 'race-msg-1',
        data: Buffer.from(JSON.stringify({
          packageName: 'conectate.com',
          eventTimeMillis: String(Date.now()),
          testNotification: { version: '1.0' },
        })).toString('base64'),
      },
    },
  }
  const makeRes = () => ({ statusCode: null, status(c) { this.statusCode = c; return this }, end() { return this } })

  const res1 = makeRes()
  const p1 = handler(req, res1, (err) => { throw err })
  await enteredPromise

  // Concurrent duplicate arrives while p1 is still in-flight
  let err2 = null
  await handler(req, makeRes(), (err) => { err2 = err })
  assert.equal(err2?.code, 'GOOGLE_PLAY_RTDN_ALREADY_PROCESSING')
  // CRITICAL: status must STILL be 'processing', NEVER overwritten to 'failed'
  assert.equal(events.get('race-msg-1').status, 'processing')

  releaseFirst()
  await p1
  assert.equal(res1.statusCode, 204)
  assert.equal(events.get('race-msg-1').status, 'processed')
})

test('scheduled-message-jobs commits claim before tx so rollback records failure and blocks DM if blocked', async () => {
  const jobId = crypto.randomUUID()
  const ownerId = crypto.randomUUID()
  const chatId = crypto.randomUUID()
  const peerId = crypto.randomUUID()

  let jobState = {
    id: jobId,
    owner_id: ownerId,
    sender_id: ownerId,
    scope_type: 'dm',
    target_id: chatId,
    content: 'blocked msg',
    content_type: 'text',
    media_url: null,
    status: 'pending',
    attempt_count: 0,
    max_attempts: 6,
    locked_by: null,
    error_message: null,
    message_id: crypto.randomUUID(),
  }

  const queryHandler = async (sql, params = []) => {
    const text = String(sql)
    if (text === 'BEGIN' || text === 'COMMIT' || text === 'ROLLBACK') return { rows: [] }
    if (text.includes('SELECT id::text') && text.includes('FROM fallback_scheduled_message_jobs')) {
      return { rows: [{ id: jobId }] }
    }
    if (text.includes("SET status = 'processing'")) {
      jobState.status = 'processing'
      jobState.locked_by = params[1]
      jobState.attempt_count += 1
      return { rows: [{ ...jobState }] }
    }
    if (text.includes('FROM fallback_users') && text.includes('WHERE id = $1::uuid')) {
      return { rows: [{ local_id: ownerId, public_id: ownerId }] }
    }
    if (text.includes('FROM fallback_profile_moderation')) {
      return { rows: [] }
    }
    if (text.includes('FROM fallback_chats c')) {
      return { rows: [{ id: chatId, participants: [ownerId, peerId] }] }
    }
    if (text.includes('FROM fallback_user_blocks')) {
      return { rows: [{ blocker_id: peerId, blocked_id: ownerId }] }
    }
    if (text.includes('FROM fallback_chat_participants')) {
      return { rows: [{ user_id: peerId }] }
    }
    if (text.includes('FROM fallback_users') && text.includes('ANY($1::text[])')) {
      return { rows: [{ id: peerId, supabase_user_id: peerId }] }
    }
    if (text.includes('UPDATE fallback_scheduled_message_jobs') && text.includes("WHERE id = $1::uuid AND status = 'processing' AND locked_by = $5")) {
      assert.equal(jobState.status, 'processing')
      assert.equal(jobState.locked_by, params[4])
      jobState.status = params[1]
      jobState.error_message = params[2]
      jobState.locked_by = null
      return { rows: [] }
    }
    throw new Error(`Unexpected SQL: ${text}`)
  }

  const pool = {
    query: queryHandler,
    async connect() {
      return { query: queryHandler, release() {} }
    },
  }

  // Scheduling a DM to a blocked user is rejected immediately with 403 USER_BLOCKED
  await assert.rejects(
    scheduleMessageJob(pool, { sub: ownerId }, {
      scope_type: 'dm',
      target_id: chatId,
      content: 'hi',
      scheduled_for: new Date(Date.now() + 60_000).toISOString(),
    }),
    (err) => err.message === 'USER_BLOCKED' && err.status === 403,
  )

  // Worker delivery for an already-scheduled job after a block transitions cleanly to terminal 'failed'
  const sent = await processDueScheduledMessageJobs(pool, { workerId: 'worker-1' })
  assert.equal(sent, 0)
  assert.equal(jobState.status, 'failed')
  assert.equal(jobState.error_message, 'USER_BLOCKED')
  assert.equal(jobState.attempt_count, 1)
})

test('anonymous random chat reveal requires two-way messages (one-sided spam stays level 0)', async () => {
  const sessionId = crypto.randomUUID()
  let lastLevel = null
  const makeDb = (countA, countB) => ({
    async query(sql, params = []) {
      const text = String(sql)
      if (text.includes('count_a') && text.includes('count_b')) {
        return { rows: [{ count_a: countA, count_b: countB }] }
      }
      if (text.includes('UPDATE fallback_random_chat_sessions')) {
        lastLevel = params[1]
        return { rows: [{ id: sessionId, user_a_reveal: lastLevel, user_b_reveal: lastLevel }] }
      }
      throw new Error(`Unexpected SQL: ${text}`)
    },
  })

  // 12 spam messages from A, 0 from B -> MUST stay level 0!
  await advanceAnonymousProgress(makeDb(12, 0), sessionId)
  assert.equal(lastLevel, 0)

  // 10 from A, 1 from B -> exchanged = 2 -> still level 0
  await advanceAnonymousProgress(makeDb(10, 1), sessionId)
  assert.equal(lastLevel, 0)

  // 2 from A, 2 from B -> exchanged = 4 -> level 1
  await advanceAnonymousProgress(makeDb(2, 2), sessionId)
  assert.equal(lastLevel, 1)

  // 4 from A, 4 from B -> exchanged = 8 -> level 2
  await advanceAnonymousProgress(makeDb(4, 4), sessionId)
  assert.equal(lastLevel, 2)

  // 6 from A, 6 from B -> exchanged = 12 -> level 3 (capped at 3)
  await advanceAnonymousProgress(makeDb(9, 6), sessionId)
  assert.equal(lastLevel, 3)
})

test('POST /v1/data/moments handles non-numeric durationHours without 500 RangeError', async () => {
  const userId = crypto.randomUUID()
  let insertedExpiresAt = null
  let insertedVisibility = null
  const pool = {
    async query(sql, params = []) {
      const text = String(sql)
      if (text.includes('INSERT INTO fallback_moments')) {
        insertedVisibility = params[5]
        insertedExpiresAt = params[13]
        return { rows: [{ id: crypto.randomUUID(), user_id: userId, expires_at: insertedExpiresAt }] }
      }
      return { rows: [] }
    },
  }
  const app = express()
  app.use(express.json())
  registerMomentsRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: userId, display_name: 'Tester' }),
    userIdentity: (u) => ({ ids: new Set([u.id]), values: [u.id], displayUserId: u.id }),
    currentUserOwnsStorageObject: async () => true,
  })
  app.use((err, _req, res, _next) => res.status(500).json({ error: err.message }))

  await withServer(app, async (baseUrl) => {
    const res = await fetch(`${baseUrl}/v1/data/moments`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        mediaUrl: 'https://novvin.example/storage/v1/object/public/moment-media/u/pic.jpg',
        durationHours: 'not-a-number',
        visibility: 'unrecognized_visibility_value',
      }),
    })
    assert.equal(res.status, 201)
    assert.ok(Number.isFinite(Date.parse(insertedExpiresAt)))
    assert.equal(insertedVisibility, 'all')
  })
})

test('Cycle 223: guest-mode-routes enforces suspended/deleted/private creator exclusion and caches to_regclass per pool', async () => {
  const { readPublicContent, readPublicProfile } = require('../src/guest-mode-routes')
  const queries = []
  let regclassCalls = 0
  const pool = {
    async query(sql) {
      const text = String(sql)
      queries.push(text)
      if (text.includes('SELECT to_regclass')) {
        regclassCalls += 1
        return { rows: [{ table_oid: 'supabase_import.videos' }] }
      }
      return { rows: [] }
    },
  }

  await readPublicContent(pool, 'videos', crypto.randomUUID())
  await readPublicContent(pool, 'videos', crypto.randomUUID())
  assert.equal(regclassCalls, 1, 'to_regclass for supabase_import.videos must be cached per pool')
  assert.ok(queries.some((q) => q.includes('fallback_video_deletions') && q.includes('fallback_profile_moderation') && q.includes('fallback_deleted_identities')))

  await readPublicProfile(pool, crypto.randomUUID())
  assert.ok(queries.some((q) => q.includes('FROM fallback_users u') && q.includes('fallback_profile_moderation') && q.includes('fallback_deleted_identities')))
})

test('Cycle 223: processDueNotificationDigests does not count LOCKED or NO_LONGER_DUE rows as delivered', async () => {
  const { processDueNotificationDigests } = require('../src/notification-digest-routes')
  const now = new Date('2026-11-20T19:00:00Z')
  const yesterday = new Date('2026-11-19T18:00:00Z').toISOString()
  const pool = {
    async query(sql) {
      const text = String(sql)
      if (text.includes('FROM fallback_notification_digest_preferences')) {
        return {
          rows: [{
            user_id: crypto.randomUUID(),
            enabled: true,
            frequency: 'daily',
            delivery_hour: 18,
            delivery_weekday: 1,
            timezone_offset_minutes: 0,
            last_delivered_at: yesterday,
          }],
        }
      }
      return { rows: [] }
    },
    async connect() {
      return {
        async query(sql) {
          if (String(sql).includes('pg_try_advisory_lock')) {
            return { rows: [{ locked: false }] }
          }
          return { rows: [] }
        },
        release() {},
      }
    },
  }

  const res = await processDueNotificationDigests(pool, now)
  assert.equal(res.checked, 1)
  assert.equal(res.delivered, 0)
  assert.equal(res.notifications, 0)
})
