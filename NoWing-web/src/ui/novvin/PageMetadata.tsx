import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'

const base = 'https://red-social-android.vercel.app'
export function PageMetadata() {
  const { pathname } = useLocation()
  useEffect(() => {
    const title =
      pathname === '/'
        ? 'Novvin — mensajes, videos y comunidades'
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
    document.title = title
    let robots = document.head.querySelector<HTMLMetaElement>('meta[name="robots"]')
    if (!robots) {
      robots = document.createElement('meta')
      robots.name = 'robots'
      document.head.append(robots)
    }
    robots.content = pathname === '/' ? 'index, follow' : 'noindex, follow'
    const canonical = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]')
    if (canonical) canonical.href = pathname === '/' ? base + '/' : base + pathname
  }, [pathname])
  return null
}
