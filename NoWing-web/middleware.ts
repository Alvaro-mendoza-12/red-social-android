import { ipAddress, next, rewrite } from '@vercel/functions'
import { isCookieIsolatedBackendPath, isPublicBackendPath, productionBackendOrigin } from './src/core/publicBackendTransport.js'

/** Public HTTPS proxy: the visitor never needs to resolve the tailnet origin. */
export default function middleware(request: Request): Response {
  const incoming = new URL(request.url)
  if (!isPublicBackendPath(incoming.pathname)) return next()
  if (request.headers.get('upgrade')?.toLowerCase() === 'websocket') {
    const channels: Record<string, string> = { '/realtime/': 'realtime', '/socket.io/': 'voice', '/call/socket.io/': 'call', '/live/socket.io/': 'live' }
    const channel = channels[incoming.pathname]
    if (!channel) return new Response('Unsupported socket path', { status: 404 })
    const target = new URL('/api/novvin-socket', incoming.origin)
    target.search = incoming.search
    target.searchParams.set('channel', channel)
    return rewrite(target)
  }
  const destination = new URL(incoming.pathname + incoming.search, productionBackendOrigin)
  const headers = new Headers(request.headers)
  // Preserve the platform-verified visitor address instead of grouping every
  // visitor behind one proxy rate-limit key. Never forward a supplied CF value.
  headers.delete('cf-connecting-ip')
  const visitor = ipAddress(request)
  if (visitor) headers.set('cf-connecting-ip', visitor)
  headers.delete('proxy-authorization')
  // Browser cookies may authorize Vercel Preview access, but guest/media
  // endpoints must keep their explicit bearer identity at the backend.
  if (isCookieIsolatedBackendPath(incoming.pathname)) headers.delete('cookie')
  if (incoming.pathname.startsWith('/p/')) headers.delete('authorization')
  return rewrite(destination, {
    request: { headers },
    headers: { 'Vercel-CDN-Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex, nofollow, noarchive', ...(!incoming.pathname.startsWith('/storage/') ? { 'Cache-Control': 'no-store' } : {}) },
  })
}

export const config = {
  runtime: 'nodejs',
  matcher: ['/p/:path*', '/v1/:path*', '/auth/v1/:path*', '/rest/v1/:path*', '/storage/v1/:path*', '/realtime/:path*', '/socket.io/:path*', '/call/socket.io/:path*', '/live/socket.io/:path*', '/health', '/ready', '/turn-credentials', '/live-active-ids'],
}
