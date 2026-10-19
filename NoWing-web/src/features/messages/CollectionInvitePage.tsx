import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { useAuth } from '../../core/auth/AuthProvider'
import { friendlyError } from '../../core/utils'
import { SafeAvatar } from '../../components/SafeAvatar'

type Preview = { name: string; description: string; imageUrl: string; memberCount: number; isMember: boolean }
export function CollectionInvitePage({ kind }: { kind: 'group' | 'community' }) {
  const { roomId = '' } = useParams(), [query] = useSearchParams(), navigate = useNavigate(), { user } = useAuth()
  const token = query.get('invite') || ''
  const collection = kind === 'group' ? 'groups' : 'communities'
  const base = `/v1/data/messages/${collection}/${encodeURIComponent(roomId)}`
  const [preview, setPreview] = useState<Preview | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const valid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(roomId) && (kind !== 'group' || /^[\w-]{32,200}$/.test(token)) && (!token || /^[\w-]{32,200}$/.test(token))
  useEffect(() => {
    let active = true
    setPreview(null); setError('')
    if (!valid) { setError('La invitación está incompleta o no es válida.'); return }
    void fallbackApiJson<Preview>(base + '/preview' + (token ? `?token=${encodeURIComponent(token)}` : '')).then(value => { if (active) setPreview(value) })
      .catch(caught => { if (active) setError(friendlyError(caught, 'La invitación caducó o dejó de estar disponible. Pide un enlace nuevo.')) })
    return () => { active = false }
  }, [base, token, user?.id, valid])
  async function enter() {
    if (!preview || busy) return
    setBusy(true); setError('')
    try {
      if (!preview.isMember) await fallbackApiJson(base + (token ? '/join-invite' : '/join'), { method: 'POST', body: JSON.stringify(token ? { token } : {}), expectedAccountId: user?.id })
      navigate(`/app/messages/${kind}/${encodeURIComponent(roomId)}`, { replace: true })
    } catch (caught) { setError(friendlyError(caught, 'No se pudo completar la entrada. Pide una invitación nueva.')) }
    finally { setBusy(false) }
  }
  return <main className="section-stack" style={{ maxWidth: 480, margin: '48px auto', padding: 20 }}>
    <Link to="/app/messages">← Mis conversaciones</Link>
    <section className="surface-card" style={{ padding: 24 }} aria-label="Invitación">
      <h1>Invitación a {kind === 'group' ? 'un grupo' : 'una comunidad'}</h1>
      {preview ? <><SafeAvatar url={preview.imageUrl} name={preview.name} /><h2>{preview.name}</h2><p>{preview.description}</p><p>{preview.memberCount} integrantes</p><p className="muted-line">Al unirte, los integrantes podrán ver tu perfil. La entrada respeta los permisos y el historial disponible de la sala.</p><button className="primary-btn" type="button" disabled={busy} onClick={() => void enter()}>{busy ? 'Entrando…' : preview.isMember ? 'Abrir conversación' : 'Unirme'}</button></> : !error ? <p role="status">Cargando invitación…</p> : null}
      {error ? <p role="alert" className="error-text">{error}</p> : null}
    </section>
  </main>
}
