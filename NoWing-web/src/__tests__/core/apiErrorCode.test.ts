import { apiErrorCode, friendlyError } from '../../core/utils'
test('expected setup conditions remain distinguishable before translation', () => {
  const error=new Error('PROXIMITY_NOT_ENABLED')
  expect(apiErrorCode(error)).toBe('PROXIMITY_NOT_ENABLED')
  expect(friendlyError(error,'Configura la ubicación')).toBe('Configura la ubicación')
})
test('structured errors use their machine code', () => {
  expect(apiErrorCode({code:'SESSION_NOT_FOUND',message:'Sesión no encontrada'})).toBe('SESSION_NOT_FOUND')
})
test('diagnostic prose and URLs never become application codes', () => {
  expect(apiErrorCode(new Error('Request timeout https://private.test'))).toBe('')
  expect(apiErrorCode('SESSION_NOT_FOUND')).toBe('')
})
test('invalid values stay empty', () => {
  expect(apiErrorCode(null)).toBe('')
  expect(apiErrorCode({code:42})).toBe('')
})
