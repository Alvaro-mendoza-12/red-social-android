import { mediaPeerIsInitiator } from '../../core/mediaPeerRole'
test('uses ordinal ordering, matching Android even for mixed-case socket ids', () => {
  expect(mediaPeerIsInitiator('same', 'same', 'Z', 'a')).toBe(false)
  expect(mediaPeerIsInitiator('same', 'same', 'a', 'Z')).toBe(true)
  expect(mediaPeerIsInitiator('a-user', 'b-user', 'z', 'A')).toBe(false)
  expect(mediaPeerIsInitiator('b-user', 'a-user', 'A', 'z')).toBe(true)
})
