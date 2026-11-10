import { Compass, Gamepad2, Globe, MessageSquareText, Plus, Video } from 'lucide-react'
import { Link, NavLink } from 'react-router-dom'
import { SafeAvatar } from '../../components/SafeAvatar'
import { NOVVIN_TABS, openNovvinMenu } from './navigation'

type AppNavigationProps = {
  activeSection: string
  badges: Record<string, number>
  avatarUrl: string
  displayName: string
  menuOpen?: boolean
}

const icons = { messages: MessageSquareText, videos: Video, social: Globe, games: Gamepad2 }

export function AppNavigation({ activeSection, badges, avatarUrl, displayName, menuOpen = false }: AppNavigationProps) {
  return (
    <nav className="novvin-bottom-nav" aria-label="Navegación principal">
      <div className="novvin-navigation-brand" aria-hidden="true">
        <img src="/novvin-logo.png" alt="" width="40" height="40" />
        <span>Novvin<small>Tu gente. Tu mundo.</small></span>
      </div>
      {NOVVIN_TABS.map(item => {
        const count = badges[item.to] || 0
        const Icon = item.icon === 'profile' ? null : icons[item.icon]
        return (
          <NavLink key={item.to} to={item.to}
            className={activeSection === item.to ? 'novvin-nav-item active' : 'novvin-nav-item'}
            aria-current={activeSection === item.to ? 'page' : undefined}>
            <span className="novvin-nav-pill" aria-hidden="true">
              {Icon ? <Icon aria-hidden="true" /> : <SafeAvatar url={avatarUrl} name={displayName} size="sm" className="novvin-nav-avatar" />}
              {count > 0 ? item.icon === 'messages'
                ? <span className="nav-badge" aria-hidden="true">{count > 9 ? '9+' : count}</span>
                : <span className="nav-dot" aria-hidden="true" /> : null}
            </span>
            <span>{item.label}</span>
            {count > 0 ? <span className="sr-only">{item.icon === 'messages' ? `${count} mensajes sin leer` : 'Actividad nueva'}</span> : null}
          </NavLink>
        )
      })}
      <div className="novvin-navigation-tools">
        <Link className="novvin-navigation-create" to="/app/social?tab=posts&compose=1" aria-label="Crear publicación"><Plus aria-hidden="true" /><span>Crear publicación</span></Link>
        <button type="button" className="novvin-navigation-explore" aria-label="Explorar opciones" aria-controls="mobile-explore-menu" aria-expanded={menuOpen} onClick={openNovvinMenu}><Compass aria-hidden="true" /><span>Explorar</span></button>
      </div>
    </nav>
  )
}
