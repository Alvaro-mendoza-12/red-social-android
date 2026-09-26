function publicAuthError(code) {
  const error = new Error(code)
  error.code = code
  error.expose = true
  error.status = 401
  return error
}

/**
 * Authenticates a presented access token against both its signature and the
 * server-side session registry. Keeping this policy independent from Express
 * makes every HTTP compatibility surface use the same revocation semantics.
 */
async function authenticateClientSession({
  token,
  verifyToken,
  userExists,
  touchSession,
}) {
  const normalizedToken = String(token || '').trim()
  if (!normalizedToken) throw publicAuthError('AUTH_REQUIRED')
  if (typeof verifyToken !== 'function') throw new TypeError('verifyToken is required')
  if (typeof userExists !== 'function') throw new TypeError('userExists is required')
  if (typeof touchSession !== 'function') throw new TypeError('touchSession is required')

  let auth
  try {
    auth = verifyToken(normalizedToken)
  } catch {
    throw publicAuthError('INVALID_TOKEN')
  }

  if (!auth?.sub || !(await userExists(auth.sub))) {
    throw publicAuthError('ACCOUNT_DELETED')
  }
  if (!auth.sid) {
    throw publicAuthError('WEB_SESSION_REVOKED')
  }
  if (!(await touchSession(auth))) {
    throw publicAuthError('WEB_SESSION_REVOKED')
  }
  return auth
}

function isPublicAuthError(error) {
  return Boolean(
    error?.status === 401 &&
      error?.expose === true &&
      /^[A-Z][A-Z0-9_]{1,79}$/.test(String(error?.code || '')),
  )
}

module.exports = {
  authenticateClientSession,
  isPublicAuthError,
}
