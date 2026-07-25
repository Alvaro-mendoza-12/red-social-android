import { useEffect, useMemo, useState } from 'react'
import { Edit3, Grid3X3, MoreHorizontal, Share2, Star, Video } from 'lucide-react'
import { Link } from 'react-router-dom'
import { ExpandableText } from '../../components/ExpandableText'
import { StatusView } from '../../components/StatusView'
import { SafeAvatar } from '../../components/SafeAvatar'
import { loadFallbackProfileMe, loadFallbackVideos } from '../../core/fallbackData'
import {
  avatarMediaTypeSource,
  findContentAvatarCandidate,
  profileAvatarSource,
  profileCoverSource,
  profileIdentityAliases,
  profileVideoCoverSource,
  profileVideoSource,
} from '../../core/profileMediaFields'
import { resolveSupabasePublicUrl, supabase } from '../../core/supabase'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { buildProfileShareUrl } from '../../core/socialLinks'
import { extractMediaEntries, type MediaEntry } from '../feed/mediaUtils'

type ProfileTab = 'videos' | 'posts' | 'shared' | 'favorites'

type ProfileStats = {
  posts: number
  videos: number
  chats: number
  followers: number
  following: number
  reportsSent: number
}

type ProfileData = {
  name: string
  username: string
  bio: string
  avatarUrl: string
  avatarMediaType: string
  coverUrl: string
  profileWelcome: string
  interests: string[]
  hobbies: string[]
  linkedGames: string[]
  career: string
  favoriteBook: string
  favoriteSport: string
  favoriteMusicUrl: string
  badges: Array<{ id: string; name: string; icon: string }>
  profileFrame: string
}

type ProfilePost = {
  id: string
  content: string
  media: MediaEntry[]
}

type ProfileVideo = {
  id: string
  ownerId: string
  title: string
  videoUrl: string
  coverUrl: string
  sharedByMe: boolean
  favoritedByMe: boolean
}

function stringArrayFrom(value: unknown): string[] {
  return Array.isArray(value) ? value.map((entry) => String(entry).trim()).filter(Boolean) : []
}

function profileBadgesFrom(value: unknown): Array<{ id: string; name: string; icon: string }> {
  if (!Array.isArray(value)) return []
  return value.flatMap((entry) => {
    if (!entry || typeof entry !== 'object') return []
    const row = entry as GenericRow
    const id = textOf(row, ['id'], '')
    if (!id) return []
    return [{ id, name: textOf(row, ['name'], id), icon: textOf(row, ['icon'], '') }]
  })
}

function mapProfile(
  row: GenericRow,
  fallbackName: string,
  fallbackAvatar: string,
  fallbackAvatarMediaType = '',
): ProfileData {
  const avatarSource = profileAvatarSource(row, fallbackAvatar)
  return {
    name: textOf(row, ['displayName', 'display_name', 'full_name'], fallbackName),
    username: textOf(row, ['username'], ''),
    bio: textOf(row, ['bio'], ''),
    avatarUrl: resolveSupabasePublicUrl('avatars', avatarSource),
    avatarMediaType: avatarMediaTypeSource(row, avatarSource, fallbackAvatarMediaType),
    coverUrl: resolveSupabasePublicUrl('covers', profileCoverSource(row)),
    profileWelcome: textOf(row, ['profile_welcome', 'profileWelcome'], ''),
    interests: stringArrayFrom(row.interests),
    hobbies: stringArrayFrom(row.hobbies),
    linkedGames: stringArrayFrom(row.linked_games ?? row.linkedGames),
    career: textOf(row, ['career'], ''),
    favoriteBook: textOf(row, ['favorite_book'], ''),
    favoriteSport: textOf(row, ['favorite_sport'], ''),
    favoriteMusicUrl: textOf(row, ['favorite_music_url', 'favoriteMusicUrl'], ''),
    badges: profileBadgesFrom(row.badges),
    profileFrame: textOf(row, ['profile_frame', 'profileFrame'], ''),
  }
}

function mapPost(row: GenericRow): ProfilePost {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    content: textOf(row, ['content', 'caption', 'description', 'text'], 'Publicación'),
    media: extractMediaEntries(row),
  }
}

function mapVideo(row: GenericRow): ProfileVideo {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    ownerId: textOf(row, ['user_id', 'userId', 'owner_id', 'ownerId', 'author_id', 'authorId'], ''),
    title: textOf(row, ['title', 'description'], 'Video'),
    videoUrl: resolveSupabasePublicUrl(
      'videos',
      profileVideoSource(row),
    ),
    coverUrl: resolveSupabasePublicUrl(
      'thumbnails',
      profileVideoCoverSource(row),
    ),
    sharedByMe: Boolean(row.shared_by_me ?? row.sharedByMe),
    favoritedByMe: Boolean(row.favorited_by_me ?? row.favoritedByMe ?? row.is_favorite),
  }
}

function ProfileVideoPreview({ item }: { item: ProfileVideo }) {
  const [coverFailed, setCoverFailed] = useState(false)
  if (item.coverUrl && !coverFailed) {
    return <img src={item.coverUrl} alt={item.title} onError={() => setCoverFailed(true)} />
  }
  return <video src={item.videoUrl} muted preload="metadata" playsInline />
}

function ProfileAvatar({ url, name, mediaType }: { url: string; name: string; mediaType: string }) {
  return <SafeAvatar url={url} mediaType={mediaType} name={name} size="lg" className="mobile-profile-avatar" />
}

export function ProfilePage() {
  const { user, isFallbackSession } = useAuth()
  const [profile, setProfile] = useState<ProfileData | null>(null)
  const [posts, setPosts] = useState<ProfilePost[]>([])
  const [videos, setVideos] = useState<ProfileVideo[]>([])
  const [stats, setStats] = useState<ProfileStats>({
    posts: 0,
    videos: 0,
    chats: 0,
    followers: 0,
    following: 0,
    reportsSent: 0,
  })
  const [activeTab, setActiveTab] = useState<ProfileTab>('videos')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [shareStatus, setShareStatus] = useState('')

  const metadataName = String(user?.user_metadata?.display_name ?? user?.user_metadata?.full_name ?? '').trim()
  const metadataRow = (user?.user_metadata ?? {}) as GenericRow
  const metadataAvatar = profileAvatarSource(metadataRow)
  const metadataAvatarMediaType = avatarMediaTypeSource(metadataRow, metadataAvatar)
  const visibleVideos = useMemo(() => {
    if (activeTab === 'shared') return videos.filter((item) => item.sharedByMe)
    if (activeTab === 'favorites') return videos.filter((item) => item.favoritedByMe)
    return videos.filter((item) => item.ownerId === user?.id || activeTab !== 'videos')
  }, [activeTab, user?.id, videos])

  useEffect(() => {
    async function loadProfile() {
      if (!user) {
        setLoading(false)
        return
      }
      setLoading(true)
      setError(null)
      try {
        if (isFallbackSession) {
          const [profileResponse, videoResponse] = await Promise.all([
            loadFallbackProfileMe(),
            loadFallbackVideos(200, 0),
          ])
          const contentAvatar = findContentAvatarCandidate(
            [...profileResponse.posts, ...videoResponse.items],
            profileIdentityAliases(profileResponse.profile, [user.id, user.email ?? '']),
          )
          setProfile(mapProfile(
            profileResponse.profile,
            metadataName || user.email || 'Usuario',
            contentAvatar?.url || metadataAvatar,
            contentAvatar?.mediaType || metadataAvatarMediaType,
          ))
          setPosts(profileResponse.posts.map(mapPost))
          setVideos(videoResponse.items.map(mapVideo))
          setStats(profileResponse.stats)
          return
        }

        if (!supabase) throw new Error('Backend no configurado')
        const [profileResult, postsResult, videosResult, followersResult, followingResult] = await Promise.all([
          supabase.from('profiles').select('*').eq('id', user.id).limit(1),
          supabase.from('posts').select('*').eq('user_id', user.id).order('created_at', { ascending: false }).limit(60),
          supabase.from('videos').select('*').order('created_at', { ascending: false }).limit(200),
          supabase.from('follows').select('*', { count: 'exact', head: true }).eq('following_id', user.id),
          supabase.from('follows').select('*', { count: 'exact', head: true }).eq('follower_id', user.id),
        ])
        if (profileResult.error) throw profileResult.error
        if (postsResult.error) throw postsResult.error
        if (videosResult.error) throw videosResult.error
        const profileRow = ((profileResult.data ?? []) as GenericRow[])[0] ?? {}
        const mappedPosts = ((postsResult.data ?? []) as GenericRow[]).map(mapPost)
        const mappedVideos = ((videosResult.data ?? []) as GenericRow[]).map(mapVideo)
        const contentAvatar = findContentAvatarCandidate(
          [
            ...((postsResult.data ?? []) as GenericRow[]),
            ...((videosResult.data ?? []) as GenericRow[]),
          ],
          profileIdentityAliases(profileRow, [user.id, user.email ?? '']),
        )
        setProfile(mapProfile(
          profileRow,
          metadataName || user.email || 'Usuario',
          contentAvatar?.url || metadataAvatar,
          contentAvatar?.mediaType || metadataAvatarMediaType,
        ))
        setPosts(mappedPosts)
        setVideos(mappedVideos)
        setStats((current) => ({
          ...current,
          posts: mappedPosts.length,
          videos: mappedVideos.filter((item) => item.ownerId === user.id).length,
          followers: followersResult.count ?? 0,
          following: followingResult.count ?? 0,
        }))
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo cargar el perfil'))
      } finally {
        setLoading(false)
      }
    }
    void loadProfile()
  }, [isFallbackSession, metadataAvatar, metadataAvatarMediaType, metadataName, user])

  async function shareProfile() {
    if (!user) return
    const url = buildProfileShareUrl(user.id)
    try {
      if (navigator.share) {
        await navigator.share({ title: profile?.name || 'Perfil Novvin', url })
      } else {
        await navigator.clipboard.writeText(url)
      }
      setShareStatus('Perfil compartido')
    } catch (caught) {
      if (caught instanceof DOMException && caught.name === 'AbortError') return
      setShareStatus('No se pudo compartir')
    }
  }

  if (loading) return <StatusView title="Cargando perfil" description="Espera un momento..." />
  if (error) return <StatusView title="Perfil no disponible" description={error} />

  const displayName = profile?.name || metadataName || user?.email || 'Usuario'
  const username = profile?.username || user?.email?.split('@')[0] || 'usuario'

  return (
    <section className="mobile-profile-page">
      <div className="mobile-profile-cover">
        {profile?.coverUrl ? <img src={profile.coverUrl} alt="Portada del perfil" /> : null}
      </div>

      <div className="mobile-profile-main">
        <div className="mobile-profile-avatar-row">
          <div className={`profile-avatar-frame profile-avatar-frame--${profile?.profileFrame || 'none'}`}>
            <ProfileAvatar
              url={profile?.avatarUrl || ''}
              mediaType={profile?.avatarMediaType || ''}
              name={displayName}
            />
          </div>
          <div className="mobile-profile-actions">
            <Link className="primary-btn" to="/app/settings"><Edit3 aria-hidden="true" />Editar perfil</Link>
            <button className="icon-btn" type="button" onClick={() => void shareProfile()} title="Compartir perfil" aria-label="Compartir perfil"><Share2 aria-hidden="true" /></button>
            <details className="profile-more-menu">
              <summary className="icon-btn" title="Más opciones" aria-label="Más opciones"><MoreHorizontal aria-hidden="true" /></summary>
              <div className="profile-more-popover">
                <Link to="/app/settings">Ajustes</Link>
                <Link to="/app/notifications">Notificaciones</Link>
              </div>
            </details>
          </div>
        </div>

        <div className="mobile-profile-identity">
          <h1>{displayName}</h1>
          <span>@{username}</span>
          <p>{profile?.bio || 'Sin biografía todavía.'}</p>
          {profile?.badges.length ? (
            <div className="chip-row">
              {profile.badges.map((badge) => <span className="folder-tag" key={badge.id}>{badge.icon} {badge.name}</span>)}
            </div>
          ) : null}
          {profile?.profileWelcome ? <p className="muted-line">{profile.profileWelcome}</p> : null}
          {shareStatus ? <small>{shareStatus}</small> : null}
        </div>

        <nav className="mobile-profile-stats" aria-label="Estadísticas del perfil">
          <button type="button"><strong>{stats.videos + stats.posts}</strong><span>Publicaciones</span></button>
          <Link to="/app/follows?tab=followers"><strong>{stats.followers}</strong><span>Seguidores</span></Link>
          <Link to="/app/follows?tab=following"><strong>{stats.following}</strong><span>Siguiendo</span></Link>
        </nav>

        {(profile?.interests.length || profile?.hobbies.length || profile?.linkedGames.length || profile?.career || profile?.favoriteBook || profile?.favoriteSport || profile?.favoriteMusicUrl) ? (
          <details className="mobile-profile-about">
            <summary>Información</summary>
            {profile.career ? <p><strong>Carrera o profesión:</strong> {profile.career}</p> : null}
            <div className="chip-row">
              {profile.interests.map((interest) => <span key={interest} className="folder-tag">{interest}</span>)}
              {profile.hobbies.map((hobby) => <span key={`hobby-${hobby}`} className="folder-tag">{hobby}</span>)}
              {profile.linkedGames.map((game) => <span key={`game-${game}`} className="folder-tag">{game}</span>)}
              {profile.favoriteBook ? <span className="folder-tag">{profile.favoriteBook}</span> : null}
              {profile.favoriteSport ? <span className="folder-tag">{profile.favoriteSport}</span> : null}
            </div>
            {profile.favoriteMusicUrl ? (
              <div>
                <small>Música de entrada · reproducción manual</small>
                <audio controls preload="none" src={profile.favoriteMusicUrl}>Tu navegador no puede reproducir este audio.</audio>
              </div>
            ) : null}
          </details>
        ) : null}
      </div>

      <div className="mobile-profile-tabs" role="tablist" aria-label="Contenido del perfil">
        <button className={activeTab === 'videos' ? 'active' : ''} type="button" role="tab" aria-selected={activeTab === 'videos'} onClick={() => setActiveTab('videos')} title="Videos"><Video aria-hidden="true" /></button>
        <button className={activeTab === 'posts' ? 'active' : ''} type="button" role="tab" aria-selected={activeTab === 'posts'} onClick={() => setActiveTab('posts')} title="Publicaciones"><Grid3X3 aria-hidden="true" /></button>
        <button className={activeTab === 'shared' ? 'active' : ''} type="button" role="tab" aria-selected={activeTab === 'shared'} onClick={() => setActiveTab('shared')} title="Compartidos"><Share2 aria-hidden="true" /></button>
        <button className={activeTab === 'favorites' ? 'active' : ''} type="button" role="tab" aria-selected={activeTab === 'favorites'} onClick={() => setActiveTab('favorites')} title="Favoritos"><Star aria-hidden="true" /></button>
      </div>

      {activeTab === 'posts' ? (
        posts.length ? (
          <div className="mobile-profile-posts">
            {posts.map((post) => (
              <article key={post.id} className="mobile-profile-post">
                {post.media[0]?.mediaType === 'image' ? <img src={post.media[0].url} alt="Publicación" /> : null}
                {post.media[0]?.mediaType === 'video' ? <video src={post.media[0].url} muted preload="metadata" /> : null}
                <ExpandableText text={post.content} lines={3} />
                <Link to={`/app/feed/post/${post.id}`}>Abrir publicación</Link>
              </article>
            ))}
          </div>
        ) : <StatusView title="Sin publicaciones" description="Tus publicaciones aparecerán aquí." />
      ) : visibleVideos.length ? (
        <div className="mobile-profile-video-grid">
            {visibleVideos.map((item) => (
              <Link key={item.id} to={`/app/reels/${item.id}`} className="mobile-profile-video">
              <ProfileVideoPreview item={item} />
              <span>{item.title}</span>
            </Link>
          ))}
        </div>
      ) : (
        <StatusView title="Sin contenido" description="Todavía no hay contenido en esta sección." />
      )}
    </section>
  )
}
