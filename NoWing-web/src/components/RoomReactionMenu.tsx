import { useEffect, useRef, useState } from 'react'
import { Smile } from 'lucide-react'

export function RoomReactionMenu({ onReaction }: { onReaction: (emoji: string) => void }) {
  const [open, setOpen] = useState(false), root = useRef<HTMLDivElement>(null), trigger = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false) }
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); trigger.current?.focus() } }
    document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape)
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape) }
  }, [open])
  return <div className="room-reaction-menu" ref={root}>
    <button ref={trigger} type="button" className="voice-control-btn" title="Reacciones" aria-label="Reacciones de la sala" aria-expanded={open} onClick={() => setOpen(value => !value)}><Smile size={18} />Reacciones</button>
    {open ? <div className="room-reaction-picker" role="group" aria-label="Elige una reacción">{['❤️', '👏', '🔥', '🎉', '😂', '😮', '🙌', '💯'].map(emoji => <button key={emoji} type="button" aria-label={`Reaccionar ${emoji}`} onClick={() => { onReaction(emoji); setOpen(false); trigger.current?.focus() }}>{emoji}</button>)}</div> : null}
  </div>
}
