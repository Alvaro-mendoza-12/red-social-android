import { useEffect, useRef, useState } from 'react'
import { loadFallbackMessagesInbox, sendFallbackChatMessage, sendFallbackRoomMessage, type FallbackInboxItem } from '../core/fallbackData'
import { dedupeInboxConversations } from '../features/messages/conversationInboxSupport'
import { friendlyError } from '../core/utils'
import { SafeAvatar } from './SafeAvatar'
import { PublicationDialog } from './PublicationDialog'

export type ForwardableMessage = { id: string; senderId: string; content: string; contentType: string; mediaUrl: string | null; deletedForAll: boolean; status?: string }
export function ForwardMessagesDialog({ messages, onClose, mode = 'forward' }: { messages: ForwardableMessage[]; onClose: () => void; mode?: 'forward' | 'share' }) {
  const eligible = messages.filter(item => !item.deletedForAll && !item.contentType.startsWith('view_once_') && !['system_membership', 'poll', 'question', 'raffle', 'birthday_card'].includes(item.contentType) && !['pending', 'failed', 'sending'].includes(item.status || ''))
  const [items, setItems] = useState<FallbackInboxItem[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [query, setQuery] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const completed = useRef(new Set<string>())
  const key = (item: FallbackInboxItem) => `${item.kind}/${item.id}`
  useEffect(() => {
    let active = true
    void Promise.all(['chats', 'groups', 'communities'].map(loadFallbackMessagesInbox)).then(results => {
      if (active) setItems(dedupeInboxConversations(results.flatMap(value => value.items).filter(item => ['chat', 'group', 'community'].includes(item.kind) && item.isMember !== false)))
    }).catch(caught => { if (active) setError(friendlyError(caught, 'No se pudieron cargar tus conversaciones.')) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])
  async function send() {
    if (busy || !selected.size || !eligible.length) return
    setBusy(true); setError('')
    try {
      for (const target of items.filter(item => selected.has(key(item)))) {
        for (const message of eligible) {
          const delivery = `${key(target)}/${message.id}`
          if (completed.current.has(delivery)) continue
          const input = { content: message.content, contentType: message.contentType, mediaUrl: message.mediaUrl, ...(mode === 'forward' ? { isForwarded: true, forwardedFromMessageId: message.id, forwardedFromUserId: message.senderId } : {}) }
          if (target.kind === 'chat') await sendFallbackChatMessage(target.id, input)
          else if (target.kind === 'group' || target.kind === 'community') await sendFallbackRoomMessage(target.kind, target.id, input)
          completed.current.add(delivery)
        }
      }
      onClose()
    } catch (caught) { setError(friendlyError(caught, 'No se completaron todos los envíos. Al reintentar se enviarán los restantes.')) }
    finally { setBusy(false) }
  }
  return <PublicationDialog title={mode === 'share' ? 'Enviar invitación' : `Reenviar ${eligible.length} mensaje${eligible.length === 1 ? '' : 's'}`} busy={busy} onClose={onClose}>
      {eligible.length < messages.length ? <p className="muted-line">Los contenidos de una sola vista, eliminados, pendientes y tarjetas interactivas conservan sus restricciones y no se reenvían.</p> : null}
      <input className="text-input" aria-label="Buscar destinatarios" placeholder="Buscar chats, grupos o comunidades" value={query} disabled={busy} onChange={event => setQuery(event.target.value)} />
      {loading ? <p role="status">Cargando conversaciones…</p> : null}{error ? <p role="alert">{error}</p> : null}
      <div className="share-video-recipients">{items.filter(item => item.title.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())).map(item => <button type="button" className="share-recipient" key={key(item)} aria-pressed={selected.has(key(item))} disabled={busy} onClick={() => setSelected(current => {
        const next = new Set(current); if (next.has(key(item))) next.delete(key(item)); else next.add(key(item)); return next
      })}><SafeAvatar url={item.avatarUrl} name={item.title} /><span><strong>{item.title}</strong><small>{item.kind === 'chat' ? 'Chat' : item.kind === 'group' ? 'Grupo' : 'Comunidad'}</small></span><b aria-hidden="true">{selected.has(key(item)) ? '✓' : ''}</b></button>)}</div>
      {!loading && !items.length ? <p>No hay conversaciones disponibles.</p> : null}
      <button type="button" className="primary-btn" disabled={busy || !selected.size || !eligible.length} onClick={() => void send()}>{busy ? 'Preparando envíos…' : `${mode === 'share' ? 'Enviar' : 'Reenviar'} a ${selected.size}`}</button>
  </PublicationDialog>
}
