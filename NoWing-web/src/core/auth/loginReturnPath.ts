/** Auth returns stay within supported local app/invite paths, never another origin. */
export function safeAuthDestination(value: string | null | undefined): string {
  if (!value || value.length > 2048 || Array.from(value).some(char => char === '\\' || char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)) return '/app/messages'
  try {
    const base = 'https://novvin.invalid'
    const target = new URL(value, base)
    if (!value.startsWith('/') || value.startsWith('//') || target.origin !== base) return '/app/messages'
    if (!/^\/(?:app(?:\/|$)|group\/|community\/|voice\/)/.test(target.pathname)) return '/app/messages'
    return target.pathname + target.search + target.hash
  } catch { return '/app/messages' }
}

export function authReturnPath(path: '/login' | '/register' | '/forgot-password', next: string): string {
  return `${path}?next=${encodeURIComponent(safeAuthDestination(next))}`
}

export function loginReturnPath(pathname: string, search = '', hash = ''): string {
  const next = `${pathname}${search}${hash}`
  return authReturnPath('/login', next)
}

export function publicSharedContentPath(pathname: string, search = '', hash = ''): string | null {
  const match = /^\/app\/(reels|feed\/post)\/([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\/?$/i.exec(pathname)
  if (!match) return null
  const kind = match[1].toLowerCase() === 'reels' ? 'videos' : 'posts'
  const next = safeAuthDestination(pathname + search + hash)
  return `/explore/${kind}/${match[2]}?next=${encodeURIComponent(next)}`
}
