import { useEffect, useState } from 'react'
import { loadFallbackMessagesInbox, sendFallbackChatMessage, sendFallbackRoomMessage, type FallbackInboxItem } from '../core/fallbackData'
import { friendlyError } from '../core/utils'
import { SafeAvatar } from './SafeAvatar'
import { dedupeInboxConversations } from '../features/messages/conversationInboxSupport'
export function ShareVideoDialog({videoId,title,onClose}:{videoId:string;title:string;onClose:()=>void}){
  const [items,setItems]=useState<FallbackInboxItem[]>([]),[selected,setSelected]=useState<Set<string>>(new Set()),[query,setQuery]=useState(''),[error,setError]=useState(''),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[info,setInfo]=useState('')
  const url=`https://red-social-android.vercel.app/app/reels/${encodeURIComponent(videoId)}`
  const key=(item:FallbackInboxItem)=>`${item.kind}/${item.id}`
  useEffect(()=>{let active=true;void Promise.all(['chats','groups','communities'].map(tab=>loadFallbackMessagesInbox(tab))).then(values=>{if(active)setItems(dedupeInboxConversations(values.flatMap(value=>value.items).filter(item=>['chat','group','community'].includes(item.kind)&&item.isMember!==false)))}).catch(e=>{if(active)setError(friendlyError(e,'No se pudieron cargar tus conversaciones.'))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[])
  async function send(){
    if(busy||!selected.size)return
    setBusy(true);setError('');const sent=new Set<string>()
    try{
      for(const item of items.filter(item=>selected.has(key(item)))){
        const payload={content:`¡Mira este video!\n${url}\n\n${title}`,contentType:'text'}
        if(item.kind==='chat')await sendFallbackChatMessage(item.id,payload)
        else if(item.kind==='group'||item.kind==='community')await sendFallbackRoomMessage(item.kind,item.id,payload)
        sent.add(key(item))
      }
      onClose()
    }catch(e){setSelected(current=>new Set([...current].filter(id=>!sent.has(id))));setError(friendlyError(e,'No se pudieron preparar todos los envíos. Puedes reintentar los restantes.'))}finally{setBusy(false)}
  }
  return <div className="app-dialog-backdrop" role="presentation" onMouseDown={()=>{if(!busy)onClose()}}><section className="surface-card collection-info" role="dialog" aria-modal="true" aria-label="Compartir video" onMouseDown={event=>event.stopPropagation()}>
    <header className="row-between"><h2>Compartir video</h2><button className="icon-btn" type="button" onClick={onClose} disabled={busy} aria-label="Cerrar">×</button></header>
    <input className="text-input" placeholder="Buscar chats, grupos o comunidades" value={query} onChange={event=>setQuery(event.target.value)} />
    {loading?<p>Buscando conversaciones…</p>:null}{error?<p role="alert">{error}</p>:null}{info?<p role="status">{info}</p>:null}
    <div className="share-video-recipients">{items.filter(item=>item.title.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().includes(query.trim().normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase())).map(item=><button type="button" className="share-recipient" data-conversation-id={item.id} data-conversation-kind={item.kind} key={key(item)} aria-pressed={selected.has(key(item))} disabled={busy} onClick={()=>setSelected(current=>{const next=new Set(current);if(next.has(key(item)))next.delete(key(item));else next.add(key(item));return next})}>
      <SafeAvatar url={item.avatarUrl} name={item.title} mediaType={item.avatarMediaType}/><span><strong>{item.title}</strong><small>{item.kind==='group'?'Grupo':item.kind==='community'?'Comunidad':'Chat'}</small></span><b aria-hidden="true">{selected.has(key(item))?'✓':''}</b>
    </button>)}</div>
    {!loading&&!items.length?<p>No tienes conversaciones disponibles para compartir.</p>:null}
    <footer className="row-between"><button type="button" className="ghost-btn" onClick={()=>void navigator.clipboard.writeText(url).then(()=>setInfo('Enlace copiado.')).catch(()=>setError('No se pudo copiar el enlace.'))}>Copiar enlace</button><button className="primary-btn" type="button" disabled={busy||!selected.size} onClick={()=>void send()}>{busy?'Preparando…':`Enviar (${selected.size})`}</button></footer>
  </section></div>
}
