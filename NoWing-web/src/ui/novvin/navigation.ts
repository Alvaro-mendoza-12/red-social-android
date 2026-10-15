export const NOVVIN_TABS = [
  { to: '/app/messages', label: 'Mensajes', icon: 'messages' },
  { to: '/app/videos', label: 'Videos', icon: 'videos' },
  { to: '/app/social', label: 'Social', icon: 'social' },
  { to: '/app/games', label: 'Juegos', icon: 'games' },
  { to: '/app/profile', label: 'Perfil', icon: 'profile' },
] as const

export function primarySection(path: string) {
  if (/^\/app\/(videos|reels)(\/|$)/.test(path)) return '/app/videos'
  if (/^\/app\/(social|moments|feed|challenges)(\/|$)/.test(path)) return '/app/social'
  if (/^\/app\/games(\/|$)/.test(path)) return '/app/games'
  if (/^\/app\/profile(\/|$)/.test(path)) return '/app/profile'
  return path.startsWith('/app/messages') ? '/app/messages' : ''
}

export function openNovvinMenu() {
  window.dispatchEvent(new Event('novvin:open-menu'))
}
export function openProfileVisits() {
  window.dispatchEvent(new Event('novvin:profile-visits'))
}
