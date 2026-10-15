import { forwardRef, useEffect, useState, useSyncExternalStore, type AudioHTMLAttributes, type ImgHTMLAttributes, type VideoHTMLAttributes } from 'react'
import { browserStreamingSource } from '../core/media/browserStreaming'
import { getStorageReadIdentity, subscribeStorageReadIdentity } from '../core/media/storageReadSession'
import { trustedStorageBackend } from '../core/media/storageReadClient'
import { rewriteRuntimeStorageUrl } from '../core/runtimeBackend'

function useSource(raw?: string) {
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const source = rewriteRuntimeStorageUrl(raw || '')
  const protectedSource = !source.startsWith('blob:') && Boolean(trustedStorageBackend(source))
  const [ready, setReady] = useState<{ source: string; identity: typeof identity; url: string } | null>(null)
  useEffect(() => {
    if (!source || !protectedSource || !identity.accountId) return
    let active = true
    void browserStreamingSource(source, identity).then(url => { if (active) setReady({ source, identity, url }) }).catch(() => {})
    return () => { active = false }
  }, [source, protectedSource, identity])
  if (!protectedSource) return source || undefined
  return ready?.source === source && ready.identity === identity ? ready.url : undefined
}
export const StreamingImage = forwardRef<HTMLImageElement, ImgHTMLAttributes<HTMLImageElement>>(function StreamingImage({ src, ...props }, ref) {
  const source = useSource(src)
  return <img {...props} ref={ref} src={source} data-novvin-media={src ? '' : undefined} />
})
export const StreamingVideo = forwardRef<HTMLVideoElement, VideoHTMLAttributes<HTMLVideoElement>>(function StreamingVideo({ src, ...props }, ref) {
  const source = useSource(src)
  return <video {...props} ref={ref} src={source} data-novvin-media={src ? '' : undefined} />
})
export const StreamingAudio = forwardRef<HTMLAudioElement, AudioHTMLAttributes<HTMLAudioElement>>(function StreamingAudio({ src, ...props }, ref) {
  const source = useSource(src)
  return <audio {...props} ref={ref} src={source} data-novvin-media={src ? '' : undefined} />
})
