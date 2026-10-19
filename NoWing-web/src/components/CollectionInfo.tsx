import { useEffect, useState } from 'react'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { searchFallbackProfiles } from '../core/fallbackData'
import { textOf, friendlyError, type GenericRow } from '../core/utils'
import { SafeAvatar } from './SafeAvatar'
import { QrCodeTile } from './QrCodeTile'
import { ForwardMessagesDialog } from './ForwardMessagesDialog'

type Participant = { id: string; name: string; avatarUrl: string; role: string; username?: string }
type Policy = { membersCanAdd: boolean; canAddMembers: boolean; canManagePolicy: boolean; canAssignRoles: boolean; onlyAdminsCanTalk: boolean; weeklyMessageLimit: number }
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
  const [adminsOnly, setAdminsOnly] = useState(true)
  const [weeklyLimit, setWeeklyLimit] = useState('10')
  const [historyFrom, setFrom] = useState('')
  const [historyUntil, setUntil] = useState('')
  const [shareLink, setShareLink] = useState(''), [shareInApp, setShareInApp] = useState(false), [copied, setCopied] = useState(false)
  async function invite() {
    setBusy(true); setError(''); setCopied(false)
    try {
      const result = await fallbackApiJson<{ token: string }>(base + '/invites', { method: 'POST', body: JSON.stringify({ expiresInHours: 72, maxUses: 100 }) })
      if (!/^[\w-]{32,200}$/.test(result.token)) throw new Error('No se pudo crear una invitación válida.')
      setShareLink(`${window.location.origin}/${scope}/${encodeURIComponent(roomId)}?invite=${encodeURIComponent(result.token)}`)
    } catch (caught) { setError(friendlyError(caught, 'No se pudo crear la invitación. Comprueba si el administrador permite añadir integrantes.')) }
    finally { setBusy(false) }
  }
  useEffect(() => {
    if (!isMember) return
    let active = true
    void fallbackApiJson<Policy>(base + '/membership-policy').then(value => {
      if (active) { setPolicy(value); setAdminsOnly(value.onlyAdminsCanTalk); setWeeklyLimit(String(value.weeklyMessageLimit || 0)) }
    }).catch(() => { if (active) setError('No se pudo cargar la configuración.') })
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
      {isMember ? <section className="collection-invitation">
        <button type="button" className="ghost-btn" disabled={busy} onClick={() => void invite()}>Compartir enlace o QR</button>
        {shareLink ? <><QrCodeTile value={shareLink} title={`Invitación a ${title}`} description="Caduca en 72 horas. La persona verá la invitación antes de unirse." compact downloadable />
          <div className="row-actions"><button type="button" className="mini-action-btn" onClick={() => void navigator.clipboard.writeText(shareLink).then(() => setCopied(true)).catch(() => setError('El navegador no permitió copiar. Usa Compartir enlace.'))}>{copied ? 'Copiado' : 'Copiar enlace'}</button>
          <button type="button" className="mini-action-btn" onClick={() => setShareInApp(true)}>Enviar en Novvin</button>
          {typeof navigator.share === 'function' ? <button type="button" className="mini-action-btn" onClick={() => void navigator.share({ title, url: shareLink }).catch(() => {})}>Compartir enlace</button> : null}</div>
          {policy?.canManagePolicy ? <button type="button" className="mini-action-btn" disabled={busy} onClick={() => {
            if (window.confirm('¿Revocar todos los enlaces y códigos anteriores? Los integrantes actuales conservan su acceso.')) void act('/invites/revoke', {}).then(ok => { if (ok) setShareLink('') })
          }}>Revocar invitaciones anteriores</button> : null}
        </> : null}
      </section> : null}
      {shareInApp && shareLink ? <ForwardMessagesDialog mode="share" messages={[{ id: 'collection-invitation', senderId: '', content: `Te invito a ${title}:\n${shareLink}`, contentType: 'text', mediaUrl: null, deletedForAll: false }]} onClose={() => setShareInApp(false)} /> : null}
      {participants.map(member => <div className="collection-member" key={member.id}>
        <SafeAvatar url={member.avatarUrl} name={member.name} />
        <span><strong>{member.name}</strong><small>{member.username ? `@${member.username} · ` : ''}{['owner', 'creator', 'admin'].includes(member.role) ? 'Administrador' : 'Miembro'}</small></span>
        {scope === 'community' && policy?.canAssignRoles && !['owner', 'creator'].includes(member.role) ? <button type="button" className="mini-action-btn" disabled={busy} onClick={() => void act('/join', { userId: member.id, role: member.role === 'admin' ? 'member' : 'admin' })}>{member.role === 'admin' ? 'Quitar administración' : 'Hacer administrador'}</button> : null}
        {policy?.canManagePolicy && !['owner', 'creator'].includes(member.role) ? <button type="button" className="mini-action-btn" disabled={busy} onClick={() => void act('/leave', { userId: member.id })}>Expulsar</button> : null}
      </div>)}
      {scope === 'community' && policy?.canManagePolicy ? <section className="collection-policy-settings">
        <h3>Publicación de mensajes</h3>
        <label className="collection-policy"><input type="checkbox" checked={adminsOnly} disabled={busy} onChange={event => {
          setAdminsOnly(event.target.checked)
          if (!event.target.checked && weeklyLimit === '0') setWeeklyLimit('10')
        }} /> Solo los administradores pueden escribir</label>
        {!adminsOnly ? <label>Mensajes por integrante a la semana <input className="text-input" type="number" min={0} max={10000} step={1} value={weeklyLimit} onChange={event => setWeeklyLimit(event.target.value)} /></label> : null}
        <p className="muted-line">0 permite mensajes sin límite. El cupo se renueva el lunes a las 00:00 UTC y los administradores están exentos. Los integrantes pueden reaccionar y responder preguntas.</p>
        <button type="button" className="primary-btn" disabled={busy || !/^\d+$/.test(weeklyLimit) || Number(weeklyLimit) > 10000} onClick={() => void act('/update', { only_admins_can_talk: adminsOnly, weekly_message_limit: Number(weeklyLimit) })}>{busy ? 'Guardando…' : 'Guardar permisos'}</button>
      </section> : null}
      {policy?.canManagePolicy ? <label className="collection-policy">
        <input type="checkbox" checked={policy.membersCanAdd} disabled={busy} onChange={async event => {
          const enabled = event.target.checked
          try {
            const next = await fallbackApiJson<Policy>(base + '/membership-policy', { method: 'PATCH', body: JSON.stringify({ membersCanAdd: enabled }) })
            setPolicy(next)
          } catch { setError('No se pudo guardar la configuración.') }
      }} /> Permitir que los miembros añadan personas directamente
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
