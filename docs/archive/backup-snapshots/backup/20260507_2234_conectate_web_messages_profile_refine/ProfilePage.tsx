import { useEffect, useState } from 'react'
import { QrCodeTile } from '../../components/QrCodeTile'
import { Link } from 'react-router-dom'
import { ExpandableText } from '../../components/ExpandableText'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
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
  const { user } = useAuth()
  const profileQrValue = user?.id ? `conectate://profile/${user.id}` : ''
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

  useEffect(() => {
    async function loadProfile() {
      if (!supabase || !user) {
        setLoading(false)
        return
      }

      setLoading(true)
      setError(null)
      try {
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
  }, [metadataAvatar, metadataName, user])

  if (loading) return <StatusView title="Cargando perfil" description="Espera un momento..." />
  if (error) return <StatusView title="Perfil no disponible" description={error} />

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Perfil</h2>
        <p>Panel completo web conectado al mismo backend de la app móvil.</p>
      </header>

      <article className="surface-card profile-hero">
        <div
          className="profile-cover"
          style={profile?.coverUrl ? { backgroundImage: `url(${profile.coverUrl})` } : undefined}
        />
        <div className="profile-hero-content">
          {profile?.avatarUrl ? (
            <img src={profile.avatarUrl} alt={profile?.name ?? 'Usuario'} className="profile-avatar" />
          ) : (
            <span className="profile-avatar avatar-fallback">{initialsOf(profile?.name ?? user?.email ?? 'Usuario')}</span>
          )}
          <div className="section-stack">
            <div>
              <h3>{profile?.name ?? user?.email ?? 'Usuario'}</h3>
              <p>{profile?.username ? `@${profile.username}` : '@usuario'}</p>
            </div>
            <p>{profile?.bio || 'Sin biografía'}</p>
            <div className="row-actions">
              <Link className="ghost-btn" to="/app/settings">
                Editar perfil
              </Link>
              <Link className="ghost-btn" to="/app/notifications">
                Alertas
              </Link>
              <a className="ghost-btn" href="#mis-reportes">
                Mis reportes
              </a>
              <Link className="ghost-btn" to="/app/follows">
                Seguidores
              </Link>
              <Link className="ghost-btn" to="/app/monetization">
                Monetización
              </Link>
            </div>
          </div>
        </div>
      </article>

      <div className="profile-enterprise-grid">
        <article className="surface-card section-stack">
          <div>
            <h3>Perfil estilo Discord</h3>
            <p>Personalización compartida con la APK: estado, intereses y gustos.</p>
          </div>
          <div className="chip-row">
            {(profile?.interests.length ? profile.interests : ['anime', 'juegos', 'música', 'libros', 'deportes']).map((interest) => (
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
              <p>{profile?.availabilitySchedule || 'Sin horario público'}</p>
            </div>
            <div className="surface-subcard">
              <strong>Último acceso</strong>
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
            <p>Juegos, música, libros y deportes visibles en web.</p>
          </div>
          <div className="linked-games-grid">
            {(profile?.linkedGames.length ? profile.linkedGames : ['Sin juegos vinculados']).map((game) => {
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
              <strong>Música</strong>
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

      <div className="stats-grid stats-grid-wide">
        <article className="surface-card stat-box">
          <strong>{stats.posts}</strong>
          <span>Publicaciones</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{stats.videos}</strong>
          <span>Videos</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{stats.chats}</strong>
          <span>Chats</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{stats.followers}</strong>
          <span>Seguidores</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{stats.following}</strong>
          <span>Siguiendo</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{stats.reportsSent}</strong>
          <span>Reportes</span>
        </article>
      </div>

      {profileQrValue ? (
        <article className="surface-card profile-qr-card">
          <div className="section-stack">
            <div>
              <h3>Mi código</h3>
              <p>Compártelo para que otro usuario abra tu perfil al instante desde Conectate móvil.</p>
            </div>
            <div className="row-actions wrap">
              <Link className="ghost-btn" to="/app/settings">
                Ajustes y QR
              </Link>
              <Link className="ghost-btn" to="/login?mode=qr">
                Login web por QR
              </Link>
            </div>
          </div>
          <QrCodeTile
            value={profileQrValue}
            title={profile?.username ? `@${profile.username}` : 'Perfil Conectate'}
            description="Este QR codifica tu perfil, no una sesión."
            compact
          />
        </article>
      ) : null}

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
