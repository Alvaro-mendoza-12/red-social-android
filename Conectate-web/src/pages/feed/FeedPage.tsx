import { useCallback, useEffect, useRef, useState } from 'react'
import { Heart, ImagePlus, MessageCircle, MoreHorizontal, Send, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { ExpandableText } from '../../components/ExpandableText'
import { StatusView } from '../../components/StatusView'
import { createFallbackPost, loadFallbackFeed, setFallbackPostReaction, uploadFallbackFile } from '../../core/fallbackData'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
  resolveSupabasePublicUrl,
} from '../../core/supabase'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useWebPreferences } from '../../core/webPreferences'
import { useAuth } from '../../core/auth/AuthProvider'
import { extractMediaEntries, type MediaEntry } from '../../features/feed/mediaUtils'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'

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

function inferPostMediaType(file: File): 'image' | 'video' | 'file' {
  if (file.type.startsWith('image/')) return 'image'
  if (file.type.startsWith('video/')) return 'video'
  return 'file'
}

function mapPost(row: GenericRow): FeedItem {
  const media = extractMediaEntries(row)
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    authorId: textOf(row, ['user_id', 'owner_id', 'author_id'], 'usuario'),
    authorName: textOf(row, ['user_name', 'displayName', 'display_name'], 'Usuario'),
    authorAvatarUrl: resolveSupabasePublicUrl(
      'avatars',
      textOf(row, ['user_profile_image', 'avatar_url', 'photo_url', 'profile_image'], ''),
    ),
    content: textOf(row, ['content', 'caption', 'description', 'text'], 'Publicación sin texto'),
    media,
    createdAt: textOf(row, ['created_at', 'updated_at'], ''),
    likeCount: Number(row.like_count ?? 0),
    commentCount: Number(row.comment_count ?? 0),
    likedByMe: Boolean(row.liked_by_me ?? row.likedByMe ?? false),
  }
}

type FeedPageProps = {
  composerOpen?: boolean
  onCloseComposer?: () => void
}

function FeedAvatar({ url, name }: { url: string; name: string }) {
  const [failedUrl, setFailedUrl] = useState('')

  if (!url || failedUrl === url) {
    return <span className="avatar-sm avatar-fallback">{initialsOf(name)}</span>
  }

  return <img src={url} alt={name} className="avatar-sm" onError={() => setFailedUrl(url)} />
}

export function FeedPage({ composerOpen = false, onCloseComposer }: FeedPageProps) {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
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
    if (useUbuntu) {
      if (!options?.silent) {
        setLoading(true)
        setError(null)
      } else {
        setRealtimeRefreshing(true)
      }
      try {
        const feed = await loadFallbackFeed(60)
        const mapped = feed.items.map(mapPost)
        setItems(mapped)
        writeCachedValue(feedCacheKey, mapped)
      } catch (caught) {
        if (!options?.silent) {
          setError(friendlyError(caught, 'No se pudo cargar el feed del servidor local'))
        }
      } finally {
        setLoading(false)
        setRealtimeRefreshing(false)
      }
      return
    }

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
            avatarUrl: resolveSupabasePublicUrl(
              'avatars',
              textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], ''),
            ),
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
      reportSupabaseServiceRestrictionIfPresent('feed-load', caught)
      if (!options?.silent) {
        setError(friendlyError(caught, 'No se pudo cargar el feed'))
      }
    } finally {
      setLoading(false)
      setRealtimeRefreshing(false)
    }
  }, [feedCacheKey, useUbuntu, user])

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
    if (useUbuntu && user?.id) {
      const scheduleRefresh = () => {
        if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current)
        refreshTimerRef.current = setTimeout(() => {
          void loadFeed({ silent: true })
        }, 260)
      }
      const unsubscribeSelf = subscribeUbuntuRealtime({
        channel: `user:${user.id}`,
        onEvent: (event) => {
          const scope = String(event.payload?.scope || '')
          if (scope === 'inbox' || event.payload?.event === 'message') scheduleRefresh()
        },
      })
      const unsubscribeFeed = subscribeUbuntuRealtime({
        channel: 'feed:live',
        onEvent: scheduleRefresh,
      })
      return () => {
        unsubscribeSelf()
        unsubscribeFeed()
        if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current)
      }
    }
    const client = supabase
    if (!client || useUbuntu) return
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
    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: 'feed-live' })

    return () => {
      if (refreshTimerRef.current) {
        clearTimeout(refreshTimerRef.current)
      }
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [loadFeed, useUbuntu, user?.id])

  async function createPost(file: File | null) {
    if (!user || posting || uploading) return
    const text = newPostText.trim()
    if (!text && !file) return

    setPosting(true)
    setError(null)
    try {
      let mediaUrl = ''
      let mediaType: 'image' | 'video' | 'file' | null = null
      if (file) {
        setUploading(true)
        mediaType = inferPostMediaType(file)
        const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
        const filePath = `${user.id}/${Date.now()}_${safeName}`
        if (useUbuntu) {
          const uploaded = await uploadFallbackFile('post-media', file, filePath)
          mediaUrl = uploaded.publicUrl
        } else if (supabase) {
          const { error: uploadError } = await supabase.storage
            .from('post-media')
            .upload(filePath, file, { contentType: file.type || undefined })
          if (uploadError) throw uploadError
          const { data } = supabase.storage.from('post-media').getPublicUrl(filePath)
          mediaUrl = data.publicUrl
        } else {
          return
        }
      }

      if (useUbuntu) {
        await createFallbackPost({
          content: text,
          mediaUrl: mediaUrl || null,
          mediaType,
        })
        setNewPostText('')
        await loadFeed({ silent: true })
        onCloseComposer?.()
        return
      }

      if (!supabase) return
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
      onCloseComposer?.()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo crear la publicación'))
    } finally {
      setUploading(false)
      setPosting(false)
    }
  }

  async function toggleLike(item: FeedItem) {
    if (useUbuntu) {
      try {
        const nextLiked = !item.likedByMe
        setItems((current) =>
          current.map((entry) =>
            entry.id === item.id
              ? {
                  ...entry,
                  likedByMe: nextLiked,
                  likeCount: Math.max(0, entry.likeCount + (nextLiked ? 1 : -1)),
                }
              : entry,
          ),
        )
        await setFallbackPostReaction(item.id, nextLiked)
        await loadFeed({ silent: true })
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo actualizar el like en Ubuntu'))
        await loadFeed({ silent: true })
      }
      return
    }
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
    <section className="section-stack feed-page">
      {realtimeRefreshing ? <small>Actualizando novedades del feed...</small> : null}

      {composerOpen ? (
        <div className="app-dialog-backdrop" role="presentation" onMouseDown={onCloseComposer}>
          <article className="surface-card feed-composer-dialog" role="dialog" aria-modal="true" aria-labelledby="new-post-title" onMouseDown={(event) => event.stopPropagation()}>
            <header className="feed-composer-head row-between">
              <h2 id="new-post-title">Nueva publicación</h2>
              <button className="icon-btn" type="button" onClick={onCloseComposer} title="Cerrar" aria-label="Cerrar"><X aria-hidden="true" /></button>
            </header>
            <textarea
              className="text-input textarea-input feed-composer-input"
              rows={5}
              maxLength={2200}
              autoFocus
              placeholder="¿Qué estás pensando?"
              aria-label="Texto de la publicación"
              value={newPostText}
              onChange={(event) => setNewPostText(event.target.value)}
            />
            <footer className="feed-composer-actions row-between">
              <small>{newPostText.trim().length}/2200</small>
              <div className="row-actions">
                <label className="icon-btn file-pick-btn" title="Adjuntar imagen o video" aria-label="Adjuntar imagen o video">
                  <ImagePlus aria-hidden="true" />
                  <input type="file" accept="image/*,video/*" hidden onChange={(event) => {
                    const file = event.target.files?.[0] ?? null
                    void createPost(file)
                    event.currentTarget.value = ''
                  }} />
                </label>
                <button className="primary-btn composer-publish-btn" type="button" disabled={posting || uploading || !newPostText.trim()} onClick={() => void createPost(null)}>
                  <Send aria-hidden="true" />
                  <span>{posting ? 'Publicando...' : uploading ? 'Subiendo...' : 'Publicar'}</span>
                </button>
              </div>
            </footer>
          </article>
        </div>
      ) : null}

      {items.length === 0 ? (
        <article className="surface-card">
          <p>No hay publicaciones todavía. Cuando alguien publique, aparecerán aquí.</p>
        </article>
      ) : null}
      {items.map((item) => (
        <article key={item.id} className="surface-card feed-card">
          <div className="row-between feed-head">
            <Link className="identity-link" to={profilePathFor(item.authorId, user?.id)}>
              <FeedAvatar url={item.authorAvatarUrl} name={item.authorName} />
              <span>
                <strong>{item.authorName}</strong>
              </span>
            </Link>
            <div className="feed-head-tools">
              <span>{formatDateTime(item.createdAt)}</span>
              <details className="feed-more-menu">
                <summary className="icon-btn" title="Más opciones" aria-label="Más opciones"><MoreHorizontal aria-hidden="true" /></summary>
                <div className="feed-more-popover">
                  <Link to={`/app/feed/post/${item.id}`}>Abrir publicación</Link>
                  <Link to={profilePathFor(item.authorId, user?.id)}>Ver perfil</Link>
                </div>
              </details>
            </div>
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
              <Heart aria-hidden="true" fill={item.likedByMe ? 'currentColor' : 'none'} />
              <span>{item.likeCount}</span>
            </button>
            <Link className="ghost-btn" to={`/app/feed/post/${item.id}#comments`}>
              <MessageCircle aria-hidden="true" />
              <span>{item.commentCount}</span>
            </Link>
          </div>
        </article>
      ))}
    </section>
  )
}
