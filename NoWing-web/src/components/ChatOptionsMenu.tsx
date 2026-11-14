import { useEffect, useRef, useState, type ComponentType } from 'react'
import { Archive, ArchiveRestore, ArrowLeft, Bell, BellOff, Heart, HeartOff, Info, Pencil, Pin, PinOff, Search, Settings2, SquareCheck, X, CalendarClock, Languages, ListChecks } from 'lucide-react'

type Item = { label: string; icon: ComponentType<{ size?: number; 'aria-hidden'?: boolean }>; action: () => void; disabled?: boolean }

export function ChatOptionsMenu({ pinned, muted, favorite, archived, busy, onPreference, onInfo, onSearch, onSelect, onAdvanced, onLeave, onClose, onNickname, onSchedule, onTranslation, onPoll }: {
  pinned: boolean; muted: boolean; favorite: boolean; archived: boolean; busy: boolean
  onPreference: (patch: { isPinned?: boolean; isMuted?: boolean; isFavorite?: boolean; isArchived?: boolean }) => void
  onInfo: () => void; onSearch: () => void; onSelect: () => void; onAdvanced: () => void; onLeave: () => void; onClose: () => void; onNickname?: () => void
  onSchedule?: () => void; onTranslation?: () => void; onPoll?: () => void
}) {
  const root = useRef<HTMLDivElement>(null)
  const [more, setMore] = useState(false)
  const previousMore = useRef(false)
  useEffect(() => {
    root.current?.querySelector<HTMLButtonElement>(previousMore.current && !more ? '[aria-haspopup="menu"]' : 'button')?.focus()
    previousMore.current = more
  }, [more])
  useEffect(() => {
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node) && !(event.target as Element)?.closest('[aria-controls="chat-options-menu"]')) onClose() }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [onClose])
  const primary: Item[] = [
    { label: 'Información del contacto', icon: Info, action: onInfo },
    { label: 'Buscar mensajes', icon: Search, action: onSearch },
    { label: 'Seleccionar mensajes', icon: SquareCheck, action: onSelect },
    { label: muted ? 'Activar notificaciones' : 'Silenciar notificaciones', icon: muted ? Bell : BellOff, action: () => onPreference({ isMuted: !muted }), disabled: busy },
    { label: 'Más opciones', icon: Settings2, action: () => setMore(true) },
    { label: 'Cerrar chat', icon: X, action: onLeave },
  ]
  const secondary: Item[] = [
    { label: 'Volver a opciones del chat', icon: ArrowLeft, action: () => setMore(false) },
    ...(onNickname ? [{ label: 'Cambiar apodo privado', icon: Pencil, action: onNickname }] : []),
    ...(onSchedule ? [{ label: 'Programar mensaje', icon: CalendarClock, action: onSchedule }] : []),
    ...(onTranslation ? [{ label: 'Traducción', icon: Languages, action: onTranslation }] : []),
    ...(onPoll ? [{ label: 'Crear encuesta', icon: ListChecks, action: onPoll }] : []),
    { label: pinned ? 'Desfijar chat' : 'Fijar chat', icon: pinned ? PinOff : Pin, action: () => onPreference({ isPinned: !pinned }), disabled: busy },
    { label: favorite ? 'Quitar de favoritos' : 'Añadir a favoritos', icon: favorite ? HeartOff : Heart, action: () => onPreference({ isFavorite: !favorite }), disabled: busy },
    { label: archived ? 'Desarchivar chat' : 'Archivar chat', icon: archived ? ArchiveRestore : Archive, action: () => onPreference({ isArchived: !archived }), disabled: busy },
    { label: 'Herramientas del chat', icon: Settings2, action: onAdvanced },
  ]
  const items = more ? secondary : primary
  return <div ref={root} id="chat-options-menu" className="chat-options-menu" role="menu" aria-label={more ? 'Más opciones del chat' : 'Opciones del chat'} onKeyDown={event => {
    if (event.key === 'ArrowLeft' && more) { event.preventDefault(); setMore(false) }
    if (event.key === 'ArrowRight' && document.activeElement?.getAttribute('aria-haspopup') === 'menu') { event.preventDefault(); setMore(true) }
    if (event.key === 'Tab') onClose()
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopPropagation()
      if (more) setMore(false)
      else { onClose(); document.querySelector<HTMLButtonElement>('[aria-controls="chat-options-menu"]')?.focus() }
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const buttons = Array.from(root.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
    const current = buttons.indexOf(document.activeElement as HTMLButtonElement)
    const index = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
    buttons[index]?.focus()
  }}>{items.map(({ label, icon: Icon, action, disabled }) => <button key={label} type="button" role="menuitem" disabled={disabled} aria-haspopup={label === 'Más opciones' ? 'menu' : undefined} onClick={() => {
    action()
    if (label !== 'Más opciones' && label !== 'Volver a opciones del chat') {
      document.querySelector<HTMLButtonElement>('[aria-controls="chat-options-menu"]')?.focus()
      onClose()
    }
  }}><Icon size={18} aria-hidden /><span>{label}</span></button>)}</div>
}
