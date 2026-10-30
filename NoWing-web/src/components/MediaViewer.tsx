import { useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Film, Image as ImageIcon, X } from 'lucide-react'
import { runtimeStoragePreviewUrl } from '../core/runtimeBackend'
import { StreamingImage, StreamingVideo } from './StreamingMedia'
import { onStorageReadInvalidated } from '../core/media/storageReadSession'
import { DocumentPreview } from './DocumentPreview'
import { ZoomableImage } from './ZoomableImage'
import { MediaDownloadButton } from './MediaDownloadButton'

export type ViewerItem = {
  id?: string
  url: string
  type: 'image' | 'video' | 'pdf' | 'document'
  title?: string
  downloadUrl?: string
  documentName?: string
}
export function MediaViewer({
  items,
  initialIndex = 0,
  onClose,
}: {
  items: ViewerItem[]
  initialIndex?: number
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const strip = useRef<HTMLElement>(null)
  const close = useRef(onClose)
  useEffect(() => { close.current = onClose }, [onClose])
  const keyOf = (entry: ViewerItem) => entry.id || entry.url
  const [selectedKey, setSelectedKey] = useState(() => {
    const initial = items[Math.max(0, Math.min(initialIndex, items.length - 1))]
    return initial ? keyOf(initial) : ''
  })
  const index = Math.max(0, items.findIndex(entry => keyOf(entry) === selectedKey))
  const [failedKey, setFailedKey] = useState<string|null>(null)
  const [attempt, setAttempt] = useState(0)
  const [visibleThumbnails, setVisibleThumbnails] = useState(() => new Set<string>())
  const item = items[index]
  const failed = item && failedKey === keyOf(item)
  const setIndex = (value: number) => { if (items[value]) setSelectedKey(keyOf(items[value])) }
  useEffect(() => {
    // The dialog must be open before measuring its initially hidden filmstrip.
    const frame = window.requestAnimationFrame(() => strip.current?.querySelector('[aria-current="true"]')?.scrollIntoView?.({block:'nearest',inline:'center'}))
    return () => window.cancelAnimationFrame(frame)
  }, [index])
  useEffect(() => {
    const node = dialog.current,
      previous = document.activeElement as HTMLElement | null
    if (!node) return
    if (typeof node.showModal === 'function') node.showModal()
    else node.setAttribute('open', '')
    const bodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const invalidate = onStorageReadInvalidated(() => {
      node.querySelectorAll('img,video,iframe').forEach((media) => media.removeAttribute('src'))
      node.close?.()
      close.current()
    })
    return () => {
      invalidate()
      document.body.style.overflow = bodyOverflow
      if (node.open && typeof node.close === 'function') node.close()
      if (previous?.isConnected) previous.focus()
    }
  }, [])
  useEffect(() => {
    const root = strip.current
    if (!root || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(entries => {
      setVisibleThumbnails(previous => {
        const next = new Set(previous)
        for (const entry of entries) {
          const key = (entry.target as HTMLElement).dataset.mediaKey
          if (entry.isIntersecting && key) next.add(key)
        }
        return next.size === previous.size ? previous : next
      })
    }, { root })
    root.querySelectorAll('[data-media-key]').forEach(button => observer.observe(button))
    return () => observer.disconnect()
  }, [items])
  if (!item) return null
  const move = (delta: number) => setIndex(Math.max(0, Math.min(items.length - 1, index + delta)))
  return (
    <dialog
      ref={dialog}
      className="media-viewer"
      aria-label={item.type === 'pdf' || item.type === 'document' ? 'Ver documento' : 'Visor multimedia'}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      onKeyDown={(event) => {
        if(event.key==='Escape'){event.preventDefault();event.stopPropagation();onClose();return}
        if (event.target instanceof HTMLInputElement) return
        if (items.length > 1 && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
          event.preventDefault()
          move(event.key === 'ArrowRight' ? 1 : -1)
        }
      }}
    >
      <header className="media-viewer-toolbar">
        <div>
          <strong>{item.title || (item.type === 'pdf' ? 'Documento' : 'Multimedia')}</strong>
          {items.length > 1 ? (
            <small>
              {index + 1} de {items.length}
            </small>
          ) : null}
        </div>
        <div className="row-actions">
          <MediaDownloadButton url={item.downloadUrl || item.url} name={item.documentName || item.title || ''} iconOnly />
          <button type="button" className="icon-btn" aria-label="Cerrar visor" onClick={onClose}>
            <X />
          </button>
        </div>
      </header>
      <div
        className={`media-viewer-stage${item.type === 'image' ? ' image-stage' : ''}`}
        onClick={(event) => {
          if (event.target === event.currentTarget) onClose()
        }}
      >
        {failed ? (
          <div className="media-viewer-error" role="status">
            <p>No se pudo mostrar este archivo. Puedes reintentar o descargarlo desde el botón de arriba.</p>
            <button className="ghost-btn" type="button" onClick={() => {setFailedKey(null);setAttempt(value => value + 1)}}>Volver a cargar</button>
          </div>
        ) : item.type === 'pdf' || item.type === 'document' ? (
          <DocumentPreview key={item.url} url={item.url} title={item.documentName || item.title || 'Documento'} pdf={item.type === 'pdf'} />
        ) : item.type === 'video' ? (
          <StreamingVideo
            key={`${item.url}/${attempt}`}
            src={item.url}
            controls
            autoPlay
            playsInline
            preload="metadata"
            onError={() => setFailedKey(keyOf(item))}
            onSourceUnavailable={() => setFailedKey(keyOf(item))}
          />
        ) : (
          <ZoomableImage key={`${keyOf(item)}/${attempt}`} src={item.url} title={item.title || 'Imagen ampliada'} onError={() => setFailedKey(keyOf(item))} />
        )}
      </div>
      {items.length>1?<nav ref={strip} className="media-viewer-filmstrip" aria-label="Archivos de la conversación">{items.map((entry,i)=><button type="button" key={`${entry.url}-${i}`} data-media-key={keyOf(entry)} aria-label={`Ver archivo ${i+1}`} aria-current={i===index?'true':undefined} onClick={()=>setIndex(i)}>{entry.type==='image'?(Math.abs(i-index)<=3||visibleThumbnails.has(keyOf(entry))?<StreamingImage src={runtimeStoragePreviewUrl(entry.url,160,65)} alt="" loading="lazy"/>:<ImageIcon size={24}/>):entry.type==='video'&&Math.abs(i-index)<=1?<StreamingVideo src={entry.url} muted preload="metadata" playsInline/>:<Film size={24}/>}<small>{i+1}</small></button>)}</nav>:null}
      {items.length > 1 ? (
        <>
          <button
            type="button"
            className="media-viewer-prev"
            aria-label="Archivo anterior"
            disabled={index === 0}
            onClick={() => move(-1)}
          >
            <ChevronLeft />
          </button>
          <button
            type="button"
            className="media-viewer-next"
            aria-label="Siguiente archivo"
            disabled={index === items.length - 1}
            onClick={() => move(1)}
          >
            <ChevronRight />
          </button>
        </>
      ) : null}
      {item.type === 'pdf' ? (
        <p className="media-viewer-hint">
          Si tu navegador no muestra el PDF, usa Descargar archivo.
        </p>
      ) : (
        <p className="media-viewer-hint">
          {items.length > 1 ? '← → para cambiar de archivo · ' : ''}Esc para cerrar
        </p>
      )}
    </dialog>
  )
}
