import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Sparkles, Send, Trash2 } from 'lucide-react'
import { useAuth } from '../../core/auth/AuthProvider'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { friendlyError } from '../../core/utils'
type Message = { id:string; role:'user'|'assistant'; text:string }
export function AssistantPage(){
  const {user}=useAuth(),[messages,setMessages]=useState<Message[]>([]),[draft,setDraft]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),request=useRef<AbortController|null>(null),bottom=useRef<HTMLDivElement>(null)
  useEffect(()=>{setMessages([]);setDraft('');setBusy(false);setError('');return()=>request.current?.abort()},[user?.id])
  useEffect(()=>{bottom.current?.scrollIntoView({behavior:'smooth',block:'nearest'})},[messages,busy])
  async function send(event:FormEvent){
    event.preventDefault();const message=draft.trim();if(!message||busy||!user)return
    const controller=new AbortController();request.current=controller;setBusy(true);setError('');setDraft('')
    const next=[...messages,{id:crypto.randomUUID(),role:'user' as const,text:message}];setMessages(next)
    try{const response=await fallbackApiJson<{answer?:string;response?:string}>('/v1/ai/chat',{method:'POST',expectedAccountId:user.id,signal:controller.signal,body:JSON.stringify({message,context:{screen:'web-assistant',locale:'es',history:messages.slice(-6).map(item=>`${item.role}: ${item.text}`).join('\n').slice(-6000)}})});if(!controller.signal.aborted){const text=response.answer||response.response;if(!text)throw new Error('EMPTY_RESPONSE');setMessages(current=>[...current,{id:crypto.randomUUID(),role:'assistant',text}])}}catch(caught){if(!controller.signal.aborted){setError(friendlyError(caught,'El asistente no pudo responder. Inténtalo de nuevo.'));setDraft(message)}}finally{if(!controller.signal.aborted)setBusy(false)}
  }
  return <section className="assistant-page"><header><div><Sparkles/><h2>Asistente de Novvin</h2></div><button className="icon-btn" type="button" disabled={busy} aria-label="Borrar conversación con el asistente" onClick={()=>setMessages([])}><Trash2/></button></header><div className="assistant-conversation">{!messages.length?<article className="assistant-welcome"><Sparkles size={34}/><h3>¿En qué te ayudo?</h3><p>Pregunta por Novvin o cuéntame qué necesitas hacer.</p><div>{['¿Cómo vinculo la web con mi teléfono?','¿Cómo publico un momento con música?','¿Cómo creo un grupo?'].map(text=><button type="button" className="ghost-btn" key={text} onClick={()=>setDraft(text)}>{text}</button>)}</div></article>:messages.map(item=><article className={`assistant-message ${item.role}`} key={item.id}><strong>{item.role==='user'?'Tú':'Novvin'}</strong><p>{item.text}</p></article>)}{busy?<p role="status">Novvin está escribiendo…</p>:null}<div ref={bottom}/></div>{error?<p role="alert" className="error-text">{error}</p>:null}<form onSubmit={event=>void send(event)}><textarea className="text-input" aria-label="Mensaje al asistente" placeholder="Escribe tu pregunta…" maxLength={1000} value={draft} onChange={event=>setDraft(event.target.value)}/><button type="submit" className="icon-btn" aria-label="Enviar al asistente" disabled={busy||!draft.trim()}><Send/></button></form></section>
}
