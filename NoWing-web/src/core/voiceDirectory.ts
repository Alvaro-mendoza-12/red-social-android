import type { GenericRow } from './utils'

export function isStandaloneVoiceRoom(row: GenericRow) {
  return !['community_id', 'communityId', 'community_channel_id', 'communityChannelId'].some(key => String(row[key] || '').trim())
}

export function voiceBackPath(path: string) {
  if (/^\/app\/voice\/?$/.test(path)) return '/app/messages'
  if (/^\/app\/voice\/[^/]+\/?$/.test(path)) return '/app/voice'
  return null
}
