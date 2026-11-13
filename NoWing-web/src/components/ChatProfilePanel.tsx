import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, ChevronRight, X } from 'lucide-react'
import { ContactFollowList, type ContactFollowTab } from './ContactFollowList'
import { ProfileMediaButton } from './ProfileMediaButton'
import { CachedProfileImage, SafeAvatar } from './SafeAvatar'
import { loadFallbackPublicProfile, type FallbackPublicProfileResponse } from '../core/fallbackData'
import { useAuth } from '../core/auth/AuthProvider'
import { textOf } from '../core/utils'
import { recordWebProfileVisit } from '../core/profileVisits'
import { avatarMediaTypeSource, profileAvatarSource, profileCoverSource } from '../core/profileMediaFields'
import { resolveSupabasePublicUrl } from '../core/supabase'
import { ProfileAwardBadges } from './ProfileIdentityMarks'
import { ProfileStatusBadges } from './ProfileStatusBadges'
import { profileIdentity } from '../ui/novvin/profileIdentity'
export function ChatProfilePanel({ profileId, onClose }: { profileId: string; onClose: () => void }) {
  const { user } = useAuth(), panel = useRef<HTMLElement>(null)
  const key = `${user?.id || ''}:${profileId}`
  const [loaded, setLoaded] = useState<{ key: string; data: FallbackPublicProfileResponse } | null>(null), [failedKey, setFailedKey] = useState('')
  const [failedCover, setFailedCover] = useState('')
  const [list, setList] = useState<ContactFollowTab | null>(null)
  const listOpener = useRef<HTMLButtonElement | null>(null)
  const backButton = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (list) backButton.current?.focus()
    else if (listOpener.current) {
      panel.current?.querySelector<HTMLButtonElement>(`[data-relation="${listOpener.current.dataset.relation}"]`)?.focus()
    }
  }, [list])
  const data = loaded?.key === key ? loaded.data : null, failed = failedKey === key
  useEffect(() => {
    let active = true; const previous = document.activeElement as HTMLElement | null; panel.current?.focus()
    void loadFallbackPublicProfile(profileId).then(value => { if (active) { setLoaded({key,data:value}); if (profileId !== user?.id) void recordWebProfileVisit(profileId, value.profile.profile_view_history_enabled !== false).catch(() => {}) } }).catch(() => { if (active) setFailedKey(key) })
    return () => { active = false; if (previous?.isConnected) previous.focus() }
  }, [profileId, user?.id, key])
  const profile = data?.profile || {}, name = textOf(profile, ['display_name','displayName','username'], 'Perfil')
  const avatar = profileAvatarSource(profile), coverSource = resolveSupabasePublicUrl('covers', profileCoverSource(profile)), cover = failedCover === coverSource ? '' : coverSource
  const listTitle = list === 'followers' ? 'Seguidores' : 'Siguiendo'
  return <aside ref={panel} tabIndex={-1} className="chat-profile-panel" role="dialog" aria-label="Perfil del contacto" onKeyDown={event => {
    if (event.key === 'Escape') { event.stopPropagation(); if (list) setList(null); else onClose() }
  }}>
    <header>
      {list ? <button ref={backButton} type="button" className="icon-btn" onClick={() => setList(null)} aria-label="Volver al contacto"><ArrowLeft size={19}/></button> : null}
      <h2>{list ? listTitle : 'Información del contacto'}</h2>
      <button className="icon-btn" type="button" onClick={onClose} aria-label="Cerrar perfil"><X /></button>
    </header>
    {list && data && user ? <><p className="contact-list-owner">{name}</p><ContactFollowList key={`${key}:${list}`} accountId={user.id} profileId={profileId} tab={list}/></> :
    data ? <><div className={`chat-profile-hero${cover ? '' : ' no-cover'}`}>{cover ? <ProfileMediaButton url={cover} name={`portada de ${name}`}><CachedProfileImage className="chat-profile-cover" src={cover} mediaType="" previewWidth={640} alt="" onError={() => setFailedCover(cover)} onSourceUnavailable={() => setFailedCover(cover)} /></ProfileMediaButton> : null}<ProfileMediaButton className="profile-avatar-open" url={resolveSupabasePublicUrl('avatars', avatar)} mediaType={avatarMediaTypeSource(profile, avatar)} name={`foto de ${name}`}><SafeAvatar className="chat-profile-avatar" size="lg" url={resolveSupabasePublicUrl('avatars', avatar)} mediaType={avatarMediaTypeSource(profile, avatar)} name={name}/></ProfileMediaButton></div><h3 className="profile-name-with-marks">{name} <ProfileStatusBadges profileId={profileId} initialIdentity={profileIdentity(profile)}/></h3><ProfileAwardBadges badges={profile.badges}/><p className="muted-line">@{textOf(profile,['username'],name)}</p><p>{textOf(profile,['bio','biography'])}</p><div className="chat-profile-counts">
      {(['followers', 'following'] as const).map(tab => {
        const label = tab === 'followers' ? 'Seguidores' : 'Siguiendo'
        const count = data.stats?.[tab]
        return <button type="button" key={tab} data-relation={tab} aria-label={`Ver ${label.toLowerCase()} de ${name}`}
          onClick={event => { listOpener.current = event.currentTarget; setList(tab) }}>
          <strong>{typeof count === 'number' ? count : '—'}</strong><span>{label}</span><ChevronRight size={14} aria-hidden="true"/>
        </button>
      })}
    </div><Link className="primary-btn" to={profileId === user?.id ? '/app/profile' : `/app/profile/${profileId}`}>Ver perfil completo</Link></> : <p role="status">{failed ? 'No se pudo cargar el perfil.' : 'Cargando perfil…'}</p>}
  </aside>
}
