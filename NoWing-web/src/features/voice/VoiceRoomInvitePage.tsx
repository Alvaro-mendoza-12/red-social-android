import { useEffect, useRef } from 'react'
import { Link, Navigate, useParams } from 'react-router-dom'
import { voiceRoomAndroidIntent, voiceRoomId, voiceRoomWebPath } from '../../core/voiceRoomLinks'
import { StatusView } from '../../components/StatusView'

/** No account, title, password or membership is disclosed by the public invitation. */
export function VoiceRoomInvitePage() {
  const { roomId } = useParams()
  const id = voiceRoomId(roomId)
  const attempted = useRef(false)
  const android = /Android/i.test(navigator.userAgent)
  useEffect(() => {
    if (!id || !android || attempted.current) return
    attempted.current = true
    window.location.replace(voiceRoomAndroidIntent(id))
  }, [android, id])
  if (!id) return <StatusView title="Enlace de sala inválido" description="Pide un enlace nuevo a quien te invitó." />
  if (!android) return <Navigate to={voiceRoomWebPath(id)} replace />
  return <section className="section-stack" style={{ maxWidth: 560, margin: '48px auto', padding: 24 }}>
    <h1>Te invitaron a una sala de voz</h1>
    <p>Abre Novvin o continúa en el navegador. Al iniciar sesión conservarás esta sala.</p>
    <a className="primary-btn" href={voiceRoomAndroidIntent(id)}>Abrir en la app</a>
    <Link className="ghost-btn" to={voiceRoomWebPath(id)}>Continuar en la web</Link>
  </section>
}
