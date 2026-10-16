export const androidStoreUrl = 'https://play.google.com/store/apps/details?id=conectate.com'

export const guideGroups = [
  { title: 'Conversar', links: [['/mensajes', 'Mensajes'], ['/comunidades', 'Grupos y comunidades'], ['/salas-de-voz', 'Salas de voz'], ['/asistente', 'Asistente']] },
  { title: 'Descubrir', links: [['/videos', 'Videos'], ['/social', 'Social'], ['/momentos', 'Momentos'], ['/juegos', 'Juegos']] },
  { title: 'Tu cuenta', links: [['/novvin-web', 'Novvin Web'], ['/descargar', 'Descargar Novvin'], ['/ayuda/vincular-web', 'Vincular web con QR'], ['/ayuda/encontrar-novvin', 'Encontrar Novvin'], ['/ayuda', 'Ayuda']] },
] as const

export function publicPageLabel(path: string) {
  for (const group of guideGroups) {
    const link = group.links.find(([href]) => href === path)
    if (link) return link[1]
  }
  return 'Novvin'
}

