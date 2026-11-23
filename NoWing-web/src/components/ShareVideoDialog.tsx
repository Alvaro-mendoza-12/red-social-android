import { useEffect, useState } from 'react'
import { getStorageReadIdentity, requireStorageReadIdentity } from '../core/media/storageReadSession'
import { useShareSession } from './useShareSession'
import { loadFallbackMessagesInbox, sendFallbackChatMessage, sendFallbackRoomMessage, type FallbackInboxItem } from '../core/fallbackData'
import { friendlyError } from '../core/utils'
import { SafeAvatar } from './SafeAvatar'
import { PublicationDialog } from './PublicationDialog'
import { dedupeInboxConversations } from '../features/messages/conversationInboxSupport'
import { buildVideoShareUrl } from '../core/socialLinks'
export function ShareVideoDialog({videoId,title,onClose}:{videoId:string;title:string;onClose:()=>void}){
  const [items,setItems]=useState<FallbackInboxItem[]>([]),[selected,setSelected]=useState<Set<string>>(new Set()),[query,setQuery]=useState(''),[error,setError]=useState(''),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[info,setInfo]=useState('')
  const { owner, sessionActive } = useShareSession(onClose)
  const url=buildVideoShareUrl(videoId)
  const key=(item:FallbackInboxItem)=>`${item.kind}/${item.id}`
  useEffect(() => {
    if (!sessionActive) return
    let active = true
    const canPublish = () => active && owner === getStorageReadIdentity()
    void Promise.all(['chats', 'groups', 'communities'].map(tab => loadFallbackMessagesInbox(tab))).then(values => {
      if (canPublish()) setItems(dedupeInboxConversations(values.flatMap(value => value.items)
        .filter(item => ['chat', 'group', 'community'].includes(item.kind) && item.isMember !== false)))
    }).catch(caught => {
      if (canPublish()) setError(friendlyError(caught, 'No se pudieron cargar tus conversaciones.'))
    }).finally(() => {
      if (canPublish()) setLoading(false)
    })
    return () => { active = false }
  }, [owner, sessionActive])
  async function send(){
    if (!sessionActive || busy || !selected.size) return
    setBusy(true);setError('');const sent=new Set<string>()
    try{
      for (const item of items.filter(item => selected.has(key(item)))) {
        requireStorageReadIdentity(owner)
        const payload={content:`¡Mira este video!\n${url}\n\n${title}`,contentType:'text'}
        if(item.kind==='chat')await sendFallbackChatMessage(item.id,payload)
        else if(item.kind==='group'||item.kind==='community')await sendFallbackRoomMessage(item.kind,item.id,payload)
        sent.add(key(item))
      }
      requireStorageReadIdentity(owner)
      onClose()
    } catch (caught) {
      if (owner !== getStorageReadIdentity()) return
      setSelected(current => new Set([...current].filter(id => !sent.has(id))))
      setError(friendlyError(caught, 'No se pudieron preparar todos los envíos. Puedes reintentar los restantes.'))
    } finally {
      if (owner === getStorageReadIdentity()) setBusy(false)
    }
  }
  if (!sessionActive) return null
  return <PublicationDialog title="Compartir video" busy={busy} onClose={onClose}>
    <input aria-label="Buscar destinatarios" disabled={busy} className="text-input" placeholder="Buscar chats, grupos o comunidades" value={query} onChange={event=>setQuery(event.target.value)} />
    {loading?<p>Buscando conversaciones…</p>:null}{error?<p role="alert">{error}</p>:null}{info?<p role="status">{info}</p>:null}
    <div className="share-video-recipients">{items.filter(item=>item.title.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().includes(query.trim().normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase())).map(item=><button type="button" className="share-recipient" data-conversation-id={item.id} data-conversation-kind={item.kind} key={key(item)} aria-pressed={selected.has(key(item))} disabled={busy} onClick={()=>setSelected(current=>{const next=new Set(current);if(next.has(key(item)))next.delete(key(item));else next.add(key(item));return next})}>
      <SafeAvatar url={item.avatarUrl} name={item.title} mediaType={item.avatarMediaType}/><span><strong>{item.title}</strong><small>{item.kind==='group'?'Grupo':item.kind==='community'?'Comunidad':'Chat'}</small></span><b aria-hidden="true">{selected.has(key(item))?'✓':''}</b>
    </button>)}</div>
    {!loading&&!items.length?<p>No tienes conversaciones disponibles para compartir.</p>:null}
    <footer className="row-between"><button type="button" className="ghost-btn" onClick={()=>void navigator.clipboard.writeText(url).then(()=>setInfo('Enlace copiado.')).catch(()=>setError('No se pudo copiar el enlace.'))}>Copiar enlace</button><button className="primary-btn" type="button" disabled={busy||!selected.size} onClick={()=>void send()}>{busy?'Preparando…':`Enviar (${selected.size})`}</button></footer>
  </PublicationDialog>
}
