import { fallbackApiJson } from './auth/fallbackAuth'
import type { GenericRow } from './utils'

export type FallbackProfileMeResponse = {
  profile: GenericRow
  posts: GenericRow[]
  reports: GenericRow[]
  stats: {
    posts: number
    videos: number
    chats: number
    followers: number
    following: number
    reportsSent: number
  }
}

export type FallbackFeedResponse = {
  items: GenericRow[]
}

export type FallbackInboxItem = {
  id: string
  title: string
  subtitle: string
  updatedAt: string
  kind: 'chat' | 'group' | 'community' | 'voice'
  avatarUrl: string
  profileId: string | null
  unreadCount: number
  isPinned: boolean
  isMuted: boolean
  isArchived: boolean
  isFavorite: boolean
  folders: string[]
  openPath?: string
}

export type FallbackInboxResponse = {
  items: FallbackInboxItem[]
}

export type FallbackChatResponse = {
  title: string
  otherParticipant: {
    id: string
    name: string
    avatarUrl: string
    lastReadAt: string
  } | null
  messages: GenericRow[]
  settings: {
    isMuted: boolean
    isPinned: boolean
    isArchived: boolean
    isFavorite: boolean
    translateEnabled: boolean
    targetLang: string
  }
}

export type FallbackStorageObject = {
  id: string
  bucket: string
  path: string
  publicUrl: string
  url: string
  contentType: string
  size: number
  ownerId: string | null
  createdAt: string
  updatedAt: string
}

export async function loadFallbackProfileMe(): Promise<FallbackProfileMeResponse> {
  return fallbackApiJson<FallbackProfileMeResponse>('/v1/data/profile/me')
}

export async function loadFallbackFeed(limit = 60): Promise<FallbackFeedResponse> {
  return fallbackApiJson<FallbackFeedResponse>(`/v1/data/feed?limit=${encodeURIComponent(String(limit))}`)
}

export async function loadFallbackMessagesInbox(tab: string): Promise<FallbackInboxResponse> {
  return fallbackApiJson<FallbackInboxResponse>(`/v1/data/messages/inbox?tab=${encodeURIComponent(tab)}`)
}

export async function loadFallbackChat(chatId: string): Promise<FallbackChatResponse> {
  return fallbackApiJson<FallbackChatResponse>(
    `/v1/data/messages/chats/${encodeURIComponent(chatId)}`,
  )
}

export async function sendFallbackChatMessage(
  chatId: string,
  input: {
    content: string
    contentType?: string
    mediaUrl?: string | null
    mediaSize?: number
    replyToId?: string | null
  },
): Promise<{ message: GenericRow }> {
  return fallbackApiJson<{ message: GenericRow }>(
    `/v1/data/messages/chats/${encodeURIComponent(chatId)}/messages`,
    {
      method: 'POST',
      body: JSON.stringify(input),
    },
  )
}

export async function uploadFallbackFile(
  bucket: string,
  file: File,
  path?: string,
): Promise<FallbackStorageObject> {
  const formData = new FormData()
  formData.set('bucket', bucket)
  if (path?.trim()) formData.set('path', path.trim())
  formData.set('file', file)

  const payload = await fallbackApiJson<{ object: FallbackStorageObject }>('/v1/storage/upload', {
    method: 'POST',
    body: formData,
  })
  return payload.object
}

export async function createFallbackPost(input: {
  content: string
  mediaUrl?: string | null
  mediaType?: string | null
}): Promise<{ post: GenericRow }> {
  return fallbackApiJson<{ post: GenericRow }>('/v1/data/feed/posts', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}
