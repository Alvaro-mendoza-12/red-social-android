import { resolveSupabasePublicUrl } from './supabase'
import { textOf, type GenericRow } from './utils'

/** Retain media saved by Android and older [Audio] URL comments. Never render opaque IDs as names. */
export function commentAudioUrl(row: GenericRow): string {
  const media = textOf(row, ['media_url', 'audio_url', 'audioUrl'], '').trim()
  const type = textOf(row, ['media_type', 'content_type'], '').toLowerCase()
  const content = textOf(row, ['content'], '')
  const legacy = /^\[audio\]\s*(https?:\/\/\S+)\s*$/i.exec(content)?.[1] || ''
  if (!legacy && !type.includes('audio') && !/^\[audio\]/i.test(content)) return ''
  const source = media || legacy
  if (!source || /^[a-z][a-z\d+.-]*:/i.test(source) && !/^https?:\/\//i.test(source)) return ''
  return resolveSupabasePublicUrl('post-comments-media', source)
}
