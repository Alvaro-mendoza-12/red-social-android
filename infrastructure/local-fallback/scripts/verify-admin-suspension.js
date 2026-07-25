const assert = require('node:assert/strict')
const WebSocket = require('../../../Conectate-web/node_modules/ws')
const { io } = require('../../../Conectate-web/node_modules/socket.io-client')

const apiUrl = String(process.argv[2] || process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const adminEmail = String(process.env.ADMIN_TEST_EMAIL || '').trim()
const normalEmail = String(process.env.NORMAL_TEST_EMAIL || '').trim()
const targetEmail = String(process.env.TARGET_TEST_EMAIL || '').trim()
const targetUserId = String(process.env.TARGET_TEST_USER_ID || '').trim()
const password = String(process.env.TEST_PASSWORD || '')

for (const [name, value] of Object.entries({ adminEmail, normalEmail, targetEmail, targetUserId, password })) {
  assert.ok(value, `${name} is required`)
}

async function request(path, { token, method = 'GET', body, expected = 200 } = {}) {
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
  const expectedStatuses = Array.isArray(expected) ? expected : [expected]
  assert.ok(
    expectedStatuses.includes(response.status),
    `${method} ${path}: expected ${expectedStatuses.join('/')} but received ${response.status} ${text}`,
  )
  return { payload, status: response.status }
}

async function login(email, expected = 200) {
  return request('/v1/auth/login', {
    method: 'POST',
    expected,
    body: { email, password },
  })
}

function realtimeAccepted(token) {
  return new Promise((resolve, reject) => {
    const url = `${apiUrl.replace(/^http/i, 'ws')}/realtime/?token=${encodeURIComponent(token)}`
    const socket = new WebSocket(url)
    const timeout = setTimeout(() => {
      socket.terminate()
      reject(new Error('Realtime accepted connection timeout'))
    }, 15_000)
    socket.on('message', (raw) => {
      const message = JSON.parse(raw.toString('utf8'))
      if (message.type !== 'connected') return
      clearTimeout(timeout)
      socket.close()
      resolve()
    })
    socket.once('error', (error) => {
      clearTimeout(timeout)
      reject(error)
    })
    socket.once('close', (code) => {
      if (code === 1000) return
      clearTimeout(timeout)
      reject(new Error(`Realtime active token closed with ${code}`))
    })
  })
}

function realtimeRejected(token) {
  return new Promise((resolve, reject) => {
    const url = `${apiUrl.replace(/^http/i, 'ws')}/realtime/?token=${encodeURIComponent(token)}`
    const socket = new WebSocket(url)
    let connected = false
    const timeout = setTimeout(() => {
      socket.terminate()
      reject(new Error('Realtime suspended connection was not rejected'))
    }, 15_000)
    socket.on('message', (raw) => {
      const message = JSON.parse(raw.toString('utf8'))
      if (message.type === 'connected') connected = true
    })
    socket.once('close', (code) => {
      clearTimeout(timeout)
      assert.equal(connected, false, 'Suspended realtime token must never receive connected')
      assert.equal(code, 4403, `Suspended realtime token closed with unexpected code ${code}`)
      resolve()
    })
    socket.once('error', () => {})
  })
}

function signalingAccepted(token) {
  return new Promise((resolve, reject) => {
    const socket = io(`${apiUrl}/live`, {
      auth: { token },
      transports: ['websocket'],
      forceNew: true,
      reconnection: false,
      timeout: 15_000,
    })
    socket.once('connect', () => {
      socket.disconnect()
      resolve()
    })
    socket.once('connect_error', (error) => {
      socket.disconnect()
      reject(error)
    })
  })
}

function signalingRejected(token) {
  return new Promise((resolve, reject) => {
    const socket = io(`${apiUrl}/live`, {
      auth: { token },
      transports: ['websocket'],
      forceNew: true,
      reconnection: false,
      timeout: 15_000,
    })
    socket.once('connect', () => {
      socket.disconnect()
      reject(new Error('Suspended signaling token connected'))
    })
    socket.once('connect_error', () => {
      socket.disconnect()
      resolve()
    })
  })
}

async function deleteOwnAccount(token) {
  return request('/rest/v1/rpc/delete_own_account', {
    token,
    method: 'POST',
    body: {},
  })
}

async function main() {
  const admin = (await login(adminEmail)).payload
  const normal = (await login(normalEmail)).payload
  assert.ok(admin.accessToken)
  assert.ok(normal.accessToken)

  const adminAccess = await request('/v1/data/admin/access', { token: admin.accessToken })
  assert.equal(adminAccess.payload.isAdmin, true)
  const normalAccess = await request('/v1/data/admin/access', {
    token: normal.accessToken,
    expected: 403,
  })
  assert.equal(normalAccess.payload.error, 'ADMIN_REQUIRED')
  await request('/v1/data/admin/stats', { token: admin.accessToken })
  await request('/v1/data/admin/reports', { token: admin.accessToken })
  await request('/v1/data/admin/video-reports', { token: admin.accessToken })

  await request('/v1/data/admin/unsuspend', {
    token: admin.accessToken,
    method: 'POST',
    expected: [200, 404],
    body: { user_id: targetUserId, reason: 'Preparacion de canario' },
  })
  const target = (await login(targetEmail)).payload
  assert.ok(target.accessToken)
  await request('/v1/data/profile/me', { token: target.accessToken })
  await request('/rest/v1/profiles?select=id&limit=1', { token: target.accessToken })
  await realtimeAccepted(target.accessToken)
  await signalingAccepted(target.accessToken)

  await request('/v1/data/admin/suspend', {
    token: admin.accessToken,
    method: 'POST',
    body: {
      user_id: targetUserId,
      reason: 'Canario automatizado de suspension',
      days: 1,
    },
  })

  const blockedLogin = await login(targetEmail, 403)
  assert.equal(blockedLogin.payload.error, 'ACCOUNT_SUSPENDED')
  const blockedApi = await request('/v1/data/profile/me', {
    token: target.accessToken,
    expected: 403,
  })
  assert.equal(blockedApi.payload.error, 'ACCOUNT_SUSPENDED')
  const blockedPostgrest = await request('/rest/v1/profiles?select=id&limit=1', {
    token: target.accessToken,
    expected: 403,
  })
  assert.equal(blockedPostgrest.payload.error, 'ACCOUNT_SUSPENDED')
  const suspensionState = await request('/rest/v1/rpc/secure_get_my_suspension_state', {
    token: target.accessToken,
    method: 'POST',
    body: {},
  })
  assert.equal(suspensionState.payload.is_suspended, true)
  await realtimeRejected(target.accessToken)
  await signalingRejected(target.accessToken)

  await request('/v1/data/admin/unsuspend', {
    token: admin.accessToken,
    method: 'POST',
    body: { user_id: targetUserId, reason: 'Canario completado' },
  })
  const reactivated = (await login(targetEmail)).payload
  assert.ok(reactivated.accessToken)

  await request('/v1/data/admin/suspend', {
    token: admin.accessToken,
    method: 'POST',
    body: {
      user_id: targetUserId,
      reason: 'Validacion de eliminacion propia',
      days: 1,
    },
  })
  await deleteOwnAccount(reactivated.accessToken)
  const deletedLogin = await login(targetEmail, 404)
  assert.equal(deletedLogin.payload.error, 'ACCOUNT_NOT_FOUND')

  await deleteOwnAccount(normal.accessToken)
  await deleteOwnAccount(admin.accessToken)

  process.stdout.write(JSON.stringify({
    ok: true,
    adminAccess: true,
    normalAdminRejected: true,
    suspendedLoginRejected: true,
    staleApiTokenRejected: true,
    postgrestRejected: true,
    suspensionRecoveryRpcAllowed: true,
    realtimeRejected: true,
    signalingRejected: true,
    reactivationPassed: true,
    suspendedAccountDeletionPassed: true,
    temporaryAccountsDeleted: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
