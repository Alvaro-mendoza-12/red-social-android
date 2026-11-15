import { useEffect, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { StreamingImage, StreamingVideo } from '../components/StreamingMedia'
import { SafeAvatar } from '../components/SafeAvatar'
import { StatusView } from '../components/StatusView'
import { loadGuestContent, type GuestKind } from '../core/auth/guestAccess'
import { authReturnPath } from '../core/auth/loginReturnPath'
import { resolveSupabasePublicUrl } from '../core/supabase'
import { textOf, type GenericRow } from '../core/utils'
import { mixBasicVideoSession } from '../core/videoRecommendations'
import './explore.css'

function GuestVideo({ src, poster, title }: { src: string; poster?: string; title: string }) {
  const element = useRef<HTMLVideoElement>(null), visible = useRef(false)
  useEffect(() => {
    const video = element.current
    if (!video || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => {
      visible.current = Boolean(entry?.isIntersecting && entry.intersectionRatio >= 0.7)
      if (visible.current) void video.play().catch(() => {})
      else video.pause()
    }, { threshold: [0, 0.7] })
    observer.observe(video)
    return () => { observer.disconnect(); video.pause() }
  }, [])
  return <StreamingVideo ref={element} className="explore-video" src={src} poster={poster} aria-label={title}
    loop muted playsInline controls preload="metadata"
    onLoadedData={() => { if (visible.current) void element.current?.play().catch(() => {}) }} />
}

function mediaOf(row: GenericRow): string[] {
  let value = row.media_urls
  if (typeof value === 'string') { try { value = JSON.parse(value) } catch { value = [] } }
  const entries = Array.isArray(value) ? value : []
  return [...new Set([row.media_url, ...entries.map(item => typeof item === 'string' ? item : item?.url || item?.media_url)]
    .filter((item): item is string => typeof item === 'string' && Boolean(item.trim())))].slice(0, 10)
}

function GuestAuthor({ id, name, profile }: { id: string; name: string; profile?: GenericRow | null }) {
  const displayName = profile ? textOf(profile, ['display_name'], name) : name
  const url = profile && !profile.is_private ? resolveSupabasePublicUrl('avatars', textOf(profile, ['avatar_url'], '')) : ''
  return <Link to={`/explore/profiles/${encodeURIComponent(id)}`}>
    {profile === undefined ? <span className="avatar-sm explore-avatar-loading" aria-hidden="true" /> : <SafeAvatar url={url} name={displayName} size="sm" />}
    <strong>{displayName}</strong>
  </Link>
}

export function ExplorePage() {
  const params = useParams()
  return <ExploreContent key={`${params.kind}/${params.id}`} />
}
function ExploreContent() {
  const params = useParams(), kind: GuestKind = ['posts', 'moments', 'profiles'].includes(params.kind || '') ? params.kind as GuestKind : 'videos'
  const [searchParams] = useSearchParams()
  const sharedTarget = params.id ? searchParams.get('next') : null
  const signIn = sharedTarget ? authReturnPath('/login', sharedTarget) : '/login'
  const register = sharedTarget ? authReturnPath('/register', sharedTarget) : '/register'
  const [items, setItems] = useState<GenericRow[]>([]), [loading, setLoading] = useState(true), [failed, setFailed] = useState(false)
  const [retry, setRetry] = useState(0), [limit, setLimit] = useState(10)
  const [profiles, setProfiles] = useState(() => new Map<string, GenericRow | null>())
  const authorLookup = useRef<{ requested: Set<string>; signal: AbortSignal } | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    authorLookup.current = { requested: new Set(), signal: controller.signal }
    const entrySession = crypto.randomUUID()
    void loadGuestContent(kind, params.id, controller.signal).then(rows => {
      if (!controller.signal.aborted) setItems(kind === 'videos' && !params.id ? mixBasicVideoSession(rows, 'guest', entrySession) : rows)
    }).catch(error => {
      if (!controller.signal.aborted && !(params.id && error instanceof Error && error.message === 'GUEST_READ_404')) setFailed(true)
    })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [kind, params.id, retry])

  useEffect(() => {
    const lookup = authorLookup.current
    if (!lookup || lookup.signal.aborted) return
    for (const item of items.slice(0, limit)) {
      const id = textOf(item, ['user_id'], '')
      if (!id || lookup.requested.has(id)) continue
      lookup.requested.add(id)
      void loadGuestContent('profiles', id, lookup.signal).then(rows => rows[0] ?? null, () => null).then(profile => {
        if (!lookup.signal.aborted) setProfiles(current => new Map(current).set(id, profile))
      })
    }
  }, [items, limit])

  function reload() {
    setLoading(true)
    setFailed(false)
    setItems([])
    setProfiles(new Map())
    setLimit(10)
    setRetry(value => value + 1)
  }

  const heading = kind === 'videos' ? 'Videos' : kind === 'moments' ? 'Momentos' : kind === 'profiles' ? 'Perfil público' : 'Social'
  return <main className="explore-page explore-shell">
    <header className="explore-header">
      <Link className="explore-brand" to="/"><img src="/novvin-logo.png" alt="" />Novvin</Link>
      <nav aria-label="Cuenta"><Link to={signIn}>Iniciar sesión</Link><Link className="primary-btn" to={register}>Crear cuenta</Link></nav>
    </header>
    <div className="explore-intro"><h1>{heading}</h1><p>Explora contenido público. Inicia sesión para conversar, seguir y compartir.</p></div>
    <nav className="explore-tabs" aria-label="Explorar">
      {([['videos', 'Videos'], ['posts', 'Social'], ['moments', 'Momentos']] as const).map(([value, label]) =>
        <Link key={value} to={`/explore/${value}`} aria-current={kind === value && !params.id ? 'page' : undefined}>{label}</Link>)}
    </nav>
    {loading ? <StatusView title="Cargando contenido" description="Espera un momento." /> : failed ?
      <section className="status-card"><h2>No pudimos cargar el contenido</h2><p>Comprueba tu conexión y vuelve a intentarlo.</p><button className="ghost-btn" onClick={reload}>Reintentar</button></section> : !items.length ?
      <StatusView title={params.id ? 'Contenido no disponible' : `Sin ${kind === 'moments' ? 'momentos activos' : 'contenido público'}`} description="Vuelve a explorar más tarde." /> :
      <section className={`explore-feed explore-${kind}`} aria-label={heading}>
        {items.slice(0, limit).map(row => {
          const name = textOf(row, ['user_name', 'display_name'], 'Usuario'), id = textOf(row, ['id'], ''), author = textOf(row, ['user_id'], '')
          const avatar = resolveSupabasePublicUrl('avatars', textOf(row, ['user_profile_image', 'avatar_url'], ''))
          const title = textOf(row, ['title', 'caption', 'content'], ''), video = resolveSupabasePublicUrl('videos', textOf(row, ['video_url'], ''))
          const images = mediaOf(row), isMomentVideo = kind === 'moments' && /video/i.test(textOf(row, ['media_type'], ''))
          return <article className="explore-card" key={id}>
            <header>{author ? <GuestAuthor id={author} name={name} profile={profiles.get(author)} /> : <><SafeAvatar url={row.is_private ? '' : avatar} name={name} size="sm" /><strong>{name}</strong></>}
              {row.created_at ? <time dateTime={String(row.created_at)}>{new Date(String(row.created_at)).toLocaleDateString()}</time> : null}</header>
            {kind === 'profiles' ? <div className="explore-copy"><h2>{name}</h2>{row.username ? <p>@{String(row.username)}</p> : null}{row.bio ? <p>{String(row.bio)}</p> : null}</div> : <>
              {title ? <p className="explore-copy">{title}</p> : null}
              {kind === 'videos' && video ? <GuestVideo src={video} poster={resolveSupabasePublicUrl('thumbnails', textOf(row, ['cover_url', 'thumbnail_url'], '')) || undefined} title={title || 'Video'} /> : null}
              {images.map((url, index) => /video/i.test(textOf(row, ['media_type'], '')) || isMomentVideo ?
                <GuestVideo key={url} src={resolveSupabasePublicUrl(kind === 'moments' ? 'moments' : 'posts', url)} title={title || 'Video'} /> :
                <StreamingImage key={url} className="explore-image" src={resolveSupabasePublicUrl(kind === 'moments' ? 'moments' : 'posts', url)} alt={title ? `${title} (${index + 1})` : 'Publicación'} loading="lazy" />)}
            </>}
            <footer><Link to={sharedTarget ? signIn : `/login?next=${encodeURIComponent(kind === 'profiles' ? `/u/${id}` : kind === 'videos' ? `/app/reels/${id}` : kind === 'posts' ? `/app/feed/post/${id}` : '/app/social?tab=moments')}`}>Inicia sesión para participar</Link></footer>
          </article>
        })}
        {items.length > limit ? <button className="ghost-btn" onClick={() => setLimit(value => value + 10)}>Ver más</button> : <button className="ghost-btn" onClick={reload}>Actualizar</button>}
      </section>}
  </main>
}
