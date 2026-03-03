import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../auth/AuthProvider'
import { extractMediaEntries, type MediaEntry } from './mediaUtils'

type PostDetail = {
  id: string
  userId: string
  userName: string
  userAvatarUrl: string
  content: string
  media: MediaEntry[]
  createdAt: string
}

type PostComment = {
  id: string
  postId: string
  userId: string
  userName: string
  userAvatarUrl: string
  content: string
  createdAt: string
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

export function PostDetailPage() {
  const { user } = useAuth()
  const { postId = '' } = useParams()
  const cleanPostId = useMemo(() => postId.trim().toLowerCase(), [postId])
  const [post, setPost] = useState<PostDetail | null>(null)
  const [comments, setComments] = useState<PostComment[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [commentDraft, setCommentDraft] = useState('')

  const load = useCallback(async () => {
    if (!supabase || !cleanPostId) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const { data: postRows, error: postError } = await supabase
        .from('posts')
        .select('*')
        .eq('id', cleanPostId)
        .limit(1)
      if (postError) throw postError
      const row = ((postRows ?? []) as GenericRow[])[0]
      if (!row) {
        setError('Publicación no encontrada.')
        setLoading(false)
        return
      }

      const postUserId = textOf(row, ['user_id'], '')

      const { data: commentRows, error: commentError } = await supabase
        .from('post_comments')
        .select('*')
        .eq('post_id', cleanPostId)
        .order('created_at', { ascending: true })
      if (commentError) throw commentError

      const mappedComments = (commentRows ?? []) as GenericRow[]
      const profileIds = Array.from(
        new Set([
          postUserId,
          ...mappedComments.map((comment) => textOf(comment, ['user_id'], '').trim()),
        ].filter(Boolean)),
      )
      const profileById = new Map<string, { name: string; avatarUrl: string }>()
      if (profileIds.length > 0) {
        const { data: profileRows } = await supabase
          .from('profiles')
          .select('id,display_name,username,avatar_url,photo_url,profile_image')
          .in('id', profileIds)
        ;((profileRows ?? []) as GenericRow[]).forEach((profile) => {
          const id = textOf(profile, ['id'], '')
          if (!id) return
          profileById.set(id, {
            name: textOf(profile, ['display_name', 'username'], '') || id,
            avatarUrl: textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], ''),
          })
        })
      }

      const postProfile = profileById.get(postUserId)
      setPost({
        id: textOf(row, ['id'], ''),
        userId: postUserId,
        userName: textOf(row, ['user_name'], '') || postProfile?.name || 'Usuario',
        userAvatarUrl: textOf(row, ['user_profile_image', 'avatar_url'], '') || postProfile?.avatarUrl || '',
        content: textOf(row, ['content'], ''),
        media: extractMediaEntries(row),
        createdAt: textOf(row, ['created_at'], ''),
      })

      setComments(
        mappedComments.map((comment) => {
          const userId = textOf(comment, ['user_id'], '')
          const profile = profileById.get(userId)
          return {
            id: textOf(comment, ['id'], crypto.randomUUID()),
            postId: textOf(comment, ['post_id'], ''),
            userId,
            userName: profile?.name || userId || 'Usuario',
            userAvatarUrl: profile?.avatarUrl || '',
            content: textOf(comment, ['content'], ''),
            createdAt: textOf(comment, ['created_at'], ''),
          } satisfies PostComment
        }),
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar la publicación'))
    } finally {
      setLoading(false)
    }
  }, [cleanPostId])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    const client = supabase
    if (!client || !cleanPostId) return
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRefresh = () => {
      if (refreshTimer) {
        clearTimeout(refreshTimer)
      }
      refreshTimer = setTimeout(() => {
        void load()
      }, 240)
    }
    const channel = client
      .channel(`post-detail-${cleanPostId}`)
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'post_comments', filter: `post_id=eq.${cleanPostId}` },
        scheduleRefresh,
      )
      .subscribe()
    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void client.removeChannel(channel)
    }
  }, [cleanPostId, load])

  async function submitComment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!supabase || !user || !cleanPostId) return
    const content = commentDraft.trim()
    if (!content) return
    const { error: insertError } = await supabase.from('post_comments').insert({
      post_id: cleanPostId,
      user_id: user.id,
      content,
    })
    if (insertError) {
      setError(friendlyError(insertError, 'No se pudo comentar'))
      return
    }
    setCommentDraft('')
    await load()
  }

  async function sharePost() {
    if (!supabase || !user || !cleanPostId) return
    const { error: shareError } = await supabase.from('shares').insert({
      user_id: user.id,
      post_id: cleanPostId,
    })
    if (shareError) {
      setError(friendlyError(shareError, 'No se pudo compartir'))
      return
    }
  }

  if (!supabase) return <StatusView title="Supabase no configurado" description="Configura .env para usar posts." />
  if (loading) return <StatusView title="Cargando publicación" description="Espera un momento..." />
  if (error && !post) {
    return (
      <section className="section-stack">
        <Link to="/app/feed" className="inline-link">
          ← Volver al feed
        </Link>
        <StatusView title="No se pudo abrir el post" description={error} />
      </section>
    )
  }
  if (!post) return <StatusView title="Post no encontrado" description="No existe o no está disponible." />

  return (
    <section className="section-stack">
      <div className="row-between">
        <Link to="/app/feed" className="inline-link">
          ← Feed
        </Link>
        <h2>Detalle del post</h2>
      </div>

      {error ? (
        <article className="surface-card">
          <small>{error}</small>
        </article>
      ) : null}

      <article className="surface-card">
        <div className="row-between">
          <Link className="identity-link" to={profilePathFor(post.userId, user?.id)}>
            {post.userAvatarUrl ? (
              <img src={post.userAvatarUrl} alt={post.userName} className="avatar-sm" />
            ) : (
              <span className="avatar-sm avatar-fallback">{initialsOf(post.userName)}</span>
            )}
            <span>
              <strong>{post.userName}</strong>
              <small>@{post.userId}</small>
            </span>
          </Link>
          <small>{formatDateTime(post.createdAt)}</small>
        </div>
        <p>{post.content || 'Sin texto'}</p>
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
        <div className="row-actions">
          <button className="ghost-btn" type="button" onClick={() => void sharePost()}>
            Compartir
          </button>
        </div>
      </article>

      <section className="section-stack">
        <h3>Comentarios ({comments.length})</h3>
        <form className="composer" onSubmit={submitComment}>
          <input
            className="text-input"
            placeholder="Escribe un comentario..."
            value={commentDraft}
            onChange={(event) => setCommentDraft(event.target.value)}
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
                  <Link className="identity-link" to={profilePathFor(comment.userId, user?.id)}>
                    {comment.userAvatarUrl ? (
                      <img src={comment.userAvatarUrl} alt={comment.userName} className="avatar-sm" />
                    ) : (
                      <span className="avatar-sm avatar-fallback">{initialsOf(comment.userName)}</span>
                    )}
                    <span>
                      <strong>{comment.userName}</strong>
                      <small>@{comment.userId}</small>
                    </span>
                  </Link>
                  <small>{formatDateTime(comment.createdAt)}</small>
                </div>
                <p>{comment.content}</p>
              </article>
            ))}
          </div>
        )}
      </section>
    </section>
  )
}
