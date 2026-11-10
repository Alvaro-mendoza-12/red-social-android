/** Keep trusted backend discovery separate from the browser's public transport. */
export const productionBackendOrigin = 'https://conectate.tail9b267c.ts.net'

function isOfficialPublicGateway(page: URL): boolean {
  const official = ['red-social-android.vercel.app', 'red-social-android-alvaro-s-projects1.vercel.app', 'red-social-android-git-main-alvaro-s-projects1.vercel.app', 'red-social-android-git-correcciones-dot-alvaro-s-projects1.vercel.app'].includes(page.hostname)
    || /^red-social-android-[a-z0-9]+-alvaro-s-projects1\.vercel\.app$/.test(page.hostname)
  return official && page.protocol === 'https:' && !page.port && !page.username && !page.password
}

export function publicBackendTransportUrl(value: string, pageOrigin = typeof window === 'undefined' ? '' : window.location.origin): string {
  try {
    const upstream = new URL(value), page = new URL(pageOrigin)
    // Production's voice setting still uses the Funnel's dedicated TLS port.
    // The public gateway serves the same voice routes on its standard HTTPS origin.
    const trustedUpstream = upstream.origin === productionBackendOrigin || upstream.origin === `${productionBackendOrigin}:8443`
    if (!isOfficialPublicGateway(page) || !trustedUpstream || upstream.username || upstream.password) return value
    const proxied = new URL(upstream.pathname + upstream.search + upstream.hash, page.origin).toString()
    return upstream.pathname === '/' && !upstream.search && !upstream.hash ? page.origin : proxied
  } catch { return value }
}

export function isPublicBackendPath(path: string): boolean {
  return ['/v1/', '/auth/v1/', '/rest/v1/', '/storage/v1/', '/realtime/', '/socket.io/', '/call/socket.io/', '/live/socket.io/'].some(prefix => path.startsWith(prefix))
    || ['/health', '/ready', '/turn-credentials', '/live-active-ids'].includes(path)
}

/** These routes authorize with a guest/media bearer, never ambient account cookies. */
export function isCookieIsolatedBackendPath(path: string): boolean {
  return ['/v1/guest/', '/storage/v1/object/', '/storage/v1/render/image/'].some(prefix => path.startsWith(prefix))
}

/** Keep Vercel deployment access at the gateway; middleware removes cookies upstream. */
export function isolatedBackendRequestCredentials(value: string, pageOrigin = typeof window === 'undefined' ? '' : window.location.origin): RequestCredentials {
  try {
    const target = new URL(value), page = new URL(pageOrigin)
    if (target.username || target.password || target.origin !== page.origin ||
        !isOfficialPublicGateway(page) ||
        !isCookieIsolatedBackendPath(target.pathname)) return 'omit'
    return 'same-origin'
  } catch { return 'omit' }
}
