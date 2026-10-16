import { publicInfoPage } from './publicInfoPages'

export function PublicInfo({ path }: { path: string }) {
  const page = publicInfoPage(path)
  if (!page) return null
  return <div className="public-home">
    <a className="public-skip-link" href="#contenido">Ir al contenido</a>
    <header className="public-home-header"><a className="public-home-brand" href="/"><img src="/novvin-logo.png" width="44" height="44" alt="" />Novvin</a><a className="ghost-btn" href="/login">Iniciar sesión</a></header>
    <main id="contenido" className="public-info">
      <a className="public-info-back" href="/">← Novvin Web</a>
      <h1>{page.heading}</h1>
      <p className="public-info-intro">{page.description}</p>
      <div className="public-home-actions"><a className="primary-btn" href="/login">Entrar a Novvin</a><a className="ghost-btn" href="/explore/videos">Explorar videos</a></div>
      {page.sections.map(section => <section key={section.heading}><h2>{section.heading}</h2><p>{section.body}</p></section>)}
      <nav aria-label="Guías de Novvin"><a href="/novvin-web">Novvin Web</a><a href="/mensajes">Mensajes</a><a href="/videos">Videos</a><a href="/salas-de-voz">Salas de voz</a><a href="/ayuda/vincular-web">Ayuda con el QR</a><a href="/social">Social</a><a href="/momentos">Momentos</a><a href="/comunidades">Grupos y comunidades</a><a href="/juegos">Juegos</a><a href="/asistente">Asistente</a><a href="/ayuda">Ayuda</a></nav>
    </main>
    <footer className="public-home-footer"><a className="public-home-brand" href="/">Novvin</a><span>Conversa. Comparte. Descubre.</span><a href="/login">Entrar a mi cuenta</a></footer>
  </div>
}
