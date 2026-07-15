import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { ArrowLeft, Flag, Heart, MessageCircle, Pencil, Share2, Star, Trash2 } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { loadFallbackVideo, setFallbackVideoFavorite, setFallbackVideoReaction } from '../../core/fallbackData'
import { isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'

type VideoDetail = {
  id: string
  ownerId: string
  ownerName: string
  ownerAvatarUrl: string
  title: string
  description: string
  videoUrl: string
  createdAt: string
}

type VideoComment = {
  id: string
  userId: string
  userName: string
  userAvatarUrl: string
  content: string
  createdAt: string
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

function isMissingSchemaError(error: unknown): boolean {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return message.includes('column') || message.includes('relation') || message.includes('42p01') || message.includes('42703')
}

function profilePathFor(profileId: string, currentUserId?: string): string {
  const clean = profileId.trim()
  if (!clean) return '/app/profile'
  if (currentUserId && clean === currentUserId) return '/app/profile'
  return `/app/profile/${clean}`
}

function initialsOf(name: string): string {
  const clean = name.trim()
  if (!clean) return 'U'
  return clean[0]?.toUpperCase() ?? 'U'
}

export function VideoDetailPage() {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const navigate = useNavigate()
  const { videoId = '' } = useParams()
  const cleanVideoId = useMemo(() => videoId.trim(), [videoId])
  const [video, setVideo] = useState<VideoDetail | null>(null)
  const [comments, setComments] = useState<VideoComment[]>([])
  const [likedByMe, setLikedByMe] = useState(false)
  const [likeCount, setLikeCount] = useState(0)
  const [favoritedByMe, setFavoritedByMe] = useState(false)
  const [favoriteCount, setFavoriteCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [reportReason, setReportReason] = useState('spam')
  const [reportDetails, setReportDetails] = useState('')
  const [reporting, setReporting] = useState(false)
  const [editing, setEditing] = useState(false)
  const [editTitle, setEditTitle] = useState('')
  const [editDescription, setEditDescription] = useState('')
  const [savingEdit, setSavingEdit] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deletingCommentId, setDeletingCommentId] = useState<string | null>(null)
  const [shareCopied, setShareCopied] = useState(false)
  const isOwner = Boolean(user && video && user.id === video.ownerId)

  const load = useCallback(async () => {
    if (!cleanVideoId) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      if (useUbuntu) {
        const payload = await loadFallbackVideo(cleanVideoId)
        const row = payload.video
        const ownerId = textOf(row, ['userId', 'user_id'], '')
        setVideo({
          id: textOf(row, ['id'], ''),
          ownerId,
          ownerName: textOf(row, ['user_name', 'userName'], '') || ownerId || 'Usuario',
          ownerAvatarUrl: textOf(row, ['user_profile_image', 'userProfileImage'], ''),
          title: textOf(row, ['title'], 'Video'),
          description: textOf(row, ['description'], ''),
          videoUrl: textOf(row, ['videoUrl', 'video_url'], ''),
          createdAt: textOf(row, ['created_at'], ''),
        })
        setEditTitle(textOf(row, ['title'], 'Video'))
        setEditDescription(textOf(row, ['description'], ''))
        setLikeCount(Number(row.like_count ?? row.likeCount ?? 0))
        setLikedByMe(Boolean(row.liked_by_me ?? row.likedByMe ?? false))
        setFavoriteCount(Number(row.favorite_count ?? row.favoriteCount ?? 0))
        setFavoritedByMe(Boolean(row.favorited_by_me ?? row.favoritedByMe ?? row.is_favorite ?? false))
        setComments([])
        return
      }

      if (!supabase) {
        setError('Configura VITE_FALLBACK_API_URL para ver videos en Ubuntu.')
        return
      }

      const { data: videoRows, error: videoError } = await supabase
        .from('videos')
        .select('*')
        .eq('id', cleanVideoId)
        .limit(1)
      if (videoError) throw videoError
      const row = ((videoRows ?? []) as GenericRow[])[0]
      if (!row) {
        setError('Video no encontrado.')
        setLoading(false)
        return
      }
      const ownerId = textOf(row, ['userId', 'user_id'], '')
      let ownerName =
        textOf(row, ['user_name', 'userName', 'author_name'], '') || ownerId || 'Usuario'
      let ownerAvatarUrl = textOf(row, ['user_profile_image', 'userProfileImage'], '')
      if (ownerId && (!ownerName || ownerName === ownerId || !ownerAvatarUrl)) {
        const { data: ownerRows } = await supabase
          .from('profiles')
          .select('display_name,username,avatar_url,photo_url,profile_image')
          .eq('id', ownerId)
          .limit(1)
        const owner = ((ownerRows ?? []) as GenericRow[])[0] ?? {}
        ownerName =
          textOf(owner, ['display_name', 'username'], '') ||
          ownerName ||
          ownerId ||
          'Usuario'
        ownerAvatarUrl =
          textOf(owner, ['avatar_url', 'photo_url', 'profile_image'], '') || ownerAvatarUrl
      }

      setVideo({
        id: textOf(row, ['id'], ''),
        ownerId,
        ownerName,
        ownerAvatarUrl,
        title: textOf(row, ['title'], 'Video'),
        description: textOf(row, ['description'], ''),
        videoUrl: textOf(row, ['videoUrl', 'video_url'], ''),
        createdAt: textOf(row, ['created_at'], ''),
      })
      setEditTitle(textOf(row, ['title'], 'Video'))
      setEditDescription(textOf(row, ['description'], ''))
      setLikeCount(Number(row.likeCount ?? row.like_count ?? 0))
      setFavoriteCount(Number(row.favoriteCount ?? row.favorite_count ?? 0))

      const camelComments = await supabase
        .from('video_comments')
        .select('*')
        .eq('videoId', cleanVideoId)
        .order('created_at', { ascending: true })

      let commentRows: GenericRow[] = []
      if (!camelComments.error) {
        commentRows = (camelComments.data ?? []) as GenericRow[]
      } else {
        const snakeComments = await supabase
          .from('video_comments')
          .select('*')
          .eq('video_id', cleanVideoId)
          .order('created_at', { ascending: true })
        if (snakeComments.error) throw snakeComments.error
        commentRows = (snakeComments.data ?? []) as GenericRow[]
      }

      const commentUserIds = Array.from(
        new Set(
          commentRows
            .map((comment) => textOf(comment, ['userId', 'user_id'], '').trim())
            .filter(Boolean),
        ),
      )
      const commentUserById = new Map<string, { name: string; avatarUrl: string }>()
      if (commentUserIds.length > 0) {
        const { data: commentProfileRows } = await supabase
          .from('profiles')
          .select('id,display_name,username,avatar_url,photo_url,profile_image')
          .in('id', commentUserIds)
        ;((commentProfileRows ?? []) as GenericRow[]).forEach((profile) => {
          const id = textOf(profile, ['id'], '')
          if (!id) return
          commentUserById.set(id, {
            name: textOf(profile, ['display_name', 'username'], '') || id,
            avatarUrl: textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], ''),
          })
        })
      }

      setComments(
        commentRows.map((comment) => {
          const userId = textOf(comment, ['userId', 'user_id'], '')
          const commentUser = commentUserById.get(userId)
          const userName =
            textOf(comment, ['user_name', 'userName'], '') ||
            commentUser?.name ||
            userId
          return {
            id: textOf(comment, ['id'], crypto.randomUUID()),
            userId,
            userName,
            userAvatarUrl: commentUser?.avatarUrl ?? '',
            content: textOf(comment, ['content'], ''),
            createdAt: textOf(comment, ['created_at'], ''),
          } satisfies VideoComment
        }),
      )

      if (user) {
        const [{ data: likeRows }, favoriteResult] = await Promise.all([
          supabase
            .from('video_likes')
            .select('id')
            .eq('video_id', cleanVideoId)
            .eq('user_id', user.id)
            .limit(1),
          supabase
            .from('video_favorites')
            .select('id')
            .eq('video_id', cleanVideoId)
            .eq('user_id', user.id)
            .limit(1),
        ])
        setLikedByMe(Boolean((likeRows ?? []).length))
        if (!favoriteResult.error) {
          setFavoritedByMe(Boolean((favoriteResult.data ?? []).length))
        } else if (isMissingSchemaError(favoriteResult.error)) {
          const legacyFavorite = await supabase
            .from('video_favorites')
            .select('id')
            .eq('videoId', cleanVideoId)
            .eq('userId', user.id)
            .limit(1)
          setFavoritedByMe(Boolean((legacyFavorite.data ?? []).length))
        }
      } else {
        setLikedByMe(false)
        setFavoritedByMe(false)
      }
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar el video'))
    } finally {
      setLoading(false)
    }
  }, [cleanVideoId, useUbuntu, user])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (!supabase || !cleanVideoId) return
    const client = supabase as NonNullable<typeof supabase>
    async function trackView() {
      try {
        const { error: viewError } = await client.rpc('increment_video_views', { video_id: cleanVideoId })
        if (viewError && !isMissingFunctionError(viewError)) throw viewError
      } catch {
        // Non-blocking metric update.
      }
    }
    void trackView()
  }, [cleanVideoId, useUbuntu])

  useEffect(() => {
    const client = supabase
    if (!client || !cleanVideoId) return
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRefresh = () => {
      if (refreshTimer) {
        clearTimeout(refreshTimer)
      }
      refreshTimer = setTimeout(() => {
        void load()
      }, 260)
    }

    const channel = client
      .channel(`conectate-web-video-detail-${cleanVideoId}`)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'video_comments' }, scheduleRefresh)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'video_likes' }, scheduleRefresh)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'videos', filter: `id=eq.${cleanVideoId}` }, scheduleRefresh)
      .subscribe()

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void client.removeChannel(channel)
    }
  }, [cleanVideoId, load])

  async function toggleLike() {
    if (!user || !cleanVideoId) return
    const nextLiked = !likedByMe
    setLikedByMe(nextLiked)
    setLikeCount((current) => Math.max(0, current + (nextLiked ? 1 : -1)))
    try {
      if (useUbuntu) {
        await setFallbackVideoReaction(cleanVideoId, nextLiked)
        await load()
        return
      }
      if (!supabase) throw new Error('Backend no configurado')
      let delta = 0
      if (likedByMe) {
        const { error: deleteError } = await supabase
          .from('video_likes')
          .delete()
          .eq('video_id', cleanVideoId)
          .eq('user_id', user.id)
        if (deleteError) throw deleteError
        delta = -1
      } else {
        const { error: insertError } = await supabase.from('video_likes').insert({
          video_id: cleanVideoId,
          user_id: user.id,
        })
        if (insertError) throw insertError
        delta = 1
      }

      if (delta !== 0) {
        const { error: likeCountError } = await supabase.rpc('increment_like_count', {
          video_id: cleanVideoId,
          delta,
        })
        if (likeCountError && !isMissingFunctionError(likeCountError)) {
          throw likeCountError
        }
      }

      await load()
    } catch (caught) {
      setLikedByMe(!nextLiked)
      setLikeCount((current) => Math.max(0, current + (nextLiked ? -1 : 1)))
      setError(friendlyError(caught, 'No se pudo actualizar like'))
    }
  }

  async function toggleFavorite() {
    if (!user || !cleanVideoId) return
    const nextFavorited = !favoritedByMe
    setFavoritedByMe(nextFavorited)
    setFavoriteCount((current) => Math.max(0, current + (nextFavorited ? 1 : -1)))
    try {
      if (useUbuntu) {
        await setFallbackVideoFavorite(cleanVideoId, nextFavorited)
        await load()
        return
      }
      if (!supabase) throw new Error('Backend no configurado')

      const snakeMutation = nextFavorited
        ? await supabase.from('video_favorites').insert({ video_id: cleanVideoId, user_id: user.id })
        : await supabase.from('video_favorites').delete().eq('video_id', cleanVideoId).eq('user_id', user.id)
      if (snakeMutation.error && isMissingSchemaError(snakeMutation.error)) {
        const legacyMutation = nextFavorited
          ? await supabase.from('video_favorites').insert({ videoId: cleanVideoId, userId: user.id })
          : await supabase.from('video_favorites').delete().eq('videoId', cleanVideoId).eq('userId', user.id)
        if (legacyMutation.error) throw legacyMutation.error
      } else if (snakeMutation.error) {
        throw snakeMutation.error
      }
      await load()
    } catch (caught) {
      setFavoritedByMe(!nextFavorited)
      setFavoriteCount((current) => Math.max(0, current + (nextFavorited ? -1 : 1)))
      setError(friendlyError(caught, 'No se pudo actualizar el favorito'))
    }
  }

  async function submitComment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!supabase || !user || !cleanVideoId) return
    const content = draft.trim()
    if (!content) return
    const camelInsert = await supabase.from('video_comments').insert({
      videoId: cleanVideoId,
      userId: user.id,
      content,
    })
    if (camelInsert.error) {
      const snakeInsert = await supabase.from('video_comments').insert({
        video_id: cleanVideoId,
        user_id: user.id,
        content,
      })
      if (snakeInsert.error) {
        setError(friendlyError(snakeInsert.error, 'No se pudo comentar'))
        return
      }
    }
    setDraft('')
    await load()
  }

  async function deleteComment(commentId: string) {
    if (!supabase || !video || !user || !commentId || deletingCommentId) return
    const target = comments.find((entry) => entry.id === commentId)
    if (!target) return
    const canDelete = target.userId === user.id || video.ownerId === user.id
    if (!canDelete) return
    setDeletingCommentId(commentId)
    setError(null)
    try {
      const { error: deleteError } = await supabase
        .from('video_comments')
        .delete()
        .eq('id', commentId)
      if (deleteError) throw deleteError
      await load()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo eliminar comentario'))
    } finally {
      setDeletingCommentId(null)
    }
  }

  async function copyVideoLink() {
    if (typeof window === 'undefined') return
    const absoluteUrl = `${window.location.origin}/app/reels/${cleanVideoId}`
    try {
      await navigator.clipboard.writeText(absoluteUrl)
      setShareCopied(true)
      window.setTimeout(() => setShareCopied(false), 1800)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo copiar enlace del video'))
    }
  }

  async function saveVideoEdits() {
    if (!supabase || !video || !isOwner || savingEdit) return
    setSavingEdit(true)
    setError(null)
    try {
      const title = editTitle.trim() || video.title
      const description = editDescription.trim()
      const { error: updateError } = await supabase
        .from('videos')
        .update({ title, description })
        .eq('id', video.id)
      if (updateError) throw updateError
      setEditing(false)
      await load()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo editar el video'))
    } finally {
      setSavingEdit(false)
    }
  }

  async function deleteVideo() {
    if (!supabase || !video || !isOwner || deleting) return
    setDeleting(true)
    setError(null)
    try {
      const { error: deleteError } = await supabase.from('videos').delete().eq('id', video.id)
      if (deleteError) throw deleteError
      navigate('/app/reels', { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo eliminar el video'))
    } finally {
      setDeleting(false)
    }
  }

  async function submitVideoReport() {
    if (!supabase || !user || !video || isOwner || reporting) return
    const reason = reportReason.trim()
    const details = reportDetails.trim()
    if (!reason) return

    setReporting(true)
    setError(null)
    try {
      const rpcResult = await supabase.rpc('secure_submit_video_report', {
        p_video_id: video.id,
        p_reason: reason,
        p_details: details || null,
      })
      if (rpcResult.error) {
        if (!isMissingFunctionError(rpcResult.error)) throw rpcResult.error

        const { data: profileRows } = await supabase
          .from('profiles')
          .select('display_name,username,email')
          .eq('id', user.id)
          .limit(1)
        const profile = ((profileRows ?? []) as GenericRow[])[0] ?? {}
        const reporterName =
          textOf(profile, ['display_name', 'username'], '') ||
          textOf(profile, ['email'], '').split('@')[0] ||
          'Usuario'

        const upsertResult = await supabase.from('video_reports').upsert(
          {
            video_id: video.id,
            video_owner_id: video.ownerId,
            video_owner_name: video.ownerId,
            video_title: video.title,
            reporter_id: user.id,
            reporter_name: reporterName,
            reason,
            details: details || null,
            status: 'pendiente',
            warn_sent: false,
            strike_applied: false,
          },
          { onConflict: 'video_id,reporter_id' },
        )
        if (upsertResult.error) throw upsertResult.error
      }

      setReportDetails('')
      setReportReason('spam')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar el reporte'))
    } finally {
      setReporting(false)
    }
  }

  if (!useUbuntu && !supabase && !isFallbackAuthConfigured) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL." />
  }
  if (loading) return <StatusView title="Cargando video" description="Espera un momento..." />
  if (error && !video) {
    return (
      <section className="section-stack">
        <Link to="/app/reels" className="inline-link">
          <ArrowLeft aria-hidden="true" size={17} /> Volver a reels
        </Link>
        <StatusView title="No se pudo abrir el video" description={error} />
      </section>
    )
  }
  if (!video) return <StatusView title="Video no encontrado" description="No existe o no está disponible." />

  return (
    <section className="section-stack">
      <div className="row-between">
        <Link to="/app/reels" className="inline-link">
          <ArrowLeft aria-hidden="true" size={17} /> Reels
        </Link>
        <h2>Detalle del video</h2>
      </div>
      {error ? <div className="voice-room-alert" role="status">{error}</div> : null}
      <article className="surface-card">
        <h3>{video.title}</h3>
        <small>{formatDateTime(video.createdAt)}</small>
        <div className="row-actions">
          <Link className="identity-link compact" to={profilePathFor(video.ownerId, user?.id)}>
            {video.ownerAvatarUrl ? (
              <img src={video.ownerAvatarUrl} alt={video.ownerName || 'Autor'} className="avatar-sm" />
            ) : (
              <span className="avatar-sm avatar-fallback">{initialsOf(video.ownerName || video.ownerId)}</span>
            )}
            <span>@{video.ownerName || video.ownerId}</span>
          </Link>
        </div>
        <video controls preload="metadata" src={video.videoUrl} className="video-frame" />
        <p>{video.description || 'Sin descripción'}</p>
        <div className="row-actions">
          <button className="ghost-btn" type="button" onClick={() => void toggleLike()} aria-label="Me gusta" aria-pressed={likedByMe} title="Me gusta">
            <Heart aria-hidden="true" fill={likedByMe ? 'currentColor' : 'none'} size={18} />
            <span>{likeCount}</span>
          </button>
          <button className={favoritedByMe ? 'ghost-btn active-action' : 'ghost-btn'} type="button" onClick={() => void toggleFavorite()} aria-label="Favorito" aria-pressed={favoritedByMe} title="Favorito">
            <Star aria-hidden="true" fill={favoritedByMe ? 'currentColor' : 'none'} color={favoritedByMe ? '#FFC107' : 'currentColor'} size={18} />
            <span>{favoriteCount}</span>
          </button>
          <span className="folder-tag"><MessageCircle aria-hidden="true" size={16} /> {comments.length}</span>
          <button className="chip-btn" type="button" onClick={() => void copyVideoLink()}>
            <Share2 aria-hidden="true" size={17} />
            {shareCopied ? 'Enlace copiado' : 'Compartir enlace'}
          </button>
          {isOwner ? (
            <>
              <button className="chip-btn" type="button" onClick={() => setEditing((current) => !current)}>
                <Pencil aria-hidden="true" size={16} />
                {editing ? 'Cancelar edición' : 'Editar video'}
              </button>
              <button className="chip-btn" type="button" disabled={deleting} onClick={() => void deleteVideo()}>
                <Trash2 aria-hidden="true" size={16} />
                {deleting ? 'Eliminando...' : 'Eliminar video'}
              </button>
            </>
          ) : user ? (
            <button className="chip-btn" type="button" disabled={reporting} onClick={() => void submitVideoReport()}>
              <Flag aria-hidden="true" size={16} />
              {reporting ? 'Reportando...' : 'Reportar video'}
            </button>
          ) : null}
        </div>
        {isOwner && editing ? (
          <div className="section-stack" style={{ marginTop: '0.7rem' }}>
            <div className="composer">
              <input
                className="text-input"
                placeholder="Título"
                value={editTitle}
                onChange={(event) => setEditTitle(event.target.value)}
              />
              <input
                className="text-input"
                placeholder="Descripción"
                value={editDescription}
                onChange={(event) => setEditDescription(event.target.value)}
              />
              <button className="primary-btn" type="button" disabled={savingEdit} onClick={() => void saveVideoEdits()}>
                {savingEdit ? 'Guardando...' : 'Guardar cambios'}
              </button>
            </div>
          </div>
        ) : null}
        {!isOwner && user ? (
          <div className="section-stack" style={{ marginTop: '0.7rem' }}>
            <div className="composer">
              <select
                className="text-input tiny-input"
                value={reportReason}
                onChange={(event) => setReportReason(event.target.value)}
              >
                <option value="spam">Spam</option>
                <option value="acoso">Acoso</option>
                <option value="violencia">Violencia</option>
                <option value="contenido_inapropiado">Contenido inapropiado</option>
                <option value="suplantacion">Suplantación</option>
                <option value="otro">Otro</option>
              </select>
              <input
                className="text-input"
                placeholder="Detalles del reporte (opcional)"
                value={reportDetails}
                onChange={(event) => setReportDetails(event.target.value)}
              />
            </div>
          </div>
        ) : null}
      </article>

      <section className="section-stack">
        <h3>Comentarios</h3>
        <form className="composer" onSubmit={submitComment}>
          <input
            className="text-input"
            placeholder="Comenta este video..."
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
          <button className="primary-btn" type="submit">
            Comentar
          </button>
        </form>
        {comments.length === 0 ? (
          <p>No hay comentarios aún.</p>
        ) : (
          <div className="list-stack">
            {comments.map((comment) => (
              <article key={comment.id} className="surface-card">
                <div className="row-between">
                  <Link className="identity-link compact" to={profilePathFor(comment.userId, user?.id)}>
                    {comment.userAvatarUrl ? (
                      <img src={comment.userAvatarUrl} alt={comment.userName || comment.userId} className="avatar-sm" />
                    ) : (
                      <span className="avatar-sm avatar-fallback">{initialsOf(comment.userName || comment.userId)}</span>
                    )}
                    <span>{comment.userName || comment.userId}</span>
                  </Link>
                  <small>{formatDateTime(comment.createdAt)}</small>
                </div>
                <p>{comment.content}</p>
                {user && (comment.userId === user.id || isOwner) ? (
                  <div className="row-actions">
                    <button
                      className="mini-action-btn danger"
                      type="button"
                      disabled={deletingCommentId === comment.id}
                      onClick={() => void deleteComment(comment.id)}
                    >
                      {deletingCommentId === comment.id ? 'Eliminando...' : 'Eliminar comentario'}
                    </button>
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
