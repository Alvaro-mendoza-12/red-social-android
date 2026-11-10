import { androidStoreUrl, guideGroups } from './publicNavigationData'

export function PublicHeader({ path = '/' }: { path?: string }) {
  return <header className="public-site-header">
    <div className="public-home-header">
      <a href="/" className="public-home-brand"><img src="/novvin-logo.png" alt="" width="44" height="44" />Novvin</a>
      <a className="ghost-btn" href="/login">Iniciar sesión</a>
    </div>
    <nav className="public-top-nav" aria-label="Navegación principal">
      {([['/que-es-novvin', 'Conoce Novvin'], ['/mensajes', 'Mensajes'], ['/videos', 'Videos'], ['/descargar', 'Descargar'], ['/ayuda', 'Ayuda']] as const).map(([href, label]) =>
        <a key={href} href={href} aria-current={path === href ? 'page' : undefined}>{label}</a>)}
    </nav>
  </header>
}

export function PublicGuideNavigation({ path = '/' }: { path?: string }) {
  return <section className="public-guide-directory" aria-labelledby="public-guide-title">
    <h2 id="public-guide-title">Encuentra tu lugar en Novvin.</h2>
    <nav className="public-guide-groups" aria-label="Guías de Novvin">
      {guideGroups.map(group => <div key={group.title}>
        <h3>{group.title}</h3>
        <ul>{group.links.map(([href, label]) => <li key={href}><a href={href} aria-current={path === href ? 'page' : undefined}>{label}<span aria-hidden="true"><svg width="14" height="14" viewBox="0 0 20 20" fill="none" focusable="false"><path d="M5 15 15 5M5 5h10v10" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg></span></a></li>)}</ul>
      </div>)}
    </nav>
  </section>
}

export function PublicFooter() {
  return <footer className="public-home-footer">
    <div><a className="public-home-brand" href="/">Novvin</a><p>Conversa. Comparte. Descubre.</p></div>
    <nav className="public-footer-links" aria-label="Accesos a Novvin"><a href="/descargar">App para Android</a><a href={androidStoreUrl}>Google Play</a><a href="/ayuda/encontrar-novvin">Encontrar la web</a><a href="/login">Entrar a mi cuenta</a></nav>
  </footer>
}
