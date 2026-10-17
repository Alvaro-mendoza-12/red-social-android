import { useEffect, useRef, useState } from 'react'
import { Cake, Mail, X } from 'lucide-react'
import { birthdayCard, defaultBirthdayCard, type BirthdayCard } from '../core/birthdayCard'
import { readConversationDraft, writeConversationDraft } from '../core/conversationDrafts'
import '../ui/novvin/birthday.css'

export function BirthdayLetter({ content }: { content: string }) {
  const card = birthdayCard(content), dialog = useRef<HTMLDialogElement>(null)
  if (!card) return <p>Tarjeta de cumpleaños</p>
  return <>
    <button type="button" className={`birthday-letter ${card.theme}`} onClick={() => dialog.current?.showModal()}>
      <Mail size={36} aria-hidden="true"/><strong>¡Feliz cumpleaños!</strong><span>Una carta para celebrar tu día</span><small>Toca para abrir</small>
    </button>
    <dialog ref={dialog} className="birthday-dialog birthday-celebration" aria-label="Tu carta de cumpleaños" onClick={e => { if (e.target === e.currentTarget) dialog.current?.close() }}>
      <button type="button" className="birthday-close" aria-label="Cerrar carta" onClick={() => dialog.current?.close()}><X/></button>
      <div className="birthday-confetti" aria-hidden="true">{Array.from({length:36},(_,i)=><i key={i} style={{left:`${(i*73)%101}%`,animationDelay:`${(i%7)*40}ms`,background:['#e75ba5','#ffbe3b','#6486ff','#37ba91'][i%4]}}/>)}</div>
      <BirthdayPreview card={card}/>
    </dialog>
  </>
}
function BirthdayPreview({ card }: { card: BirthdayCard }) { return <article className={`birthday-letter birthday-preview ${card.theme}`}>
  <Cake size={36} aria-hidden="true"/><h2>¡Feliz cumpleaños!</h2><p>{card.message || 'Tu felicitación aparecerá aquí'}</p><small>Con cariño · Novvin</small>
</article> }
export function BirthdayComposer({ owner, chatId, recipient, onClose, onSend }: { owner: string; chatId: string; recipient: string; onClose: () => void; onSend: (content: string) => Promise<void> }) {
  const room = 'birthday:' + chatId
  const [card, setCard] = useState(() => birthdayCard(readConversationDraft(owner, 'chat', room)) || defaultBirthdayCard)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { dialog.current?.showModal() }, [])
  function update(next: BirthdayCard) { setCard(next); writeConversationDraft(owner, 'chat', room, JSON.stringify(next)) }
  async function send() {
    if (busy || !birthdayCard(JSON.stringify(card))) return
    setBusy(true); setError('')
    try { await onSend(JSON.stringify({ ...card, message: card.message.trim() })); writeConversationDraft(owner, 'chat', room, ''); onClose() }
    catch { setError('No se pudo enviar la carta. Conservamos tu borrador para reintentar.'); setBusy(false) }
  }
  return <dialog ref={dialog} className="birthday-dialog birthday-composer" aria-label="Felicítalo ahora" onCancel={e => { e.preventDefault(); if (!busy) onClose() }}>
    <button type="button" className="birthday-close" aria-label="Cerrar felicitación" disabled={busy} onClick={onClose}><X/></button>
    <h2>Felicítalo ahora</h2><p>Una carta de cumpleaños{recipient ? ' para ' + recipient : ''}</p>
    <div className="birthday-themes" aria-label="Estilo de la carta">{(['confetti','sunrise','garden'] as const).map((theme,i) => <button type="button" key={theme} aria-pressed={card.theme === theme} disabled={busy} onClick={() => update({ ...card, theme })}>{['Confeti','Amanecer','Jardín'][i]}</button>)}</div>
    <BirthdayPreview card={card}/>
    <label>Tu felicitación<textarea aria-label="Tu felicitación" value={card.message} disabled={busy} rows={4} onChange={e => update({ ...card, message: [...e.target.value].slice(0,600).join('') })}/></label>
    <small>{[...card.message].length}/600 · Se guarda como borrador</small>
    {error ? <p role="alert">{error}</p> : null}
    <button type="button" className="primary-btn" disabled={busy || !birthdayCard(JSON.stringify(card))} onClick={() => void send()}>{busy ? 'Preparando envío…' : 'Enviar carta de cumpleaños'}</button>
  </dialog>
}
