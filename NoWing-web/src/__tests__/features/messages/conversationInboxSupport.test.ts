import {
  dedupeInboxConversations,
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
