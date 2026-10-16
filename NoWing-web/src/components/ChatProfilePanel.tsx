import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { X } from 'lucide-react'
import { ProfileMediaButton } from './ProfileMediaButton'
import { SafeAvatar } from './SafeAvatar'
import { StreamingImage } from './StreamingMedia'
import { loadFallbackPublicProfile, type FallbackPublicProfileResponse } from '../core/fallbackData'
import { useAuth } from '../core/auth/AuthProvider'
import { textOf } from '../core/utils'
import { recordWebProfileVisit } from '../core/profileVisits'
import { avatarMediaTypeSource, profileAvatarSource, profileCoverSource } from '../core/profileMediaFields'
import { resolveSupabasePublicUrl } from '../core/supabase'
export function ChatProfilePanel({ profileId, onClose }: { profileId: string; onClose: () => void }) {
  const { user } = useAuth(), panel = useRef<HTMLElement>(null)
  const key = `${user?.id || ''}:${profileId}`
  const [loaded, setLoaded] = useState<{ key: string; data: FallbackPublicProfileResponse } | null>(null), [failedKey, setFailedKey] = useState('')
  const data = loaded?.key === key ? loaded.data : null, failed = failedKey === key
  useEffect(() => {
    let active = true; const previous = document.activeElement as HTMLElement | null; panel.current?.focus()
    void loadFallbackPublicProfile(profileId).then(value => { if (active) { setLoaded({key,data:value}); if (profileId !== user?.id) void recordWebProfileVisit(profileId, value.profile.profile_view_history_enabled !== false).catch(() => {}) } }).catch(() => { if (active) setFailedKey(key) })
    return () => { active = false; if (previous?.isConnected) previous.focus() }
  }, [profileId, user?.id, key])
  const profile = data?.profile || {}, name = textOf(profile, ['display_name','displayName','username'], 'Perfil')
  const avatar = profileAvatarSource(profile), cover = resolveSupabasePublicUrl('covers', profileCoverSource(profile))
  return <aside ref={panel} tabIndex={-1} className="chat-profile-panel" role="dialog" aria-label="Perfil del contacto" onKeyDown={event => { if (event.key === 'Escape') onClose() }}>
    <header><h2>Información del contacto</h2><button className="icon-btn" type="button" onClick={onClose} aria-label="Cerrar perfil"><X /></button></header>
    {data ? <><div className={`chat-profile-hero${cover ? '' : ' no-cover'}`}>{cover ? <ProfileMediaButton url={cover} name={`portada de ${name}`}><StreamingImage className="chat-profile-cover" src={cover} alt="" /></ProfileMediaButton> : null}<ProfileMediaButton className="profile-avatar-open" url={resolveSupabasePublicUrl('avatars', avatar)} mediaType={avatarMediaTypeSource(profile, avatar)} name={`foto de ${name}`}><SafeAvatar className="chat-profile-avatar" size="lg" url={resolveSupabasePublicUrl('avatars', avatar)} mediaType={avatarMediaTypeSource(profile, avatar)} name={name}/></ProfileMediaButton></div><h3>{name}</h3><p className="muted-line">@{textOf(profile,['username'],name)}</p><p>{textOf(profile,['bio','biography'])}</p><div className="chat-profile-counts"><span><strong>{data.stats?.followers || 0}</strong> Seguidores</span><span><strong>{data.stats?.following || 0}</strong> Siguiendo</span></div><Link className="primary-btn" to={profileId === user?.id ? '/app/profile' : `/app/profile/${profileId}`}>Ver perfil completo</Link></> : <p role="status">{failed ? 'No se pudo cargar el perfil.' : 'Cargando perfil…'}</p>}
  </aside>
}
