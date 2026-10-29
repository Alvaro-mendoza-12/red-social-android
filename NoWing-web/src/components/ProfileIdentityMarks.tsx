import { BadgeCheck, Check } from 'lucide-react'
import type { ProfileIdentity } from '../ui/novvin/profileIdentity'
import { textOf, type GenericRow } from '../core/utils'

/** Public presentation only; these marks never grant permissions. */
export function ProfileIdentityMarks({ identity }: { identity: ProfileIdentity }) {
  return <>{identity.verified ? <span className="profile-status-verified" role="img" aria-label="Cuenta verificada"><BadgeCheck size={18} fill="currentColor" strokeWidth={0} aria-hidden="true"/><Check size={11} className="verification-tick" aria-hidden="true"/></span> : null}
    {identity.admin ? <span className="novvin-admin-badge" aria-label="Administrador verificado"><BadgeCheck size={13} aria-hidden="true"/>ADMIN</span> : null}</>
}

export function ProfileAwardBadges({ badges }: { badges: unknown }) {
  if (!Array.isArray(badges)) return null
  const rows = badges.filter((badge): badge is GenericRow => !!badge && typeof badge === 'object').slice(0, 12)
  return rows.length ? <div className="profile-badges" aria-label="Insignias del perfil">{rows.map((badge, index) => {
    const name = textOf(badge, ['name'], 'Insignia'), icon = textOf(badge, ['icon'])
    return <span className="profile-badge" key={textOf(badge, ['id'], String(index))} title={textOf(badge, ['description'], name)}>{icon && !/^[A-Za-z_:/.-]+$/.test(icon) ? <span aria-hidden="true">{icon}</span> : <BadgeCheck size={16} aria-hidden="true"/>}{name}</span>
  })}</div> : null
}
