import { MediaViewer } from './MediaViewer'
import { browserStreamingSource } from '../core/media/browserStreaming'
import { StreamingVideo, StreamingAudio } from './StreamingMedia'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { rewriteRuntimeStorageUrl, runtimeStoragePreviewUrl } from '../core/runtimeBackend'
import { prepareStorageReadAccessToken, trustedStorageBackend } from '../core/media/storageReadClient'
import {
  readBoundedStorageBlob, STORAGE_PREVIEW_MAX_BYTES, STORAGE_SHORT_MEDIA_MAX_BYTES,
} from '../core/media/boundedStorageRead'
import {
  getStorageReadIdentity, onStorageReadInvalidated, requireStorageReadIdentity,
  subscribeStorageReadIdentity, type StorageReadIdentity,
} from '../core/media/storageReadSession'

export type ChatAttachmentKind = 'image' | 'video' | 'audio' | 'pdf' | 'file'
type Preview = { source: string; identity: StorageReadIdentity; url: string; mime: string; size: number }

function safeExternalUrl(value: string): string {
  try {
    const parsed = new URL(value)
    return parsed.protocol === 'https:' && !parsed.username && !parsed.password ? parsed.href : ''
  } catch { return '' }
}

function attachmentName(url: string, fallback: string) {
  if (/^[^/\\\u0000-\u001f]{1,180}\.[a-z0-9]{1,10}$/i.test(fallback.trim())) return fallback.trim()
  const name = url.split('/').at(-1)?.split(/[?#]/)[0] || fallback
  try { return decodeURIComponent(name) } catch { return name }
}

function acceptsInline(kind: ChatAttachmentKind, mime: string): boolean {
  if (kind === 'image') return /^image\/(png|jpeg|webp|gif|avif|bmp)$/.test(mime)
  if (kind === 'audio' || kind === 'video') return mime.startsWith(`${kind}/`)
  if (kind === 'pdf') return mime === 'application/pdf'
  return true
}

export function attachmentKind(contentType: string, url: string): ChatAttachmentKind {
  const value = contentType.toLowerCase()
  if (value === 'sticker' || value === 'image' || value.startsWith('image/')) return 'image'
  if (value === 'audio' || value.startsWith('audio/')) return 'audio'
  if (value === 'video' || value.startsWith('video/')) return 'video'
  if (value.includes('pdf')) return 'pdf'
  const path = url.split(/[?#]/)[0].toLowerCase()
  if (/\.(png|jpg|jpeg|webp|gif|avif)$/.test(path)) return 'image'
  if (/\.(mp4|webm|mov|m4v)$/.test(path)) return 'video'
  if (/\.(mp3|m4a|aac|ogg|oga|wav)$/.test(path)) return 'audio'
  if (path.endsWith('.pdf')) return 'pdf'
  return 'file'
}

export function ChatAttachmentMedia({
  url, kind, title = 'Archivo', size = 0, imageClassName = 'chat-media-image', imageLink = true, onImageOpen, preserveAnimation = false, allowRetry = true,
}: {
  url: string; kind: ChatAttachmentKind; title?: string; size?: number; imageClassName?: string; imageLink?: boolean; onImageOpen?: () => void; preserveAnimation?: boolean; allowRetry?: boolean
}) {
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const rewritten = rewriteRuntimeStorageUrl(url)
  const source = kind === 'image' && !preserveAnimation && !/\.(gif|webp)(?:[?#]|$)/i.test(rewritten) ? runtimeStoragePreviewUrl(rewritten) : rewritten
  const backendBase = trustedStorageBackend(source)
  const ownStorage = Boolean(backendBase)
  const maxBytes = kind === 'image' ? STORAGE_PREVIEW_MAX_BYTES : STORAGE_SHORT_MEDIA_MAX_BYTES
  const [viewerOpen,setViewerOpen]=useState(false)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [failure, setFailure] = useState<{ source: string; identity: StorageReadIdentity; code: string } | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [visible, setVisible] = useState(false)
  const [readRequested, setReadRequested] = useState(false)
  const [downloadRequested, setDownloadRequested] = useState(false)
  const [downloadPreparing, setDownloadPreparing] = useState(false)
  const container = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const target = container.current
    if (!target || typeof IntersectionObserver === 'undefined') { setVisible(true); return }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) { setVisible(true); observer.disconnect() }
    }, { rootMargin: '250px' })
    observer.observe(target)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!ownStorage || !visible || !identity.accountId || kind === 'video' || kind === 'audio' || ((kind === 'file' || kind === 'pdf') && !readRequested)) return
    let active = true
    let objectUrl = ''
    const controller = new AbortController()
    const clearDeliveredMedia = () => {
      controller.abort()
      if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = '' }
      // React renders the new identity next; stop old decoded/buffered media now.
      container.current?.querySelectorAll('audio,video').forEach((element) => {
        const player = element as HTMLMediaElement
        player.pause(); player.removeAttribute('src'); player.load()
      })
      container.current?.querySelectorAll('img').forEach((element) => element.removeAttribute('src'))
      container.current?.querySelectorAll('iframe').forEach((element) => element.setAttribute('src', 'about:blank'))
      container.current?.querySelectorAll('a').forEach((element) => element.removeAttribute('href'))
    }
    const removeInvalidator = onStorageReadInvalidated(clearDeliveredMedia)
    if (size > maxBytes) {
      setFailure({ source, identity, code: 'MEDIA_TOO_LARGE' })
    } else {
      void readBoundedStorageBlob({
        url: source, backendBase, identity, maxBytes, signal: controller.signal,
        getAccessToken: () => prepareStorageReadAccessToken(identity),
      }).then((blob) => {
        requireStorageReadIdentity(identity)
        if (!active || controller.signal.aborted) return
        if (!acceptsInline(kind, blob.type)) throw new Error('MEDIA_UNSUPPORTED_TYPE')
        // Unknown documents are downloads; never open arbitrary HTML/SVG in an app-origin Blob tab.
        const safeBlob = kind === 'file' ? new Blob([blob], { type: 'application/octet-stream' }) : blob
        objectUrl = URL.createObjectURL(safeBlob)
        requireStorageReadIdentity(identity)
        if (!active || controller.signal.aborted) { clearDeliveredMedia(); return }
        setPreview({ source, identity, url: objectUrl, mime: blob.type, size: blob.size })
      }).catch((error: unknown) => {
        if (!active || controller.signal.aborted) return
        const code = error instanceof Error && error.message === 'MEDIA_TOO_LARGE' ? 'MEDIA_TOO_LARGE' : 'MEDIA_UNAVAILABLE'
        setFailure({ source, identity, code })
      })
    }
    return () => { active = false; removeInvalidator(); clearDeliveredMedia() }
  }, [backendBase, identity, kind, maxBytes, ownStorage, size, source, visible, attempt, readRequested])

  useEffect(()=>onStorageReadInvalidated(()=>{setViewerOpen(false);setReadRequested(false);setDownloadRequested(false)}),[])
  const ready = preview?.source === source && preview.identity === identity ? preview : null
  const currentFailure = failure?.source === source && failure.identity === identity ? failure : null
  const playbackUrl = ownStorage ? ready?.url ?? '' : safeExternalUrl(source)
  const retry = () => { setFailure(null); setPreview(null); setAttempt((value) => value + 1) }
  const decodingFailed = () => setFailure({ source, identity, code: 'MEDIA_UNAVAILABLE' })
  async function downloadOriginal() {
    if (downloadPreparing) return
    if (!ownStorage || !('serviceWorker' in navigator)) { setReadRequested(true); setDownloadRequested(true); return }
    setDownloadPreparing(true)
    try {
      const handle = await browserStreamingSource(rewritten, identity, { downloadName: kind === 'file' || kind === 'pdf' ? attachmentName(rewritten,title) : title }); requireStorageReadIdentity(identity)
      const link = document.createElement('a'); link.href = handle; link.download = kind === 'file' || kind === 'pdf' ? attachmentName(rewritten,title) : title; link.rel = 'noopener noreferrer'; link.click()
    } catch { setFailure({ source, identity, code: 'MEDIA_UNAVAILABLE' }) } finally { setDownloadPreparing(false) }
  }
  useEffect(() => {
    if (!downloadRequested || !playbackUrl) return
    const link = document.createElement('a'); link.href = playbackUrl; link.download = kind === 'file' || kind === 'pdf' ? attachmentName(rewritten,title) : title; link.rel = 'noopener noreferrer'; link.click(); setDownloadRequested(false)
  }, [downloadRequested, playbackUrl, title])
  if (kind === 'video' || kind === 'audio') {
    return <div ref={container}>{kind === 'video'
      ? <><StreamingVideo className="chat-media-video" src={visible ? rewritten : undefined} controls preload="metadata" playsInline /><button type="button" className="mini-action-btn" onClick={()=>{container.current?.querySelector('video')?.pause();setViewerOpen(true)}}>Ampliar video</button>{viewerOpen?<MediaViewer items={[{url:rewritten,type:'video',title}]} onClose={()=>setViewerOpen(false)}/>:null}</>
      : <StreamingAudio className="chat-audio-player" src={visible ? rewritten : undefined} controls preload="metadata" />}</div>
  }
  let content
  if (ownStorage && !identity.accountId) {
    content = <p className="muted-line">Inicia sesión para abrir este archivo.</p>
  } else if (currentFailure || !playbackUrl && !ownStorage) {
    const large = currentFailure?.code === 'MEDIA_TOO_LARGE'
    content = <div className="chat-file-card media-load-error" role="status">
      <span><strong>{large ? 'Archivo demasiado grande para la vista web' : 'No se pudo abrir el archivo'}</strong><small>{title}</small></span>
      {!large && allowRetry ? <button className="mini-action-btn" type="button" onClick={retry}>Reintentar</button> : null}
      {kind === 'file' || kind === 'pdf' ? <button type="button" className="mini-action-btn" disabled={downloadPreparing} onClick={() => void downloadOriginal()}>{downloadPreparing ? 'Preparando…' : 'Descargar original'}</button> : null}
    </div>
  } else if ((kind === 'file' || kind === 'pdf') && !viewerOpen && !downloadRequested) {
    content = <div className="chat-file-card"><span className="chat-file-icon">{kind === 'pdf' ? 'PDF' : 'DOC'}</span><span><strong>{title}</strong>{size > 0 ? <small>{Math.ceil(size / 1024)} KB</small> : null}<span className="chat-document-actions"><button className="mini-action-btn" type="button" onClick={() => { setReadRequested(true); setViewerOpen(true) }}>Abrir documento</button><button className="mini-action-btn" type="button" disabled={downloadPreparing} onClick={() => void downloadOriginal()}>{downloadPreparing ? 'Preparando…' : 'Descargar'}</button></span></span></div>
  } else if (!playbackUrl) {
    content = <div className="chat-file-card chat-file-card-loading" role="status"><span><strong>Preparando archivo</strong><small>{title}</small></span></div>
  } else if (kind === 'image') {
    const image = <img className={imageClassName} src={playbackUrl} alt={title} loading="lazy" decoding="async" onError={decodingFailed} />
    content = imageLink ? <button type="button" className="chat-media-card" aria-label={`Ampliar ${title}`} onClick={()=>onImageOpen ? onImageOpen() : setViewerOpen(true)}>{image}</button> : image
  } else if (kind === 'pdf') {
    content = <div className="chat-file-card"><strong>{title}</strong></div>
  } else {
    content = <a className="chat-file-card" href={playbackUrl} download={title} rel="noopener noreferrer"><span className="chat-file-icon">DOC</span><span><strong>{title}</strong>{ready?.size ? <small>{Math.ceil(ready.size / 1024)} KB</small> : null}</span></a>
  }
  return <div ref={container}>{content}{viewerOpen && playbackUrl && (!ownStorage || identity.accountId) ? <MediaViewer items={[{url:playbackUrl,type:kind==='pdf'?'pdf':kind==='file'?'document':'image',title,documentName:attachmentName(rewritten, title),downloadUrl:playbackUrl}]} onClose={()=>setViewerOpen(false)}/> : null}</div>
}
