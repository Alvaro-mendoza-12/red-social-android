export function collectionInvitationPath(raw: string, webOrigin = 'https://novvinapp.com'): string {
  if (!raw || raw.length > 2048) throw new Error('El QR no contiene una invitación válida.')
  let url: URL
  try { url = new URL(raw.trim()) } catch { throw new Error('El QR no contiene un enlace de Novvin.') }
  const own = new URL(webOrigin)
  const web = ['https:', 'http:'].includes(url.protocol)
  const trusted = web && (url.origin === own.origin || (url.protocol === 'https:' && ['novvinapp.com', 'www.novvinapp.com', 'conectate.com', 'www.conectate.com', 'red-social-android.vercel.app'].includes(url.hostname) && !url.port))
  if ((!trusted && url.protocol !== 'conectate:') || url.username || url.password || url.hash) throw new Error('Este QR no es una invitación de Novvin.')
  const parts = url.pathname.split('/').filter(Boolean)
  const sourceKind = url.protocol === 'conectate:' ? url.hostname : parts.shift()
  const kind = sourceKind === 'groups' ? 'group' : sourceKind === 'communities' ? 'community' : sourceKind
  const id = parts[0] || ''
  if (!['group', 'community'].includes(kind || '') || parts.length !== 1 || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) throw new Error('El QR no corresponde a un grupo o comunidad válido.')
  const token = url.searchParams.get('invite') || ''
  if ((kind === 'group' && !token) || (token && !/^[\w-]{32,200}$/.test(token))) throw new Error('La invitación está incompleta. Pide un QR nuevo.')
  return `/${kind}/${id}` + (token ? `?invite=${encodeURIComponent(token)}` : '')
}
