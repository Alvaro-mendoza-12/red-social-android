import { useEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import './publicationDialog.css'

/** Native modal keeps focus and page interactions inside the publication flow. */
export function PublicationDialog({ title, busy = false, onClose, className = '', children }: {
  title: string; busy?: boolean; onClose: () => void; className?: string; children: ReactNode
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const node = dialog.current, previous = document.activeElement as HTMLElement | null
    node?.showModal()
    return () => { node?.close(); if (previous?.isConnected) previous.focus() }
  }, [])
  return <dialog ref={dialog} className={`publication-dialog ${className}`} aria-label={title}
    onCancel={event => { event.preventDefault(); if (!busy) onClose() }}>
    <header className="publication-dialog-header">
      <h3>{title}</h3>
      <button className="icon-btn" type="button" disabled={busy} onClick={onClose} aria-label={`Cerrar ${title.toLowerCase()}`}><X aria-hidden="true" /></button>
    </header>
    <div className="publication-dialog-body" aria-busy={busy}>{children}</div>
  </dialog>
}
