import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { InteractiveMediaSession } from '../core/interactiveMediaSession'
export function MediaSessionConflict({ session }: { session: InteractiveMediaSession }) {
  const navigate = useNavigate(), [busy, setBusy] = useState(false), [error, setError] = useState('')
  return <aside className="media-session-conflict" role="status"><strong>{session.kind === 'live' ? 'Tienes un directo activo' : 'Estás en una sala de voz'}</strong><p>{session.title}. Cierra esa sesión antes de entrar a otra para mantener separados el audio y el micrófono.</p><button type="button" className="primary-btn" onClick={() => navigate(session.path)}>Volver a la sesión</button><button type="button" className="ghost-btn" disabled={busy} onClick={() => { setBusy(true); void session.close().catch(() => setError('No se pudo cerrar. Vuelve a la sesión para intentarlo de nuevo.')).finally(() => setBusy(false)) }}>{session.kind === 'live' && session.host ? 'Finalizar directo' : 'Salir de la sesión'}</button>{error ? <p role="alert">{error}</p> : null}</aside>
}
