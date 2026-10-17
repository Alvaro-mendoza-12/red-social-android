import { StreamingImage, StreamingVideo } from '../components/StreamingMedia'
import { useEffect, useRef, useState } from 'react'
import { readBoundedStorageBlob } from '../core/media/boundedStorageRead'
import { prepareStorageReadAccessToken } from '../core/media/storageReadClient'
import { onStorageReadInvalidated, requireStorageReadIdentity } from '../core/media/storageReadSession'
import { viewOnceLimit, type ViewOnceLease } from '../core/media/viewOnceLease'

/** One authenticated GET, an ephemeral Blob, and deletion on close/background/logout. */
export function ViewOnceMediaDialog({ lease, onClose }: { lease: ViewOnceLease; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const close = useRef(onClose)
  useEffect(() => { close.current = onClose }, [onClose])
  const [preview, setPreview] = useState('')
  const [failed, setFailed] = useState(false)
  const [zoomed, setZoomed] = useState(false)
  useEffect(() => {
    const element = dialog.current
    if (!element) return
    const previousFocus = document.activeElement as HTMLElement | null
    if (typeof element.showModal === 'function') element.showModal()
    else element.setAttribute('open', '')
    let active = true, objectUrl = ''
    const controller = new AbortController()
    const clear = () => {
      active = false; controller.abort()
      element.querySelectorAll('video').forEach(player => { player.pause(); player.removeAttribute('src'); player.load() })
      element.querySelectorAll('img').forEach(image => image.removeAttribute('src'))
      if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = '' }
    }
    const dismiss = () => { clear(); close.current() }
    const background = () => { if (document.visibilityState === 'hidden') dismiss() }
    const invalidator = onStorageReadInvalidated(dismiss)
    document.addEventListener('visibilitychange', background)
    window.addEventListener('pagehide', dismiss)
    const expires = setTimeout(dismiss, Math.max(0, lease.expiresAt - Date.now()))
    void (async () => {
      requireStorageReadIdentity(lease.identity)
      if (lease.expiresAt <= Date.now() || document.visibilityState === 'hidden') throw new Error('VIEW_ONCE_EXPIRED')
      const blob = await readBoundedStorageBlob({ url: lease.url, backendBase: lease.backend, identity: lease.identity, signal: controller.signal, maxBytes: viewOnceLimit(lease.kind), getAccessToken: () => prepareStorageReadAccessToken(lease.identity) })
      requireStorageReadIdentity(lease.identity)
      if (!active || controller.signal.aborted) return
      const supported = lease.kind === 'image' ? /^image\/(png|jpeg|webp|gif|avif|bmp)$/.test(blob.type) : /^video\/(mp4|webm|quicktime|x-m4v)$/.test(blob.type)
      if (!supported || blob.size !== lease.bytes) throw new Error('VIEW_ONCE_INVALID_CONTENT')
      objectUrl = URL.createObjectURL(blob)
      requireStorageReadIdentity(lease.identity)
      if (!active || controller.signal.aborted) { clear(); return }
      setPreview(objectUrl)
    })().catch(() => { if (active && !controller.signal.aborted) setFailed(true) })
    return () => {
      clear(); clearTimeout(expires); invalidator()
      document.removeEventListener('visibilitychange', background); window.removeEventListener('pagehide', dismiss)
      if (element.open && typeof element.close === 'function') element.close()
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [lease])
  return <dialog ref={dialog} aria-label="Ver una vez" onCancel={event => { event.preventDefault(); onClose() }} onContextMenu={event => event.preventDefault()} style={{ position: 'fixed', inset: 0, width: '100%', height: '100%', maxWidth: 'none', maxHeight: 'none', margin: 0, padding: 12, border: 0, background: '#09090c', color: '#fff', overflow: 'hidden' }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}><strong>Ver una vez</strong><button type="button" onClick={onClose}>Cerrar</button></div>
    <div style={{ height: 'calc(100% - 64px)', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'auto' }}>
      {failed ? <p role="alert">No se pudo abrir este archivo. Por seguridad, no se puede volver a abrir.</p> : !preview ? <p role="status">Preparando archivo…</p> : lease.kind === 'image' ? <StreamingImage src={preview} alt="Foto de una sola visualización" draggable={false} onError={() => setFailed(true)} style={{ maxWidth: zoomed ? '200%' : '100%', maxHeight: zoomed ? '200%' : '100%', transform: zoomed ? 'scale(2)' : undefined, objectFit: 'contain' }} /> : <StreamingVideo src={preview} controls autoPlay playsInline disablePictureInPicture controlsList="nodownload noremoteplayback" onEnded={onClose} onError={() => setFailed(true)} style={{ maxWidth: '100%', maxHeight: '100%' }} />}
    </div>
    {preview && !failed && lease.kind === 'image' ? <button type="button" onClick={() => setZoomed(value => !value)} style={{ position: 'absolute', bottom: 12, left: 12 }}>{zoomed ? 'Restablecer' : 'Ampliar'}</button> : null}
  </dialog>
}
