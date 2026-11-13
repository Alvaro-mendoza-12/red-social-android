import { PublicFooter, PublicHeader } from './PublicNavigation'

export function PublicHome() {
  return <div className="public-home public-home-main">
    <a className="public-skip-link" href="#contenido">Ir al contenido</a>
    <PublicHeader/>
    <main id="contenido">
      <section className="public-home-hero">
        <div>
          <h1>Novvin.<br/>Tu gente, más cerca.</h1>
          <p>Novvin es una red social para conectar con tu gente. Conversa, comparte fotos y videos y encuentra tus comunidades desde Android o la web.</p>
          <div className="public-home-actions">
            <a className="primary-btn" href="/login">Abrir Novvin Web</a>
            <a className="ghost-btn" href="/descargar">Descargar para Android</a>
          </div>
          <div className="public-home-entry-links"><a href="/register">Crear una cuenta</a><a href="/explore/posts">Explorar sin cuenta</a></div>
        </div>
        <figure className="public-home-preview">
          <picture><source srcSet="/images/novvin-chat-web-demo.webp" type="image/webp"/><img src="/images/novvin-chat-web-demo.png" alt="Captura real de Novvin Web con una conversación de demostración" width="1440" height="900" fetchPriority="high"/></picture>
          <figcaption>Novvin Web · Conversación con una cuenta de demostración</figcaption>
        </figure>
      </section>
      <section className="public-home-features" aria-label="Qué puedes hacer en Novvin">
        <article><h2>Conversa</h2><p>Chats privados, archivos, notas de voz y llamadas para seguir cerca.</p><a href="/mensajes">Ver mensajes</a></article>
        <article><h2>Comparte</h2><p>Fotos, videos y Momentos para contar lo que pasa en tu día.</p><a href="/videos">Descubrir videos</a></article>
        <article><h2>Encuentra tu espacio</h2><p>Grupos y comunidades para reunirte con personas que comparten tus intereses.</p><a href="/comunidades">Conocer las comunidades</a></article>
      </section>
      <nav className="public-home-help" aria-label="Ayuda para empezar"><a href="/funciones">Todas las funciones</a><a href="/ayuda">Necesito ayuda</a><a href="/ayuda/encontrar-novvin">Enlaces oficiales de Novvin</a></nav>
    </main>
    <PublicFooter/>
  </div>
}
