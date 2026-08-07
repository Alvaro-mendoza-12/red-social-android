const assert = require('assert')
const {
  didLiveStart,
  enqueueLiveStartedFollowerNotifications,
  ensureLiveFollowerNotificationTables,
  liveFollowerNotificationsEnabled,
  notificationForOutbox,
  processPendingLiveFollowerNotifications,
} = require('../src/live-follower-notifications')
const { registerLiveRoutes } = require('../src/live-routes')

function withFlag(value, action) {
  const previous = process.env.LIVE_FOLLOWER_NOTIFICATIONS_ENABLED
  if (value === undefined) delete process.env.LIVE_FOLLOWER_NOTIFICATIONS_ENABLED
  else process.env.LIVE_FOLLOWER_NOTIFICATIONS_ENABLED = value
  return Promise.resolve()
    .then(action)
    .finally(() => {
      if (previous === undefined) delete process.env.LIVE_FOLLOWER_NOTIFICATIONS_ENABLED
      else process.env.LIVE_FOLLOWER_NOTIFICATIONS_ENABLED = previous
    })
}

async function testFlagDefaultsClosed() {
  await withFlag(undefined, () => {
    assert.strictEqual(liveFollowerNotificationsEnabled(), false)
  })
  await withFlag('true', () => {
    assert.strictEqual(liveFollowerNotificationsEnabled(), true)
  })
}

function testOnlyFalseToTrueStartsLive() {
  assert.strictEqual(didLiveStart(false, true, true), true)
  assert.strictEqual(didLiveStart(true, true, true), false)
  assert.strictEqual(didLiveStart(false, false, true), false)
  assert.strictEqual(didLiveStart(false, true, false), false)
}

async function testSchemaHasDurableDeduplication() {
  const statements = []
  const pool = {
    async query(statement) {
      statements.push(String(statement).replace(/\s+/g, ' ').trim())
      return { rows: [] }
    },
  }
  await ensureLiveFollowerNotificationTables(pool)
  const schema = statements.join('\n')
  assert.match(schema, /fallback_live_follower_notification_outbox/)
  assert.match(schema, /REFERENCES fallback_live_streams\(id\) ON DELETE CASCADE/)
  assert.match(schema, /idx_live_follower_notification_outbox_unique/)
  assert.match(schema, /lower\(recipient_id\)/)
  assert.match(schema, /idx_fallback_notifications_live_started_delivery/)
  assert.match(schema, /delivery_key/)
}

async function testDisabledEnqueueDoesNotQueryFollowers() {
  await withFlag(undefined, async () => {
    let calls = 0
    const result = await enqueueLiveStartedFollowerNotifications({
      async query() {
        calls += 1
        return { rows: [] }
      },
    }, {
      liveId: '11111111-1111-4111-8111-111111111111',
      hostUserId: 'host',
    })
    assert.deepStrictEqual(result, { enqueued: 0, disabled: true })
    assert.strictEqual(calls, 0)
  })
}

async function testEnqueueUsesFollowerAndBlockRules() {
  await withFlag('true', async () => {
    let query = ''
    const result = await enqueueLiveStartedFollowerNotifications({
      async query(statement) {
        query = String(statement)
        return { rows: [{ enqueued: 2 }] }
      },
    }, {
      liveId: '11111111-1111-4111-8111-111111111111',
      hostUserId: 'host-id',
      hostIdentityIds: ['host-id', 'host-alias'],
      hostName: 'Host',
    })
    assert.deepStrictEqual(result, { enqueued: 2, disabled: false })
    assert.match(query, /fallback_follows/)
    assert.match(query, /fallback_user_blocks/)
    assert.match(query, /recipient_id <> ALL/)
    assert.match(query, /ON CONFLICT DO NOTHING/)
  })
}

async function testWorkerPersistsBeforeRealtimeAndPush() {
  await withFlag('true', async () => {
    const timeline = []
    const outbox = {
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      live_id: '11111111-1111-4111-8111-111111111111',
      recipient_id: 'viewer',
      host_user_id: 'host',
      host_name: 'Host',
      host_avatar_url: null,
      attempts: 1,
      payload: { live_id: '11111111-1111-4111-8111-111111111111' },
    }
    const result = await processPendingLiveFollowerNotifications({}, {
      claim: async () => [outbox],
      isRecipientEligible: async () => true,
      persist: async (_pool, notification) => {
        timeline.push('persist')
        return { ...notification, id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' }
      },
      shouldDefer: async () => ({ defer: false, category: null }),
      publishRealtime: async () => { timeline.push('realtime') },
      sendPush: async () => {
        timeline.push('push')
        return { sent: 1, failed: 0 }
      },
      setStatus: async (_pool, _id, status, options) => {
        timeline.push(`${status}:${options?.notificationId || ''}`)
      },
    })
    assert.deepStrictEqual(result, { processed: 1, delivered: 1, suppressed: 0, failed: 0 })
    assert.deepStrictEqual(timeline, [
      'persist',
      'realtime',
      'push',
      'delivered:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    ])
  })
}

function testNotificationPayloadIsStableAndScoped() {
  const notification = notificationForOutbox({
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    live_id: '11111111-1111-4111-8111-111111111111',
    recipient_id: 'viewer',
    host_user_id: 'host',
    host_name: 'Host',
    payload: {},
  })
  assert.strictEqual(notification.type, 'live_started')
  assert.strictEqual(notification.target_type, 'live')
  assert.strictEqual(notification.target_id, '11111111-1111-4111-8111-111111111111')
  assert.strictEqual(
    notification.extra_data.delivery_key,
    'live_started:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  )
}

function responseCapture() {
  return {
    statusCode: 200,
    body: null,
    status(value) {
      this.statusCode = value
      return this
    },
    json(value) {
      this.body = value
      return this
    },
  }
}

async function testLiveStateTransitionCommitsBeforeFanoutAndDoesNotRepeat() {
  await withFlag('true', async () => {
    const queries = []
    const liveId = '11111111-1111-4111-8111-111111111111'
    const state = {
      id: liveId,
      host_user_id: 'host',
      host_name: 'Host',
      host_avatar_url: null,
      title: 'Prueba',
      source: 'camera',
      hashtags: [],
      viewer_count: 0,
      peak_viewer_count: 0,
      total_comments: 0,
      total_reactions: 0,
      chat_paused: false,
      allow_guest_requests: false,
      is_active: false,
      started_at: null,
      ended_at: null,
      created_at: '2026-01-01T00:00:00.000Z',
      updated_at: '2026-01-01T00:00:00.000Z',
    }
    const client = {
      async query(statement) {
        const sql = String(statement)
        queries.push(sql)
        if (sql === 'BEGIN' || sql === 'COMMIT' || sql === 'ROLLBACK') return { rows: [] }
        if (sql.includes('FROM fallback_live_streams')) return { rows: [{ ...state }] }
        if (sql.includes('UPDATE fallback_live_streams')) {
          state.is_active = true
          return { rows: [{ ...state }] }
        }
        if (sql.includes('WITH follower_candidates')) return { rows: [{ enqueued: 1 }] }
        if (sql.includes('WITH candidates AS')) return { rows: [] }
        throw new Error(`Unexpected SQL: ${sql.slice(0, 80)}`)
      },
      release() {},
    }
    const pool = { connect: async () => client, query: client.query.bind(client) }
    let stateHandler = null
    const app = {
      get() {}, post() {}, put() {}, delete() {},
      patch(path, ...handlers) {
        if (path === '/v1/data/live/:liveId/state') stateHandler = handlers.at(-1)
      },
    }
    registerLiveRoutes(app, {
      pool,
      authRequired: (_req, _res, next) => next(),
      getCurrentUser: async () => ({ id: 'host', display_name: 'Host' }),
      userIdentity: () => ({
        ids: new Set(['host']),
        values: ['host'],
        displayUserId: 'host',
      }),
    })
    assert.ok(stateHandler)
    const request = { params: { liveId }, body: { isActive: true }, auth: { sub: 'host' } }
    const first = responseCapture()
    await stateHandler(request, first, (error) => { throw error })
    await new Promise((resolve) => setImmediate(resolve))
    assert.strictEqual(first.statusCode, 200)
    assert.strictEqual(first.body.stream.is_active, true)
    assert.ok(queries.findIndex((entry) => entry === 'COMMIT') > queries.findIndex((entry) => entry.includes('WITH follower_candidates')))
    assert.match(queries.find((entry) => entry.includes('FROM fallback_live_streams')), /FOR UPDATE/)
    assert.strictEqual(queries.filter((entry) => entry.includes('WITH follower_candidates')).length, 1)

    const second = responseCapture()
    await stateHandler(request, second, (error) => { throw error })
    await new Promise((resolve) => setImmediate(resolve))
    assert.strictEqual(second.statusCode, 200)
    assert.strictEqual(queries.filter((entry) => entry.includes('WITH follower_candidates')).length, 1)
  })
}

Promise.resolve()
  .then(testFlagDefaultsClosed)
  .then(testOnlyFalseToTrueStartsLive)
  .then(testSchemaHasDurableDeduplication)
  .then(testDisabledEnqueueDoesNotQueryFollowers)
  .then(testEnqueueUsesFollowerAndBlockRules)
  .then(testWorkerPersistsBeforeRealtimeAndPush)
  .then(testNotificationPayloadIsStableAndScoped)
  .then(testLiveStateTransitionCommitsBeforeFanoutAndDoesNotRepeat)
  .then(() => console.log('live follower notification tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
