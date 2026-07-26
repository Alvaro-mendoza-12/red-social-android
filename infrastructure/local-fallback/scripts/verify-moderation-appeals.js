const assert = require('node:assert/strict')

const apiUrl = String(process.env.API_URL || process.argv[2] || 'http://127.0.0.1:3080')
  .replace(/\/+$/, '')
const action = String(process.env.ACTION || process.argv[3] || 'verify').trim().toLowerCase()
const runId = String(process.env.RUN_ID || Date.now()).replace(/[^0-9a-z_-]/gi, '')
const password = String(process.env.TEST_PASSWORD || 'NovvinAppeal123!')
const adminEmail = `module-appeal-admin.${runId}@example.local`
const targetEmail = `module-appeal-target.${runId}@example.local`
let adminToken = ''
let targetToken = ''

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
  let payload = {}
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = { raw: text }
    }
  }
  const allowed = Array.isArray(expected) ? expected : [expected]
  assert.ok(
    allowed.includes(response.status),
    `${method} ${path}: expected ${allowed.join('/')} but received ${response.status} ${text}`,
  )
  return { payload, status: response.status }
}

async function register(email, displayName) {
  return request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: { email, password, displayName },
  })
}

async function login(email, expected = 200) {
  return request('/v1/auth/login', {
    method: 'POST',
    expected,
    body: { email, password },
  })
}

async function cleanup(token, label) {
  if (!token) return
  await request('/rest/v1/rpc/delete_own_account', {
    token,
    method: 'POST',
    body: {},
    expected: [200, 401, 404],
  }).catch((error) => {
    console.error(`MODERATION_APPEAL_CLEANUP_FAILED_${label}=${error.message}`)
  })
}

async function prepare() {
  const admin = (await register(adminEmail, `Appeal Admin ${runId}`)).payload
  const target = (await register(targetEmail, `Appeal Target ${runId}`)).payload
  assert.ok(admin.user?.id)
  assert.ok(target.user?.id)
  process.stdout.write(JSON.stringify({
    ok: true,
    action: 'prepare',
    runId,
    password,
    adminEmail,
    adminUserId: admin.user.id,
    targetEmail,
    targetUserId: target.user.id,
  }))
}

async function verify() {
  try {
    const admin = (await login(adminEmail)).payload
    const target = (await login(targetEmail)).payload
    adminToken = admin.accessToken
    targetToken = target.accessToken
    assert.ok(adminToken)
    assert.ok(targetToken)
    assert.equal(
      (await request('/v1/data/admin/access', { token: adminToken })).payload.isAdmin,
      true,
      'The prepared fixture was not promoted to administrator',
    )

    const targetUserId = target.user.id
    const suspended = await request('/v1/data/admin/suspend', {
      token: adminToken,
      method: 'POST',
      body: {
        user_id: targetUserId,
        rule_code: 'fraud_scam',
        severity: 'high',
        reason: `Canario B06/B07 ${runId}: prueba ficticia de decisión explicable`,
        evidence: [{
          source: 'automated_test',
          reference: `run:${runId}`,
          label: 'Evidencia ficticia creada exclusivamente por la prueba integral',
        }],
        days: 1,
      },
    })
    assert.equal(suspended.payload.decision.rule_code, 'fraud_scam')
    assert.equal(suspended.payload.decision.evidence.length, 1)

    const blocked = await login(targetEmail, 403)
    assert.equal(blocked.payload.error, 'ACCOUNT_SUSPENDED')
    assert.equal(blocked.payload.rule_code, 'fraud_scam')
    assert.match(blocked.payload.explanation, /prueba ficticia/i)
    assert.equal(blocked.payload.evidence.length, 1)
    assert.ok(blocked.payload.appeal_deadline)
    assert.ok(blocked.payload.appealToken)
    const appealToken = blocked.payload.appealToken

    const normalApiRejected = await request('/v1/data/profile/me', {
      token: appealToken,
      expected: 401,
    })
    assert.equal(normalApiRejected.payload.error, 'INVALID_TOKEN')

    const decision = await request('/v1/moderation/decision', { token: appealToken })
    assert.equal(decision.payload.decision.rule_code, 'fraud_scam')
    assert.equal(decision.payload.decision.appeal, null)

    const submitted = await request('/v1/moderation/appeals', {
      token: appealToken,
      method: 'POST',
      expected: 201,
      body: {
        statement: 'Esta es una apelación ficticia para verificar la revisión humana y la reactivación atómica.',
        evidence_urls: ['https://example.invalid/evidence/fixture'],
      },
    })
    const appealId = submitted.payload.appeal.id
    assert.ok(appealId)

    const duplicate = await request('/v1/moderation/appeals', {
      token: appealToken,
      method: 'POST',
      expected: 409,
      body: {
        statement: 'Esta segunda apelación ficticia debe rechazarse porque ya existe una revisión pendiente.',
      },
    })
    assert.equal(duplicate.payload.error, 'APPEAL_ALREADY_OPEN')

    const appeals = await request('/v1/data/admin/moderation/appeals?status=pending', {
      token: adminToken,
    })
    assert.ok(appeals.payload.items.some((item) => item.id === appealId))

    const reviewed = await request(`/v1/data/admin/moderation/appeals/${appealId}`, {
      token: adminToken,
      method: 'PUT',
      body: {
        outcome: 'overturned',
        note: 'Canario aprobado: la cuenta ficticia se reactiva tras revisión humana.',
      },
    })
    assert.equal(reviewed.payload.account_reactivated, true)
    assert.equal(reviewed.payload.appeal.status, 'overturned')

    const reactivated = (await login(targetEmail)).payload
    targetToken = reactivated.accessToken
    assert.ok(targetToken)
    const profile = await request('/v1/data/profile/me', { token: targetToken })
    assert.ok(profile.payload)

    process.stdout.write(JSON.stringify({
      ok: true,
      action: 'verify',
      runId,
      decisionExplained: true,
      restrictedAppealToken: true,
      normalApiRejectedRestrictedToken: true,
      duplicateAppealRejected: true,
      humanReviewListed: true,
      overturnedReactivatedAtomically: true,
    }))
  } finally {
    await cleanup(targetToken, 'target')
    await cleanup(adminToken, 'admin')
  }
}

async function main() {
  if (action === 'prepare') return prepare()
  if (action === 'verify') return verify()
  throw new Error(`Unsupported ACTION: ${action}`)
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
