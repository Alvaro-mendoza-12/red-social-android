import { useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Film, Minus, Plus, RotateCcw, X } from 'lucide-react'
import { StreamingImage, StreamingVideo } from './StreamingMedia'
import { onStorageReadInvalidated } from '../core/media/storageReadSession'
import { DocumentPreview } from './DocumentPreview'
import { MediaDownloadButton } from './MediaDownloadButton'

export type ViewerItem = {
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
  const [index, setIndex] = useState(Math.max(0, Math.min(initialIndex, items.length - 1)))
  const [zoomState, setZoomState] = useState({index:-1,value:1})
  const [failedIndex, setFailedIndex] = useState<number|null>(null)
  const zoom = zoomState.index === index ? zoomState.value : 1, failed = failedIndex === index
  const setZoom = (value: number | ((current: number) => number)) => setZoomState({index,value:typeof value === 'function'?value(zoom):value})
  const item = items[index]
  useEffect(() => {
    strip.current?.querySelector('[aria-current="true"]')?.scrollIntoView?.({block:'nearest',inline:'center'})
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
  if (!item) return null
  const move = (delta: number) => setIndex((value) => Math.max(0, Math.min(items.length - 1, value + delta)))
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
          {item.type === 'image' ? (
            <>
              <button
                type="button"
                className="icon-btn"
                aria-label="Alejar"
                disabled={zoom <= 1}
                onClick={() => setZoom((value) => Math.max(1, value - 0.5))}
              >
                <Minus />
              </button>
              <button
                type="button"
                className="icon-btn"
                aria-label="Ampliar"
                disabled={zoom >= 3}
                onClick={() => setZoom((value) => Math.min(3, value + 0.5))}
              >
                <Plus />
              </button>
              <button
                type="button"
                className="icon-btn"
                aria-label="Restablecer tamaño"
                onClick={() => setZoom(1)}
              >
                <RotateCcw size={19} />
              </button>
            </>
          ) : null}
          <MediaDownloadButton url={item.downloadUrl || item.url} name={item.documentName || item.title || ''} iconOnly />
          <button type="button" className="icon-btn" aria-label="Cerrar visor" onClick={onClose}>
            <X />
          </button>
        </div>
      </header>
      <div
        className={`media-viewer-stage${zoom > 1 ? ' zoomed' : ''}`}
        onClick={(event) => {
          if (event.target === event.currentTarget) onClose()
        }}
      >
        {failed ? (
          <p role="status">
            No se pudo mostrar este archivo.
            {item.downloadUrl ? ' Puedes descargarlo desde el botón de arriba.' : ''}
          </p>
        ) : item.type === 'pdf' || item.type === 'document' ? (
          <DocumentPreview key={item.url} url={item.url} title={item.documentName || item.title || 'Documento'} pdf={item.type === 'pdf'} />
        ) : item.type === 'video' ? (
          <StreamingVideo
            key={item.url}
            src={item.url}
            controls
            autoPlay
            playsInline
            preload="metadata"
            onError={() => setFailedIndex(index)}
          />
        ) : (
          <StreamingImage
            key={item.url}
            src={item.url}
            alt={item.title || 'Imagen ampliada'}
            draggable={false}
            onError={() => setFailedIndex(index)}
            style={{
              width: zoom > 1 ? `${zoom * 100}%` : undefined,
              maxWidth: zoom > 1 ? 'none' : undefined,
              maxHeight: zoom > 1 ? 'none' : undefined,
            }}
          />
        )}
      </div>
      {items.length>1?<nav ref={strip} className="media-viewer-filmstrip" aria-label="Archivos de la conversación">{items.map((entry,i)=><button type="button" key={`${entry.url}-${i}`} aria-label={`Ver archivo ${i+1}`} aria-current={i===index?'true':undefined} onClick={()=>setIndex(i)}>{entry.type==='image'?<StreamingImage src={entry.url} alt="" loading="lazy"/>:entry.type==='video'&&Math.abs(i-index)<=4?<StreamingVideo src={entry.url} muted preload="metadata" playsInline/>:<Film size={24}/>}<small>{i+1}</small></button>)}</nav>:null}
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
