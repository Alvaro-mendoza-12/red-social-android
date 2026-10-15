import { useEffect, useState } from 'react'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { searchFallbackProfiles } from '../core/fallbackData'
import { textOf, friendlyError, type GenericRow } from '../core/utils'
import { SafeAvatar } from './SafeAvatar'

type Participant = { id: string; name: string; avatarUrl: string; role: string; username?: string }
type Policy = { membersCanAdd: boolean; canAddMembers: boolean; canManagePolicy: boolean }
export function CollectionInfo({ scope, roomId, title, participants, isMember, onClose, onChanged }: {
  scope: 'group' | 'community'; roomId: string; title: string; participants: Participant[]; isMember: boolean; onClose: () => void; onChanged: () => void
}) {
  const collection = scope === 'group' ? 'groups' : 'communities'
  const base = `/v1/data/messages/${collection}/${encodeURIComponent(roomId)}`
  const [policy, setPolicy] = useState<Policy | null>(null)
  const [query, setQuery] = useState('')
  const [matches, setMatches] = useState<GenericRow[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [historyFrom, setFrom] = useState('')
  const [historyUntil, setUntil] = useState('')
  useEffect(() => {
    if (!isMember) return
    let active = true
    void fallbackApiJson<Policy>(base + '/membership-policy').then(value => { if (active) setPolicy(value) }).catch(() => { if (active) setError('No se pudo cargar la configuración.') })
    return () => { active = false }
  }, [base, isMember])
  useEffect(() => {
    if (!query.trim() || !policy?.canAddMembers) { setMatches([]); return }
    let active = true
    const timer = window.setTimeout(() => {
      void searchFallbackProfiles(query.trim(), 12).then(value => {
        if (active) setMatches(value.items.filter(row => !participants.some(member => member.id === textOf(row, ['id'], ''))))
      }).catch(() => { if (active) setMatches([]) })
    }, 200)
    return () => { active = false; window.clearTimeout(timer) }
  }, [query, policy?.canAddMembers, participants])
  async function act(path: string, body: object, method = 'POST') {
    setBusy(true); setError('')
    try { await fallbackApiJson(base + path, { method, body: JSON.stringify(body) }); onChanged(); return true }
    catch (caught) { setError(friendlyError(caught, 'No se pudo realizar esta acción.')); return false }
    finally { setBusy(false) }
  }
  return <div className="app-dialog-backdrop" role="presentation" onMouseDown={onClose}>
    <section className="surface-card collection-info" role="dialog" aria-modal="true" aria-label={`Información de ${title}`} onMouseDown={event => event.stopPropagation()}>
      <header className="row-between"><h2>{title}</h2><button type="button" className="icon-btn" onClick={onClose} aria-label="Cerrar información">×</button></header>
      {error ? <p role="alert">{error}</p> : null}
      <p>{participants.length} participantes</p>
      {participants.map(member => <div className="collection-member" key={member.id}>
        <SafeAvatar url={member.avatarUrl} name={member.name} />
        <span><strong>{member.name}</strong><small>{member.username ? `@${member.username} · ` : ''}{['owner', 'creator', 'admin'].includes(member.role) ? 'Administrador' : 'Miembro'}</small></span>
        {policy?.canManagePolicy && !['owner', 'creator'].includes(member.role) ? <button type="button" className="mini-action-btn" disabled={busy} onClick={() => void act('/leave', { userId: member.id })}>Expulsar</button> : null}
      </div>)}
      {policy?.canManagePolicy ? <label className="collection-policy">
        <input type="checkbox" checked={policy.membersCanAdd} disabled={busy} onChange={async event => {
          const enabled = event.target.checked
          try {
            const next = await fallbackApiJson<Policy>(base + '/membership-policy', { method: 'PATCH', body: JSON.stringify({ membersCanAdd: enabled }) })
            setPolicy(next)
          } catch { setError('No se pudo guardar la configuración.') }
        }} /> Permitir que los miembros añadan personas
      </label> : null}
      {policy?.canAddMembers ? <section>
        <h3>Añadir personas</h3>
        <input className="text-input" aria-label="Buscar una persona para añadir" placeholder="Nombre o usuario" value={query} onChange={event => setQuery(event.target.value)} />
        <p className="muted-line">Sin compartir historial, la persona verá los mensajes enviados desde su entrada.</p>
        <details><summary>Compartir un periodo del historial</summary>
          <label>Desde <input type="datetime-local" value={historyFrom} onChange={event => setFrom(event.target.value)} /></label>
          <label>Hasta <input type="datetime-local" value={historyUntil} onChange={event => setUntil(event.target.value)} /></label>
        </details>
        {matches.map(person => <button type="button" className="mention-candidate" key={textOf(person, ['id'], '')} disabled={busy} onClick={() => {
          const userId = textOf(person, ['id'], '')
          if ((historyFrom && !historyUntil) || (!historyFrom && historyUntil) || (historyFrom && new Date(historyFrom) > new Date(historyUntil))) { setError('Elige un periodo válido.'); return }
          void act('/join', { userId, ...(historyFrom && historyUntil ? { historyFrom: new Date(historyFrom).toISOString(), historyUntil: new Date(historyUntil).toISOString() } : {}) }).then(success => { if (success) setQuery('') })
        }}><SafeAvatar url={textOf(person, ['avatar_url', 'profile_image'], '')} name={textOf(person, ['display_name', 'full_name'], 'Usuario')} /><span>{textOf(person, ['display_name', 'full_name', 'username'], 'Usuario')}</span></button>)}
      </section> : null}
      {isMember ? <button type="button" className="ghost-btn" disabled={busy} onClick={() => void act('/leave', {}).then(success => { if (success) onClose() })}>Salir del {scope === 'group' ? 'grupo' : 'comunidad'}</button> : <p className="muted-line">Puedes consultar el historial que conservas. Ya no recibes nuevos mensajes.</p>}
    </section>
  </div>
}
