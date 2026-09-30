'use strict'

// bcrypt consumes at most 72 UTF-8 bytes. Reject new secrets above that limit
// instead of silently creating two passwords with the same effective value.
// Existing credentials remain usable on login, including imported hashes.
function requireBcryptPasswordLength(...fields) {
  return (req, res, next) => {
    const password = String(fields.map(field => req.body?.[field]).find(Boolean) || '')
    if (Buffer.byteLength(password, 'utf8') > 72) {
      return res.status(400).json({
        error: 'PASSWORD_TOO_LONG', message: 'La contraseña es demasiado larga. Usa una más corta.',
      })
    }
    return next()
  }
}

module.exports = { requireBcryptPasswordLength }
