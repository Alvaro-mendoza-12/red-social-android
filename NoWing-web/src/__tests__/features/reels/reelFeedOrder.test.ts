import { filterFriendFeedItems, shuffleFeedItems } from '../../../features/reels/reelFeedOrder'

describe('shuffleFeedItems', () => {
  it('creates a stable random order for the same feed session', () => {
    const items = ['a', 'b', 'c', 'd', 'e', 'f']

    expect(shuffleFeedItems(items, 42)).toEqual(shuffleFeedItems(items, 42))
  })

  it('returns every item once without mutating the source', () => {
    const items = ['a', 'b', 'c', 'd', 'e', 'f']
    const source = [...items]
    const shuffled = shuffleFeedItems(items, 2026)

    expect(items).toEqual(source)
    expect([...shuffled].sort()).toEqual([...items].sort())
    expect(new Set(shuffled).size).toBe(items.length)
  })

  it('can produce a new order when the user asks to mix again', () => {
    const items = Array.from({ length: 12 }, (_, index) => `video-${index}`)

    expect(shuffleFeedItems(items, 10)).not.toEqual(shuffleFeedItems(items, 11))
  })

  it('keeps followed authors and the current user in the friends feed', () => {
    const items = [
      { id: 'own', authorId: 'CURRENT' },
      { id: 'friend', authorId: 'friend-1' },
      { id: 'recommended', authorId: 'other-1' },
    ]

    expect(filterFriendFeedItems(items, ['FRIEND-1'], 'current').map((item) => item.id)).toEqual([
      'own',
      'friend',
    ])
  })

  it('does not mutate the recommended feed while filtering friends', () => {
    const items = [{ authorId: 'friend' }, { authorId: 'other' }]

    expect(filterFriendFeedItems(items, ['friend'])).toEqual([{ authorId: 'friend' }])
    expect(items).toEqual([{ authorId: 'friend' }, { authorId: 'other' }])
  })
})
