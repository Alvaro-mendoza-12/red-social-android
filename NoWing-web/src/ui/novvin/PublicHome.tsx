import { PublicFooter, PublicGuideNavigation, PublicHeader } from './PublicNavigation'

export function PublicHome() {
  return (
    <div className="public-home public-home-refresh">
      <a className="public-skip-link" href="#contenido">Ir al contenido</a>
      <PublicHeader />
      <main id="contenido">
        <section className="public-home-hero">
          <div>
            <p className="public-home-eyebrow">TU GENTE. TU MUNDO.</p>
            <h1>
              Novvin. Tu gente,
              <br />
              <span>más cerca.</span>
            </h1>
            <p>
              Novvin es una red social para conectar con tu gente. Envía mensajes, comparte fotos
              y videos y únete a grupos y comunidades desde Android o la web.
            </p>
            <div className="public-home-actions">
              <a className="primary-btn" href="/login">
                Entrar a Novvin
              </a>
              <a className="ghost-btn" href="/explore/videos">
                Explorar videos
              </a>
            </div>
            <a className="public-home-register" href="/register">
              ¿Eres nuevo? Crea tu cuenta
            </a>
            <a className="public-home-download" href="/descargar">También disponible para Android →</a>
          </div>
          <figure className="public-conversation-preview" aria-label="Una conversación en Novvin, con datos de ejemplo">
            <div className="public-preview-topline"><span className="public-preview-dot" aria-hidden="true" />Un espacio para tu gente</div>
            <div className="public-preview-conversation">
              <div className="public-preview-heading">
                <span className="public-preview-avatar" aria-hidden="true">N</span>
                <div><strong>Nuestro próximo plan</strong><span>Una conversación para compartir</span></div>
              </div>
              <div className="public-preview-bubbles">
                <p>¿Compartimos las fotos del viaje?</p>
                <p>¡Sí! Las dejo en el grupo.<span aria-hidden="true"> ✨</span></p>
              </div>
              <div className="public-preview-topics" aria-hidden="true"><span>Fotos</span><span>Notas de voz</span><span>Ideas</span></div>
              <p className="public-preview-signoff">Las mejores historias<br /><strong>empiezan con un hola.</strong></p>
            </div>
            <figcaption>Vista ilustrativa · datos de ejemplo</figcaption>
          </figure>
        </section>
        <section className="public-home-feature-section" aria-labelledby="public-features-title">
          <div className="public-section-heading"><p className="public-home-eyebrow">A TU MANERA</p><h2 id="public-features-title">Elige cómo conectar</h2><p>Una conversación, algo que compartir o un nuevo descubrimiento.</p></div>
          <div className="public-home-features">
          <article>
            <img className="public-feature-preview" src="/images/novvin-chats.png" alt="Vista ilustrativa de los chats de Novvin con mensajes y fotos de ejemplo" width="1200" height="800" loading="lazy" decoding="async" />
            <span>01</span>
            <h2>La conversación sigue.</h2>
            <p>
              Mensajes privados, grupos y comunidades para compartir texto, fotos, archivos y notas
              de voz con tu gente.
            </p>
            <a href="/login?next=%2Fapp%2Fmessages">Abrir mis mensajes →</a>
          </article>
          <article>
            <img className="public-feature-preview" src="/images/novvin-videos.png" alt="Vista ilustrativa de la sección de videos de Novvin" width="1200" height="800" loading="lazy" decoding="async" />
            <span>02</span>
            <h2>Algo nuevo por descubrir.</h2>
            <p>Explora videos y publicaciones, sigue a tus creadores y comparte lo que te gusta.</p>
            <a href="/explore/posts">Descubrir Social →</a>
          </article>
          <article>
            <img className="public-feature-preview" src="/images/novvin-momentos.png" alt="Vista ilustrativa de fotos y videos en Momentos de Novvin" width="1200" height="800" loading="lazy" decoding="async" />
            <span>03</span>
            <h2>Momentos que conectan.</h2>
            <p>
              Comparte una foto o un video con música, muestra tu personalidad y encuentra a tu
              comunidad.
            </p>
            <a href="/explore/moments">Ver momentos →</a>
          </article>
          </div>
        </section>
        <PublicGuideNavigation />
        <section className="public-home-faq">
          <h2>Tu cuenta, también en la web.</h2>
          <details open>
            <summary>¿Qué es Novvin y para qué sirve?</summary>
            <p>
              Novvin reúne conversaciones privadas, grupos, comunidades, videos y publicaciones
              sociales. Puedes usar la app para Android y Novvin Web con la misma cuenta.
              Conoce <a href="/que-es-novvin">qué puedes hacer en Novvin</a> o consulta las
              <a href="/ayuda/preguntas-frecuentes"> preguntas frecuentes</a> para empezar.
            </p>
          </details>
          <details>
            <summary>¿Cómo inicio sesión en Novvin Web?</summary>
            <p>
              Abre <a href="/login">Iniciar sesión</a> y usa el correo y la contraseña de tu cuenta.
              También puedes elegir el código QR y escanearlo desde Perfil → Vincular web en
              Novvin móvil.
            </p>
          </details>
          <details>
            <summary>¿Puedo entrar desde celular, tablet o computadora?</summary>
            <p>
              Sí. Novvin Web adapta sus mensajes, videos y perfiles al tamaño de tu pantalla. Usa la
              misma cuenta del aplicativo móvil.
            </p>
          </details>
          <details>
            <summary>¿Puedo explorar sin una cuenta?</summary>
            <p>
              Puedes <a href="/explore/videos">entrar como invitado</a> para explorar contenido
              público. Inicia sesión para conversar, seguir y participar.
            </p>
          </details>
          <details open>
            <summary>¿Cómo encuentro el acceso oficial a Novvin?</summary>
            <p>
              Entra a tus mensajes, descubre videos y participa en grupos y comunidades desde
              Android o tu navegador. Puedes <a href="/login">iniciar sesión</a>,
              <a href="/descargar"> descargar la app</a> o <a href="/funciones">conocer sus funciones</a>.
              Consulta <a href="/ayuda/encontrar-novvin">los enlaces oficiales de Novvin</a>.
            </p>
          </details>
        </section>
      </main>
      <PublicFooter />
    </div>
  )
}
