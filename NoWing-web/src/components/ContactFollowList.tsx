import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronRight } from 'lucide-react'
import { loadFallbackFollows } from '../core/fallbackData'
import { textOf, type GenericRow } from '../core/utils'
import { avatarMediaTypeSource, profileAvatarSource } from '../core/profileMediaFields'
import { SafeAvatar } from './SafeAvatar'
import { subscribeUbuntuRealtime } from '../core/ubuntuRealtime'

export type ContactFollowTab = 'followers' | 'following'

export function ContactFollowList({ profileId, tab, accountId }: { profileId: string; tab: ContactFollowTab; accountId: string }) {
  return <ContactFollowListContent key={`${accountId}/${profileId}/${tab}`} profileId={profileId} tab={tab} accountId={accountId}/>
}

function ContactFollowListContent({ profileId, tab, accountId }: { profileId: string; tab: ContactFollowTab; accountId: string }) {
  const [items, setItems] = useState<GenericRow[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let controller: AbortController | undefined
    let timer: ReturnType<typeof setTimeout> | undefined
    const load = () => {
      controller?.abort()
      const current = new AbortController(); controller = current
      void loadFallbackFollows(tab, profileId, current.signal).then(result => {
        if (!current.signal.aborted) { setItems(result.items); setFailed(false) }
      }).catch(() => { if (!current.signal.aborted) setFailed(true) })
    }
    const refresh = () => { if (!document.hidden) { clearTimeout(timer); timer = setTimeout(load, 200) } }
    load()
    const unsubscribe = subscribeUbuntuRealtime({ channel: `profile-counts:${profileId}`, onEvent: refresh })
    const interval = window.setInterval(refresh, 30000)
    window.addEventListener('focus', refresh)
    window.addEventListener('novvin:realtime-ready', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      controller?.abort(); clearTimeout(timer); window.clearInterval(interval); unsubscribe()
      window.removeEventListener('focus', refresh); window.removeEventListener('novvin:realtime-ready', refresh)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [profileId, tab, accountId, attempt])

  if (failed) return <div className="contact-list-status"><p role="status">La lista no está disponible. Puede ser privada o haber un problema de conexión.</p><button type="button" className="ghost-btn" onClick={() => { setFailed(false); setAttempt(value => value + 1) }}>Reintentar</button></div>
  if (!items) return <p role="status" className="contact-list-status">Cargando lista…</p>
  if (!items.length) return <p role="status" className="contact-list-status">No hay perfiles visibles en esta lista.</p>
  return <ul className="contact-follow-list">{items.map(profile => {
    const id = textOf(profile, ['supabase_user_id', 'id', 'user_id'])
    if (!id) return null
    const name = textOf(profile, ['displayName', 'display_name', 'full_name', 'username'], 'Perfil')
    const handle = textOf(profile, ['username'])
    const avatar = profileAvatarSource(profile)
    return <li key={id}><Link to={id === accountId ? '/app/profile' : `/app/profile/${encodeURIComponent(id)}`}>
      <SafeAvatar size="md" name={name} url={avatar} mediaType={avatarMediaTypeSource(profile, avatar)}/>
      <span><strong>{name}</strong>{handle ? <small>@{handle}</small> : null}</span>
      <ChevronRight size={16} aria-hidden="true"/>
    </Link></li>
  })}</ul>
}
