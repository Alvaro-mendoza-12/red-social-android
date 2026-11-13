import { useEffect, useRef, useState } from 'react'
import { Search, UserRoundPlus, X } from 'lucide-react'
import { SafeAvatar } from '../../components/SafeAvatar'
import { loadFallbackMessagesInbox, searchFallbackProfiles } from '../../core/fallbackData'
import { profileAvatarSource } from '../../core/profileMediaFields'
import { textOf } from '../../core/utils'

type Contact = { id: string; name: string; avatar: string }

export function CallInviteDialog({ excludedIds, onInvite, onClose }: {
  excludedIds: string[]; onInvite: (contact: Contact) => Promise<string | void>; onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const busy = useRef(false)
  const [query, setQuery] = useState('')
  const [retry, setRetry] = useState(0)
  const [result, setResult] = useState<{ term: string; items: Contact[]; failed: boolean } | null>(null)
  const [sending, setSending] = useState('')
  const [invited, setInvited] = useState<Record<string, number>>({})
  const [clock, setClock] = useState(Date.now)
  const [status, setStatus] = useState('')
  const term = query.trim()
  useEffect(() => {
    const timer = window.setInterval(() => setClock(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  useEffect(() => {
    const node = dialog.current, previous = document.activeElement as HTMLElement | null
    node?.showModal()
    return () => { node?.close(); if (previous?.isConnected) previous.focus() }
  }, [])
  useEffect(() => {
    let active = true
    const timer = window.setTimeout(async () => {
      try {
        const items = term.length >= 2
          ? (await searchFallbackProfiles(term, 30)).items.map(row => ({ id: textOf(row, ['supabase_user_id', 'id', 'user_id']), name: textOf(row, ['display_name', 'full_name', 'username'], 'Perfil'), avatar: profileAvatarSource(row) }))
          : (await loadFallbackMessagesInbox('chats')).items.filter(row => row.kind === 'chat' && row.profileId).map(row => ({ id: row.profileId!, name: row.title, avatar: row.avatarUrl }))
        const unique = new Map(items.filter(item => item.id && (term.length !== 1 || item.name.toLocaleLowerCase().includes(term.toLocaleLowerCase()))).map(item => [item.id, item]))
        if (active) setResult({ term, items: [...unique.values()], failed: false })
      } catch { if (active) setResult({ term, items: [], failed: true }) }
    }, term.length >= 2 ? 220 : 0)
    return () => { active = false; clearTimeout(timer) }
  }, [term, retry])
  const visible = result?.term === term ? result : null
  const items = visible?.items.filter(item => !excludedIds.includes(item.id)) ?? []
  const invite = async (contact: Contact) => {
    if (busy.current) return
    busy.current = true; setSending(contact.id); setStatus('')
    try { const expiresAt = await onInvite(contact); const until = typeof expiresAt === 'string' ? Date.parse(expiresAt) : Date.now() + 45000; setInvited(current => ({ ...current, [contact.id]: until })); setStatus(`Invitación enviada a ${contact.name}.`) }
    catch (error) { setStatus(error instanceof Error ? error.message : 'No se pudo enviar la invitación. Inténtalo de nuevo.') }
    finally { busy.current = false; setSending('') }
  }
  return <dialog ref={dialog} className="call-invite-dialog" aria-labelledby="call-invite-title" onCancel={event => { event.preventDefault(); onClose() }}>
    <header><div><h2 id="call-invite-title">Invitar a la llamada</h2><p>La conversación continúa mientras invitas.</p></div><button type="button" className="icon-btn" aria-label="Cerrar invitaciones" onClick={onClose}><X aria-hidden="true"/></button></header>
    <label className="call-contact-search"><Search size={18} aria-hidden="true"/><input type="search" autoFocus value={query} onChange={event => setQuery(event.target.value)} placeholder="Nombre o usuario" aria-label="Buscar personas para invitar"/></label>
    <p className="call-invite-hint">{term ? 'Resultados' : 'Conversaciones recientes'} · Hasta 8 personas</p>
    {!visible ? <p role="status">Buscando contactos…</p> : visible.failed ? <p role="status">No se pudieron cargar los contactos. <button type="button" className="ghost-btn" onClick={() => setRetry(value => value + 1)}>Reintentar</button></p> : !items.length ? <p>No hay contactos disponibles. Prueba con otro nombre.</p> :
      <ul className="call-contact-list">{items.map(contact => <li key={contact.id}><SafeAvatar size="sm" name={contact.name} url={contact.avatar}/><strong>{contact.name}</strong><button type="button" className="ghost-btn" disabled={!!sending || invited[contact.id] > clock} onClick={() => void invite(contact)} aria-label={`Invitar a ${contact.name}`}><UserRoundPlus size={17} aria-hidden="true"/>{sending === contact.id ? 'Enviando…' : invited[contact.id] > clock ? `Invitado · ${Math.ceil((invited[contact.id] - clock) / 1000)} s` : invited[contact.id] ? 'Reinvitar' : 'Invitar'}</button></li>)}</ul>}
    <p role="status" className="call-invite-status">{status}</p>
  </dialog>
}
