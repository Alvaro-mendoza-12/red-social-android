import { Link } from 'react-router-dom'
import './help.css'

const topics = [
  { title: '¿Cómo encuentro personas?', answer: 'Usa Buscar para encontrar perfiles. En Juegos puedes filtrar por juego y plataforma; en Personas cerca puedes activar el radar si autorizas la ubicación.', path: '/app/search', action: 'Abrir Buscar' },
  { title: '¿Cómo creo una publicación?', answer: 'En Social abre la sección Publicaciones y usa Nueva publicación. Revisa el texto y los archivos antes de compartir.', path: '/app/social?tab=posts&compose=1', action: 'Crear publicación' },
  { title: '¿Cómo protejo mi cuenta?', answer: 'En Ajustes revisa privacidad y seguridad. Puedes cambiar tu contraseña y elegir qué datos de perfil compartes.', path: '/app/settings', action: 'Abrir Ajustes' },
  { title: '¿Cómo reporto contenido o una persona?', answer: 'Abre la opción Reportar desde el contenido o perfil. Después consulta el estado en Mis reportes.', path: '/app/reports', action: 'Ver mis reportes' },
  { title: '¿Cómo empiezo en Juegos?', answer: 'Configura tu perfil de jugador, confirma tu edad, agrega un juego y decide si quieres aparecer en las búsquedas. La búsqueda de partida requiere un juego y un perfil visible.', path: '/app/games', action: 'Abrir Juegos' },
  { title: '¿Cómo entro a la web con mi móvil?', answer: 'En la pantalla de acceso elige Iniciar sesión con QR. En la app Android abre Vincular web y escanea el código: la cuenta elegida se vinculará automáticamente. El código vence por seguridad.', path: '/app/settings#acceso-qr-ajustes', action: 'Ver acceso y QR' },
]

export function HelpPage() {
  return <section className="help-page">
    <div className="surface-card help-intro"><h2>¿En qué necesitas ayuda?</h2><p>Elige un tema para ver una respuesta y entrar directamente a la función.</p></div>
    <div className="help-list">{topics.map((topic) => <details key={topic.title} className="surface-card help-topic"><summary>{topic.title}</summary><p>{topic.answer}</p><Link className="ghost-btn" to={topic.path}>{topic.action}</Link></details>)}</div>
    <div className="surface-card help-intro"><h3>¿La app muestra un error?</h3><p>Revisa tu conexión y vuelve a intentar la acción. Si persiste, anota qué estabas haciendo y conserva el mensaje de error para soporte.</p></div>
  </section>
}
