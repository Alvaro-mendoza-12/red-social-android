import { Bell, Bot, CircleHelp, CirclePlus, Flag, MapPin, Mic, Phone, Radio, Search, Settings } from 'lucide-react'
import { NavLink } from 'react-router-dom'

// Keep each destination paired with its own meaning, rather than borrowing
// unrelated primary-tab symbols (for example, settings for help).
const secondaryNavItems = [
  { to: '/app/assistant', label: 'Asistente de Novvin', Icon: Bot },
  { to: '/app/search', label: 'Buscar', Icon: Search },
  { to: '/app/notifications', label: 'Notificaciones', Icon: Bell },
  { to: '/app/calls', label: 'Llamadas', Icon: Phone },
  { to: '/app/voice', label: 'Salas de voz', Icon: Mic },
  { to: '/app/create-space', label: 'Crear espacio', Icon: CirclePlus },
  { to: '/app/live', label: 'En vivo', Icon: Radio },
  { to: '/app/proximity', label: 'Personas cerca', Icon: MapPin },
  { to: '/app/settings', label: 'Ajustes', Icon: Settings },
  { to: '/app/reports', label: 'Mis reportes', Icon: Flag },
  { to: '/app/help', label: 'Ayuda', Icon: CircleHelp },
] as const

export function SecondaryNavigation({ onNavigate }: { onNavigate: () => void }) {
  return <>{secondaryNavItems.map(({ to, label, Icon }) => (
    <NavLink key={to} to={to} className="mobile-explore-link" onClick={onNavigate}>
      <span className="nav-icon"><Icon aria-hidden="true" /></span>
      {label}
    </NavLink>
  ))}</>
}
