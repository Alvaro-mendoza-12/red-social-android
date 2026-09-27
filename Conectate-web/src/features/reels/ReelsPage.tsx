import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Heart, MessageCircle, MoreHorizontal, Plus, Radio, Repeat, Search, Send, Share2, SlidersHorizontal, Star, Upload, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { SafeAvatar } from '../../components/SafeAvatar'
import { StatusView } from '../../components/StatusView'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
  resolveSupabasePublicUrl,
} from '../../core/supabase'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import {
  createFallbackVideo,
  loadFallbackFollows,
  loadFallbackLiveStreams,
  loadFallbackVideos,
  setFallbackVideoFavorite,
  setFallbackVideoReaction,
  uploadFallbackFile,
  type FallbackLiveStream,
} from '../../core/fallbackData'
import { isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'
import {
  avatarMediaTypeSource,
  profileIdentityAliases,
  profileVideoCoverSource,
  profileVideoSource,
  reelAuthorAvatarSource,
} from '../../core/profileMediaFields'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import { filterFriendFeedItems } from './reelFeedOrder'
import { LiveFeedCard, LiveStrip } from '../live/LiveStrip'

type ReelItem = {
  id: string
  title: string
  description: string
  videoUrl: string
  coverUrl: string
  authorId: string
  authorName: string
  authorAvatarUrl: string
  authorAvatarMediaType: string
  createdAt: string
  likeCount: number
  commentCount: number
  favoriteCount: number
  likedByMe: boolean
  favoritedByMe: boolean
  sharedByMe: boolean
  viewCount: number
  durationSeconds: number
  trimStartSeconds: number
  trimEndSeconds: number
}

type TrimPreviewFrame = {
  second: number
  src: string
}

type FeedSort = 'recommended' | 'new' | 'likes' | 'views'
type FeedMode = 'friends' | 'recommended'
type CoverInputMode = 'upload' | 'frame'
type WatermarkDetectionResult = {
  detectedBrands: string[]
  checkedAt: string
}

const MAX_REEL_DURATION_SECONDS = 30
const MAX_VIDEO_BYTES = 120 * 1024 * 1024
const MAX_COVER_BYTES = 10 * 1024 * 1024
const MIN_TRIM_SPAN_SECONDS = 1
const knownWatermarkBrands = [
  { brand: 'TikTok', tokens: ['tiktok', 'douyin', 'vm.tiktok.com'] },
  { brand: 'Facebook', tokens: ['facebook', 'fb.watch', 'facebook.com', 'meta'] },
  { brand: 'YouTube', tokens: ['youtube', 'youtu.be', 'youtube.com', 'shorts'] },
  { brand: 'Kwai', tokens: ['kwai', 'kwai-video'] },
  { brand: 'Instagram', tokens: ['instagram', 'insta', 'ig_'] },
]

function mapVideoRow(row: GenericRow): ReelItem {
  const durationSeconds = Number(row.duration_seconds ?? row.duration ?? 0)
  const authorAvatarSource = reelAuthorAvatarSource(row)
  const trimStartSeconds = Number(
    row.trim_start_seconds ?? row.trimStartSeconds ?? row.clip_start_seconds ?? row.clipStartSeconds ?? 0,
  )
  const trimEndRaw = Number(
    row.trim_end_seconds ?? row.trimEndSeconds ?? row.clip_end_seconds ?? row.clipEndSeconds ?? 0,
  )
  const trimEndSeconds = trimEndRaw > 0 ? trimEndRaw : durationSeconds
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    title: textOf(row, ['title', 'caption', 'description', 'content'], 'Video'),
    description: textOf(row, ['description'], ''),
    videoUrl: resolveSupabasePublicUrl(
      'videos',
      profileVideoSource(row),
    ),
    coverUrl: resolveSupabasePublicUrl(
      'thumbnails',
      profileVideoCoverSource(row),
    ),
    authorId: textOf(row, ['user_id', 'userId', 'owner_id', 'ownerId', 'author_id', 'authorId'], 'usuario'),
    authorName: textOf(row, ['user_name', 'userName', 'author_name'], ''),
    authorAvatarUrl: resolveSupabasePublicUrl('avatars', authorAvatarSource),
    authorAvatarMediaType: avatarMediaTypeSource(row, authorAvatarSource),
    createdAt: textOf(row, ['created_at', 'updated_at'], ''),
    likeCount: Number(row.like_count ?? row.likeCount ?? 0),
    commentCount: Number(row.comment_count ?? row.commentCount ?? 0),
    favoriteCount: Number(row.favorite_count ?? row.favoriteCount ?? 0),
    likedByMe: Boolean(row.liked_by_me ?? row.likedByMe ?? false),
    favoritedByMe: Boolean(row.favorited_by_me ?? row.favoritedByMe ?? row.is_favorite ?? false),
    sharedByMe: Boolean(row.shared_by_me ?? row.sharedByMe ?? false),
    viewCount: Number(row.views ?? row.view_count ?? row.viewCount ?? 0),
    durationSeconds,
    trimStartSeconds,
    trimEndSeconds,
  }
}

function ReelAvatar({ url, name, mediaType }: { url: string; name: string; mediaType: string }) {
  return (
    <SafeAvatar
      url={url}
      mediaType={mediaType}
      name={name}
      size="sm"
      className="reel-overlay-avatar"
    />
  )
}

function ReelFeedCard({
  item,
  currentUserId,
  onLike,
  onFavorite,
  onShare,
  onToggleProfileShare,
}: {
  item: ReelItem
  currentUserId?: string
  onLike: (item: ReelItem) => void
  onFavorite: (item: ReelItem) => void
  onShare: (item: ReelItem) => void
  onToggleProfileShare: (item: ReelItem) => void
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const [shareMenuOpen, setShareMenuOpen] = useState(false)

  useEffect(() => {
    const video = videoRef.current
    if (!video || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting && entry.intersectionRatio >= 0.72) {
          void video.play().catch(() => undefined)
        } else {
          video.pause()
        }
      },
      { threshold: [0.25, 0.72, 0.95] },
    )
    observer.observe(video)
    return () => observer.disconnect()
  }, [])

  return (
    <article className="reel-feed-item">
      <video
        ref={videoRef}
        loop
        muted
        playsInline
        controls
        preload="metadata"
        src={item.videoUrl}
        poster={item.coverUrl || undefined}
      />
      <div className="reel-feed-shade" />
      <div className="reel-feed-author">
        <Link to={profilePathFor(item.authorId, currentUserId)}>
          <ReelAvatar
            url={item.authorAvatarUrl}
            mediaType={item.authorAvatarMediaType}
            name={item.authorName || item.authorId}
          />
          <strong>@{item.authorName || item.authorId}</strong>
        </Link>
        <h2>{item.title}</h2>
        {item.description ? <p>{item.description}</p> : null}
      </div>
      <div className="reel-feed-actions">
        <button className={item.likedByMe ? 'active' : ''} type="button" onClick={() => onLike(item)} aria-label="Me gusta" title="Me gusta">
          <Heart aria-hidden="true" fill={item.likedByMe ? 'currentColor' : 'none'} />
          <span>{item.likeCount}</span>
        </button>
        <Link to={`/app/reels/${item.id}#comments`} aria-label="Comentarios" title="Comentarios">
          <MessageCircle aria-hidden="true" />
          <span>{item.commentCount}</span>
        </Link>
        <button
          className={item.favoritedByMe ? 'active' : ''}
          type="button"
          onClick={() => onFavorite(item)}
          aria-label="Favorito"
          title="Favorito"
        >
          <Star
            aria-hidden="true"
            fill={item.favoritedByMe ? 'currentColor' : 'none'}
            color={item.favoritedByMe ? '#FFC107' : 'currentColor'}
          />
          <span>{item.favoriteCount}</span>
        </button>
        <div className="reel-share-wrapper">
          <button
            className="reel-share-trigger"
            type="button"
            onClick={() => setShareMenuOpen((current) => !current)}
            aria-label="Compartir"
            aria-haspopup="menu"
            aria-expanded={shareMenuOpen}
            title="Compartir"
          >
            <Share2 aria-hidden="true" />
          </button>
          {shareMenuOpen ? (
            <div className="reel-share-menu" role="menu">
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setShareMenuOpen(false)
                  onShare(item)
                }}
              >
                <Send aria-hidden="true" />
                <span>Enviar a amigo</span>
              </button>
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setShareMenuOpen(false)
                  if (typeof window !== 'undefined') {
                    const shareUrl = `${window.location.origin}/app/reels/${item.id}`
                    const sendIntent = {
                      title: item.title || 'Video de Novvin',
                      text: `Mira este video en Novvin: ${shareUrl}`,
                      url: shareUrl,
                    }
                    if (navigator.share) {
                      void navigator.share(sendIntent).catch(() => undefined)
                    } else {
                      void navigator.clipboard.writeText(shareUrl).catch(() => undefined)
                    }
                  }
                }}
              >
                <Share2 aria-hidden="true" />
                <span>Compartir enlace</span>
              </button>
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setShareMenuOpen(false)
                  onToggleProfileShare(item)
                }}
              >
                <Repeat aria-hidden="true" />
                <span>{item.sharedByMe ? 'Quitar de mi perfil' : 'Añadir a mi perfil'}</span>
              </button>
            </div>
          ) : null}
        </div>
        <Link to={`/app/reels/${item.id}`} aria-label="Ver detalles del video" title="Ver detalles del video">
          <MoreHorizontal aria-hidden="true" />
        </Link>
      </div>
    </article>
  )
}

function profilePathFor(profileId: string, currentUserId?: string): string {
  const clean = profileId.trim()
  if (!clean) return '/app/profile'
  if (currentUserId && clean === currentUserId) return '/app/profile'
  return `/app/profile/${clean}`
}

function isMissingSchemaError(error: unknown): boolean {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return message.includes('column') || message.includes('42703')
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

function safeName(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9._-]/g, '_')
}

function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 MB'
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function detectWatermarkBrands(file: File, title: string, description: string): WatermarkDetectionResult {
  const haystack = [
    file.name,
    file.type,
    title,
    description,
  ].join(' ').toLowerCase()
  const detectedBrands = knownWatermarkBrands
    .filter((entry) => entry.tokens.some((token) => haystack.includes(token)))
    .map((entry) => entry.brand)

  return {
    detectedBrands: Array.from(new Set(detectedBrands)),
    checkedAt: new Date().toISOString(),
  }
}

function toRoundedDuration(value: number): number {
  if (!Number.isFinite(value) || value <= 0) return 0
  return Math.max(1, Math.round(value))
}

function fileExtension(file: File, fallback: string): string {
  const name = file.name || ''
  const index = name.lastIndexOf('.')
  if (index <= 0 || index >= name.length - 1) return fallback
  return name.slice(index).toLowerCase()
}

function readVideoDurationSeconds(file: File): Promise<number> {
  if (typeof document === 'undefined') return Promise.resolve(0)
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const media = document.createElement('video')
    let settled = false

    const finish = (value: number) => {
      if (settled) return
      settled = true
      URL.revokeObjectURL(url)
      resolve(toRoundedDuration(value))
    }

    media.preload = 'metadata'
    media.onloadedmetadata = () => finish(media.duration)
    media.onerror = () => finish(0)
    media.src = url
  })
}

function formatSecondsClock(totalSeconds: number): string {
  if (!Number.isFinite(totalSeconds) || totalSeconds <= 0) return '00:00'
  const seconds = Math.max(0, Math.floor(totalSeconds))
  const minutes = Math.floor(seconds / 60)
  const remaining = seconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(remaining).padStart(2, '0')}`
}

function baseNameFromFile(file: File): string {
  const name = file.name || 'cover'
  const index = name.lastIndexOf('.')
  return index > 0 ? name.slice(0, index) : name
}

function captureVideoFrameAsFile(file: File, second: number): Promise<File> {
  if (typeof document === 'undefined') {
    return Promise.reject(new Error('No se puede generar portada en este entorno.'))
  }
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file)
    const video = document.createElement('video')
    let cleaned = false

    const cleanup = () => {
      if (cleaned) return
      cleaned = true
      URL.revokeObjectURL(objectUrl)
    }

    video.preload = 'auto'
    video.muted = true
    video.playsInline = true

    video.onerror = () => {
      cleanup()
      reject(new Error('No se pudo leer el video para generar portada.'))
    }

    video.onloadedmetadata = () => {
      const duration = Number.isFinite(video.duration) ? Math.max(0, video.duration) : 0
      const safeSecond = Math.max(0, Math.min(second, duration > 0 ? Math.max(0, duration - 0.05) : second))
      video.currentTime = safeSecond
    }

    video.onseeked = () => {
      const width = video.videoWidth || 720
      const height = video.videoHeight || 1280
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        cleanup()
        reject(new Error('No se pudo preparar el render de portada.'))
        return
      }
      ctx.drawImage(video, 0, 0, width, height)
      canvas.toBlob(
        (blob) => {
          cleanup()
          if (!blob) {
            reject(new Error('No se pudo generar portada desde el video.'))
            return
          }
          const safeBase = safeName(baseNameFromFile(file))
          resolve(new File([blob], `${safeBase || 'cover'}_frame.jpg`, { type: 'image/jpeg' }))
        },
        'image/jpeg',
        0.92,
      )
    }

    video.src = objectUrl
    video.load()
  })
}

function captureVideoFrameDataUrl(
  file: File,
  second: number,
  targetWidth = 220,
  quality = 0.72,
): Promise<string> {
  if (typeof document === 'undefined') {
    return Promise.reject(new Error('No se puede generar miniatura en este entorno.'))
  }
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file)
    const video = document.createElement('video')
    let cleaned = false

    const cleanup = () => {
      if (cleaned) return
      cleaned = true
      URL.revokeObjectURL(objectUrl)
    }

    video.preload = 'auto'
    video.muted = true
    video.playsInline = true

    video.onerror = () => {
      cleanup()
      reject(new Error('No se pudo generar miniatura del video.'))
    }

    video.onloadedmetadata = () => {
      const duration = Number.isFinite(video.duration) ? Math.max(0, video.duration) : 0
      const safeSecond = Math.max(0, Math.min(second, duration > 0 ? Math.max(0, duration - 0.05) : second))
      video.currentTime = safeSecond
    }

    video.onseeked = () => {
      const sourceWidth = Math.max(1, video.videoWidth || targetWidth)
      const sourceHeight = Math.max(1, video.videoHeight || Math.round(targetWidth * 1.6))
      const width = Math.max(80, targetWidth)
      const height = Math.max(80, Math.round((sourceHeight / sourceWidth) * width))
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        cleanup()
        reject(new Error('No se pudo renderizar miniatura.'))
        return
      }
      ctx.drawImage(video, 0, 0, width, height)
      const dataUrl = canvas.toDataURL('image/jpeg', quality)
      cleanup()
      resolve(dataUrl)
    }

    video.src = objectUrl
    video.load()
  })
}

async function insertVideoWithFallback(payload: {
  userId: string
  userName: string
  avatarUrl: string
  title: string
  description: string
  videoUrl: string
  coverUrl: string | null
  durationSeconds: number
  trimStartSeconds: number
  trimEndSeconds: number
}) {
  if (isFallbackAuthConfigured) {
    await createFallbackVideo({
      title: payload.title,
      description: payload.description,
      videoUrl: payload.videoUrl,
      coverUrl: payload.coverUrl,
      durationSeconds: payload.durationSeconds,
      trimStartSeconds: payload.trimStartSeconds,
      trimEndSeconds: payload.trimEndSeconds,
      isPublic: true,
    })
    return
  }
  if (!supabase) return

  const snakeBase: GenericRow = {
    user_id: payload.userId,
    user_name: payload.userName,
    user_profile_image: payload.avatarUrl,
    title: payload.title,
    description: payload.description,
    video_url: payload.videoUrl,
    is_public: true,
    views: 0,
    like_count: 0,
    comment_count: 0,
  }
  const camelBase: GenericRow = {
    userId: payload.userId,
    userName: payload.userName,
    userProfileImage: payload.avatarUrl,
    title: payload.title,
    description: payload.description,
    videoUrl: payload.videoUrl,
    isPublic: true,
    views: 0,
    likeCount: 0,
    commentCount: 0,
  }

  const variants: GenericRow[] = [
    {
      ...snakeBase,
      cover_url: payload.coverUrl,
      duration: payload.durationSeconds,
      duration_seconds: payload.durationSeconds,
      trim_start_seconds: payload.trimStartSeconds,
      trim_end_seconds: payload.trimEndSeconds,
    },
    {
      ...snakeBase,
      cover_url: payload.coverUrl,
      duration: payload.durationSeconds,
      trim_start_seconds: payload.trimStartSeconds,
      trim_end_seconds: payload.trimEndSeconds,
    },
    {
      ...snakeBase,
      duration: payload.durationSeconds,
      trim_start_seconds: payload.trimStartSeconds,
      trim_end_seconds: payload.trimEndSeconds,
    },
    snakeBase,
    {
      ...camelBase,
      coverUrl: payload.coverUrl,
      duration: payload.durationSeconds,
      durationSeconds: payload.durationSeconds,
      trimStartSeconds: payload.trimStartSeconds,
      trimEndSeconds: payload.trimEndSeconds,
    },
    {
      ...camelBase,
      coverUrl: payload.coverUrl,
      duration: payload.durationSeconds,
      trimStartSeconds: payload.trimStartSeconds,
      trimEndSeconds: payload.trimEndSeconds,
    },
    {
      ...camelBase,
      duration: payload.durationSeconds,
      trimStartSeconds: payload.trimStartSeconds,
      trimEndSeconds: payload.trimEndSeconds,
    },
    camelBase,
  ]

  let lastError: unknown = null
  for (const variant of variants) {
    const { error } = await supabase.from('videos').insert(variant)
    if (!error) return
    if (!isMissingSchemaError(error)) throw error
    lastError = error
  }
  if (lastError) throw lastError
}

export function ReelsPage() {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const reelsCacheKey = `reels:${user?.id ?? 'anon'}`
  const [items, setItems] = useState<ReelItem[]>([])
  const [liveStreams, setLiveStreams] = useState<FallbackLiveStream[]>([])
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [titleDraft, setTitleDraft] = useState('')
  const [descriptionDraft, setDescriptionDraft] = useState('')
  const [videoFile, setVideoFile] = useState<File | null>(null)
  const [coverFile, setCoverFile] = useState<File | null>(null)
  const [coverMode, setCoverMode] = useState<CoverInputMode>('upload')
  const [coverFrameSecond, setCoverFrameSecond] = useState(0)
  const [generatingFrameCover, setGeneratingFrameCover] = useState(false)
  const [posting, setPosting] = useState(false)
  const [showUploadForm, setShowUploadForm] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [onlyMine, setOnlyMine] = useState(false)
  const [feedMode, setFeedMode] = useState<FeedMode>('recommended')
  const [followingIds, setFollowingIds] = useState<string[]>([])
  const [sortBy, setSortBy] = useState<FeedSort>('recommended')
  const [videoDurationSeconds, setVideoDurationSeconds] = useState(0)
  const [trimStartSeconds, setTrimStartSeconds] = useState(0)
  const [trimEndSeconds, setTrimEndSeconds] = useState(0)
  const [watermarkDetection, setWatermarkDetection] = useState<WatermarkDetectionResult | null>(null)
  const [readingDuration, setReadingDuration] = useState(false)
  const [videoPreviewUrl, setVideoPreviewUrl] = useState('')
  const [coverPreviewUrl, setCoverPreviewUrl] = useState('')
  const [previewTrimLoop, setPreviewTrimLoop] = useState(false)
  const [trimPreviewFrames, setTrimPreviewFrames] = useState<TrimPreviewFrame[]>([])
  const [loadingTrimPreviewFrames, setLoadingTrimPreviewFrames] = useState(false)
  const [selectedFramePreviewUrl, setSelectedFramePreviewUrl] = useState('')
  const [loadingSelectedFramePreview, setLoadingSelectedFramePreview] = useState(false)
  const previewVideoRef = useRef<HTMLVideoElement | null>(null)

  const canPublish = useMemo(
    () =>
      Boolean(user && videoFile && !posting) &&
      (videoDurationSeconds <= 0 || videoDurationSeconds <= MAX_REEL_DURATION_SECONDS) &&
      (watermarkDetection?.detectedBrands.length ?? 0) === 0,
    [posting, user, videoDurationSeconds, videoFile, watermarkDetection?.detectedBrands.length],
  )
  const effectiveTrimEndSeconds = useMemo(() => {
    if (videoDurationSeconds <= 0) return 0
    if (trimEndSeconds <= 0) return videoDurationSeconds
    return Math.min(videoDurationSeconds, Math.max(MIN_TRIM_SPAN_SECONDS, trimEndSeconds))
  }, [trimEndSeconds, videoDurationSeconds])
  const trimStartPercent = useMemo(() => {
    if (videoDurationSeconds <= 0) return 0
    return Math.max(0, Math.min(100, (trimStartSeconds / videoDurationSeconds) * 100))
  }, [trimStartSeconds, videoDurationSeconds])
  const trimEndPercent = useMemo(() => {
    if (videoDurationSeconds <= 0) return 100
    return Math.max(0, Math.min(100, (effectiveTrimEndSeconds / videoDurationSeconds) * 100))
  }, [effectiveTrimEndSeconds, videoDurationSeconds])

  const visibleItems = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    let next = items

    if (feedMode === 'friends') {
      next = filterFriendFeedItems(next, followingIds, user?.id)
    }
    if (onlyMine && user) {
      next = next.filter((item) => item.authorId === user.id)
    }
    if (query) {
      next = next.filter((item) =>
        [item.title, item.description, item.authorName, item.authorId].join(' ').toLowerCase().includes(query),
      )
    }

    if (sortBy === 'recommended') return next

    return [...next].sort((a, b) => {
      if (sortBy === 'likes') return b.likeCount - a.likeCount || Date.parse(b.createdAt) - Date.parse(a.createdAt)
      if (sortBy === 'views') return b.viewCount - a.viewCount || Date.parse(b.createdAt) - Date.parse(a.createdAt)
      return Date.parse(b.createdAt) - Date.parse(a.createdAt)
    })
  }, [feedMode, followingIds, items, onlyMine, searchQuery, sortBy, user])

  const loadVideos = useCallback(async (options?: { silent?: boolean }) => {
    if (useUbuntu) {
      if (options?.silent) {
        setSyncing(true)
      } else {
        setLoading(true)
        setError(null)
      }
      try {
        const feed = await loadFallbackVideos(80, 0)
        const mapped = feed.items.map(mapVideoRow).filter((item) => item.videoUrl.length > 0)
        setItems(mapped)
        writeCachedValue(reelsCacheKey, mapped)
      } catch (caught) {
        if (!options?.silent) {
          setError(friendlyError(caught, 'No se pudo cargar reels desde Ubuntu'))
        }
      } finally {
        setLoading(false)
        setSyncing(false)
      }
      return
    }

    if (!supabase) {
      setLoading(false)
      setError('Configura VITE_FALLBACK_API_URL para usar reels en Ubuntu.')
      return
    }
    if (options?.silent) {
      setSyncing(true)
    } else {
      setLoading(true)
      setError(null)
    }

    try {
      const attempts = ['videos', 'posts']
      for (const table of attempts) {
        const { data, error: queryError } = await supabase
          .from(table)
          .select('*')
          .order('created_at', { ascending: false })
          .limit(80)

        if (queryError) continue
        const rows = (data ?? []) as GenericRow[]
        let mapped = rows.map(mapVideoRow).filter((item) => item.videoUrl.length > 0)
        const authorIds = Array.from(new Set(mapped.map((item) => item.authorId).filter(Boolean)))
        if (authorIds.length > 0) {
          const { data: profileRows } = await supabase
            .from('profiles')
            .select('*')
            .in('id', authorIds)
          const profileById = new Map<string, { name: string; avatarUrl: string; avatarMediaType: string }>()
          ;((profileRows ?? []) as GenericRow[]).forEach((profile) => {
            const id = textOf(profile, ['id'], '')
            if (!id) return
            const avatarSource = reelAuthorAvatarSource(profile)
            profileById.set(id, {
              name: textOf(profile, ['display_name', 'username'], '') || id,
              avatarUrl: resolveSupabasePublicUrl('avatars', avatarSource),
              avatarMediaType: avatarMediaTypeSource(profile, avatarSource),
            })
          })
          mapped = mapped.map((item) => {
            const profile = profileById.get(item.authorId)
            if (!profile) return item
            return {
              ...item,
              authorName: item.authorName.trim() || profile.name,
              authorAvatarUrl: item.authorAvatarUrl.trim() || profile.avatarUrl,
              authorAvatarMediaType: item.authorAvatarMediaType.trim() || profile.avatarMediaType,
            }
          })
        }
        if (mapped.length > 0) {
          setItems(mapped)
          writeCachedValue(reelsCacheKey, mapped)
          setLoading(false)
          setSyncing(false)
          return
        }
      }
      setItems([])
      writeCachedValue(reelsCacheKey, [])
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('reels-load', caught)
      if (!options?.silent) {
        setError(friendlyError(caught, 'No se pudo cargar reels/videos'))
      }
    } finally {
      setLoading(false)
      setSyncing(false)
    }
  }, [reelsCacheKey, useUbuntu])

  useEffect(() => {
    if (!user?.id) return
    const userId = user.id
    let cancelled = false

    async function loadFollowingIds() {
      try {
        if (useUbuntu) {
          const response = await loadFallbackFollows('following')
          const ids = response.items.flatMap((profile) => profileIdentityAliases(profile))
          if (!cancelled) setFollowingIds(Array.from(new Set(ids)))
          return
        }

        if (!supabase) return
        const { data, error: followsError } = await supabase
          .from('follows')
          .select('following_id')
          .eq('follower_id', userId)
        if (followsError) {
          if (!cancelled) setFollowingIds([])
          return
        }
        const ids = ((data ?? []) as GenericRow[])
          .map((row) => textOf(row, ['following_id', 'followingId'], ''))
          .filter(Boolean)
        if (!cancelled) setFollowingIds(Array.from(new Set(ids)))
      } catch {
        if (!cancelled) setFollowingIds([])
      }
    }

    void loadFollowingIds()
    return () => {
      cancelled = true
    }
  }, [useUbuntu, user?.id])

  useEffect(() => {
    const cached = readCachedValue<ReelItem[]>(reelsCacheKey, 1000 * 60 * 60 * 8)
    if (cached && cached.length > 0) {
      setItems(cached)
      setLoading(false)
      void loadVideos({ silent: true })
      return
    }
    void loadVideos({ silent: false })
  }, [loadVideos, reelsCacheKey])

  useEffect(() => {
    if (!useUbuntu) {
      setLiveStreams([])
      return
    }
    let cancelled = false
    const loadLives = async () => {
      try {
        const response = await loadFallbackLiveStreams(12)
        if (!cancelled) setLiveStreams(response.items.filter((stream) => Boolean(stream.is_active)))
      } catch {
        if (!cancelled) setLiveStreams([])
      }
    }
    void loadLives()
    const interval = window.setInterval(() => void loadLives(), 12_000)
    return () => {
      cancelled = true
      window.clearInterval(interval)
    }
  }, [useUbuntu])

  useEffect(() => {
    if (useUbuntu) return
    const client = supabase
    if (!client) return
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRefresh = () => {
      if (refreshTimer) {
        clearTimeout(refreshTimer)
      }
      refreshTimer = setTimeout(() => {
        void loadVideos({ silent: true })
      }, 280)
    }

    const channel = client
      .channel('conectate-web-reels-live')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'videos' }, scheduleRefresh)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'video_likes' }, scheduleRefresh)
    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: 'reels-live' })

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [loadVideos, useUbuntu])

  useEffect(() => {
    if (!videoFile) {
      setVideoPreviewUrl('')
      setPreviewTrimLoop(false)
      setTrimPreviewFrames([])
      setLoadingTrimPreviewFrames(false)
      return
    }
    const url = URL.createObjectURL(videoFile)
    setVideoPreviewUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [videoFile])

  useEffect(() => {
    if (!coverFile) {
      setCoverPreviewUrl('')
      return
    }
    const url = URL.createObjectURL(coverFile)
    setCoverPreviewUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [coverFile])

  useEffect(() => {
    let cancelled = false
    if (!videoFile) {
      setVideoDurationSeconds(0)
      setReadingDuration(false)
      setWatermarkDetection(null)
      return
    }
    setWatermarkDetection(detectWatermarkBrands(videoFile, titleDraft, descriptionDraft))
    setReadingDuration(true)
    void (async () => {
      const duration = await readVideoDurationSeconds(videoFile)
      if (cancelled) return
      setVideoDurationSeconds(duration)
      setReadingDuration(false)
    })()
    return () => {
      cancelled = true
    }
  }, [descriptionDraft, titleDraft, videoFile])

  useEffect(() => {
    if (videoDurationSeconds <= 0) {
      setCoverFrameSecond(0)
      setTrimStartSeconds(0)
      setTrimEndSeconds(0)
      return
    }
    setCoverFrameSecond((current) => {
      if (current <= 0) return Math.min(videoDurationSeconds, 1)
      if (current > videoDurationSeconds) return videoDurationSeconds
      return current
    })
    setTrimStartSeconds((current) =>
      Math.max(0, Math.min(current, Math.max(0, videoDurationSeconds - MIN_TRIM_SPAN_SECONDS))),
    )
    setTrimEndSeconds((current) => {
      const maxClipEnd = Math.min(videoDurationSeconds, MAX_REEL_DURATION_SECONDS)
      if (current <= 0) return maxClipEnd
      return Math.min(maxClipEnd, Math.max(MIN_TRIM_SPAN_SECONDS, current))
    })
  }, [videoDurationSeconds])

  async function generateCoverFromFrame() {
    if (!videoFile || generatingFrameCover) return
    setGeneratingFrameCover(true)
    setError(null)
    try {
      const nextCoverFile = await captureVideoFrameAsFile(videoFile, coverFrameSecond)
      if (nextCoverFile.size <= 0 || nextCoverFile.size > MAX_COVER_BYTES) {
        throw new Error(`La portada generada supera el límite (${formatSize(MAX_COVER_BYTES)}).`)
      }
      setCoverFile(nextCoverFile)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo generar portada desde fotograma'))
    } finally {
      setGeneratingFrameCover(false)
    }
  }

  function applyTrimStart(nextValue: number) {
    if (videoDurationSeconds <= 0) {
      setTrimStartSeconds(0)
      return
    }
    const bounded = Math.max(0, Math.min(nextValue, Math.max(0, videoDurationSeconds - MIN_TRIM_SPAN_SECONDS)))
    setTrimStartSeconds(bounded)
    setTrimEndSeconds((current) => {
      const safeCurrent = current <= 0 ? videoDurationSeconds : current
      if (safeCurrent - bounded < MIN_TRIM_SPAN_SECONDS) {
        return Math.min(videoDurationSeconds, bounded + MIN_TRIM_SPAN_SECONDS)
      }
      return safeCurrent
    })
  }

  function applyTrimEnd(nextValue: number) {
    if (videoDurationSeconds <= 0) {
      setTrimEndSeconds(0)
      return
    }
    const bounded = Math.max(
      MIN_TRIM_SPAN_SECONDS,
      Math.min(nextValue, Math.min(videoDurationSeconds, MAX_REEL_DURATION_SECONDS)),
    )
    setTrimEndSeconds(bounded)
    setTrimStartSeconds((current) => {
      if (bounded - current < MIN_TRIM_SPAN_SECONDS) {
        return Math.max(0, bounded - MIN_TRIM_SPAN_SECONDS)
      }
      return current
    })
  }

  function jumpToTrimStart() {
    const video = previewVideoRef.current
    if (!video) return
    video.currentTime = Math.max(0, trimStartSeconds)
  }

  function jumpToTrimEnd() {
    const video = previewVideoRef.current
    if (!video) return
    const end = effectiveTrimEndSeconds > 0 ? effectiveTrimEndSeconds : videoDurationSeconds
    video.currentTime = Math.max(0, end)
  }

  function handlePreviewTimeUpdate() {
    if (!previewTrimLoop) return
    const video = previewVideoRef.current
    if (!video || videoDurationSeconds <= 0) return
    const end = effectiveTrimEndSeconds > 0 ? effectiveTrimEndSeconds : videoDurationSeconds
    if (end <= trimStartSeconds) return
    if (video.currentTime >= end) {
      video.currentTime = trimStartSeconds
      void video.play().catch(() => {})
    }
  }

  useEffect(() => {
    const video = previewVideoRef.current
    if (!video) return
    if (!previewTrimLoop) return
    if (videoDurationSeconds <= 0) return
    video.currentTime = Math.max(0, trimStartSeconds)
    void video.play().catch(() => {})
  }, [previewTrimLoop, trimStartSeconds, videoDurationSeconds])

  useEffect(() => {
    let cancelled = false
    if (!videoFile || videoDurationSeconds <= 0) {
      setTrimPreviewFrames([])
      setLoadingTrimPreviewFrames(false)
      return
    }

    const frameCount = Math.min(8, Math.max(4, Math.floor(videoDurationSeconds / 4) + 1))
    const points = Array.from({ length: frameCount }, (_, index) => {
      if (frameCount <= 1) return 0
      return (videoDurationSeconds * index) / (frameCount - 1)
    })

    setLoadingTrimPreviewFrames(true)
    void (async () => {
      const nextFrames: TrimPreviewFrame[] = []
      for (const second of points) {
        try {
          const src = await captureVideoFrameDataUrl(videoFile, second, 180)
          if (cancelled) return
          nextFrames.push({
            second: toRoundedDuration(second),
            src,
          })
        } catch {
          // Skip frame capture errors and keep remaining frames.
        }
      }
      if (cancelled) return
      setTrimPreviewFrames(nextFrames)
      setLoadingTrimPreviewFrames(false)
    })()

    return () => {
      cancelled = true
    }
  }, [videoDurationSeconds, videoFile])

  useEffect(() => {
    let cancelled = false
    if (!videoFile || coverMode !== 'frame' || videoDurationSeconds <= 0) {
      setSelectedFramePreviewUrl('')
      setLoadingSelectedFramePreview(false)
      return
    }
    setLoadingSelectedFramePreview(true)
    const timeoutId = window.setTimeout(() => {
      void (async () => {
        try {
          const src = await captureVideoFrameDataUrl(videoFile, coverFrameSecond, 260, 0.78)
          if (cancelled) return
          setSelectedFramePreviewUrl(src)
        } catch {
          if (cancelled) return
          setSelectedFramePreviewUrl('')
        } finally {
          if (!cancelled) setLoadingSelectedFramePreview(false)
        }
      })()
    }, 110)
    return () => {
      cancelled = true
      window.clearTimeout(timeoutId)
    }
  }, [coverFrameSecond, coverMode, videoDurationSeconds, videoFile])

  function jumpToPreviewFrame(second: number) {
    const video = previewVideoRef.current
    if (video) {
      video.currentTime = Math.max(0, second)
    }
    if (coverMode === 'frame') {
      setCoverFrameSecond(Math.max(0, Math.min(second, videoDurationSeconds)))
    }
  }

  async function createReel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!user || !videoFile || posting) return
    if (!useUbuntu && !supabase) return
    setPosting(true)
    setError(null)

    try {
      if (!videoFile.type.startsWith('video/')) {
        throw new Error('Debes seleccionar un archivo de video válido.')
      }
      if (videoFile.size <= 0 || videoFile.size > MAX_VIDEO_BYTES) {
        throw new Error(`El video supera el límite permitido (${formatSize(MAX_VIDEO_BYTES)}).`)
      }
      const measuredDurationSeconds =
        videoDurationSeconds > 0 ? videoDurationSeconds : await readVideoDurationSeconds(videoFile)
      if (measuredDurationSeconds > MAX_REEL_DURATION_SECONDS) {
        throw new Error(`El video debe durar máximo ${MAX_REEL_DURATION_SECONDS} segundos antes de subirlo.`)
      }
      const detection = detectWatermarkBrands(videoFile, titleDraft, descriptionDraft)
      setWatermarkDetection(detection)
      if (detection.detectedBrands.length > 0) {
        throw new Error(
          `Detecté posible marca de agua de ${detection.detectedBrands.join(', ')}. Sube un archivo limpio para publicar.`,
        )
      }

      let selectedCoverFile = coverFile
      if (coverMode === 'frame' && !selectedCoverFile) {
        selectedCoverFile = await captureVideoFrameAsFile(videoFile, coverFrameSecond)
        setCoverFile(selectedCoverFile)
      }

      if (selectedCoverFile) {
        if (!selectedCoverFile.type.startsWith('image/')) {
          throw new Error('La portada debe ser una imagen.')
        }
        if (selectedCoverFile.size <= 0 || selectedCoverFile.size > MAX_COVER_BYTES) {
          throw new Error(`La portada supera el límite permitido (${formatSize(MAX_COVER_BYTES)}).`)
        }
      }

      const extension = fileExtension(videoFile, '.mp4')
      const baseName = safeName(videoFile.name.replace(extension, ''))
      const videoPath = `${user.id}/videos/${Date.now()}_${baseName}${extension}`

      let videoUrl = ''
      let coverUrl: string | null = null

      if (useUbuntu) {
        const uploadedVideo = await uploadFallbackFile('videos', videoFile, videoPath)
        videoUrl = uploadedVideo.publicUrl || uploadedVideo.url
        if (!videoUrl) throw new Error('No se pudo obtener URL pública del video.')
        if (selectedCoverFile) {
          const coverExtension = fileExtension(selectedCoverFile, '.jpg')
          const coverBaseName = safeName(selectedCoverFile.name.replace(coverExtension, ''))
          const coverPath = `${user.id}/covers/${Date.now()}_${coverBaseName}${coverExtension}`
          const uploadedCover = await uploadFallbackFile('videos', selectedCoverFile, coverPath)
          coverUrl = uploadedCover.publicUrl || uploadedCover.url || null
        }
      } else if (supabase) {
        const registerVideo = await supabase.rpc('register_media_object', {
          p_bucket_name: 'videos',
          p_object_path: videoPath,
          p_content_type: videoFile.type || 'video/mp4',
          p_size_bytes: videoFile.size,
          p_sha256: null,
        })
        if (registerVideo.error && !isMissingFunctionError(registerVideo.error)) {
          throw registerVideo.error
        }

        const { error: uploadVideoError } = await supabase.storage
          .from('videos')
          .upload(videoPath, videoFile, { contentType: videoFile.type || undefined })
        if (uploadVideoError) throw uploadVideoError

        const { data: videoPublicData } = supabase.storage.from('videos').getPublicUrl(videoPath)
        videoUrl = videoPublicData.publicUrl
        if (!videoUrl) throw new Error('No se pudo obtener URL pública del video.')

        if (selectedCoverFile) {
          const coverExtension = fileExtension(selectedCoverFile, '.jpg')
          const coverBaseName = safeName(selectedCoverFile.name.replace(coverExtension, ''))
          const coverPath = `${user.id}/covers/${Date.now()}_${coverBaseName}${coverExtension}`

          const registerCover = await supabase.rpc('register_media_object', {
            p_bucket_name: 'videos',
            p_object_path: coverPath,
            p_content_type: selectedCoverFile.type || 'image/jpeg',
            p_size_bytes: selectedCoverFile.size,
            p_sha256: null,
          })
          if (registerCover.error && !isMissingFunctionError(registerCover.error)) {
            throw registerCover.error
          }

          const { error: uploadCoverError } = await supabase.storage
            .from('videos')
            .upload(coverPath, selectedCoverFile, { contentType: selectedCoverFile.type || undefined })
          if (uploadCoverError) throw uploadCoverError

          const { data: coverPublicData } = supabase.storage.from('videos').getPublicUrl(coverPath)
          coverUrl = coverPublicData.publicUrl || null
        }
      }

      let displayName =
        String(user.user_metadata?.display_name ?? user.user_metadata?.full_name ?? '').trim() ||
        user.email?.split('@')[0] ||
        'Usuario'
      let avatarUrl = String(user.user_metadata?.avatar_url ?? user.user_metadata?.picture ?? '').trim()

      if (!useUbuntu && supabase) {
        const { data: profileRows } = await supabase
          .from('profiles')
          .select('display_name,username,avatar_url,photo_url,profile_image,email')
          .eq('id', user.id)
          .limit(1)
        const profile = ((profileRows ?? []) as GenericRow[])[0] ?? {}
        displayName =
          textOf(profile, ['display_name', 'username'], '') ||
          textOf(profile, ['email'], '').split('@')[0] ||
          displayName
        avatarUrl = textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], avatarUrl)
      }

      const safeDurationSeconds = Math.max(0, measuredDurationSeconds)
      const safeTrimStart = Math.max(
        0,
        Math.min(trimStartSeconds, Math.max(0, safeDurationSeconds - MIN_TRIM_SPAN_SECONDS)),
      )
      const safeTrimEndRaw = trimEndSeconds > 0 ? trimEndSeconds : safeDurationSeconds
      const safeTrimEnd = safeDurationSeconds > 0
        ? Math.max(MIN_TRIM_SPAN_SECONDS, Math.min(safeTrimEndRaw, safeDurationSeconds))
        : 0
      if (safeDurationSeconds > 0 && safeTrimEnd - safeTrimStart < MIN_TRIM_SPAN_SECONDS) {
        throw new Error('El recorte seleccionado es inválido. Ajusta inicio y fin.')
      }
      const hasTrim =
        safeDurationSeconds > 0 &&
        (safeTrimStart > 0 || safeTrimEnd > 0) &&
        (safeTrimStart > 0 || safeTrimEnd < safeDurationSeconds)

      await insertVideoWithFallback({
        userId: user.id,
        userName: displayName,
        avatarUrl,
        title: titleDraft.trim() || 'Nuevo reel',
        description: descriptionDraft.trim(),
        videoUrl,
        coverUrl,
        durationSeconds: safeDurationSeconds,
        trimStartSeconds: hasTrim ? safeTrimStart : 0,
        trimEndSeconds: hasTrim ? safeTrimEnd : safeDurationSeconds,
      })

      setTitleDraft('')
      setDescriptionDraft('')
      setVideoFile(null)
      setCoverFile(null)
      setCoverMode('upload')
      setCoverFrameSecond(0)
      setVideoDurationSeconds(0)
      setTrimStartSeconds(0)
      setTrimEndSeconds(0)
      setPreviewTrimLoop(false)
      await loadVideos()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo publicar el reel'))
    } finally {
      setPosting(false)
    }
  }

  async function toggleVideoLike(item: ReelItem) {
    if (!user) return
    const nextLiked = !item.likedByMe
    setItems((current) => current.map((entry) => entry.id === item.id
      ? { ...entry, likedByMe: nextLiked, likeCount: Math.max(0, entry.likeCount + (nextLiked ? 1 : -1)) }
      : entry))
    try {
      if (useUbuntu) {
        await setFallbackVideoReaction(item.id, nextLiked)
      } else if (supabase) {
        if (nextLiked) {
          const { error: likeError } = await supabase.from('video_likes').insert({ video_id: item.id, user_id: user.id })
          if (likeError) throw likeError
        } else {
          const { error: unlikeError } = await supabase
            .from('video_likes')
            .delete()
            .eq('video_id', item.id)
            .eq('user_id', user.id)
          if (unlikeError) throw unlikeError
        }
      }
      await loadVideos({ silent: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar el like'))
      await loadVideos({ silent: true })
    }
  }

  async function toggleVideoFavorite(item: ReelItem) {
    if (!user) return
    const nextFavorited = !item.favoritedByMe
    setItems((current) =>
      current.map((entry) =>
        entry.id === item.id
          ? {
              ...entry,
              favoritedByMe: nextFavorited,
              favoriteCount: Math.max(0, entry.favoriteCount + (nextFavorited ? 1 : -1)),
            }
          : entry,
      ),
    )
    try {
      if (useUbuntu) {
        await setFallbackVideoFavorite(item.id, nextFavorited)
      } else if (supabase) {
        const camelKey = 'video_favorites'
        if (nextFavorited) {
          const { error: favError } = await supabase
            .from(camelKey)
            .insert({ video_id: item.id, user_id: user.id })
          if (favError && isMissingSchemaError(favError)) {
            const { error: fallbackError } = await supabase
              .from('video_favorites')
              .insert({ videoId: item.id, userId: user.id })
            if (fallbackError && !isMissingSchemaError(fallbackError)) throw fallbackError
          } else if (favError) {
            throw favError
          }
        } else {
          const { error: unfavError } = await supabase
            .from(camelKey)
            .delete()
            .eq('video_id', item.id)
            .eq('user_id', user.id)
          if (unfavError && isMissingSchemaError(unfavError)) {
            const { error: fallbackError } = await supabase
              .from('video_favorites')
              .delete()
              .eq('videoId', item.id)
              .eq('userId', user.id)
            if (fallbackError && !isMissingSchemaError(fallbackError)) throw fallbackError
          } else if (unfavError) {
            throw unfavError
          }
        }
      }
      await loadVideos({ silent: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar el favorito'))
      await loadVideos({ silent: true })
    }
  }

  function shareVideo(item: ReelItem) {
    if (typeof window === 'undefined') return
    const shareUrl = `${window.location.origin}/app/reels/${item.id}`
    if (navigator.share) {
      void navigator
        .share({ title: item.title || 'Video de Novvin', text: item.description || '', url: shareUrl })
        .catch(() => undefined)
    } else {
      void navigator.clipboard.writeText(shareUrl).catch(() => undefined)
    }
  }

  async function toggleProfileShare(item: ReelItem) {
    if (!user) return
    const nextShared = !item.sharedByMe
    setItems((current) =>
      current.map((entry) =>
        entry.id === item.id ? { ...entry, sharedByMe: nextShared } : entry,
      ),
    )
    try {
      if (useUbuntu) {
        // The Ubuntu backend doesn't expose a dedicated share endpoint; keep optimistic update.
        await loadVideos({ silent: true })
      } else if (supabase) {
        const { error: shareError } = await supabase
          .from('video_shares')
          .upsert(
            { video_id: item.id, user_id: user.id, shared: nextShared },
            { onConflict: 'video_id,user_id' },
          )
        if (shareError && isMissingSchemaError(shareError)) {
          const { error: fallbackError } = await supabase
            .from('video_shares')
            .upsert(
              { videoId: item.id, userId: user.id, shared: nextShared },
              { onConflict: 'videoId,userId' },
            )
          if (fallbackError && !isMissingSchemaError(fallbackError)) throw fallbackError
        } else if (shareError) {
          throw shareError
        }
        await loadVideos({ silent: true })
      }
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar el compartido'))
      await loadVideos({ silent: true })
    }
  }

  if (loading) {
    return <StatusView title="Cargando reels" description="Buscando videos..." />
  }
  if (!useUbuntu && !supabase && !isFallbackAuthConfigured) {
    return <StatusView title="Reels no disponible" description="Configura VITE_FALLBACK_API_URL." />
  }

  return (
    <section className="section-stack reels-page">
      {syncing ? <small>Sincronizando videos...</small> : null}

      {error ? <StatusView title="Error de reels" description={error} /> : null}

      <div className="reels-compact-toolbar">
        <div className="reels-mode-switch" aria-label="Modo del feed">
          <button
            className={feedMode === 'friends' ? 'active' : ''}
            type="button"
            aria-pressed={feedMode === 'friends'}
            onClick={() => {
              setFeedMode('friends')
              setSortBy('recommended')
            }}
          >
            Amigos
          </button>
          <button
            className={feedMode === 'recommended' ? 'active' : ''}
            type="button"
            aria-pressed={feedMode === 'recommended'}
            onClick={() => {
              setFeedMode('recommended')
              setSortBy('recommended')
            }}
          >
            Recomendado
          </button>
        </div>
        <div className="reels-toolbar-actions">
          <label className="reels-search-control" title="Buscar videos">
            <Search aria-hidden="true" />
            <input placeholder="Buscar" value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} />
          </label>
          <details className="reels-filter-menu">
            <summary className="icon-btn" title="Ordenar y filtrar" aria-label="Ordenar y filtrar"><SlidersHorizontal aria-hidden="true" /></summary>
            <div className="reels-filter-popover">
              <label>Orden
                <select value={sortBy} onChange={(event) => setSortBy(event.target.value as FeedSort)}>
                  <option value="recommended">Recomendado</option>
                  <option value="new">Más recientes</option>
                  <option value="likes">Más likes</option>
                  <option value="views">Más vistas</option>
                </select>
              </label>
              <label className="checkbox-inline"><input type="checkbox" checked={onlyMine} onChange={(event) => setOnlyMine(event.target.checked)} />Solo mis videos</label>
            </div>
          </details>
          <details className="reels-create-menu">
            <summary className="primary-btn reels-publish-button" title="Crear" aria-label="Abrir opciones para crear"><Plus aria-hidden="true" /><span>Crear</span></summary>
            <div className="reels-create-popover" role="menu">
              <button type="button" role="menuitem" onClick={() => setShowUploadForm(true)}><Upload aria-hidden="true" /><span><strong>Subir video</strong><small>Publicar un reel</small></span></button>
              <Link role="menuitem" to="/app/live?create=1"><Radio aria-hidden="true" /><span><strong>Crear directo</strong><small>Cámara o pantalla</small></span></Link>
              <Link role="menuitem" to="/app/live"><Search aria-hidden="true" /><span><strong>Ver directos</strong><small>Emisiones activas</small></span></Link>
            </div>
          </details>
        </div>
      </div>

      <LiveStrip compact />

      {showUploadForm ? <div className="app-dialog-backdrop" role="presentation" onMouseDown={() => setShowUploadForm(false)}>
        <article className="surface-card section-stack video-upload-dialog" role="dialog" aria-modal="true" aria-label="Publicar video" onMouseDown={(event) => event.stopPropagation()}>
        <div className="row-between"><h3>Publicar reel</h3><button className="icon-btn" type="button" onClick={() => setShowUploadForm(false)} title="Cerrar" aria-label="Cerrar"><X aria-hidden="true" /></button></div>
        <form className="section-stack" onSubmit={createReel}>
          <div className="composer">
            <input
              className="text-input"
              placeholder="Título"
              value={titleDraft}
              onChange={(event) => setTitleDraft(event.target.value)}
            />
            <input
              className="text-input"
              placeholder="Descripción"
              value={descriptionDraft}
              onChange={(event) => setDescriptionDraft(event.target.value)}
            />
          </div>
          <div className="composer">
            <input
              className="text-input"
              type="file"
              accept="video/*"
              onChange={(event) => {
                setVideoFile(event.target.files?.[0] ?? null)
                setCoverFile(null)
                setCoverFrameSecond(0)
                setTrimStartSeconds(0)
                setTrimEndSeconds(0)
              }}
            />
            <button className="primary-btn" type="submit" disabled={!canPublish}>
              {posting ? 'Publicando...' : 'Publicar reel'}
            </button>
          </div>
          <div className="chip-row">
            <button
              className={coverMode === 'upload' ? 'chip-btn active' : 'chip-btn'}
              type="button"
              onClick={() => setCoverMode('upload')}
            >
              Portada por archivo
            </button>
            <button
              className={coverMode === 'frame' ? 'chip-btn active' : 'chip-btn'}
              type="button"
              onClick={() => setCoverMode('frame')}
            >
              Portada por fotograma
            </button>
            {coverFile ? (
              <button className="chip-btn" type="button" onClick={() => setCoverFile(null)}>
                Quitar portada
              </button>
            ) : null}
          </div>
          {coverMode === 'upload' ? (
            <div className="composer">
              <input
                className="text-input"
                type="file"
                accept="image/*"
                onChange={(event) => setCoverFile(event.target.files?.[0] ?? null)}
              />
            </div>
          ) : (
            <div className="composer">
              <input
                className="text-input"
                type="range"
                min={0}
                max={Math.max(0, videoDurationSeconds)}
                step={1}
                value={Math.min(coverFrameSecond, Math.max(0, videoDurationSeconds))}
                disabled={!videoFile || videoDurationSeconds <= 0 || generatingFrameCover}
                onChange={(event) => setCoverFrameSecond(Number(event.target.value))}
              />
              <span className="folder-tag">
                Frame {formatSecondsClock(coverFrameSecond)} / {formatSecondsClock(videoDurationSeconds)}
              </span>
              <button
                className="ghost-btn"
                type="button"
                disabled={!videoFile || videoDurationSeconds <= 0 || generatingFrameCover}
                onClick={() => void generateCoverFromFrame()}
              >
                {generatingFrameCover ? 'Generando...' : 'Generar portada'}
              </button>
            </div>
          )}
          {coverMode === 'frame' ? (
            <div className="section-stack">
              {loadingSelectedFramePreview ? (
                <small>Actualizando preview del frame seleccionado...</small>
              ) : selectedFramePreviewUrl ? (
                <>
                  <small>Frame seleccionado para portada: {formatSecondsClock(coverFrameSecond)}</small>
                  <img
                    src={selectedFramePreviewUrl}
                    alt={`Frame ${formatSecondsClock(coverFrameSecond)}`}
                    className="trim-frame-preview"
                  />
                </>
              ) : null}
            </div>
          ) : null}
          {videoFile ? (
            <div className="section-stack">
              <small>
                Video: {videoFile.name} ({formatSize(videoFile.size)}){' '}
                {readingDuration
                  ? '• detectando duración...'
                  : videoDurationSeconds > 0
                    ? `• ${videoDurationSeconds}s`
                    : ''}
              </small>
              <div className="quality-gate-grid">
                <span className={videoDurationSeconds > MAX_REEL_DURATION_SECONDS ? 'quality-gate danger' : 'quality-gate ok'}>
                  Límite web/móvil: máximo {MAX_REEL_DURATION_SECONDS}s
                </span>
                {watermarkDetection?.detectedBrands.length ? (
                  <span className="quality-gate danger">
                    Marca de agua detectada: {watermarkDetection.detectedBrands.join(', ')}
                  </span>
                ) : (
                  <span className="quality-gate ok">Sin señales de TikTok/Facebook/YouTube/Kwai/Instagram</span>
                )}
              </div>
              {videoPreviewUrl ? (
                <video
                  ref={previewVideoRef}
                  controls
                  preload="metadata"
                  src={videoPreviewUrl}
                  className="video-frame"
                  onTimeUpdate={handlePreviewTimeUpdate}
                />
              ) : null}
              <div className="section-stack">
                <small>
                  Recorte: {formatSecondsClock(trimStartSeconds)} → {formatSecondsClock(trimEndSeconds || videoDurationSeconds)}
                </small>
                <div className="trim-timeline">
                  <div className="trim-timeline-full" />
                  <div
                    className="trim-timeline-window"
                    style={{
                      left: `${trimStartPercent}%`,
                      width: `${Math.max(0, trimEndPercent - trimStartPercent)}%`,
                    }}
                  />
                </div>
                {loadingTrimPreviewFrames ? (
                  <small>Generando miniaturas del video...</small>
                ) : trimPreviewFrames.length > 0 ? (
                  <div className="trim-strip">
                    {trimPreviewFrames.map((frame) => {
                      const inRange =
                        frame.second >= trimStartSeconds &&
                        frame.second <= (trimEndSeconds || videoDurationSeconds)
                      const isCoverFrame = Math.abs(frame.second - coverFrameSecond) <= 1
                      const classNames = [
                        'trim-strip-item',
                        inRange ? 'in-range' : '',
                        isCoverFrame && coverMode === 'frame' ? 'cover-selected' : '',
                      ]
                        .join(' ')
                        .trim()
                      return (
                        <button
                          key={`${frame.second}-${frame.src.slice(0, 12)}`}
                          type="button"
                          className={classNames}
                          onClick={() => jumpToPreviewFrame(frame.second)}
                        >
                          <img src={frame.src} alt={`Frame ${formatSecondsClock(frame.second)}`} />
                          <span>{formatSecondsClock(frame.second)}</span>
                        </button>
                      )
                    })}
                  </div>
                ) : null}
                <div className="row-actions">
                  <button className="chip-btn" type="button" disabled={!videoFile} onClick={() => jumpToTrimStart()}>
                    Ir al inicio recorte
                  </button>
                  <button className="chip-btn" type="button" disabled={!videoFile} onClick={() => jumpToTrimEnd()}>
                    Ir al fin recorte
                  </button>
                  <button
                    className={previewTrimLoop ? 'chip-btn active' : 'chip-btn'}
                    type="button"
                    disabled={!videoFile || videoDurationSeconds <= 0}
                    onClick={() => setPreviewTrimLoop((current) => !current)}
                  >
                    {previewTrimLoop ? 'Preview recorte: ON' : 'Preview recorte: OFF'}
                  </button>
                </div>
                <div className="composer">
                  <span className="folder-tag">Inicio</span>
                  <input
                    className="text-input"
                    type="range"
                    min={0}
                    max={Math.max(0, videoDurationSeconds)}
                    step={1}
                    value={Math.min(trimStartSeconds, Math.max(0, videoDurationSeconds))}
                    disabled={!videoFile || videoDurationSeconds <= 0 || posting}
                    onChange={(event) => applyTrimStart(Number(event.target.value))}
                  />
                  <span className="folder-tag">{formatSecondsClock(trimStartSeconds)}</span>
                </div>
                <div className="composer">
                  <span className="folder-tag">Fin</span>
                  <input
                    className="text-input"
                    type="range"
                    min={0}
                    max={Math.max(0, Math.min(videoDurationSeconds, MAX_REEL_DURATION_SECONDS))}
                    step={1}
                    value={Math.min(
                      trimEndSeconds || Math.min(videoDurationSeconds, MAX_REEL_DURATION_SECONDS),
                      Math.max(0, Math.min(videoDurationSeconds, MAX_REEL_DURATION_SECONDS)),
                    )}
                    disabled={!videoFile || videoDurationSeconds <= 0 || posting}
                    onChange={(event) => applyTrimEnd(Number(event.target.value))}
                  />
                  <span className="folder-tag">{formatSecondsClock(trimEndSeconds || videoDurationSeconds)}</span>
                </div>
              </div>
            </div>
          ) : (
            <small>Selecciona un video para publicar.</small>
          )}
          {coverFile ? (
            <div className="section-stack">
              <small>Portada: {coverFile.name} ({formatSize(coverFile.size)})</small>
              {coverPreviewUrl ? <img src={coverPreviewUrl} alt="Vista previa portada" className="story-media" /> : null}
            </div>
          ) : null}
        </form>
        </article>
      </div> : null}

      {visibleItems.length === 0 ? (
        <StatusView
          title={feedMode === 'friends' ? 'Sin videos de amigos' : 'Sin reels'}
          description={
            feedMode === 'friends'
              ? 'Cuando las personas que sigues publiquen videos, aparecerán aquí mezclados.'
              : 'Aún no hay videos con esos filtros. Publica el primero desde esta pantalla.'
          }
        />
      ) : (
        <div className="reels-vertical-feed">
          {visibleItems.map((item, index) => {
            const live = liveStreams.length > 0 && (index + 1) % 6 === 0
              ? liveStreams[Math.floor(index / 6) % liveStreams.length]
              : null
            return (
              <Fragment key={item.id}>
                <ReelFeedCard
                  item={item}
                  currentUserId={user?.id}
                  onLike={(video) => void toggleVideoLike(video)}
                  onFavorite={(video) => void toggleVideoFavorite(video)}
                  onShare={(video) => shareVideo(video)}
                  onToggleProfileShare={(video) => void toggleProfileShare(video)}
                />
                {live ? <LiveFeedCard stream={live} /> : null}
              </Fragment>
            )
          })}
        </div>
      )}
    </section>
  )
}
