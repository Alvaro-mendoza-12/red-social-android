// Public errors expose stable application codes, never transformed diagnostics.
const PUBLIC_CODE = /^[A-Z][A-Z0-9_]{1,79}$/

function publicApiError(error) {
  const status = Number(error?.status || error?.statusCode || 500)
  const safeStatus = Number.isInteger(status) && status >= 400 && status <= 599 ? status : 500
  const explicitCode = error?.expose === true && PUBLIC_CODE.test(String(error?.code || ''))
    ? String(error.code) : null
  const applicationCode = safeStatus < 500 && PUBLIC_CODE.test(String(error?.message || ''))
    ? String(error.message) : null
  return { status: safeStatus, code: explicitCode || applicationCode || (safeStatus < 500 ? 'REQUEST_REJECTED' : 'INTERNAL_ERROR') }
}

module.exports = { publicApiError }
