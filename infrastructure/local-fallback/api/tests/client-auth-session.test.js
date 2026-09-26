const assert = require('assert/strict')
const {
  authenticateClientSession,
  isPublicAuthError,
} = require('../src/client-auth-session')

async function expectAuthFailure(options, code) {
  await assert.rejects(
    authenticateClientSession(options),
    (error) => {
      assert.equal(error.code, code)
      assert.equal(error.status, 401)
      assert.equal(isPublicAuthError(error), true)
      return true
    },
  )
}

async function run() {
  const payload = {
    sub: '11111111-1111-4111-8111-111111111111',
    sid: '22222222-2222-4222-8222-222222222222',
  }
  const defaults = {
    token: 'signed-access-token',
    touchSession: async (auth) => auth.sid === payload.sid,
    userExists: async (userId) => userId === payload.sub,
    verifyToken: () => payload,
  }

  assert.deepEqual(await authenticateClientSession(defaults), payload)

  await expectAuthFailure({ ...defaults, token: '' }, 'AUTH_REQUIRED')
  await expectAuthFailure({
    ...defaults,
    verifyToken() {
      throw new Error('bad signature')
    },
  }, 'INVALID_TOKEN')

  let sessionTouches = 0
  await expectAuthFailure({
    ...defaults,
    touchSession: async () => {
      sessionTouches += 1
      return true
    },
    userExists: async () => false,
  }, 'ACCOUNT_DELETED')
  assert.equal(sessionTouches, 0, 'deleted accounts must be rejected before touching a session')

  await expectAuthFailure({
    ...defaults,
    touchSession: async () => false,
  }, 'WEB_SESSION_REVOKED')

  let legacySessionTouches = 0
  await expectAuthFailure({
    ...defaults,
    verifyToken: () => ({ sub: payload.sub, provider: 'local-fallback' }),
    touchSession: async () => {
      legacySessionTouches += 1
      return true
    },
  }, 'WEB_SESSION_REVOKED')
  assert.equal(legacySessionTouches, 0, 'sessionless access tokens must not bypass revocation')

  const databaseError = new Error('database unavailable')
  await assert.rejects(
    authenticateClientSession({
      ...defaults,
      userExists: async () => { throw databaseError },
    }),
    (error) => error === databaseError,
  )

  assert.equal(isPublicAuthError(new Error('ordinary failure')), false)
  console.log('client auth session tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exit(1)
})
