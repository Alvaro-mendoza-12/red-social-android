import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import { friendlyError } from '../../core/utils'
import { getOrCreateDirectChat } from '../messages/directChat'

export function ProfileChatRedirectPage() {
  const { loading, user } = useAuth()
  const navigate = useNavigate()
  const { profileId = '' } = useParams()
  const targetProfileId = useMemo(() => profileId.trim(), [profileId])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (loading || !user || !targetProfileId) return
    let cancelled = false
    const currentUser = user

    async function openChat() {
      try {
        if (currentUser.id === targetProfileId) {
          navigate('/app/profile', { replace: true })
          return
        }
        const chatId = await getOrCreateDirectChat(currentUser.id, targetProfileId)
        if (!cancelled) navigate(`/app/messages/${chatId}`, { replace: true })
      } catch (caught) {
        if (!cancelled) setError(friendlyError(caught, 'No se pudo abrir el chat desde el QR.'))
      }
    }

    void openChat()
    return () => {
      cancelled = true
    }
  }, [loading, navigate, targetProfileId, user])

  if (loading) return <StatusView title="Abriendo chat" description="Verificando sesión..." />

  if (!targetProfileId) {
    return <StatusView title="QR incompleto" description="Este enlace no incluye un perfil válido." />
  }

  if (!user) {
    return (
      <section className="section-stack public-link-shell">
        <StatusView
          title="Inicia sesión para chatear"
          description="El perfil se puede ver público, pero el chat directo necesita una cuenta activa."
        />
        <div className="row-actions center">
          <Link className="primary-btn" to={`/login?next=/chat/${encodeURIComponent(targetProfileId)}`}>
            Iniciar sesión
          </Link>
          <Link className="ghost-btn" to={`/u/${encodeURIComponent(targetProfileId)}`}>
            Ver perfil
          </Link>
        </div>
      </section>
    )
  }

  if (error) {
    return (
      <section className="section-stack public-link-shell">
        <StatusView title="Chat no disponible" description={error} />
        <div className="row-actions center">
          <Link className="ghost-btn" to={`/u/${encodeURIComponent(targetProfileId)}`}>
            Ver perfil
          </Link>
        </div>
      </section>
    )
  }

  return <StatusView title="Abriendo chat" description="Preparando conversación directa..." />
}
