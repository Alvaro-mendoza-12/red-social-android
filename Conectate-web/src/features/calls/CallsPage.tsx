import { useCallback, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import { CallInterface } from './CallInterface'

const callEntryCards = [
  {
    icon: '📞',
    title: 'Llamada 1:1',
    description: 'Abre una llamada directa y prepara audio o video desde este navegador.',
    action: 'Iniciar ahora',
  },
  {
    icon: '🧭',
    title: 'Volver a mensajes',
    description: 'Arranca la llamada desde tu conversación activa si ya tienes el contexto listo.',
    action: 'Ir a mensajes',
  },
  {
    icon: '🎙️',
    title: 'Salas de voz',
    description: 'Explora salas persistentes estilo comunidad y súmate al canal correcto.',
    action: 'Abrir salas',
  },
]

const readinessChecklist = [
  'Permite micrófono y cámara sólo cuando vayas a iniciar la llamada.',
  'Usa auriculares si compartes espacio o quieres evitar eco.',
  'Si tu conexión está inestable, mantén desactivado el video al comenzar.',
  'El flujo más estable sigue siendo iniciar desde una conversación real, igual que en Android.',
]

export function CallsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const initialCallId = searchParams.get('callId')?.trim() ?? ''
  const initialTargetId = searchParams.get('target')?.trim() ?? ''
  const initialTargetName = searchParams.get('name')?.trim() ?? ''
  const initialKind = searchParams.get('kind')?.trim().toLowerCase()
  const initialVideoEnabled = initialKind === 'video'
  const shouldJoinExistingCall = searchParams.get('join') === '1'
  const [activeCall, setActiveCall] = useState(Boolean(initialTargetId || initialCallId))

  const displayName = useMemo(() => {
    const metadataName = String(user?.user_metadata?.display_name ?? user?.user_metadata?.full_name ?? '').trim()
    if (metadataName) return metadataName
    return user?.email?.split('@')[0] ?? 'tu cuenta'
  }, [user?.email, user?.user_metadata?.display_name, user?.user_metadata?.full_name])

  const handleNewCall = useCallback(() => {
    setActiveCall(true)
  }, [])

  if (!user) {
    return (
      <StatusView
        title="Autenticación requerida"
        description="Debes iniciar sesión para hacer llamadas desde la web."
      />
    )
  }

  if (activeCall) {
    return (
      <section className="section-stack">
        <article className="surface-card calls-hero-card">
          <div className="calls-hero-copy">
            <div>
              <span className="brand-tag">WebRTC web</span>
              <h2>Llamada activa</h2>
              <p>Estás usando el módulo web experimental de llamadas para {displayName}.</p>
            </div>
            <div className="row-actions">
              <button className="ghost-btn" type="button" onClick={() => setActiveCall(false)}>
                Volver al hub
              </button>
              <Link className="ghost-btn" to="/app/messages">
                Abrir mensajes
              </Link>
            </div>
          </div>
        </article>

        <section className="calls-live-shell">
          <CallInterface
            initialCallId={initialCallId}
            initialRemoteUserId={initialTargetId}
            initialRemoteName={initialTargetName}
            initialVideoEnabled={initialVideoEnabled}
            joinExisting={shouldJoinExistingCall}
          />
        </section>
      </section>
    )
  }

  return (
    <section className="section-stack">
      <article className="surface-card calls-hero-card">
        <div className="calls-hero-copy">
          <div>
            <span className="brand-tag">Llamadas</span>
            <h2>Audio y video conectados al resto del producto</h2>
            <p>
              Esta entrada deja de vivir aislada: ahora se integra a la shell web y mantiene el
              mismo lenguaje que mensajes, salas de voz y perfil.
            </p>
          </div>
          <div className="calls-hero-stats">
            <div className="calls-hero-stat">
              <strong>1:1</strong>
              <span>audio y video</span>
            </div>
            <div className="calls-hero-stat">
              <strong>WebRTC</strong>
              <span>base actual</span>
            </div>
            <div className="calls-hero-stat">
              <strong>App + Web</strong>
              <span>mismo backend</span>
            </div>
          </div>
        </div>
      </article>

      <div className="calls-dashboard-grid">
        <section className="calls-action-grid">
          {callEntryCards.map((card) => (
            <article key={card.title} className="surface-card calls-action-card">
              <div className="calls-action-icon">{card.icon}</div>
              <div className="section-stack calls-action-copy">
                <div>
                  <h3>{card.title}</h3>
                  <p>{card.description}</p>
                </div>
                {card.title === 'Llamada 1:1' ? (
                  <button className="primary-btn" type="button" onClick={handleNewCall}>
                    {card.action}
                  </button>
                ) : card.title === 'Volver a mensajes' ? (
                  <button className="ghost-btn" type="button" onClick={() => navigate('/app/messages')}>
                    {card.action}
                  </button>
                ) : (
                  <button className="ghost-btn" type="button" onClick={() => navigate('/app/voice')}>
                    {card.action}
                  </button>
                )}
              </div>
            </article>
          ))}
        </section>

        <aside className="surface-card calls-side-panel">
          <div className="section-stack">
            <div>
              <h3>Checklist rápida</h3>
              <p>Antes de marcar, deja el navegador listo para una sesión limpia.</p>
            </div>
            <div className="calls-checklist">
              {readinessChecklist.map((item) => (
                <div key={item} className="calls-check-item">
                  <span className="calls-check-dot" />
                  <p>{item}</p>
                </div>
              ))}
            </div>
          </div>
        </aside>
      </div>
    </section>
  )
}
