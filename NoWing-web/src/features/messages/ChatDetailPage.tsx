import { backendRpc } from '../../core/backendRpc'
import { chatMediaAlbums } from '../../core/chatMediaAlbums'
import { ChatMediaAlbum } from '../../components/ChatMediaAlbum'
import { StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { SafeAvatar } from '../../components/SafeAvatar'
import { conversationWindow } from '../../core/conversationWindow'
import { sharedVideoReference } from '../../core/sharedVideoReference'
import { SharedVideoCard } from '../../components/SharedVideoCard'
import { retryPendingWebMessage } from '../../core/webOutbox'
import {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type DragEvent,
  type FormEvent,
} from 'react'
import { Link, useParams } from 'react-router-dom'
import { MoreHorizontal } from 'lucide-react'
import { StatusView } from '../../components/StatusView'
import {
  deleteFallbackChatMessage,
  editFallbackChatMessage,
  loadFallbackChat,
  markFallbackChatRead,
  sendFallbackChatMessage,
  toggleFallbackChatReaction,
  uploadFallbackFile,
  saveFallbackChatPreferences,
} from '../../core/fallbackData'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
  resolveSupabasePublicUrl,
} from '../../core/supabase'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
import {
  formatDateTime,
  friendlyError,
  isMissingTableError,
  textOf,
  type GenericRow,
} from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { ViewOnceMediaDialog } from '../../components/ViewOnceMediaDialog'
import { requestViewOnceMedia } from '../../core/media/viewOnceClient'
import type { ViewOnceLease } from '../../core/media/viewOnceLease'
import { ChatAttachmentMedia } from '../../components/ChatAttachmentMedia'
import { StickerPicker, type CatalogSticker } from '../../components/StickerPicker'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'

type ChatMessage = {
  id: string
  senderId: string
  content: string
  contentType: string
  mediaUrl: string
  mediaSize: number
  createdAt: string
  updatedAt: string
  status: string
  reactions: Record<string, string[]>
  replyToId: string | null
  pollId: string | null
  isForwarded: boolean
  deletedForAll: boolean
  deletedBy: string[]
  isEdited: boolean
  deliveredAt: string
  readAt: string
}

type PollState = {
  pollId: string
  optionId: string
  optionText: string
  displayOrder: number
  voteCount: number
  votedByMe: boolean
  totalVotes: number
  allowsMultiple: boolean
  isClosed: boolean
  question: string
}

type StickerItem = CatalogSticker

type ChatSettings = {
  isMuted: boolean
  isPinned: boolean
  isArchived: boolean
  isFavorite: boolean
  translateEnabled: boolean
  targetLang: string
}

type ScheduledJob = {
  id: string
  content: string
  scheduledFor: string
  status: string
  errorMessage: string
}

type OtherParticipantState = {
  id: string
  name: string
  avatarUrl: string
  lastReadAt: string
}

type SendMessageInput = {
  content: string
  mediaUrl?: string
  contentType?: string
  mediaSize?: number
  replyToId?: string | null
  isForwarded?: boolean
  forwardedFromMessageId?: string | null
  forwardedFromUserId?: string | null
}

type PendingAttachment = {
  id: string
  file: File
  contentType: 'image' | 'video' | 'audio' | 'file'
  previewUrl: string
  caption: string
}

function inferContentType(file: File): 'image' | 'video' | 'audio' | 'file' {
  if (file.type.startsWith('image/')) return 'image'
  if (file.type.startsWith('video/')) return 'video'
  if (file.type.startsWith('audio/')) return 'audio'
  return 'file'
}

type MessageMediaKind = 'image' | 'video' | 'audio' | 'pdf' | 'file'

function extensionFromUrl(url: string): string {
  try {
    const parsed = new URL(url, window.location.origin)
    const cleanPath = parsed.pathname.toLowerCase()
    const dotIndex = cleanPath.lastIndexOf('.')
    return dotIndex >= 0 ? cleanPath.slice(dotIndex + 1) : ''
  } catch {
    const cleanPath = url.split('?')[0]?.toLowerCase() ?? ''
    const dotIndex = cleanPath.lastIndexOf('.')
    return dotIndex >= 0 ? cleanPath.slice(dotIndex + 1) : ''
  }
}

function fileNameFromUrl(url: string, fallback = 'Archivo'): string {
  try {
    const parsed = new URL(url, window.location.origin)
    const rawName = parsed.pathname.split('/').filter(Boolean).pop() ?? fallback
    return decodeURIComponent(rawName).replace(/^\d+_\d+_/, '').replace(/_/g, ' ')
  } catch {
    const rawName = url.split('?')[0]?.split('/').filter(Boolean).pop() ?? fallback
    try { return decodeURIComponent(rawName).replace(/^\d+_\d+_/, '').replace(/_/g, ' ') }
    catch { return rawName.replace(/_/g, ' ') }
  }
}

function mediaKindFor(contentType: string, url: string): MessageMediaKind {
  const type = contentType.toLowerCase()
  const extension = extensionFromUrl(url)
  if (type === 'image' || type.startsWith('image/') || ['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif'].includes(extension)) {
    return 'image'
  }
  if (type === 'video' || type.startsWith('video/') || ['mp4', 'webm', 'mov', 'm4v'].includes(extension)) {
    return 'video'
  }
  if (type === 'audio' || type.startsWith('audio/') || ['mp3', 'wav', 'ogg', 'oga', 'm4a', 'webm'].includes(extension)) {
    return 'audio'
  }
  if (type === 'application/pdf' || extension === 'pdf') return 'pdf'
  return 'file'
}

function contentLooksLikeAttachmentName(content: string): boolean {
  return /\.(avif|gif|jpe?g|m4a|m4v|mov|mp3|mp4|oga|ogg|pdf|png|webm|webp|wav|docx?|xlsx?|pptx?|zip)$/i.test(
    content.trim(),
  )
}

function shouldRenderMessageText(message: ChatMessage): boolean {
  const content = message.content.trim()
  if (!content) return false
  if (!message.mediaUrl) return true
  if (message.contentType === 'location' || message.contentType === 'contact') return true
  return !contentLooksLikeAttachmentName(content)
}

function clipboardFilesFrom(dataTransfer: DataTransfer | null): File[] {
  if (!dataTransfer) return []
  const files = Array.from(dataTransfer.files ?? []).filter((file) => file.size > 0)
  if (files.length > 0) return files
  return Array.from(dataTransfer.items ?? [])
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => Boolean(file && file.size > 0))
}

function toBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') return value.toLowerCase() === 'true'
  if (typeof value === 'number') return value === 1
  return false
}

function normalizeReactions(input: unknown): Record<string, string[]> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return {}
  const result: Record<string, string[]> = {}
  for (const [emoji, users] of Object.entries(input as Record<string, unknown>)) {
    if (!Array.isArray(users)) continue
    result[emoji] = users.map((entry) => String(entry))
  }
  return result
}

function normalizeStringArray(input: unknown): string[] {
  if (!Array.isArray(input)) return []
  return input.map((value) => String(value).trim()).filter((value) => value.length > 0)
}

function isMissingFunctionError(error: unknown): boolean {
  const message =
    error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return (
    message.includes('could not find the function') ||
    message.includes('42883') ||
    message.includes('function')
  )
}

function isMissingColumnError(error: unknown): boolean {
  const message =
    error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return message.includes('column') || message.includes('42703')
}

function isViewOnceType(contentType: string): boolean {
  return contentType === 'view_once_image' || contentType === 'view_once_video'
}

function localDateTimeToIso(value: string): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  const parsed = new Date(trimmed)
  if (Number.isNaN(parsed.getTime())) return null
  return parsed.toISOString()
}

function toMillis(value: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function mineDeliveryState(
  message: ChatMessage,
  readByOther: boolean,
): 'pending' | 'failed' | 'sent' | 'delivered' | 'read' {
  if (message.status === 'pending' || message.status === 'failed') return message.status
  if (readByOther || Boolean(message.readAt)) return 'read'
  const status = (message.status || '').toLowerCase()
  if (Boolean(message.deliveredAt) || status === 'delivered' || status === 'read' || status === 'received') {
    return 'delivered'
  }
  return 'sent'
}

function reactionCount(reactions: Record<string, string[]>, emoji: string): number {
  return reactions[emoji]?.length ?? 0
}

function previewUrlForFile(file: File): string {
  if (file.type.startsWith('image/') || file.type.startsWith('video/')) {
    return URL.createObjectURL(file)
  }
  return ''
}

function formatFileSize(sizeInBytes: number): string {
  if (!Number.isFinite(sizeInBytes) || sizeInBytes <= 0) return ''
  if (sizeInBytes < 1024) return `${sizeInBytes} B`
  if (sizeInBytes < 1024 * 1024) return `${Math.round(sizeInBytes / 1024)} KB`
  return `${(sizeInBytes / (1024 * 1024)).toFixed(1)} MB`
}

function mediaEntryUrl(entry: unknown): string {
  if (typeof entry === 'string') return entry.trim()
  if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return ''
  const row = entry as GenericRow
  return textOf(row, ['url', 'publicUrl', 'public_url', 'media_url', 'path'], '')
}

function firstMediaEntry(row: GenericRow): GenericRow | string | null {
  const raw = row.media_urls ?? row.mediaUrls
  if (!raw) return null
  if (Array.isArray(raw)) return (raw.find((entry) => mediaEntryUrl(entry)) as GenericRow | string | undefined) ?? null
  if (typeof raw !== 'string') return null

  const trimmed = raw.trim()
  if (!trimmed) return null
  try {
    const parsed = JSON.parse(trimmed) as unknown
    if (Array.isArray(parsed)) {
      return (parsed.find((entry) => mediaEntryUrl(entry)) as GenericRow | string | undefined) ?? null
    }
  } catch {
    return null
  }
  return null
}

function mediaUrlOf(row: GenericRow): string {
  const direct = textOf(row, ['media_url', 'mediaUrl'], '')
  if (direct) return direct
  const entry = firstMediaEntry(row)
  return mediaEntryUrl(entry)
}

function mediaSizeOf(row: GenericRow): number {
  const direct = Number(row.media_size ?? row.mediaSize ?? 0)
  if (Number.isFinite(direct) && direct > 0) return direct
  const entry = firstMediaEntry(row)
  if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return 0
  const candidate = Number((entry as GenericRow).size ?? (entry as GenericRow).bytes ?? 0)
  return Number.isFinite(candidate) && candidate > 0 ? candidate : 0
}

function ChatMessageMedia({ message }: { message: ChatMessage }) {
  if (!message.mediaUrl) return null
  return <ChatAttachmentMedia url={message.mediaUrl} kind={mediaKindFor(message.contentType, message.mediaUrl)} title={message.content.trim() || fileNameFromUrl(message.mediaUrl)} size={message.mediaSize} />
}

function dayKeyOf(value: string): string {
  if (!value) return 'sin-fecha'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return `${parsed.getFullYear()}-${parsed.getMonth()}-${parsed.getDate()}`
}

function formatConversationDay(value: string): string {
  if (!value) return 'Sin fecha'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value

  const now = new Date()
  const todayKey = dayKeyOf(now.toISOString())
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  const dayKey = dayKeyOf(parsed.toISOString())

  if (dayKey === todayKey) return 'Hoy'
  if (dayKey === dayKeyOf(yesterday.toISOString())) return 'Ayer'

  const includeYear = parsed.getFullYear() !== now.getFullYear()
  return new Intl.DateTimeFormat('es-CO', {
    day: 'numeric',
    month: 'long',
    year: includeYear ? 'numeric' : undefined,
  }).format(parsed)
}

function mapMessage(row: GenericRow): ChatMessage {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    senderId: textOf(row, ['sender_id'], ''),
    content: textOf(row, ['content'], ''),
    contentType: textOf(row, ['content_type'], 'text'),
    mediaUrl: mediaUrlOf(row),
    mediaSize: mediaSizeOf(row),
    createdAt: textOf(row, ['created_at', 'updated_at'], ''),
    updatedAt: textOf(row, ['updated_at', 'created_at'], ''),
    status: textOf(row, ['status'], 'sent'),
    reactions: normalizeReactions(row.reactions),
    replyToId: textOf(row, ['reply_to_id'], '') || null,
    pollId: textOf(row, ['poll_id'], '') || null,
    isForwarded: toBoolean(row.is_forwarded),
    deletedForAll: toBoolean(row.deleted_for_all),
    deletedBy: normalizeStringArray(row.deleted_by),
    isEdited: toBoolean(row.is_edited),
    deliveredAt: textOf(row, ['delivered_at'], ''),
    readAt: textOf(row, ['read_at'], ''),
  }
}

function mapPollState(row: GenericRow): PollState {
  return {
    pollId: textOf(row, ['poll_id'], ''),
    optionId: textOf(row, ['option_id'], ''),
    optionText: textOf(row, ['option_text'], ''),
    displayOrder: Number(row.display_order ?? 0),
    voteCount: Number(row.vote_count ?? 0),
    votedByMe: toBoolean(row.voted_by_me),
    totalVotes: Number(row.total_votes ?? 0),
    allowsMultiple: toBoolean(row.allows_multiple),
    isClosed: toBoolean(row.is_closed),
    question: textOf(row, ['question'], ''),
  }
}



function profilePathFor(profileId: string, currentUserId?: string): string {
  const clean = profileId.trim()
  if (!clean) return '/app/profile'
  if (currentUserId && clean === currentUserId) return '/app/profile'
  return `/app/profile/${clean}`
}

function FallbackAvatar({ url, name, className }: { url: string; name: string; className: string }) {
  return <SafeAvatar url={url} name={name} className={className} size="sm" />
}

type ChatDetailPageProps = {
  chatIdOverride?: string
  embedded?: boolean
}

export function ChatDetailPage({ chatIdOverride, embedded = false }: ChatDetailPageProps = {}) {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const { chatId: routeChatId = '' } = useParams()
  const chatId = chatIdOverride ?? routeChatId
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [title, setTitle] = useState('Chat')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const draftRevisionRef = useRef(0)
  const sendInFlightRef = useRef(false)
  const [sending, setSending] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [isRecordingAudio, setIsRecordingAudio] = useState(false)
  const [dragActive, setDragActive] = useState(false)
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([])
  const [showMessageSearch, setShowMessageSearch] = useState(false)
  const [messageSearchQuery, setMessageSearchQuery] = useState('')
  const [showQuickShare, setShowQuickShare] = useState(false)
  const [replyToId, setReplyToId] = useState<string | null>(null)
  const [editingMessageId, setEditingMessageId] = useState<string | null>(null)
  const [editingDraft, setEditingDraft] = useState('')
  const [pollQuestion, setPollQuestion] = useState('')
  const [pollOptions, setPollOptions] = useState('Si\nNo')
  const [showPollComposer, setShowPollComposer] = useState(false)
  const [pollAllowsMultiple, setPollAllowsMultiple] = useState(false)
  const [creatingPoll, setCreatingPoll] = useState(false)
  const [scheduledDraft, setScheduledDraft] = useState('')
  const [scheduledAt, setScheduledAt] = useState('')
  const [showScheduleComposer, setShowScheduleComposer] = useState(false)
  const [scheduling, setScheduling] = useState(false)
  const [showStickers, setShowStickers] = useState(false)
  const [scheduledJobs, setScheduledJobs] = useState<ScheduledJob[]>([])
  const scheduleRequestKeyRef = useRef<string | null>(null)
  const [pollStatesById, setPollStatesById] = useState<Record<string, PollState[]>>({})
  const [consumedViewOnce, setConsumedViewOnce] = useState<Record<string, true>>({})
  const [viewOnceLease, setViewOnceLease] = useState<ViewOnceLease | null>(null)
  const viewOncePending = useRef(false)
  const [otherParticipant, setOtherParticipant] = useState<OtherParticipantState | null>(null)
  const [settings, setSettings] = useState<ChatSettings>({
    isMuted: false,
    isPinned: false,
    isArchived: false,
    isFavorite: false,
    translateEnabled: false,
    targetLang: 'es',
  })
  const [savingSettings, setSavingSettings] = useState(false)
  const [showScrollToBottom, setShowScrollToBottom] = useState(false)
  const threadRef = useRef<HTMLElement | null>(null)
  const messageSearchInputRef = useRef<HTMLInputElement | null>(null)
  const shouldStickToBottomRef = useRef(true)
  const forceScrollToBottomRef = useRef(true)
  const pendingInitialBottomSnapRef = useRef(true)
  const pendingAttachmentsRef = useRef<PendingAttachment[]>([])
  const audioRecorderRef = useRef<MediaRecorder | null>(null)
  const audioStreamRef = useRef<MediaStream | null>(null)
  const audioChunksRef = useRef<Blob[]>([])
  const loadSequenceRef = useRef(0)

  const cleanChatId = useMemo(() => chatId.trim().toLowerCase(), [chatId])
  const messageById = useMemo(() => new Map(messages.map((message) => [message.id, message])), [messages])
  const normalizedMessageSearch = useMemo(() => messageSearchQuery.trim().toLowerCase(), [messageSearchQuery])
  const filteredMessages = useMemo(() => {
    if (!normalizedMessageSearch) return messages
    return messages.filter((message) => {
      const haystack = `${message.content} ${message.contentType} ${message.mediaUrl}`.toLowerCase()
      return haystack.includes(normalizedMessageSearch)
    })
  }, [messages, normalizedMessageSearch])
  const [historyWindowSize, setHistoryWindowSize] = useState(80)
  useEffect(() => setHistoryWindowSize(80), [cleanChatId])
  const renderedMessages = conversationWindow(filteredMessages, historyWindowSize, Boolean(normalizedMessageSearch))
  const albums = chatMediaAlbums(renderedMessages, user?.id || '')
  const replyToMessage = replyToId ? messageById.get(replyToId) ?? null : null
  const composerText = editingMessageId ? editingDraft : draft
  const deviceId = useMemo(() => {
    const storageKey = 'conectate-web-device-id'
    const fallbackId = () => {
      try {
        return crypto.randomUUID()
      } catch {
        return `dev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
      }
    }
    try {
      const existing = localStorage.getItem(storageKey)
      if (existing && existing.trim().length > 0) return existing
      const generated = fallbackId()
      localStorage.setItem(storageKey, generated)
      return generated
    } catch {
      return fallbackId()
    }
  }, [])

  const upsertMessageInState = useCallback((nextMessage: ChatMessage) => {
    setMessages((current) => {
      const index = current.findIndex((entry) => entry.id === nextMessage.id)
      if (index === -1) {
        return [...current, nextMessage].sort((a, b) => toMillis(a.createdAt) - toMillis(b.createdAt))
      }
      const next = [...current]
      next[index] = nextMessage
      next.sort((a, b) => toMillis(a.createdAt) - toMillis(b.createdAt))
      return next
    })
  }, [])

  const removeMessageFromState = useCallback((messageId: string) => {
    if (!messageId) return
    setMessages((current) => current.filter((entry) => entry.id !== messageId))
    setConsumedViewOnce((current) => {
      if (!current[messageId]) return current
      const next = { ...current }
      delete next[messageId]
      return next
    })
  }, [])

  const scrollThreadToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const thread = threadRef.current
    if (!thread) return
    shouldStickToBottomRef.current = true
    setShowScrollToBottom(false)
    thread.scrollTo({ top: thread.scrollHeight, behavior })
  }, [])

  const handleThreadScroll = useCallback(() => {
    const thread = threadRef.current
    if (!thread) return
    const distanceToBottom = thread.scrollHeight - thread.scrollTop - thread.clientHeight
    const isNearBottom = distanceToBottom < 120
    shouldStickToBottomRef.current = isNearBottom
    setShowScrollToBottom(!isNearBottom && messages.length > 0)
  }, [messages.length])
  const keepLatestMessageVisible = useCallback(() => {
    if (shouldStickToBottomRef.current) scrollThreadToBottom('auto')
  }, [scrollThreadToBottom])
  useEffect(() => {
    window.addEventListener('resize', keepLatestMessageVisible)
    return () => window.removeEventListener('resize', keepLatestMessageVisible)
  }, [keepLatestMessageVisible])

  const queueAttachmentFiles = useCallback((input: FileList | File[] | null) => {
    if (!input || input.length === 0) return
    const files = Array.from(input).filter((entry) => entry.size > 0)
    if (files.length === 0) return
    setPendingAttachments((current) => [
      ...current,
      ...files.map((file) => ({
        id: crypto.randomUUID(),
        file,
        contentType: inferContentType(file),
        previewUrl: previewUrlForFile(file),
        caption: '',
      })),
    ])
  }, [])

  const stopAudioStream = useCallback(() => {
    audioStreamRef.current?.getTracks().forEach((track) => track.stop())
    audioStreamRef.current = null
  }, [])

  const toggleAudioRecording = useCallback(async () => {
    const activeRecorder = audioRecorderRef.current
    if (activeRecorder && activeRecorder.state !== 'inactive') {
      activeRecorder.stop()
      return
    }
    if (
      typeof window === 'undefined' ||
      typeof MediaRecorder === 'undefined' ||
      !navigator.mediaDevices?.getUserMedia
    ) {
      return
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      audioStreamRef.current = stream
      const mimeCandidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus']
      const supportedMimeType = mimeCandidates.find((candidate) => {
        try {
          return MediaRecorder.isTypeSupported(candidate)
        } catch {
          return false
        }
      })
      const recorder = supportedMimeType
        ? new MediaRecorder(stream, { mimeType: supportedMimeType })
        : new MediaRecorder(stream)
      audioChunksRef.current = []
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data)
        }
      }
      recorder.onstop = () => {
        const blobType = recorder.mimeType || 'audio/webm'
        const blob = new Blob(audioChunksRef.current, { type: blobType })
        audioChunksRef.current = []
        audioRecorderRef.current = null
        stopAudioStream()
        setIsRecordingAudio(false)
        if (blob.size === 0) return
        const extension = blobType.includes('ogg') ? 'ogg' : 'webm'
        const file = new File([blob], `audio-${Date.now()}.${extension}`, { type: blobType })
        queueAttachmentFiles([file])
      }
      recorder.onerror = () => {
        audioChunksRef.current = []
        audioRecorderRef.current = null
        stopAudioStream()
        setIsRecordingAudio(false)
      }
      recorder.start(250)
      audioRecorderRef.current = recorder
      setIsRecordingAudio(true)
    } catch {
      audioChunksRef.current = []
      audioRecorderRef.current = null
      stopAudioStream()
      setIsRecordingAudio(false)
    }
  }, [queueAttachmentFiles, stopAudioStream])

  const updatePendingAttachmentCaption = useCallback((attachmentId: string, caption: string) => {
    setPendingAttachments((current) =>
      current.map((entry) => (entry.id === attachmentId ? { ...entry, caption } : entry)),
    )
  }, [])

  const removePendingAttachment = useCallback((attachmentId: string) => {
    setPendingAttachments((current) => {
      const target = current.find((entry) => entry.id === attachmentId)
      if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl)
      return current.filter((entry) => entry.id !== attachmentId)
    })
  }, [])

  const clearPendingAttachments = useCallback(() => {
    setPendingAttachments((current) => {
      current.forEach((entry) => {
        if (entry.previewUrl) URL.revokeObjectURL(entry.previewUrl)
      })
      return []
    })
  }, [])

  const handleDropZoneDragOver = useCallback((event: DragEvent<HTMLElement>) => {
    event.preventDefault()
    event.stopPropagation()
    setDragActive(true)
  }, [])

  const handleDropZoneDragLeave = useCallback((event: DragEvent<HTMLElement>) => {
    event.preventDefault()
    event.stopPropagation()
    if (event.currentTarget.contains(event.relatedTarget as Node | null)) return
    setDragActive(false)
  }, [])

  const handleDropZoneDrop = useCallback(
    (event: DragEvent<HTMLElement>) => {
      event.preventDefault()
      event.stopPropagation()
      setDragActive(false)
      queueAttachmentFiles(event.dataTransfer.files)
    },
    [queueAttachmentFiles],
  )

  useEffect(() => {
    if (editingMessageId) return undefined
    const handlePaste = (event: ClipboardEvent) => {
      const files = clipboardFilesFrom(event.clipboardData)
      if (files.length === 0) return
      event.preventDefault()
      queueAttachmentFiles(files)
    }
    window.addEventListener('paste', handlePaste)
    return () => window.removeEventListener('paste', handlePaste)
  }, [editingMessageId, queueAttachmentFiles])

  useEffect(() => {
    pendingAttachmentsRef.current = pendingAttachments
  }, [pendingAttachments])

  useEffect(() => {
    return () => {
      pendingAttachmentsRef.current.forEach((entry) => {
        if (entry.previewUrl) URL.revokeObjectURL(entry.previewUrl)
      })
    }
  }, [])

  useEffect(() => {
    return () => {
      const recorder = audioRecorderRef.current
      if (recorder) {
        recorder.ondataavailable = null
        recorder.onstop = null
        recorder.onerror = null
        if (recorder.state !== 'inactive') {
          recorder.stop()
        }
      }
      audioRecorderRef.current = null
      audioChunksRef.current = []
      stopAudioStream()
    }
  }, [stopAudioStream])

  const loadPollStates = useCallback(async (rows: ChatMessage[]) => {
    if (!supabase && !useUbuntu) return
    const pollIds = Array.from(
      new Set(rows.map((message) => message.pollId).filter((pollId): pollId is string => Boolean(pollId))),
    )
    if (pollIds.length === 0) {
      setPollStatesById({})
      return
    }

    const entries = await Promise.all(
      pollIds.map(async (pollId) => {
        const { data, error: pollError } = await backendRpc('secure_get_poll_state', { p_poll_id: pollId })
        if (pollError) return [pollId, [] as PollState[]] as const
        return [pollId, ((data ?? []) as GenericRow[]).map(mapPollState)] as const
      }),
    )

    setPollStatesById(Object.fromEntries(entries))
  }, [useUbuntu])

  const refreshPollState = useCallback(async (pollId: string) => {
    if ((!supabase && !useUbuntu) || !pollId) return
    const { data, error } = await backendRpc('secure_get_poll_state', { p_poll_id: pollId })
    if (error) return
    const mapped = ((data ?? []) as GenericRow[]).map(mapPollState)
    setPollStatesById((current) => ({ ...current, [pollId]: mapped }))
  }, [useUbuntu])

  const refreshMessageById = useCallback(
    async (messageId: string) => {
      if (!supabase || !messageId || !cleanChatId) return
      const { data, error } = await supabase
        .from('messages')
        .select('*')
        .eq('chat_id', cleanChatId)
        .eq('id', messageId)
        .limit(1)
      if (error) return
      const row = ((data ?? []) as GenericRow[])[0]
      if (!row) {
        removeMessageFromState(messageId)
        return
      }
      const mapped = mapMessage(row)
      upsertMessageInState(mapped)
      if (mapped.pollId) {
        void refreshPollState(mapped.pollId)
      }
    },
    [cleanChatId, refreshPollState, removeMessageFromState, upsertMessageInState],
  )

  const loadConsumedViewOnce = useCallback(
    async (rows: ChatMessage[]) => {
      if (!supabase || !user) return
      const ids = rows.filter((message) => isViewOnceType(message.contentType)).map((message) => message.id)
      if (ids.length === 0) {
        setConsumedViewOnce({})
        return
      }
      const { data, error: consumeError } = await supabase
        .from('message_view_once_consumptions')
        .select('message_id')
        .eq('scope_type', 'dm')
        .eq('user_id', user.id)
        .in('message_id', ids)
      if (consumeError) {
        if (isMissingTableError(consumeError)) return
        throw consumeError
      }
      const consumed = ((data ?? []) as GenericRow[]).reduce<Record<string, true>>((acc, row) => {
        const messageId = textOf(row, ['message_id'], '')
        if (messageId) acc[messageId] = true
        return acc
      }, {})
      setConsumedViewOnce(consumed)
    },
    [user],
  )

  const refreshScheduledJobs = useCallback(async () => {
    if (!cleanChatId) return
    try {
      let rows: GenericRow[] = []
      if (useUbuntu) {
        rows = await fallbackApiJson<GenericRow[]>('/rest/v1/rpc/secure_get_chat_scheduled_jobs', {
          method: 'POST',
          body: JSON.stringify({ p_scope_type: 'dm', p_target_id: cleanChatId }),
        })
      } else {
        if (!supabase) return
        const { data, error: jobsError } = await supabase.rpc('secure_get_chat_scheduled_jobs', {
          p_scope_type: 'dm',
          p_target_id: cleanChatId,
        })
        if (jobsError) throw jobsError
        rows = (data ?? []) as GenericRow[]
      }
      setScheduledJobs(rows.map((row) => ({
        id: textOf(row, ['id'], ''),
        content: textOf(row, ['content'], ''),
        scheduledFor: textOf(row, ['scheduled_for', 'scheduledFor'], ''),
        status: textOf(row, ['status'], 'pending'),
        errorMessage: textOf(row, ['error_message', 'errorMessage'], ''),
      })).filter((job) => job.id))
    } catch (caught) {
      console.error('Error loading scheduled jobs:', caught)
    }
  }, [cleanChatId, useUbuntu])


  const markAsDeliveredAndRead = useCallback(async () => {
    if (!user || !cleanChatId) return
    if (useUbuntu) {
      try {
        await markFallbackChatRead(cleanChatId)
      } catch (caught) {
        console.warn('markFallbackChatRead failed', caught)
      }
      return
    }
    if (!supabase) return
    const nowIso = new Date().toISOString()
    try {
      const [{ error: deliveredError }, { error: readError }] = await Promise.all([
        supabase.rpc('secure_mark_messages_delivered', { p_chat_id: cleanChatId }),
        supabase.rpc('secure_mark_messages_read', { p_chat_id: cleanChatId }),
      ])

      if (deliveredError && !isMissingFunctionError(deliveredError)) {
        throw deliveredError
      }
      if (readError && !isMissingFunctionError(readError)) {
        throw readError
      }

      const { error: messageUpdateError } = await supabase
        .from('messages')
        .update({ status: 'read', read_at: nowIso, delivered_at: nowIso, updated_at: nowIso })
        .eq('chat_id', cleanChatId)
        .neq('sender_id', user.id)
        .is('read_at', null)
      if (messageUpdateError && !isMissingTableError(messageUpdateError)) {
        throw messageUpdateError
      }

      const { error: participantUpsertError } = await supabase.from('chat_participants').upsert(
        {
          chat_id: cleanChatId,
          user_id: user.id,
          joined_at: nowIso,
          last_read_at: nowIso,
        },
        { onConflict: 'chat_id,user_id' },
      )
      if (participantUpsertError && !isMissingTableError(participantUpsertError)) {
        throw participantUpsertError
      }
    } catch (caught) {
      console.warn('markAsDeliveredAndRead failed', caught)
    }
  }, [cleanChatId, useUbuntu, user])

  const loadChatSettings = useCallback(async () => {
    if (!supabase || !user || !cleanChatId) return
    try {
      let participantResult: { data: unknown; error: unknown } = await supabase
        .from('chat_participants')
        .select('is_muted,is_pinned,is_archived,is_favorite')
        .eq('chat_id', cleanChatId)
        .eq('user_id', user.id)
        .limit(1)
      if (participantResult.error && isMissingColumnError(participantResult.error)) {
        participantResult = await supabase
          .from('chat_participants')
          .select('is_muted,is_pinned,is_archived')
          .eq('chat_id', cleanChatId)
          .eq('user_id', user.id)
          .limit(1)
      }
      if (participantResult.error && isMissingColumnError(participantResult.error)) {
        participantResult = await supabase
          .from('chat_participants')
          .select('is_muted,is_pinned')
          .eq('chat_id', cleanChatId)
          .eq('user_id', user.id)
          .limit(1)
      }
      if (participantResult.error && !isMissingTableError(participantResult.error)) {
        throw participantResult.error
      }
      const { data: participantRows } = participantResult
      const participant = ((participantRows ?? []) as GenericRow[])[0]

      let translateEnabled = false
      let targetLang = 'es'
      const { data: translationRows, error: translationError } = await supabase
        .from('chat_translation_settings')
        .select('enabled,target_lang')
        .eq('chat_id', cleanChatId)
        .eq('user_id', user.id)
        .limit(1)
      if (!translationError) {
        const translation = ((translationRows ?? []) as GenericRow[])[0]
        translateEnabled = toBoolean(translation?.enabled)
        targetLang = textOf(translation ?? {}, ['target_lang'], 'es')
      } else if (!isMissingTableError(translationError)) {
        throw translationError
      }

      setSettings({
        isMuted: toBoolean(participant?.is_muted),
        isPinned: toBoolean(participant?.is_pinned),
        isArchived: toBoolean(participant?.is_archived),
        isFavorite: toBoolean(participant?.is_favorite),
        translateEnabled,
        targetLang: targetLang || 'es',
      })
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('chat-settings', caught)
      setError(friendlyError(caught, 'No se pudieron cargar ajustes del chat'))
    }
  }, [cleanChatId, user])

  const loadChat = useCallback(async (options?: { silent?: boolean }) => {
    const loadSequence = ++loadSequenceRef.current
    const isStaleLoad = () => loadSequenceRef.current !== loadSequence
    if (!user || !cleanChatId) {
      if (!isStaleLoad()) {
        setLoading(false)
      }
      return
    }

    if (!options?.silent) setLoading(true)
    setError(null)
    try {
      if (useUbuntu) {
        const response = await loadFallbackChat(cleanChatId)
        if (isStaleLoad()) return
        setTitle(response.title || 'Chat privado')
        setOtherParticipant(response.otherParticipant)
        const mappedMessages = response.messages.map(mapMessage)
        setMessages(mappedMessages)
        setSettings(response.settings)
        setScheduledJobs([])
        await loadPollStates(mappedMessages)
        setConsumedViewOnce({})
        return
      }

      if (!supabase) {
        if (!isStaleLoad()) {
          setError('Supabase no está configurado.')
          setMessages([])
        }
        return
      }

      const { data: chats, error: chatError } = await supabase
        .from('chats')
        .select('*')
        .eq('id', cleanChatId)
        .contains('participants', [user.id])
        .limit(1)
      if (chatError) throw chatError

      const chatRow = ((chats ?? []) as GenericRow[])[0]
      if (!chatRow) {
        if (!isStaleLoad()) {
          setError('No tienes acceso a este chat o no existe.')
          setMessages([])
          setLoading(false)
        }
        return
      }

      const participants = Array.isArray(chatRow.participants)
        ? chatRow.participants.map((entry) => String(entry))
        : []
      const otherId = participants.find((participantId) => participantId !== user.id)
      if (otherId) {
        const [{ data: profileRows }, { data: participantRows, error: participantError }] =
          await Promise.all([
            supabase.from('profiles').select('*').eq('id', otherId).limit(1),
            supabase
              .from('chat_participants')
              .select('last_read_at')
              .eq('chat_id', cleanChatId)
              .eq('user_id', otherId)
              .limit(1),
          ])
        if (participantError && !isMissingTableError(participantError)) {
          throw participantError
        }
        const profile = ((profileRows ?? []) as GenericRow[])[0]
        const displayName = textOf(profile ?? {}, ['displayName', 'display_name', 'username'], 'Chat privado')
        const rawAvatarUrl = textOf(profile ?? {}, ['avatar_url', 'photo_url', 'profile_image'], '')
        const avatarUrl = resolveSupabasePublicUrl('avatars', rawAvatarUrl)
        const participant = ((participantRows ?? []) as GenericRow[])[0]
        if (!isStaleLoad()) {
          setTitle(displayName)
          setOtherParticipant({
            id: otherId,
            name: displayName,
            avatarUrl,
            lastReadAt: textOf(participant ?? {}, ['last_read_at'], ''),
          })
        }
      } else {
        if (!isStaleLoad()) {
          setTitle('Chat privado')
          setOtherParticipant(null)
        }
      }

      const { data: messageRows, error: msgError } = await supabase
        .from('messages')
        .select('*')
        .eq('chat_id', cleanChatId)
        .order('created_at', { ascending: true })
        .limit(500)
      if (msgError) throw msgError

      const mappedMessages = ((messageRows ?? []) as GenericRow[]).map(mapMessage)
      if (isStaleLoad()) return
      setMessages(mappedMessages)
      await Promise.all([
        loadPollStates(mappedMessages),
        loadConsumedViewOnce(mappedMessages),
        markAsDeliveredAndRead(),
      ])
    } catch (caught) {
      if (!isStaleLoad()) {
        setError(friendlyError(caught, 'No se pudo cargar la conversación'))
      }
    } finally {
      if (!isStaleLoad()) {
        setLoading(false)
      }
    }
  }, [cleanChatId, loadConsumedViewOnce, loadPollStates, markAsDeliveredAndRead, useUbuntu, user])

  useEffect(() => {
    void loadChat()
  }, [loadChat])

  useEffect(() => {
    forceScrollToBottomRef.current = true
    pendingInitialBottomSnapRef.current = true
    shouldStickToBottomRef.current = true
    setShowScrollToBottom(false)
    setShowMessageSearch(false)
    setMessageSearchQuery('')
  }, [cleanChatId])

  useEffect(() => {
    if (loading || showMessageSearch) return
    if (!messages.length && !forceScrollToBottomRef.current) return
    const shouldScroll = forceScrollToBottomRef.current || shouldStickToBottomRef.current
    if (!shouldScroll) return
    const behavior: ScrollBehavior = forceScrollToBottomRef.current ? 'auto' : 'smooth'
    const frame = window.requestAnimationFrame(() => {
      scrollThreadToBottom(behavior)
      forceScrollToBottomRef.current = false
    })
    return () => window.cancelAnimationFrame(frame)
  }, [loading, messages, scrollThreadToBottom, showMessageSearch])

  useEffect(() => {
    if (loading || !pendingInitialBottomSnapRef.current) return
    const snapToBottom = () => scrollThreadToBottom('auto')
    const frame = window.requestAnimationFrame(snapToBottom)
    const timer = window.setTimeout(snapToBottom, 180)
    pendingInitialBottomSnapRef.current = false
    return () => {
      window.cancelAnimationFrame(frame)
      window.clearTimeout(timer)
    }
  }, [loading, scrollThreadToBottom])

  useEffect(() => {
    if (!showMessageSearch) return
    const frame = window.requestAnimationFrame(() => messageSearchInputRef.current?.focus())
    return () => window.cancelAnimationFrame(frame)
  }, [showMessageSearch])

  useEffect(() => {
    if (!cleanChatId || !user || useUbuntu) return
    const syncReadState = () => {
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
      void markAsDeliveredAndRead()
    }
    syncReadState()
    const intervalId = window.setInterval(syncReadState, 15000)
    window.addEventListener('focus', syncReadState)
    document.addEventListener('visibilitychange', syncReadState)
    return () => {
      window.clearInterval(intervalId)
      window.removeEventListener('focus', syncReadState)
      document.removeEventListener('visibilitychange', syncReadState)
    }
  }, [cleanChatId, markAsDeliveredAndRead, useUbuntu, user])

  useEffect(() => {
    if (!useUbuntu) void loadChatSettings()
    void refreshScheduledJobs()
  }, [loadChatSettings, refreshScheduledJobs, useUbuntu])

  useEffect(() => {
    if (useUbuntu && user && cleanChatId) {
      return subscribeUbuntuRealtime({
        channel: `chat:${cleanChatId}`,
        onEvent: (event) => {
          if (event.payload?.event !== 'message') return
          const row = (event.payload.message ?? {}) as GenericRow
          const messageId = textOf(row, ['id'], '')
          if (!messageId) return
          upsertMessageInState(mapMessage(row))
          const pollId = textOf(row, ['poll_id', 'pollId'], '')
          if (pollId) void refreshPollState(pollId)
        },
      })
    }
    const client = supabase
    if (!client || !user || !cleanChatId || useUbuntu) return
    const channel = client
      .channel(`chat-live-${cleanChatId}`)
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'messages', filter: `chat_id=eq.${cleanChatId}` },
        (payload) => {
          const row = ((payload.eventType === 'DELETE' ? payload.old : payload.new) ?? {}) as GenericRow
          const messageId = textOf(row, ['id'], '')
          if (!messageId) return
          if (payload.eventType === 'DELETE') {
            removeMessageFromState(messageId)
            return
          }
          const mapped = mapMessage(row)
          upsertMessageInState(mapped)
          if (mapped.pollId) {
            void refreshPollState(mapped.pollId)
          }
        },
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'message_poll_votes' },
        (payload) => {
          const row = ((payload.eventType === 'DELETE' ? payload.old : payload.new) ?? {}) as GenericRow
          const pollId = textOf(row, ['poll_id'], '')
          if (!pollId) return
          void refreshPollState(pollId)
        },
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'chat_participants', filter: `chat_id=eq.${cleanChatId}` },
        (payload) => {
          const row = ((payload.eventType === 'DELETE' ? payload.old : payload.new) ?? {}) as GenericRow
          const participantId = textOf(row, ['user_id'], '')
          if (!participantId || participantId === user.id) return
          const lastReadAt = textOf(row, ['last_read_at'], '')
          if (!lastReadAt) return
          setOtherParticipant((current) => {
            if (!current || current.id !== participantId) return current
            return { ...current, lastReadAt }
          })
        },
      )
    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: `chat-${cleanChatId}` })

    return () => {
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [cleanChatId, refreshPollState, removeMessageFromState, upsertMessageInState, useUbuntu, user])

  async function sendMessage(input: SendMessageInput) {
    if (!user || !cleanChatId) return
    forceScrollToBottomRef.current = true
    scrollThreadToBottom('auto')
    if (useUbuntu) {
      const response = await sendFallbackChatMessage(cleanChatId, {
        content: input.content,
        contentType: input.contentType ?? 'text',
        mediaUrl: input.mediaUrl ?? null,
        mediaSize: input.mediaSize ?? 0,
        replyToId: input.replyToId ?? null,
        isForwarded: input.isForwarded,
        forwardedFromMessageId: input.forwardedFromMessageId,
        forwardedFromUserId: input.forwardedFromUserId,
      })
      forceScrollToBottomRef.current = true
      upsertMessageInState(mapMessage(response.message))
      return
    }
    if (!supabase) return
    const payload = {
      p_chat_id: cleanChatId,
      p_content: input.content,
      p_content_type: input.contentType ?? 'text',
      p_media_url: input.mediaUrl || null,
      p_media_size: input.mediaSize ?? 0,
      p_reply_to_id: input.replyToId ?? null,
      p_is_forwarded: input.isForwarded ?? false,
      p_forwarded_from_message_id: input.forwardedFromMessageId ?? null,
      p_forwarded_from_user_id: input.forwardedFromUserId ?? null,
    }

    const { error: rpcError } = await supabase.rpc('secure_send_message', payload)
    if (!rpcError) return

    const nowIso = new Date().toISOString()
    const fallbackPayload: Record<string, unknown> = {
      chat_id: cleanChatId,
      sender_id: user.id,
      content: input.content,
      content_type: input.contentType ?? 'text',
      media_url: input.mediaUrl || null,
      media_size: input.mediaSize ?? 0,
      reply_to_id: input.replyToId ?? null,
      status: 'sent',
      created_at: nowIso,
    }
    const { error: insertError } = await supabase.from('messages').insert(fallbackPayload)
    if (insertError) throw insertError

    await supabase
      .from('chats')
      .update({ last_message: input.content || 'Archivo', last_message_at: nowIso, updated_at: nowIso })
      .eq('id', cleanChatId)
  }

  async function sendPendingAttachments(textFallback: string) {
    if (useUbuntu) {
      if (!user || !cleanChatId || pendingAttachments.length === 0) return
      const queued = [...pendingAttachments]
      setUploading(true)
      try {
        for (let index = 0; index < queued.length; index += 1) {
          const attachment = queued[index]
          const safeName = attachment.file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
          const filePath = `dm/${user.id}/${Date.now()}_${index}_${safeName}`
          const uploaded = await uploadFallbackFile('chat-media', attachment.file, filePath)
          const caption =
            attachment.caption.trim() || (index === 0 ? textFallback.trim() : '') || attachment.file.name
          await sendMessage({
            content: caption,
            mediaUrl: uploaded.publicUrl,
            contentType: attachment.contentType,
            mediaSize: attachment.file.size,
            replyToId: index === 0 ? replyToId : null,
          })
          setPendingAttachments((current) => current.filter((entry) => entry.id !== attachment.id))
          if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl)
        }
        setReplyToId(null)
      } finally {
        setUploading(false)
      }
      return
    }
    if (!supabase || !user || !cleanChatId || pendingAttachments.length === 0) return
    const queued = [...pendingAttachments]
    setUploading(true)
    try {
      for (let index = 0; index < queued.length; index += 1) {
        const attachment = queued[index]
        const safeName = attachment.file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
        const filePath = `dm/${user.id}/${Date.now()}_${index}_${safeName}`
        const { error: uploadError } = await supabase.storage
          .from('chat-media')
          .upload(filePath, attachment.file, { contentType: attachment.file.type || undefined })
        if (uploadError) throw uploadError
        const { data } = supabase.storage.from('chat-media').getPublicUrl(filePath)
        const caption =
          attachment.caption.trim() || (index === 0 ? textFallback.trim() : '') || attachment.file.name
        await sendMessage({
          content: caption,
          mediaUrl: data.publicUrl,
          contentType: attachment.contentType,
          mediaSize: attachment.file.size,
          replyToId: index === 0 ? replyToId : null,
        })
        setPendingAttachments((current) => current.filter((entry) => entry.id !== attachment.id))
        if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl)
      }
      setReplyToId(null)
    } finally {
      setUploading(false)
    }
  }

  useEffect(() => {
    const update = () => { void loadChat({ silent: true }) }
    window.addEventListener('novvin:outbox-changed', update)
    return () => window.removeEventListener('novvin:outbox-changed', update)
  }, [loadChat])

  async function handleSend(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!user || !cleanChatId || (!supabase && !useUbuntu)) {
      setError('No hay una sesión activa para enviar el mensaje')
      return
    }
    const text = (editingMessageId ? editingDraft : draft).trim()
    if ((!text && pendingAttachments.length === 0) || sending || uploading || sendInFlightRef.current) return
    const submittedDraft = draft
    const draftRevisionAtSend = draftRevisionRef.current
    const sendingTextOnly = !editingMessageId && pendingAttachments.length === 0
    sendInFlightRef.current = true
    if (sendingTextOnly) setDraft('')
    setSending(true)
    try {
      if (editingMessageId) {
        if (useUbuntu) {
          const response = await editFallbackChatMessage(cleanChatId, editingMessageId, text)
          upsertMessageInState(mapMessage(response.message))
          setEditingMessageId(null)
          setEditingDraft('')
          return
        }
        if (!supabase) return
        if (!text) return
        const nowIso = new Date().toISOString()
        const { error: editError } = await supabase
          .from('messages')
          .update({ content: text, is_edited: true, updated_at: nowIso })
          .eq('id', editingMessageId)
          .eq('chat_id', cleanChatId)
          .eq('sender_id', user?.id ?? '')
        if (editError) throw editError
        setMessages((current) =>
          current.map((entry) =>
            entry.id === editingMessageId
              ? { ...entry, content: text, isEdited: true, updatedAt: nowIso }
              : entry,
          ),
        )
        setEditingMessageId(null)
        setEditingDraft('')
      } else if (pendingAttachments.length > 0) {
        await sendPendingAttachments(text)
        setDraft((current) => current === submittedDraft ? '' : current)
      } else {
        await sendMessage({ content: text, replyToId })
        setReplyToId(null)
      }
    } catch (caught) {
      if (sendingTextOnly && draftRevisionRef.current === draftRevisionAtSend) {
        setDraft(submittedDraft)
      }
      setError(friendlyError(caught, 'No se pudo enviar el mensaje'))
    } finally {
      sendInFlightRef.current = false
      setSending(false)
    }
  }

  function handleAttachFile(input: FileList | File[] | null) {
    queueAttachmentFiles(input)
  }

  async function toggleReaction(messageId: string, emoji: string) {
    if (useUbuntu) {
      try {
        const response = await toggleFallbackChatReaction(cleanChatId, messageId, emoji)
        upsertMessageInState(mapMessage(response.message))
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo reaccionar'))
      }
      return
    }
    if (!supabase) return
    const { error: reactionError } = await supabase.rpc('secure_toggle_message_reaction', {
      p_message_id: messageId,
      p_emoji: emoji,
    })
    if (reactionError) {
      setError(friendlyError(reactionError, 'No se pudo reaccionar'))
      return
    }
    await refreshMessageById(messageId)
  }

  async function sendForwardedMessage(source: ChatMessage) {
    try {
      await sendMessage({
        content: source.content || 'Mensaje reenviado',
        mediaUrl: source.mediaUrl,
        contentType: source.contentType,
        isForwarded: true,
        forwardedFromMessageId: source.id,
        forwardedFromUserId: source.senderId,
      })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo reenviar'))
    }
  }

  async function deleteMessageForAll(message: ChatMessage) {
    if (useUbuntu) {
      try {
        const response = await deleteFallbackChatMessage(cleanChatId, message.id, true)
        upsertMessageInState(mapMessage(response.message))
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo borrar para todos'))
      }
      return
    }
    if (!supabase || !user || message.senderId !== user.id) return
    try {
      const nowIso = new Date().toISOString()
      const { error: deleteError } = await supabase
        .from('messages')
        .update({ deleted_for_all: true, content: '', media_url: null, updated_at: nowIso })
        .eq('id', message.id)
        .eq('sender_id', user.id)
      if (deleteError) throw deleteError
      setMessages((current) =>
        current.map((entry) =>
          entry.id === message.id
            ? { ...entry, deletedForAll: true, content: '', mediaUrl: '', updatedAt: nowIso }
            : entry,
        ),
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo borrar para todos'))
    }
  }

  async function deleteMessageForMe(message: ChatMessage) {
    if (useUbuntu) {
      try {
        const response = await deleteFallbackChatMessage(cleanChatId, message.id, false)
        upsertMessageInState(mapMessage(response.message))
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo borrar para mi'))
      }
      return
    }
    if (!supabase || !user) return
    try {
      const nextDeletedBy = Array.from(new Set([...message.deletedBy, user.id]))
      const { error: deleteError } = await supabase
        .from('messages')
        .update({ deleted_by: nextDeletedBy, updated_at: new Date().toISOString() })
        .eq('id', message.id)
      if (deleteError) throw deleteError
      setMessages((current) =>
        current.map((entry) =>
          entry.id === message.id ? { ...entry, deletedBy: nextDeletedBy } : entry,
        ),
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo borrar para mi'))
    }
  }

  async function createChatPoll() {
    if ((!supabase && !useUbuntu) || !cleanChatId || creatingPoll) return
    const question = pollQuestion.trim()
    const options = pollOptions
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.length > 0)

    if (!question || options.length < 2) {
      setError('La encuesta necesita pregunta y al menos 2 opciones')
      return
    }

    setCreatingPoll(true)
    setError(null)
    try {
      const { error: pollError } = await backendRpc('secure_create_chat_poll_message', {
        p_chat_id: cleanChatId,
        p_message_id: crypto.randomUUID(),
        p_question: question,
        p_options: options,
        p_allows_multiple: pollAllowsMultiple,
      })
      if (pollError) throw pollError
      setPollQuestion('')
      setPollOptions('Si\nNo')
      setPollAllowsMultiple(false)
      setShowPollComposer(false)
      void loadChat({ silent: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo crear encuesta'))
    } finally {
      setCreatingPoll(false)
    }
  }

  async function votePollOption(pollId: string, optionId: string, selected: boolean) {
    if ((!supabase && !useUbuntu) || !pollId || !optionId) return
    try {
      const { data, error: voteError } = await backendRpc('secure_vote_poll_option', {
        p_poll_id: pollId,
        p_option_id: optionId,
        p_selected: selected,
      })
      if (voteError) throw voteError
      const mapped = ((data ?? []) as GenericRow[]).map(mapPollState)
      setPollStatesById((current) => ({ ...current, [pollId]: mapped }))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo votar'))
    }
  }

  async function consumeViewOnce(message: ChatMessage) {
    if (!isViewOnceType(message.contentType) || viewOncePending.current || viewOnceLease) return
    viewOncePending.current = true
    try {
      const lease = await requestViewOnceMedia({ scope: 'dm', messageId: message.id, deviceId, kind: message.contentType === 'view_once_video' ? 'video' : 'image', useUbuntu })
      setConsumedViewOnce(current => ({ ...current, [message.id]: true }))
      setViewOnceLease(lease)
    } catch (caught) {
      if (caught instanceof Error && caught.message === 'VIEW_ONCE_CONSUMED') setConsumedViewOnce(current => ({ ...current, [message.id]: true }))
      setError(friendlyError(caught, 'No se pudo abrir el archivo de una sola visualización'))
    } finally { viewOncePending.current = false }
  }


  async function sendSticker(sticker: StickerItem) {
    try {
      await sendMessage({
        content: sticker.title || 'Sticker',
        mediaUrl: sticker.mediaUrl,
        contentType: 'sticker',
        replyToId,
      })
      setReplyToId(null)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar sticker'))
    }
  }

  async function scheduleMessage() {
    if ((!supabase && !useUbuntu) || !cleanChatId || scheduling) return
    const content = scheduledDraft.trim()
    if (!content) return

    const scheduleIso = localDateTimeToIso(scheduledAt)
    if (scheduledAt.trim() && !scheduleIso) {
      setError('Fecha invalida para programacion')
      return
    }

    setScheduling(true)
    setError(null)
    try {
      const requestKey = scheduleRequestKeyRef.current || crypto.randomUUID()
      scheduleRequestKeyRef.current = requestKey
      const payload = {
        p_scope_type: 'dm',
        p_target_id: cleanChatId,
        p_content: content,
        p_content_type: 'text',
        p_scheduled_for: scheduleIso,
        p_translate_to: settings.translateEnabled ? settings.targetLang : null,
        p_idempotency_key: requestKey,
      }
      if (useUbuntu) {
        await fallbackApiJson('/rest/v1/rpc/secure_schedule_message_job', {
          method: 'POST',
          body: JSON.stringify(payload),
        })
      } else {
        if (!supabase) throw new Error('Servicio de mensajes no disponible')
        const { p_idempotency_key: _idempotencyKey, ...supabasePayload } = payload
        void _idempotencyKey
        const { error: scheduleError } = await supabase.rpc('secure_schedule_message_job', supabasePayload)
        if (scheduleError) throw scheduleError
      }
      scheduleRequestKeyRef.current = null
      setScheduledDraft('')
      setScheduledAt('')
      await refreshScheduledJobs()
      setShowScheduleComposer(false)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo programar mensaje'))
    } finally {
      setScheduling(false)
    }
  }



  async function saveChatPreferences(patch: Partial<Pick<ChatSettings, 'isMuted' | 'isPinned' | 'isArchived' | 'isFavorite'>>) {
    if (useUbuntu) {
      if (savingSettings) return
      setSavingSettings(true)
      try {
        await saveFallbackChatPreferences(cleanChatId, patch)
        setSettings(current => ({ ...current, ...patch }))
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudieron guardar los cambios del chat'))
      } finally { setSavingSettings(false) }
      return
    }
    if (!supabase || !user || !cleanChatId || savingSettings) return
    const nextMuted = patch.isMuted ?? settings.isMuted
    const nextPinned = patch.isPinned ?? settings.isPinned
    const nextArchived = patch.isArchived ?? settings.isArchived
    const nextFavorite = patch.isFavorite ?? settings.isFavorite
    setSavingSettings(true)
    setError(null)
    try {
      const { error: prefError } = await supabase.rpc('secure_set_chat_preferences', {
        p_chat_id: cleanChatId,
        p_is_muted: nextMuted,
        p_is_pinned: nextPinned,
        p_is_archived: nextArchived,
        p_is_favorite: nextFavorite,
      })
      if (prefError && !isMissingFunctionError(prefError)) throw prefError
      if (prefError && isMissingFunctionError(prefError)) {
        const nowIso = new Date().toISOString()
        const fullFallback = await supabase.from('chat_participants').upsert(
          {
            chat_id: cleanChatId,
            user_id: user.id,
            joined_at: nowIso,
            last_read_at: nowIso,
            is_muted: nextMuted,
            is_pinned: nextPinned,
            is_archived: nextArchived,
            is_favorite: nextFavorite,
          },
          { onConflict: 'chat_id,user_id' },
        )
        if (fullFallback.error && !isMissingColumnError(fullFallback.error)) throw fullFallback.error
        if (fullFallback.error && isMissingColumnError(fullFallback.error)) {
          const archiveFallback = await supabase.from('chat_participants').upsert(
            {
              chat_id: cleanChatId,
              user_id: user.id,
              joined_at: nowIso,
              last_read_at: nowIso,
              is_muted: nextMuted,
              is_pinned: nextPinned,
              is_archived: nextArchived,
            },
            { onConflict: 'chat_id,user_id' },
          )
          if (archiveFallback.error && !isMissingColumnError(archiveFallback.error)) {
            throw archiveFallback.error
          }
          if (!archiveFallback.error) {
            setSettings((current) => ({
              ...current,
              isMuted: nextMuted,
              isPinned: nextPinned,
              isArchived: nextArchived,
              isFavorite: false,
            }))
            return
          }
          const { error: legacyFallbackError } = await supabase.from('chat_participants').upsert(
            {
              chat_id: cleanChatId,
              user_id: user.id,
              joined_at: nowIso,
              last_read_at: nowIso,
              is_muted: nextMuted,
              is_pinned: nextPinned,
            },
            { onConflict: 'chat_id,user_id' },
          )
          if (legacyFallbackError) throw legacyFallbackError
        }
      }
      setSettings((current) => ({
        ...current,
        isMuted: nextMuted,
        isPinned: nextPinned,
        isArchived: nextArchived,
        isFavorite: nextFavorite,
      }))
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('chat-preferences', caught)
      setError(friendlyError(caught, 'No se pudieron guardar preferencias'))
    } finally {
      setSavingSettings(false)
    }
  }

  async function saveTranslationSettings() {
    if (useUbuntu) return
    if (!supabase || !user || !cleanChatId || savingSettings) return
    const targetLang = settings.targetLang.trim().toLowerCase() || 'es'
    setSavingSettings(true)
    setError(null)
    try {
      const { error: translationError } = await supabase.rpc('secure_set_chat_translation_settings', {
        p_chat_id: cleanChatId,
        p_enabled: settings.translateEnabled,
        p_target_lang: targetLang,
      })
      if (translationError && !isMissingFunctionError(translationError)) throw translationError
      if (translationError && isMissingFunctionError(translationError)) {
        const { error: fallbackError } = await supabase.from('chat_translation_settings').upsert(
          {
            chat_id: cleanChatId,
            user_id: user.id,
            enabled: settings.translateEnabled,
            target_lang: settings.translateEnabled ? targetLang : null,
            updated_at: new Date().toISOString(),
          },
          { onConflict: 'chat_id,user_id' },
        )
        if (fallbackError) throw fallbackError
      }
      setSettings((current) => ({ ...current, targetLang }))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo guardar traduccion'))
    } finally {
      setSavingSettings(false)
    }
  }

  if (!supabase && !useUbuntu) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL para usar mensajes con Ubuntu." />
  }

  async function importSticker(file: File | null) {
    if (!file || !user || !cleanChatId) return
    if (!file.type.startsWith('image/')) {
      setError('El sticker debe ser una imagen PNG, WEBP, GIF, JPG o JPEG.')
      return
    }
    setUploading(true)
    setError(null)
    try {
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `stickers/${user.id}/${Date.now()}_${safeName}`
      const mediaUrl = useUbuntu
        ? (await uploadFallbackFile('chat-media', file, filePath)).publicUrl
        : await (async () => {
            if (!supabase) throw new Error('Backend no configurado')
            const { error: uploadError } = await supabase.storage
              .from('chat-media')
              .upload(filePath, file, { contentType: file.type || undefined })
            if (uploadError) throw uploadError
            return supabase.storage.from('chat-media').getPublicUrl(filePath).data.publicUrl
          })()
      await sendMessage({
        content: file.name.replace(/\.[^.]+$/, '') || 'Sticker',
        mediaUrl,
        mediaSize: file.size,
        contentType: 'sticker',
        replyToId,
      })
      setReplyToId(null)
      setShowStickers(false)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo importar el sticker'))
    } finally {
      setUploading(false)
    }
  }

  async function cancelScheduledJob(jobId: string) {
    if (!jobId) return
    setError(null)
    try {
      if (useUbuntu) {
        await fallbackApiJson('/rest/v1/rpc/secure_cancel_scheduled_message_job', {
          method: 'POST',
          body: JSON.stringify({ p_job_id: jobId }),
        })
      } else {
        if (!supabase) throw new Error('Servicio de mensajes no disponible')
        const { error: cancelError } = await supabase.rpc('secure_cancel_scheduled_message_job', {
          p_job_id: jobId,
        })
        if (cancelError) throw cancelError
      }
      await refreshScheduledJobs()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cancelar el mensaje programado'))
    }
  }

  if (loading) {
    return <StatusView title="Cargando conversacion" description="Espera un momento..." />
  }

  if (error) {
    return (
      <section className="section-stack">
        {!embedded ? (
          <Link to="/app/messages" className="inline-link">
            ← Volver a mensajes
          </Link>
        ) : null}
        <StatusView title="No se pudo abrir el chat" description={error} />
      </section>
    )
  }

  const rootClassName = [
    embedded ? 'section-stack chat-detail-embedded' : 'section-stack chat-detail-page',
    dragActive ? 'chat-drop-active' : '',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <section
      className={rootClassName}
      onDragOver={handleDropZoneDragOver}
      onDragLeave={handleDropZoneDragLeave}
      onDrop={handleDropZoneDrop}
    >
      {dragActive ? (
        <div className="chat-drop-overlay">
          <strong>Suelta tus archivos aquí</strong>
          <small>Imágenes, videos, audios y documentos</small>
        </div>
      ) : null}
      <div className="chat-header-row row-between shadow-sm">
        <div className="row-actions">
          {!embedded ? (
            <Link to="/app/messages" className="icon-btn-sm" title="Volver">
              ←
            </Link>
          ) : null}
          {otherParticipant ? (
            <Link className="identity-link compact" to={profilePathFor(otherParticipant.id, user?.id)}>
              <FallbackAvatar url={otherParticipant.avatarUrl} name={otherParticipant.name} className="avatar-sm" />
              <div className="col-stack-tight">
                <strong>{otherParticipant.name}</strong>
                <small>Chat privado</small>
              </div>
            </Link>
          ) : (
            <h2>{title}</h2>
          )}
        </div>
        <div className="row-actions chat-head-actions">
          {otherParticipant ? (
            <>
              <Link
                className="icon-btn header-icon-btn"
                to={`/app/calls?contextId=${encodeURIComponent(cleanChatId)}&target=${encodeURIComponent(otherParticipant.id)}&kind=audio&name=${encodeURIComponent(otherParticipant.name)}`}
                title="Llamada de voz"
              >
                <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                  <path d="M6.62 10.79c1.44 2.82 3.77 5.15 6.59 6.59l2.2-2.2a1 1 0 0 1 1.02-.24c1.12.37 2.33.57 3.57.57a1 1 0 0 1 1 1V20a1 1 0 0 1-1 1C10.61 21 3 13.39 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.24.2 2.45.57 3.57a1 1 0 0 1-.25 1.02l-2.2 2.2z" fill="currentColor" />
                </svg>
              </Link>
              <Link
                className="icon-btn header-icon-btn"
                to={`/app/calls?contextId=${encodeURIComponent(cleanChatId)}&target=${encodeURIComponent(otherParticipant.id)}&kind=video&name=${encodeURIComponent(otherParticipant.name)}`}
                title="Videollamada"
              >
                <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                  <path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h7A2.5 2.5 0 0 1 16 6.5v1.9l3.55-2.13A1 1 0 0 1 21 7.13v9.74a1 1 0 0 1-1.45.9L16 15.6v1.9a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 4 17.5v-11z" fill="currentColor" />
                </svg>
              </Link>
            </>
          ) : null}
          <button
            className={showMessageSearch ? 'icon-btn header-icon-btn active' : 'icon-btn header-icon-btn'}
            type="button"
            title="Buscar"
            onClick={() => {
              setShowMessageSearch((current) => !current)
              if (showMessageSearch) {
                setMessageSearchQuery('')
              } else {
                setShowQuickShare(false)
              }
            }}
          >
            <svg viewBox="0 0 24 24" width="20" height="20"><path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" fill="currentColor" /></svg>
          </button>
          <button
            className={showQuickShare ? 'icon-btn header-icon-btn active' : 'icon-btn header-icon-btn'}
            type="button"
            title="Menú"
            onClick={() => setShowQuickShare((c) => !c)}
          >
            <svg viewBox="0 0 24 24" width="20" height="20">
              <circle cx="12" cy="5" r="1.8" fill="currentColor" />
              <circle cx="12" cy="12" r="1.8" fill="currentColor" />
              <circle cx="12" cy="19" r="1.8" fill="currentColor" />
            </svg>
          </button>
        </div>
      </div>

      {showMessageSearch ? (
        <div className="chat-search-row">
          <input
            ref={messageSearchInputRef}
            className="text-input chat-search-input"
            placeholder="Buscar en este chat..."
            value={messageSearchQuery}
            onChange={(event) => setMessageSearchQuery(event.target.value)}
          />
          {normalizedMessageSearch ? (
            <small className="chat-search-count">
              {filteredMessages.length} resultado{filteredMessages.length === 1 ? '' : 's'}
            </small>
          ) : null}
          <button
            className="mini-action-btn"
            type="button"
            onClick={() => {
              setShowMessageSearch(false)
              setMessageSearchQuery('')
            }}
          >
            Cerrar
          </button>
        </div>
      ) : null}

      {showQuickShare ? (
        <div className="chat-tools-overlay fade-in">
          <article className="surface-card section-stack shadow-lg">
            <div className="row-between">
              <h3>Herramientas de Chat</h3>
              <button className="icon-btn-sm" onClick={() => setShowQuickShare(false)}>✕</button>
            </div>

            <div className="grid-2-col">
              <section className="tool-section">
                <h4>Preferencias</h4>
                <div className="row-actions wrap">
                  <button
                    className={settings.isPinned ? 'chip-btn active' : 'chip-btn'}
                    onClick={() => void saveChatPreferences({ isPinned: !settings.isPinned })}
                  >
                    {settings.isPinned ? 'Despinear' : 'Pinear'}
                  </button>
                  <button
                    className={settings.isMuted ? 'chip-btn active' : 'chip-btn'}
                    onClick={() => void saveChatPreferences({ isMuted: !settings.isMuted })}
                  >
                    {settings.isMuted ? 'Notificaciones' : 'Silenciar'}
                  </button>
                  <button
                    className={settings.isFavorite ? 'chip-btn active' : 'chip-btn'}
                    onClick={() => void saveChatPreferences({ isFavorite: !settings.isFavorite })}
                  >
                    {settings.isFavorite ? 'Quitar favorito' : 'Favorito'}
                  </button>
                  <button
                    className={settings.isArchived ? 'chip-btn active' : 'chip-btn'}
                    onClick={() => void saveChatPreferences({ isArchived: !settings.isArchived })}
                  >
                    {settings.isArchived ? 'Desarchivar' : 'Archivar'}
                  </button>
                </div>
              </section>

              <section className="tool-section">
                <h4>Traducción</h4>
                <div className="composer mini-composer">
                  <input
                    className="text-input tiny-input"
                    placeholder="Idioma (es, en...)"
                    value={settings.targetLang}
                    onChange={(e) => setSettings(s => ({ ...s, targetLang: e.target.value }))}
                  />
                  <button className="ghost-btn-sm" onClick={() => void saveTranslationSettings()}>OK</button>
                </div>
              </section>
            </div>

            <div className="grid-2-col">
              <section className="tool-section">
                <h4>Encuesta</h4>
                <button className="ghost-btn-sm w-full" onClick={() => setShowPollComposer((current) => !current)}>
                  {showPollComposer ? 'Cancelar' : 'Nueva encuesta'}
                </button>
                {showPollComposer && (
                  <div className="section-stack mt-2">
                    <input className="text-input tiny-input" placeholder="Pregunta" value={pollQuestion} onChange={e => setPollQuestion(e.target.value)} />
                    <button className="primary-btn-sm" onClick={() => void createChatPoll()}>
                      {creatingPoll ? 'Creando...' : 'Crear'}
                    </button>
                  </div>
                )}
              </section>

              <section className="tool-section">
                <h4>Programar</h4>
                <button className="ghost-btn-sm w-full" onClick={() => setShowScheduleComposer((current) => !current)}>
                  {showScheduleComposer ? 'Cancelar' : 'Programar mensaje'}
                </button>
                {showScheduleComposer && (
                  <div className="section-stack mt-2">
                    <input className="text-input tiny-input" type="datetime-local" value={scheduledAt} onChange={e => setScheduledAt(e.target.value)} />
                    <input className="text-input tiny-input" placeholder="Mensaje programado..." value={scheduledDraft} onChange={(e) => setScheduledDraft(e.target.value)} />
                    <button className="primary-btn-sm" onClick={() => void scheduleMessage()}>
                      {scheduling ? 'Programando...' : 'Programar'}
                    </button>
                    {scheduledJobs.length > 0 ? (
                      <div className="list-stack">
                        {scheduledJobs.slice(0, 5).map((job) => (
                          <div key={job.id} className="surface-subcard">
                            <small>
                              {job.content || 'Mensaje'} · {formatDateTime(job.scheduledFor)} · {job.status}
                            </small>
                            {job.errorMessage ? <small className="danger-line">{job.errorMessage}</small> : null}
                            <button className="ghost-btn-sm" onClick={() => void cancelScheduledJob(job.id)}>
                              Cancelar
                            </button>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <small className="muted-line">Sin mensajes programados</small>
                    )}
                  </div>
                )}
              </section>
            </div>
          </article>
        </div>
      ) : null}

      <div className="chat-thread-shell">
        <section className="chat-thread" ref={threadRef} onScroll={handleThreadScroll} onLoadCapture={keepLatestMessageVisible} onLoadedMetadataCapture={keepLatestMessageVisible}>
          {renderedMessages.length < filteredMessages.length ? <button type="button" className="ghost-btn" onClick={() => {
            const thread = threadRef.current, height = thread?.scrollHeight || 0, top = thread?.scrollTop || 0
            shouldStickToBottomRef.current = false
            setHistoryWindowSize(size => size + 80)
            window.requestAnimationFrame(() => { if (thread) thread.scrollTop = top + thread.scrollHeight - height })
          }}>Ver mensajes anteriores</button> : null}
          {filteredMessages.length === 0 && normalizedMessageSearch ? (
            <div className="chat-search-empty">
              <small className="muted-line">No hay mensajes para ese filtro.</small>
            </div>
          ) : filteredMessages.length === 0 ? (
            <div className="chat-search-empty">
              <small className="muted-line">Todavía no hay mensajes en este chat.</small>
            </div>
          ) : renderedMessages.map((message, index) => {
          const previousMessage = index > 0 ? renderedMessages[index - 1] : null
          const showDaySeparator =
            !previousMessage || dayKeyOf(previousMessage.createdAt) !== dayKeyOf(message.createdAt)
          const deletedForMe = user ? message.deletedBy.includes(user.id) : false
          if (deletedForMe) return null
          const album = albums.get(message.id)
          if (album && album.leader !== message.id) return null
          if (album) return <Fragment key={message.id}>{showDaySeparator ? <div className="chat-day-separator"><span>{formatConversationDay(message.createdAt)}</span></div> : null}<ChatMediaAlbum items={album.items} mine={message.senderId === user?.id} deliveryState={item => mineDeliveryState(item, toMillis(item.createdAt) > 0 && toMillis(otherParticipant?.lastReadAt ?? '') >= toMillis(item.createdAt))} onReply={item => setReplyToId(item.id)} onForward={item => void sendForwardedMessage(item)} onDelete={item => void deleteMessageForMe(item)} onDeleteForAll={item => void deleteMessageForAll(item)} onReact={item => void toggleReaction(item.id, '👍')} /></Fragment>

          const mine = message.senderId === user?.id
          const replied = message.replyToId ? messageById.get(message.replyToId) : null
          const pollRows =
            message.pollId && pollStatesById[message.pollId]
              ? [...pollStatesById[message.pollId]].sort((a, b) => a.displayOrder - b.displayOrder)
              : []
          const pollMeta = pollRows[0]
          const consumed = consumedViewOnce[message.id] === true
          const readByOther =
            mine &&
            toMillis(message.createdAt) > 0 &&
            toMillis(otherParticipant?.lastReadAt ?? '') >= toMillis(message.createdAt)
          const deliveryState = mine ? mineDeliveryState(message, readByOther) : null
          const showMessageText = shouldRenderMessageText(message)
          const activeReactions = ['👍', '❤️', '😂'].filter(
            (emoji) => reactionCount(message.reactions, emoji) > 0,
          )

          return (
            <Fragment key={message.id}>
              {showDaySeparator ? (
                <div className="chat-day-separator">
                  <span>{formatConversationDay(message.createdAt)}</span>
                </div>
              ) : null}
              <article className={`${mine ? 'bubble bubble-me' : 'bubble bubble-them'}${message.contentType === 'sticker' && !message.deletedForAll ? ' sticker-bubble' : ''}`}>
                {message.isForwarded ? <small>Reenviado</small> : null}
                {replied ? <div className="reply-preview">Respuesta a: {replied.content || 'Archivo'}</div> : null}

                {message.deletedForAll ? (
                  <p className="muted-line">Mensaje eliminado para todos</p>
                ) : sharedVideoReference(message.content) ? (
                  <SharedVideoCard reference={sharedVideoReference(message.content)!} />
                ) : message.contentType === 'poll' && pollRows.length > 0 ? (
                  <div className="poll-stack">
                    <strong>{pollMeta?.question || message.content}</strong>
                    {pollRows.map((option) => (
                      <button
                        key={option.optionId}
                        type="button"
                        className={option.votedByMe ? 'poll-option active' : 'poll-option'}
                        disabled={option.isClosed}
                        onClick={() =>
                          void votePollOption(message.pollId ?? '', option.optionId, !option.votedByMe)
                        }
                      >
                        <span>{option.optionText}</span>
                        <span>{option.voteCount}</span>
                      </button>
                    ))}
                    <small>
                      {pollMeta?.totalVotes ?? 0} votos · {pollMeta?.allowsMultiple ? 'Multiple' : 'Unica'}
                    </small>
                  </div>
                ) : (
                  <>
                    {message.contentType === 'location' ? (
                      <>
                        {message.content ? <p>{message.content}</p> : null}
                        {message.mediaUrl ? (
                          <a className="inline-link" href={message.mediaUrl} target="_blank" rel="noreferrer">
                            Abrir ubicacion en mapa
                          </a>
                        ) : null}
                      </>
                    ) : message.contentType === 'contact' ? (
                      <p>Contacto: {message.content}</p>
                    ) : (
                      <>
                        {message.mediaUrl && message.contentType !== 'sticker' && !isViewOnceType(message.contentType) ? (
                          <ChatMessageMedia message={message} />
                        ) : null}
                        {showMessageText ? (
                          <p className={message.mediaUrl ? 'message-caption' : undefined}>{message.content}</p>
                        ) : null}
                      </>
                    )}
                    {message.mediaUrl && message.contentType === 'sticker' ? (
                      <ChatAttachmentMedia url={message.mediaUrl} kind="image" title={message.content || 'Sticker'} imageClassName="sticker-preview" imageLink={false} />
                    ) : null}
                    {message.mediaUrl && isViewOnceType(message.contentType) ? (
                      consumed ? (
                        <small>Contenido view-once ya consumido.</small>
                      ) : (
                        <button className="ghost-btn" type="button" onClick={() => void consumeViewOnce(message)}>
                          Ver una vez
                        </button>
                      )
                    ) : null}
                  </>
                )}

                {activeReactions.length > 0 ? (
                  <div className="reaction-row">
                  {activeReactions.map((emoji) => (
                    <button
                      key={emoji}
                      type="button"
                      className="reaction-chip"
                      onClick={() => void toggleReaction(message.id, emoji)}
                    >
                      {emoji} {reactionCount(message.reactions, emoji)}
                    </button>
                  ))}
                  </div>
                ) : null}

                {!message.deletedForAll ? (
                  <details className={mine ? 'message-menu message-menu-mine' : 'message-menu'}>
                    <summary title="Opciones del mensaje" aria-label="Opciones del mensaje">
                      <MoreHorizontal aria-hidden="true" />
                    </summary>
                    <div className="message-menu-popover">
                    {['👍', '❤️', '😂'].map((emoji) => (
                      <button key={emoji} className="mini-action-btn reaction-action" type="button" aria-label={`Reaccionar con ${emoji}`} onClick={() => void toggleReaction(message.id, emoji)}>
                        {emoji}
                      </button>
                    ))}
                    <button className="mini-action-btn" type="button" onClick={() => setReplyToId(message.id)}>
                      Responder
                    </button>
                    <button className="mini-action-btn" type="button" onClick={() => void sendForwardedMessage(message)}>
                      Reenviar
                    </button>
                    {mine && message.contentType === 'text' ? (
                      <button
                        className="mini-action-btn"
                        type="button"
                        onClick={() => {
                          setEditingMessageId(message.id)
                          setEditingDraft(message.content)
                        }}
                      >
                        Editar
                      </button>
                    ) : null}
                    {mine ? (
                      <button className="mini-action-btn danger" type="button" onClick={() => void deleteMessageForAll(message)}>
                        Borrar todos
                      </button>
                    ) : null}
                    <button className="mini-action-btn" type="button" onClick={() => void deleteMessageForMe(message)}>
                      Borrar mi vista
                    </button>
                    </div>
                  </details>
                ) : null}

                <small className="message-meta">
                      {new Date(message.createdAt).toLocaleTimeString("es", {
                        hour: "2-digit",
                        minute: "2-digit",
                      })}
                      {message.isEdited ? " · editado" : ""}
                      {mine ? (
                        <span
                          className={`message-status ${deliveryState}`}
                          aria-label={
                            deliveryState === "pending"
                              ? "Pendiente"
                              : deliveryState === "failed"
                                ? "Error al enviar"
                                : deliveryState === "read"
                                  ? "Leído"
                                  : deliveryState === "delivered"
                                    ? "Entregado"
                                    : "Enviado"
                          }
                          title={
                            readByOther
                              ? `Leído por ${otherParticipant?.name || "contacto"}`
                              : undefined
                          }
                        >
                          {deliveryState === "pending" ? (
                            "◷"
                          ) : deliveryState === "failed" ? (
                            <button
                              className="mini-action-btn"
                              type="button"
                              onClick={() =>
                                void retryPendingWebMessage(message.id)
                              }
                            >
                              Reintentar
                            </button>
                          ) : deliveryState === "sent" ? (
                            "✓"
                          ) : (
                            "✓✓"
                          )}
                        </span>
                      ) : null}
                    </small>
              </article>
            </Fragment>
          )
        })}
          <div className="chat-thread-bottom-anchor" />
        </section>
        {showScrollToBottom ? (
          <button className="chat-scroll-bottom-btn" type="button" aria-label="Ir al último mensaje" title="Ir al último mensaje" onClick={() => scrollThreadToBottom('smooth')}>
            ↓
          </button>
        ) : null}
      </div>

      {replyToMessage ? (
        <div className="surface-subcard row-between">
          <small>Respondiendo a: {replyToMessage.content || 'Archivo'}</small>
          <button className="mini-action-btn" type="button" onClick={() => setReplyToId(null)}>
            Quitar
          </button>
        </div>
      ) : null}

      {editingMessageId ? (
        <div className="surface-subcard row-between">
          <small>Editando mensaje</small>
          <button
            className="mini-action-btn"
            type="button"
            onClick={() => {
              setEditingMessageId(null)
              setEditingDraft('')
            }}
          >
            Cancelar
          </button>
        </div>
      ) : null}

      {showStickers ? <StickerPicker onSelect={item => void sendSticker(item)} onImport={file => void importSticker(file)} onClose={() => setShowStickers(false)} /> : null}

      {pendingAttachments.length > 0 ? (
        <section className="attachment-draft-tray fade-in">
          <div className="row-between">
            <strong>Adjuntos ({pendingAttachments.length})</strong>
            <small className="muted-line">Se enviarán juntos al tocar enviar</small>
            <button className="mini-action-btn" type="button" onClick={clearPendingAttachments}>
              Limpiar
            </button>
          </div>
          <div className="attachment-draft-grid">
            {pendingAttachments.map((attachment) => (
              <article key={attachment.id} className="attachment-draft-item">
                {attachment.previewUrl ? (
                  attachment.contentType === 'video' ? (
                    <StreamingVideo className="attachment-preview" src={attachment.previewUrl} controls preload="metadata" />
                  ) : (
                    <StreamingImage className="attachment-preview" src={attachment.previewUrl} alt={attachment.file.name} />
                  )
                ) : (
                  <div className="attachment-preview attachment-preview-fallback">
                    <span>{attachment.contentType === 'audio' ? 'Audio' : 'Archivo'}</span>
                  </div>
                )}
                <div className="attachment-draft-body">
                  <div className="attachment-draft-meta">
                    <strong>{attachment.file.name}</strong>
                    <small>{formatFileSize(attachment.file.size)}</small>
                  </div>
                  <input
                    className="text-input tiny-input"
                    placeholder="Texto opcional para este archivo"
                    value={attachment.caption}
                    onChange={(event) =>
                      updatePendingAttachmentCaption(attachment.id, event.target.value)
                    }
                  />
                </div>
                <button
                  className="mini-action-btn attachment-remove-btn"
                  type="button"
                  onClick={() => removePendingAttachment(attachment.id)}
                >
                  Quitar
                </button>
              </article>
            ))}
          </div>
        </section>
      ) : null}

      <form className="composer chat-composer shadow-lg" onSubmit={handleSend}>
        <button
          className="icon-btn"
          type="button"
          onClick={() => setShowStickers((c) => !c)}
          title="Stickers"
        >
          <svg viewBox="0 0 24 24" width="24" height="24"><path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm3.5-9c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zm-7 0c.83 0 1.5-.67 1.5-1.5S9.33 8 8.5 8 7 8.67 7 9.5 7.67 11 8.5 11zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z" fill="currentColor" /></svg>
        </button>

        <label className="icon-btn clickable" role="button" tabIndex={0} aria-label="Adjuntar archivos" title="Adjuntar archivos"
          onKeyDown={event => { if(event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.currentTarget.querySelector<HTMLInputElement>('input')?.click() } }}>
            <svg viewBox="0 0 24 24" width="24" height="24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor" /></svg>
          <input
            type="file"
            multiple
            hidden
            onChange={(event) => {
              const files = event.target.files
              handleAttachFile(files)
              event.currentTarget.value = ''
            }}
          />
        </label>

        <button
          className={isRecordingAudio ? 'icon-btn recording' : 'icon-btn'}
          type="button"
          title={isRecordingAudio ? 'Detener grabación' : 'Grabar audio'}
          onClick={() => void toggleAudioRecording()}
        >
          {isRecordingAudio ? (
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
              <rect x="7" y="7" width="10" height="10" rx="2.2" fill="currentColor" />
            </svg>
          ) : (
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
              <path d="M12 3a3 3 0 00-3 3v6a3 3 0 006 0V6a3 3 0 00-3-3zm5 9a1 1 0 112 0 7 7 0 01-6 6.93V21h3a1 1 0 110 2H8a1 1 0 110-2h3v-2.07A7 7 0 015 12a1 1 0 112 0 5 5 0 0010 0z" fill="currentColor" />
            </svg>
          )}
        </button>

        <input
          className="text-input chat-input-field"
          placeholder={editingMessageId ? 'Edita tu mensaje...' : 'Escribe un mensaje...'}
          value={composerText}
          onChange={(event) => {
            if (editingMessageId) setEditingDraft(event.target.value)
            else {
              draftRevisionRef.current += 1
              setDraft(event.target.value)
            }
          }}
        />

        <button className="primary-btn circle-btn" type="submit" aria-label="Enviar mensaje" disabled={sending || uploading}>
          {sending || uploading ? (
            '...'
          ) : (
            <svg viewBox="0 0 24 24" width="24" height="24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="currentColor" /></svg>
          )}
        </button>
      </form>
      {viewOnceLease ? <ViewOnceMediaDialog lease={viewOnceLease} onClose={() => setViewOnceLease(null)} /> : null}
    </section>
  )
}
