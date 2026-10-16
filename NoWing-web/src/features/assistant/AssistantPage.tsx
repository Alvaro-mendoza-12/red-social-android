import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Sparkles, Send, Trash2, Square, Copy, RefreshCw } from 'lucide-react'
import { useAuth } from '../../core/auth/AuthProvider'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import { friendlyError } from '../../core/utils'
import './assistant.css'

type Message = { id: string; role: 'user' | 'assistant'; text: string }
function savedMessages(key: string): Message[] {
  const saved = readCachedValue<unknown>(key, 30 * 86400000)
  if (!Array.isArray(saved)) return []
  return saved.filter((item): item is Message => item && typeof item.id === 'string' && ['user','assistant'].includes(item.role) && typeof item.text === 'string').slice(-40).map(item => ({ ...item, text: item.text.slice(0,12000) }))
}
function AssistantConversation({ accountId }: { accountId: string }) {
  const key = 'novvin:assistant:' + accountId
  const [messages,setMessages] = useState<Message[]>(() => savedMessages(key)), [draft,setDraft] = useState('')
  const [busy,setBusy] = useState(false), [error,setError] = useState(''), [failedId,setFailedId] = useState(''), [copied,setCopied] = useState('')
  const request = useRef<AbortController | null>(null), bottom = useRef<HTMLDivElement>(null), composer = useRef<HTMLTextAreaElement>(null)
  useEffect(() => () => request.current?.abort(), [])
  useEffect(() => { writeCachedValue(key,messages.slice(-40)); bottom.current?.scrollIntoView({block:'nearest'}) }, [key,messages,busy])
  async function ask(text: string, existingId?: string) {
    if (!text.trim() || request.current) return
    const controller = new AbortController(); request.current = controller; setBusy(true); setError(''); setFailedId('')
    const id = existingId || crypto.randomUUID(), history = existingId ? messages.filter(item => item.id !== existingId) : messages
    if (!existingId) setMessages(current => [...current,{id,role:'user',text}])
    try {
      const response = await fallbackApiJson<{answer?:string;response?:string}>('/v1/ai/chat',{method:'POST',expectedAccountId:accountId,signal:controller.signal,body:JSON.stringify({message:text,context:{screen:'web-assistant',locale:'es',history:history.slice(-6).map(item => item.role + ': ' + item.text).join('\n').slice(-6000)}})})
      if (controller.signal.aborted) return
      const answer = response.answer || response.response
      if (!answer) throw new Error('EMPTY_RESPONSE')
      setMessages(current => [...current,{id:crypto.randomUUID(),role:'assistant',text:answer}])
    } catch (caught) {
      if (!controller.signal.aborted) { setFailedId(id); setError(friendlyError(caught,'El asistente no pudo responder. Puedes volver a intentarlo.')) }
    } finally {
      if (request.current === controller) { request.current = null; setBusy(false); composer.current?.focus() }
    }
  }
  function send(event: FormEvent) { event.preventDefault(); if (!draft.trim() || busy) return; const text=draft.trim(); setDraft(''); void ask(text) }
  function stop() { request.current?.abort(); request.current=null;setBusy(false);setError('Respuesta detenida. Puedes volver a preguntar.');const last=messages.at(-1);if(last?.role==='user')setFailedId(last.id) }
  return <section className="assistant-page">
    <header><div><Sparkles/><div><h2>Asistente de Novvin</h2><small>Tu espacio para preguntar y crear</small></div></div><button className="icon-btn" type="button" aria-label="Borrar conversación con el asistente" onClick={() => { request.current?.abort();request.current=null;setBusy(false);setMessages([]);setError('');setFailedId('') }}><Trash2 size={19}/></button></header>
    <div className="assistant-conversation" role="log" aria-label="Conversación con el asistente" aria-live="polite">
      {!messages.length ? <article className="assistant-welcome"><Sparkles size={34}/><h3>¿En qué te ayudo?</h3><p>Pregunta por Novvin o cuéntame qué necesitas hacer.</p><div>{['¿Cómo vinculo la web con mi teléfono?','¿Cómo publico un momento con música?','Ayúdame a escribir una publicación'].map(text => <button type="button" className="ghost-btn" key={text} onClick={() => { setDraft(text);composer.current?.focus() }}>{text}</button>)}</div></article> : messages.map(item => <article className={'assistant-message '+item.role} key={item.id}><strong>{item.role==='user'?'Tú':'Novvin'}</strong><p>{item.text}</p>{item.role==='assistant'?<button className="assistant-message-copy" type="button" aria-label="Copiar respuesta" onClick={() => { void (async () => { try { if (!navigator.clipboard) throw new Error('CLIPBOARD_UNAVAILABLE'); await navigator.clipboard.writeText(item.text);setCopied(item.id) } catch { setError('No se pudo copiar la respuesta. Selecciona el texto para copiarlo.') } })() }}><Copy size={14}/>{copied===item.id?'Copiado':'Copiar'}</button>:failedId===item.id?<button className="ghost-btn" type="button" disabled={busy} onClick={() => void ask(item.text,item.id)}><RefreshCw size={15}/>Volver a intentar</button>:null}</article>)}
      {busy ? <p className="assistant-typing" role="status"><span/>Novvin está escribiendo…</p> : null}<div ref={bottom}/>
    </div>
    {error ? <p role="status" className="assistant-notice">{error}</p> : null}
    <form onSubmit={send}><textarea ref={composer} className="text-input" aria-label="Mensaje al asistente" placeholder="Escribe tu pregunta…" maxLength={1000} value={draft} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key==='Enter'&&!event.shiftKey&&!event.nativeEvent.isComposing) {event.preventDefault();event.currentTarget.form?.requestSubmit()} }}/>{busy?<button type="button" className="icon-btn" aria-label="Detener respuesta" onClick={stop}><Square size={18}/></button>:<button type="submit" className="icon-btn" aria-label="Enviar al asistente" disabled={!draft.trim()}><Send size={20}/></button>}</form>
    <small className="assistant-footnote">Enter para enviar · Mayús + Enter para una nueva línea</small>
  </section>
}
export function AssistantPage() { const { user }=useAuth(); return user ? <AssistantConversation key={user.id} accountId={user.id}/> : null }
