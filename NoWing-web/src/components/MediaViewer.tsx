import { useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Download, Minus, Plus, RotateCcw, X } from 'lucide-react'
import { StreamingImage, StreamingVideo } from './StreamingMedia'
import { onStorageReadInvalidated } from '../core/media/storageReadSession'

export type ViewerItem = {
  url: string
  type: 'image' | 'video' | 'pdf'
  title?: string
  downloadUrl?: string
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
  const close = useRef(onClose)
  close.current = onClose
  const [index, setIndex] = useState(Math.max(0, Math.min(initialIndex, items.length - 1)))
  const [zoom, setZoom] = useState(1)
  const [failed, setFailed] = useState(false)
  const item = items[index]
  useEffect(() => {
    setZoom(1)
    setFailed(false)
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
  const move = (delta: number) => setIndex((value) => (value + delta + items.length) % items.length)
  return (
    <dialog
      ref={dialog}
      className="media-viewer"
      aria-label={item.type === 'pdf' ? 'Ver documento' : 'Visor multimedia'}
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
          {item.downloadUrl ? (
            <a
              className="icon-btn"
              href={item.downloadUrl}
              download={item.title || 'archivo'}
              aria-label="Descargar archivo"
              rel="noopener noreferrer"
            >
              <Download />
            </a>
          ) : null}
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
        ) : item.type === 'pdf' ? (
          <iframe
            key={item.url}
            src={item.url}
            title={item.title || 'Documento PDF'}
            sandbox="allow-scripts allow-downloads"
          />
        ) : item.type === 'video' ? (
          <StreamingVideo
            key={item.url}
            src={item.url}
            controls
            autoPlay
            playsInline
            preload="metadata"
            onError={() => setFailed(true)}
          />
        ) : (
          <StreamingImage
            key={item.url}
            src={item.url}
            alt={item.title || 'Imagen ampliada'}
            draggable={false}
            onError={() => setFailed(true)}
            style={{
              width: zoom > 1 ? `${zoom * 100}%` : undefined,
              maxWidth: zoom > 1 ? 'none' : undefined,
              maxHeight: zoom > 1 ? 'none' : undefined,
            }}
          />
        )}
      </div>
      {items.length > 1 ? (
        <>
          <button
            type="button"
            className="media-viewer-prev"
            aria-label="Archivo anterior"
            onClick={() => move(-1)}
          >
            <ChevronLeft />
          </button>
          <button
            type="button"
            className="media-viewer-next"
            aria-label="Siguiente archivo"
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
