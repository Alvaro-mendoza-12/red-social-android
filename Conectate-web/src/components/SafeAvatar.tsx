import { useEffect, useMemo, useState } from 'react'

type AvatarSize = 'sm' | 'md' | 'lg'

type SafeAvatarProps = {
  url?: string | null
  name: string
  size?: AvatarSize
  className?: string
  title?: string
}

const sizeClassByVariant: Record<AvatarSize, string> = {
  sm: 'avatar-sm',
  md: 'avatar-md',
  lg: 'avatar-lg',
}

function initialsOf(name: string): string {
  const clean = name.trim()
  if (!clean) return 'U'
  const first = clean[0]
  if (!first) return 'U'
  return first.toUpperCase()
}

function hashString(value: string): number {
  let hash = 0
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash << 5) - hash + value.charCodeAt(index)
    hash |= 0
  }
  return Math.abs(hash)
}

function buildDiceBearUrl(seed: string, size: number): string {
  // DiceBear avatars are deterministic per seed, free, and don't need auth.
  // We embed the size to request a properly sized PNG.
  const safeSeed = encodeURIComponent(seed.trim() || 'conectate')
  return `https://api.dicebear.com/7.x/initials/png?seed=${safeSeed}&size=${size}&backgroundType=gradientLinear`
}

function buildUiAvatarsUrl(seed: string, size: number): string {
  // UI Avatars renders initials with a colored background as a fallback.
  const safeSeed = encodeURIComponent(seed.trim() || 'U')
  return `https://ui-avatars.com/api/?name=${safeSeed}&size=${size}&background=5865F2&color=fff&bold=true&format=png`
}

/**
 * SafeAvatar: renders the provided image URL when available and falls back to a
 * generated avatar (DiceBear / UI Avatars / initials) when:
 *   - the URL is empty,
 *   - the URL returns a 404 or other network error.
 *
 * This is the central place to manage avatar resilience so the inbox, groups,
 * communities and the user profile all show a real picture instead of a blank
 * circle. The seed is derived from the user's id when available so the same
 * person always gets the same fallback image across the app.
 */
export function SafeAvatar({
  url,
  name,
  size = 'md',
  className,
  title,
}: SafeAvatarProps) {
  const trimmedUrl = (url ?? '').trim()
  const initialName = name && name.trim().length > 0 ? name.trim() : 'Usuario'
  const seed = useMemo(() => {
    if (initialName && initialName !== 'Usuario' && !/^U$/.test(initialName)) {
      return initialName
    }
    return `conectate-${initialName}`
  }, [initialName])
  const [stage, setStage] = useState<'remote' | 'dicebear' | 'ui-avatars' | 'initials'>(
    trimmedUrl ? 'remote' : 'dicebear',
  )
  const [failedRemote, setFailedRemote] = useState<string | null>(null)
  const [failedDicebear, setFailedDicebear] = useState(false)
  const [failedUiAvatars, setFailedUiAvatars] = useState(false)

  const pixelSize = size === 'sm' ? 64 : size === 'lg' ? 192 : 128

  useEffect(() => {
    // Reset the cascading fallback whenever the upstream inputs change.
    setStage(trimmedUrl ? 'remote' : 'dicebear')
    setFailedRemote(null)
    setFailedDicebear(false)
    setFailedUiAvatars(false)
  }, [trimmedUrl, seed])

  const sizeClass = sizeClassByVariant[size]
  const combinedClassName = [sizeClass, 'safe-avatar', className].filter(Boolean).join(' ')

  if (stage === 'remote' && trimmedUrl && failedRemote !== trimmedUrl) {
    return (
      <img
        className={combinedClassName}
        src={trimmedUrl}
        alt={initialName}
        title={title ?? initialName}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => {
          setFailedRemote(trimmedUrl)
          setStage('dicebear')
        }}
      />
    )
  }

  if (stage === 'dicebear' && !failedDicebear) {
    return (
      <img
        className={combinedClassName}
        src={buildDiceBearUrl(seed, pixelSize)}
        alt={initialName}
        title={title ?? initialName}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => {
          setFailedDicebear(true)
          setStage('ui-avatars')
        }}
      />
    )
  }

  if (stage === 'ui-avatars' && !failedUiAvatars) {
    return (
      <img
        className={combinedClassName}
        src={buildUiAvatarsUrl(initialName, pixelSize)}
        alt={initialName}
        title={title ?? initialName}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => {
          setFailedUiAvatars(true)
          setStage('initials')
        }}
      />
    )
  }

  const hue = hashString(seed) % 360
  const backgroundStyle = {
    backgroundImage: `linear-gradient(135deg, hsl(${hue} 70% 55%), hsl(${(hue + 40) % 360} 70% 45%))`,
  }

  return (
    <span
      className={[sizeClass, 'avatar-fallback', className].filter(Boolean).join(' ')}
      style={backgroundStyle}
      title={title ?? initialName}
      aria-label={initialName}
    >
      {initialsOf(initialName)}
    </span>
  )
}

export default SafeAvatar
