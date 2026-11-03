import { StreamingImage, StreamingVideo } from '../components/StreamingMedia'
import { useEffect, useRef, useState, useSyncExternalStore, type ImgHTMLAttributes, type VideoHTMLAttributes } from 'react'
import { isVideoAvatarSource } from '../core/profileMediaFields'
import { rewriteRuntimeStorageUrl, runtimeStoragePreviewUrl } from '../core/runtimeBackend'
import { cachedStorageImagePreview, readCachedStorageImage, readCachedAvatarVideo } from '../core/media/imagePreviewCache'
import { STORAGE_PREVIEW_MAX_BYTES } from '../core/media/boundedStorageRead'
import { prepareStorageReadAccessToken, trustedStorageBackend } from '../core/media/storageReadClient'
import { getStorageReadIdentity, subscribeStorageReadIdentity } from '../core/media/storageReadSession'

const pendingImage = 'data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs='
const avatarVideoMaxBytes = 8 * 1024 * 1024

/** Reuse the existing account-scoped Blob cache, including its decoded browser resource. */
export function CachedProfileImage({ src, mediaType, previewWidth = 160, onSourceUnavailable, ...props }: ImgHTMLAttributes<HTMLImageElement> & { src: string; mediaType: string; previewWidth?: number; onSourceUnavailable: () => void }) {
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const animated = /\.(gif|webp|apng)(?:[?#]|$)/i.test(src) || /gif|webp|apng/i.test(mediaType)
  let source = animated ? rewriteRuntimeStorageUrl(src) : runtimeStoragePreviewUrl(src, previewWidth, 75)
  if (animated && trustedStorageBackend(source) && /^\/storage\/v1\/object\/public\/(avatars|groups|communities)\//.test(new URL(source).pathname)) {
    const preview = new URL(runtimeStoragePreviewUrl(source, previewWidth, 75))
    preview.searchParams.set('animated', '1')
    source = preview.href
  }
  const backendBase = trustedStorageBackend(source)
  const cache = backendBase && identity.accountId ? cachedStorageImagePreview(source, identity, STORAGE_PREVIEW_MAX_BYTES) : undefined
  const [ready, setReady] = useState<typeof cache>()
  const image = useRef<HTMLImageElement>(null)
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === 'undefined')
  const eager = props.loading === 'eager'
  const shouldLoad = eager || visible
  const key = `${identity.generation}/${source}`
  const [streamingKey, setStreamingKey] = useState('')
  const delivered = cache || (ready?.source === source && ready.identity === identity ? ready : undefined)
  const hasCached = Boolean(cache)
  useEffect(() => {
    if (shouldLoad || !image.current) return
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) { setVisible(true); observer.disconnect() }
    }, { rootMargin: '128px' })
    observer.observe(image.current)
    return () => observer.disconnect()
  }, [shouldLoad])
  useEffect(() => {
    if (!backendBase || !identity.accountId || !shouldLoad || hasCached || streamingKey === key) return
    let active = true, retries = 0
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    const load = () => {
      void readCachedStorageImage({ url: source, backendBase, identity, maxBytes: STORAGE_PREVIEW_MAX_BYTES,
        signal: controller.signal, priority: eager, getAccessToken: () => prepareStorageReadAccessToken(identity),
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
  }, [backendBase, identity, shouldLoad, hasCached, streamingKey, key, source, eager])
  return backendBase && streamingKey !== key
    ? <img {...props} ref={image} src={delivered?.url || pendingImage} decoding="async" aria-busy={!delivered}/>
    : <StreamingImage {...props} ref={image} src={source} pendingSrc={pendingImage} onSourceUnavailable={onSourceUnavailable}/>
}

function CachedProfileVideo({ src, priority, onSourceUnavailable, ...props }: VideoHTMLAttributes<HTMLVideoElement> & {
  src: string; priority: boolean; onSourceUnavailable: () => void
}) {
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const source = rewriteRuntimeStorageUrl(src)
  const backendBase = trustedStorageBackend(source)
  const cache = backendBase && identity.accountId ? cachedStorageImagePreview(source, identity, avatarVideoMaxBytes, true) : undefined
  const [ready, setReady] = useState<typeof cache>()
  const [poster, setPoster] = useState<typeof cache>()
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === 'undefined')
  const video = useRef<HTMLVideoElement>(null)
  const key = `${identity.generation}/${source}`
  const [streamingKey, setStreamingKey] = useState('')
  const delivered = cache || (ready?.source === source && ready.identity === identity ? ready : undefined)
  const hasCached = Boolean(cache)
  const posterUrl = poster?.identity === identity && poster.source === `${source}#poster` ? poster.url : undefined
  const shouldLoad = priority || visible
  useEffect(() => {
    if (shouldLoad || !video.current) return
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) { setVisible(true); observer.disconnect() }
    }, { rootMargin: '128px' })
    observer.observe(video.current)
    return () => observer.disconnect()
  }, [shouldLoad])
  useEffect(() => {
    if (!backendBase || !identity.accountId || !shouldLoad || hasCached || streamingKey === key) return
    const controller = new AbortController()
    const options = { backendBase, identity, signal: controller.signal, priority,
      getAccessToken: () => prepareStorageReadAccessToken(identity) }
    if (new URL(source).pathname.startsWith('/storage/v1/object/public/avatars/')) {
      const preview = new URL(runtimeStoragePreviewUrl(source, 160, 78))
      preview.searchParams.set('poster', '1')
      void readCachedStorageImage({ ...options, url: preview.href, maxBytes: STORAGE_PREVIEW_MAX_BYTES }).then(() => {
        if (controller.signal.aborted) return
        const image = cachedStorageImagePreview(preview.href, identity, STORAGE_PREVIEW_MAX_BYTES)
        if (image) setPoster({ ...image, source: `${source}#poster` })
      }).catch(() => {})
    }
    void readCachedAvatarVideo({ ...options, url: source, maxBytes: avatarVideoMaxBytes }).then(() => {
      if (controller.signal.aborted) return
      const clip = cachedStorageImagePreview(source, identity, avatarVideoMaxBytes, true)
      if (clip) setReady(clip)
      else setStreamingKey(key)
    }).catch(() => { if (!controller.signal.aborted) setStreamingKey(key) })
    return () => controller.abort()
  }, [backendBase, identity, source, key, shouldLoad, hasCached, streamingKey, priority])
  return backendBase && streamingKey !== key
    ? <video {...props} ref={video} src={delivered?.url} poster={posterUrl} preload="auto" onError={() => setStreamingKey(key)} data-novvin-media=""/>
    : <StreamingVideo {...props} ref={video} src={source} poster={posterUrl} onSourceUnavailable={onSourceUnavailable}/>
}

type AvatarSize = 'sm' | 'md' | 'lg'

type SafeAvatarProps = {
  url?: string | null
  name: string
  size?: AvatarSize
  className?: string
  title?: string
  mediaType?: string | null
  priority?: boolean
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
  priority = false,
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
      <CachedProfileVideo
        className={combinedClassName}
        src={trimmedUrl}
        priority={priority}
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
        loading={priority ? 'eager' : 'lazy'}
        fetchPriority={priority ? 'high' : undefined}
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
