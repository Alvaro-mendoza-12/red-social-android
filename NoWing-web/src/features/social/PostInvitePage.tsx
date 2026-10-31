import { Link, Navigate, useParams } from 'react-router-dom'
import { sharedPostAndroidIntent, sharedPostId, sharedPostWebPath } from '../../core/postShareLinks'
import { StatusView } from '../../components/StatusView'

/** Private post details are fetched only after authentication and permission checks. */
export function PostInvitePage() {
  const { postId } = useParams()
  const id = sharedPostId(postId)
  const android = /Android/i.test(navigator.userAgent)
  if (!id) return <StatusView title="Enlace de publicación inválido" description="Pide un enlace nuevo a quien lo compartió." />
  if (!android) return <Navigate to={sharedPostWebPath(id)} replace />
  return <section className="section-stack" style={{ maxWidth: 560, margin: '48px auto', padding: 24 }}>
    <h1>Te compartieron una publicación</h1>
    <p>Abre Novvin o continúa en el navegador. Si necesitas iniciar sesión, volverás a esta publicación.</p>
    <a className="primary-btn" href={sharedPostAndroidIntent(id)}>Abrir en la app</a>
    <Link className="ghost-btn" to={sharedPostWebPath(id)}>Continuar en la web</Link>
  </section>
}
