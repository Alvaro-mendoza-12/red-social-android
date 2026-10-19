import { useEffect, useRef } from 'react'
import { X } from 'lucide-react'

export function RoomChatPhoto({ source, author, active = true }: { source: string; author: string; active?: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { if (!active && dialog.current?.open) dialog.current.close() }, [active])
  return <>
    <button type="button" className="voice-chat-photo-open" aria-label={`Ampliar foto de ${author}`} onClick={() => dialog.current?.showModal()}>
      <img className="voice-chat-photo" src={source} alt={`Foto de ${author}`} loading="lazy" />
    </button>
    <dialog ref={dialog} className="voice-photo-viewer" aria-label={`Foto de ${author}`} onClick={event => { if (event.target === event.currentTarget) dialog.current?.close() }}>
      <button type="button" className="icon-btn" aria-label="Cerrar foto" onClick={() => dialog.current?.close()}><X /></button>
      <img src={source} alt={`Foto de ${author}`} />
    </dialog>
  </>
}
