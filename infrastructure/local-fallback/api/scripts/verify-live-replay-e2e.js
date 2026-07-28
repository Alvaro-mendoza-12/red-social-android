const assert = require('node:assert/strict')
const crypto = require('node:crypto')

const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const suffix = crypto.randomUUID()
const password = `LiveReplay-${suffix}!`
const accounts = []
let liveId = ''
let replayObjectPath = ''

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
  return { response, body }
}

async function register(label) {
  const email = `live-replay-${label}-${suffix}@novvin.invalid`
  const result = await jsonRequest('/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, displayName: `Replay ${label}` }),
  })
  assert.equal(result.response.status, 201, JSON.stringify(result.body))
  const account = {
    id: String(result.body.user.id).toLowerCase(),
    token: String(result.body.accessToken),
  }
  accounts.push(account)
  return account
}

async function authorized(account, path, options = {}) {
  return jsonRequest(path, {
    ...options,
    headers: {
      authorization: `Bearer ${account.token}`,
      ...(options.headers || {}),
    },
  })
}

async function uploadFixture(account) {
  const requestedPath = `replays/${liveId}/${crypto.randomUUID()}.mp4`
  const fixture = Buffer.from('000000186674797069736f6d0000020069736f6d69736f32', 'hex')
  const response = await fetch(
    `${baseUrl}/storage/v1/object/live-replays/${requestedPath}`,
    {
      method: 'PUT',
      headers: {
        authorization: `Bearer ${account.token}`,
        'content-type': 'video/mp4',
        'content-length': String(fixture.length),
        'x-upsert': 'false',
      },
      body: fixture,
    },
  )
  const body = await response.json()
  assert.equal(response.status, 200, JSON.stringify(body))
  replayObjectPath = String(body.path)
  return String(body.publicUrl)
}

async function cleanup(host) {
  if (host && replayObjectPath) {
    await authorized(
      host,
      `/v1/storage/objects/live-replays/${replayObjectPath
        .split('/')
        .map(encodeURIComponent)
        .join('/')}`,
      { method: 'DELETE' },
    )
  }
  if (host && liveId) {
    await authorized(host, `/v1/data/live/${liveId}`, { method: 'DELETE' })
  }
  for (const account of accounts) {
    const removed = await authorized(account, '/rest/v1/rpc/delete_own_account', {
      method: 'POST',
      body: '{}',
    })
    assert.ok(removed.response.ok, `Self cleanup failed: ${JSON.stringify(removed.body)}`)
  }
}

async function run() {
  let host
  try {
    host = await register('host')
    const viewer = await register('viewer')
    liveId = crypto.randomUUID()

    const created = await authorized(host, '/v1/data/live', {
      method: 'POST',
      body: JSON.stringify({
        id: liveId,
        title: 'Fixture B19',
        source: 'camera',
        hashtags: ['repeticion'],
      }),
    })
    assert.equal(created.response.status, 201, JSON.stringify(created.body))

    const activated = await authorized(host, `/v1/data/live/${liveId}/state`, {
      method: 'PATCH',
      body: JSON.stringify({ isActive: true, viewerCount: 1 }),
    })
    assert.equal(activated.response.status, 200, JSON.stringify(activated.body))

    const chapter = await authorized(host, `/v1/data/live/${liveId}/chapters`, {
      method: 'POST',
      body: JSON.stringify({ title: 'Momento clave', offsetMs: 0, isHighlight: true }),
    })
    assert.equal(chapter.response.status, 201, JSON.stringify(chapter.body))
    assert.equal(chapter.body.chapter.title, 'Momento clave')
    assert.equal(Number(chapter.body.chapter.offset_ms), 0)

    const ended = await authorized(host, `/v1/data/live/${liveId}/state`, {
      method: 'PATCH',
      body: JSON.stringify({ isActive: false, viewerCount: 0 }),
    })
    assert.equal(ended.response.status, 200, JSON.stringify(ended.body))

    const replayUrl = await uploadFixture(host)
    const published = await authorized(host, `/v1/data/live/${liveId}/replay`, {
      method: 'PUT',
      body: JSON.stringify({
        replayUrl,
        durationMs: 60_000,
        published: true,
      }),
    })
    assert.equal(published.response.status, 200, JSON.stringify(published.body))
    assert.equal(published.body.replay.replay_published, true)
    assert.equal(Number(published.body.replay.replay_duration_ms), 60_000)
    assert.equal(published.body.replay.chapters.length, 1)

    const publicList = await authorized(viewer, '/v1/data/live/replays')
    assert.equal(publicList.response.status, 200, JSON.stringify(publicList.body))
    assert.ok(publicList.body.items.some((item) => item.id === liveId))

    const publicDetail = await authorized(viewer, `/v1/data/live/${liveId}/replay`)
    assert.equal(publicDetail.response.status, 200, JSON.stringify(publicDetail.body))
    assert.equal(publicDetail.body.replay.chapters[0].title, 'Momento clave')

    const forbidden = await authorized(viewer, `/v1/data/live/${liveId}/replay`, {
      method: 'PUT',
      body: JSON.stringify({ replayUrl, durationMs: 60_000, published: false }),
    })
    assert.equal(forbidden.response.status, 403, JSON.stringify(forbidden.body))
    assert.equal(forbidden.body.error, 'LIVE_FORBIDDEN')

    console.log(JSON.stringify({
      ok: true,
      replayOwnedStorageRequired: true,
      publicReplayLibrary: true,
      chapterSeekingContract: true,
      foreignMutationBlocked: true,
    }))
  } finally {
    if (host) await cleanup(host)
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
