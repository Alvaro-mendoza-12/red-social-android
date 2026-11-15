import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Send, Radar } from 'lucide-react'
import { useAuth } from '../../core/auth/AuthProvider'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { friendlyError } from '../../core/utils'

type Bottle = { id: string; content: string; status: string }
type BottleResponse = { bottle: Bottle | null; status: string }
const pollDelayMs = 5_000

export function RadarMessages({ onAccepted }: { onAccepted: () => void }) {
  const { user } = useAuth()
  return user ? <BottleDesk key={user.id} accountId={user.id} onAccepted={onAccepted} /> : null
}

function BottleDesk({ accountId, onAccepted }: { accountId: string; onAccepted: () => void }) {
  const [draft, setDraft] = useState('')
  const [receiving, setReceiving] = useState(false)
  const [outgoing, setOutgoing] = useState<Bottle | null>(null)
  const [incoming, setIncoming] = useState<Bottle | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [receiveError, setReceiveError] = useState(false)
  const [info, setInfo] = useState('')
  const accepted = useRef('')
  const generation = useRef(0)
  const onAccept = useRef(onAccepted)
  useEffect(() => { onAccept.current = onAccepted }, [onAccepted])
  const call = useCallback(<T,>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal) =>
    fallbackApiJson<T>(`/v1/data/proximity/random/${path}`, {
      method, expectedAccountId: accountId, signal,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }), [accountId])

  useEffect(() => {
    const controller = new AbortController()
    if (busy) return
    function poll(request: (signal: AbortSignal) => Promise<BottleResponse>, receive: (value: BottleResponse) => void, fail?: () => void) {
      let timer: number | undefined
      let active = false
      async function tick() {
        window.clearTimeout(timer)
        if (active || document.hidden || controller.signal.aborted) return
        active = true
        const currentGeneration = generation.current
        const isCurrent = () => !controller.signal.aborted && currentGeneration === generation.current
        const requestController = new AbortController()
        const abortRequest = () => requestController.abort()
        const timeout = window.setTimeout(abortRequest, 6_000)
        controller.signal.addEventListener('abort', abortRequest, { once: true })
        try {
          const response = await request(requestController.signal)
          if (isCurrent()) receive(response)
        } catch {
          if (isCurrent()) fail?.()
        } finally {
          window.clearTimeout(timeout)
          controller.signal.removeEventListener('abort', abortRequest)
          active = false
          if (!controller.signal.aborted && !document.hidden) timer = window.setTimeout(tick, pollDelayMs)
        }
      }
      const visibilityChanged = () => {
        if (document.hidden) window.clearTimeout(timer)
        else void tick()
      }
      document.addEventListener('visibilitychange', visibilityChanged)
      void tick()
      return () => {
        window.clearTimeout(timer)
        document.removeEventListener('visibilitychange', visibilityChanged)
      }
    }
    const stopOutgoing = poll(
      signal => call('bottles/outgoing', 'GET', undefined, signal),
      response => {
        setOutgoing(response.bottle)
        if (response.status === 'accepted' && response.bottle && accepted.current !== response.bottle.id) {
          accepted.current = response.bottle.id
          onAccept.current()
        }
      },
    )
    const stopIncoming = receiving ? poll(
      signal => call('availability', 'POST', {}, signal),
      response => { setIncoming(response.bottle); setReceiveError(false) },
      () => setReceiveError(true),
    ) : undefined
    return () => {
      controller.abort()
      stopOutgoing()
      stopIncoming?.()
    }
  }, [busy, call, receiving])

  async function mutate(operation: () => Promise<void>, failureMessage: string) {
    if (busy) return
    ++generation.current
    setBusy(true)
    setError('')
    setInfo('')
    try { await operation() } catch (caught) { setError(friendlyError(caught, failureMessage)) } finally { setBusy(false) }
  }

  function toggleReceiving(next: boolean) {
    ++generation.current
    setReceiving(next)
    setIncoming(null)
    setReceiveError(false)
    // Let the server lease expire when reception stops. A delayed DELETE can
    // unregister another tab that has just renewed the same account's lease.
  }

  async function send(event: FormEvent) {
    event.preventDefault()
    const content = draft.trim()
    if (!content) return
    await mutate(async () => {
      const result = await call<BottleResponse>('bottles', 'POST', { content })
      setOutgoing(result.bottle)
      setDraft('')
      setInfo('Tu mensaje está buscando una persona que quiera conversar.')
    }, 'No se pudo lanzar el mensaje.')
  }

  async function respond(action: 'accept' | 'decline') {
    if (!incoming) return
    const id = encodeURIComponent(incoming.id)
    await mutate(async () => {
      await call(`bottles/${id}/respond`, 'POST', { action })
      setIncoming(null)
      if (action === 'accept') {
        toggleReceiving(false)
        onAccept.current()
      }
    }, 'La invitación ya no está disponible.')
  }

  async function withdraw(id: string) {
    await mutate(async () => {
      await call(`bottles/${encodeURIComponent(id)}`, 'DELETE')
      setOutgoing(null)
      setInfo('Mensaje retirado.')
    }, 'No se pudo retirar el mensaje.')
  }

  const pending = outgoing && ['searching', 'offered'].includes(outgoing.status)
  return <section className="radar-messages surface-card">
    <header><Radar /><div><h3>Mensajes del radar</h3><p>Lanza un mensaje anónimo y conversa cuando alguien lo acepte.</p></div></header>
    <label className="checkbox-inline"><input type="checkbox" checked={receiving} onChange={event => toggleReceiving(event.target.checked)} />Recibir mensajes mientras estoy aquí</label>
    {receiveError && <p role="alert">No se pudo mantener la recepción de mensajes. Volveremos a intentarlo.</p>}
    {incoming && <article className="radar-message-invitation">
      <p>{incoming.content}</p><div className="row-actions">
        <button className="primary-btn" type="button" disabled={busy} onClick={() => void respond('accept')}>Aceptar conversación</button>
        <button className="ghost-btn" type="button" disabled={busy} onClick={() => void respond('decline')}>Pasar</button>
      </div>
    </article>}
    <form onSubmit={event => void send(event)}>
      <label>Tu mensaje<textarea className="text-input" maxLength={500} value={draft} onChange={event => setDraft(event.target.value)} placeholder="Comparte una idea o saluda…" /></label>
      <button className="primary-btn" type="submit" disabled={busy || !draft.trim() || Boolean(pending)}><Send size={17} />Lanzar mensaje</button>
    </form>
    {pending && <div className="row-between"><span>Buscando conversación…</span><button type="button" className="ghost-btn" disabled={busy} onClick={() => void withdraw(outgoing.id)}>Retirar mensaje</button></div>}
    {error ? <p role="alert" className="error-text">{error}</p> : info ? <p role="status">{info}</p> : null}
  </section>
}
