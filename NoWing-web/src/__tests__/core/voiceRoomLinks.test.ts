import { voiceRoomAndroidIntent, voiceRoomId, voiceRoomShareUrl, voiceRoomWebPath } from '../../core/voiceRoomLinks'
import { authReturnPath, safeAuthDestination } from '../../core/auth/loginReturnPath'

test('one shared link resolves to the same room in web and package-bound Android fallback', () => {
  const id = 'fb172201-69ec-4f2b-b7f4-fb05c9394e4f'
  expect(voiceRoomShareUrl(id)).toBe(`https://novvinapp.com/voice/${id}`)
  expect(voiceRoomWebPath(id)).toBe(`/app/voice/${id}?join=1`)
  expect(voiceRoomAndroidIntent(id)).toContain('package=conectate.com;')
  expect(voiceRoomAndroidIntent(id)).toContain(`S.browser_fallback_url=${encodeURIComponent('https://novvinapp.com' + voiceRoomWebPath(id))};end`)
})

test.each(['', '../other', '//evil.test', 'room?a=1', 'room#Intent', 'a/b', 'a'.repeat(101)])('rejects untrusted room route %s', value => {
  expect(voiceRoomId(value)).toBeNull()
  expect(() => voiceRoomShareUrl(value)).toThrow()
})

test('login and registration round trips preserve admission and room', () => {
  const next = '/app/voice/room_123?join=1'
  const login = new URL(authReturnPath('/login', next), 'https://novvin.invalid')
  const register = new URL(authReturnPath('/register', login.searchParams.get('next')!), 'https://novvin.invalid')
  expect(safeAuthDestination(register.searchParams.get('next'))).toBe(next)
})

test.each(['//evil.test', '/\\evil.test', 'https://evil.test', '/login?next=/login', '/%2e%2e//evil.test', '/app/voice/a\nheader'])('rejects auth redirects %s', value => {
  expect(safeAuthDestination(value)).toBe('/app/messages')
})
