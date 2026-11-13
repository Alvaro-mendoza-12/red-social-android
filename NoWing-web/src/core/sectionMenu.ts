export type SectionMenuItem = { label: string; to?: string; voiceAction?: 'settings' | 'refresh' | 'copy' | 'leave' }
export type MessagesMenuTab = 'chats' | 'groups' | 'communities' | 'voice'

export const messagesMenuLabels: Record<MessagesMenuTab, string> = {
  chats: 'mensajes', groups: 'grupos', communities: 'comunidades', voice: 'salas de voz',
}

export function messagesMenu(tab: MessagesMenuTab): SectionMenuItem[] {
  if (tab === 'groups') return [{ label: 'Crear grupo', to: '/app/create-space?type=group' }]
  if (tab === 'communities') return [{ label: 'Crear comunidad', to: '/app/create-space?type=community' }]
  if (tab === 'voice') return [
    { label: 'Crear sala de voz', to: '/app/create-space?type=voice' },
    { label: 'Ver salas de voz', to: '/app/voice' },
  ]
  return [
    { label: 'Nueva llamada', to: '/app/calls' },
    { label: 'Crear grupo', to: '/app/create-space?type=group' },
    { label: 'Crear comunidad', to: '/app/create-space?type=community' },
  ]
}
export function sectionMenu(path: string): SectionMenuItem[] {
  if (/^\/app\/voice\/[^/]+/.test(path)) return [
    { label: 'Ajustes de audio y video', voiceAction: 'settings' },
    { label: 'Actualizar participantes', voiceAction: 'refresh' },
    { label: 'Copiar enlace de la sala', voiceAction: 'copy' },
    { label: 'Salir de la sala', voiceAction: 'leave' },
  ]
  if (path.startsWith('/app/voice')) return [{ label: 'Crear sala de voz', to: '/app/create-space?type=voice' }]
  if (path.startsWith('/app/social')) return [{ label: 'Nueva publicación', to: '/app/social?tab=posts&compose=1' }, { label: 'Solicitudes de amistad', to: '/app/friend-requests' }]
  if (path === '/app/profile') return [{ label: 'Ajustes del perfil y apariencia', to: '/app/settings' }, { label: 'Seguidores y seguidos', to: '/app/follows' }]
  if (path.startsWith('/app/live/')) return [{ label: 'Explorar directos', to: '/app/live' }]
  return path === '/app/help' ? [] : [{ label: 'Centro de ayuda', to: '/app/help' }]
}
