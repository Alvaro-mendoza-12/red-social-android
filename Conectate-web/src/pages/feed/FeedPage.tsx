import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ExpandableText } from '../../components/ExpandableText'
import { StatusView } from '../../components/StatusView'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useWebPreferences } from '../../core/webPreferences'
import { useAuth } from '../../core/auth/AuthProvider'
import { extractMediaEntries, type MediaEntry } from '../../features/feed/mediaUtils'

type FeedItem = {
  id: string
  authorId: string
  authorName: string
  authorAvatarUrl: string
  content: string
  media: MediaEntry[]
  createdAt: string
  likeCount: number
  commentCount: number
  likedByMe: boolean
}

type LoadFeedOptions = {
  silent?: boolean
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

function mapPost(row: GenericRow): FeedItem {
  const media = extractMediaEntries(row)
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    authorId: textOf(row, ['user_id', 'owner_id', 'author_id'], 'usuario'),
    authorName: textOf(row, ['user_name', 'displayName', 'display_name'], 'Usuario'),
    authorAvatarUrl: textOf(row, ['user_profile_image', 'avatar_url', 'photo_url', 'profile_image'], ''),
    content: textOf(row, ['content', 'caption', 'description', 'text'], 'Publicación sin texto'),
    media,
    createdAt: textOf(row, ['created_at', 'updated_at'], ''),
    likeCount: Number(row.like_count ?? 0),
    commentCount: Number(row.comment_count ?? 0),
    likedByMe: false,
  }
}

export function FeedPage() {
  const { user } = useAuth()
  const [webPreferences] = useWebPreferences()
  const feedCacheKey = `feed:${user?.id ?? 'anon'}`
  const [items, setItems] = useState<FeedItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [newPostText, setNewPostText] = useState('')
  const [posting, setPosting] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [realtimeRefreshing, setRealtimeRefreshing] = useState(false)
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const loadFeed = useCallback(async (options?: LoadFeedOptions) => {
    if (!supabase) {
      setError('Supabase no está configurado.')
      setLoading(false)
      return
    }
    if (!options?.silent) {
      setLoading(true)
    } else {
      setRealtimeRefreshing(true)
    }
    if (!options?.silent) {
      setError(null)
    }
    try {
      const { data, error: queryError } = await supabase
        .from('posts')
        .select('*')
        .order('created_at', { ascending: false })
        .limit(60)
      if (queryError) throw queryError
      let mapped = ((data ?? []) as GenericRow[]).map(mapPost)

      const missingAuthorMeta = mapped.filter((item) => !item.authorName.trim() || !item.authorAvatarUrl.trim())
      const authorIds = Array.from(new Set(missingAuthorMeta.map((item) => item.authorId).filter(Boolean)))
      if (authorIds.length > 0) {
        const { data: profileRows } = await supabase
          .from('profiles')
          .select('id,display_name,username,avatar_url,photo_url,profile_image')
          .in('id', authorIds)
        const profileById = new Map<string, { name: string; avatarUrl: string }>()
        ;((profileRows ?? []) as GenericRow[]).forEach((profile) => {
          const id = textOf(profile, ['id'], '')
          if (!id) return
          profileById.set(id, {
            name: textOf(profile, ['display_name', 'username'], '') || id,
            avatarUrl: textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], ''),
          })
        })
        mapped = mapped.map((item) => {
          const profile = profileById.get(item.authorId)
          if (!profile) return item
          return {
            ...item,
            authorName: item.authorName.trim() || profile.name,
            authorAvatarUrl: item.authorAvatarUrl.trim() || profile.avatarUrl,
          }
        })
      }

      if (user && mapped.length > 0) {
        const ids = mapped.map((item) => item.id)
        const { data: likesRows } = await supabase
          .from('post_likes')
          .select('post_id')
          .eq('user_id', user.id)
          .in('post_id', ids)
        const likedSet = new Set(
          ((likesRows ?? []) as GenericRow[]).map((row) => textOf(row, ['post_id'], '')),
        )
        const nextItems = mapped.map((item) => ({ ...item, likedByMe: likedSet.has(item.id) }))
        setItems(nextItems)
        writeCachedValue(feedCacheKey, nextItems)
      } else {
        setItems(mapped)
        writeCachedValue(feedCacheKey, mapped)
      }
    } catch (caught) {
      if (!options?.silent) {
        setError(friendlyError(caught, 'No se pudo cargar el feed'))
      }
    } finally {
      setLoading(false)
      setRealtimeRefreshing(false)
    }
  }, [feedCacheKey, user])

  useEffect(() => {
    const cached = readCachedValue<FeedItem[]>(feedCacheKey, 1000 * 60 * 60 * 8)
    if (cached && cached.length > 0) {
      setItems(cached)
      setLoading(false)
      void loadFeed({ silent: true })
      return
    }
    void loadFeed({ silent: false })
  }, [feedCacheKey, loadFeed])

  useEffect(() => {
    const client = supabase
    if (!client) return
    const scheduleRefresh = () => {
      if (refreshTimerRef.current) {
        clearTimeout(refreshTimerRef.current)
      }
      refreshTimerRef.current = setTimeout(() => {
        void loadFeed({ silent: true })
      }, 260)
    }

    const channel = client
      .channel('conectate-web-feed-live')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'posts' }, scheduleRefresh)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'post_likes' }, scheduleRefresh)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'post_comments' }, scheduleRefresh)
      .subscribe()

    return () => {
      if (refreshTimerRef.current) {
        clearTimeout(refreshTimerRef.current)
      }
      void client.removeChannel(channel)
    }
  }, [loadFeed])

  async function createPost(file: File | null) {
    if (!supabase || !user || posting || uploading) return
    const text = newPostText.trim()
    if (!text && !file) return

    setPosting(true)
    setError(null)
    try {
      let mediaUrl = ''
      if (file) {
        setUploading(true)
        const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
        const filePath = `${user.id}/${Date.now()}_${safeName}`
        const { error: uploadError } = await supabase.storage
          .from('post-media')
          .upload(filePath, file, { contentType: file.type || undefined })
        if (uploadError) throw uploadError
        const { data } = supabase.storage.from('post-media').getPublicUrl(filePath)
        mediaUrl = data.publicUrl
      }

      const profileNameGuess = user.user_metadata?.display_name ?? user.email?.split('@')[0] ?? 'Usuario'
      const { error: insertError } = await supabase.from('posts').insert({
        user_id: user.id,
        user_name: String(profileNameGuess),
        user_profile_image: user.user_metadata?.avatar_url ?? null,
        content: text,
        media_url: mediaUrl || null,
      })
      if (insertError) throw insertError
      setNewPostText('')
      await loadFeed({ silent: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo crear la publicación'))
    } finally {
      setUploading(false)
      setPosting(false)
    }
  }

  async function toggleLike(item: FeedItem) {
    if (!supabase || !user) return
    try {
      if (item.likedByMe) {
        const { error: unlikeError } = await supabase
          .from('post_likes')
          .delete()
          .eq('post_id', item.id)
          .eq('user_id', user.id)
        if (unlikeError) throw unlikeError
      } else {
        const { error: likeError } = await supabase.from('post_likes').insert({
          post_id: item.id,
          user_id: user.id,
        })
        if (likeError) throw likeError
      }
      await loadFeed({ silent: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar el like'))
    }
  }

  if (loading) {
    return <StatusView title="Cargando feed" description="Trayendo publicaciones..." />
  }

  if (error) {
    return <StatusView title="Feed no disponible" description={error} />
  }

  return (
    <section className="section-stack">
      <article className="surface-card feed-hero-card">
        <div className="feed-hero-copy">
          <div>
            <span className="brand-tag">Comunidad</span>
            <h3>Publicaciones más limpias, compactas y fáciles de leer</h3>
            <p>
              El modo web ahora resume textos largos, prioriza el contenido reciente y mantiene la
              misma línea visual del móvil.
            </p>
          </div>
          <div className="feed-hero-stats">
            <div className="feed-hero-stat">
              <strong>{items.length}</strong>
              <span>posts visibles</span>
            </div>
            <div className="feed-hero-stat">
              <strong>{items.filter((item) => item.media.length > 0).length}</strong>
              <span>con multimedia</span>
            </div>
            <div className="feed-hero-stat">
              <strong>{webPreferences.lowDataMode ? 'ON' : 'OFF'}</strong>
              <span>ahorro de datos</span>
            </div>
          </div>
        </div>
      </article>

      {realtimeRefreshing ? <small>Actualizando novedades del feed...</small> : null}

      <article className="surface-card feed-composer-card">
        <div className="row-between feed-composer-head">
          <div>
            <h3>Crear publicación</h3>
            <p>Texto corto, media opcional y ritmo visual más social.</p>
          </div>
          {webPreferences.lowDataMode ? <span className="folder-tag">Ahorro de datos</span> : null}
        </div>

        <div className="composer feed-composer">
          <textarea
            className="text-input textarea-input feed-composer-input"
            rows={3}
            maxLength={2200}
            placeholder="¿Qué está pasando en tu comunidad?"
            value={newPostText}
            onChange={(event) => setNewPostText(event.target.value)}
          />
          <div className="row-between feed-composer-actions">
            <small className="feed-composer-hint">
              {newPostText.trim().length}/2200 caracteres
            </small>
            <div className="row-actions wrap">
              <label className="ghost-btn file-pick-btn">
                Adjuntar archivo
                <input
                  type="file"
                  hidden
                  onChange={(event) => {
                    const file = event.target.files?.[0] ?? null
                    void createPost(file)
                    event.currentTarget.value = ''
                  }}
                />
              </label>
              <button
                className="primary-btn"
                type="button"
                disabled={posting || uploading || !newPostText.trim()}
                onClick={() => void createPost(null)}
              >
                {posting ? 'Publicando...' : uploading ? 'Subiendo...' : 'Publicar'}
              </button>
            </div>
          </div>
        </div>
      </article>

      {items.length === 0 ? (
        <article className="surface-card">
          <p>No hay publicaciones todavía. Cuando alguien publique, aparecerán aquí.</p>
        </article>
      ) : null}
      {items.map((item) => (
        <article key={item.id} className="surface-card feed-card">
          <div className="row-between feed-head">
            <Link className="identity-link" to={profilePathFor(item.authorId, user?.id)}>
              {item.authorAvatarUrl ? (
                <img src={item.authorAvatarUrl} alt={item.authorName} className="avatar-sm" />
              ) : (
                <span className="avatar-sm avatar-fallback">{initialsOf(item.authorName)}</span>
              )}
              <span>
                <strong>{item.authorName}</strong>
                <small>@{item.authorId}</small>
              </span>
            </Link>
            <span>{formatDateTime(item.createdAt)}</span>
          </div>
          <ExpandableText text={item.content} lines={3} className="feed-card-text" />
          {item.media.length > 0 ? (
            <div className={item.media.length > 1 ? 'feed-media-grid' : ''}>
              {item.media.map((media, index) => {
                const mediaClass =
                  item.media.length > 1 ? 'feed-media feed-media-grid-item' : 'feed-media'
                if (media.mediaType === 'video') {
                  return (
                    <video
                      key={`${item.id}-media-${index}`}
                      controls
                      preload={webPreferences.lowDataMode ? 'none' : 'metadata'}
                      src={media.url}
                      className={mediaClass}
                    />
                  )
                }
                if (media.mediaType === 'image') {
                  return (
                    <img
                      key={`${item.id}-media-${index}`}
                      src={media.url}
                      alt="Multimedia del post"
                      className={mediaClass}
                    />
                  )
                }
                return (
                  <a
                    key={`${item.id}-media-${index}`}
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
          <div className="row-actions feed-card-actions wrap">
            <button className={item.likedByMe ? 'ghost-btn active-action' : 'ghost-btn'} type="button" onClick={() => void toggleLike(item)}>
              {item.likedByMe ? 'Te gusta' : 'Me gusta'} ({item.likeCount})
            </button>
            <span>Comentarios: {item.commentCount}</span>
            <Link className="ghost-btn" to={`/app/feed/post/${item.id}`}>
              Ver post
            </Link>
          </div>
        </article>
      ))}
    </section>
  )
}
