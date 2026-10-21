import { publicInfoPage, publicIllustrations } from './publicInfoPages'
import { PublicFooter, PublicGuideNavigation, PublicHeader } from './PublicNavigation'
import { androidStoreUrl, publicPageLabel } from './publicNavigationData'

export function PublicInfo({ path }: { path: string }) {
  const page = publicInfoPage(path)
  if (!page) return null
  return <div className="public-home">
    <a className="public-skip-link" href="#contenido">Ir al contenido</a>
    <PublicHeader path={path} />
    <main id="contenido" className="public-info">
      <nav className="public-breadcrumb" aria-label="Ruta de esta página"><ol><li><a href="/">Novvin</a></li><li aria-current="page">{publicPageLabel(path)}</li></ol></nav>
      <h1>{page.heading}</h1>
      <p className="public-info-intro">{page.description}</p>
      {publicIllustrations[path]?.length ? <section className="public-feature-gallery" aria-label="Imágenes de las funciones de Novvin">{publicIllustrations[path].map(image=><figure key={image.src}><img src={image.src} alt={image.alt} width={image.width} height={image.height} loading="lazy"/><figcaption>{image.caption}</figcaption></figure>)}</section> : null}
      <div className="public-home-actions"><a className="primary-btn" href="/login">Entrar a Novvin</a><a className="ghost-btn" href="/explore/videos">Explorar videos</a></div>
      {(path === '/descargar' || path === '/que-es-novvin') && <aside className="public-download-card" aria-label="Descarga oficial de Novvin">
        <img src="/novvin-logo.png" width="72" height="72" alt="" />
        <div><h2>Novvin para Android</h2><p>Abre la ficha de la aplicación para consultar su disponibilidad y descargarla.</p><a className="primary-btn" href={androidStoreUrl}>Ver Novvin en Google Play</a></div>
      </aside>}
      {page.sections.map(section => <section key={section.heading}><h2>{section.heading}</h2><p>{section.body}</p></section>)}
      <PublicGuideNavigation path={path} />
    </main>
    <PublicFooter />
  </div>
}
