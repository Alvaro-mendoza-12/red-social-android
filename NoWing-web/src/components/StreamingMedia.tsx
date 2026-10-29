import { forwardRef, useCallback, useEffect, useRef, useState, useSyncExternalStore, type AudioHTMLAttributes, type ImgHTMLAttributes, type VideoHTMLAttributes } from 'react'
import { browserStreamingSource, cachedBrowserStreamingSource, subscribeBrowserStreamingRecovery } from '../core/media/browserStreaming'
import { getStorageReadIdentity, subscribeStorageReadIdentity } from '../core/media/storageReadSession'
import { trustedStorageBackend } from '../core/media/storageReadClient'
import { rewriteRuntimeStorageUrl } from '../core/runtimeBackend'

function useSource(raw?: string, retryKey = 0) {
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const source = rewriteRuntimeStorageUrl(raw || '')
  const protectedSource = !source.startsWith('blob:') && Boolean(trustedStorageBackend(source))
  const [ready, setReady] = useState<{ source: string; identity: typeof identity; url: string } | null>(null)
  const [unavailable, setUnavailable] = useState<{ source: string; identity: typeof identity; retryKey: number } | null>(null)
  const revisionRef = useRef(0)
  const retryRef = useRef<() => boolean>(() => false)
  const loadedRef = useRef<() => void>(() => {})
  useEffect(() => {
    retryRef.current = () => false
    if (!source || !protectedSource || !identity.accountId) return
    let active = true, failures = 0, request = 0
    let timer: ReturnType<typeof setTimeout> | undefined
    const load = async (refresh = false) => {
      const sequence = ++request
      try {
        const url = await browserStreamingSource(source, identity)
        if (active && sequence === request) {
          if (refresh || retryKey > 0) revisionRef.current++
          setReady({ source, identity, url: revisionRef.current ? `${url}?retry=${revisionRef.current}` : url })
          setUnavailable(null)
        }
      } catch { if (active && sequence === request) schedule() }
    }
    const schedule = () => {
      if (!active) return false
      if (timer) return true
      if (failures >= 3) { setUnavailable({ source, identity, retryKey }); return false }
      timer = setTimeout(() => { timer = undefined; void load(true) }, [350, 1200, 3000][failures++])
      return true
    }
    retryRef.current = schedule
    loadedRef.current = () => { failures = 0; clearTimeout(timer); timer = undefined }
    const unsubscribe = subscribeBrowserStreamingRecovery(() => {
      // Healthy media keeps its playback position when returning to the tab.
      if (!failures) return
      clearTimeout(timer); timer = undefined; failures = 0; void load(true)
    })
    void load()
    return () => { active = false; clearTimeout(timer); unsubscribe(); retryRef.current = () => false; loadedRef.current = () => {} }
  }, [source, protectedSource, identity, retryKey])
  const retry = useCallback(() => retryRef.current(), [])
  const loaded = useCallback(() => loadedRef.current(), [])
  return { url: !protectedSource ? source || undefined : ready?.source === source && ready.identity === identity ? ready.url : cachedBrowserStreamingSource(source, identity), unavailable: unavailable?.source === source && unavailable.identity === identity && unavailable.retryKey === retryKey, retry, loaded }
}
export const StreamingImage = forwardRef<HTMLImageElement, ImgHTMLAttributes<HTMLImageElement> & { onSourceUnavailable?: () => void }>(function StreamingImage({ src, onError, onLoad, onSourceUnavailable, ...props }, ref) {
  const source = useSource(src)
  useEffect(() => { if (source.unavailable) onSourceUnavailable?.() }, [source.unavailable, onSourceUnavailable])
  return <img {...props} ref={ref} src={source.url} onLoad={event => { source.loaded(); onLoad?.(event) }} onError={event => { if (!source.retry()) onError?.(event) }} data-novvin-media={src ? '' : undefined} />
})
export const StreamingVideo = forwardRef<HTMLVideoElement, VideoHTMLAttributes<HTMLVideoElement> & { onSourceUnavailable?: () => void; retryKey?: number }>(function StreamingVideo({ src, poster, onError, onLoadedData, onSourceUnavailable, retryKey, ...props }, ref) {
  const source = useSource(src, retryKey)
  const posterSource = useSource(poster, retryKey)
  useEffect(() => { if (source.unavailable) onSourceUnavailable?.() }, [source.unavailable, onSourceUnavailable])
  return <video {...props} ref={ref} src={source.url} poster={posterSource.url} onLoadedData={event => { source.loaded(); onLoadedData?.(event) }} onError={event => { if (!source.retry()) onError?.(event) }} data-novvin-media={src ? '' : undefined} />
})
export const StreamingAudio = forwardRef<HTMLAudioElement, AudioHTMLAttributes<HTMLAudioElement> & { onSourceUnavailable?: () => void }>(function StreamingAudio({ src, onError, onLoadedData, onSourceUnavailable, ...props }, ref) {
  const source = useSource(src)
  useEffect(() => { if (source.unavailable) onSourceUnavailable?.() }, [source.unavailable, onSourceUnavailable])
  return <audio {...props} ref={ref} src={source.url} onLoadedData={event => { source.loaded(); onLoadedData?.(event) }} onError={event => { if (!source.retry()) onError?.(event) }} data-novvin-media={src ? '' : undefined} />
})
