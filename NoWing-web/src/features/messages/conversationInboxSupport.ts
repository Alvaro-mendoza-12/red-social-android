export type InboxConversationLike = {
  id: string
  kind: string
  profileId: string | null
  updatedAt: string
  unreadCount: number
  isPinned: boolean
  isMuted: boolean
  isArchived: boolean
  isFavorite: boolean
  folders: string[]
}

function toMillis(value: string): number {
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function normalized(value: string | null): string {
  return String(value ?? '').trim().toLowerCase()
}

export function dedupeInboxConversations<T extends InboxConversationLike>(items: T[]): T[] {
  const newestFirst = [...items].sort((left, right) => toMillis(right.updatedAt) - toMillis(left.updatedAt))
  const byConversation = new Map<string, T>()

  newestFirst.forEach((item) => {
    const peerId = item.kind === 'chat' ? normalized(item.profileId) : ''
    const key = peerId ? `chat:${peerId}` : `${item.kind}:${normalized(item.id)}`
    const current = byConversation.get(key)
    if (!current) {
      byConversation.set(key, item)
      return
    }

    byConversation.set(key, {
      ...current,
      unreadCount: Math.max(current.unreadCount, item.unreadCount),
      isPinned: current.isPinned || item.isPinned,
      isMuted: current.isMuted || item.isMuted,
      isArchived: current.isArchived && item.isArchived,
      isFavorite: current.isFavorite || item.isFavorite,
      folders: Array.from(new Set([...current.folders, ...item.folders])),
    })
  })

  return Array.from(byConversation.values())
}

/** Badges count unread conversations before search and filter selection. */
export function inboxFilterCounts(items: InboxConversationLike[]) {
  const unread = dedupeInboxConversations(items).filter(item => item.kind === 'chat' && item.unreadCount > 0)
  const active = unread.filter(item => !item.isArchived)
  return { all: active.length, unread: active.length, favorites: active.filter(item => item.isFavorite).length,
    archived: unread.filter(item => item.isArchived).length }
}
