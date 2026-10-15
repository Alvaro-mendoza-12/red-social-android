import { StreamingImage, StreamingVideo, StreamingAudio } from '../../components/StreamingMedia'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { MessageSquareText, Share2, QrCode, MoreHorizontal } from 'lucide-react'
import { ProfileHeader, profileIdentity, type ProfileIdentity } from '../../ui/novvin/ProfileHeader'
import { StatusView } from '../../components/StatusView'
import { QrCodeTile } from '../../components/QrCodeTile'
import { recordWebProfileVisit } from '../../core/profileVisits'
import {
  avatarMediaTypeSource,
  findContentAvatarCandidate,
  profileAvatarSource,
  profileCoverSource,
  profileIdentityAliases,
  profileVideoSource,
  profileVideoCoverSource,
} from '../../core/profileMediaFields'
import { resolveSupabasePublicUrl, supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import {
  loadFallbackPublicProfile,
  loadFallbackVideos,
  setFallbackProfileFollow,
  submitFallbackUserReport,
} from '../../core/fallbackData'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import {
  buildProfileChatQrPayload,
  buildProfileChatUrl,
  buildProfileQrPayload,
  buildProfileShareUrl,
} from '../../core/socialLinks'
import { extractMediaEntries, type MediaEntry } from '../feed/mediaUtils'
import { getOrCreateDirectChat } from '../messages/directChat'

type PublicProfileData = {
  id: string
  name: string
  username: string
  bio: string
  avatarUrl: string
  avatarMediaType: string
  coverUrl: string
  isPrivate: boolean
  profileWelcome: string
  interests: string[]
  hobbies: string[]
  linkedGames: string[]
  career: string
  favoriteSport: string
  favoriteMusicUrl: string
  badges: Array<{ id: string; name: string; icon: string }>
  profileFrame: string
  identity: ProfileIdentity
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

type PublicProfileStats = {
  posts: number
  followers: number
  following: number
}

type PublicPost = {
  id: string
  content: string
  createdAt: string
  media: MediaEntry[]
}

function isMissingFunctionError(error: unknown): boolean {
  const message =
    error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return (
    message.includes('could not find') ||
    message.includes('function') ||
    message.includes('does not exist') ||
    message.includes('42883')
  )
}

function mapPublicProfile(
  row: GenericRow,
  fallbackId: string,
  fallbackAvatar = '',
  fallbackAvatarMediaType = '',
): PublicProfileData {
  const avatarSource = profileAvatarSource(row, fallbackAvatar)
  return {
    id: textOf(row, ['id'], fallbackId),
    identity: profileIdentity(row),
    name: textOf(row, ['displayName', 'display_name', 'full_name', 'name'], 'Usuario'),
    username: textOf(row, ['username'], ''),
    bio: textOf(row, ['bio', 'description'], ''),
    avatarUrl: resolveSupabasePublicUrl('avatars', avatarSource),
    avatarMediaType: avatarMediaTypeSource(row, avatarSource, fallbackAvatarMediaType),
    coverUrl: resolveSupabasePublicUrl('covers', profileCoverSource(row)),
    isPrivate: Boolean(row.is_private ?? row.isPrivate),
    profileWelcome: textOf(row, ['profile_welcome', 'profileWelcome'], ''),
    interests: stringArrayFrom(row.interests),
    hobbies: stringArrayFrom(row.hobbies),
    linkedGames: stringArrayFrom(row.linked_games ?? row.linkedGames),
    career: textOf(row, ['career'], ''),
    favoriteSport: textOf(row, ['favorite_sport', 'favoriteSport'], ''),
    favoriteMusicUrl: textOf(row, ['favorite_music_url', 'favoriteMusicUrl'], ''),
    badges: profileBadgesFrom(row.badges),
    profileFrame: textOf(row, ['profile_frame', 'profileFrame'], ''),
  }
}

function mapPublicPost(row: GenericRow): PublicPost {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    content: textOf(row, ['content', 'caption', 'description', 'text'], 'Publicación'),
    createdAt: textOf(row, ['created_at', 'updated_at'], ''),
    media: extractMediaEntries(row),
  }
}

export function PublicProfilePage() {
  const { user, isFallbackSession } = useAuth()
  const navigate = useNavigate()
  const { profileId = '' } = useParams()
  const cleanProfileId = useMemo(() => profileId.trim(), [profileId])

  const [profile, setProfile] = useState<PublicProfileData | null>(null)
  const [activeTab, setActiveTab] = useState<'videos' | 'posts'>('videos')
  const [homeTab, setHomeTab] = useState<'home' | 'about'>('home')
  const [showQr, setShowQr] = useState(false)
  const [showReport, setShowReport] = useState(false)
  const [videos, setVideos] = useState<
    Array<{ id: string; url: string; cover: string; title: string }>
  >([])
  const [posts, setPosts] = useState<PublicPost[]>([])
  const [stats, setStats] = useState<PublicProfileStats>({
    posts: 0,
    followers: 0,
    following: 0,
  })
  const [isFollowing, setIsFollowing] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [followingAction, setFollowingAction] = useState(false)
  const [openingChat, setOpeningChat] = useState(false)
  const [reportReason, setReportReason] = useState('spam')
  const [reportDetails, setReportDetails] = useState('')
  const [reporting, setReporting] = useState(false)
  const [shareStatus, setShareStatus] = useState<string | null>(null)
  const useFallback = shouldUseUbuntuBackend(isFallbackSession)

  const isSelf = Boolean(user && cleanProfileId && user.id === cleanProfileId)
  const canSeePosts = Boolean(profile && (!profile.isPrivate || isSelf || isFollowing))
  const profileShareUrl = useMemo(
    () => (cleanProfileId ? buildProfileShareUrl(cleanProfileId) : ''),
    [cleanProfileId],
  )
  const profileChatUrl = useMemo(
    () => (cleanProfileId ? buildProfileChatUrl(cleanProfileId) : ''),
    [cleanProfileId],
  )
  const profileQrValue = useMemo(
    () => (cleanProfileId ? buildProfileQrPayload(cleanProfileId) : ''),
    [cleanProfileId],
  )
  const chatQrValue = useMemo(
    () => (cleanProfileId ? buildProfileChatQrPayload(cleanProfileId) : ''),
    [cleanProfileId],
  )

  const loadProfile = useCallback(async () => {
    if (useFallback) {
      if (!cleanProfileId) {
        setLoading(false)
        return
      }
      setLoading(true)
      setError(null)
      try {
        const [profileResult, videoResult] = await Promise.allSettled([
          loadFallbackPublicProfile(cleanProfileId),
          loadFallbackVideos(200, 0),
        ])
        if (profileResult.status === 'rejected') throw profileResult.reason
        const response = profileResult.value
        if (!isSelf)
          void recordWebProfileVisit(
            textOf(response.profile, ['id'], cleanProfileId),
            response.profile.profile_view_history_enabled !== false,
          ).catch(() => {})
        const videoItems = videoResult.status === 'fulfilled' ? videoResult.value.items : []
        const aliases = profileIdentityAliases(response.profile, [cleanProfileId])
        setVideos(
          videoItems
            .filter((row) =>
              aliases.includes(textOf(row, ['user_id', 'userId', 'owner_id', 'author_id'], '')),
            )
            .map((row) => ({
              id: textOf(row, ['id'], ''),
              url: resolveSupabasePublicUrl('videos', profileVideoSource(row)),
              cover: resolveSupabasePublicUrl('thumbnails', profileVideoCoverSource(row)),
              title: textOf(row, ['title', 'description'], 'Video'),
            })),
        )
        const contentAvatar = findContentAvatarCandidate(
          [...(response.posts ?? []), ...videoItems],
          profileIdentityAliases(response.profile, [cleanProfileId]),
        )
        setProfile(
          mapPublicProfile(
            response.profile,
            cleanProfileId,
            contentAvatar?.url,
            contentAvatar?.mediaType,
          ),
        )
        setIsFollowing(Boolean(response.isFollowing))
        setPosts((response.posts ?? []).map(mapPublicPost))
        setStats({
          posts: Number(response.stats?.posts ?? response.posts?.length ?? 0),
          followers: Number(response.stats?.followers ?? 0),
          following: Number(response.stats?.following ?? 0),
        })
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo cargar el perfil desde Ubuntu'))
      } finally {
        setLoading(false)
      }
      return
    }

    if (!supabase || !cleanProfileId) {
      setLoading(false)
      return
    }

    setLoading(true)
    setError(null)
    try {
      const { data: profileRows, error: profileError } = await supabase
        .from('profiles')
        .select('*')
        .eq('id', cleanProfileId)
        .limit(1)
      if (profileError) throw profileError
      const profileRow = ((profileRows ?? []) as GenericRow[])[0]
      if (!profileRow) {
        setError('Perfil no encontrado.')
        setLoading(false)
        return
      }

      const nextProfile = mapPublicProfile(profileRow, cleanProfileId)
      setProfile(nextProfile)

      let nextIsFollowing = false
      if (!isSelf && user) {
        const { data: followRows, error: followError } = await supabase
          .from('follows')
          .select('id')
          .eq('follower_id', user.id)
          .eq('following_id', cleanProfileId)
          .limit(1)
        if (followError) throw followError
        nextIsFollowing = Boolean((followRows ?? []).length)
        setIsFollowing(nextIsFollowing)
      } else {
        setIsFollowing(false)
      }

      const canLoadPosts = !nextProfile.isPrivate || isSelf || nextIsFollowing
      const profileQueryId = nextProfile.id || cleanProfileId
      const [postsResult, videosResult, followersResult, followingResult] = await Promise.all([
        canLoadPosts
          ? supabase
              .from('posts')
              .select('*', { count: 'exact' })
              .eq('user_id', profileQueryId)
              .order('created_at', { ascending: false })
              .limit(30)
          : Promise.resolve({
              data: [] as GenericRow[],
              error: null,
              count: 0,
            }),
        supabase.from('videos').select('*').eq('user_id', profileQueryId).limit(30),
        supabase
          .from('follows')
          .select('id', { count: 'exact', head: true })
          .eq('following_id', profileQueryId),
        supabase
          .from('follows')
          .select('id', { count: 'exact', head: true })
          .eq('follower_id', profileQueryId),
      ])
      const { data: postRows, error: postsError } = postsResult
      if (postsError) throw postsError
      const mappedPosts = ((postRows ?? []) as GenericRow[]).map(mapPublicPost)
      const contentAvatar = findContentAvatarCandidate(
        [...((postRows ?? []) as GenericRow[]), ...((videosResult.data ?? []) as GenericRow[])],
        profileIdentityAliases(profileRow, [cleanProfileId, profileQueryId]),
      )
      if (contentAvatar) {
        setProfile(
          mapPublicProfile(profileRow, cleanProfileId, contentAvatar.url, contentAvatar.mediaType),
        )
      }
      setPosts(mappedPosts)
      setVideos(
        ((videosResult.data ?? []) as GenericRow[]).map((row) => ({
          id: textOf(row, ['id'], ''),
          url: resolveSupabasePublicUrl('videos', profileVideoSource(row)),
          cover: resolveSupabasePublicUrl('thumbnails', profileVideoCoverSource(row)),
          title: textOf(row, ['title', 'description'], 'Video'),
        })),
      )
      setStats({
        posts: postsResult.count ?? mappedPosts.length,
        followers: followersResult.count ?? 0,
        following: followingResult.count ?? 0,
      })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar el perfil'))
    } finally {
      setLoading(false)
    }
  }, [cleanProfileId, isSelf, user, useFallback])

  useEffect(() => {
    void loadProfile()
  }, [loadProfile])

  async function toggleFollow() {
    if (!user || isSelf || followingAction) return
    setFollowingAction(true)
    setError(null)
    try {
      if (useFallback) {
        const response = await setFallbackProfileFollow(cleanProfileId, !isFollowing)
        setIsFollowing(Boolean(response.isFollowing))
        await loadProfile()
        return
      }

      if (!supabase) throw new Error('No hay Supabase configurado para actualizar seguimiento.')
      if (isFollowing) {
        const { error: deleteError } = await supabase
          .from('follows')
          .delete()
          .eq('follower_id', user.id)
          .eq('following_id', cleanProfileId)
        if (deleteError) throw deleteError
        setIsFollowing(false)
      } else {
        const { error: insertError } = await supabase.from('follows').insert({
          follower_id: user.id,
          following_id: cleanProfileId,
        })
        if (insertError) throw insertError
        setIsFollowing(true)
      }
      await loadProfile()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar seguimiento'))
    } finally {
      setFollowingAction(false)
    }
  }

  async function openDirectChat() {
    if (!user || !cleanProfileId || isSelf || openingChat) return
    setOpeningChat(true)
    setError(null)
    try {
      const chatId = await getOrCreateDirectChat(user.id, cleanProfileId)
      navigate(`/app/messages/${chatId}`)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo abrir chat directo'))
    } finally {
      setOpeningChat(false)
    }
  }

  async function copyLink(url: string, label: string) {
    if (!url) return
    try {
      await navigator.clipboard.writeText(url)
      setShareStatus(`${label} copiado.`)
    } catch {
      setShareStatus('No se pudo copiar el enlace.')
    }
  }

  async function shareLink(url: string) {
    if (!url) return
    try {
      if (navigator.share) {
        await navigator.share({ title: profile?.name ?? 'Perfil Novvin', url })
        setShareStatus('Enlace compartido.')
        return
      }
      await copyLink(url, 'Enlace')
    } catch (caught) {
      if (caught instanceof DOMException && caught.name === 'AbortError') return
      setShareStatus('No se pudo compartir el enlace.')
    }
  }

  async function submitUserReport() {
    if (!user || !profile || isSelf || reporting) return
    const reason = reportReason.trim()
    const details = reportDetails.trim()
    if (!reason) return

    setReporting(true)
    setError(null)
    try {
      if (useFallback) {
        await submitFallbackUserReport({
          reportedUserId: profile.id,
          reportedUserName: profile.name,
          reason,
          description: details || null,
        })
        setReportDetails('')
        setReportReason('spam')
        return
      }

      if (!supabase) throw new Error('No hay Supabase configurado para enviar reportes.')
      const reporterName = (user.email ?? 'Usuario').split('@')[0]
      const rpcResult = await supabase.rpc('secure_submit_report', {
        p_reported_user_id: profile.id,
        p_reported_user_name: profile.name,
        p_reason: reason,
        p_description: details || null,
        p_evidence_url: null,
        p_reporter_name: reporterName,
      })
      if (rpcResult.error) {
        if (!isMissingFunctionError(rpcResult.error)) throw rpcResult.error

        const withReporter = await supabase.from('reports').insert({
          reporter_id: user.id,
          reporter_name: reporterName,
          reported_user_id: profile.id,
          reported_user_name: profile.name,
          reason,
          description: details || null,
          evidence_url: null,
          status: 'pendiente',
        })
        if (withReporter.error) {
          const message = String(withReporter.error.message ?? '').toLowerCase()
          if (!message.includes('reporter_name') && !message.includes('42703')) {
            throw withReporter.error
          }
          const fallback = await supabase.from('reports').insert({
            reporter_id: user.id,
            reported_user_id: profile.id,
            reported_user_name: profile.name,
            reason,
            description: details || null,
            evidence_url: null,
            status: 'pendiente',
          })
          if (fallback.error) throw fallback.error
        }
      }
      setReportDetails('')
      setReportReason('spam')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar reporte del usuario'))
    } finally {
      setReporting(false)
    }
  }

  if (!supabase && !useFallback)
    return (
      <StatusView
        title="Servidor de perfiles no configurado"
        description="Configura Ubuntu o Supabase para usar perfiles."
      />
    )
  if (loading) return <StatusView title="Cargando perfil" description="Espera un momento..." />
  if (error && !profile) return <StatusView title="Perfil no disponible" description={error} />
  if (!profile)
    return <StatusView title="Perfil no encontrado" description="No existe o no está disponible." />

  return (
    <section className="mobile-profile-page">
      <ProfileHeader
        name={profile.name}
        username={profile.username}
        avatarUrl={profile.avatarUrl}
        avatarMediaType={profile.avatarMediaType}
        coverUrl={profile.coverUrl}
        frame={profile.profileFrame}
        identity={profile.identity}
        followers={stats.followers}
        following={stats.following}
      />
      <div className="mobile-profile-main">
        {error ? (
          <p className="error-text" role="alert">
            {error}
          </p>
        ) : null}
        {profile.badges.length ? (
          <div className="profile-badges">
            {profile.badges.map((badge) => (
              <span className="profile-badge" key={badge.id}>
                {badge.icon} {badge.name}
              </span>
            ))}
          </div>
        ) : null}
        {!isSelf ? (
          <div className="mobile-profile-actions profile-public-actions">
            <button
              className={isFollowing ? 'ghost-btn' : 'primary-btn'}
              type="button"
              disabled={followingAction}
              onClick={() => void toggleFollow()}
            >
              {followingAction ? 'Actualizando…' : isFollowing ? 'Siguiendo' : 'Seguir'}
            </button>
            <button
              className="ghost-btn"
              type="button"
              onClick={() => void shareLink(profileShareUrl)}
            >
              <Share2 />
              Compartir
            </button>
            <button
              className="ghost-btn profile-message-btn"
              type="button"
              disabled={openingChat}
              onClick={() => void openDirectChat()}
            >
              <MessageSquareText />
              {openingChat ? 'Abriendo…' : 'Mensaje'}
            </button>
          </div>
        ) : (
          <Link className="ghost-btn" to="/app/profile">
            Ir a mi perfil
          </Link>
        )}
        {profile.isPrivate ? <small>Cuenta privada</small> : null}
        {shareStatus ? <small role="status">{shareStatus}</small> : null}
        <div className="public-profile-extra">
          <button
            type="button"
            className="icon-btn"
            aria-label="Ver QR del perfil"
            onClick={() => setShowQr(true)}
          >
            <QrCode size={20} />
          </button>
          {!isSelf ? (
            <button
              type="button"
              className="icon-btn"
              aria-label="Reportar usuario"
              onClick={() => setShowReport(true)}
            >
              <MoreHorizontal size={20} />
            </button>
          ) : null}
        </div>
        <div className="profile-home-switch">
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
          profile.profileWelcome ? (
            <p className="profile-welcome">✦ {profile.profileWelcome}</p>
          ) : null
        ) : (
          <div className="profile-about-copy">
            <p>{profile.bio || 'Sin presentación todavía.'}</p>
            {profile.career ? <p>{profile.career}</p> : null}
            <div className="chip-row">
              {[...profile.interests, ...profile.hobbies, ...profile.linkedGames].map((value) => (
                <span className="profile-badge" key={value}>
                  {value}
                </span>
              ))}
            </div>
            {profile.favoriteSport ? <p>{profile.favoriteSport}</p> : null}
            {profile.favoriteMusicUrl ? (
              <StreamingAudio src={profile.favoriteMusicUrl} controls preload="none" />
            ) : null}
          </div>
        )}
      </div>
      <div className="mobile-profile-tabs" aria-label="Contenido del perfil">
        <button
          type="button"
          className={activeTab === 'videos' ? 'active' : ''}
          aria-pressed={activeTab === 'videos'}
          onClick={() => setActiveTab('videos')}
        >
          Videos
        </button>
        <button
          type="button"
          className={activeTab === 'posts' ? 'active' : ''}
          aria-pressed={activeTab === 'posts'}
          onClick={() => setActiveTab('posts')}
        >
          Publicaciones
        </button>
      </div>
      {!canSeePosts ? (
        <StatusView
          title="Contenido privado"
          description="Sigue esta cuenta para ver sus publicaciones."
        />
      ) : activeTab === 'videos' ? (
        videos.length ? (
          <div className="mobile-profile-video-grid">
            {videos.map((video) => (
              <Link key={video.id} to={`/app/reels/${video.id}`} className="mobile-profile-video">
                {video.cover ? (
                  <StreamingImage src={video.cover} alt={video.title} />
                ) : (
                  <StreamingVideo src={video.url} muted playsInline preload="metadata" />
                )}
                <span>{video.title}</span>
              </Link>
            ))}
          </div>
        ) : (
          <StatusView
            title="No hay videos aún"
            description="Los videos de este perfil aparecerán aquí."
          />
        )
      ) : posts.length ? (
        <div className="mobile-profile-posts">
          {posts.map((post) => (
            <article key={post.id} className="mobile-profile-post">
              {post.media[0]?.mediaType === 'video' ? (
                <StreamingVideo src={post.media[0].url} muted preload="metadata" />
              ) : post.media[0]?.mediaType === 'image' ? (
                <StreamingImage src={post.media[0].url} alt="Publicación" />
              ) : (
                <p>{post.content}</p>
              )}
              <Link
                to={`/app/feed/post/${post.id}`}
                aria-label={`Abrir publicación del ${formatDateTime(post.createdAt)}`}
              >
                Abrir publicación
              </Link>
            </article>
          ))}
        </div>
      ) : (
        <StatusView
          title="Sin publicaciones"
          description="Las publicaciones de este perfil aparecerán aquí."
        />
      )}
      {showQr ? (
        <div className="app-dialog-backdrop" onMouseDown={() => setShowQr(false)}>
          <section
            className="app-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="QR del perfil"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <header className="row-between">
              <h2>QR del perfil</h2>
              <button
                className="icon-btn"
                type="button"
                aria-label="Cerrar"
                onClick={() => setShowQr(false)}
              >
                ×
              </button>
            </header>
            <QrCodeTile
              value={profileQrValue}
              title={profile.name}
              description={`@${profile.username}`}
              compact
            />
            <div className="row-actions wrap">
              <button
                className="ghost-btn"
                type="button"
                onClick={() => void copyLink(profileShareUrl, 'Perfil')}
              >
                Copiar perfil
              </button>
              <button
                className="ghost-btn"
                type="button"
                onClick={() => void copyLink(profileChatUrl, 'Chat')}
              >
                Copiar chat
              </button>
            </div>
            <details>
              <summary>QR para mensaje directo</summary>
              <QrCodeTile value={chatQrValue} title="Chat" compact />
            </details>
          </section>
        </div>
      ) : null}
      {showReport ? (
        <div className="app-dialog-backdrop" onMouseDown={() => setShowReport(false)}>
          <section
            className="app-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="Reportar usuario"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <header className="row-between">
              <h2>Reportar usuario</h2>
              <button
                className="icon-btn"
                type="button"
                aria-label="Cerrar"
                onClick={() => setShowReport(false)}
              >
                ×
              </button>
            </header>
            <select
              className="text-input"
              aria-label="Motivo del reporte"
              value={reportReason}
              onChange={(event) => setReportReason(event.target.value)}
            >
              <option value="spam">Spam</option>
              <option value="acoso">Acoso</option>
              <option value="suplantacion">Suplantación</option>
              <option value="contenido_inapropiado">Contenido inapropiado</option>
              <option value="otro">Otro</option>
            </select>
            <textarea
              className="text-input"
              placeholder="Detalle (opcional)"
              value={reportDetails}
              onChange={(event) => setReportDetails(event.target.value)}
            />
            <button
              className="primary-btn"
              type="button"
              disabled={reporting}
              onClick={() => void submitUserReport()}
            >
              {reporting ? 'Enviando…' : 'Enviar reporte'}
            </button>
          </section>
        </div>
      ) : null}
    </section>
  )
}
