import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { X } from 'lucide-react'
import { SafeAvatar } from './SafeAvatar'
import { StreamingImage } from './StreamingMedia'
import { loadFallbackPublicProfile, type FallbackPublicProfileResponse } from '../core/fallbackData'
import { useAuth } from '../core/auth/AuthProvider'
import { textOf } from '../core/utils'
import { recordWebProfileVisit } from '../core/profileVisits'
export function ChatProfilePanel({ profileId, onClose }: { profileId: string; onClose: () => void }) {
  const { user } = useAuth(), panel = useRef<HTMLElement>(null)
  const [data, setData] = useState<FallbackPublicProfileResponse | null>(null), [failed, setFailed] = useState(false)
  useEffect(() => {
    let active = true; setData(null); setFailed(false); const previous = document.activeElement as HTMLElement | null; panel.current?.focus()
    void loadFallbackPublicProfile(profileId).then(value => { if (active) { setData(value); if (profileId !== user?.id) void recordWebProfileVisit(profileId, value.profile.profile_view_history_enabled !== false).catch(() => {}) } }).catch(() => { if (active) setFailed(true) })
    return () => { active = false; if (previous?.isConnected) previous.focus() }
  }, [profileId, user?.id])
  const profile = data?.profile || {}, name = textOf(profile, ['display_name','displayName','username'], 'Perfil')
  return <aside ref={panel} tabIndex={-1} className="chat-profile-panel" role="dialog" aria-label="Perfil del contacto" onKeyDown={event => { if (event.key === 'Escape') onClose() }}>
    <header><h2>Información del contacto</h2><button className="icon-btn" type="button" onClick={onClose} aria-label="Cerrar perfil"><X /></button></header>
    {data ? <><StreamingImage className="chat-profile-cover" src={textOf(profile,['cover_url','coverUrl']) || undefined} alt="" /><SafeAvatar className="chat-profile-avatar" size="lg" url={textOf(profile,['avatar_url','avatarUrl'])} mediaType={textOf(profile,['avatar_media_type'])} name={name}/><h3>{name}</h3><p className="muted-line">@{textOf(profile,['username'],name)}</p><p>{textOf(profile,['bio','biography'])}</p><div className="chat-profile-counts"><span><strong>{data.stats?.followers || 0}</strong> Seguidores</span><span><strong>{data.stats?.following || 0}</strong> Siguiendo</span></div><Link className="primary-btn" to={profileId === user?.id ? '/app/profile' : `/app/profile/${profileId}`}>Ver perfil completo</Link></> : <p role="status">{failed ? 'No se pudo cargar el perfil.' : 'Cargando perfil…'}</p>}
  </aside>
}
