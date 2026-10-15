import { StreamingImage, StreamingVideo, StreamingAudio } from '../../components/StreamingMedia'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { QrCodeTile } from '../../components/QrCodeTile'
import { SafeAvatar } from '../../components/SafeAvatar'
import {
  avatarMediaTypeSource,
  findContentAvatarCandidate,
  profileAvatarSource,
  profileCoverSource,
  profileIdentityAliases,
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
import { buildProfileChatQrPayload, buildProfileChatUrl, buildProfileQrPayload, buildProfileShareUrl } from '../../core/socialLinks'
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
  const message = error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
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
  const [posts, setPosts] = useState<PublicPost[]>([])
  const [stats, setStats] = useState<PublicProfileStats>({ posts: 0, followers: 0, following: 0 })
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
  const profileShareUrl = useMemo(() => (cleanProfileId ? buildProfileShareUrl(cleanProfileId) : ''), [cleanProfileId])
  const profileChatUrl = useMemo(() => (cleanProfileId ? buildProfileChatUrl(cleanProfileId) : ''), [cleanProfileId])
  const profileQrValue = useMemo(() => (cleanProfileId ? buildProfileQrPayload(cleanProfileId) : ''), [cleanProfileId])
  const chatQrValue = useMemo(() => (cleanProfileId ? buildProfileChatQrPayload(cleanProfileId) : ''), [cleanProfileId])

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
        const videoItems = videoResult.status === 'fulfilled' ? videoResult.value.items : []
        const contentAvatar = findContentAvatarCandidate(
          [...(response.posts ?? []), ...videoItems],
          profileIdentityAliases(response.profile, [cleanProfileId]),
        )
        setProfile(mapPublicProfile(
          response.profile,
          cleanProfileId,
          contentAvatar?.url,
          contentAvatar?.mediaType,
        ))
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
          : Promise.resolve({ data: [] as GenericRow[], error: null, count: 0 }),
        supabase.from('videos').select('*').eq('user_id', profileQueryId).limit(30),
        supabase.from('follows').select('id', { count: 'exact', head: true }).eq('following_id', profileQueryId),
        supabase.from('follows').select('id', { count: 'exact', head: true }).eq('follower_id', profileQueryId),
      ])
      const { data: postRows, error: postsError } = postsResult
      if (postsError) throw postsError
      const mappedPosts = ((postRows ?? []) as GenericRow[]).map(mapPublicPost)
      const contentAvatar = findContentAvatarCandidate(
        [
          ...((postRows ?? []) as GenericRow[]),
          ...((videosResult.data ?? []) as GenericRow[]),
        ],
        profileIdentityAliases(profileRow, [cleanProfileId, profileQueryId]),
      )
      if (contentAvatar) {
        setProfile(mapPublicProfile(
          profileRow,
          cleanProfileId,
          contentAvatar.url,
          contentAvatar.mediaType,
        ))
      }
      setPosts(mappedPosts)
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

  if (!supabase && !useFallback) return <StatusView title="Servidor de perfiles no configurado" description="Configura Ubuntu o Supabase para usar perfiles." />
  if (loading) return <StatusView title="Cargando perfil" description="Espera un momento..." />
  if (error && !profile) return <StatusView title="Perfil no disponible" description={error} />
  if (!profile) return <StatusView title="Perfil no encontrado" description="No existe o no está disponible." />

  return (
    <section className="section-stack">
      <div className="row-between">
        <Link to="/app/search" className="inline-link">
          ← Buscar
        </Link>
        <h2>Perfil público</h2>
      </div>

      {error ? <StatusView title="Acción no completada" description={error} /> : null}

      <article className="surface-card section-stack public-profile-card">
        <div className="public-profile-cover">
          {profile.coverUrl ? <StreamingImage src={profile.coverUrl} alt={`Portada de ${profile.name}`} /> : null}
        </div>
        <div className="public-profile-identity">
          <div className={`profile-avatar-frame profile-avatar-frame--${profile.profileFrame || 'none'}`}>
            <SafeAvatar
              url={profile.avatarUrl}
              mediaType={profile.avatarMediaType}
              name={profile.name || profile.id}
              size="lg"
              className="public-profile-avatar"
            />
          </div>
          <div>
            <h3>{profile.name}</h3>
            <span>{profile.username ? `@${profile.username}` : '@usuario'}</span>
          </div>
        </div>
        <p>{profile.bio || 'Sin biografía'}</p>
        {profile.badges.length ? (
          <div className="chip-row">
            {profile.badges.map((badge) => <span className="folder-tag" key={badge.id}>{badge.icon} {badge.name}</span>)}
          </div>
        ) : null}
        {profile.profileWelcome ? <p className="muted-line">{profile.profileWelcome}</p> : null}
        {(profile.career || profile.favoriteSport || profile.interests.length || profile.hobbies.length || profile.linkedGames.length) ? (
          <div className="chip-row">
            {profile.career ? <span className="folder-tag">{profile.career}</span> : null}
            {profile.favoriteSport ? <span className="folder-tag">{profile.favoriteSport}</span> : null}
            {profile.interests.map((interest) => <span key={`interest-${interest}`} className="folder-tag">{interest}</span>)}
            {profile.hobbies.map((hobby) => <span key={`hobby-${hobby}`} className="folder-tag">{hobby}</span>)}
            {profile.linkedGames.map((game) => <span key={`game-${game}`} className="folder-tag">{game}</span>)}
          </div>
        ) : null}
        {profile.favoriteMusicUrl ? (
          <div className="section-stack">
            <small>Música de entrada · reproducción manual</small>
            <StreamingAudio controls preload="none" src={profile.favoriteMusicUrl}>Tu navegador no puede reproducir este audio.</StreamingAudio>
          </div>
        ) : null}
        <nav className="public-profile-stats" aria-label="Estadísticas del perfil">
          <span><strong>{stats.posts}</strong><small>Publicaciones</small></span>
          <span><strong>{stats.followers}</strong><small>Seguidores</small></span>
          <span><strong>{stats.following}</strong><small>Siguiendo</small></span>
        </nav>
        <div className="row-actions wrap">
          {profile.isPrivate ? <span className="folder-tag">Cuenta privada</span> : null}
        </div>
        {!isSelf ? (
          <div className="composer">
            <button className="ghost-btn" type="button" disabled={followingAction} onClick={() => void toggleFollow()}>
              {followingAction ? 'Procesando...' : isFollowing ? 'Dejar de seguir' : 'Seguir'}
            </button>
            <button className="ghost-btn" type="button" disabled={openingChat} onClick={() => void openDirectChat()}>
              {openingChat ? 'Abriendo...' : 'Enviar mensaje'}
            </button>
            <button className="ghost-btn" type="button" onClick={() => void shareLink(profileShareUrl)}>
              Compartir perfil
            </button>
          </div>
        ) : null}
        {shareStatus ? <small className="muted-line">{shareStatus}</small> : null}
      </article>

      <article className="surface-card profile-public-qr-card">
        <div className="row-between">
          <div>
            <h3>QR social</h3>
            <p>Escanéalo desde la APK para abrir este perfil o iniciar chat.</p>
          </div>
          <div className="row-actions wrap">
            <button className="mini-action-btn" type="button" onClick={() => void copyLink(profileShareUrl, 'Perfil')}>
              Copiar perfil
            </button>
            <button className="mini-action-btn" type="button" onClick={() => void copyLink(profileChatUrl, 'Chat')}>
              Copiar chat
            </button>
          </div>
        </div>
        <div className="profile-qr-grid">
          <QrCodeTile value={profileQrValue} title="Perfil" description={profile.username ? `@${profile.username}` : profile.name} compact />
          <QrCodeTile value={chatQrValue} title="Chat" description="Conversación directa" compact />
        </div>
      </article>

      {!isSelf ? (
        <article className="surface-card section-stack">
          <h3>Reportar usuario</h3>
          <div className="composer">
            <select className="text-input tiny-input" value={reportReason} onChange={(event) => setReportReason(event.target.value)}>
              <option value="spam">Spam</option>
              <option value="acoso">Acoso</option>
              <option value="suplantacion">Suplantación</option>
              <option value="contenido_inapropiado">Contenido inapropiado</option>
              <option value="otro">Otro</option>
            </select>
            <input
              className="text-input"
              placeholder="Detalle (opcional)"
              value={reportDetails}
              onChange={(event) => setReportDetails(event.target.value)}
            />
            <button className="ghost-btn" type="button" disabled={reporting} onClick={() => void submitUserReport()}>
              {reporting ? 'Reportando...' : 'Reportar'}
            </button>
          </div>
        </article>
      ) : null}

      <section className="section-stack">
        <h3>Publicaciones</h3>
        {!canSeePosts ? (
          <StatusView title="Contenido privado" description="Sigue esta cuenta para ver sus publicaciones." />
        ) : posts.length === 0 ? (
          <p>Este perfil no tiene publicaciones todavía.</p>
        ) : (
          <div className="list-stack">
            {posts.map((post) => (
              <article key={post.id} className="surface-card">
                <div className="row-between">
                  <strong>Post</strong>
                  <small>{formatDateTime(post.createdAt)}</small>
                </div>
                <p>{post.content}</p>
                {post.media.length > 0 ? (
                  <div className={post.media.length > 1 ? 'feed-media-grid' : ''}>
                    {post.media.map((media, index) => {
                      const mediaClass = post.media.length > 1 ? 'feed-media feed-media-grid-item' : 'feed-media'
                      if (media.mediaType === 'video') {
                        return (
                          <StreamingVideo
                            key={`${post.id}-media-${index}`}
                            controls
                            preload="metadata"
                            src={media.url}
                            className={mediaClass}
                          />
                        )
                      }
                      if (media.mediaType === 'image') {
                        return (
                          <StreamingImage
                            key={`${post.id}-media-${index}`}
                            src={media.url}
                            alt="Multimedia del post"
                            className={mediaClass}
                          />
                        )
                      }
                      return (
                        <a
                          key={`${post.id}-media-${index}`}
                          className="inline-link"
                          href={media.url}
                          target="_blank"
                          rel="noreferrer"
                        >
                          Abrir archivo {index + 1}
                        </a>
                      )
                    })}
                  </div>
                ) : null}
              </article>
            ))}
          </div>
        )}
      </section>
    </section>
  )
}
