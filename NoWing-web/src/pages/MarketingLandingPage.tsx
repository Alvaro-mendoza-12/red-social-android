import { StreamingImage } from '../components/StreamingMedia'
import { useState } from 'react'
import { Link } from 'react-router-dom'

const PLAY_STORE_URL = 'https://play.google.com/store/apps/details?id=conectate.com'
const WEB_APP_URL = 'https://red-social-android.vercel.app'

export function MarketingLandingPage() {
  const [shareStatus, setShareStatus] = useState<string | null>(null)

  async function shareNovvin() {
    const shareData = {
      title: 'Novvin',
      text: 'Prueba Novvin: videos, chats, comunidades y salas de voz en un solo lugar.',
      url: PLAY_STORE_URL,
    }
    try {
      if (navigator.share) {
        await navigator.share(shareData)
        setShareStatus('Compartido')
      } else {
        await navigator.clipboard.writeText(PLAY_STORE_URL)
        setShareStatus('Enlace copiado')
      }
      window.setTimeout(() => setShareStatus(null), 2500)
    } catch {
      setShareStatus(null)
    }
  }

  return (
    <main className="marketing-page">
      <nav className="marketing-nav" aria-label="Navegación principal">
        <Link className="marketing-brand" to="/">
          <StreamingImage src="/novvin-logo.png" alt="Novvin" />
          <span>Novvin</span>
        </Link>
        <div className="marketing-nav-actions">
          <Link className="marketing-text-link" to="/login">Iniciar sesión</Link>
          <Link className="marketing-outline-btn" to="/register">Crear cuenta</Link>
        </div>
      </nav>

      <section className="marketing-hero" aria-labelledby="marketing-title">
        <div className="marketing-hero-copy">
          <span className="marketing-kicker">La red social para conectar de verdad</span>
          <h1 id="marketing-title">Tus personas, tus rondas, tu mundo.</h1>
          <p>
            Comparte videos, conversa por chat o voz y encuentra comunidades para pasar de
            descubrir a participar en un solo lugar.
          </p>
          <div className="marketing-cta-row">
            <a className="marketing-primary-btn" href={PLAY_STORE_URL} target="_blank" rel="noreferrer">
              Descargar en Google Play
            </a>
            <Link className="marketing-secondary-btn" to="/register">Probar en la web</Link>
            <button type="button" className="marketing-share-btn" onClick={() => void shareNovvin()}>
              {shareStatus || 'Compartir Novvin'}
            </button>
          </div>
          <p className="marketing-note">Gratis para empezar · Android y navegador · Comunidad en crecimiento</p>
        </div>
        <div className="marketing-hero-card" aria-label="Funciones de Novvin">
          <div className="marketing-orb marketing-orb-one" />
          <div className="marketing-orb marketing-orb-two" />
          <div className="marketing-phone-card">
            <div className="marketing-phone-head">
              <StreamingImage src="/novvin-logo.png" alt="" />
              <div><strong>Novvin</strong><span>Conecta y comparte</span></div>
            </div>
            <div className="marketing-feature-row"><span>🎬</span><div><strong>Videos</strong><small>Descubre y participa</small></div></div>
            <div className="marketing-feature-row"><span>💬</span><div><strong>Mensajes</strong><small>Chats, grupos y comunidades</small></div></div>
            <div className="marketing-feature-row"><span>🎙️</span><div><strong>Voz en vivo</strong><small>Conversa en tiempo real</small></div></div>
            <div className="marketing-feature-row"><span>✨</span><div><strong>Retos y radar</strong><small>Conoce gente nueva</small></div></div>
          </div>
        </div>
      </section>

      <section className="marketing-proof" aria-label="Por qué Novvin">
        <article><strong>01</strong><h2>Contenido con contexto</h2><p>Publica, comenta y vuelve a la conversación sin perderte.</p></article>
        <article><strong>02</strong><h2>Comunidades vivas</h2><p>Encuentra espacios de voz y grupos alrededor de tus intereses.</p></article>
        <article><strong>03</strong><h2>Un inicio sencillo</h2><p>Crea tu cuenta gratis y empieza desde tu teléfono o navegador.</p></article>
      </section>

      <section className="marketing-final-cta">
        <div>
          <span className="marketing-kicker">Únete a la beta pública</span>
          <h2>Ayúdanos a construir la siguiente gran comunidad.</h2>
          <p>Descarga Novvin, crea tu perfil y comparte tu primera publicación.</p>
        </div>
        <a className="marketing-primary-btn" href={PLAY_STORE_URL} target="_blank" rel="noreferrer">Descargar Novvin</a>
      </section>

      <footer className="marketing-footer">
        <span>© {new Date().getFullYear()} Novvin</span>
        <span><a href={WEB_APP_URL}>Web</a> · <a href={PLAY_STORE_URL} target="_blank" rel="noreferrer">Google Play</a></span>
      </footer>
    </main>
  )
}
