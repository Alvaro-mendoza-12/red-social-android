export const site = 'https://red-social-android.vercel.app'
export const publicRoutes = [
  ['/', 'index.html', 'Inicio'],
  ['/novvin-web', 'novvin-web.html', 'Novvin Web'],
  ['/mensajes', 'mensajes.html', 'Mensajes'],
  ['/videos', 'videos.html', 'Videos'],
  ['/salas-de-voz', 'salas-de-voz.html', 'Salas de voz'],
  ['/ayuda/vincular-web', 'vincular-web.html', 'Vincular web con QR'],
  ['/descargar', 'descargar.html', 'Descargar Novvin'],
  ['/ayuda/encontrar-novvin', 'encontrar-novvin.html', 'Encontrar Novvin'],
  ["/social", "social.html", "Social"],
  ["/momentos", "momentos.html", "Momentos"],
  ["/comunidades", "comunidades.html", "Grupos y comunidades"],
  ["/juegos", "juegos.html", "Juegos"],
  ["/asistente", "asistente.html", "Asistente"],
  ["/ayuda", "ayuda.html", "Ayuda"],
]

// Describe the real brand and visible page; no invented reviews, contact data,
// keyword lists, unsupported search actions, or promised search-result features.
export function structuredData(route, title, description, label) {
  const home = site + '/', url = route === '/' ? home : site + route
  const organization = { '@type': 'Organization', '@id': home + '#organization', name: 'Novvin', alternateName: 'Novvin Web', url: home, logo: { '@type': 'ImageObject', url: site + '/novvin-logo.png', width: 512, height: 512 } }
  const website = { '@type': 'WebSite', '@id': home + '#website', name: 'Novvin', alternateName: ['Novvin Web', new URL(site).hostname], url: home, inLanguage: 'es', publisher: { '@id': organization['@id'] } }
  const page = { '@type': 'WebPage', '@id': url + '#webpage', url, name: title, description, inLanguage: 'es', isPartOf: { '@id': website['@id'] }, publisher: { '@id': organization['@id'] }, primaryImageOfPage: { '@type': 'ImageObject', url: site + '/novvin-share.png', width: 1200, height: 630 } }
  const graph = [organization, website, page]
  if (route !== '/') {
    page.breadcrumb = { '@id': url + '#breadcrumb' }
    graph.push({ '@type': 'BreadcrumbList', '@id': page.breadcrumb['@id'], itemListElement: [{ '@type': 'ListItem', position: 1, name: 'Novvin', item: home }, { '@type': 'ListItem', position: 2, name: label, item: url }] })
  }
  return JSON.stringify({ '@context': 'https://schema.org', '@graph': graph }).replaceAll('<', '\\u003c')
}
