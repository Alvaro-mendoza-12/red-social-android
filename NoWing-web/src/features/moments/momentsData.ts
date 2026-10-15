import { isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'
import { deleteUbuntuMoment, fetchUbuntuMomentsFeed, publishUbuntuMoment, recordUbuntuMomentView } from '../../core/momentsUbuntu'
import { getDataClient } from '../../core/ubuntuDataClient'
import { isUbuntuPrimary } from '../../core/ubuntuBackend'
import { uploadFallbackFile } from '../../core/fallbackData'
import { isMissingTableError, textOf, type GenericRow } from '../../core/utils'

export type MomentItem = {
  id: string
  userId: string
  ownerName: string
  mediaUrl: string
  mediaType: string
  caption: string
  visibility: string
  allowReplies: boolean
  createdAt: string
  expiresAt: string
  viewsCount: number
  viewedByMe: boolean
  viewerPreview: string[]
}

function toBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') return value.toLowerCase() === 'true'
  if (typeof value === 'number') return value === 1
  return false
}

function toMillis(value: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

async function fetchLegacyMomentsFeed(currentUserId: string): Promise<MomentItem[]> {
  const client = getDataClient()
  if (!client) return []
  const now = Date.now()

  const { data: momentRows, error: momentsError } = await client
    .from('moments')
    .select('*')
    .order('created_at', { ascending: false })
    .limit(240)
  if (momentsError) throw momentsError

  const rows = (momentRows ?? []) as GenericRow[]
  if (rows.length === 0) return []

  const userIds = Array.from(new Set(rows.map((row) => textOf(row, ['user_id'], '')).filter(Boolean)))
  const profilesById = new Map<string, string>()
  if (userIds.length > 0) {
    const { data: profileRows } = await client.from('profiles').select('*').in('id', userIds)
    ;((profileRows ?? []) as GenericRow[]).forEach((profile) => {
      const id = textOf(profile, ['id'], '')
      if (!id) return
      profilesById.set(
        id,
        textOf(profile, ['display_name', 'displayName', 'full_name', 'username'], 'Usuario'),
      )
    })
  }

  const momentIds = rows.map((row) => textOf(row, ['id'], '')).filter(Boolean)
  const viewsByMoment = new Map<string, number>()
  const viewedByMe = new Set<string>()
  const viewersByMoment = new Map<string, Array<{ userId: string; viewedAt: string }>>()
  if (momentIds.length > 0) {
    const { data: viewRows, error: viewsError } = await client
      .from('moment_views')
      .select('moment_id,user_id,viewed_at')
      .in('moment_id', momentIds)
    if (viewsError && !isMissingTableError(viewsError)) throw viewsError
    ;((viewRows ?? []) as GenericRow[]).forEach((view) => {
      const momentId = textOf(view, ['moment_id'], '')
      if (!momentId) return
      viewsByMoment.set(momentId, (viewsByMoment.get(momentId) ?? 0) + 1)
      const viewerId = textOf(view, ['user_id'], '')
      if (viewerId === currentUserId) viewedByMe.add(momentId)
      if (viewerId) {
        const current = viewersByMoment.get(momentId) ?? []
        current.push({ userId: viewerId, viewedAt: textOf(view, ['viewed_at'], '') })
        viewersByMoment.set(momentId, current)
      }
    })
  }

  const viewerIds = Array.from(
    new Set(
      Array.from(viewersByMoment.values()).flatMap((entries) =>
        entries.map((entry) => entry.userId).filter(Boolean),
      ),
    ),
  )
  const viewerNamesById = new Map<string, string>()
  if (viewerIds.length > 0) {
    const { data: viewerRows } = await client.from('profiles').select('*').in('id', viewerIds)
    ;((viewerRows ?? []) as GenericRow[]).forEach((viewer) => {
      const id = textOf(viewer, ['id'], '')
      if (!id) return
      viewerNamesById.set(
        id,
        textOf(viewer, ['display_name', 'displayName', 'full_name', 'username'], 'Usuario'),
      )
    })
  }

  return rows
    .map((row) => {
      const id = textOf(row, ['id'], crypto.randomUUID())
      const ownerId = textOf(row, ['user_id'], '')
      const viewerPreview = Array.from(
        new Set(
          (viewersByMoment.get(id) ?? [])
            .filter((entry) => entry.userId !== ownerId)
            .sort((a, b) => toMillis(b.viewedAt) - toMillis(a.viewedAt))
            .map((entry) => viewerNamesById.get(entry.userId) ?? entry.userId),
        ),
      ).slice(0, 5)
      return {
        id,
        userId: ownerId,
        ownerName: profilesById.get(ownerId) ?? 'Usuario',
        mediaUrl: textOf(row, ['media_url'], ''),
        mediaType: textOf(row, ['media_type'], 'image'),
        caption: textOf(row, ['caption'], ''),
        visibility: textOf(row, ['visibility'], 'all'),
        allowReplies: toBoolean(row.allow_replies),
        createdAt: textOf(row, ['created_at'], ''),
        expiresAt: textOf(row, ['expires_at'], ''),
        viewsCount: viewsByMoment.get(id) ?? 0,
        viewedByMe: viewedByMe.has(id),
        viewerPreview,
      } satisfies MomentItem
    })
    .filter((moment) => {
      const expiresAtMillis = toMillis(moment.expiresAt)
      return expiresAtMillis === 0 || expiresAtMillis > now
    })
}

export async function fetchMomentsFeed(currentUserId: string): Promise<MomentItem[]> {
  if (!currentUserId) return []
  if (isUbuntuPrimary || isFallbackAuthConfigured) {
    return fetchUbuntuMomentsFeed(currentUserId)
  }
  return fetchLegacyMomentsFeed(currentUserId)
}

export async function recordMomentView(momentId: string, currentUserId: string): Promise<void> {
  if (!momentId || !currentUserId) return
  if (isUbuntuPrimary || isFallbackAuthConfigured) {
    await recordUbuntuMomentView(momentId)
    return
  }
  const client = getDataClient()
  if (!client) return
  const { error: viewError } = await client.from('moment_views').upsert(
    {
      moment_id: momentId,
      user_id: currentUserId,
      viewed_at: new Date().toISOString(),
    },
    { onConflict: 'moment_id,user_id' },
  )
  if (viewError) throw viewError
}

export async function removeMoment(momentId: string, currentUserId: string): Promise<void> {
  if (isUbuntuPrimary || isFallbackAuthConfigured) {
    await deleteUbuntuMoment(momentId)
    return
  }
  const client = getDataClient()
  if (!client) return
  const { error } = await client.from('moments').delete().eq('id', momentId).eq('user_id', currentUserId)
  if (error) throw error
}

export async function publishMoment(input: {
  file: File
  caption: string
  durationHours: number
  visibility: string
  allowReplies: boolean
}): Promise<void> {
  const mediaType = input.file.type.startsWith('video/') ? 'video' : 'image'
  const path = `moments/${Date.now()}_${mediaType}_${input.file.name.replace(/[^a-zA-Z0-9._-]/g, '_')}`

  if (isUbuntuPrimary || isFallbackAuthConfigured) {
    const uploaded = await uploadFallbackFile('moment-media', input.file, path)
    await publishUbuntuMoment({
      mediaUrl: uploaded.publicUrl || uploaded.url,
      mediaType,
      caption: input.caption,
      durationHours: input.durationHours,
      visibility: input.visibility,
      allowReplies: input.allowReplies,
    })
    return
  }

  const client = getDataClient()
  if (!client) throw new Error('Cliente de datos no configurado')
  const { error: uploadError } = await client.storage.from('moment-media').upload(path, input.file)
  if (uploadError) throw uploadError
  const { data } = client.storage.from('moment-media').getPublicUrl(path)
  const mediaUrl = String((data as { publicUrl?: string })?.publicUrl ?? '')
  const { error: insertError } = await client.from('moments').insert({
    media_url: mediaUrl,
    media_type: mediaType,
    caption: input.caption,
    duration_hours: input.durationHours,
    visibility: input.visibility,
    allow_replies: input.allowReplies,
  })
  if (insertError) throw insertError
}
