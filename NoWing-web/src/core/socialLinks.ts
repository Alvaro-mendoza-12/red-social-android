const DEFAULT_PUBLIC_ORIGIN = 'https://novvinapp.com'

function cleanOrigin(value: string | undefined): string {
  const trimmed = value?.trim().replace(/\/+$/, '')
  if (trimmed) return trimmed
  return DEFAULT_PUBLIC_ORIGIN
}

export function getPublicWebOrigin(): string {
  return cleanOrigin(import.meta.env.VITE_PUBLIC_WEB_URL)
}

export function buildProfileShareUrl(profileId: string): string {
  return `${getPublicWebOrigin()}/u/${encodeURIComponent(profileId.trim())}`
}

export function buildProfileChatUrl(profileId: string): string {
  return `${getPublicWebOrigin()}/chat/${encodeURIComponent(profileId.trim())}`
}

export function buildProfileQrPayload(profileId: string): string {
  return buildProfileShareUrl(profileId)
}

export function buildProfileChatQrPayload(profileId: string): string {
  return buildProfileChatUrl(profileId)
}
