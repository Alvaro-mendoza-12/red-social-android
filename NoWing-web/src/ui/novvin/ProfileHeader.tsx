import { BadgeCheck, Check, Info } from 'lucide-react'
import { Link } from 'react-router-dom'
import { ProfileMediaButton } from '../../components/ProfileMediaButton'
import { SafeAvatar } from '../../components/SafeAvatar'
import { StreamingImage } from '../../components/StreamingMedia'
import type { ProfileIdentity } from './profileIdentity'
import { ProfileCoverEffect } from '../../components/ProfileCoverEffect'

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
  profileEffect,
  profileTheme,
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
  profileEffect?: string
  profileTheme?: string
  frame?: string
  identity: ProfileIdentity
  followers: number
  following: number
  own?: boolean
}) {
  return (
    <>
      <div className="mobile-profile-cover">
        {coverUrl ? <ProfileMediaButton url={coverUrl} name={`portada de ${name}`}><StreamingImage src={coverUrl} alt={`Portada de ${name}`} /></ProfileMediaButton> : <ProfileCoverEffect effect={profileEffect} theme={profileTheme} />}
      </div>
      <div className="mobile-profile-main novvin-profile-main-header">
        <div className="novvin-profile-header">
          <div className={`profile-avatar-frame profile-avatar-frame--${frame || 'none'}`}>
            <ProfileMediaButton url={avatarUrl} mediaType={avatarMediaType} name={`foto de ${name}`}><SafeAvatar
              url={avatarUrl}
              mediaType={avatarMediaType}
              name={name}
              size="lg"
              className="mobile-profile-avatar"
            /></ProfileMediaButton>
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
