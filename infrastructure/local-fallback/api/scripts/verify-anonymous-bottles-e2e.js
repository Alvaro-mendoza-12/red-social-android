const assert = require('node:assert/strict')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const runId = String(process.env.E2E_RUN_ID || Date.now())
const emailPrefix = `anonymous-bottle-e2e-${runId}`
const password = 'BottleE2E-2026!'

async function request(path, { token, method = 'GET', body, expected = 200 } = {}) {
  const response = await fetch(`${apiUrl}${path}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body == null ? {} : { 'content-type': 'application/json' }),
    },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `${method} ${path}: ${response.status} ${text}`)
  return payload
}

async function register(role) {
  const email = `${emailPrefix}-${role}@example.local`
  const payload = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: { email, password, displayName: `Bottle ${role}` },
  })
  assert.ok(payload.accessToken)
  return { email, token: payload.accessToken, id: payload.user.id }
}

async function incoming(account) {
  return request('/v1/data/proximity/random/bottles/incoming', { token: account.token })
}

async function main() {
  const sender = await register('sender')
  const recipientA = await register('recipient-a')
  const recipientB = await register('recipient-b')
  const matching = { gender: 'unspecified', lookingFor: 'any' }

  for (const recipient of [recipientA, recipientB]) {
    await request('/v1/data/proximity/random/availability', {
      token: recipient.token,
      method: 'POST',
      body: matching,
    })
  }

  const launched = await request('/v1/data/proximity/random/bottles', {
    token: sender.token,
    method: 'POST',
    expected: 201,
    body: { ...matching, content: `Mensaje E2E ${runId}` },
  })
  assert.equal(launched.status, 'offered')
  assert.ok(launched.bottle?.id)

  const firstIncoming = await Promise.all([incoming(recipientA), incoming(recipientB)])
  const firstIndex = firstIncoming.findIndex((result) => result.bottle?.id === launched.bottle.id)
  assert.notEqual(firstIndex, -1, 'the first offer did not reach an available recipient')
  const firstRecipient = [recipientA, recipientB][firstIndex]
  const secondRecipient = [recipientA, recipientB][1 - firstIndex]

  const declined = await request(
    `/v1/data/proximity/random/bottles/${launched.bottle.id}/respond`,
    { token: firstRecipient.token, method: 'POST', body: { action: 'decline' } },
  )
  assert.equal(declined.status, 'declined')

  let rerouted = null
  for (let attempt = 0; attempt < 10 && !rerouted?.bottle; attempt += 1) {
    rerouted = await incoming(secondRecipient)
    if (!rerouted.bottle) await new Promise((resolve) => setTimeout(resolve, 250))
  }
  assert.equal(rerouted?.bottle?.id, launched.bottle.id, 'declined bottle was not rerouted')

  const accepted = await request(
    `/v1/data/proximity/random/bottles/${launched.bottle.id}/respond`,
    { token: secondRecipient.token, method: 'POST', body: { action: 'accept' } },
  )
  assert.equal(accepted.status, 'accepted')
  assert.equal(accepted.session?.status, 'active')
  assert.ok(accepted.session?.id)

  const senderResult = await request('/v1/data/proximity/random/bottles/outgoing', {
    token: sender.token,
  })
  assert.equal(senderResult.status, 'accepted')
  assert.equal(senderResult.session?.id, accepted.session.id)

  console.log(JSON.stringify({
    ok: true,
    runId,
    emailPrefix,
    bottleId: launched.bottle.id,
    sessionId: accepted.session.id,
    declinedThenRerouted: true,
    acceptedChatOpenedForBothSides: true,
  }))
}

main().catch((error) => {
  console.error(`E2E_EMAIL_PREFIX=${emailPrefix}`)
  console.error(error)
  process.exitCode = 1
})
