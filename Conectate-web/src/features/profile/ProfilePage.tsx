import { useEffect, useMemo, useState } from 'react'
import { QrCodeTile } from '../../components/QrCodeTile'
import { Link } from 'react-router-dom'
import { ExpandableText } from '../../components/ExpandableText'
import { StatusView } from '../../components/StatusView'
import { loadFallbackProfileMe } from '../../core/fallbackData'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { buildProfileChatQrPayload, buildProfileChatUrl, buildProfileQrPayload, buildProfileShareUrl } from '../../core/socialLinks'
import { extractMediaEntries, type MediaEntry } from '../feed/mediaUtils'

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
  coverUrl: string
  interests: string[]
  linkedGames: string[]
  gameLogoUrls: Record<string, string>
  favoriteMusicUrl: string
  favoriteBook: string
  favoriteSport: string
  career: string
  availabilitySchedule: string
  lastSeenVisibility: string
  onlineStatusVisibility: string
}

type MyPost = {
  id: string
  content: string
  createdAt: string
  media: MediaEntry[]
}

type MyReport = {
  id: string
  reason: string
  status: string
  reportedUserName: string
  createdAt: string
}

function initialsOf(name: string): string {
  const clean = name.trim()
  if (!clean) return 'U'
  return clean[0]?.toUpperCase() ?? 'U'
}

function FallbackAvatar({ url, name, className }: { url: string; name: string; className: string }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)

  if (url && failedUrl !== url) {
    return (
      <img
        src={url}
        alt={name}
        className={className}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => setFailedUrl(url)}
      />
    )
  }

  return <span className={`${className} avatar-fallback`}>{initialsOf(name)}</span>
}

function stringArrayFrom(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.map((entry) => String(entry).trim()).filter(Boolean)
}

function objectFromJson(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).map(([key, nextValue]) => [key, String(nextValue ?? '')]),
  )
}

function mapProfileData(row: GenericRow, fallbackName: string, fallbackAvatar: string): ProfileData {
  return {
    name: textOf(row, ['displayName', 'display_name', 'full_name'], fallbackName),
    username: textOf(row, ['username'], ''),
    bio: textOf(row, ['bio'], ''),
    avatarUrl: textOf(row, ['avatar_url', 'photo_url', 'profile_image'], '') || fallbackAvatar,
    coverUrl: textOf(row, ['cover_url', 'banner_url'], ''),
    interests: stringArrayFrom(row.interests),
    linkedGames: stringArrayFrom(row.linked_games),
    gameLogoUrls: objectFromJson(row.game_logo_urls),
    favoriteMusicUrl: textOf(row, ['favorite_music_url'], ''),
    favoriteBook: textOf(row, ['favorite_book'], ''),
    favoriteSport: textOf(row, ['favorite_sport'], ''),
    career: textOf(row, ['career'], ''),
    availabilitySchedule: textOf(row, ['availability_schedule'], ''),
    lastSeenVisibility: textOf(row, ['last_seen_visibility'], 'everyone'),
    onlineStatusVisibility: textOf(row, ['online_status_visibility'], 'everyone'),
  }
}

export function ProfilePage() {
  const { user, isFallbackSession } = useAuth()
  const profileShareUrl = user?.id ? buildProfileShareUrl(user.id) : ''
  const profileChatUrl = user?.id ? buildProfileChatUrl(user.id) : ''
  const profileQrValue = user?.id ? buildProfileQrPayload(user.id) : ''
  const chatQrValue = user?.id ? buildProfileChatQrPayload(user.id) : ''
  const metadataName = String(user?.user_metadata?.display_name ?? user?.user_metadata?.full_name ?? '').trim()
  const metadataAvatar = String(user?.user_metadata?.avatar_url ?? user?.user_metadata?.picture ?? '').trim()
  const [profile, setProfile] = useState<ProfileData | null>(null)
  const [myPosts, setMyPosts] = useState<MyPost[]>([])
  const [myReports, setMyReports] = useState<MyReport[]>([])
  const [stats, setStats] = useState<ProfileStats>({
    posts: 0,
    videos: 0,
    chats: 0,
    followers: 0,
    following: 0,
    reportsSent: 0,
  })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [shareStatus, setShareStatus] = useState<string | null>(null)
  const visibleInterests = useMemo(
    () => (profile?.interests.length ? profile.interests : ['anime', 'juegos', 'musica', 'libros', 'deportes']),
    [profile?.interests],
  )
  const linkedGames = useMemo(
    () => (profile?.linkedGames.length ? profile.linkedGames : ['Sin juegos vinculados']),
    [profile?.linkedGames],
  )
  const profileCompletion = useMemo(() => {
    const checks = [
      profile?.bio,
      profile?.coverUrl,
      profile?.avatarUrl,
      profile?.favoriteMusicUrl,
      profile?.favoriteBook,
      profile?.favoriteSport,
      profile?.career,
      profile?.availabilitySchedule,
      profile?.interests.length ? 'interests' : '',
      profile?.linkedGames.length ? 'games' : '',
    ]
    const filled = checks.filter((value) => String(value ?? '').trim().length > 0).length
    return Math.round((filled / checks.length) * 100)
  }, [profile])
  const profilePulseItems = useMemo(
    () => [
      { label: 'Publicaciones', value: stats.posts },
      { label: 'Videos', value: stats.videos },
      { label: 'Chats', value: stats.chats },
      { label: 'Seguidores', value: stats.followers },
      { label: 'Siguiendo', value: stats.following },
      { label: 'Reportes', value: stats.reportsSent },
    ],
    [stats],
  )
  const creatorSignals = useMemo(
    () => [
      {
        label: 'Perfil completo',
        value: `${profileCompletion}%`,
        hint: profileCompletion >= 80 ? 'Listo para presentarte mejor en la web.' : 'Todavia puedes completar mas campos.',
      },
      {
        label: 'Huella social',
        value: stats.posts + stats.videos,
        hint: 'Suma publicaciones y videos visibles desde tu cuenta.',
      },
      {
        label: 'Alcance',
        value: stats.followers,
        hint: 'Seguidores sincronizados con el backend compartido.',
      },
    ],
    [profileCompletion, stats.followers, stats.posts, stats.videos],
  )
  const profileLaunchPads = useMemo(
    () => [
      { label: 'Mensajes', description: 'Retoma conversaciones activas', to: '/app/messages' },
      { label: 'Llamadas', description: 'Salta a voice y 1:1', to: '/app/calls' },
      { label: 'Monetizacion', description: 'Creator program y wallet', to: '/app/monetization' },
      { label: 'Ajustes', description: 'Privacidad, idioma y acceso', to: '/app/settings' },
    ],
    [],
  )

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
          const response = await loadFallbackProfileMe()
          setProfile(mapProfileData(response.profile, metadataName || user.email || 'Usuario', metadataAvatar))
          setMyPosts(
            response.posts.map((row) => ({
              id: textOf(row, ['id'], crypto.randomUUID()),
              content: textOf(row, ['content', 'caption', 'description', 'text'], 'Publicación'),
              createdAt: textOf(row, ['created_at', 'updated_at'], ''),
              media: extractMediaEntries(row),
            })),
          )
          setMyReports(
            response.reports.map((row) => ({
              id: textOf(row, ['id'], crypto.randomUUID()),
              reason: textOf(row, ['reason'], 'Sin motivo'),
              status: textOf(row, ['status'], 'pendiente'),
              reportedUserName: textOf(row, ['reported_user_name'], 'Usuario'),
              createdAt: textOf(row, ['created_at'], ''),
            })),
          )
          setStats(response.stats)
          return
        }

        if (!supabase) {
          setLoading(false)
          return
        }

        const { data: profileRows, error: profileError } = await supabase
          .from('profiles')
          .select('*')
          .eq('id', user.id)
          .limit(1)

        if (profileError) throw profileError
        const row = ((profileRows ?? []) as GenericRow[])[0]
        if (row) {
          setProfile(mapProfileData(row, metadataName || user.email || 'Usuario', metadataAvatar))
        } else {
          setProfile({
            name: metadataName || user.email || 'Usuario',
            username: '',
            bio: '',
            avatarUrl: metadataAvatar,
            coverUrl: '',
            interests: [],
            linkedGames: [],
            gameLogoUrls: {},
            favoriteMusicUrl: '',
            favoriteBook: '',
            favoriteSport: '',
            career: '',
            availabilitySchedule: '',
            lastSeenVisibility: 'everyone',
            onlineStatusVisibility: 'everyone',
          })
        }

        const [
          { count: postCount },
          { count: videoCount },
          { count: chatCount },
          { count: followersCount },
          { count: followingCount },
          { count: reportsCount },
        ] = await Promise.all([
          supabase.from('posts').select('*', { count: 'exact', head: true }).eq('user_id', user.id),
          supabase
            .from('videos')
            .select('*', { count: 'exact', head: true })
            .or(`user_id.eq.${user.id},userId.eq.${user.id}`),
          supabase
            .from('chats')
            .select('*', { count: 'exact', head: true })
            .contains('participants', [user.id]),
          supabase.from('follows').select('*', { count: 'exact', head: true }).eq('following_id', user.id),
          supabase.from('follows').select('*', { count: 'exact', head: true }).eq('follower_id', user.id),
          supabase.from('reports').select('*', { count: 'exact', head: true }).eq('reporter_id', user.id),
        ])

        const { data: postsRows, error: postsError } = await supabase
          .from('posts')
          .select('*')
          .eq('user_id', user.id)
          .order('created_at', { ascending: false })
          .limit(30)
        if (postsError) throw postsError

        setMyPosts(
          ((postsRows ?? []) as GenericRow[]).map((row) => ({
            id: textOf(row, ['id'], crypto.randomUUID()),
            content: textOf(row, ['content', 'caption', 'description', 'text'], 'Publicación'),
            createdAt: textOf(row, ['created_at', 'updated_at'], ''),
            media: extractMediaEntries(row),
          })),
        )

        try {
          const { data: reportRows, error: reportsError } = await supabase
            .from('reports')
            .select('*')
            .eq('reporter_id', user.id)
            .order('created_at', { ascending: false })
            .limit(12)
          if (reportsError) throw reportsError

          setMyReports(
            ((reportRows ?? []) as GenericRow[]).map((row) => ({
              id: textOf(row, ['id'], crypto.randomUUID()),
              reason: textOf(row, ['reason'], 'Sin motivo'),
              status: textOf(row, ['status'], 'pendiente'),
              reportedUserName: textOf(row, ['reported_user_name'], 'Usuario'),
              createdAt: textOf(row, ['created_at'], ''),
            })),
          )
        } catch (caught) {
          if (!isMissingTableError(caught)) {
            throw caught
          }
          setMyReports([])
        }

        setStats({
          posts: postCount ?? 0,
          videos: videoCount ?? 0,
          chats: chatCount ?? 0,
          followers: followersCount ?? 0,
          following: followingCount ?? 0,
          reportsSent: reportsCount ?? 0,
        })
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo cargar el perfil'))
      } finally {
        setLoading(false)
      }
    }

    void loadProfile()
  }, [isFallbackSession, metadataAvatar, metadataName, user])

  async function copyProfileLink(url: string, label: string) {
    if (!url) return
    try {
      await navigator.clipboard.writeText(url)
      setShareStatus(`${label} copiado.`)
    } catch {
      setShareStatus('No se pudo copiar el enlace.')
    }
  }

  async function shareProfileLink(url: string, title: string) {
    if (!url) return
    try {
      if (navigator.share) {
        await navigator.share({ title, url })
        setShareStatus('Enlace compartido.')
        return
      }
      await copyProfileLink(url, 'Enlace')
    } catch (caught) {
      if (caught instanceof DOMException && caught.name === 'AbortError') return
      setShareStatus('No se pudo compartir el enlace.')
    }
  }

  if (loading) return <StatusView title="Cargando perfil" description="Espera un momento..." />
  if (error) return <StatusView title="Perfil no disponible" description={error} />

  return (
    <section className="section-stack profile-page-shell">
      <header className="section-header">
        <span className="brand-tag">Perfil conectado</span>
        <h2>Identidad, actividad y monetizacion sobre la misma cuenta</h2>
        <p>La web ya no deja el perfil disperso: ordena tu presencia social, accesos y estado general en un solo lugar.</p>
      </header>

      <div className="profile-dashboard-grid">
        <div className="section-stack">
          <article className="surface-card profile-hero profile-hero-upgraded">
            <div
              className="profile-cover"
              style={profile?.coverUrl ? { backgroundImage: `url(${profile.coverUrl})` } : undefined}
            />
            <div className="profile-hero-content">
              <FallbackAvatar
                url={profile?.avatarUrl ?? ''}
                name={profile?.name ?? user?.email ?? 'Usuario'}
                className="profile-avatar"
              />
              <div className="profile-hero-main">
                <div className="profile-hero-heading">
                  <div>
                    <h3>{profile?.name ?? user?.email ?? 'Usuario'}</h3>
                    <p>{profile?.username ? `@${profile.username}` : '@usuario'}</p>
                  </div>
                  <div className="profile-status-cluster">
                    <span className="shell-status-pill accent">Perfil {profileCompletion}% completo</span>
                    <span className="shell-status-pill">Ultimo acceso: {profile?.lastSeenVisibility || 'everyone'}</span>
                    <span className="shell-status-pill">Estado online: {profile?.onlineStatusVisibility || 'everyone'}</span>
                  </div>
                </div>
                <p className="profile-hero-bio">{profile?.bio || 'Sin biografia todavia. Completa tu perfil para que la version web se vea tan solida como la app.'}</p>
                <div className="row-actions wrap">
                  <Link className="ghost-btn" to="/app/settings">
                    Editar perfil
                  </Link>
                  <Link className="ghost-btn" to="/app/notifications">
                    Alertas
                  </Link>
                  <Link className="ghost-btn" to="/app/follows">
                    Seguidores
                  </Link>
                  <Link className="ghost-btn" to="/app/monetization">
                    Monetizacion
                  </Link>
                  <a className="ghost-btn" href="#mis-reportes">
                    Mis reportes
                  </a>
                </div>
                <div className="profile-pulse-grid">
                  {profilePulseItems.map((item) => (
                    <div key={item.label} className="profile-pulse-card">
                      <strong>{item.value}</strong>
                      <span>{item.label}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </article>

          <div className="profile-enterprise-grid">
            <article className="surface-card section-stack">
              <div>
                <h3>Perfil estilo Discord</h3>
                <p>Personalizacion compartida con la APK: intereses, estado, carrera y horarios visibles tambien en web.</p>
              </div>
              <div className="chip-row">
                {visibleInterests.map((interest) => (
                  <span key={interest} className="folder-tag">{interest}</span>
                ))}
              </div>
              <div className="profile-facts-grid">
                <div className="surface-subcard">
                  <strong>Carrera</strong>
                  <p>{profile?.career || 'No configurada'}</p>
                </div>
                <div className="surface-subcard">
                  <strong>Horario</strong>
                  <p>{profile?.availabilitySchedule || 'Sin horario publico'}</p>
                </div>
                <div className="surface-subcard">
                  <strong>Ultimo acceso</strong>
                  <p>{profile?.lastSeenVisibility || 'everyone'}</p>
                </div>
                <div className="surface-subcard">
                  <strong>Estado online</strong>
                  <p>{profile?.onlineStatusVisibility || 'everyone'}</p>
                </div>
              </div>
            </article>

            <article className="surface-card section-stack">
              <div>
                <h3>Gustos vinculados</h3>
                <p>Juegos, musica, libros y deporte quedan organizados sin verse apretados.</p>
              </div>
              <div className="linked-games-grid">
                {linkedGames.map((game) => {
                  const logoUrl = profile?.gameLogoUrls[game] ?? ''
                  return (
                    <span key={game} className="linked-game-pill">
                      {logoUrl ? <img src={logoUrl} alt="" /> : null}
                      {game}
                    </span>
                  )
                })}
              </div>
              <div className="profile-facts-grid">
                <div className="surface-subcard">
                  <strong>Musica</strong>
                  {profile?.favoriteMusicUrl ? (
                    <a className="inline-link" href={profile.favoriteMusicUrl} target="_blank" rel="noreferrer">
                      Abrir enlace musical
                    </a>
                  ) : (
                    <p>No configurada</p>
                  )}
                </div>
                <div className="surface-subcard">
                  <strong>Libro</strong>
                  <p>{profile?.favoriteBook || 'No configurado'}</p>
                </div>
                <div className="surface-subcard">
                  <strong>Deporte</strong>
                  <p>{profile?.favoriteSport || 'No configurado'}</p>
                </div>
              </div>
            </article>
          </div>

          <article className="surface-card section-stack profile-launchpad-card">
            <div className="row-between">
              <div>
                <h3>Atajos conectados</h3>
                <p>Accesos rapidos entre perfil, mensajes, llamadas y monetizacion.</p>
              </div>
              <span className="folder-tag">Web + APK</span>
            </div>
            <div className="profile-launchpad-grid">
              {profileLaunchPads.map((entry) => (
                <Link key={entry.label} className="profile-launchpad-item" to={entry.to}>
                  <strong>{entry.label}</strong>
                  <span>{entry.description}</span>
                </Link>
              ))}
            </div>
          </article>
        </div>

        <aside className="profile-side-column">
          <article className="surface-card section-stack profile-side-card">
            <div>
              <h3>Resumen social</h3>
              <p>Lectura rapida del estado de tu cuenta dentro del ecosistema Conectate.</p>
            </div>
            <div className="profile-side-metrics">
              {creatorSignals.map((item) => (
                <div key={item.label} className="profile-side-metric">
                  <strong>{item.value}</strong>
                  <span>{item.label}</span>
                  <p>{item.hint}</p>
                </div>
              ))}
            </div>
          </article>

          {profileQrValue ? (
            <article className="surface-card profile-qr-card">
              <div className="section-stack">
                <div>
                  <h3>QR social</h3>
                  <p>Perfil y chat directo listos para compartir desde web o APK.</p>
                </div>
                <div className="row-actions wrap">
                  <button className="ghost-btn" type="button" onClick={() => void copyProfileLink(profileShareUrl, 'Perfil')}>
                    Copiar perfil
                  </button>
                  <button className="ghost-btn" type="button" onClick={() => void shareProfileLink(profileShareUrl, 'Perfil Conectate')}>
                    Compartir
                  </button>
                  <Link className="ghost-btn" to="/login?mode=qr">
                    Login web por QR
                  </Link>
                </div>
                {shareStatus ? <small className="muted-line">{shareStatus}</small> : null}
                <div className="profile-share-box">
                  <span>{profileShareUrl}</span>
                  <button className="mini-action-btn" type="button" onClick={() => void copyProfileLink(profileChatUrl, 'Chat')}>
                    Copiar chat
                  </button>
                </div>
              </div>
              <div className="profile-qr-grid">
                <QrCodeTile
                  value={profileQrValue}
                  title={profile?.username ? `@${profile.username}` : 'Perfil Conectate'}
                  description="Abrir perfil"
                  compact
                />
                <QrCodeTile
                  value={chatQrValue}
                  title="Chat directo"
                  description="Abrir conversación"
                  compact
                />
              </div>
            </article>
          ) : null}

          <article className="surface-card section-stack profile-side-card">
            <div>
              <h3>Ruta de creador</h3>
              <p>Monetizacion, premium, gifts y earnings viven en el mismo backend que ya usa la APK.</p>
            </div>
            <div className="profile-side-metrics">
              <div className="profile-side-metric">
                <strong>{stats.posts + stats.videos}</strong>
                <span>Piezas publicadas</span>
                <p>Contenido util para activar creator program y compartir mas desde web.</p>
              </div>
              <div className="profile-side-metric">
                <strong>{stats.followers}</strong>
                <span>Seguidores</span>
                <p>Base actual visible para medir alcance antes de abrir monetizacion.</p>
              </div>
            </div>
            <Link className="primary-btn profile-inline-cta" to="/app/monetization">
              Abrir centro de monetizacion
            </Link>
          </article>

          <article className="surface-card section-stack profile-side-card">
            <div>
              <h3>Privacidad y acceso</h3>
              <p>Controles mas delicados a un clic para no perder tiempo buscando dentro de ajustes.</p>
            </div>
            <div className="profile-privacy-list">
              <div className="surface-subcard">
                <strong>Visibilidad ultimo acceso</strong>
                <p>{profile?.lastSeenVisibility || 'everyone'}</p>
              </div>
              <div className="surface-subcard">
                <strong>Visibilidad estado online</strong>
                <p>{profile?.onlineStatusVisibility || 'everyone'}</p>
              </div>
            </div>
            <div className="row-actions wrap">
              <Link className="ghost-btn" to="/app/settings">
                Privacidad
              </Link>
              <Link className="ghost-btn" to="/app/notifications">
                Alertas
              </Link>
            </div>
          </article>
        </aside>
      </div>

      <section className="section-stack">
        <h3>Mis publicaciones</h3>
        {myPosts.length === 0 ? (
          <p>No tienes publicaciones todavía.</p>
        ) : (
          <div className="list-stack">
            {myPosts.map((post) => {
              return (
                <article key={post.id} className="surface-card">
                  <div className="row-between">
                    <Link className="inline-link" to={`/app/feed/post/${post.id}`}>
                      Ver post
                    </Link>
                    <small>{formatDateTime(post.createdAt)}</small>
                  </div>
                  <ExpandableText text={post.content} lines={3} className="feed-card-text" />
                  {post.media.length > 0 ? (
                    <div className={post.media.length > 1 ? 'feed-media-grid' : ''}>
                      {post.media.map((media, index) => {
                        const mediaClass = post.media.length > 1 ? 'feed-media feed-media-grid-item' : 'feed-media'
                        if (media.mediaType === 'video') {
                          return (
                            <video
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
                            <img
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
              )
            })}
          </div>
        )}
      </section>

      <section id="mis-reportes" className="section-stack">
        <h3>Mis reportes enviados</h3>
        {myReports.length === 0 ? (
          <p>No tienes reportes recientes.</p>
        ) : (
          <div className="list-stack">
            {myReports.map((report) => (
              <article key={report.id} className="surface-card row-between">
                <div>
                  <strong>{report.reason}</strong>
                  <p>Contra: {report.reportedUserName}</p>
                  <small>{formatDateTime(report.createdAt)}</small>
                </div>
                <span className="folder-tag">{report.status}</span>
              </article>
            ))}
          </div>
        )}
      </section>
    </section>
  )
}
