import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Search, UserCheck, UserPlus } from 'lucide-react'
import { StatusView } from '../../components/StatusView'
import { SafeAvatar } from '../../components/SafeAvatar'
import { supabase } from '../../core/supabase'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { loadFallbackFollows, setFallbackProfileFollow } from '../../core/fallbackData'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import { avatarMediaTypeSource, profileAvatarSource } from '../../core/profileMediaFields'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'

type FollowTab = 'following' | 'followers'
type ProfileItem = { id: string; name: string; username: string; avatar: string; mediaType: string; following: boolean }
function mapProfile(row: GenericRow): ProfileItem {
  const avatar = profileAvatarSource(row)
  return {
    id: textOf(row, ['supabase_user_id', 'id', 'user_id']),
    name: textOf(row, ['displayName', 'display_name', 'full_name', 'username'], 'Usuario'),
    username: textOf(row, ['username']), avatar, mediaType: avatarMediaTypeSource(row, avatar),
    following: row.is_following === true,
  }
}
export function FollowsPage() {
  const { user, isFallbackSession } = useAuth()
  const [params, setParams] = useSearchParams()
  const activeTab: FollowTab = params.get('tab') === 'followers' ? 'followers' : 'following'
  return <FollowTabContent key={`${user?.id}/${activeTab}/${isFallbackSession}`} activeTab={activeTab}
    setActiveTab={tab => setParams({ tab }, { replace: true })}/>
}
function FollowTabContent({ activeTab, setActiveTab }: { activeTab: FollowTab; setActiveTab: (tab: FollowTab) => void }) {
  const { user, isFallbackSession } = useAuth()
  const accountId = user?.id
  const useFallback = shouldUseUbuntuBackend(isFallbackSession)
  const [items, setItems] = useState<ProfileItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')
  const [pending, setPending] = useState<Set<string>>(new Set())
  const request = useRef<AbortController | null>(null)
  const mounted = useRef(false)
  const mutations = useRef(new Set<string>())
  const filtered = useMemo(() => {
    const q = search.trim().toLocaleLowerCase()
    return items.filter(item => !q || `${item.name} ${item.username}`.toLocaleLowerCase().includes(q))
  }, [items, search])
  const load = useCallback(async (silent = false) => {
    request.current?.abort()
    const controller = new AbortController(); request.current = controller
    if (!silent) setLoading(true)
    setError('')
    try {
      let rows: GenericRow[] = []
      if (useFallback && accountId) {
        rows = (await loadFallbackFollows(activeTab, undefined, controller.signal)).items
      } else if (supabase && accountId) {
        const idField = activeTab === 'following' ? 'following_id' : 'follower_id'
        const filterField = activeTab === 'following' ? 'follower_id' : 'following_id'
        const edges = await supabase.from('follows').select('*').eq(filterField, accountId).abortSignal(controller.signal)
        if (edges.error) throw edges.error
        const ids = ((edges.data ?? []) as GenericRow[]).map(row => textOf(row, [idField])).filter(Boolean)
        if (ids.length) {
          const profiles = await supabase.from('profiles').select('*').in('id', ids).abortSignal(controller.signal)
          if (profiles.error) throw profiles.error
          let followingIds = new Set(ids)
          if (activeTab === 'followers') {
            const outgoing = await supabase.from('follows').select('following_id').eq('follower_id', accountId).abortSignal(controller.signal)
            if (outgoing.error) throw outgoing.error
            followingIds = new Set(((outgoing.data ?? []) as GenericRow[]).map(row => textOf(row, ['following_id'])))
          }
          rows = ((profiles.data ?? []) as GenericRow[]).map(row => ({ ...row, is_following: followingIds.has(textOf(row, ['id'])) }))
        }
      }
      if (!controller.signal.aborted && mounted.current) {
        const unique = new Map(rows.map(mapProfile).filter(item => item.id).map(item => [item.id, item]))
        setItems([...unique.values()])
      }
    } catch (caught) {
      if (!controller.signal.aborted && mounted.current) setError(friendlyError(caught, 'No se pudo cargar la lista.'))
    } finally {
      if (!controller.signal.aborted && mounted.current) setLoading(false)
    }
  }, [accountId, activeTab, useFallback])
  useEffect(() => {
    mounted.current = true
    void load()
    let debounce: ReturnType<typeof setTimeout> | undefined
    const refresh = () => {
      if (document.hidden) return
      clearTimeout(debounce)
      debounce = setTimeout(() => void load(true), 200)
    }
    const unsubscribe = useFallback && accountId
      ? subscribeUbuntuRealtime({ channel: `profile-counts:${accountId}`, onEvent: refresh }) : () => {}
    const interval = window.setInterval(refresh, 30000)
    window.addEventListener('focus', refresh)
    window.addEventListener('online', refresh)
    window.addEventListener('novvin:realtime-ready', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      mounted.current = false; request.current?.abort(); unsubscribe()
      clearTimeout(debounce); window.clearInterval(interval)
      window.removeEventListener('focus', refresh); window.removeEventListener('online', refresh)
      window.removeEventListener('novvin:realtime-ready', refresh); document.removeEventListener('visibilitychange', refresh)
    }
  }, [load, accountId, useFallback])
  async function toggleFollow(profile: ProfileItem) {
    if (!accountId || mutations.current.has(profile.id)) return
    mutations.current.add(profile.id); setPending(new Set(mutations.current)); setError('')
    try {
      if (useFallback) await setFallbackProfileFollow(profile.id, !profile.following)
      else if (supabase) {
        const result = profile.following
          ? await supabase.from('follows').delete().eq('follower_id', accountId).eq('following_id', profile.id)
          : await supabase.from('follows').insert({ follower_id: accountId, following_id: profile.id })
        if (result.error) throw result.error
      }
      if (mounted.current) await load(true)
    } catch (caught) {
      if (mounted.current) setError(friendlyError(caught, 'No se pudo actualizar el seguimiento.'))
    } finally {
      mutations.current.delete(profile.id)
      if (mounted.current) setPending(new Set(mutations.current))
    }
  }
  if (!supabase && !useFallback) return <StatusView title="Lista no disponible" description="Inténtalo de nuevo más tarde."/>
  return <section className="follows-page section-stack">
    <header className="follows-header"><Link className="icon-btn" to="/app/profile" aria-label="Volver a mi perfil"><ArrowLeft aria-hidden="true"/></Link><div><h2>Tu comunidad</h2><p>Personas con las que conectas en Novvin</p></div></header>
    <nav className="follows-tabs" aria-label="Listas de tu comunidad">
      {(['following', 'followers'] as const).map(tab => <button key={tab} type="button" className={activeTab === tab ? 'chip-btn active' : 'chip-btn'} aria-pressed={activeTab === tab} onClick={() => setActiveTab(tab)}>{tab === 'following' ? 'Siguiendo' : 'Seguidores'}</button>)}
    </nav>
    <label className="follows-search"><Search size={19} aria-hidden="true"/><span className="sr-only">Buscar en esta lista</span><input className="text-input" placeholder="Buscar por nombre o usuario" value={search} onChange={event => setSearch(event.target.value)}/></label>
    {error ? <p role="status" className="follows-notice">{error} <button className="ghost-btn" type="button" onClick={() => void load(items.length > 0)}>Reintentar</button></p> : null}
    {loading ? <p className="follows-empty" role="status">Cargando personas…</p> : !filtered.length ? !error && <p className="follows-empty" role="status">{search ? 'No encontramos personas con ese nombre.' : activeTab === 'following' ? 'Aquí aparecerán las personas que sigas.' : 'Aquí aparecerán tus seguidores.'}</p> : <>
      <p className="follows-count" role="status">{filtered.length} {filtered.length === 1 ? 'persona' : 'personas'}{search ? ' en esta búsqueda' : ''}</p>
      <ul className="follows-list">{filtered.map(profile => <li key={profile.id}>
        <Link className="follows-person" to={`/app/profile/${encodeURIComponent(profile.id)}`}>
          <SafeAvatar size="md" name={profile.name} url={profile.avatar} mediaType={profile.mediaType}/>
          <span><strong>{profile.name}</strong>{profile.username ? <small>@{profile.username}</small> : null}</span>
        </Link>
        <button className={profile.following ? 'ghost-btn follows-action' : 'primary-btn follows-action'} type="button" disabled={pending.has(profile.id)} title={profile.following ? `Dejar de seguir a ${profile.name}` : undefined} aria-label={pending.has(profile.id) ? `Guardando seguimiento de ${profile.name}` : profile.following ? `Siguiendo a ${profile.name}. Dejar de seguir` : `Seguir a ${profile.name}`} onClick={() => void toggleFollow(profile)}>
          {profile.following ? <UserCheck size={17} aria-hidden="true"/> : <UserPlus size={17} aria-hidden="true"/>}<span>{pending.has(profile.id) ? 'Guardando…' : profile.following ? 'Siguiendo' : 'Seguir'}</span>
        </button>
      </li>)}</ul>
    </>}
  </section>
}
