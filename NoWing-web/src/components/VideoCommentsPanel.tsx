import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { X, Send } from 'lucide-react'
import { useAuth } from '../core/auth/AuthProvider'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { supabase } from '../core/supabase'
import { subscribeUbuntuRealtime } from '../core/ubuntuRealtime'
import { textOf, friendlyError, type GenericRow } from '../core/utils'
import { SafeAvatar } from './SafeAvatar'
import { ChatAttachmentMedia, attachmentKind } from './ChatAttachmentMedia'
export function VideoCommentsPanel({ videoId, onClose, onCountChange }: { videoId: string; onClose: () => void; onCountChange?: (count: number) => void }) {
  const { user, isFallbackSession } = useAuth(), sequence = useRef(0), panel = useRef<HTMLElement>(null), countCallback = useRef(onCountChange)
  countCallback.current = onCountChange
  const [items,setItems] = useState<GenericRow[]>([]), [draft,setDraft] = useState(''), [reply,setReply] = useState<GenericRow|null>(null), [loading,setLoading] = useState(true), [busy,setBusy] = useState(false), [error,setError] = useState(''), [visible,setVisible] = useState(30)
  const load = useCallback(async (silent=false) => {
    const expected = ++sequence.current
    if(!silent)setLoading(true)
    try {
      const result = isFallbackSession ? (await fallbackApiJson<{items:GenericRow[]}>(`/v1/data/videos/${encodeURIComponent(videoId)}/comments?limit=500`,{expectedAccountId:user?.id})).items : (await supabase!.from('video_comments').select('*').eq('video_id',videoId).order('created_at',{ascending:true}).limit(500)).data || []
      if(expected===sequence.current){setItems(result);setError('');countCallback.current?.(result.length)}
    }catch(caught){if(expected===sequence.current)setError(friendlyError(caught,'No se pudieron cargar los comentarios.'))}finally{if(expected===sequence.current)setLoading(false)}
  },[videoId,user?.id,isFallbackSession])
  useEffect(()=>{setItems([]);setVisible(30);setDraft('');setReply(null);void load();const stop=subscribeUbuntuRealtime({channel:`video-comments:${videoId}`,onEvent:()=>void load(true)});return()=>{sequence.current++;stop()}},[load,videoId])
  useEffect(()=>{const previous=document.activeElement as HTMLElement|null;panel.current?.focus();return()=>{if(previous?.isConnected)previous.focus()}},[])
  async function send(event:FormEvent){
    event.preventDefault();if(!draft.trim()||busy||!user)return;setBusy(true);setError('')
    try{
      if(isFallbackSession)await fallbackApiJson(`/v1/data/videos/${encodeURIComponent(videoId)}/comments`,{method:'POST',expectedAccountId:user.id,body:JSON.stringify({content:draft.trim(),parentId:reply?.id||null,contentType:'text'})})
      else{const result=await supabase!.from('video_comments').insert({video_id:videoId,user_id:user.id,content:draft.trim(),parent_comment_id:reply?.id||null});if(result.error)throw result.error}
      setDraft('');setReply(null);await load(true)
    }catch(caught){setError(friendlyError(caught,'No se pudo enviar el comentario.'))}finally{setBusy(false)}
  }
  async function remove(id:string){
    if(busy)return;setBusy(true)
    try{if(isFallbackSession)await fallbackApiJson(`/v1/data/videos/comments/${encodeURIComponent(id)}`,{method:'DELETE',expectedAccountId:user?.id});else{const result=await supabase!.from('video_comments').delete().eq('id',id);if(result.error)throw result.error}await load(true)}catch(caught){setError(friendlyError(caught,'No se pudo eliminar el comentario.'))}finally{setBusy(false)}
  }
  return <aside ref={panel} tabIndex={-1} className="video-comments-panel" role="dialog" aria-label="Comentarios del video" onKeyDown={event=>{if(event.key==='Escape')onClose()}}><header><h2>Comentarios <small>{items.length}</small></h2><button type="button" className="icon-btn" aria-label="Cerrar comentarios" onClick={onClose}><X/></button></header><div className="video-comments-list">{loading?<p role="status">Cargando comentarios…</p>:!items.length?<p>Sé la primera persona en comentar.</p>:items.slice(-visible).map(item=>{const id=textOf(item,['id']),owner=textOf(item,['user_id','userId']),name=textOf(item,['user_name','userName'],'Usuario'),media=textOf(item,['media_url','mediaUrl']);return <article className="video-comment" key={id}><SafeAvatar url={textOf(item,['user_profile_image','avatar_url','userAvatarUrl'])} name={name}/><div><strong>{name}</strong><p>{textOf(item,['content'])}</p>{media?<ChatAttachmentMedia url={media} kind={attachmentKind(textOf(item,['content_type']),media)} title="Comentario" preserveAnimation imageLink={textOf(item,['content_type'])!=='sticker'}/>:null}<footer><button type="button" onClick={()=>setReply(item)}>Responder</button>{owner===user?.id?<button type="button" disabled={busy} onClick={()=>void remove(id)}>Eliminar</button>:null}</footer></div></article>})}{items.length>visible?<button type="button" className="ghost-btn" onClick={()=>setVisible(value=>value+30)}>Ver comentarios anteriores</button>:null}</div>{error?<p role="alert" className="error-text">{error}</p>:null}<form onSubmit={event=>void send(event)}>{reply?<div className="row-between"><small>Respondiendo a {textOf(reply,['user_name','userName'],'Usuario')}</small><button className="icon-btn" type="button" aria-label="Cancelar respuesta" onClick={()=>setReply(null)}>×</button></div>:null}<div className="row-actions"><textarea className="text-input" aria-label="Escribir comentario" placeholder="Añade un comentario…" maxLength={1000} value={draft} onChange={event=>setDraft(event.target.value)}/><button type="submit" className="icon-btn" aria-label="Enviar comentario" disabled={busy||!draft.trim()}><Send/></button></div></form></aside>
}
