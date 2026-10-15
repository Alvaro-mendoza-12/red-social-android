import { isHttpRetrySafeMethod } from '../../core/httpRetryPolicy'

describe('fallback retry policy', () => {
  test.each(['GET', 'get', 'HEAD', 'OPTIONS', undefined])(
    'allows retry for idempotent method %s',
    (method) => {
      expect(isHttpRetrySafeMethod(method)).toBe(true)
    },
  )

  test.each(['POST', 'PUT', 'PATCH', 'DELETE'])(
    'never replays mutable method %s automatically',
    (method) => {
      expect(isHttpRetrySafeMethod(method)).toBe(false)
    },
  )
})
