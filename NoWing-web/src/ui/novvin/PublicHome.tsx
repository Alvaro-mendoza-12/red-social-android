import { PublicFooter, PublicGuideNavigation, PublicHeader } from './PublicNavigation'

export function PublicHome() {
  return (
    <div className="public-home">
      <a className="public-skip-link" href="#contenido">Ir al contenido</a>
      <PublicHeader />
      <main id="contenido">
        <section className="public-home-hero">
          <div>
            <p className="public-home-eyebrow">TU GENTE. TU MUNDO.</p>
            <h1>
              Novvin. Conversa,
              <br />
              comparte y descubre.
            </h1>
            <p>
              Mensajes, videos, momentos y comunidades. Conecta con tu gente desde el móvil o abre
              Novvin Web en tu computadora.
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
          <div className="public-home-visual" aria-hidden="true">
            <img src="/novvin-logo.png" alt="" width="220" height="220" />
            <span>Mensajes · Videos · Social</span>
          </div>
        </section>
        <section className="public-home-features" aria-label="Qué puedes hacer en Novvin">
          <article>
            <span>01</span>
            <h2>La conversación sigue.</h2>
            <p>
              Mensajes privados, grupos y comunidades para compartir texto, fotos, archivos y notas
              de voz con tu gente.
            </p>
            <a href="/login?next=%2Fapp%2Fmessages">Abrir mis mensajes →</a>
          </article>
          <article>
            <span>02</span>
            <h2>Algo nuevo por descubrir.</h2>
            <p>Explora videos y publicaciones, sigue a tus creadores y comparte lo que te gusta.</p>
            <a href="/explore/posts">Descubrir Social →</a>
          </article>
          <article>
            <span>03</span>
            <h2>Momentos que conectan.</h2>
            <p>
              Comparte una foto o un video con música, muestra tu personalidad y encuentra a tu
              comunidad.
            </p>
            <a href="/explore/moments">Ver momentos →</a>
          </article>
        </section>
        <PublicGuideNavigation />
        <section className="public-home-faq">
          <h2>Tu cuenta, también en la web.</h2>
          <details>
            <summary>¿Cómo inicio sesión en Novvin Web?</summary>
            <p>
              Abre <a href="/login">Iniciar sesión</a> y usa el correo y la contraseña de tu cuenta.
              También puedes elegir el código QR y aprobar el acceso desde Perfil → Vincular web en
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
            <summary>¿Buscabas Novvyn, Noving, Nowing o NoWin?</summary>
            <p>
              Si buscabas nuestra red social escribiendo Novvyn con «y», Noving, Nowing o NoWin,
              el nombre es <strong>Novvin</strong>: N-O-V-V-I-N. Desde esta página puedes
              <a href="/login"> iniciar sesión</a> o <a href="/novvin-web">conocer Novvin Web</a>.
            </p>
          </details>
        </section>
      </main>
      <PublicFooter />
    </div>
  )
}
