export type SectionMenuItem = { label: string; to?: string; voiceAction?: 'settings' | 'refresh' | 'copy' | 'leave' }
export function sectionMenu(path: string): SectionMenuItem[] {
  if (/^\/app\/voice\/[^/]+/.test(path)) return [
    { label: 'Ajustes de audio y video', voiceAction: 'settings' },
    { label: 'Actualizar participantes', voiceAction: 'refresh' },
    { label: 'Copiar enlace de la sala', voiceAction: 'copy' },
    { label: 'Salir de la sala', voiceAction: 'leave' },
  ]
  if (path.startsWith('/app/voice')) return [{ label: 'Buscar salas', to: '/app/voice' }, { label: 'Crear sala de voz', to: '/app/create-space?type=voice' }]
  if (path.startsWith('/app/social')) return [{ label: 'Nueva publicación', to: '/app/social?tab=posts&compose=1' }, { label: 'Solicitudes de amistad', to: '/app/friend-requests' }, { label: 'Publicidad · Crear y gestionar', to: '/app/social?tab=posts&advertising=1' }]
  if (path.startsWith('/app/profile')) return [{ label: 'Ajustes del perfil y apariencia', to: '/app/settings' }, { label: 'Seguidores y seguidos', to: '/app/follows' }]
  if (path.startsWith('/app/live')) return [{ label: 'Explorar directos', to: '/app/live' }]
  return [{ label: 'Ayuda de este apartado', to: '/app/help?section=' + encodeURIComponent(path.split('/')[2] || 'inicio') }]
}
