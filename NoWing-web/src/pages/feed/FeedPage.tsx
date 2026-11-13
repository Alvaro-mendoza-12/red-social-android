import {Fragment} from 'react'
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
import { SocialPromotion } from '../../components/SocialPromotion'
import { SocialMoments } from '../../ui/novvin/SocialMoments'
import { runtimeStoragePreviewUrl } from '../../core/runtimeBackend'
import { getPublicWebOrigin } from '../../core/socialLinks'

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
  offset?: number
}

function profilePathFor(profileId: string, currentUserId?: string): string {
  const clean = profileId.trim()
  if (!clean) return '/app/profile'
  if (currentUserId && clean === currentUserId) return '/app/profile'
  return `/app/profile/${clean}`
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
  onOpenComposer?: () => void
  onCloseComposer?: () => void
  promotionsOpen?: boolean
  onClosePromotions?: () => void
}

function FeedAvatar({ url, name, priority = false }: { url: string; name: string; priority?: boolean }) {
  return <SafeAvatar url={url} name={name} priority={priority} />
}

export function FeedPage({ composerOpen = false, onOpenComposer, onCloseComposer, promotionsOpen = false, onClosePromotions }: FeedPageProps) {
  const mediaInput = useRef<HTMLInputElement>(null)
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const [webPreferences] = useWebPreferences()
  const [discoveryTab, setDiscoveryTab] = useState<'discover' | 'following'>('discover')
  const feedCacheKey = `feed:${user?.id ?? 'anon'}:${discoveryTab}`
  const [promotionManager,setPromotionManager]=useState(false)
  const [canManagePromotions,setCanManagePromotions]=useState(false)
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
  const [posting, setPosting] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [realtimeRefreshing, setRealtimeRefreshing] = useState(false)
  const [shuffleSeed, setShuffleSeed] = useState(createShuffleSeed)
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const likeRequestsRef = useRef(new Set<string>())
  const feedRequestRef = useRef(0)
  const loadedCountRef = useRef(60)
  const feedScopeRef = useRef(feedCacheKey)
  feedScopeRef.current = feedCacheKey
  const [hasMore, setHasMore] = useState(false)
  const [nextOffset, setNextOffset] = useState(0)
  const [loadingMore, setLoadingMore] = useState(false)
  const shuffledItems = useMemo(
    () => discoveryTab === 'following' ? items : shuffleFeedItems(items, shuffleSeed),
    [items, shuffleSeed, discoveryTab],
  )

  function reshufflePosts() {
    setShuffleSeed((currentSeed) => {
      const nextSeed = createShuffleSeed()
      return nextSeed === currentSeed ? (currentSeed + 1) >>> 0 : nextSeed
    })
  }

  const loadFeed = useCallback(
    async (options?: LoadFeedOptions) => {
      if (feedScopeRef.current !== feedCacheKey) return
      const requestId = ++feedRequestRef.current
      const offset = options?.offset ?? 0
      const pageSize = offset === 0 && options?.silent ? Math.max(60, loadedCountRef.current) : 60
      const currentRequest = () => feedRequestRef.current === requestId && feedScopeRef.current === feedCacheKey
      const acceptPage = (mapped: FeedItem[], more: boolean, followingOffset: number) => {
        if (!currentRequest()) return
        setError(null)
        setItems((previous) => {
          // A realtime refresh must not undo a reaction while its write is still pending.
          const pending = new Map(previous.filter(item => likeRequestsRef.current.has(item.id)).map(item => [item.id, item]))
          const page = mapped.map(item => {
            const optimistic = pending.get(item.id)
            if (!optimistic || optimistic.likedByMe === item.likedByMe) return item
            return { ...item, likedByMe: optimistic.likedByMe,
              likeCount: Math.max(0, item.likeCount + (optimistic.likedByMe ? 1 : -1)) }
          })
          const next = offset > 0
            ? Array.from(new Map([...previous, ...page].map(item => [item.id, item])).values())
            : page
          loadedCountRef.current = next.length
          writeCachedValue(feedCacheKey, next)
          return next
        })
        setHasMore(more)
        setNextOffset(followingOffset)
      }
      if (offset > 0) setLoadingMore(true)
      if (useUbuntu) {
        if (!options?.silent && offset === 0) {
          setLoading(true)
          setError(null)
        } else {
          setRealtimeRefreshing(true)
        }
        try {
          const pages = await Promise.all(Array.from({ length: Math.ceil(pageSize / 200) }, (_, index) => {
            const limit = Math.min(200, pageSize - index * 200)
            return loadFallbackFeed(limit, { mode: discoveryTab, offset: offset + index * 200 })
          }))
          const mapped = pages.flatMap(page => page.items.map(mapPost))
          const lastPage = pages[pages.length - 1]
          acceptPage(mapped, lastPage.hasMore ?? mapped.length === pageSize,
            lastPage.nextOffset ?? offset + mapped.length)
        } catch (caught) {
          if (currentRequest()) {
            setError(friendlyError(caught, 'No se pudo cargar el feed del servidor local'))
          }
        } finally {
          if (currentRequest()) {
            setLoading(false)
            setRealtimeRefreshing(false)
            setLoadingMore(false)
          }
        }
        return
      }

      if (!supabase) {
        setError('Supabase no está configurado.')
        setLoading(false)
        return
      }
      if (!options?.silent && offset === 0) {
        setLoading(true)
      } else {
        setRealtimeRefreshing(true)
      }
      if (!options?.silent) {
        setError(null)
      }
      try {
        let query = supabase
          .from('posts')
          .select('*')
          .order('created_at', { ascending: false })
          .order('id', { ascending: false })
          .range(offset, offset + pageSize - 1)
        if (discoveryTab === 'following') {
          if (!user) { acceptPage([], false, 0); return }
          const { data: follows, error: followError } = await supabase.from('follows')
            .select('following_id').eq('follower_id', user.id)
          if (followError) throw followError
          const followed = Array.from(new Set([user.id, ...(follows ?? []).map(row => String(row.following_id))]))
          query = query.in('user_id', followed)
        }
        const { data, error: queryError } = await query
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
          acceptPage(nextItems, mapped.length === pageSize, offset + mapped.length)
        } else {
          acceptPage(mapped, mapped.length === pageSize, offset + mapped.length)
        }
      } catch (caught) {
        reportSupabaseServiceRestrictionIfPresent('feed-load', caught)
        if (currentRequest()) {
          setError(friendlyError(caught, 'No se pudo cargar el feed'))
        }
      } finally {
        if (currentRequest()) {
          setLoading(false)
          setRealtimeRefreshing(false)
          setLoadingMore(false)
        }
      }
    },
    [feedCacheKey, useUbuntu, user, discoveryTab],
  )

  useEffect(() => {
    setHasMore(false)
    loadedCountRef.current = 60
    setNextOffset(0)
    setLoadingMore(false)
    const cached = readCachedValue<FeedItem[]>(feedCacheKey, 1000 * 60 * 60 * 8)
    if (cached && cached.length > 0) {
      setItems(cached)
      setLoading(false)
      void loadFeed({ silent: true })
      return
    }
    setItems([])
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
          if (scope === 'inbox' || event.payload?.event === 'message' || event.payload?.event === 'post_reaction') scheduleRefresh()
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
    if (likeRequestsRef.current.has(item.id)) return
    if (!useUbuntu && (!supabase || !user)) return
    likeRequestsRef.current.add(item.id)
    const previousLiked = item.likedByMe
    const nextLiked = !previousLiked
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
    try {
      if (useUbuntu) {
        await setFallbackPostReaction(item.id, nextLiked)
      } else if (previousLiked) {
        const { error: unlikeError } = await supabase!
          .from('post_likes')
          .delete()
          .eq('post_id', item.id)
          .eq('user_id', user!.id)
        if (unlikeError) throw unlikeError
      } else {
        const { error: likeError } = await supabase!.from('post_likes').insert({
          post_id: item.id,
          user_id: user!.id,
        })
        if (likeError) throw likeError
      }
      await loadFeed({ silent: true })
    } catch (caught) {
      setItems((current) =>
        current.map((entry) => entry.id === item.id
          ? {
              ...entry,
              likedByMe: previousLiked,
              likeCount: Math.max(0, entry.likeCount + (nextLiked ? -1 : 1)),
            }
          : entry),
      )
      setError(friendlyError(caught, 'No se pudo actualizar el like'))
      await loadFeed({ silent: true })
    } finally {
      likeRequestsRef.current.delete(item.id)
    }
  }

  if (loading) {
    return <StatusView title="Cargando feed" description="Trayendo publicaciones..." />
  }

  if (error && items.length === 0) {
    return <StatusView title="Feed no disponible" description={error} />
  }

  return (
    <section className="section-stack feed-page">
      {error ? <p role="status">{error} <button className="ghost-btn" type="button"
        onClick={() => void loadFeed({ silent: true })}>Reintentar</button></p> : null}
      {gallery ? (
        <MediaViewer
          items={gallery.items}
          initialIndex={gallery.index}
          onClose={() => setGallery(null)}
        />
      ) : null}
      <span className="sr-only" role="status">
        {realtimeRefreshing ? 'Actualizando novedades' : ''}
      </span>
      {shareStatus ? <p role="status" className="social-share-status">{shareStatus}<button type="button" aria-label="Cerrar aviso" onClick={() => setShareStatus('')}><X size={16} aria-hidden="true" /></button></p> : null}
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
            {canManagePromotions?<button type="button" className="ghost-btn" onClick={event=>{event.currentTarget.closest('details')?.removeAttribute('open');setPromotionManager(true)}}>Publicidad · Crear y gestionar</button>:null}
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
        <button
          className="icon-btn"
          type="button" title="Adjuntar foto o video"
          aria-label="Adjuntar foto o video"
          onClick={() => mediaInput.current?.click()}
        >
          <ImagePlus />
        </button>
        <input ref={mediaInput} type="file" hidden accept="image/*,video/*" onChange={event => {
          const file = event.currentTarget.files?.[0]
          event.currentTarget.value = ''
          if (file) { setAttachedFile(file); onOpenComposer?.() }
        }} />
      </div>
      <SocialMoments />
      <SocialPromotion showAd={false} manageOpen={promotionManager || promotionsOpen} onClose={()=>{setPromotionManager(false);onClosePromotions?.()}} onManageAccess={setCanManagePromotions}/>
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
                <button
                  type="button"
                  className="icon-btn file-pick-btn"
                  title="Adjuntar imagen o video"
                  aria-label="Adjuntar imagen o video"
                  onClick={() => mediaInput.current?.click()}
                >
                  <ImagePlus aria-hidden="true" />
                </button>
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
        <Fragment key={item.id}><article className="surface-card feed-card">
          <div className="row-between feed-head">
            <Link className="identity-link" to={profilePathFor(item.authorId, user?.id)}>
              <FeedAvatar url={item.authorAvatarUrl} name={item.authorName} priority={itemIndex < 2} />
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
            <span aria-label={`${item.likeCount} Me gusta`}>
              <ThumbsUp size={15} aria-hidden="true" /> {item.likeCount}
            </span>
            <Link to={`/app/feed/post/${item.id}#comments`}>
              {item.commentCount === 1 ? '1 comentario' : `${item.commentCount} comentarios`}
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
                const url = `${getPublicWebOrigin()}/post/${encodeURIComponent(item.id)}?preview=1`
                const copy = async () => {
                  try { await navigator.clipboard.writeText(url); setShareStatus('Enlace copiado') }
                  catch { setShareStatus('No se pudo compartir el enlace. Inténtalo de nuevo.') }
                }
                if (navigator.share) {
                  void navigator.share({ title: item.authorName, url }).catch(error => {
                    if (error?.name !== 'AbortError') void copy()
                  })
                } else void copy()
              }}
            >
              <Share2 />
              Compartir
            </button>
          </div>
        </article>{itemIndex===Math.min(3,shuffledItems.length-1)?<SocialPromotion/>:null}</Fragment>
      ))}
      {hasMore ? <button className="secondary-btn" type="button" disabled={loadingMore}
        onClick={() => void loadFeed({ silent: true, offset: nextOffset })}>
        {loadingMore ? 'Cargando publicaciones…' : 'Ver más publicaciones'}
      </button> : null}
    </section>
  )
}
