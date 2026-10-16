import { useEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import './mediaDeviceDialog.css'

export function MediaDeviceDialog({ title, label, onClose, children }: { title: string; label: string; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const node = dialog.current, previous = document.activeElement as HTMLElement | null
    node?.showModal()
    return () => { node?.close(); if (previous?.isConnected) previous.focus() }
  }, [])
  return <dialog ref={dialog} className="media-device-dialog" aria-label={label} onCancel={event => { event.preventDefault(); onClose() }}>
    <header><h3>{title}</h3><button type="button" className="icon-btn-sm" aria-label="Cerrar ajustes de audio y video" onClick={onClose}><X size={19} /></button></header>
    {children}
  </dialog>
}
