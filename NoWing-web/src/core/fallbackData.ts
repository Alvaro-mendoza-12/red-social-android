import { cachedConversation, cacheConversation, cancelPendingWebMessage, isTransientOutboxError, outboxOwner, pendingConversationMessages, sendDurableWebMessage, stageOfflineChatAttachment } from './webOutbox'
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

export type FallbackPostDetailResponse = {
  post: GenericRow
  comments: GenericRow[]
}

export type FallbackInboxItem = {
  id: string
  title: string
  subtitle: string
  updatedAt: string
  kind: 'chat' | 'group' | 'community' | 'voice'
  avatarUrl: string
  avatarMediaType?: string
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
  chat?: GenericRow
  room?: GenericRow
  historyAccess?: { isMember: boolean; canReadHistory?: boolean }
  participants?: Array<{ id: string; name: string; avatarUrl: string; role: string; username?: string }>
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

export type FallbackVoiceRoom = GenericRow

export type FallbackVoiceRoomsResponse = {
  rooms: FallbackVoiceRoom[]
  items?: FallbackVoiceRoom[]
}

export type FallbackVoiceRoomResponse = {
  room: FallbackVoiceRoom
  participants: GenericRow[]
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

export type FallbackProfilesResponse = {
  items: GenericRow[]
}

export type FallbackPublicProfileResponse = {
  profile: GenericRow
  posts: GenericRow[]
  isFollowing: boolean
  stats: {
    posts: number
    followers: number
    following: number
    reports: number
  }
}

export type FallbackNotificationsResponse = {
  items: GenericRow[]
  unreadCount: number
}

export type FallbackLiveStream = GenericRow & {
  id: string
  host_user_id?: string
  host_name?: string
  host_avatar_url?: string | null
  title?: string
  source?: 'camera' | 'screen' | string
  hashtags?: string[]
  viewer_count?: number
  peak_viewer_count?: number
  total_comments?: number
  total_reactions?: number
  is_active?: boolean
  started_at?: string | null
  ended_at?: string | null
}

export type FallbackLiveComment = GenericRow & {
  id: string
  live_id?: string
  user_id?: string
  user_name?: string
  avatar_url?: string | null
  content?: string
  created_at?: string
}

export type FallbackNearbyProfile = {
  user_id: string
  display_name: string
  avatar_url: string | null
  city: string | null
  country: string | null
  interests: string[]
  distance_km: number
}

export type FallbackRandomSession = {
  id: string
  status: 'active' | 'ended' | string
  own_reveal_level: number
  peer_reveal_level: number
  peer: {
    alias: string
    display_name: string
    avatar_url: string | null
    interests: string[]
    hobbies: string[]
    user_id: string | null
  }
  last_message?: string | null
  last_message_sender?: 'me' | 'peer' | null
  unread_count?: number
  created_at: string
  updated_at: string
}

export type FallbackRandomMessage = {
  id: string
  client_message_id: string
  sender: 'me' | 'peer'
  content: string
  content_type?: string
  media_url?: string | null
  created_at: string
}

export async function loadFallbackProfileMe(): Promise<FallbackProfileMeResponse> {
  return fallbackApiJson<FallbackProfileMeResponse>('/v1/data/profile/me')
}

export async function updateFallbackProfileMe(input: {
  displayName?: string | null
  bio?: string | null
  country?: string | null
  city?: string | null
  status?: string | null
  avatarUrl?: string | null
  coverUrl?: string | null
  username?: string | null
  interests?: string[]
  hobbies?: string[]
  linkedGames?: string[]
  gameLogoUrls?: Record<string, string>
  favoriteMusicUrl?: string | null
  favoriteBook?: string | null
  favoriteSport?: string | null
  career?: string | null
  availabilitySchedule?: string | null
  profileSectionOrder?: string[]
  profileSectionVisibility?: Record<string, 'everyone' | 'followers' | 'only_me'>
  isPrivate?: boolean | null
  profileVisibility?: string | null
  lastSeenVisibility?: string | null
  onlineStatusVisibility?: string | null
}): Promise<FallbackProfileMeResponse> {
  return fallbackApiJson<FallbackProfileMeResponse>('/v1/data/profile/me', {
    method: 'PATCH',
    body: JSON.stringify(input),
  })
}

export async function searchFallbackProfiles(query = '', limit = 120): Promise<FallbackProfilesResponse> {
  const params = new URLSearchParams()
  if (query.trim()) params.set('q', query.trim())
  params.set('limit', String(limit))
  return fallbackApiJson<FallbackProfilesResponse>(`/v1/data/profiles/search?${params.toString()}`)
}

export async function loadFallbackPublicProfile(profileId: string): Promise<FallbackPublicProfileResponse> {
  return fallbackApiJson<FallbackPublicProfileResponse>(
    `/v1/data/profiles/${encodeURIComponent(profileId)}`,
  )
}

export async function setFallbackProfileFollow(
  profileId: string,
  shouldFollow: boolean,
): Promise<{ ok: boolean; isFollowing: boolean }> {
  return fallbackApiJson<{ ok: boolean; isFollowing: boolean }>(
    `/v1/data/profiles/${encodeURIComponent(profileId)}/follow`,
    {
      method: shouldFollow ? 'POST' : 'DELETE',
      body: shouldFollow ? JSON.stringify({}) : undefined,
    },
  )
}

export async function loadFallbackFollows(tab: 'following' | 'followers'): Promise<FallbackProfilesResponse> {
  return fallbackApiJson<FallbackProfilesResponse>(`/v1/data/follows?tab=${encodeURIComponent(tab)}`)
}

export async function submitFallbackUserReport(input: {
  reportedUserId: string
  reportedUserName?: string | null
  reason: string
  description?: string | null
  evidenceUrl?: string | null
}): Promise<{ report: GenericRow }> {
  return fallbackApiJson<{ report: GenericRow }>('/v1/data/reports/users', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export async function loadFallbackNotifications(limit = 200): Promise<FallbackNotificationsResponse> {
  return fallbackApiJson<FallbackNotificationsResponse>(
    `/v1/data/notifications?limit=${encodeURIComponent(String(limit))}`,
  )
}

export async function markFallbackNotificationRead(notificationId: string): Promise<{ ok: boolean }> {
  return fallbackApiJson<{ ok: boolean }>(
    `/v1/data/notifications/${encodeURIComponent(notificationId)}/read`,
    {
      method: 'PATCH',
      body: JSON.stringify({}),
    },
  )
}

export async function markAllFallbackNotificationsRead(): Promise<{ ok: boolean }> {
  return fallbackApiJson<{ ok: boolean }>('/v1/data/notifications/read-all', {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export async function loadFallbackFeed(limit = 60): Promise<FallbackFeedResponse> {
  return fallbackApiJson<FallbackFeedResponse>(`/v1/data/feed?limit=${encodeURIComponent(String(limit))}`)
}

export async function loadFallbackPostDetail(postId: string): Promise<FallbackPostDetailResponse> {
  return fallbackApiJson<FallbackPostDetailResponse>(
    `/v1/data/feed/posts/${encodeURIComponent(postId)}`,
  )
}

export async function addFallbackPostComment(
  postId: string,
  input: {
    content: string
    parentId?: string | null
    mediaUrl?: string | null
    mediaType?: string | null
  },
): Promise<{ comment: GenericRow }> {
  return fallbackApiJson<{ comment: GenericRow }>(
    `/v1/data/feed/posts/${encodeURIComponent(postId)}/comments`,
    {
      method: 'POST',
      body: JSON.stringify(input),
    },
  )
}

export async function setFallbackPostReaction(
  postId: string,
  liked: boolean,
  reactionType = 'LIKE',
): Promise<{ ok: boolean; post?: GenericRow | null }> {
  return fallbackApiJson<{ ok: boolean; post?: GenericRow | null }>(
    `/v1/data/feed/posts/${encodeURIComponent(postId)}/reaction`,
    {
      method: liked ? 'POST' : 'DELETE',
      body: liked ? JSON.stringify({ reactionType }) : undefined,
    },
  )
}

export async function shareFallbackPost(postId: string): Promise<{ ok: boolean; post?: GenericRow | null }> {
  return fallbackApiJson<{ ok: boolean; post?: GenericRow | null }>(
    `/v1/data/feed/posts/${encodeURIComponent(postId)}/share`,
    {
      method: 'POST',
      body: JSON.stringify({}),
    },
  )
}

export async function loadFallbackMessagesInbox(tab: string): Promise<FallbackInboxResponse> {
  return fallbackApiJson<FallbackInboxResponse>(`/v1/data/messages/inbox?tab=${encodeURIComponent(tab)}`)
}

async function loadDurableConversation(scope: 'chat' | 'group' | 'community', room: string, path: string): Promise<FallbackChatResponse> {
  const owner = outboxOwner()
  let response: FallbackChatResponse
  try {
    response = await fallbackApiJson<FallbackChatResponse>(path, { expectedAccountId: owner })
    await cacheConversation(scope, room, response as unknown as Record<string, unknown>, owner)
  } catch (error) {
    if (!isTransientOutboxError(error)) throw error
    const cached = await cachedConversation(scope, room, owner)
    if (!cached) throw error
    response = cached as unknown as FallbackChatResponse
  }
  const pending = await pendingConversationMessages(scope, room, owner)
  const byId = new Map((response.messages || []).map(row => [String(row.id), row]))
  for (const row of pending) if (!byId.has(String(row.id))) byId.set(String(row.id), row)
  return { ...response, messages: [...byId.values()] }
}

export async function loadFallbackChat(chatId: string): Promise<FallbackChatResponse> {
  return loadDurableConversation('chat', chatId,
    `/v1/data/messages/chats/${encodeURIComponent(chatId)}`,
  )
}

export async function loadFallbackLiveStreams(limit = 60): Promise<{ items: FallbackLiveStream[] }> {
  return fallbackApiJson<{ items: FallbackLiveStream[] }>(
    `/v1/data/live?limit=${encodeURIComponent(String(limit))}`,
  )
}

export async function loadFallbackLiveStream(liveId: string): Promise<{ stream: FallbackLiveStream }> {
  return fallbackApiJson<{ stream: FallbackLiveStream }>(
    `/v1/data/live/${encodeURIComponent(liveId)}`,
  )
}

export async function createFallbackLiveStream(input: {
  id?: string
  title: string
  source: 'camera' | 'screen'
  hashtags?: string[]
}): Promise<{ stream: FallbackLiveStream }> {
  return fallbackApiJson<{ stream: FallbackLiveStream }>('/v1/data/live', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export async function updateFallbackLiveState(
  liveId: string,
  input: {
    isActive?: boolean
    viewerCount?: number
    peakViewerCount?: number
    totalComments?: number
    totalReactions?: number
  },
): Promise<{ stream: FallbackLiveStream }> {
  return fallbackApiJson<{ stream: FallbackLiveStream }>(
    `/v1/data/live/${encodeURIComponent(liveId)}/state`,
    {
      method: 'PATCH',
      body: JSON.stringify(input),
    },
  )
}

export async function loadFallbackLiveComments(
  liveId: string,
  limit = 100,
): Promise<{ items: FallbackLiveComment[] }> {
  return fallbackApiJson<{ items: FallbackLiveComment[] }>(
    `/v1/data/live/${encodeURIComponent(liveId)}/comments?limit=${encodeURIComponent(String(limit))}`,
  )
}

export async function sendFallbackLiveComment(
  liveId: string,
  content: string,
): Promise<{ comment: FallbackLiveComment }> {
  return fallbackApiJson<{ comment: FallbackLiveComment }>(
    `/v1/data/live/${encodeURIComponent(liveId)}/comments`,
    {
      method: 'POST',
      body: JSON.stringify({ content }),
    },
  )
}

export async function enableFallbackProximity(input: {
  latitude: number
  longitude: number
  radiusKm: number
  gender: string
  lookingFor: string
  randomEnabled?: boolean
}): Promise<{ enabled: boolean; radius_km: number; expires_in_minutes: number }> {
  return fallbackApiJson('/v1/data/proximity/presence', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export async function disableFallbackProximity(): Promise<{ enabled: boolean }> {
  return fallbackApiJson('/v1/data/proximity/presence', { method: 'DELETE' })
}

export async function loadFallbackNearbyProfiles(
  radiusKm: number,
): Promise<{ items: FallbackNearbyProfile[]; radius_km: number }> {
  return fallbackApiJson(
    `/v1/data/proximity/nearby?radiusKm=${encodeURIComponent(String(radiusKm))}`,
  )
}

export async function matchFallbackRandomChat(input: {
  gender: string
  lookingFor: string
}): Promise<{ status: 'waiting' | 'matched'; session?: FallbackRandomSession }> {
  return fallbackApiJson('/v1/data/proximity/random/match', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export async function loadFallbackRandomChatStatus(): Promise<{
  status: 'idle' | 'waiting' | 'matched'
  session?: FallbackRandomSession
}> {
  return fallbackApiJson('/v1/data/proximity/random/status')
}

export async function cancelFallbackRandomChatMatch(): Promise<{ status: 'idle' }> {
  return fallbackApiJson('/v1/data/proximity/random/queue', { method: 'DELETE' })
}

export async function loadFallbackRandomSession(
  sessionId: string,
): Promise<{ session: FallbackRandomSession }> {
  return fallbackApiJson(
    `/v1/data/proximity/random/sessions/${encodeURIComponent(sessionId)}`,
  )
}

export async function loadFallbackRandomMessages(
  sessionId: string,
): Promise<{ items: FallbackRandomMessage[] }> {
  return fallbackApiJson(
    `/v1/data/proximity/random/sessions/${encodeURIComponent(sessionId)}/messages`,
  )
}

export async function sendFallbackRandomMessage(
  sessionId: string,
  input: { content: string; clientMessageId: string },
): Promise<{ message: FallbackRandomMessage }> {
  return fallbackApiJson(
    `/v1/data/proximity/random/sessions/${encodeURIComponent(sessionId)}/messages`,
    {
      method: 'POST',
      body: JSON.stringify(input),
    },
  )
}

export async function revealFallbackRandomProfile(
  sessionId: string,
  level: number,
): Promise<{ session: FallbackRandomSession }> {
  return fallbackApiJson(
    `/v1/data/proximity/random/sessions/${encodeURIComponent(sessionId)}/reveal`,
    {
      method: 'POST',
      body: JSON.stringify({ level }),
    },
  )
}

export async function endFallbackRandomSession(sessionId: string): Promise<{ status: 'ended' }> {
  return fallbackApiJson(
    `/v1/data/proximity/random/sessions/${encodeURIComponent(sessionId)}`,
    { method: 'DELETE' },
  )
}

export async function markFallbackChatRead(chatId: string): Promise<{ ok: boolean }> {
  return fallbackApiJson<{ ok: boolean }>(
    `/v1/data/messages/chats/${encodeURIComponent(chatId)}/read`,
    { method: 'POST', body: JSON.stringify({}) },
  )
}

export async function createFallbackDirectChat(participantId: string): Promise<{ chat: GenericRow }> {
  return fallbackApiJson<{ chat: GenericRow }>('/v1/data/messages/chats', {
    method: 'POST',
    body: JSON.stringify({ participantId }),
  })
}

export async function loadFallbackRoomChat(
  scope: 'group' | 'community',
  roomId: string,
): Promise<FallbackChatResponse> {
  const collection = scope === 'group' ? 'groups' : 'communities'
  return loadDurableConversation(scope, roomId,
    `/v1/data/messages/${collection}/${encodeURIComponent(roomId)}`,
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
    isForwarded?: boolean
    forwardedFromMessageId?: string | null
    forwardedFromUserId?: string | null
  },
): Promise<{ message: GenericRow }> {
  return sendDurableWebMessage('chat', chatId, input)
}

export async function editFallbackChatMessage(
  chatId: string,
  messageId: string,
  content: string,
): Promise<{ message: GenericRow }> {
  return fallbackApiJson<{ message: GenericRow }>(
    `/v1/data/messages/chats/${encodeURIComponent(chatId)}/messages/${encodeURIComponent(messageId)}`,
    { method: 'PATCH', body: JSON.stringify({ content }) },
  )
}

export async function deleteFallbackChatMessage(
  chatId: string,
  messageId: string,
  forEveryone: boolean,
): Promise<{ message: GenericRow }> {
  const cancelled = await cancelPendingWebMessage('chat', chatId, messageId)
  if (cancelled) return { message: cancelled }
  return fallbackApiJson<{ message: GenericRow }>(
    `/v1/data/messages/chats/${encodeURIComponent(chatId)}/messages/${encodeURIComponent(messageId)}/delete`,
    { method: 'POST', body: JSON.stringify({ forEveryone }) },
  )
}

export async function toggleFallbackChatReaction(
  chatId: string,
  messageId: string,
  emoji: string,
): Promise<{ message: GenericRow }> {
  return fallbackApiJson<{ message: GenericRow }>(
    `/v1/data/messages/chats/${encodeURIComponent(chatId)}/messages/${encodeURIComponent(messageId)}/reactions`,
    { method: 'POST', body: JSON.stringify({ emoji }) },
  )
}

export async function sendFallbackRoomMessage(
  scope: 'group' | 'community',
  roomId: string,
  input: {
    content: string
    contentType?: string
    mediaUrl?: string | null
    mediaSize?: number
    replyToId?: string | null
    isForwarded?: boolean
    forwardedFromMessageId?: string | null
    forwardedFromUserId?: string | null
  },
): Promise<{ message: GenericRow }> {
  const collection = scope === 'group' ? 'groups' : 'communities'
  return sendDurableWebMessage(scope, roomId, input)
}

export async function editFallbackRoomMessage(
  scope: 'group' | 'community',
  roomId: string,
  messageId: string,
  content: string,
): Promise<{ message: GenericRow }> {
  const collection = scope === 'group' ? 'groups' : 'communities'
  return fallbackApiJson<{ message: GenericRow }>(
    `/v1/data/messages/${collection}/${encodeURIComponent(roomId)}/messages/${encodeURIComponent(messageId)}`,
    { method: 'PATCH', body: JSON.stringify({ content }) },
  )
}

export async function deleteFallbackRoomMessage(
  scope: 'group' | 'community',
  roomId: string,
  messageId: string,
  forEveryone: boolean,
): Promise<{ message: GenericRow }> {
  const cancelled = await cancelPendingWebMessage(scope, roomId, messageId)
  if (cancelled) return { message: cancelled }
  const collection = scope === 'group' ? 'groups' : 'communities'
  return fallbackApiJson<{ message: GenericRow }>(
    `/v1/data/messages/${collection}/${encodeURIComponent(roomId)}/messages/${encodeURIComponent(messageId)}/delete`,
    { method: 'POST', body: JSON.stringify({ forEveryone }) },
  )
}

export async function uploadFallbackFile(
  bucket: string,
  file: File,
  path?: string,
): Promise<FallbackStorageObject> {
  if (['chat-media', 'group-media', 'community-media'].includes(bucket)) {
    return stageOfflineChatAttachment(bucket, file, path)
  }
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

export async function loadFallbackVoiceRooms(): Promise<FallbackVoiceRoomsResponse> {
  return fallbackApiJson<FallbackVoiceRoomsResponse>('/v1/data/voice/rooms')
}

export async function loadFallbackVoiceRoom(roomId: string): Promise<FallbackVoiceRoomResponse> {
  return fallbackApiJson<FallbackVoiceRoomResponse>(
    `/v1/data/voice/rooms/${encodeURIComponent(roomId)}`,
  )
}

export async function joinFallbackVoiceRoom(
  roomId: string,
  input: {
    userName?: string
    userAvatarUrl?: string
    socketId?: string
    isMuted?: boolean
  },
): Promise<{ participants: GenericRow[] }> {
  return fallbackApiJson<{ participants: GenericRow[] }>(
    `/v1/data/voice/rooms/${encodeURIComponent(roomId)}/join`,
    {
      method: 'POST',
      body: JSON.stringify(input),
    },
  )
}

export async function leaveFallbackVoiceRoom(roomId: string): Promise<{ ok: boolean }> {
  return fallbackApiJson<{ ok: boolean }>(
    `/v1/data/voice/rooms/${encodeURIComponent(roomId)}/leave`,
    {
      method: 'POST',
      body: JSON.stringify({}),
    },
  )
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

export type FallbackVideosResponse = {
  items: GenericRow[]
}

export async function loadFallbackVideos(limit = 80, offset = 0): Promise<FallbackVideosResponse> {
  const params = new URLSearchParams()
  params.set('limit', String(limit))
  params.set('offset', String(offset))
  return fallbackApiJson<FallbackVideosResponse>(`/v1/data/videos?${params.toString()}`)
}

export async function loadFallbackVideo(videoId: string): Promise<{ video: GenericRow }> {
  return fallbackApiJson<{ video: GenericRow }>(`/v1/data/videos/${encodeURIComponent(videoId)}`)
}

export async function createFallbackVideo(input: {
  title: string
  description?: string
  videoUrl: string
  coverUrl?: string | null
  durationSeconds?: number
  trimStartSeconds?: number
  trimEndSeconds?: number
  isPublic?: boolean
  tags?: string[]
}): Promise<{ video: GenericRow }> {
  return fallbackApiJson<{ video: GenericRow }>('/v1/data/videos', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export async function setFallbackVideoReaction(videoId: string, liked: boolean): Promise<{ ok: boolean }> {
  return fallbackApiJson<{ ok: boolean }>(
    `/v1/data/videos/${encodeURIComponent(videoId)}/reaction`,
    {
      method: liked ? 'POST' : 'DELETE',
      body: liked ? JSON.stringify({}) : undefined,
    },
  )
}

export async function setFallbackVideoFavorite(videoId: string, favorite: boolean): Promise<{
  ok: boolean
  favorited_by_me?: boolean
  favorite_count?: number
}> {
  return fallbackApiJson<{
    ok: boolean
    favorited_by_me?: boolean
    favorite_count?: number
  }>(
    `/v1/data/videos/${encodeURIComponent(videoId)}/favorite`,
    {
      method: favorite ? 'POST' : 'DELETE',
      body: favorite ? JSON.stringify({}) : undefined,
    },
  )
}

export type FallbackFriendsResponse = {
  items: GenericRow[]
}

export async function loadFallbackFriends(tab: 'incoming' | 'outgoing' | 'accepted' = 'incoming'): Promise<FallbackFriendsResponse> {
  return fallbackApiJson<FallbackFriendsResponse>(`/v1/data/friends?tab=${encodeURIComponent(tab)}`)
}

export async function sendFallbackFriendRequest(receiverId: string): Promise<{ request: GenericRow }> {
  return fallbackApiJson<{ request: GenericRow }>('/v1/data/friends', {
    method: 'POST',
    body: JSON.stringify({ receiverId }),
  })
}

export async function updateFallbackFriendRequest(
  requestId: string,
  status: 'accepted' | 'rejected',
): Promise<{ request: GenericRow }> {
  return fallbackApiJson<{ request: GenericRow }>(
    `/v1/data/friends/${encodeURIComponent(requestId)}`,
    {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    },
  )
}

/** Lectura genérica PostgREST contra el servidor Ubuntu (datos importados o compat). */
export async function loadUbuntuRestTable(table: string, query = ''): Promise<GenericRow[]> {
  const suffix = query ? (query.startsWith('?') ? query : `?${query}`) : ''
  return fallbackApiJson<GenericRow[]>(`/rest/v1/${encodeURIComponent(table)}${suffix}`)
}
