import { useEffect, useRef, useState, type FormEvent } from 'react'
import { BarChart3, Check, MessageCircle, Pin, Plus } from 'lucide-react'
import { PublicationDialog } from '../../components/PublicationDialog'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
import { inboxRefreshScheduler } from '../../core/inboxRefreshScheduler'

type AudienceItem = {
  id: string; kind: 'poll' | 'question'; content: string; options: string[]
  status: 'open' | 'closed' | 'answered' | 'dismissed'; answer: string
  highlighted: boolean; authorId: string; authorName: string
  counts: number[]; totalVotes: number; myChoice: number | null; closesAt: string | null
}
type AudienceState = { items: AudienceItem[]; canModerate: boolean; hasMore: boolean }
type Patch = { status?: 'closed' | 'answered' | 'dismissed'; answer?: string; highlighted?: boolean }

function userError(error: unknown) {
  const code = error instanceof Error ? error.message : ''
  if (code.includes('LIVE_POLL_ALREADY_OPEN')) return 'Ya hay una encuesta abierta. Ciérrala para crear la siguiente.'
  if (code.includes('LIVE_POLL_CLOSED')) return 'La encuesta terminó. Actualiza para ver los resultados.'
  if (code.includes('LIVE_MODERATOR_REQUIRED')) return 'Esta acción necesita permisos de moderación.'
  if (code.includes('LIVE_POLL_OPTIONS_INVALID')) return 'Escribe entre dos y seis respuestas diferentes.'
  if (code.includes('LIVE_NOT_ACTIVE')) return 'El directo terminó.'
  if (code.includes('LIVE_NOT_FOUND')) return 'El directo ya no está disponible para tu cuenta.'
  if (code.includes('LIVE_COMMENT_BLOCKED')) return 'Revisa la pregunta. El directo tiene un filtro de palabras.'
  return 'No se pudo actualizar. Revisa tu conexión y vuelve a intentarlo.'
}

export function LiveAudienceDialog({ liveId, accountId, onClose }: { liveId: string; accountId: string; onClose: () => void }) {
  const [state, setState] = useState<AudienceState | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [tab, setTab] = useState<'poll' | 'question'>('poll')
  const [creating, setCreating] = useState(false)
  const [question, setQuestion] = useState('')
  const alive = useRef(true), writing = useRef(false), version = useRef(0)
  const operation = useRef<AbortController | null>(null)
  const pendingCreates = useRef(new Map<string, { payload: string; nonce: string }>())
  const base = `/v1/data/live/${encodeURIComponent(liveId)}/audience`
  const reload = useRef<() => void>(() => {})
  useEffect(() => {
    alive.current = true
    const controller = new AbortController()
    const load = async () => {
      if (writing.current || document.hidden) return
      const request = ++version.current
      try {
        const response = await fallbackApiJson<AudienceState>(base, {
          expectedAccountId: accountId, signal: controller.signal, cache: 'no-store',
        })
        if (!controller.signal.aborted && request === version.current) { setState(response); setError('') }
      } catch (cause) {
        if (!controller.signal.aborted && request === version.current) setError(userError(cause))
      }
    }
    const scheduler = inboxRefreshScheduler(load, cause => {
      if (!controller.signal.aborted) setError(userError(cause))
    })
    reload.current = scheduler.request
    const channel = `live-audience:${liveId}`
    const ready = (event: Event) => { if ((event as CustomEvent<string>).detail === channel) scheduler.request() }
    const visible = () => { if (!document.hidden) scheduler.request() }
    const unsubscribe = subscribeUbuntuRealtime({ channel, onEvent: scheduler.request })
    window.addEventListener('novvin:channel-ready', ready)
    window.addEventListener('online', visible)
    document.addEventListener('visibilitychange', visible)
    const timer = window.setInterval(scheduler.request, 5000)
    scheduler.request()
    return () => {
      alive.current = false; controller.abort(); operation.current?.abort()
      window.clearInterval(timer); scheduler.stop(); unsubscribe()
      window.removeEventListener('novvin:channel-ready', ready)
      window.removeEventListener('online', visible)
      document.removeEventListener('visibilitychange', visible)
    }
  }, [base, liveId, accountId])

  async function save(path: string, body: object, method = 'POST') {
    if (writing.current) return false
    writing.current = true; ++version.current
    setBusy(true); setError('')
    const controller = new AbortController()
    operation.current = controller
    try {
      const result = await fallbackApiJson<AudienceState>(base + path, {
        method, body: JSON.stringify(body), signal: controller.signal, expectedAccountId: accountId,
      })
      if (!alive.current) return false
      setState(result)
      return true
    } catch (cause) {
      if (alive.current && !controller.signal.aborted) setError(userError(cause))
      return false
    } finally {
      writing.current = false
      if (alive.current) { setBusy(false); reload.current() }
    }
  }
  async function create(kind: 'polls' | 'questions', body: object) {
    const payload = JSON.stringify(body)
    let pending = pendingCreates.current.get(kind)
    if (pending?.payload !== payload) {
      pending = { payload, nonce: crypto.randomUUID() }
      pendingCreates.current.set(kind, pending)
    }
    if (await save('/' + kind, { ...body, requestNonce: pending!.nonce })) {
      pendingCreates.current.delete(kind)
      setCreating(false); setQuestion('')
    }
  }
  const vote = (id: string, choiceIndex: number) => { void save(`/items/${encodeURIComponent(id)}/vote`, { choiceIndex }) }
  const update = (id: string, patch: Patch) => { void save(`/items/${encodeURIComponent(id)}`, patch, 'PATCH') }
  const polls = state?.items.filter(item => item.kind === 'poll' && item.status !== 'dismissed') || []
  return <PublicationDialog title="Participa en el directo" busy={busy} onClose={onClose} className="live-audience-dialog">
    <div className="live-audience-tabs" aria-label="Participación">
      <button type="button" aria-pressed={tab === 'poll'} onClick={() => setTab('poll')}><BarChart3 size={18}/>Encuesta</button>
      <button type="button" aria-pressed={tab === 'question'} onClick={() => setTab('question')}><MessageCircle size={18}/>Preguntas</button>
    </div>
    {error ? <div role="status"><p>{error}</p><button className="ghost-btn" type="button" onClick={() => reload.current()} disabled={busy}>Actualizar</button></div> : null}
    {!state && !error ? <p role="status">Cargando participación…</p> : null}
    {state && tab === 'poll' ? <>
      {state.canModerate && !polls.some(item => item.status === 'open') ? creating
        ? <PollComposer busy={busy} onCreate={(content, options, durationSeconds) => { void create('polls', { content, options, durationSeconds }) }}/>
        : <button className="primary-btn" type="button" onClick={() => setCreating(true)}><Plus size={18}/>Crear encuesta</button> : null}
      {!polls.length && !creating ? <p>Todavía no hay encuesta.</p> : null}
      {polls.map(item => <PollCard key={item.id} item={item} busy={busy} canModerate={state.canModerate}
        onVote={index => vote(item.id, index)} onClose={() => update(item.id, { status: 'closed' })}/>)}
    </> : null}
    {state && tab === 'question' ? <>
      <form className="live-audience-form" onSubmit={event => { event.preventDefault(); if (question.trim()) void create('questions', { content: question.trim() }) }}>
        <label>Tu pregunta<textarea value={question} onChange={event => setQuestion(event.target.value)} maxLength={300} rows={2} disabled={busy}/></label>
        <button className="primary-btn" type="submit" disabled={busy || !question.trim()}>Enviar pregunta</button>
      </form>
      {!state.items.some(item => item.kind === 'question' && item.status !== 'dismissed') ? <p>Todavía no hay preguntas.</p> : null}
      {state.items.filter(item => item.kind === 'question' && item.status !== 'dismissed').map(item =>
        <QuestionCard key={item.id} item={item} busy={busy} own={item.authorId === accountId} canModerate={state.canModerate}
          onVote={() => vote(item.id, 0)} onUpdate={patch => update(item.id, patch)}/>)}
    </> : null}
    {state?.hasMore ? <p className="muted-line">Se muestran las participaciones más recientes.</p> : null}
  </PublicationDialog>
}

function PollComposer({ busy, onCreate }: { busy: boolean; onCreate: (content: string, options: string[], duration: number) => void }) {
  const [content, setContent] = useState(''), [options, setOptions] = useState(['', ''])
  const [duration, setDuration] = useState(300)
  const valid = content.trim() && options.every(item => item.trim()) &&
    new Set(options.map(item => item.trim().toLocaleLowerCase())).size === options.length
  function submit(event: FormEvent) { event.preventDefault(); if (valid) onCreate(content.trim(), options.map(item => item.trim()), duration) }
  return <form className="live-audience-form" onSubmit={submit}>
    <label>Pregunta de la encuesta<input value={content} onChange={event => setContent(event.target.value)} maxLength={300} disabled={busy}/></label>
    {options.map((option, index) => <label key={index}>Respuesta {index + 1}<input maxLength={80} value={option} disabled={busy}
      onChange={event => setOptions(items => items.map((item, i) => i === index ? event.target.value : item))}/></label>)}
    {options.length < 6 ? <button className="ghost-btn" type="button" disabled={busy} onClick={() => setOptions(items => [...items, ''])}>Añadir respuesta</button> : null}
    <label>Duración<select value={duration} onChange={event => setDuration(Number(event.target.value))} disabled={busy}>
      <option value={60}>1 minuto</option><option value={300}>5 minutos</option><option value={600}>10 minutos</option>
    </select></label>
    <button className="primary-btn" type="submit" disabled={busy || !valid}>Publicar encuesta</button>
  </form>
}

function PollCard({ item, busy, canModerate, onVote, onClose }: { item: AudienceItem; busy: boolean; canModerate: boolean; onVote: (index: number) => void; onClose: () => void }) {
  return <section className="live-audience-card" aria-label={item.content}>
    <h4>{item.content}</h4>
    <div className="live-poll-choices">{item.options.map((option, index) => {
      const count = item.counts[index] || 0, percent = item.totalVotes ? Math.round(100 * count / item.totalVotes) : 0
      return <button type="button" key={index} disabled={busy || item.status !== 'open'} aria-pressed={item.myChoice === index}
        onClick={() => onVote(index)} className="live-poll-choice">
        <span><span>{item.myChoice === index ? <Check size={16} aria-hidden="true"/> : null}{option}</span><small>{count} · {percent} %</small></span>
        <progress max={100} value={percent} aria-label={`Votos de ${option}`}/>
      </button>
    })}</div>
    <p className="muted-line" role="status">{item.totalVotes} {item.totalVotes === 1 ? 'voto' : 'votos'} · {item.status === 'open' ? 'Abierta' : 'Finalizada'}</p>
    {canModerate && item.status === 'open' ? <button className="ghost-btn" type="button" disabled={busy} onClick={onClose}>Cerrar encuesta</button> : null}
  </section>
}

function QuestionCard({ item, busy, own, canModerate, onVote, onUpdate }: { item: AudienceItem; busy: boolean; own: boolean; canModerate: boolean; onVote: () => void; onUpdate: (patch: Patch) => void }) {
  const [replying, setReplying] = useState(false), [answer, setAnswer] = useState('')
  return <article className="live-audience-card">
    {item.highlighted ? <small className="live-question-highlight"><Pin size={14}/>Pregunta destacada</small> : null}
    <strong>{item.authorName}</strong><p>{item.content}</p>
    {item.answer ? <blockquote><small>Respuesta</small><p>{item.answer}</p></blockquote> : null}
    <div className="live-audience-actions">
      <button className="ghost-btn" type="button" aria-pressed={item.myChoice !== null} disabled={busy || item.myChoice !== null || item.status !== 'open'} onClick={onVote}>
        {item.myChoice !== null ? 'Pregunta apoyada' : 'Me interesa'} · {item.totalVotes}
      </button>
      {canModerate ? <><button className="ghost-btn" type="button" onClick={() => setReplying(value => !value)}>Responder</button>
        <button className="ghost-btn" type="button" disabled={busy} onClick={() => onUpdate({ highlighted: !item.highlighted })}>{item.highlighted ? 'Quitar destacado' : 'Destacar'}</button></> : null}
      {canModerate || own ? <button className="ghost-btn" type="button" disabled={busy} onClick={() => onUpdate({ status: 'dismissed' })}>{own ? 'Retirar pregunta' : 'Ocultar pregunta'}</button> : null}
    </div>
    {replying ? <form className="live-audience-form" onSubmit={event => { event.preventDefault(); if (answer.trim()) onUpdate({ status: 'answered', answer: answer.trim() }) }}>
      <label>Respuesta del anfitrión<textarea maxLength={500} value={answer} onChange={event => setAnswer(event.target.value)} disabled={busy}/></label>
      <button className="primary-btn" type="submit" disabled={busy || !answer.trim()}>Publicar respuesta</button>
    </form> : null}
  </article>
}
