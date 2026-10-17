import { birthdayLabel } from '../../core/birthdayCard'
import { ProfilePostPreview } from '../../components/ProfilePostPreview'
import { StreamingImage, StreamingVideo, StreamingAudio } from '../../components/StreamingMedia'
import { useEffect, useMemo, useState } from 'react'
import { Edit3, Palette, QrCode, Share2, Star } from 'lucide-react'
import { Link } from 'react-router-dom'
import { ExpandableText } from '../../components/ExpandableText'
import { StatusView } from '../../components/StatusView'
import { ProfileHeader } from '../../ui/novvin/ProfileHeader'
import { profileIdentity, type ProfileIdentity } from '../../ui/novvin/profileIdentity'
import { ProfileTools } from '../../components/ProfileTools'
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
  education: string
  workplace: string
  birthDate: string
  career: string
  favoriteBook: string
  favoriteSport: string
  favoriteMusicUrl: string
  badges: Array<{ id: string; name: string; icon: string }>
  profileFrame: string
  identity: ProfileIdentity
  identityAliases: string[]
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
    identity: profileIdentity(row),
    identityAliases: profileIdentityAliases(row),
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
    education: textOf(row, ['education'], ''),
    workplace: textOf(row, ['workplace'], ''),
    birthDate: textOf(row, ['birth_date'], ''),
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
    videoUrl: resolveSupabasePublicUrl('videos', profileVideoSource(row)),
    coverUrl: resolveSupabasePublicUrl('thumbnails', profileVideoCoverSource(row)),
    sharedByMe: Boolean(row.shared_by_me ?? row.sharedByMe),
    favoritedByMe: Boolean(row.favorited_by_me ?? row.favoritedByMe ?? row.is_favorite),
  }
}

function ProfileVideoPreview({ item }: { item: ProfileVideo }) {
  const [coverFailed, setCoverFailed] = useState(false)
  if (item.coverUrl && !coverFailed) {
    return (
      <StreamingImage src={item.coverUrl} alt={item.title} onError={() => setCoverFailed(true)} />
    )
  }
  return <StreamingVideo src={item.videoUrl} muted preload="metadata" playsInline />
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
  const [homeTab, setHomeTab] = useState<'home' | 'about'>('home')
  const [activeTab, setActiveTab] = useState<ProfileTab>('videos')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [shareStatus, setShareStatus] = useState('')

  const metadataName = String(
    user?.user_metadata?.display_name ?? user?.user_metadata?.full_name ?? '',
  ).trim()
  const metadataRow = (user?.user_metadata ?? {}) as GenericRow
  const metadataAvatar = profileAvatarSource(metadataRow)
  const metadataAvatarMediaType = avatarMediaTypeSource(metadataRow, metadataAvatar)
  const visibleVideos = useMemo(() => {
    if (activeTab === 'shared') return videos.filter((item) => item.sharedByMe)
    if (activeTab === 'favorites') return videos.filter((item) => item.favoritedByMe)
    return videos.filter(
      (item) =>
        item.ownerId === user?.id ||
        profile?.identityAliases.includes(item.ownerId.toLowerCase()) ||
        activeTab !== 'videos',
    )
  }, [activeTab, user?.id, videos, profile?.identityAliases])

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
          setProfile(
            mapProfile(
              profileResponse.profile,
              metadataName || user.email || 'Usuario',
              contentAvatar?.url || metadataAvatar,
              contentAvatar?.mediaType || metadataAvatarMediaType,
            ),
          )
          setPosts(profileResponse.posts.map(mapPost))
          setVideos(videoResponse.items.map(mapVideo))
          setStats(profileResponse.stats)
          return
        }

        if (!supabase) throw new Error('Backend no configurado')
        const [profileResult, postsResult, videosResult, followersResult, followingResult] =
          await Promise.all([
            supabase.from('profiles').select('*').eq('id', user.id).limit(1),
            supabase
              .from('posts')
              .select('*')
              .eq('user_id', user.id)
              .order('created_at', { ascending: false })
              .limit(60),
            supabase
              .from('videos')
              .select('*')
              .order('created_at', { ascending: false })
              .limit(200),
            supabase
              .from('follows')
              .select('*', { count: 'exact', head: true })
              .eq('following_id', user.id),
            supabase
              .from('follows')
              .select('*', { count: 'exact', head: true })
              .eq('follower_id', user.id),
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
        setProfile(
          mapProfile(
            profileRow,
            metadataName || user.email || 'Usuario',
            contentAvatar?.url || metadataAvatar,
            contentAvatar?.mediaType || metadataAvatarMediaType,
          ),
        )
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
      <ProfileHeader
        name={displayName}
        username={username}
        avatarUrl={profile?.avatarUrl || ''}
        avatarMediaType={profile?.avatarMediaType}
        coverUrl={profile?.coverUrl || ''}
        frame={profile?.profileFrame}
        identity={profile?.identity || profileIdentity(metadataRow)}
        followers={stats.followers}
        following={stats.following}
        own
      />
      <div className="mobile-profile-main">
        {profile?.badges.length ? (
          <div className="profile-badges">
            {profile.badges.map((badge) => (
              <span className="profile-badge" key={badge.id}>
                {badge.icon} {badge.name}
              </span>
            ))}
          </div>
        ) : null}
        <div className="mobile-profile-actions">
          <Link className="ghost-btn" to="/app/settings">
            <Edit3 />
            Editar
          </Link>
          <button className="ghost-btn" type="button" onClick={() => void shareProfile()}>
            <Share2 />
            Compartir
          </button>
          <button
            className="ghost-btn"
            type="button"
            onClick={() => window.dispatchEvent(new Event('novvin:profile-qr'))}
          >
            <QrCode />
            Mi QR
          </button>
        </div>
        <Link className="ghost-btn profile-customize" to="/app/settings">
          <Palette size={20} />
          Personalizar perfil
        </Link>
        {shareStatus ? <small role="status">{shareStatus}</small> : null}
        {user ? <ProfileTools profileId={user.id} name={displayName} showButtons={false} /> : null}
        <div className="profile-home-switch" aria-label="Información del perfil">
          <button
            type="button"
            className={homeTab === 'home' ? 'active' : ''}
            aria-pressed={homeTab === 'home'}
            onClick={() => setHomeTab('home')}
          >
            Inicio
          </button>
          <button
            type="button"
            className={homeTab === 'about' ? 'active' : ''}
            aria-pressed={homeTab === 'about'}
            onClick={() => setHomeTab('about')}
          >
            Presentación
          </button>
        </div>
        {homeTab === 'home' ? (
          profile?.profileWelcome ? (
            <p className="profile-welcome">✦ {profile.profileWelcome}</p>
          ) : null
        ) : (
          <div className="profile-about-copy">
            <p>{profile?.bio || 'Sin presentación todavía.'}</p>
            {profile?.education ? <p>🎓 Estudios: {profile.education}</p> : null}
            {profile?.career ? <p>Profesión o carrera: {profile.career}</p> : null}
            {profile?.workplace ? <p>Trabajo: {profile.workplace}</p> : null}
            <div className="chip-row">
              {[
                ...(profile?.interests || []),
                ...(profile?.hobbies || []),
                ...(profile?.linkedGames || []),
              ].map((value) => (
                <span className="profile-badge" key={value}>
                  {value}
                </span>
              ))}
            </div>
            {profile?.favoriteBook ? <p>Libro favorito: {profile.favoriteBook}</p> : null}
            {profile?.favoriteSport ? <p>Deporte favorito: {profile.favoriteSport}</p> : null}
            {profile?.favoriteMusicUrl ? (
              <StreamingAudio controls preload="none" src={profile.favoriteMusicUrl} />
            ) : null}
            {profile?.birthDate && birthdayLabel(profile.birthDate) ? <p>🎂 Cumpleaños: {birthdayLabel(profile.birthDate)}</p> : null}
          </div>
        )}
      </div>

      {homeTab === 'home' ? <>
      <div className="mobile-profile-tabs" role="tablist" aria-label="Contenido del perfil">
        <button
          className={activeTab === 'videos' ? 'active' : ''}
          type="button"
          role="tab"
          aria-selected={activeTab === 'videos'}
          onClick={() => setActiveTab('videos')}
          title="Videos"
        >
          Videos
        </button>
        <button
          className={activeTab === 'posts' ? 'active' : ''}
          type="button"
          role="tab"
          aria-selected={activeTab === 'posts'}
          onClick={() => setActiveTab('posts')}
          title="Publicaciones"
        >
          Publicaciones
        </button>
        <button
          className={activeTab === 'shared' ? 'active' : ''}
          type="button"
          role="tab"
          aria-selected={activeTab === 'shared'}
          onClick={() => setActiveTab('shared')}
          title="Compartidos"
        >
          Compartidos
        </button>
        <button
          className={activeTab === 'favorites' ? 'active' : ''}
          type="button"
          role="tab"
          aria-selected={activeTab === 'favorites'}
          onClick={() => setActiveTab('favorites')}
          title="Favoritos"
        >
          <Star aria-hidden="true" />
        </button>
      </div>

      {activeTab === 'posts' ? (
        posts.length ? (
          <div className="mobile-profile-posts">
            {posts.map((post) => (
              <ProfilePostPreview key={post.id} media={post.media[0]} text={post.content}>
                {post.media[0]?.mediaType === 'image' ? (
                  <StreamingImage src={post.media[0].url} alt="Publicación" />
                ) : null}
                {post.media[0]?.mediaType === 'video' ? (
                  <StreamingVideo src={post.media[0].url} muted preload="metadata" />
                ) : null}
                <ExpandableText text={post.content} lines={3} />
                <Link to={`/app/feed/post/${post.id}`}>Abrir publicación</Link>
              </ProfilePostPreview>
            ))}
          </div>
        ) : (
          <StatusView title="Sin publicaciones" description="Tus publicaciones aparecerán aquí." />
        )
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
      </> : null}
    </section>
  )
}
