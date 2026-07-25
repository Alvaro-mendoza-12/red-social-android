const assert = require('node:assert/strict')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const runId = Date.now().toString()
const password = 'Conectate123!'

async function request(path, { token, method = 'GET', body, expected = 200 } = {}) {
  const response = await fetch(`${apiUrl}${path}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body != null ? { 'content-type': 'application/json' } : {}),
    },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `${method} ${path}: ${response.status} ${text}`)
  return payload
}

async function register(label) {
  const auth = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: {
      email: `module-live.${label}.${runId}@example.local`,
      password,
      displayName: `Live ${label}`,
    },
  })
  return { id: auth.user.id, token: auth.accessToken }
}

async function main() {
  const owner = await register('owner')
  const viewer = await register('viewer')
  const created = await request('/v1/data/live', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: {
      title: `Directo ${runId}`,
      source: 'screen',
      hashtags: ['#Gaming', 'gaming', ' comunidad '],
    },
  })
  const liveId = created.stream.id
  assert.ok(liveId)
  assert.equal(created.stream.is_active, false)
  assert.equal(created.stream.source, 'screen')
  assert.deepEqual(created.stream.hashtags, ['gaming', 'comunidad'])

  await request(`/v1/data/live/${liveId}`, { token: viewer.token, expected: 404 })
  await request(`/v1/data/live/${liveId}/state`, {
    token: viewer.token,
    method: 'PATCH',
    body: { isActive: true },
    expected: 403,
  })
  await request(`/v1/data/live/${liveId}`, { token: viewer.token, method: 'DELETE', expected: 403 })

  const active = await request(`/v1/data/live/${liveId}/state`, {
    token: owner.token,
    method: 'PATCH',
    body: { isActive: true, viewerCount: 0 },
  })
  assert.equal(active.stream.is_active, true)
  assert.ok(active.stream.started_at)
  await request(`/v1/data/live/${liveId}`, { token: owner.token, method: 'DELETE', expected: 409 })

  const discovery = await request('/v1/data/live?limit=60', { token: viewer.token })
  assert.ok(discovery.items.some((item) => item.id === liveId))
  const visible = await request(`/v1/data/live/${liveId}`, { token: viewer.token })
  assert.equal(visible.stream.id, liveId)

  const comment = await request(`/v1/data/live/${liveId}/comments`, {
    token: viewer.token,
    method: 'POST',
    expected: 201,
    body: { content: 'Comentario persistente del directo' },
  })
  assert.ok(comment.comment.id)
  const comments = await request(`/v1/data/live/${liveId}/comments?limit=200`, { token: owner.token })
  assert.ok(comments.items.some((item) => item.id === comment.comment.id))

  const counted = await request(`/v1/data/live/${liveId}/state`, {
    token: owner.token,
    method: 'PATCH',
    body: { viewerCount: 2, peakViewerCount: 2, totalReactions: 5 },
  })
  assert.equal(counted.stream.viewer_count, 2)
  assert.equal(counted.stream.peak_viewer_count, 2)
  assert.equal(counted.stream.total_reactions, 5)

  const ended = await request(`/v1/data/live/${liveId}/state`, {
    token: owner.token,
    method: 'PATCH',
    body: { isActive: false, viewerCount: 0 },
  })
  assert.equal(ended.stream.is_active, false)
  assert.equal(ended.stream.viewer_count, 0)
  assert.ok(ended.stream.ended_at)
  await request(`/v1/data/live/${liveId}`, { token: viewer.token, expected: 404 })
  const afterEnd = await request('/v1/data/live?limit=60', { token: viewer.token })
  assert.ok(!afterEnd.items.some((item) => item.id === liveId))

  await request(`/v1/data/live/${liveId}`, { token: owner.token, method: 'DELETE' })
  await request(`/v1/data/live/${liveId}`, { token: owner.token, expected: 404 })

  process.stdout.write(JSON.stringify({
    ok: true,
    inactiveDraftProtected: true,
    ownershipProtected: true,
    discovery: true,
    commentsPersisted: true,
    countersPersisted: true,
    endAndCleanup: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
