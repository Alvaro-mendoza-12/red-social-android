import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { publicInfoPage } from './publicInfoPages'
import { useAuth } from '../../core/auth/AuthProvider'
import { useInboxUnreadMessageTotal } from '../../core/inboxActivity'

const base = 'https://red-social-android.vercel.app'
export function PageMetadata() {
  const { pathname } = useLocation()
  const { user } = useAuth()
  const unread = useInboxUnreadMessageTotal(user?.id || '')
  useEffect(() => {
    const info = publicInfoPage(pathname)
    const title =
      info ? info.title : pathname === '/'
        ? 'Novvin Web — mensajes, videos y comunidades'
        : pathname === '/login'
          ? 'Iniciar sesión en Novvin Web'
          : pathname === '/register'
            ? 'Crear una cuenta en Novvin'
            : pathname.startsWith('/app/messages')
              ? 'Mensajes · Novvin'
              : /^\/app\/(videos|reels)/.test(pathname)
                ? 'Videos · Novvin'
                : pathname.startsWith('/app/social')
                  ? 'Social · Novvin'
                  : pathname.startsWith('/app/profile')
                    ? 'Perfil · Novvin'
                    : 'Novvin Web'
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
