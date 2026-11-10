import { authErrorMessage, isUntrustedWebSessionError, untrustedWebSessionMessage } from '../../core/auth/authErrorMessage'

test.each([
  new Error('UNTRUSTED_WEB_SESSION_REQUEST'),
  { code: 'UNTRUSTED_WEB_SESSION_REQUEST', message: 'private server detail' },
])('only the explicit backend origin-rejection code becomes an official-site hint', error => {
  expect(isUntrustedWebSessionError(error)).toBe(true)
  expect(authErrorMessage(error, 'No se pudo iniciar sesión')).toBe(untrustedWebSessionMessage)
})

test.each([
  Object.assign(new Error('ACCOUNT_SUSPENDED'), { status: 403 }),
  new Error('UNTRUSTED_WEB_SESSION_REQUEST_SUFFIX'),
  new Error('Request failed: https://private.example/UNTRUSTED_WEB_SESSION_REQUEST'),
  undefined,
])('does not infer a rejected origin from unrelated or malformed errors', error => {
  expect(isUntrustedWebSessionError(error)).toBe(false)
  expect(authErrorMessage(error, 'No se pudo iniciar sesión')).toBe('No se pudo iniciar sesión')
})
