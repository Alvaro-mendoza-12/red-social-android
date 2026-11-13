import { ipAddress, next, rewrite } from '@vercel/functions'
import { isCookieIsolatedBackendPath, isPublicBackendPath, productionBackendOrigin } from './src/core/publicBackendTransport.js'
import { publicSharePreviewPath } from './src/core/sharePreview.js'

/** Public HTTPS proxy: the visitor never needs to resolve the tailnet origin. */
export default function middleware(request: Request): Response {
  const incoming = new URL(request.url)
  const preview = publicSharePreviewPath(incoming.pathname, request.headers.get('user-agent') || '')
  if (!preview && !isPublicBackendPath(incoming.pathname)) return next()
  if (request.headers.get('upgrade')?.toLowerCase() === 'websocket') {
    const channels: Record<string, string> = { '/realtime/': 'realtime', '/socket.io/': 'voice', '/call/socket.io/': 'call', '/live/socket.io/': 'live' }
    const channel = channels[incoming.pathname]
    if (!channel) return new Response('Unsupported socket path', { status: 404 })
    const target = new URL('/api/novvin-socket', incoming.origin)
    target.search = incoming.search
    target.searchParams.set('channel', channel)
    return rewrite(target)
  }
  const destination = new URL(preview || incoming.pathname + incoming.search, productionBackendOrigin)
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
  if (preview || incoming.pathname.startsWith('/p/') || incoming.pathname.startsWith('/v1/public/')) {
    headers.delete('authorization')
    headers.delete('cookie')
  }
  return rewrite(destination, {
    request: { headers },
    headers: { 'Vercel-CDN-Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex, nofollow, noarchive', ...(!incoming.pathname.startsWith('/storage/') ? { 'Cache-Control': 'no-store' } : {}) },
  })
}

export const config = {
  runtime: 'nodejs',
  matcher: ['/u/:path*', '/profile/:path*', '/app/profile/:path*', '/app/feed/post/:path*', '/post/:path*', '/app/reels/:path*', '/app/videos/:path*', '/app/video/:path*', '/video/:path*', '/p/:path*', '/v1/:path*', '/auth/v1/:path*', '/rest/v1/:path*', '/storage/v1/:path*', '/realtime/:path*', '/socket.io/:path*', '/call/socket.io/:path*', '/live/socket.io/:path*', '/health', '/ready', '/turn-credentials', '/live-active-ids'],
}
