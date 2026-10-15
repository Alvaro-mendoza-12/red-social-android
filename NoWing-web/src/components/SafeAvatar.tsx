import { StreamingImage, StreamingVideo } from '../components/StreamingMedia'
import { useState } from 'react'
import { isVideoAvatarSource } from '../core/profileMediaFields'

type AvatarSize = 'sm' | 'md' | 'lg'

type SafeAvatarProps = {
  url?: string | null
  name: string
  size?: AvatarSize
  className?: string
  title?: string
  mediaType?: string | null
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

/**
 * Renders a real image or video avatar and falls back to accessible initials
 * only when that media source is absent or cannot be loaded.
 */
export function SafeAvatar({
  url,
  name,
  size = 'md',
  className,
  title,
  mediaType,
}: SafeAvatarProps) {
  const trimmedUrl = (url ?? '').trim()
  const initialName = name && name.trim().length > 0 ? name.trim() : 'Usuario'
  const normalizedMediaType = (mediaType ?? '').trim()
  const signature = `${trimmedUrl}|${normalizedMediaType}`
  const [failedSignature, setFailedSignature] = useState('')
  const canRenderMedia = Boolean(trimmedUrl && failedSignature !== signature)

  const sizeClass = sizeClassByVariant[size]
  const combinedClassName = [sizeClass, 'safe-avatar', className].filter(Boolean).join(' ')

  if (canRenderMedia && isVideoAvatarSource(trimmedUrl, normalizedMediaType)) {
    return (
      <StreamingVideo
        className={combinedClassName}
        src={trimmedUrl}
        title={title ?? initialName}
        aria-label={initialName}
        muted
        loop
        playsInline
        autoPlay
        preload="metadata"
        onError={() => setFailedSignature(signature)}
      />
    )
  }

  if (canRenderMedia) {
    return (
      <StreamingImage
        className={combinedClassName}
        src={trimmedUrl}
        alt={initialName}
        title={title ?? initialName}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => setFailedSignature(signature)}
      />
    )
  }

  const hue = hashString(initialName) % 360
  const backgroundStyle = {
    backgroundImage: `linear-gradient(135deg, hsl(${hue} 70% 55%), hsl(${(hue + 40) % 360} 70% 45%))`,
  }

  return (
    <span
      className={[sizeClass, 'safe-avatar', 'avatar-fallback', className].filter(Boolean).join(' ')}
      style={backgroundStyle}
      title={title ?? initialName}
      aria-label={initialName}
    >
      {initialsOf(initialName)}
    </span>
  )
}

export default SafeAvatar
