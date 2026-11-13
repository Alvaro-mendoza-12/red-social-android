const DEFAULT_PUBLIC_ORIGIN = 'https://novvinapp.com'

function cleanOrigin(value: string | undefined): string {
  try {
    const parsed = new URL(value?.trim() || DEFAULT_PUBLIC_ORIGIN)
    if (parsed.protocol === 'https:' && !parsed.username && !parsed.password && !parsed.port &&
        ['novvinapp.com', 'www.novvinapp.com'].includes(parsed.hostname) && parsed.pathname === '/' && !parsed.search && !parsed.hash) return DEFAULT_PUBLIC_ORIGIN
  } catch { /* Shared links retain the official canonical domain. */ }
  return DEFAULT_PUBLIC_ORIGIN
}

export function getPublicWebOrigin(): string {
  return cleanOrigin(import.meta.env.VITE_PUBLIC_WEB_URL)
}

export function buildProfileShareUrl(profileId: string): string {
  return `${getPublicWebOrigin()}/p/${encodeURIComponent(profileId.trim())}?preview=5`
}

export function buildLiveShareUrl(liveId: string): string {
  const id = liveId.trim()
  if (!/^[a-zA-Z0-9_-]{1,100}$/.test(id)) throw new Error('Directo no válido')
  return `${getPublicWebOrigin()}/app/live/${encodeURIComponent(id)}`
}

export function buildProfileChatUrl(profileId: string): string {
  return `${getPublicWebOrigin()}/chat/${encodeURIComponent(profileId.trim())}`
}

export function buildVideoShareUrl(videoId: string): string {
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(videoId)) throw new Error('Video no válido')
  return `${getPublicWebOrigin()}/app/reels/${encodeURIComponent(videoId)}?preview=1`
}

export function buildProfileQrPayload(profileId: string): string {
  // Already installed Android clients recognize this exact profile destination.
  return `${getPublicWebOrigin()}/u/${encodeURIComponent(profileId.trim())}`
}

export function buildProfileChatQrPayload(profileId: string): string {
  return buildProfileChatUrl(profileId)
}
