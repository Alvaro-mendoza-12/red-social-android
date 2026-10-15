import { lazy, Suspense, useEffect, useState } from 'react'
import { previewDocument, type DocumentContent } from '../core/documentPreview'
const PdfDocument = lazy(() => import('./PdfDocument'))
export function DocumentPreview({ url, title, pdf = false }: { url: string; title: string; pdf?: boolean }) {
  const [content, setContent] = useState<DocumentContent | null>(null), [error, setError] = useState(''), [sheet, setSheet] = useState(0)
  useEffect(() => {
    if (pdf) return
    let active = true; const controller = new AbortController(); setContent(null); setError(''); setSheet(0)
    void fetch(url, { signal: controller.signal, credentials: 'omit' }).then(async response => {
      if (!response.ok || !response.body || Number(response.headers.get('Content-Length')) > 64 * 1024 * 1024) throw new Error('Documento demasiado grande.')
      const reader = response.body.getReader(), parts: Uint8Array[] = []; let bytes = 0
      for (;;) { const next = await reader.read(); if (next.done) break; bytes += next.value.length; if (bytes > 64 * 1024 * 1024) { await reader.cancel(); throw new Error('Documento demasiado grande.') } parts.push(next.value) }
      const data = new Uint8Array(bytes); let offset = 0; for (const part of parts) { data.set(part,offset); offset += part.length } return data
    }).then(data => {
      const result = previewDocument(data, title); if (active) setContent(result)
    }).catch(() => { if (active) setError('No se pudo mostrar este documento. Puedes descargar el original.') })
    return () => { active = false; controller.abort() }
  }, [url, title, pdf])
  if (pdf) return <Suspense fallback={<p role="status">Abriendo PDF…</p>}><PdfDocument url={url} /></Suspense>
  return <section className="document-reader" aria-label="Vista previa del documento">
    {error ? <p role="status">{error}</p> : !content ? <p role="status">Abriendo documento…</p> : content.type === 'text' ? <pre>{content.text}</pre> : content.type === 'paragraphs' ? <article className="document-paper">{content.paragraphs.map((paragraph,index) => <p key={index}>{paragraph}</p>)}</article> : content.type === 'sheet' ? <><nav>{content.sheets.map((value,index) => <button type="button" key={index} aria-pressed={sheet === index} onClick={() => setSheet(index)}>{value.name}</button>)}</nav><div className="document-sheet"><table><tbody>{content.sheets[sheet]?.rows.map((row,index) => <tr key={index}><th>{index+1}</th>{row.map((cell,column) => <td key={column}>{cell}</td>)}</tr>)}</tbody></table></div></> : content.type === 'archive' ? <article className="document-paper"><h3>Archivos de la carpeta</h3><ul>{content.entries.map(name => <li key={name}>{name}</li>)}</ul></article> : <p>Este formato se puede descargar. La vista previa está disponible para PDF, documentos de texto, Word, Excel y PowerPoint modernos.</p>}
  </section>
}
