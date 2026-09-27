import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { useAuth } from '../../core/auth/AuthProvider'
import './spaces.css'

type SpaceKind = 'group' | 'community' | 'voice'
const labels: Record<SpaceKind, string> = { group: 'Grupo', community: 'Comunidad', voice: 'Sala de voz' }
function initialKind(value: string | null): SpaceKind { return value === 'community' || value === 'voice' ? value : 'group' }

export function CreateSpacePage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const { isFallbackSession } = useAuth()
  const [kind, setKind] = useState<SpaceKind>(() => initialKind(params.get('type')))
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState('')
  const [isPublic, setIsPublic] = useState(true)
  const [roomMode, setRoomMode] = useState<'casual' | 'stage'>('casual')
  const [maxParticipants, setMaxParticipants] = useState(50)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function create(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const cleanName = name.trim()
    if (!cleanName) { setError('Escribe un nombre para el espacio.'); return }
    if (kind === 'voice' && !isPublic && password.length < 4) { setError('La contraseña de la sala privada debe tener al menos 4 caracteres.'); return }
    setBusy(true); setError('')
    try {
      if (kind === 'voice') {
        const result = await fallbackApiJson<{ room: { id: string } }>('/v1/data/voice/rooms', {
          method: 'POST',
          body: JSON.stringify({ name: cleanName, description: description.trim(), isPublic, maxParticipants, roomMode, password: isPublic ? '' : password }),
        })
        if (!result.room?.id) throw new Error('CREATE_FAILED')
        navigate(`/app/voice/${encodeURIComponent(result.room.id)}`, { replace: true })
      } else {
        const endpoint = kind === 'group' ? 'groups' : 'communities'
        const result = await fallbackApiJson<{ id: string }>(`/v1/data/messages/${endpoint}`, {
          method: 'POST',
          body: JSON.stringify({ name: cleanName, description: description.trim(), category: kind === 'community' ? category.trim() : null, isPublic: kind === 'community' ? isPublic : false }),
        })
        if (!result.id) throw new Error('CREATE_FAILED')
        navigate(`/app/messages/${kind}/${encodeURIComponent(result.id)}`, { replace: true })
      }
    } catch (caught) {
      const code = caught instanceof Error ? caught.message : ''
      setError(code === 'NAME_REQUIRED' ? 'Escribe un nombre para el espacio.' : code === 'VOICE_ROOM_PASSWORD_REQUIRED' ? 'Agrega una contraseña para la sala privada.' : 'No se pudo crear el espacio. Revisa tu conexión e inténtalo de nuevo.')
    } finally { setBusy(false) }
  }

  if (!isFallbackSession) return <section className="surface-card spaces-state"><h2>Crear espacio</h2><p>Inicia sesión en Novvin para crear grupos, comunidades o salas.</p></section>
  return <section className="spaces-page">
    <div className="surface-card spaces-intro"><h2>Un lugar para reunirse</h2><p>Elige el tipo de espacio y completa lo esencial. Puedes volver a Mensajes o Voz en cualquier momento.</p></div>
    <div className="spaces-kind-picker" aria-label="Tipo de espacio">{(['group', 'community', 'voice'] as const).map((value) => <button type="button" key={value} className={kind === value ? 'spaces-kind active' : 'spaces-kind'} aria-pressed={kind === value} onClick={() => { setKind(value); setError('') }}>{labels[value]}</button>)}</div>
    <form className="surface-card spaces-form" onSubmit={(event) => void create(event)}>
      <h3>Nuevo {labels[kind].toLowerCase()}</h3>
      <label>Nombre<input className="text-input" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} placeholder={kind === 'voice' ? 'Charla de la comunidad' : 'Nombre del espacio'} /></label>
      <label>Descripción<textarea className="text-input" maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} placeholder="¿De qué se hablará aquí?" /></label>
      {kind === 'community' && <label>Categoría<input className="text-input" maxLength={80} value={category} onChange={(event) => setCategory(event.target.value)} placeholder="Música, juegos, deportes..." /></label>}
      {kind !== 'group' && <label className="spaces-check"><input type="checkbox" checked={isPublic} onChange={(event) => setIsPublic(event.target.checked)} /> Espacio público</label>}
      {kind === 'voice' && <div className="spaces-extra"><label>Modo<select className="text-input" value={roomMode} onChange={(event) => setRoomMode(event.target.value as 'casual' | 'stage')}><option value="casual">Conversación libre</option><option value="stage">Escenario</option></select></label><label>Máximo de participantes<input className="text-input" type="number" min="2" max="200" value={maxParticipants} onChange={(event) => setMaxParticipants(Number(event.target.value))} /></label>{!isPublic && <label>Contraseña de la sala<input className="text-input" type="password" minLength={4} maxLength={128} required value={password} onChange={(event) => setPassword(event.target.value)} /></label>}</div>}
      {kind === 'group' && <p className="spaces-hint">Los grupos son privados. Podrás compartirlos después de crearlos.</p>}
      {error && <p className="spaces-error" role="alert">{error}</p>}
      <div className="spaces-actions"><button className="primary-btn" type="submit" disabled={busy || !name.trim()}>{busy ? 'Creando...' : `Crear ${labels[kind].toLowerCase()}`}</button><Link className="ghost-btn" to={kind === 'voice' ? '/app/voice' : '/app/messages'}>Cancelar</Link></div>
    </form>
  </section>
}
