import { useCallback, useEffect, useRef, useState } from 'react'
import { Mic } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { SafeAvatar } from '../../components/SafeAvatar'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { getStorageReadIdentity, requireStorageReadIdentity, subscribeStorageReadIdentity } from '../../core/media/storageReadSession'
import { clearGroupVoiceIntent, groupVoiceRecipients, loadGroupVoiceIntent, saveGroupVoiceIntent, type GroupVoiceIntent, type GroupVoiceRoster } from './groupVoiceDraft'
import './groupVoiceRoom.css'

async function withAbort<T>(request: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(new DOMException('Invitation request interrupted', 'AbortError'))
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true })
    request.then(value => { signal.removeEventListener('abort', abort); resolve(value) },
      error => { signal.removeEventListener('abort', abort); reject(error) })
  })
}

export function GroupVoiceRoomDialog({ groupId, onClose }: { groupId: string; onClose: () => void }) {
  const navigate = useNavigate()
  const [owner] = useState(getStorageReadIdentity)
  const [roster, setRoster] = useState<GroupVoiceRoster | null>(null)
  const [mode, setMode] = useState<'all' | 'selected'>('all')
  const [selected, setSelected] = useState(new Set<string>())
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const intent = useRef<GroupVoiceIntent | null>(null)
  const active = useRef(true)
  const sending = useRef(false)
  const dialog = useRef<HTMLDialogElement>(null)
  const close = useRef(onClose); close.current = onClose
  const controller = useRef<AbortController | null>(null)
  const endpoint = `/v1/data/messages/groups/${encodeURIComponent(groupId)}`
  const current = useCallback(() => active.current && owner === getStorageReadIdentity(), [owner])
  useEffect(() => {
    let alive = true
    active.current = true
    dialog.current?.showModal()
    const stop = subscribeStorageReadIdentity(() => { if (owner !== getStorageReadIdentity()) close.current() })
    const abort = new AbortController()
    const timeout = setTimeout(() => abort.abort(), 20000)
    void (async () => {
      try {
        requireStorageReadIdentity(owner)
        const saved = loadGroupVoiceIntent(owner, groupId)
        intent.current = saved
        if (saved) { setMode(saved.mode); setSelected(new Set(saved.recipientIds)); setPending(true) }
        const result = await withAbort(fallbackApiJson<GroupVoiceRoster>(endpoint + '/voice-room-invitees', { expectedIdentity: owner, signal: abort.signal }), abort.signal)
        if (alive && current()) {
          setRoster(result)
          setError(saved ? 'Hay una invitación pendiente de confirmar. Reintenta para recuperar la misma sala.' : '')
        }
      } catch { if (alive && current()) setError('No se pudieron cargar los integrantes. Cierra y vuelve a intentar.') }
      finally { clearTimeout(timeout); if (alive && current()) setLoading(false) }
    })()
    return () => { alive = false; active.current = false; clearTimeout(timeout); abort.abort(); controller.current?.abort(); stop() }
  }, [current, endpoint, groupId, owner])
  const recipients = pending && intent.current ? intent.current.recipientIds : roster ? groupVoiceRecipients(roster, mode, selected) : []
  const editable = !loading && !busy && !pending
  async function create() {
    if (!roster || sending.current || !recipients.length) return
    sending.current = true; setBusy(true); setError('')
    const abort = new AbortController(); controller.current = abort
    const timeout = setTimeout(() => abort.abort(), 20000)
    try {
      requireStorageReadIdentity(owner)
      const attempt = intent.current ?? { requestId: crypto.randomUUID(), mode, recipientIds: recipients }
      // Retry durability before each HTTP send, including a failed previous storage write.
      saveGroupVoiceIntent(owner, groupId, attempt)
      intent.current = attempt; setPending(true)
      const result = await withAbort(fallbackApiJson<{ roomId: string; invitedCount: number }>(endpoint + '/voice-rooms', {
        method: 'POST', body: JSON.stringify(attempt), expectedIdentity: owner, signal: abort.signal,
      }), abort.signal)
      requireStorageReadIdentity(owner)
      try { clearGroupVoiceIntent(owner, groupId, attempt.requestId) } catch { /* ACK is authoritative; replay remains safe. */ }
      if (current()) { close.current(); navigate(`/app/voice/${encodeURIComponent(result.roomId)}?join=1`) }
    } catch (caught) {
      if (!current()) return
      const code = caught instanceof Error ? caught.message : ''
      if (['GROUP_VOICE_MEMBERS_CHANGED', 'GROUP_VOICE_ROOM_ENDED', 'GROUP_VOICE_CREATE_LIMIT'].includes(code)) {
        try {
          if (intent.current) clearGroupVoiceIntent(owner, groupId, intent.current.requestId)
          intent.current = null; setPending(false)
        } catch { /* Keep the original immutable request when cleanup fails. */ }
      }
      setError(code === 'GROUP_VOICE_MEMBERS_CHANGED' ? 'Cambiaron los integrantes. Cierra y vuelve a abrir para revisar la invitación.'
        : code === 'GROUP_VOICE_MEMBERSHIP_REQUIRED' ? 'Debes seguir siendo integrante de este grupo.'
          : code === 'GROUP_VOICE_ROOM_ENDED' ? 'La sala ya terminó. Puedes crear otra.'
            : code === 'GROUP_VOICE_CREATE_LIMIT' ? 'Has creado varias salas. Espera unos minutos.'
              : 'No se pudo confirmar la sala. Reintenta para recuperar la misma invitación, sin duplicarla.')
    } finally { clearTimeout(timeout); sending.current = false; if (current()) setBusy(false) }
  }
  return <dialog ref={dialog} className="group-voice-dialog surface-card" aria-labelledby="group-voice-title" onCancel={event => { event.preventDefault(); onClose() }}>
    <header><Mic aria-hidden="true" /><h2 id="group-voice-title">Crear sala de voz</h2></header>
    <p>{roster?.groupName ?? 'Invita a los integrantes del grupo'}</p>
    <p className="muted-line">Recibirán una notificación y decidirán si entran. No se realizará ninguna llamada.</p>
    <fieldset disabled={!editable}><legend>¿A quién quieres invitar?</legend>
      <label><input type="radio" name="group-voice-mode" checked={mode === 'all'} onChange={() => setMode('all')} />Incluir a todos</label>
      <label><input type="radio" name="group-voice-mode" checked={mode === 'selected'} onChange={() => setMode('selected')} />Seleccionar integrantes</label>
    </fieldset>
    {loading ? <p role="status">Cargando integrantes…</p> : null}
    {mode === 'selected' ? <>
      <button type="button" className="ghost-btn-sm" disabled={!editable} onClick={() => setSelected(selected.size === roster?.members.length ? new Set() : new Set(roster?.members.map(member => member.id)))}>
        {selected.size === roster?.members.length ? 'Deseleccionar a todos' : 'Seleccionar a todos'}
      </button>
      <ul className="group-voice-members">{roster?.members.map(member => <li key={member.id}><label>
        <SafeAvatar url={member.avatarUrl} name={member.name} size="sm" /><span>{member.name}</span>
        <input type="checkbox" disabled={!editable} checked={selected.has(member.id)} onChange={() => setSelected(previous => {
          const next = new Set(previous); if (next.has(member.id)) next.delete(member.id); else next.add(member.id); return next
        })} />
      </label></li>)}</ul>
    </> : null}
    <p role="status">{recipients.length} integrantes recibirán la invitación</p>
    {!loading && roster?.members.length === 0 ? <p>No hay otros integrantes a quienes invitar.</p> : null}
    {error ? <p role="alert">{error}</p> : null}
    <footer><button type="button" className="ghost-btn" onClick={onClose}>Cancelar</button>
      <button type="button" className="primary-btn" disabled={loading || busy || !recipients.length} onClick={() => void create()}>
        {busy ? 'Creando…' : pending ? 'Confirmar invitación pendiente' : 'Crear sala de voz'}
      </button></footer>
  </dialog>
}
