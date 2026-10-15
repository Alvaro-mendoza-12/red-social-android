import { useCallback, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Headphones, MessageCircle, PhoneCall } from 'lucide-react'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import { CallInterface } from './CallInterface'
import './calls.css'

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
      <section className="section-stack calls-page">
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
    <section className="section-stack calls-page">
      <header className="section-header"><h2>Llamadas</h2><p>Habla con tus contactos por voz o video.</p></header>

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


      </div>
    </section>
  )
}
