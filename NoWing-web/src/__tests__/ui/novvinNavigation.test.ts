import { NOVVIN_TABS, primarySection } from '../../ui/novvin/navigation'

describe('APK navigation on the web', () => {
  test('keeps exactly the five Android destinations in the same order', () => {
    expect(NOVVIN_TABS.map(tab => tab.label)).toEqual(['Mensajes', 'Videos', 'Social', 'Juegos', 'Perfil'])
    expect(new Set(NOVVIN_TABS.map(tab => tab.to)).size).toBe(5)
  })

  test.each([
    ['/app/messages', '/app/messages'],
    ['/app/messages/group/a', '/app/messages'],
    ['/app/videos', '/app/videos'],
    ['/app/reels', '/app/videos'],
    ['/app/reels/a', '/app/videos'],
    ['/app/social', '/app/social'],
    ['/app/feed/post/a', '/app/social'],
    ['/app/moments/view/a', '/app/social'],
    ['/app/challenges/ranking', '/app/social'],
    ['/app/profile', '/app/profile'],
    ['/app/profile/a', '/app/profile'],
    ['/app/games', '/app/games'],
    ['/app/settings', ''],
    ['/app/reels-other', ''],
  ])('selects the correct main section for %s', (path, expected) => {
    expect(primarySection(path)).toBe(expected)
  })
})
