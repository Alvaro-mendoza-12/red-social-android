const assert = require('node:assert/strict')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const runId = Date.now().toString()
const email = `module-registration.${runId}@example.local`
const password = '12345678'

async function request(path, { method = 'GET', body, expected = 200 } = {}) {
  const response = await fetch(`${apiUrl}${path}`, {
    method,
    headers: body == null ? {} : { 'content-type': 'application/json' },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `${method} ${path}: ${response.status} ${text}`)
  return payload
}

async function main() {
  const unsignedToken = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url') +
    `.${Buffer.from(JSON.stringify({ sub: '00000000-0000-0000-0000-000000000001' })).toString('base64url')}.`
  const forgedResponse = await fetch(`${apiUrl}/v1/auth/me`, {
    headers: { authorization: `Bearer ${unsignedToken}` },
  })
  assert.equal(forgedResponse.status, 401)

  const shortPassword = await request('/v1/auth/register', {
    method: 'POST',
    expected: 400,
    body: {
      email: `module-registration.short.${runId}@example.local`,
      password: '123456',
      displayName: 'Registro corto',
    },
  })
  assert.equal(shortPassword.error, 'PASSWORD_TOO_SHORT')
  assert.equal(shortPassword.message, 'La contraseña debe tener al menos 8 caracteres.')

  const registration = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: { email, password, displayName: 'Registro validado' },
  })
  assert.equal(registration.user.email, email)
  assert.ok(registration.accessToken)

  const login = await request('/v1/auth/login', {
    method: 'POST',
    body: { email, password },
  })
  assert.equal(login.user.id, registration.user.id)
  assert.ok(login.accessToken)

  console.log(JSON.stringify({
    ok: true,
    shortPasswordRejected: true,
    minimumPasswordAccepted: true,
    loginAfterRegistration: true,
    forgedTokenRejected: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
