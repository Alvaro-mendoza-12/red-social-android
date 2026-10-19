import { useCallback, useEffect, useRef, useState } from 'react'
import { useAuth } from '../core/auth/AuthProvider'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { shouldUseUbuntuBackend } from '../core/ubuntuBackend'
import { supabase } from '../core/supabase'
import { subscribeUbuntuRealtime } from '../core/ubuntuRealtime'
import { friendlyError, textOf, type GenericRow } from '../core/utils'
import { CommentComposer, type CommentDraft } from './CommentComposer'
import { CommentThreadList } from './CommentThreadList'

export function PublicComments({ kind, targetId, onCountChange }: { kind:'video'|'post'; targetId:string; onCountChange?:(count:number)=>void }) {
  const {user,isFallbackSession}=useAuth(), ubuntu=shouldUseUbuntuBackend(isFallbackSession)
  const [items,setItems]=useState<GenericRow[]>([]), [reply,setReply]=useState<GenericRow|null>(null), [loading,setLoading]=useState(true), [busy,setBusy]=useState(false), [error,setError]=useState('')
  const scope=`${kind}:${targetId}:${user?.id}`,scopeRef=useRef(scope);scopeRef.current=scope
  const sequence=useRef(0), owner=useRef(user?.id), callback=useRef(onCountChange), mounted=useRef(true);owner.current=user?.id;callback.current=onCountChange
  const base=kind==='video'?'/v1/data/videos':'/v1/data/feed',table=kind==='video'?'video_comments':'post_comments'
  const path=kind==='video'?`${base}/${encodeURIComponent(targetId)}/comments?limit=500`:`${base}/posts/${encodeURIComponent(targetId)}`
  const load=useCallback(async(silent=false)=>{
    const expected=++sequence.current;if(!silent)setLoading(true)
    try{
      let result:GenericRow[]
      if(ubuntu){const response=await fallbackApiJson<{items?:GenericRow[];comments?:GenericRow[]}>(path,{expectedAccountId:user?.id});result=response.items||response.comments||[]}
      else{const response=await supabase!.from(table).select('*').eq(kind==='video'?'video_id':'post_id',targetId).order('created_at',{ascending:true}).limit(500);if(response.error)throw response.error;result=response.data||[]}
      if(expected===sequence.current){setItems(result);setError('');callback.current?.(result.length)}
    }catch(caught){if(expected===sequence.current)setError(friendlyError(caught,'No se pudieron cargar los comentarios.'))}finally{if(expected===sequence.current)setLoading(false)}
  },[path,ubuntu,user?.id,table,kind,targetId])
  useEffect(()=>{const activeSequence=sequence;mounted.current=true;setItems([]);setReply(null);void load();const stop=subscribeUbuntuRealtime({channel:kind==='video'?`video-comments:${targetId}`:`post-comments:${targetId}`,onEvent:()=>void load(true)});const timer=setInterval(()=>{if(document.visibilityState==='visible')void load(true)},15000);return()=>{mounted.current=false;activeSequence.current++;stop();clearInterval(timer)}},[load,kind,targetId])
  async function mutate(task:()=>Promise<unknown>){
    if(busy)return false;const account=user?.id, expectedScope=scope;setBusy(true);setError('')
    try{await task();if(owner.current!==account||!mounted.current||scopeRef.current!==expectedScope)return false;await load(true);return true}catch(caught){if(owner.current===account&&mounted.current)setError(friendlyError(caught,'No se pudo guardar el comentario.'));return false}finally{if(owner.current===account&&mounted.current)setBusy(false)}
  }
  async function send(value:CommentDraft){
    if(!user)throw new Error('Inicia sesión para comentar.')
    const ok=await mutate(async()=>{
      if(ubuntu)return fallbackApiJson(kind==='video'?`${base}/${encodeURIComponent(targetId)}/comments`:`${base}/posts/${encodeURIComponent(targetId)}/comments`,{method:'POST',expectedAccountId:user.id,body:JSON.stringify({...value,mediaType:value.contentType,parentId:reply?.id||null})})
      const response=await supabase!.from(table).insert({[kind==='video'?'video_id':'post_id']:targetId,user_id:user.id,content:value.content,[kind==='video'?'parent_comment_id':'parent_id']:reply?.id||null,media_url:value.mediaUrl||null,[kind==='video'?'content_type':'media_type']:value.contentType});if(response.error)throw response.error
    });if(ok)setReply(null);else throw new Error('No se pudo enviar el comentario. Puedes reintentarlo.')
  }
  const endpoint=(id:string)=>`${base}/comments/${encodeURIComponent(id)}`
  async function remove(id:string){await mutate(async()=>{if(ubuntu)return fallbackApiJson(endpoint(id),{method:'DELETE',expectedAccountId:user?.id});const response=await supabase!.from(table).delete().eq('id',id);if(response.error)throw response.error})}
  async function react(id:string,reaction:string|null){await mutate(async()=>{if(!ubuntu)throw new Error('Las reacciones requieren el servidor actualizado.');return fallbackApiJson(`${endpoint(id)}/reaction`,{method:reaction?'PUT':'DELETE',expectedAccountId:user?.id,...(reaction?{body:JSON.stringify({reactionType:reaction})}:{})})})}
  async function edit(id:string,content:string){const ok=await mutate(async()=>{if(ubuntu)return fallbackApiJson(endpoint(id),{method:'PATCH',expectedAccountId:user?.id,body:JSON.stringify({content})});const response=await supabase!.from(table).update({content}).eq('id',id);if(response.error)throw response.error});if(!ok)throw new Error('No se pudo guardar el comentario.')}
  return <div className="public-comments"><div className="video-comments-list">{loading?<p role="status">Cargando comentarios…</p>:items.length?<CommentThreadList key={`${kind}:${targetId}:${user?.id}`} items={items} accountId={user?.id} kind={kind} busy={busy} onReply={setReply} onRemove={id=>void remove(id)} onReaction={(id,type)=>void react(id,type)} onEdit={edit}/>:<p>Sé la primera persona en comentar.</p>}</div>{error?<p className="error-text" role="alert">{error}</p>:null}<CommentComposer key={`${kind}:${targetId}:${user?.id}`} kind={kind} busy={busy} replyName={reply?textOf(reply,['user_name','userName','display_name'],'Usuario'):undefined} onCancelReply={()=>setReply(null)} onSend={send}/></div>
}
