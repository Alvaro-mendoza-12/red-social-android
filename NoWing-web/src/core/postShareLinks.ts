const origin = 'https://red-social-android.vercel.app'
export function sharedPostId(value: string | undefined): string | null {
  return value && /^[A-Za-z0-9_-]{8,100}$/.test(value) ? value : null
}
export function sharedPostWebPath(id: string): string {
  if (!sharedPostId(id)) throw Error('Publicación inválida')
  return `/app/feed/post/${encodeURIComponent(id)}`
}
export function sharedPostAndroidIntent(id: string): string {
  const fallback = origin + sharedPostWebPath(id)
  return `intent://post/${encodeURIComponent(id)}#Intent;scheme=conectate;package=conectate.com;S.browser_fallback_url=${encodeURIComponent(fallback)};end`
}
