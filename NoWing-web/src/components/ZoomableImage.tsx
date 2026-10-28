import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Minus, Plus, RotateCcw } from 'lucide-react'
import { StreamingImage } from './StreamingMedia'
import { rewriteRuntimeStorageUrl, runtimeStoragePreviewUrl } from '../core/runtimeBackend'
import { cachedStorageImagePreview } from '../core/media/imagePreviewCache'
import { STORAGE_PREVIEW_MAX_BYTES } from '../core/media/boundedStorageRead'
import { getStorageReadIdentity, subscribeStorageReadIdentity } from '../core/media/storageReadSession'

/** One mounted instance per image: navigation always restores fit-to-screen. */
export function ZoomableImage({ src, title, onError }: { src: string; title: string; onError: () => void }) {
  const stage = useRef<HTMLDivElement>(null)
  const picture = useRef<HTMLImageElement>(null)
  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const view = useRef({ scale: 1, x: 0, y: 0 })
  const [state, setState] = useState(view.current)
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const original = rewriteRuntimeStorageUrl(src)
  const cachedOriginal = identity.accountId ? cachedStorageImagePreview(original, identity, STORAGE_PREVIEW_MAX_BYTES) : undefined
  const cachedPreview = cachedOriginal || (identity.accountId ? cachedStorageImagePreview(runtimeStoragePreviewUrl(original), identity, STORAGE_PREVIEW_MAX_BYTES) : undefined)
  const [attempt, setAttempt] = useState(0)
  const [loadedKey, setLoadedKey] = useState('')
  const [slowKey, setSlowKey] = useState('')
  const requestKey = `${src}/${attempt}`
  const loaded = loadedKey === requestKey
  useEffect(() => {
    if (loaded) return
    const timer = window.setTimeout(() => setSlowKey(requestKey), 15000)
    return () => window.clearTimeout(timer)
  }, [requestKey, loaded])
  const update = useCallback((scale: number, x = view.current.x, y = view.current.y) => {
    const box = stage.current, img = picture.current
    scale = Math.min(5, Math.max(1, scale))
    const maxX = Math.max(0, ((img?.offsetWidth || 0) * scale - (box?.clientWidth || 0)) / 2)
    const maxY = Math.max(0, ((img?.offsetHeight || 0) * scale - (box?.clientHeight || 0)) / 2)
    view.current = { scale, x: Math.max(-maxX, Math.min(maxX, x)), y: Math.max(-maxY, Math.min(maxY, y)) }
    setState(view.current)
  }, [])
  const zoomAt = useCallback((scale: number, clientX?: number, clientY?: number) => {
    const rect = stage.current?.getBoundingClientRect(), old = view.current
    const next = Math.max(1, Math.min(5, scale)), ratio = next / old.scale
    const x = rect && clientX !== undefined ? clientX - rect.left - rect.width / 2 : 0
    const y = rect && clientY !== undefined ? clientY - rect.top - rect.height / 2 : 0
    update(next, x - (x - old.x) * ratio, y - (y - old.y) * ratio)
  }, [update])
  useEffect(() => {
    const node = stage.current
    if (!node) return
    const wheel = (event: WheelEvent) => { event.preventDefault(); zoomAt(view.current.scale * Math.exp(-event.deltaY * .002), event.clientX, event.clientY) }
    const resize = new ResizeObserver(() => update(view.current.scale))
    resize.observe(node)
    node.addEventListener('wheel', wheel, { passive: false })
    return () => { resize.disconnect(); node.removeEventListener('wheel', wheel) }
  }, [update, zoomAt])
  return <div className="image-zoom" onKeyDown={event => {
    if (event.key === '+' || event.key === '=') { event.preventDefault(); zoomAt(view.current.scale + .5) }
    if (event.key === '-') { event.preventDefault(); zoomAt(view.current.scale - .5) }
    if (event.key === '0') update(1, 0, 0)
  }}>
    {!loaded ? <div className="image-zoom-loading" role="status">
      <span>{slowKey === requestKey ? 'La imagen está tardando en cargar.' : 'Cargando imagen…'}</span>
      {slowKey === requestKey ? <button className="mini-action-btn" type="button" onClick={() => setAttempt(value => value + 1)}>Reintentar imagen</button> : null}
    </div> : null}
    <div className="image-zoom-controls" aria-label="Zoom de imagen">
      <button className="icon-btn" aria-label="Alejar" disabled={state.scale === 1} onClick={() => zoomAt(state.scale - .5)}><Minus /></button>
      <output aria-live="polite">{Math.round(state.scale * 100)}%</output>
      <button className="icon-btn" aria-label="Ampliar" disabled={state.scale === 5} onClick={() => zoomAt(state.scale + .5)}><Plus /></button>
      <button className="icon-btn" aria-label="Restablecer tamaño" onClick={() => update(1, 0, 0)}><RotateCcw /></button>
    </div>
    <div ref={stage} className={`image-zoom-canvas${state.scale > 1 ? ' is-zoomed' : ''}`} tabIndex={0} role="img" aria-label={`${title}. Usa la rueda, dos dedos o los botones para ampliar.`}
      onDoubleClick={event => zoomAt(state.scale > 1 ? 1 : 2.5, event.clientX, event.clientY)}
      onPointerDown={event => { pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY }); event.currentTarget.setPointerCapture(event.pointerId) }}
      onPointerMove={event => {
        const points = pointers.current, before = points.get(event.pointerId)
        if (!before) return
        const other = [...points.entries()].find(([id]) => id !== event.pointerId)?.[1]
        points.set(event.pointerId, { x: event.clientX, y: event.clientY })
        if (other) {
          const previous = Math.hypot(before.x - other.x, before.y - other.y), next = Math.hypot(event.clientX - other.x, event.clientY - other.y)
          if (previous > 1) zoomAt(view.current.scale * next / previous, (event.clientX + other.x) / 2, (event.clientY + other.y) / 2)
        } else update(view.current.scale, view.current.x + event.clientX - before.x, view.current.y + event.clientY - before.y)
      }}
      onPointerUp={event => pointers.current.delete(event.pointerId)} onPointerCancel={event => pointers.current.delete(event.pointerId)} onLostPointerCapture={event => pointers.current.delete(event.pointerId)}>
      {!loaded && cachedPreview && !cachedOriginal ? <img className="image-zoom-preview" src={cachedPreview.url} alt="" aria-hidden="true" draggable={false}/> : null}
      <StreamingImage key={requestKey} ref={picture} src={cachedOriginal?.url || src} alt={title} draggable={false} onError={onError} onLoad={() => { setLoadedKey(requestKey); update(view.current.scale) }} style={{ transform: `translate(${state.x}px, ${state.y}px) scale(${state.scale})` }} />
    </div>
  </div>
}
