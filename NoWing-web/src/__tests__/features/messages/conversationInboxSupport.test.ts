import {
  dedupeInboxConversations,
  inboxFilterCounts,
  type InboxConversationLike,
} from '../../../features/messages/conversationInboxSupport'

function conversation(overrides: Partial<InboxConversationLike> & Pick<InboxConversationLike, 'id' | 'updatedAt'>): InboxConversationLike {
  return {
    kind: 'chat',
    profileId: 'peer-1',
    unreadCount: 0,
    isPinned: false,
    isMuted: false,
    isArchived: false,
    isFavorite: false,
    folders: [],
    ...overrides,
  }
}

describe('dedupeInboxConversations', () => {
  test('filter badges count unread direct conversations independently of message totals', () => {
    const rows = [
      conversation({ id: 'regular', profileId: 'one', updatedAt: '', unreadCount: 8 }),
      conversation({ id: 'favorite', profileId: 'two', updatedAt: '', unreadCount: 2, isFavorite: true }),
      conversation({ id: 'archive', profileId: 'three', updatedAt: '', unreadCount: 4, isFavorite: true, isArchived: true }),
      conversation({ id: 'read', profileId: 'four', updatedAt: '', isFavorite: true }),
      conversation({ id: 'group', kind: 'group', updatedAt: '', unreadCount: 10 }),
    ]
    expect(inboxFilterCounts([...rows, rows[0]!])).toEqual({all:2,unread:2,favorites:1,archived:1})
    expect(inboxFilterCounts(rows.map(row => ({...row, unreadCount:0})))).toEqual({all:0,unread:0,favorites:0,archived:0})
  })
  test('keeps the newest direct conversation and merges its preferences', () => {
    const result = dedupeInboxConversations([
      conversation({ id: 'old', updatedAt: '2026-07-10T10:00:00Z', isFavorite: true, folders: ['Amigos'] }),
      conversation({ id: 'new', updatedAt: '2026-07-12T10:00:00Z', unreadCount: 2, isPinned: true }),
    ])

    expect(result).toHaveLength(1)
    expect(result[0]).toMatchObject({ id: 'new', unreadCount: 2, isPinned: true, isFavorite: true })
    expect(result[0]?.folders).toEqual(['Amigos'])
  })

  test('does not merge different room ids', () => {
    const result = dedupeInboxConversations([
      conversation({ id: 'group-a', kind: 'group', profileId: null, updatedAt: '2026-07-12T10:00:00Z' }),
      conversation({ id: 'group-b', kind: 'group', profileId: null, updatedAt: '2026-07-12T11:00:00Z' }),
    ])

    expect(result).toHaveLength(2)
  })
})
