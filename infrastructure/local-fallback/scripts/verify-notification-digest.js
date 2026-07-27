const assert = require('node:assert/strict')

const apiUrl = String(process.env.API_URL || process.argv[2] || 'http://127.0.0.1:3080')
  .replace(/\/+$/, '')
const runId = String(process.env.RUN_ID || Date.now()).replace(/[^0-9a-z_-]/gi, '')
const password = 'NovvinDigest123!'
let recipientToken = ''
let senderToken = ''

async function request(path, {
  token = '',
  method = 'GET',
  body,
  expected = 200,
} = {}) {
  const response = await fetch(`${apiUrl}${path}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  const expectedStatuses = Array.isArray(expected) ? expected : [expected]
  assert.ok(
    expectedStatuses.includes(response.status),
    `${method} ${path}: ${response.status} ${text}`,
  )
  return payload
}

async function register(role) {
  return request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: {
      email: `module-digest-${role}.${runId}@example.local`,
      password,
      displayName: `Digest ${role} ${runId}`,
    },
  })
}

async function cleanup(token, role) {
  if (!token) return
  await request('/rest/v1/rpc/delete_own_account', {
    token,
    method: 'POST',
    body: {},
    expected: [200, 401, 404],
  }).catch((error) => {
    console.error(`NOTIFICATION_DIGEST_CLEANUP_FAILED_${role}=${error.message}`)
  })
}

async function main() {
  try {
    const recipient = await register('recipient')
    const sender = await register('sender')
    recipientToken = recipient.accessToken
    senderToken = sender.accessToken
    const recipientId = recipient.user.id

    const defaults = await request('/v1/data/notification-digest/preferences', {
      token: recipientToken,
    })
    assert.equal(defaults.preferences.enabled, false)
    assert.equal(defaults.preferences.includeDirectMessages, false)

    const immediateBeforeOptIn = await request('/v1/data/notifications', {
      token: senderToken,
      method: 'POST',
      expected: 201,
      body: {
        recipientId,
        type: 'mention_post',
        targetType: 'post',
        targetId: `pre-${runId}`,
        content: 'Mención anterior a activar el resumen',
      },
    })
    assert.notEqual(immediateBeforeOptIn.notification.extra_data?.digest_deferred, true)

    const invalid = await request('/v1/data/notification-digest/preferences', {
      token: recipientToken,
      method: 'PUT',
      expected: 400,
      body: {
        enabled: true,
        includeMentions: false,
        includeDirectMessages: false,
        includeCommunityActivity: false,
        includeUpcomingLives: false,
      },
    })
    assert.equal(invalid.error, 'DIGEST_CATEGORY_REQUIRED')

    const configured = await request('/v1/data/notification-digest/preferences', {
      token: recipientToken,
      method: 'PUT',
      body: {
        enabled: true,
        frequency: 'weekly',
        deliveryHour: 20,
        deliveryWeekday: 5,
        timezoneOffsetMinutes: -300,
        includeMentions: true,
        includeDirectMessages: true,
        includeCommunityActivity: true,
        includeUpcomingLives: true,
      },
    })
    assert.equal(configured.preferences.enabled, true)
    assert.equal(configured.preferences.frequency, 'weekly')
    assert.equal(configured.preferences.timezoneOffsetMinutes, -300)

    const deferredMention = await request('/v1/data/notifications', {
      token: senderToken,
      method: 'POST',
      expected: 201,
      body: {
        recipientId,
        type: 'mention_post',
        targetType: 'post',
        targetId: `mention-${runId}`,
        content: 'Mención diferida ficticia',
      },
    })
    assert.equal(deferredMention.notification.extra_data.digest_deferred, true)
    assert.equal(deferredMention.notification.extra_data.digest_category, 'mentions')

    const urgentDirectMessage = await request('/v1/data/notifications', {
      token: senderToken,
      method: 'POST',
      expected: 201,
      body: {
        recipientId,
        type: 'new_message',
        targetType: 'chat',
        targetId: `urgent-${runId}`,
        content: 'Mensaje urgente ficticio',
        extraData: { priority: 'urgent' },
      },
    })
    assert.notEqual(urgentDirectMessage.notification.extra_data.digest_deferred, true)

    const deferredDirectMessage = await request('/v1/data/notifications', {
      token: senderToken,
      method: 'POST',
      expected: 201,
      body: {
        recipientId,
        type: 'new_message',
        targetType: 'chat',
        targetId: `normal-${runId}`,
        content: 'Mensaje normal ficticio',
      },
    })
    assert.equal(deferredDirectMessage.notification.extra_data.digest_deferred, true)
    assert.equal(
      deferredDirectMessage.notification.extra_data.digest_category,
      'direct_messages',
    )

    const overview = await request('/v1/data/notification-digest', {
      token: recipientToken,
    })
    assert.equal(overview.preferences.enabled, true)
    assert.equal(overview.summary.total, 2)
    assert.equal(overview.summary.categories.mentions, 1)
    assert.equal(overview.summary.categories.directMessages, 1)
    assert.equal(overview.summary.unreadCount, 2)

    const ready = await request('/ready')
    assert.ok(ready.capabilities.includes('notification_digest_v1'))

    console.log(JSON.stringify({
      ok: true,
      runId,
      defaultOptOutPreserved: true,
      invalidEmptySelectionRejected: true,
      mentionDeferred: true,
      ordinaryDirectMessageDeferred: true,
      urgentDirectMessageImmediate: true,
      summaryCountsVerified: true,
      capabilityPublished: true,
    }))
  } finally {
    await cleanup(senderToken, 'sender')
    await cleanup(recipientToken, 'recipient')
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
