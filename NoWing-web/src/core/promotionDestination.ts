/** Normalize older announcement links before handing them to the app router. */
export function promotionLinkTarget(value: unknown): string {
  const target = typeof value === 'string' ? value.trim() : ''
  const aliases: Record<string, string> = { messages: '/app/messages', '/messages': '/app/messages', videos: '/app/videos', '/videos': '/app/videos', social: '/app/social', '/social': '/app/social' }
  if (aliases[target]) return aliases[target]
  if (/^\/app\/(?:messages|videos|social|games|profile|voice|live|search|notifications|settings)(?:[/?#][^\s\\]*)?$/.test(target)) return target
  try {
    const url = new URL(target)
    if (url.protocol === 'https:' && !url.username && !url.password) return url.href
  } catch { /* Older cards can lack a destination; the inbox is the safe default. */ }
  return '/app/messages'
}
