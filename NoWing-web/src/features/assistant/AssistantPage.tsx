import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Sparkles, Send, Trash2, Square, Copy, RefreshCw } from 'lucide-react'
import { useAuth } from '../../core/auth/AuthProvider'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import { friendlyError } from '../../core/utils'
import './assistant.css'

type Message = { id: string; role: 'user' | 'assistant'; text: string; suggestions?: string[] }
type Answer = { answer?: string; response?: string; suggestions?: unknown }
function suggestionsOf(value: unknown): string[] { return Array.isArray(value) ? [...new Set(value.filter((text): text is string => typeof text === 'string').map(text => text.trim()).filter(text => text && text.length <= 1000))].slice(0, 3) : [] }
function savedMessages(key: string): Message[] {
  const saved = readCachedValue<unknown>(key, 30 * 86400000)
  if (!Array.isArray(saved)) return []
  return saved.filter((item): item is Message => item && typeof item.id === 'string' && ['user','assistant'].includes(item.role) && typeof item.text === 'string').slice(-40).map(item => ({ ...item, text: item.text.slice(0,12000), suggestions: suggestionsOf(item.suggestions) }))
}
function AssistantConversation({ accountId }: { accountId: string }) {
  const key = 'novvin:assistant:' + accountId
  const [messages,setMessages] = useState<Message[]>(() => savedMessages(key)), [draft,setDraft] = useState(() => { const saved = readCachedValue<unknown>(key + ':draft', 30 * 86400000); return typeof saved === 'string' ? saved.slice(0,1000) : '' })
  const [expanded, setExpanded] = useState(false)
  const [conversationId, setConversationId] = useState(() => readCachedValue<string>(key + ':conversation') || crypto.randomUUID())
  const [busy,setBusy] = useState(false), [error,setError] = useState(''), [failedId,setFailedId] = useState(''), [copied,setCopied] = useState('')
  const request = useRef<AbortController | null>(null), bottom = useRef<HTMLDivElement>(null), composer = useRef<HTMLTextAreaElement>(null)
  useEffect(() => () => request.current?.abort(), [])
  useEffect(() => { writeCachedValue(key + ':draft', draft); if (composer.current) { composer.current.style.height = 'auto'; composer.current.style.height = Math.min(160, composer.current.scrollHeight) + 'px' } }, [key,draft])
  useEffect(() => { writeCachedValue(key + ':conversation', conversationId) }, [key,conversationId])
  useEffect(() => { writeCachedValue(key,messages.slice(-40)); bottom.current?.scrollIntoView({block:'nearest'}) }, [key,messages,busy])
  async function ask(text: string, existingId?: string) {
    if (!text.trim() || request.current) return
    const controller = new AbortController(); request.current = controller; setBusy(true); setError(''); setFailedId('')
    const id = existingId || crypto.randomUUID(), history = existingId ? messages.slice(0,messages.findIndex(item => item.id === existingId)) : messages
    if (!existingId) setMessages(current => [...current,{id,role:'user',text}])
    try {
      const response = await fallbackApiJson<Answer>('/v1/ai/chat',{method:'POST',expectedAccountId:accountId,signal:controller.signal,body:JSON.stringify({message:text,context:{screen:'web-assistant',locale:'es',conversationId,detailLevel:expanded?'expanded':'standard',history:history.slice(-8).map(item => (item.role==='user'?'Usuario':'Novvin') + ': ' + item.text.replace(/\s+/g,' ')).join('\n').slice(-6000)}})})
      if (controller.signal.aborted) return
      const answer = response.answer || response.response
      if (!answer) throw new Error('EMPTY_RESPONSE')
      setMessages(current => [...current,{id:crypto.randomUUID(),role:'assistant',text:answer,suggestions:suggestionsOf(response.suggestions)}])
    } catch (caught) {
      if (!controller.signal.aborted) { setFailedId(id); setError(friendlyError(caught,'El asistente no pudo responder. Puedes volver a intentarlo.')) }
    } finally {
      if (request.current === controller) { request.current = null; setBusy(false); composer.current?.focus() }
    }
  }
  function send(event: FormEvent) { event.preventDefault(); if (!draft.trim() || busy) return; const text=draft.trim(); setDraft(''); void ask(text) }
  function stop() { request.current?.abort(); request.current=null;setBusy(false);setError('Respuesta detenida. Puedes volver a preguntar.');const last=messages.at(-1);if(last?.role==='user')setFailedId(last.id) }
  return <section className="assistant-page">
    <header><div><Sparkles/><div><h2>Asistente de Novvin</h2><small>Tu espacio para preguntar y crear</small></div></div><button className="icon-btn" type="button" aria-label="Borrar conversación con el asistente" onClick={() => { request.current?.abort();request.current=null;setBusy(false);setMessages([]);setDraft('');setConversationId(crypto.randomUUID());setError('');setFailedId('') }}><Trash2 size={19}/></button></header>
    <div className="assistant-conversation" role="log" aria-label="Conversación con el asistente" aria-live="polite">
      {!messages.length ? <article className="assistant-welcome"><Sparkles size={34}/><h3>¿En qué te ayudo?</h3><p>Pregunta por Novvin o cuéntame qué necesitas hacer.</p><div>{['¿Cómo vinculo la web con mi teléfono?','¿Cómo publico un momento con música?','Ayúdame a escribir una publicación'].map(text => <button type="button" className="ghost-btn" key={text} onClick={() => { setDraft(text);composer.current?.focus() }}>{text}</button>)}</div></article> : messages.map(item => <article className={'assistant-message '+item.role} key={item.id}><strong>{item.role==='user'?'Tú':'Novvin'}</strong><div className="assistant-answer">{item.text.split(/\n{2,}/).map((paragraph,index) => <p key={index}>{paragraph}</p>)}</div>{item.role==='assistant'?<button className="assistant-message-copy" type="button" aria-label="Copiar respuesta" onClick={() => { void (async () => { try { if (!navigator.clipboard) throw new Error('CLIPBOARD_UNAVAILABLE'); await navigator.clipboard.writeText(item.text);setCopied(item.id) } catch { setError('No se pudo copiar la respuesta. Selecciona el texto para copiarlo.') } })() }}><Copy size={14}/>{copied===item.id?'Copiado':'Copiar'}</button>:failedId===item.id?<button className="ghost-btn" type="button" disabled={busy} onClick={() => void ask(item.text,item.id)}><RefreshCw size={15}/>Volver a intentar</button>:null}</article>)}
      {busy ? <p className="assistant-typing" role="status"><span/>Novvin está escribiendo…</p> : null}<div ref={bottom}/>
    </div>
    {messages.at(-1)?.role==='assistant' && !busy && messages.at(-1)?.suggestions?.length ? <div className="assistant-followups" aria-label="Sugerencias para continuar">{messages.at(-1)!.suggestions!.map(text => <button type="button" className="ghost-btn" key={text} onClick={() => { setDraft(''); void ask(text) }}>{text}</button>)}</div> : null}
    {error ? <p role="status" className="assistant-notice">{error}</p> : null}
    <form onSubmit={send}><textarea ref={composer} className="text-input" aria-label="Mensaje al asistente" placeholder="Escribe tu pregunta…" maxLength={1000} value={draft} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key==='Enter'&&!event.shiftKey&&!event.nativeEvent.isComposing) {event.preventDefault();event.currentTarget.form?.requestSubmit()} }}/>{busy?<button type="button" className="icon-btn" aria-label="Detener respuesta" onClick={stop}><Square size={18}/></button>:<button type="submit" className="icon-btn" aria-label="Enviar al asistente" disabled={!draft.trim()}><Send size={20}/></button>}</form>
    <div className="assistant-composer-options"><label><input type="checkbox" checked={expanded} onChange={event => setExpanded(event.target.checked)}/>Respuesta paso a paso</label><small aria-label="Caracteres del mensaje">{draft.length}/1000</small></div>
    <small className="assistant-footnote">Enter para enviar · Mayús + Enter para una nueva línea</small>
  </section>
}
export function AssistantPage() { const { user }=useAuth(); return user ? <AssistantConversation key={user.id} accountId={user.id}/> : null }
