import { StreamingImage, StreamingVideo } from '../components/StreamingMedia'
import { useEffect, useRef, useState, useSyncExternalStore, type ImgHTMLAttributes } from 'react'
import { isVideoAvatarSource } from '../core/profileMediaFields'
import { rewriteRuntimeStorageUrl, runtimeStoragePreviewUrl } from '../core/runtimeBackend'
import { cachedStorageImagePreview, readCachedStorageImage } from '../core/media/imagePreviewCache'
import { STORAGE_PREVIEW_MAX_BYTES } from '../core/media/boundedStorageRead'
import { prepareStorageReadAccessToken, trustedStorageBackend } from '../core/media/storageReadClient'
import { getStorageReadIdentity, subscribeStorageReadIdentity } from '../core/media/storageReadSession'

/** Reuse the existing account-scoped Blob cache, including its decoded browser resource. */
export function CachedProfileImage({ src, mediaType, previewWidth = 160, onSourceUnavailable, ...props }: ImgHTMLAttributes<HTMLImageElement> & { src: string; mediaType: string; previewWidth?: number; onSourceUnavailable: () => void }) {
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const source = /\.(gif|webp|apng)(?:[?#]|$)/i.test(src) || /gif|webp|apng/i.test(mediaType)
    ? rewriteRuntimeStorageUrl(src) : runtimeStoragePreviewUrl(src, previewWidth, 75)
  const backendBase = trustedStorageBackend(source)
  const cache = backendBase && identity.accountId ? cachedStorageImagePreview(source, identity, STORAGE_PREVIEW_MAX_BYTES) : undefined
  const [ready, setReady] = useState<typeof cache>()
  const image = useRef<HTMLImageElement>(null)
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === 'undefined')
  const key = `${identity.generation}/${source}`
  const [streamingKey, setStreamingKey] = useState('')
  const delivered = cache || (ready?.source === source && ready.identity === identity ? ready : undefined)
  const hasCached = Boolean(cache)
  useEffect(() => {
    if (visible || !image.current) return
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) { setVisible(true); observer.disconnect() }
    }, { rootMargin: '128px' })
    observer.observe(image.current)
    return () => observer.disconnect()
  }, [visible])
  useEffect(() => {
    if (!backendBase || !identity.accountId || !visible || hasCached || streamingKey === key) return
    let active = true, retries = 0
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    const load = () => {
      void readCachedStorageImage({ url: source, backendBase, identity, maxBytes: STORAGE_PREVIEW_MAX_BYTES,
        signal: controller.signal, getAccessToken: () => prepareStorageReadAccessToken(identity),
      }).then(() => {
        if (!active) return
        const cached = cachedStorageImagePreview(source, identity, STORAGE_PREVIEW_MAX_BYTES)
        if (cached) setReady(cached)
        else setStreamingKey(key)
      }).catch(error => {
        if (!active || controller.signal.aborted) return
        if (error instanceof Error && error.message === 'MEDIA_READ_QUEUE_FULL' && retries++ < 3) timer = setTimeout(load, 250 * retries)
        else setStreamingKey(key)
      })
    }
    load()
    return () => { active = false; clearTimeout(timer); controller.abort() }
  }, [backendBase, identity, visible, hasCached, streamingKey, key, source])
  return backendBase && streamingKey !== key
    ? <img {...props} ref={image} src={delivered?.url} decoding="async"/>
    : <StreamingImage {...props} ref={image} src={source} onSourceUnavailable={onSourceUnavailable}/>
}

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
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const trimmedUrl = (url ?? '').trim()
  const initialName = name && name.trim().length > 0 ? name.trim() : 'Usuario'
  const normalizedMediaType = (mediaType ?? '').trim()
  const signature = `${identity.generation}|${trimmedUrl}|${normalizedMediaType}`
  const [failedSignature, setFailedSignature] = useState('')
  useEffect(() => {
    if (!failedSignature) return
    const retry = () => setFailedSignature('')
    window.addEventListener('online', retry)
    navigator.serviceWorker?.addEventListener('controllerchange', retry)
    return () => { window.removeEventListener('online', retry); navigator.serviceWorker?.removeEventListener('controllerchange', retry) }
  }, [failedSignature])
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
        onSourceUnavailable={() => setFailedSignature(signature)}
      />
    )
  }

  if (canRenderMedia) {
    return (
      <CachedProfileImage
        className={combinedClassName}
        src={trimmedUrl}
        mediaType={normalizedMediaType}
        alt={initialName}
        title={title ?? initialName}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => setFailedSignature(signature)}
        onSourceUnavailable={() => setFailedSignature(signature)}
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
