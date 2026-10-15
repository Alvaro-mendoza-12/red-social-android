import { useCallback, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Headphones, MessageCircle, PhoneCall } from 'lucide-react'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import { CallInterface } from './CallInterface'

type CallEntryIcon = 'call' | 'messages' | 'voice'

const callEntryCards = [
  {
    icon: 'call' as CallEntryIcon,
    title: 'Llamada 1:1',
    description: 'Abre una llamada directa y prepara audio o video desde este navegador.',
    action: 'Iniciar ahora',
  },
  {
    icon: 'messages' as CallEntryIcon,
    title: 'Volver a mensajes',
    description: 'Arranca la llamada desde tu conversación activa si ya tienes el contexto listo.',
    action: 'Ir a mensajes',
  },
  {
    icon: 'voice' as CallEntryIcon,
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

function CallEntryIconView({ name }: { name: CallEntryIcon }) {
  if (name === 'messages') return <MessageCircle aria-hidden="true" size={26} />
  if (name === 'voice') return <Headphones aria-hidden="true" size={26} />
  return <PhoneCall aria-hidden="true" size={26} />
}

export function CallsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const initialContextId = searchParams.get('contextId')?.trim() ?? ''
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
              <p>Sesión en curso para {displayName}. Mantén mensajes y voz a un toque de distancia.</p>
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
            initialContextId={initialContextId}
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
            <h2>Habla ahora, vuelve al chat después</h2>
            <p>
              Inicia llamadas 1:1, salta a salas de voz o vuelve a mensajes cuando necesites
              contexto.
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
              <strong>Móvil + web</strong>
              <span>misma cuenta</span>
            </div>
          </div>
        </div>
      </article>

      <div className="calls-dashboard-grid">
        <section className="calls-action-grid">
          {callEntryCards.map((card) => (
            <article key={card.title} className="surface-card calls-action-card">
              <div className="calls-action-icon">
                <CallEntryIconView name={card.icon} />
              </div>
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
