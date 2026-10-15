import { MediaViewer, type ViewerItem } from '../../components/MediaViewer'
import { StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { MentionSuggestions } from '../../components/MentionSuggestions'
import { insertMention, mentionQuery, type MentionCandidate } from '../../core/mentionSuggestions'
import { SafeAvatar } from '../../components/SafeAvatar'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ThumbsUp,
  ImagePlus,
  MessageCircle,
  MoreHorizontal,
  Send,
  Shuffle,
  Share2,
  X,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { ExpandableText } from '../../components/ExpandableText'
import { StatusView } from '../../components/StatusView'
import {
  createFallbackPost,
  loadFallbackFollows,
  loadFallbackFeed,
  searchFallbackProfiles,
  setFallbackPostReaction,
  uploadFallbackFile,
} from '../../core/fallbackData'
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
import { createShuffleSeed, shuffleFeedItems } from '../../features/reels/reelFeedOrder'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import { profileIdentityAliases } from '../../core/profileMediaFields'
import { SocialMoments } from '../../ui/novvin/SocialMoments'
import { runtimeStoragePreviewUrl } from '../../core/runtimeBackend'

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
  return <SafeAvatar url={url} name={name} />
}

export function FeedPage({ composerOpen = false, onCloseComposer }: FeedPageProps) {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const [webPreferences] = useWebPreferences()
  const feedCacheKey = `feed:${user?.id ?? 'anon'}`
  const [gallery, setGallery] = useState<{ items: ViewerItem[]; index: number } | null>(null)
  const [items, setItems] = useState<FeedItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [newPostText, setNewPostText] = useState('')
  const [mentionCandidates, setMentionCandidates] = useState<MentionCandidate[]>([])
  useEffect(() => {
    const query = mentionQuery(newPostText)
    if (!useUbuntu || query === null) {
      setMentionCandidates([])
      return
    }
    let active = true
    const timer = window.setTimeout(() => {
      void searchFallbackProfiles(query, 12)
        .then((result) => {
          if (active)
            setMentionCandidates(
              result.items.map((row) => ({
                id: textOf(row, ['id'], ''),
                name: textOf(row, ['display_name', 'full_name', 'username'], 'Usuario'),
                username: textOf(row, ['username'], ''),
                avatarUrl: textOf(row, ['avatar_url', 'profile_image'], ''),
              })),
            )
        })
        .catch(() => {
          if (active) setMentionCandidates([])
        })
    }, 200)
    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [newPostText, useUbuntu])
  const [discoveryTab, setDiscoveryTab] = useState<'discover' | 'following'>('discover')
  const [followingIds, setFollowingIds] = useState<Set<string>>(new Set())
  const [attachedFile, setAttachedFile] = useState<File | null>(null)
  const [previewUrl, setPreviewUrl] = useState('')
  const [shareStatus, setShareStatus] = useState('')
  useEffect(() => {
    if (!attachedFile) {
      setPreviewUrl('')
      return
    }
    const url = URL.createObjectURL(attachedFile)
    setPreviewUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [attachedFile])
  useEffect(() => {
    let active = true
    if (useUbuntu && user)
      void loadFallbackFollows('following')
        .then((result) => {
          if (active)
            setFollowingIds(new Set(result.items.flatMap((row) => profileIdentityAliases(row))))
        })
        .catch(() => undefined)
    return () => {
      active = false
    }
  }, [useUbuntu, user])
  const [posting, setPosting] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [realtimeRefreshing, setRealtimeRefreshing] = useState(false)
  const [shuffleSeed, setShuffleSeed] = useState(createShuffleSeed)
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const shuffledItems = useMemo(
    () =>
      shuffleFeedItems(
        discoveryTab === 'following'
          ? items.filter((item) => followingIds.has(item.authorId))
          : items,
        shuffleSeed,
      ),
    [items, shuffleSeed, discoveryTab, followingIds],
  )

  function reshufflePosts() {
    setShuffleSeed((currentSeed) => {
      const nextSeed = createShuffleSeed()
      return nextSeed === currentSeed ? (currentSeed + 1) >>> 0 : nextSeed
    })
  }

  const loadFeed = useCallback(
    async (options?: LoadFeedOptions) => {
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

        const missingAuthorMeta = mapped.filter(
          (item) => !item.authorName.trim() || !item.authorAvatarUrl.trim(),
        )
        const authorIds = Array.from(
          new Set(missingAuthorMeta.map((item) => item.authorId).filter(Boolean)),
        )
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
          const nextItems = mapped.map((item) => ({
            ...item,
            likedByMe: likedSet.has(item.id),
          }))
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
    },
    [feedCacheKey, useUbuntu, user],
  )

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
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'post_likes' },
        scheduleRefresh,
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'post_comments' },
        scheduleRefresh,
      )
    const subscribedChannel = subscribeRealtimeChannel(channel, {
      reason: 'feed-live',
    })

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
        setAttachedFile(null)
        await loadFeed({ silent: true })
        onCloseComposer?.()
        return
      }

      if (!supabase) return
      const profileNameGuess =
        user.user_metadata?.display_name ?? user.email?.split('@')[0] ?? 'Usuario'
      const { error: insertError } = await supabase.from('posts').insert({
        user_id: user.id,
        user_name: String(profileNameGuess),
        user_profile_image: user.user_metadata?.avatar_url ?? null,
        content: text,
        media_url: mediaUrl || null,
      })
      if (insertError) throw insertError
      setNewPostText('')
      setAttachedFile(null)
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
      {gallery ? (
        <MediaViewer
          items={gallery.items}
          initialIndex={gallery.index}
          onClose={() => setGallery(null)}
        />
      ) : null}
      <span className="sr-only" role="status">
        {realtimeRefreshing ? 'Actualizando novedades' : ''}
        {shareStatus}
      </span>
      <div className="social-discovery-tabs">
        <button
          className={discoveryTab === 'discover' ? 'tab-pill active' : 'tab-pill'}
          type="button"
          aria-pressed={discoveryTab === 'discover'}
          onClick={() => setDiscoveryTab('discover')}
        >
          Descubrir
        </button>
        <button
          className={discoveryTab === 'following' ? 'tab-pill active' : 'tab-pill'}
          type="button"
          aria-pressed={discoveryTab === 'following'}
          onClick={() => setDiscoveryTab('following')}
        >
          Seguidos
        </button>
        <details>
          <summary className="icon-btn" aria-label="Opciones del feed">
            <MoreHorizontal />
          </summary>
          <div>
            <button
              type="button"
              className="ghost-btn"
              disabled={items.length < 2}
              onClick={reshufflePosts}
            >
              <Shuffle size={18} />
              Mezclar publicaciones
            </button>
          </div>
        </details>
      </div>
      <div className="social-composer">
        <FeedAvatar
          url={String(user?.user_metadata?.avatar_url || '')}
          name={String(user?.user_metadata?.display_name || 'Tú')}
        />
        <Link className="social-composer-prompt" to="/app/social?tab=posts&compose=1">
          ¿Qué estás pensando?
        </Link>
        <Link
          className="icon-btn"
          to="/app/social?tab=posts&compose=1"
          aria-label="Publicar foto o video"
        >
          <ImagePlus />
        </Link>
      </div>
      <SocialMoments />
      {composerOpen ? (
        <div className="app-dialog-backdrop" role="presentation" onMouseDown={onCloseComposer}>
          <article
            className="surface-card feed-composer-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="new-post-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <header className="feed-composer-head row-between">
              <h2 id="new-post-title">Nueva publicación</h2>
              <button
                className="icon-btn"
                type="button"
                onClick={onCloseComposer}
                title="Cerrar"
                aria-label="Cerrar"
              >
                <X aria-hidden="true" />
              </button>
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
            {previewUrl && attachedFile ? (
              <div className="feed-composer-preview-row">
                {attachedFile.type.startsWith('video/') ? (
                  <StreamingVideo src={previewUrl} className="feed-composer-preview" controls />
                ) : (
                  <StreamingImage
                    src={previewUrl}
                    alt="Vista previa"
                    className="feed-composer-preview"
                  />
                )}
                <button
                  className="icon-btn"
                  type="button"
                  aria-label="Quitar archivo"
                  onClick={() => setAttachedFile(null)}
                >
                  <X />
                </button>
              </div>
            ) : null}
            <MentionSuggestions
              text={newPostText}
              candidates={mentionCandidates}
              actorId={user?.id}
              onSelect={(username) => setNewPostText((current) => insertMention(current, username))}
            />
            <footer className="feed-composer-actions row-between">
              <small>{newPostText.trim().length}/2200</small>
              <div className="row-actions">
                <label
                  className="icon-btn file-pick-btn"
                  title="Adjuntar imagen o video"
                  aria-label="Adjuntar imagen o video"
                >
                  <ImagePlus aria-hidden="true" />
                  <input
                    type="file"
                    accept="image/*,video/*"
                    hidden
                    onChange={(event) => {
                      const file = event.target.files?.[0] ?? null
                      setAttachedFile(file)
                      event.currentTarget.value = ''
                    }}
                  />
                </label>
                <button
                  className="primary-btn composer-publish-btn"
                  type="button"
                  disabled={posting || uploading || (!newPostText.trim() && !attachedFile)}
                  onClick={() => void createPost(attachedFile)}
                >
                  <Send aria-hidden="true" />
                  <span>{posting ? 'Publicando...' : uploading ? 'Subiendo...' : 'Publicar'}</span>
                </button>
              </div>
            </footer>
          </article>
        </div>
      ) : null}

      {shuffledItems.length === 0 ? (
        <article className="surface-card">
          <p>
            {discoveryTab === 'following'
              ? 'Las publicaciones de las personas que sigues aparecerán aquí.'
              : 'Cuando haya publicaciones, aparecerán aquí.'}
          </p>
        </article>
      ) : null}
      {shuffledItems.map((item, itemIndex) => (
        <article key={item.id} className="surface-card feed-card">
          <div className="row-between feed-head">
            <Link className="identity-link" to={profilePathFor(item.authorId, user?.id)}>
              <FeedAvatar url={item.authorAvatarUrl} name={item.authorName} />
              <span>
                <strong>{item.authorName}</strong>
                <small>{formatDateTime(item.createdAt)}</small>
              </span>
            </Link>
            <div className="feed-head-tools">
              <details className="feed-more-menu">
                <summary className="icon-btn" title="Más opciones" aria-label="Más opciones">
                  <MoreHorizontal aria-hidden="true" />
                </summary>
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
                    <StreamingVideo
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
                    <button
                      className="feed-media-open"
                      type="button"
                      key={`${item.id}-media-${index}`}
                      aria-label={`Ampliar imagen ${index + 1} de ${item.authorName}`}
                      onClick={() => {
                        const entries = item.media.filter((m) => m.mediaType !== 'file')
                        setGallery({
                          items: entries.map((m) => ({
                            url: m.url,
                            type: m.mediaType === 'video' ? 'video' : 'image',
                            title: item.authorName,
                          })),
                          index: entries.findIndex((m) => m.url === media.url),
                        })
                      }}
                    >
                      <StreamingImage
                        key={`${item.id}-media-${index}`}
                        src={runtimeStoragePreviewUrl(media.url)}
                        alt="Multimedia del post"
                        className={mediaClass}
                        loading={itemIndex < 2 ? 'eager' : 'lazy'}
                        decoding="async"
                        fetchPriority={itemIndex === 0 ? 'high' : 'auto'}
                      />{' '}
                    </button>
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
          <div className="feed-stats">
            <Link to={`/app/feed/post/${item.id}`} aria-label={`Ver ${item.likeCount} Me gusta`}>
              <ThumbsUp size={15} />
              {item.likeCount || ''}
            </Link>
            <Link to={`/app/feed/post/${item.id}#comments`}>
              {item.commentCount ? `${item.commentCount} comentarios` : ''}
            </Link>
          </div>
          <div className="feed-card-actions">
            <button
              className={item.likedByMe ? 'ghost-btn active-action' : 'ghost-btn'}
              type="button"
              aria-pressed={item.likedByMe}
              onClick={() => void toggleLike(item)}
            >
              <ThumbsUp fill={item.likedByMe ? 'currentColor' : 'none'} />
              Me gusta
            </button>
            <Link className="ghost-btn" to={`/app/feed/post/${item.id}#comments`}>
              <MessageCircle />
              Comentar
            </Link>
            <button
              className="ghost-btn"
              type="button"
              onClick={() => {
                const url = `${window.location.origin}/app/feed/post/${item.id}`
                if (navigator.share)
                  void navigator.share({ title: item.authorName, url }).catch(() => undefined)
                else
                  void navigator.clipboard
                    .writeText(url)
                    .then(() => setShareStatus('Enlace copiado'))
                    .catch(() => setShareStatus('No se pudo copiar el enlace'))
              }}
            >
              <Share2 />
              Compartir
            </button>
          </div>
        </article>
      ))}
    </section>
  )
}
