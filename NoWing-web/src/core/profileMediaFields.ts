export type MediaSourceRow = Record<string, unknown>

const nestedProfileKeys = ['profile', 'user', 'author', 'otherParticipant', 'other_participant', 'user_metadata', 'group', 'community', 'room'] as const
const nestedUrlKeys = ['url', 'publicUrl', 'public_url', 'path', 'src'] as const

const profileAvatarKeys = [
  'avatarUrl',
  'avatar_url',
  'photoUrl',
  'photo_url',
  'profileImage',
  'profile_image',
  'picture',
] as const

const conversationAvatarKeys = [
  'avatarUrl',
  'avatar_url',
  'otherUserAvatarUrl',
  'other_user_avatar_url',
  'otherUserAvatar',
  'other_user_avatar',
  'otherUserImage',
  'other_user_image',
  'otherUserPhoto',
  'other_user_photo',
  'participantAvatarUrl',
  'participant_avatar_url',
  'photoUrl',
  'photo_url',
  'profileImage',
  'profile_image',
  'iconUrl',
  'icon_url',
  'groupImage',
  'group_image',
  'communityImage',
  'community_image',
  'image',
  'icon',
  'imageUrl',
  'image_url',
  'coverUrl',
  'cover_url',
] as const

const reelAuthorAvatarKeys = [
  'userProfileImage',
  'user_profile_image',
  'authorAvatarUrl',
  'author_avatar_url',
  'userAvatarUrl',
  'user_avatar_url',
  ...profileAvatarKeys,
] as const

const profileCoverKeys = [
  'coverUrl',
  'cover_url',
  'bannerUrl',
  'banner_url',
  'headerImageUrl',
  'header_image_url',
] as const

const videoSourceKeys = [
  'videoUrl',
  'video_url',
  'mediaUrl',
  'media_url',
  'fileUrl',
  'file_url',
  'url',
] as const

const videoCoverKeys = [
  'coverUrl',
  'cover_url',
  'thumbnailUrl',
  'thumbnail_url',
  'thumbUrl',
  'thumb_url',
  'posterUrl',
  'poster_url',
] as const

const avatarMediaTypeKeys = [
  'avatarMediaType',
  'avatar_media_type',
  'userAvatarMediaType',
  'user_avatar_media_type',
  'userProfileMediaType',
  'user_profile_media_type',
] as const

const profileIdentityKeys = [
  'id',
  'userId',
  'user_id',
  'profileId',
  'profile_id',
  'supabaseUserId',
  'supabase_user_id',
  'email',
  'username',
] as const

const contentAuthorKeys = [
  'userId',
  'user_id',
  'ownerId',
  'owner_id',
  'authorId',
  'author_id',
  'profileId',
  'profile_id',
] as const

function sourceFromValue(value: unknown): string {
  if (typeof value === 'string') return value.trim()
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ''

  const record = value as MediaSourceRow
  for (const key of nestedUrlKeys) {
    const nested = record[key]
    if (typeof nested === 'string' && nested.trim()) return nested.trim()
  }
  return ''
}

export function firstMediaSource(
  row: MediaSourceRow,
  keys: readonly string[],
  fallback = '',
): string {
  for (const key of keys) {
    const source = sourceFromValue(row[key])
    if (source) return source
  }
  return sourceFromValue(fallback)
}

function sourceFromProfileShape(
  row: MediaSourceRow,
  keys: readonly string[],
  fallback = '',
): string {
  const direct = firstMediaSource(row, keys)
  if (direct) return direct

  for (const nestedKey of nestedProfileKeys) {
    const nested = row[nestedKey]
    if (!nested || typeof nested !== 'object' || Array.isArray(nested)) continue
    const source = firstMediaSource(nested as MediaSourceRow, keys)
    if (source) return source
  }
  return sourceFromValue(fallback)
}

export function profileAvatarSource(row: MediaSourceRow, fallback = ''): string {
  return sourceFromProfileShape(row, profileAvatarKeys, fallback)
}

export function conversationAvatarSource(row: MediaSourceRow, fallback = ''): string {
  return sourceFromProfileShape(row, conversationAvatarKeys, fallback)
}

export function reelAuthorAvatarSource(row: MediaSourceRow, fallback = ''): string {
  return sourceFromProfileShape(row, reelAuthorAvatarKeys, fallback)
}

export function profileCoverSource(row: MediaSourceRow, fallback = ''): string {
  return sourceFromProfileShape(row, profileCoverKeys, fallback)
}

export function profileVideoSource(row: MediaSourceRow, fallback = ''): string {
  return firstMediaSource(row, videoSourceKeys, fallback)
}

export function profileVideoCoverSource(row: MediaSourceRow, fallback = ''): string {
  return firstMediaSource(row, videoCoverKeys, fallback)
}

export function isVideoAvatarSource(url: string, mediaType = ''): boolean {
  const normalizedType = mediaType.trim().toLowerCase()
  if (normalizedType.startsWith('video/') || normalizedType === 'video') return true
  return /\.(?:mp4|webm|ogv|ogg|mov|m4v)(?:[?#].*)?$/i.test(url.trim())
}

export function avatarMediaTypeSource(
  row: MediaSourceRow,
  avatarUrl = '',
  fallback = '',
): string {
  const declared = sourceFromProfileShape(row, avatarMediaTypeKeys, fallback)
  if (declared) return declared
  if (!isVideoAvatarSource(avatarUrl)) return ''
  const extension = avatarUrl.match(/\.([a-z0-9]+)(?:[?#].*)?$/i)?.[1]?.toLowerCase()
  if (extension === 'webm') return 'video/webm'
  if (extension === 'ogg' || extension === 'ogv') return 'video/ogg'
  if (extension === 'mov') return 'video/quicktime'
  return 'video/mp4'
}

function normalizedAliases(row: MediaSourceRow, keys: readonly string[]): string[] {
  return keys
    .map((key) => sourceFromValue(row[key]).toLowerCase())
    .filter(Boolean)
}

export function profileIdentityAliases(
  row: MediaSourceRow,
  additionalAliases: readonly string[] = [],
): string[] {
  return Array.from(new Set([
    ...normalizedAliases(row, profileIdentityKeys),
    ...additionalAliases.map((value) => value.trim().toLowerCase()).filter(Boolean),
  ]))
}

export type AvatarMediaCandidate = {
  url: string
  mediaType: string
}

export function findContentAvatarCandidate(
  rows: readonly MediaSourceRow[],
  profileAliases: readonly string[],
): AvatarMediaCandidate | null {
  const allowedAliases = new Set(profileAliases.map((value) => value.trim().toLowerCase()).filter(Boolean))
  if (allowedAliases.size === 0) return null

  for (const row of rows) {
    const authorAliases = normalizedAliases(row, contentAuthorKeys)
    if (!authorAliases.some((alias) => allowedAliases.has(alias))) continue
    const url = reelAuthorAvatarSource(row)
    if (!url) continue
    return { url, mediaType: avatarMediaTypeSource(row, url) }
  }
  return null
}
