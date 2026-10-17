import { useEffect, useRef, useState } from 'react'
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
GlobalWorkerOptions.workerSrc = workerUrl

export default function PdfDocument({ url }: { url: string }) {
  return <PdfDocumentContent key={url} url={url} />
}
function PdfDocumentContent({ url }: { url: string }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [document, setDocument] = useState<PDFDocumentProxy | null>(null), [page, setPage] = useState(1), [scale, setScale] = useState(1), [error, setError] = useState('')
  useEffect(() => {
    let active = true
    const task = getDocument({ url, stopAtErrors: true, maxImageSize: 24_000_000, withCredentials: false })
    void task.promise.then(value => { if (active) setDocument(value) }).catch(() => { if (active) setError('No se pudo abrir el PDF. Puedes descargar el archivo original.') })
    return () => { active = false; void task.destroy() }
  }, [url])
  useEffect(() => {
    if (!document || !canvas.current) return
    let active = true; let render: ReturnType<Awaited<ReturnType<PDFDocumentProxy['getPage']>>['render']> | undefined
    void document.getPage(page).then(value => {
      if (!active || !canvas.current) return
      const natural = value.getViewport({ scale: 1 }), width = canvas.current.parentElement!.clientWidth
      const viewport = value.getViewport({ scale: Math.min(2, (width - 24) / natural.width) * scale })
      const pixelRatio = Math.min(window.devicePixelRatio || 1, 2), node = canvas.current
      node.width = Math.ceil(viewport.width * pixelRatio); node.height = Math.ceil(viewport.height * pixelRatio)
      node.style.width = `${viewport.width}px`; node.style.height = `${viewport.height}px`
      render = value.render({ canvas: node, viewport, transform: pixelRatio !== 1 ? [pixelRatio, 0, 0, pixelRatio, 0, 0] : undefined })
      return render.promise
    }).catch(error => { if (active && error?.name !== 'RenderingCancelledException') setError('No se pudo mostrar esta página.') })
    return () => { active = false; render?.cancel() }
  }, [document, page, scale])
  return <section className="document-reader pdf-document" aria-label="Documento PDF">
    <nav><button type="button" disabled={!document || page <= 1} onClick={() => setPage(value => value - 1)}>Anterior</button><span>Página {page} de {document?.numPages || '…'}</span><button type="button" disabled={!document || page >= document.numPages} onClick={() => setPage(value => value + 1)}>Siguiente</button><button type="button" aria-label="Alejar documento" disabled={scale <= .5} onClick={() => setScale(value => Math.max(.5, value - .25))}>−</button><button type="button" aria-label="Ampliar documento" disabled={scale >= 2} onClick={() => setScale(value => Math.min(2, value + .25))}>+</button></nav>
    {error ? <p role="status">{error}</p> : !document ? <p role="status">Abriendo documento…</p> : <div className="pdf-canvas-scroll"><canvas ref={canvas} aria-label={`Página ${page}`} /></div>}
  </section>
}
