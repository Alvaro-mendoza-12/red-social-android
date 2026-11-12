import { buildLiveShareUrl } from '../../core/socialLinks'

test('live sharing uses the public domain with no host role or current page state', () => {
  expect(buildLiveShareUrl('04253203-9341-4557-9ece-c54da1be335a')).toBe('https://novvinapp.com/app/live/04253203-9341-4557-9ece-c54da1be335a')
  expect(buildLiveShareUrl('safe_live')).not.toContain('host=1')
  expect(() => buildLiveShareUrl('../live?host=1')).toThrow()
  expect(() => buildLiveShareUrl('')).toThrow()
})
