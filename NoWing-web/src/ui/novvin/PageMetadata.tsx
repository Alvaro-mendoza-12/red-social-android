import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { publicInfoPage } from './publicInfoPages'
import { useAuth } from '../../core/auth/AuthProvider'
import { useInboxUnreadMessageTotal } from '../../core/inboxActivity'

const appTitles: Record<string, string> = {
  messages: 'Mensajes', videos: 'Videos', reels: 'Videos', social: 'Social', feed: 'Publicación',
  profile: 'Perfil', games: 'Juegos', settings: 'Ajustes', notifications: 'Notificaciones',
  voice: 'Salas de voz', calls: 'Llamadas', live: 'En vivo', proximity: 'Personas cerca',
  search: 'Buscar', assistant: 'Asistente', follows: 'Amigos', 'friend-requests': 'Solicitudes',
  'create-space': 'Crear espacio', reports: 'Mis reportes', help: 'Ayuda', admin: 'Administración',
  moments: 'Momentos', ranking: 'Ranking', challenges: 'Rondas', monetization: 'Monetización',
}
const base = 'https://novvinapp.com'
export function PageMetadata() {
  const { pathname } = useLocation()
  const { user } = useAuth()
  const unread = useInboxUnreadMessageTotal(user?.id || '')
  useEffect(() => {
    const info = publicInfoPage(pathname)
    const section = pathname.startsWith('/app/') ? appTitles[pathname.split('/')[2]] : undefined
    const title = info?.title || (section ? `${section} · Novvin`
      : pathname === '/' ? 'Novvin Web — mensajes, videos y comunidades'
      : pathname === '/login' ? 'Iniciar sesión en Novvin Web'
      : pathname === '/register' ? 'Crear una cuenta en Novvin'
      : pathname === '/forgot-password' ? 'Recuperar contraseña · Novvin'
      : pathname.startsWith('/explore/videos') ? 'Explorar videos · Novvin'
      : pathname.startsWith('/explore') ? 'Explorar Social · Novvin'
      : 'Novvin Web')
    document.title = `${user && pathname.startsWith('/app') && unread > 0 ? `(${unread > 999 ? '999+' : unread}) ` : ''}${title}`
    let robots = document.head.querySelector<HTMLMetaElement>('meta[name="robots"]')
    if (!robots) {
      robots = document.createElement('meta')
      robots.name = 'robots'
      document.head.append(robots)
    }
    robots.content = pathname === '/' || info ? 'index, follow' : 'noindex, follow'
    const description = document.head.querySelector<HTMLMetaElement>('meta[name="description"]')
    if (description) description.content = info?.description || 'Descubre Novvin: mensajes, videos, momentos y comunidades. Conecta desde tu móvil o computadora con Novvin Web.'
    const canonical = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]')
    if (canonical) canonical.href = pathname === '/' ? base + '/' : base + pathname
  }, [pathname, unread, user])
  return null
}
