import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronRight } from 'lucide-react'
import { loadFallbackFollows } from '../core/fallbackData'
import { textOf, type GenericRow } from '../core/utils'
import { profileAvatarSource } from '../core/profileMediaFields'
import { SafeAvatar } from './SafeAvatar'

export type ContactFollowTab = 'followers' | 'following'

export function ContactFollowList({ profileId, tab, accountId }: { profileId: string; tab: ContactFollowTab; accountId: string }) {
  const [items, setItems] = useState<GenericRow[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    void loadFallbackFollows(tab, profileId, controller.signal).then(result => {
      if (!controller.signal.aborted) setItems(result.items)
    }).catch(() => { if (!controller.signal.aborted) setFailed(true) })
    return () => controller.abort()
  }, [profileId, tab, accountId, attempt])

  if (failed) return <div className="contact-list-status"><p role="status">La lista no está disponible. Puede ser privada o haber un problema de conexión.</p><button type="button" className="ghost-btn" onClick={() => { setFailed(false); setAttempt(value => value + 1) }}>Reintentar</button></div>
  if (!items) return <p role="status" className="contact-list-status">Cargando lista…</p>
  if (!items.length) return <p role="status" className="contact-list-status">No hay perfiles visibles en esta lista.</p>
  return <ul className="contact-follow-list">{items.map(profile => {
    const id = textOf(profile, ['supabase_user_id', 'id', 'user_id'])
    if (!id) return null
    const name = textOf(profile, ['display_name', 'full_name', 'username'], 'Perfil')
    const handle = textOf(profile, ['username'])
    return <li key={id}><Link to={id === accountId ? '/app/profile' : `/app/profile/${encodeURIComponent(id)}`}>
      <SafeAvatar size="sm" name={name} url={profileAvatarSource(profile)}/>
      <span><strong>{name}</strong>{handle ? <small>@{handle}</small> : null}</span>
      <ChevronRight size={16} aria-hidden="true"/>
    </Link></li>
  })}</ul>
}
