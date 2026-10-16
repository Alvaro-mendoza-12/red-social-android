import { fallbackApiJson } from './auth/fallbackAuth'
import type { GenericRow } from './utils'

export type VideoFeedPage = { items: GenericRow[]; nextCursor: string | null; hasMore: boolean; legacy: boolean; offset: number }
const legacyCodes = new Set(['USER_ALGORITHM_DISABLED', 'ALGORITHM_PROFILE_NOT_FOUND', 'ALGORITHM_PROFILE_DISABLED', 'RECOMMENDATION_CURSOR_UNAVAILABLE'])

/** Keep server ordering and the same opaque cursor for the entire browsing session. */
export async function loadRecommendedVideoPage(input: { owner: string; refreshId: string; cursor?: string | null; offset?: number; legacy?: boolean; signal?: AbortSignal }): Promise<VideoFeedPage> {
  const limit = 20, offset = input.offset || 0
  if (!input.legacy) {
    try {
      const params = new URLSearchParams({ surface: 'video', limit: String(limit), refreshId: input.refreshId })
      if (input.cursor) params.set('cursor', input.cursor)
      const response = await fallbackApiJson<{ items: GenericRow[]; page: { nextCursor?: string | null; hasMore?: boolean }; algorithm: { authoritative?: boolean } }>('/v1/data/recommendations?' + params, { expectedAccountId: input.owner, signal: input.signal })
      if (!Array.isArray(response.items) || response.algorithm?.authoritative !== true || !response.page || (response.page.hasMore && !response.page.nextCursor)) throw new Error('INVALID_RECOMMENDATION_PAGE')
      return { items: response.items, nextCursor: response.page.nextCursor || null, hasMore: response.page.hasMore === true, legacy: false, offset: offset + response.items.length }
    } catch (error) {
      if (input.cursor || !legacyCodes.has((error as Error)?.message)) throw error
    }
  }
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset), recommended: 'true', feedSessionId: input.refreshId })
  const response = await fallbackApiJson<{ items: GenericRow[] }>('/v1/data/videos?' + params, { expectedAccountId: input.owner, signal: input.signal })
  return { items: response.items, nextCursor: null, hasMore: response.items.length === limit, legacy: true, offset: offset + response.items.length }
}
