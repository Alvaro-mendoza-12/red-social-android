export const site = 'https://novvinapp.com'
export const homeMetadata = { title: 'Novvin | Red social, mensajes y videos', description: 'Novvin es una red social para conectar con tu gente. Envía mensajes, comparte fotos y videos y únete a grupos y comunidades desde Android o la web.' }
export const publicRoutes = [
  ['/', 'index.html', 'Inicio'],
  ['/privacidad', 'privacidad.html', 'Privacidad'],
  ['/cookies', 'cookies.html', 'Cookies'],
  ['/que-es-novvin', 'que-es-novvin.html', 'Qué es Novvin'],
  ['/ayuda/preguntas-frecuentes', 'preguntas-frecuentes.html', 'Preguntas frecuentes'],
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
  ['/funciones', 'funciones.html', 'Funciones de Novvin'],
  ['/ayuda/stickers-animados', 'stickers-animados.html', 'Stickers animados'],
  ['/ayuda/privacidad-y-seguridad', 'privacidad-y-seguridad.html', 'Privacidad y seguridad'],
]

// Describe the real brand and visible page; no invented reviews, contact data,
// keyword lists, unsupported search actions, or promised search-result features.
export function structuredData(route, title, description, label, images = []) {
  const home = site + '/', url = route === '/' ? home : site + route
  const organization = { '@type': 'Organization', '@id': home + '#organization', name: 'Novvin', alternateName: 'Novvin Web', url: home, email: 'administracion@novvinapp.com', logo: { '@type': 'ImageObject', url: site + '/novvin-logo.png', width: 512, height: 512 } }
  const website = { '@type': 'WebSite', '@id': home + '#website', name: 'Novvin', alternateName: ['Novvin Web', new URL(site).hostname], url: home, inLanguage: 'es', publisher: { '@id': organization['@id'] } }
  const page = { '@type': 'WebPage', '@id': url + '#webpage', url, name: title, description, inLanguage: 'es', isPartOf: { '@id': website['@id'] }, publisher: { '@id': organization['@id'] }, primaryImageOfPage: { '@type': 'ImageObject', url: site + '/novvin-share.png', width: 1200, height: 630 } }
  const graph = [organization, website, page]
  if (images.length) page.image = images.map(image => ({ '@type': 'ImageObject', url: site + image.src, contentUrl: site + image.src, name: image.alt, caption: image.caption, width: image.width, height: image.height }))
  // These entities describe the same Android app and browser access shown on
  // the page. Installation links identify the actual app, without invented ratings.
  if (['/', '/que-es-novvin', '/novvin-web', '/descargar', '/ayuda/preguntas-frecuentes'].includes(route)) {
    const mobile = { '@type': 'MobileApplication', '@id': home + '#android-app', name: 'Novvin', url: site + '/descargar', applicationCategory: 'SocialNetworkingApplication', operatingSystem: 'Android', installUrl: 'https://play.google.com/store/apps/details?id=conectate.com', publisher: { '@id': organization['@id'] }, inLanguage: 'es' }
    const web = { '@type': 'WebApplication', '@id': home + '#web-app', name: 'Novvin Web', url: site + '/novvin-web', applicationCategory: 'SocialNetworkingApplication', browserRequirements: 'Navegador actualizado con JavaScript', publisher: { '@id': organization['@id'] }, inLanguage: 'es' }
    page.about = [{ '@id': mobile['@id'] }, { '@id': web['@id'] }]
    graph.push(mobile, web)
  }
  if (route !== '/') {
    page.breadcrumb = { '@id': url + '#breadcrumb' }
    graph.push({ '@type': 'BreadcrumbList', '@id': page.breadcrumb['@id'], itemListElement: [{ '@type': 'ListItem', position: 1, name: 'Novvin', item: home }, { '@type': 'ListItem', position: 2, name: label, item: url }] })
  }
  return JSON.stringify({ '@context': 'https://schema.org', '@graph': graph }).replaceAll('<', '\\u003c')
}
