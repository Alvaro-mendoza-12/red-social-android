import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { extractMediaEntries, type MediaEntry } from '../feed/mediaUtils'
import { getOrCreateDirectChat } from '../messages/directChat'

type PublicProfileData = {
  id: string
  name: string
  username: string
  bio: string
  avatarUrl: string
  isPrivate: boolean
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

export function PublicProfilePage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const { profileId = '' } = useParams()
  const cleanProfileId = useMemo(() => profileId.trim(), [profileId])

  const [profile, setProfile] = useState<PublicProfileData | null>(null)
  const [posts, setPosts] = useState<PublicPost[]>([])
  const [isFollowing, setIsFollowing] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [followingAction, setFollowingAction] = useState(false)
  const [openingChat, setOpeningChat] = useState(false)
  const [reportReason, setReportReason] = useState('spam')
  const [reportDetails, setReportDetails] = useState('')
  const [reporting, setReporting] = useState(false)

  const isSelf = Boolean(user && cleanProfileId && user.id === cleanProfileId)
  const canSeePosts = Boolean(profile && (!profile.isPrivate || isSelf || isFollowing))

  const loadProfile = useCallback(async () => {
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

      const nextProfile: PublicProfileData = {
        id: textOf(profileRow, ['id'], cleanProfileId),
        name: textOf(profileRow, ['displayName', 'display_name', 'full_name'], 'Usuario'),
        username: textOf(profileRow, ['username'], ''),
        bio: textOf(profileRow, ['bio'], ''),
        avatarUrl: textOf(profileRow, ['avatar_url', 'photo_url', 'profile_image'], ''),
        isPrivate: Boolean(profileRow.is_private),
      }
      setProfile(nextProfile)

      if (!isSelf && user) {
        const { data: followRows, error: followError } = await supabase
          .from('follows')
          .select('id')
          .eq('follower_id', user.id)
          .eq('following_id', cleanProfileId)
          .limit(1)
        if (followError) throw followError
        setIsFollowing(Boolean((followRows ?? []).length))
      } else {
        setIsFollowing(false)
      }

      const { data: postRows, error: postsError } = await supabase
        .from('posts')
        .select('*')
        .eq('user_id', cleanProfileId)
        .order('created_at', { ascending: false })
        .limit(30)
      if (postsError) throw postsError
      setPosts(
        ((postRows ?? []) as GenericRow[]).map((row) => ({
          id: textOf(row, ['id'], crypto.randomUUID()),
          content: textOf(row, ['content', 'caption', 'description', 'text'], 'Publicación'),
          createdAt: textOf(row, ['created_at', 'updated_at'], ''),
          media: extractMediaEntries(row),
        })),
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar el perfil'))
    } finally {
      setLoading(false)
    }
  }, [cleanProfileId, isSelf, user])

  useEffect(() => {
    void loadProfile()
  }, [loadProfile])

  async function toggleFollow() {
    if (!supabase || !user || isSelf || followingAction) return
    setFollowingAction(true)
    setError(null)
    try {
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

  async function submitUserReport() {
    if (!supabase || !user || !profile || isSelf || reporting) return
    const reason = reportReason.trim()
    const details = reportDetails.trim()
    if (!reason) return

    setReporting(true)
    setError(null)
    try {
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

  if (!supabase) return <StatusView title="Supabase no configurado" description="Configura .env para usar perfiles." />
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

      <article className="surface-card section-stack">
        <div className="row-between">
          <h3>{profile.name}</h3>
          <span>{profile.username ? `@${profile.username}` : '@usuario'}</span>
        </div>
        <p>{profile.bio || 'Sin biografía'}</p>
        <div className="row-actions">
          {profile.isPrivate ? <span className="folder-tag">Cuenta privada</span> : null}
          <span className="folder-tag">Posts: {posts.length}</span>
        </div>
        {!isSelf ? (
          <div className="composer">
            <button className="ghost-btn" type="button" disabled={followingAction} onClick={() => void toggleFollow()}>
              {followingAction ? 'Procesando...' : isFollowing ? 'Dejar de seguir' : 'Seguir'}
            </button>
            <button className="ghost-btn" type="button" disabled={openingChat} onClick={() => void openDirectChat()}>
              {openingChat ? 'Abriendo...' : 'Enviar mensaje'}
            </button>
          </div>
        ) : null}
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
            ))}
          </div>
        )}
      </section>
    </section>
  )
}
