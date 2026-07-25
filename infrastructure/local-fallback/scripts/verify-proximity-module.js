const assert = require('node:assert/strict')
const crypto = require('node:crypto')

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
      email: `module-proximity.${label}.${runId}@example.local`,
      password,
      displayName: `Proximity ${label}`,
    },
  })
  return { id: auth.user.id, token: auth.accessToken }
}

async function presence(account, latitude, longitude, options = {}) {
  return request('/v1/data/proximity/presence', {
    token: account.token,
    method: 'PUT',
    body: {
      latitude,
      longitude,
      radiusKm: options.radiusKm || 20,
      gender: options.gender || 'unspecified',
      lookingFor: options.lookingFor || 'any',
      randomEnabled: Boolean(options.randomEnabled),
    },
  })
}

async function main() {
  const userA = await register('a')
  const userB = await register('b')
  const blockedNearby = await register('blocked')
  const farAway = await register('far')

  await request('/v1/data/profile/me', {
    token: userB.token,
    method: 'PATCH',
    body: {
      displayName: 'Perfil revelado B',
      interests: ['fotografía'],
      hobbies: ['senderismo'],
    },
  })

  await presence(userA, 4.711, -74.072, { gender: 'man', lookingFor: 'woman', randomEnabled: true })
  await presence(userB, 4.721, -74.072, { gender: 'woman', lookingFor: 'man', randomEnabled: true })
  await presence(blockedNearby, 4.716, -74.072)
  await presence(farAway, 5.011, -74.072)

  await request(`/v1/data/profiles/${blockedNearby.id}/block`, { token: userA.token, method: 'POST' })
  const nearby = await request('/v1/data/proximity/nearby?radiusKm=20', { token: userA.token })
  assert.ok(nearby.items.some((item) => item.user_id === userB.id))
  assert.ok(!nearby.items.some((item) => item.user_id === blockedNearby.id))
  assert.ok(!nearby.items.some((item) => item.user_id === farAway.id))
  assert.ok(nearby.items.every((item) => !Object.hasOwn(item, 'latitude') && !Object.hasOwn(item, 'longitude')))
  assert.ok(nearby.items.every((item) => item.distance_km >= 0.5 && item.distance_km <= 20))

  const waiting = await request('/v1/data/proximity/random/match', {
    token: userA.token,
    method: 'POST',
    expected: 202,
    body: { gender: 'man', lookingFor: 'woman' },
  })
  assert.equal(waiting.status, 'waiting')

  const matchedB = await request('/v1/data/proximity/random/match', {
    token: userB.token,
    method: 'POST',
    body: { gender: 'woman', lookingFor: 'man' },
  })
  assert.equal(matchedB.status, 'matched')
  const sessionId = matchedB.session.id
  assert.ok(sessionId)

  const matchedA = await request('/v1/data/proximity/random/status', { token: userA.token })
  assert.equal(matchedA.status, 'matched')
  assert.equal(matchedA.session.id, sessionId)
  assert.equal(matchedA.session.peer.user_id, null)
  assert.deepEqual(matchedA.session.peer.interests, [])

  await request(`/v1/data/proximity/random/sessions/${sessionId}`, {
    token: blockedNearby.token,
    expected: 404,
  })
  await request(`/v1/data/proximity/random/sessions/${sessionId}/messages`, {
    token: blockedNearby.token,
    expected: 404,
  })

  const clientMessageId = crypto.randomUUID()
  const firstSend = await request(`/v1/data/proximity/random/sessions/${sessionId}/messages`, {
    token: userA.token,
    method: 'POST',
    expected: 201,
    body: { clientMessageId, content: 'Mensaje anónimo idempotente' },
  })
  const repeatedSend = await request(`/v1/data/proximity/random/sessions/${sessionId}/messages`, {
    token: userA.token,
    method: 'POST',
    expected: 201,
    body: { clientMessageId, content: 'Mensaje anónimo idempotente' },
  })
  assert.equal(repeatedSend.message.id, firstSend.message.id)
  const messages = await request(`/v1/data/proximity/random/sessions/${sessionId}/messages`, { token: userB.token })
  assert.equal(messages.items.filter((item) => item.client_message_id === clientMessageId).length, 1)
  assert.equal(messages.items.find((item) => item.client_message_id === clientMessageId).sender, 'peer')

  const clampedReveal = await request(`/v1/data/proximity/random/sessions/${sessionId}/reveal`, {
    token: userA.token,
    method: 'POST',
    body: { level: 3 },
  })
  assert.equal(clampedReveal.session.own_reveal_level, 1)

  await request(`/v1/data/proximity/random/sessions/${sessionId}/reveal`, {
    token: userB.token,
    method: 'POST',
    body: { level: 1 },
  })
  let aView = await request(`/v1/data/proximity/random/sessions/${sessionId}`, { token: userA.token })
  assert.deepEqual(aView.session.peer.interests, ['fotografía'])
  assert.equal(aView.session.peer.user_id, null)

  await request(`/v1/data/proximity/random/sessions/${sessionId}/reveal`, {
    token: userB.token,
    method: 'POST',
    body: { level: 2 },
  })
  aView = await request(`/v1/data/proximity/random/sessions/${sessionId}`, { token: userA.token })
  assert.equal(aView.session.peer.display_name, 'Perfil revelado B')
  assert.equal(aView.session.peer.user_id, null)

  await request(`/v1/data/proximity/random/sessions/${sessionId}/reveal`, {
    token: userB.token,
    method: 'POST',
    body: { level: 3 },
  })
  aView = await request(`/v1/data/proximity/random/sessions/${sessionId}`, { token: userA.token })
  assert.equal(aView.session.peer.user_id, userB.id)

  await request(`/v1/data/proximity/random/sessions/${sessionId}`, { token: userB.token, method: 'DELETE' })
  const ended = await request(`/v1/data/proximity/random/sessions/${sessionId}`, { token: userA.token })
  assert.equal(ended.session.status, 'ended')

  for (const account of [userA, userB, blockedNearby, farAway]) {
    await request('/v1/data/proximity/presence', { token: account.token, method: 'DELETE' })
  }

  process.stdout.write(JSON.stringify({
    ok: true,
    coordinatesNeverExposed: true,
    radiusEnforced: true,
    blocksEnforced: true,
    compatibleMatching: true,
    thirdPartyIsolation: true,
    messagesIdempotent: true,
    gradualRevealEnforced: true,
    sessionEndPersisted: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
