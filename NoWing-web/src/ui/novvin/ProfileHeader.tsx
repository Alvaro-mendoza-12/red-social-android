import { BadgeCheck, Check, Info } from 'lucide-react'
import { Link } from 'react-router-dom'
import { SafeAvatar } from '../../components/SafeAvatar'
import { StreamingImage } from '../../components/StreamingMedia'
import { textOf, type GenericRow } from '../../core/utils'

export type ProfileIdentity = {
  verified: boolean
  admin: boolean
  publicId: string
}
export function profileIdentity(row: GenericRow): ProfileIdentity {
  const yes = (value: unknown) => value === true || value === 1 || value === 'true'
  return {
    verified: yes(row.is_verified ?? row.isVerified ?? row.verified),
    admin:
      yes(row.is_admin ?? row.isAdmin) ||
      ['admin', 'super_admin', 'owner'].includes(
        textOf(row, ['admin_role', 'adminRole', 'role'], '').toLowerCase(),
      ),
    publicId: textOf(row, ['public_id', 'publicId', 'user_code', 'userCode', 'numeric_id'], ''),
  }
}
export function VerifiedMark() {
  return (
    <span className="novvin-profile-verified" aria-label="Cuenta verificada">
      <BadgeCheck fill="currentColor" strokeWidth={0} />
      <Check
        size={12}
        color="white"
        style={{ position: 'absolute', width: 12, height: 12, margin: 4 }}
      />
    </span>
  )
}
export function ProfileHeader({
  name,
  username,
  avatarUrl,
  avatarMediaType,
  coverUrl,
  frame,
  identity,
  followers,
  following,
  own = false,
}: {
  name: string
  username: string
  avatarUrl: string
  avatarMediaType?: string
  coverUrl: string
  frame?: string
  identity: ProfileIdentity
  followers: number
  following: number
  own?: boolean
}) {
  return (
    <>
      <div className="mobile-profile-cover">
        {coverUrl ? <StreamingImage src={coverUrl} alt={`Portada de ${name}`} /> : null}
      </div>
      <div className="mobile-profile-main novvin-profile-main-header">
        <div className="novvin-profile-header">
          <div className={`profile-avatar-frame profile-avatar-frame--${frame || 'none'}`}>
            <SafeAvatar
              url={avatarUrl}
              mediaType={avatarMediaType}
              name={name}
              size="lg"
              className="mobile-profile-avatar"
            />
            {identity.verified ? (
              <span className="profile-avatar-verification">
                <VerifiedMark />
              </span>
            ) : null}
          </div>
          <div className="novvin-profile-identity">
            <div className="novvin-profile-name-row">
              <div className="novvin-profile-name">
                <h1 title={name}>{name}</h1>
                {identity.verified ? <VerifiedMark /> : null}
              </div>
              <nav className="mobile-profile-stats" aria-label="Estadísticas del perfil">
                {own ? (
                  <>
                    <Link to="/app/follows?tab=followers">
                      <strong>{followers}</strong>
                      <span>Seguidores</span>
                    </Link>
                    <Link to="/app/follows?tab=following">
                      <strong>{following}</strong>
                      <span>Siguiendo</span>
                    </Link>
                  </>
                ) : (
                  <>
                    <span>
                      <strong>{followers}</strong>
                      <small>Seguidores</small>
                    </span>
                    <span>
                      <strong>{following}</strong>
                      <small>Siguiendo</small>
                    </span>
                  </>
                )}
              </nav>
            </div>
            <div className="novvin-profile-meta">
              <span className="username">@{username || 'usuario'}</span>
              {identity.publicId ? <span title="ID de usuario">#{identity.publicId}</span> : null}
              {identity.publicId ? (
                <span
                  title={`ID de usuario: ${identity.publicId}`}
                  aria-label={`ID de usuario: ${identity.publicId}`}
                >
                  <Info size={14} />
                </span>
              ) : null}
              {identity.admin ? (
                <span className="novvin-admin-badge">
                  <BadgeCheck />
                  ADMIN
                </span>
              ) : null}
            </div>
          </div>
        </div>
      </div>
    </>
  )
}
