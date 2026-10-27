/** Keep trusted backend discovery separate from the browser's public transport. */
export const productionBackendOrigin = 'https://conectate.tail9b267c.ts.net'

export function publicBackendTransportUrl(value: string, pageOrigin = typeof window === 'undefined' ? '' : window.location.origin): string {
  try {
    const upstream = new URL(value), page = new URL(pageOrigin)
    const official = ['red-social-android.vercel.app', 'red-social-android-alvaro-s-projects1.vercel.app', 'red-social-android-git-main-alvaro-s-projects1.vercel.app'].includes(page.hostname)
      || /^red-social-android-[a-z0-9]+-alvaro-s-projects1\.vercel\.app$/.test(page.hostname)
    if (!official || page.protocol !== 'https:' || page.port || page.username || page.password || upstream.origin !== productionBackendOrigin || upstream.username || upstream.password) return value
    const proxied = new URL(upstream.pathname + upstream.search + upstream.hash, page.origin).toString()
    return upstream.pathname === '/' && !upstream.search && !upstream.hash ? page.origin : proxied
  } catch { return value }
}

export function isPublicBackendPath(path: string): boolean {
  return ['/v1/', '/auth/v1/', '/rest/v1/', '/storage/v1/', '/realtime/', '/socket.io/', '/call/socket.io/', '/live/socket.io/'].some(prefix => path.startsWith(prefix))
    || ['/health', '/ready', '/turn-credentials', '/live-active-ids'].includes(path)
}
