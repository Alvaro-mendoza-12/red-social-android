export const voiceShareOrigin = 'https://novvinapp.com'

export function voiceRoomId(value: string | undefined | null): string | null {
  const clean = value?.trim() || ''
  return /^[A-Za-z0-9_-]{1,100}$/.test(clean) ? clean : null
}

export function voiceRoomWebPath(id: string): string {
  const room = voiceRoomId(id)
  if (!room) throw Error('Sala inválida')
  return `/app/voice/${encodeURIComponent(room)}?join=1`
}

export function voiceRoomShareUrl(id: string): string {
  const room = voiceRoomId(id)
  if (!room) throw Error('Sala inválida')
  return `${voiceShareOrigin}/voice/${encodeURIComponent(room)}`
}

/** Chrome's package-bound intent opens Android when installed and otherwise stays on the web. */
export function voiceRoomAndroidIntent(id: string): string {
  const room = voiceRoomId(id)
  if (!room) throw Error('Sala inválida')
  const fallback = voiceShareOrigin + voiceRoomWebPath(room)
  return `intent://voice/${encodeURIComponent(room)}#Intent;scheme=conectate;package=conectate.com;S.browser_fallback_url=${encodeURIComponent(fallback)};end`
}
