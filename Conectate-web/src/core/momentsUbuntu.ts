import { fallbackApiJson } from './auth/fallbackAuth'
import type { MomentItem } from '../features/moments/momentsData'

type FeedResponse = {
  items: Array<{
    id: string
    user_id: string
    owner_name: string
    media_url: string
    media_type: string
    caption: string
    visibility: string
    allow_replies: boolean
    created_at: string
    expires_at: string
    views_count: number
    viewed_by_me: boolean
    viewer_preview: string[]
  }>
}

export async function fetchUbuntuMomentsFeed(_currentUserId: string): Promise<MomentItem[]> {
  // The API identifies the viewer from the bearer token.
  void _currentUserId
  const payload = await fallbackApiJson<FeedResponse>('/v1/data/moments/feed')
  return (payload.items ?? []).map((row) => ({
    id: row.id,
    userId: row.user_id,
    ownerName: row.owner_name || 'Usuario',
    mediaUrl: row.media_url,
    mediaType: row.media_type || 'image',
    caption: row.caption || '',
    visibility: row.visibility || 'all',
    allowReplies: row.allow_replies !== false,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    viewsCount: Number(row.views_count ?? 0),
    viewedByMe: Boolean(row.viewed_by_me),
    viewerPreview: row.viewer_preview ?? [],
  }))
}

export async function publishUbuntuMoment(input: {
  mediaUrl: string
  mediaType: string
  caption?: string
  durationHours: number
  visibility: string
  allowReplies: boolean
}) {
  return fallbackApiJson('/v1/data/moments', {
    method: 'POST',
    body: JSON.stringify({
      mediaUrl: input.mediaUrl,
      mediaType: input.mediaType,
      caption: input.caption ?? '',
      durationHours: input.durationHours,
      visibility: input.visibility,
      allowReplies: input.allowReplies,
    }),
  })
}

export async function recordUbuntuMomentView(momentId: string) {
  await fallbackApiJson(`/v1/data/moments/${encodeURIComponent(momentId)}/view`, {
    method: 'POST',
    body: JSON.stringify({}),
  })
}

export async function deleteUbuntuMoment(momentId: string) {
  await fallbackApiJson(`/v1/data/moments/${encodeURIComponent(momentId)}`, { method: 'DELETE' })
}
