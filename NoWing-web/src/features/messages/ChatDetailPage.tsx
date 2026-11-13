import { ArrowLeft, Check, Files, LoaderCircle, Mic, MoreVertical, Paperclip, Phone, Search, Send, Square, Sticker, Video, X } from 'lucide-react'
import { animatedImageFile } from '../../core/imageEditPolicy'
import { messageDeliveryFeedback } from '../../core/outboxFailureMessage'
import { MessageTimestamp } from '../../components/MessageTimestamp'
import { ContactMessageCard } from '../../components/ContactMessageCard'
import { DirectMessageRequestNotice } from '../../components/DirectMessageRequestNotice'
import { messageLocationUrl } from '../../core/messageLocation'
import { shouldReplyToMessageDoubleClick } from '../../core/chatMessageGestures'
import { readCachedValue } from '../../core/localCache'
import { ForwardMessagesDialog } from '../../components/ForwardMessagesDialog'
import { copyChatMessage } from '../../core/media/copyChatMessage'
import { clipboardFilesFrom, clipboardMayContainImage, clipboardHasStickerHint, readClipboardImages, canPasteIntoChat, pastedAsSticker, isCopiedStickerImage, isEmptyClipboardPaste, normalizeClipboardFiles, isStickerImage } from '../../core/chatClipboard'
import { ProfileStatusBadges } from '../../components/ProfileStatusBadges'
import { newerMessage, mergeMessageSnapshot, mergeOlderMessagePage, mergeLatestMessagePage } from '../../core/conversationMessageMerge'
import {MessageOptionsMenu,MessageReactionBar} from '../../components/MessageOptionsMenu'
import {confirmInboxConversationRead,markInboxConversationRead} from '../../core/inboxActivity'
import { BirthdayComposer, BirthdayLetter } from '../../components/BirthdayLetter'
import { useSearchParams } from 'react-router-dom'
import { FolderAttachmentPicker } from '../../components/FolderAttachmentPicker'
import { MessageSticker } from '../../components/MessageSticker'
import { useConversationDraft } from '../../core/conversationDrafts'
import { MessageText } from '../../components/MessageText'
import { MessageReplyPreview } from '../../components/MessageReplyPreview'
import { ChatProfilePanel } from '../../components/ChatProfilePanel'
import { droppedAttachments, prepareAttachmentFiles } from '../../core/attachmentPreparation'
import { attachmentKind } from '../../core/attachmentKind'
import { useAttachmentDraft, useAttachmentPreparation } from '../../core/attachmentJobs'
import { chatGallery } from '../../core/chatGallery'
import { MediaViewer } from '../../components/MediaViewer'
import { ChatImageEditor } from '../../components/ChatImageEditor'
import { ChatAttachmentPreview } from '../../components/ChatAttachmentPreview'
import { SpellingSuggestions } from '../../components/SpellingSuggestions'
import { RecordingIndicator } from '../../components/RecordingIndicator'
import { backendRpc } from '../../core/backendRpc'
import { chatMediaAlbums } from '../../core/chatMediaAlbums'
import { ChatMediaAlbum } from '../../components/ChatMediaAlbum'
import { SafeAvatar } from '../../components/SafeAvatar'
import { conversationWindow } from '../../core/conversationWindow'
import { captureConversationViewportAnchor, restoreConversationViewportAnchor } from '../../core/conversationViewport'
import { sharedVideoReference } from '../../core/sharedVideoReference'
import { SharedVideoCard } from '../../components/SharedVideoCard'
import { cancelPendingWebMessage, retryPendingWebMessage } from '../../core/webOutbox'
import {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type DragEvent,
  type FormEvent,
} from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ChatOptionsMenu } from '../../components/ChatOptionsMenu'
import { MediaDownloadButton } from '../../components/MediaDownloadButton'
import { StatusView } from '../../components/StatusView'
import {
  deleteFallbackChatMessage,
  editFallbackChatMessage,
  loadFallbackChat,
  loadFallbackHistoryPage,
  fallbackHistoryAllows,
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
import { useConversationRecovery } from './useConversationRecovery'
import { useChatEntryFocus } from './useChatEntryFocus'
import {
  formatDateTime,
  friendlyError,
  isMissingTableError,
  textOf,
  type GenericRow,
} from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { getStorageReadIdentity, subscribeStorageReadIdentity } from '../../core/media/storageReadSession'
import { ViewOnceMediaDialog } from '../../components/ViewOnceMediaDialog'
import { requestViewOnceMedia } from '../../core/media/viewOnceClient'
import type { ViewOnceLease } from '../../core/media/viewOnceLease'
import { ChatAttachmentMedia } from '../../components/ChatAttachmentMedia'
import { OutboxTransferStatus } from '../../components/OutboxTransferStatus'
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
  deliveryError?: string
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

const initialChatSettings: ChatSettings = {
  isMuted: false, isPinned: false, isArchived: false, isFavorite: false,
  translateEnabled: false, targetLang: 'es',
}

const CONVERSATION_PAGE_SIZE = 80

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
  publicName?: string
  nickname?: string
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
  contentType: 'image' | 'video' | 'audio' | 'file' | 'sticker'
  previewUrl: string
  caption: string
}

function inferContentType(file: File): 'image' | 'video' | 'audio' | 'file' {
  if (file.type.startsWith('image/')) return 'image'
  if (file.type.startsWith('video/')) return 'video'
  if (file.type.startsWith('audio/')) return 'audio'
  return 'file'
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
  if (file.type.startsWith('image/') || file.type.startsWith('video/') || file.type.startsWith('audio/')) {
    return URL.createObjectURL(file)
  }
  return ''
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

function ChatMessageMedia({ message, onImageOpen, overlay }: { message: ChatMessage; onImageOpen: () => void; overlay?: import('react').ReactNode }) {
  if (!message.mediaUrl) return null
  return <ChatAttachmentMedia url={message.mediaUrl} kind={attachmentKind(message.contentType, message.mediaUrl)} inlineDownload={false} title={message.content.trim() || fileNameFromUrl(message.mediaUrl)} size={message.mediaSize} onImageOpen={onImageOpen} overlay={overlay} />
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
    deliveryError: textOf(row, ['delivery_error'], ''),
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




function FallbackAvatar({ url, name, className }: { url: string; name: string; className: string }) {
  return <SafeAvatar url={url} name={name} className={className} size="sm" />
}

type ChatDetailPageProps = {
  focusRequest?: number
  chatIdOverride?: string
  embedded?: boolean
  onCloseChat?:()=>void
}

export function ChatDetailPage({ chatIdOverride, embedded = false, onCloseChat, focusRequest = 0 }: ChatDetailPageProps = {}) {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const { chatId: routeChatId = '' } = useParams()
  const location = useLocation()
  const chatId = chatIdOverride ?? routeChatId
  const notificationParticipant = useMemo(() => {
    const routeIdentity = chatId.trim().toLowerCase() === routeChatId.trim().toLowerCase()
      ? (location.state as { notificationParticipant?: { id?: unknown; name?: unknown; avatarUrl?: unknown } } | null)?.notificationParticipant : undefined
    const cachedInbox = user?.id ? readCachedValue<Array<{ id: string; profileId: string | null; title: string; avatarUrl: string }>>(`messages:${user.id}:chats`, 8 * 60 * 60 * 1000) : null
    const cached = Array.isArray(cachedInbox) ? cachedInbox.find(item => item.id === chatId.trim().toLowerCase() && item.profileId && item.profileId !== user?.id) : undefined
    if (cached && cached.title?.trim() && !['usuario', 'alguien', 'novvin'].includes(cached.title.trim().toLowerCase())) {
      return { id: cached.profileId!, name: cached.title.trim(), avatarUrl: cached.avatarUrl || '', lastReadAt: '' }
    }
    return routeIdentity && typeof routeIdentity.id === 'string' && routeIdentity.id &&
      routeIdentity.id !== user?.id && typeof routeIdentity.name === 'string' && routeIdentity.name.trim() &&
      !['usuario', 'alguien', 'novvin'].includes(routeIdentity.name.trim().toLowerCase())
      ? { id: routeIdentity.id, name: routeIdentity.name.trim(), publicName: routeIdentity.name.trim(), avatarUrl: typeof routeIdentity.avatarUrl === 'string' ? routeIdentity.avatarUrl : '', lastReadAt: '' }
      : null
  }, [chatId, routeChatId, location.state, user?.id])
  const [birthdayParams, setBirthdayParams] = useSearchParams()
  const showBirthday = birthdayParams.get('birthdayGreeting') === '1'
  const closeBirthday = () => { const next = new URLSearchParams(birthdayParams); next.delete('birthdayGreeting'); setBirthdayParams(next, { replace: true }) }
  const [loading, setLoading] = useState(true)
  const [hasOlderMessages, setHasOlderMessages] = useState(false)
  const [loadingOlderMessages, setLoadingOlderMessages] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [title, setTitle] = useState(notificationParticipant?.name || 'Chat')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const messagesSnapshotRef = useRef(messages)
  messagesSnapshotRef.current = messages
  const [draft, setDraft] = useConversationDraft(user?.id, 'chat', chatId.trim().toLowerCase())
  const draftRevisionRef = useRef(0)
  const [submittedText, setSubmittedText] = useState<{owner:string; revision:number; value:string}|null>(null)
  const sendInFlightRef = useRef(false)
  const [sending, setSending] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [isRecordingAudio, setIsRecordingAudio] = useState(false)
  const [dragActive, setDragActive] = useState(false)
  const [pendingAttachments, setPendingAttachments] = useAttachmentDraft<PendingAttachment>(user?.id || '', 'chat', chatId.trim().toLowerCase())
  const [imageEditorFile, setImageEditorFile] = useState<File | null>(null)
  const [spellingCaret, setSpellingCaret] = useState<number|undefined>()
  const [galleryMessageId, setGalleryMessageId] = useState<string | null>(null)
  const [profilePanelOpen, setProfilePanelOpen] = useState(false)
  const discardRecordingRef = useRef(false)
  const preparationOwnerRef = useRef('')

  const [showMessageSearch, setShowMessageSearch] = useState(false)
  const [messageSearchQuery, setMessageSearchQuery] = useState('')
  const [showQuickShare, setShowQuickShare] = useState(false)
  const [showAdvancedTools, setShowAdvancedTools] = useState(false)
  const [selectingMessages, setSelectingMessages] = useState(false)
  const [selectedMessageIds, setSelectedMessageIds] = useState<Set<string>>(new Set())
  const [selectionBusy, setSelectionBusy] = useState(false)
  const navigate = useNavigate()
  function toggleSelectedMessage(id: string) {
    setSelectedMessageIds(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next })
  }
  const [replyToId, setReplyToId] = useState<string | null>(null)
  const [forwardMessages, setForwardMessages] = useState<ChatMessage[]>([])
  const [replyJumpId, setReplyJumpId] = useState<string | null>(null)
  useMessageFocusFromUrl(messages.length > 0, setReplyJumpId)

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
  const [otherParticipant, setOtherParticipant] = useState<OtherParticipantState | null>(notificationParticipant)
  const [nicknameDialogOpen, setNicknameDialogOpen] = useState(false)
  const [nicknameDraft, setNicknameDraft] = useState('')
  const [savingNickname, setSavingNickname] = useState(false)
  const otherParticipantRef = useRef<OtherParticipantState | null>(notificationParticipant)
  const [settings, setSettings] = useState<ChatSettings>(initialChatSettings)
  const [savingSettings, setSavingSettings] = useState(false)
  const [showScrollToBottom, setShowScrollToBottom] = useState(false)
  const threadRef = useRef<HTMLElement | null>(null)
  const loadingOlderMessagesRef = useRef(false)
  const historyCursorRef = useRef<string | null>(null)
  const expandedHistoryRef = useRef(false)
  const historyGenerationRef = useRef(0)
  const loadOlderMessagesRef = useRef<() => void>(() => {})
  const messageSearchInputRef = useRef<HTMLInputElement | null>(null)
  const composerInputRef = useRef<HTMLInputElement | null>(null)
  const beginReply = useCallback((messageId: string) => {
    setEditingMessageId(null)
    setEditingDraft('')
    setReplyToId(messageId)
    composerInputRef.current?.focus({ preventScroll: true })
  }, [])
  const shouldStickToBottomRef = useRef(true)
  const forceScrollToBottomRef = useRef(true)
  const pendingInitialBottomSnapRef = useRef(true)
  const audioRecorderRef = useRef<MediaRecorder | null>(null)
  const audioStreamRef = useRef<MediaStream | null>(null)
  const audioChunksRef = useRef<Blob[]>([])
  const loadSequenceRef = useRef(0)

  const cleanChatId = useMemo(() => chatId.trim().toLowerCase(), [chatId])
  const scheduleIdentity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const scheduleContext = useRef(0)
  useEffect(() => {
    setScheduledDraft('')
    setScheduledAt('')
    setShowScheduleComposer(false)
    setScheduling(false)
    setScheduledJobs([])
    scheduleRequestKeyRef.current = null
    return () => { scheduleContext.current += 1 }
  }, [user?.id, useUbuntu, cleanChatId, scheduleIdentity])
  const pollContext = useRef(0)
  useEffect(() => {
    setPollQuestion('')
    setPollOptions('Si\nNo')
    setPollAllowsMultiple(false)
    setShowPollComposer(false)
    setCreatingPoll(false)
    return () => { pollContext.current += 1 }
  }, [user?.id, useUbuntu, cleanChatId, scheduleIdentity])
  const deletionContext = useRef(0)
  useEffect(() => {
    setSelectionBusy(false)
    setSelectedMessageIds(new Set())
    setSelectingMessages(false)
    return () => { deletionContext.current += 1 }
  }, [user?.id, useUbuntu, cleanChatId, scheduleIdentity])
  const reactionContext = useRef(0)
  useEffect(() => () => { reactionContext.current += 1 }, [user?.id, useUbuntu, cleanChatId])
  const nicknameContext = useRef(0)
  useEffect(() => {
    setNicknameDialogOpen(false)
    setNicknameDraft('')
    setSavingNickname(false)
    return () => { nicknameContext.current += 1 }
  }, [user?.id, useUbuntu, cleanChatId, scheduleIdentity])
  const preferenceContext = useRef(0)
  useEffect(() => {
    setSavingSettings(false)
    return () => { preferenceContext.current += 1 }
  }, [user?.id, useUbuntu, cleanChatId])
  useChatEntryFocus(composerInputRef, user?.id ? `${user.id}/chat/${cleanChatId}` : '', !loading || Boolean(otherParticipant), focusRequest)
  useEffect(() => { otherParticipantRef.current = otherParticipant }, [otherParticipant])
  useEffect(() => {
    loadSequenceRef.current += 1
    historyGenerationRef.current += 1
    loadingOlderMessagesRef.current = false
    historyCursorRef.current = null
    expandedHistoryRef.current = false
    setHasOlderMessages(false)
    setLoadingOlderMessages(false)
    setPollStatesById({})
    setConsumedViewOnce({})
  }, [cleanChatId, user?.id])
  useEffect(() => {
    setTitle(notificationParticipant?.name || 'Chat')
    setOtherParticipant(notificationParticipant)
    setNicknameDialogOpen(false)
  }, [cleanChatId, notificationParticipant])
  useEffect(() => { setSelectedMessageIds(new Set()); setSelectingMessages(false); setShowQuickShare(false); setShowAdvancedTools(false) }, [cleanChatId])
  preparationOwnerRef.current = `${user?.id}/${cleanChatId}`
  const messageById = useMemo(() => new Map(messages.map((message) => [message.id, message])), [messages])
  useEffect(() => {
    if (!replyJumpId) return
    setMessageSearchQuery('')
    setHistoryWindowSize(size => Math.max(size, messages.length))
    const frame = window.requestAnimationFrame(() => window.requestAnimationFrame(() => {
      const node = threadRef.current?.querySelector<HTMLElement>(`#${CSS.escape(`message-${replyJumpId}`)}`)
      if (node) {
        shouldStickToBottomRef.current = false
        node.scrollIntoView({ block: 'center', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
        node.animate([{ boxShadow: '0 0 0 3px #27c6c9', backgroundColor: '#245356' }, { boxShadow: '0 0 0 0 transparent' }], { duration: 1500 })
      } else setError('El mensaje original no está en el historial disponible.')
    }))
    const timer = window.setTimeout(() => setReplyJumpId(null), 1800)
    return () => { window.cancelAnimationFrame(frame); window.clearTimeout(timer) }
  }, [replyJumpId, messages.length])

  const normalizedMessageSearch = useMemo(() => messageSearchQuery.trim().toLowerCase(), [messageSearchQuery])
  const filteredMessages = useMemo(() => {
    if (!normalizedMessageSearch) return messages
    return messages.filter((message) => {
      const haystack = `${message.content} ${message.contentType} ${message.mediaUrl}`.toLowerCase()
      return haystack.includes(normalizedMessageSearch)
    })
  }, [messages, normalizedMessageSearch])
  const [historyWindowSize, setHistoryWindowSize] = useState(CONVERSATION_PAGE_SIZE)
  useEffect(() => setHistoryWindowSize(CONVERSATION_PAGE_SIZE), [cleanChatId])
  const renderedMessages = useMemo(() => conversationWindow(filteredMessages, historyWindowSize, Boolean(normalizedMessageSearch)), [filteredMessages, historyWindowSize, normalizedMessageSearch])
  const albums = useMemo(() => chatMediaAlbums(renderedMessages, user?.id || ''), [renderedMessages, user?.id])
  const gallery = useMemo(() => chatGallery(messages, user?.id || ''), [messages, user?.id])
  const galleryIndex = galleryMessageId ? gallery.findIndex(item => item.id === galleryMessageId) : null
  const openGallery = (message: { id: string }) => setGalleryMessageId(message.id)
  const replyToMessage = replyToId ? messageById.get(replyToId) ?? null : null
  const visibleDraft = submittedText?.owner === preparationOwnerRef.current &&
    submittedText.revision === draftRevisionRef.current && submittedText.value === draft ? '' : draft
  const composerText = editingMessageId ? editingDraft : visibleDraft
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
      next[index] = newerMessage(current[index], nextMessage)
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
    if (thread.scrollTop < 96 && hasOlderMessages && !loadingOlderMessagesRef.current) {
      loadOlderMessagesRef.current()
    }
  }, [hasOlderMessages, messages.length])
  const keepLatestMessageVisible = useCallback(() => {
    if (shouldStickToBottomRef.current) scrollThreadToBottom('auto')
  }, [scrollThreadToBottom])
  useEffect(() => {
    window.addEventListener('resize', keepLatestMessageVisible)
    return () => window.removeEventListener('resize', keepLatestMessageVisible)
  }, [keepLatestMessageVisible])

  const queueAttachmentFiles = useCallback((input: FileList | File[] | null, pasted = false, stickerIntent = false) => {
    if (!input || input.length === 0) return
    const files = Array.from(input).filter((entry) => entry.size > 0)
    if (files.length === 0) return
    setPendingAttachments((current) => [
      ...current,
      ...files.map((file) => ({
        id: crypto.randomUUID(),
        file,
        contentType: pasted && pastedAsSticker(file, stickerIntent) ? 'sticker' as const : inferContentType(file),
        previewUrl: previewUrlForFile(file),
        caption: '',
      })),
    ])
  }, [setPendingAttachments])
  const { start: startPreparation, cancel: cancelPreparation, preparing: preparingAttachments, status: preparationStatus } = useAttachmentPreparation(user?.id || '', 'chat', cleanChatId, queueAttachmentFiles, setError)

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
      discardRecordingRef.current = false
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
        if (discardRecordingRef.current) { discardRecordingRef.current = false; return }
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
  }, [setPendingAttachments])

  const removePendingAttachment = useCallback((attachmentId: string) => {
    setPendingAttachments((current) => {
      const target = current.find((entry) => entry.id === attachmentId)
      if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl)
      return current.filter((entry) => entry.id !== attachmentId)
    })
  }, [setPendingAttachments])

  const clearPendingAttachments = useCallback(() => {
    setPendingAttachments((current) => {
      current.forEach((entry) => {
        if (entry.previewUrl) URL.revokeObjectURL(entry.previewUrl)
      })
      return []
    })
  }, [setPendingAttachments])

  useEffect(() => {
    setImageEditorFile(null); setGalleryMessageId(null)
    discardRecordingRef.current = true
    const recorder = audioRecorderRef.current
    if (recorder) { recorder.onstop = null; if (recorder.state !== 'inactive') recorder.stop(); audioRecorderRef.current = null }
    stopAudioStream(); setIsRecordingAudio(false)
  }, [user?.id, cleanChatId, stopAudioStream])

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
      setError(null)
      startPreparation(progress => droppedAttachments(event.dataTransfer, progress))
    },
    [startPreparation],
  )

  useEffect(() => {
    if (editingMessageId) return
    const paste = (event: ClipboardEvent) => {
      if (event.defaultPrevented || !canPasteIntoChat(event.target)) return
      const files = clipboardFilesFrom(event.clipboardData)
      const hasAttachments = files.length > 0 || clipboardMayContainImage(event.clipboardData) || isEmptyClipboardPaste(event.clipboardData)
      const text = event.clipboardData?.getData('text/plain') || ''
      const textField = event.target instanceof Element ? event.target.closest('input,textarea,[contenteditable="true"]') : null
      // Let the browser insert ordinary text into the active field so its native
      // selection, composition and undo history are preserved.
      if (!hasAttachments && (textField || !text)) return
      if (text && (!textField || textField === composerInputRef.current)) {
        const input = composerInputRef.current
        const start = textField ? input?.selectionStart : null
        const end = textField ? input?.selectionEnd : null
        draftRevisionRef.current += 1
        setDraft(current => current.slice(0, start ?? current.length) + text + current.slice(end ?? current.length))
        if (!hasAttachments) input?.focus({ preventScroll: true })
      }
      if (!hasAttachments) { event.preventDefault(); return }
      const stickerHint = clipboardHasStickerHint(event.clipboardData)
      const pasteOwner = preparationOwnerRef.current
      event.preventDefault()
      void (async () => {
        const pastedFiles = await readClipboardImages(event.clipboardData, navigator.clipboard)
        if (pasteOwner !== preparationOwnerRef.current) return
        if (!pastedFiles.length) {
          setError('El navegador no entregó la imagen o el archivo copiado. Si viene de WhatsApp para Windows, usa Adjuntar o Importar con el archivo original del sticker; conservaremos su movimiento.')
          return
        }
        for (const file of pastedFiles) {
          const sticker = pastedAsSticker(file, showStickers) || await isCopiedStickerImage(file, stickerHint)
          if (pasteOwner !== preparationOwnerRef.current) return
          queueAttachmentFiles([file], true, sticker)
        }
      })()
    }
    window.addEventListener('paste', paste)
    return () => window.removeEventListener('paste', paste)
  }, [editingMessageId, queueAttachmentFiles, showStickers, setDraft])

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
      return
    }

    const entries = await Promise.all(
      pollIds.map(async (pollId) => {
        const { data, error: pollError } = await backendRpc('secure_get_poll_state', { p_poll_id: pollId })
        if (pollError) return [pollId, [] as PollState[]] as const
        return [pollId, ((data ?? []) as GenericRow[]).map(mapPollState)] as const
      }),
    )

    setPollStatesById(current => ({ ...current, ...Object.fromEntries(entries) }))
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
      setConsumedViewOnce(current => ({ ...current, ...consumed }))
    },
    [user],
  )

  const refreshScheduledJobs = useCallback(async () => {
    if (!user || !cleanChatId) return
    const context = scheduleContext.current
    const identity = scheduleIdentity
    const isCurrent = () => context === scheduleContext.current && identity === getStorageReadIdentity()
    try {
      let rows: GenericRow[] = []
      if (useUbuntu) {
        rows = await fallbackApiJson<GenericRow[]>('/rest/v1/rpc/secure_get_chat_scheduled_jobs', {
          method: 'POST',
          body: JSON.stringify({ p_scope_type: 'dm', p_target_id: cleanChatId }),
          expectedIdentity: identity, expectedAccountId: user.id,
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
      if (!isCurrent()) return
      setScheduledJobs(rows.map((row) => ({
        id: textOf(row, ['id'], ''),
        content: textOf(row, ['content'], ''),
        scheduledFor: textOf(row, ['scheduled_for', 'scheduledFor'], ''),
        status: textOf(row, ['status'], 'pending'),
        errorMessage: textOf(row, ['error_message', 'errorMessage'], ''),
      })).filter((job) => job.id))
    } catch (caught) {
      if (isCurrent()) console.error('Error loading scheduled jobs:', caught)
    }
  }, [cleanChatId, useUbuntu, user, scheduleIdentity])


  const markAsDeliveredAndRead = useCallback(async () => {
    if (!user || !cleanChatId) return
    const readToken=markInboxConversationRead(user.id,'chat',cleanChatId)
    if (useUbuntu) {
      try {
        const result=await markFallbackChatRead(cleanChatId)
        confirmInboxConversationRead(user.id,'chat',cleanChatId,readToken,result.lastReadAt)
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
      confirmInboxConversationRead(user.id,'chat',cleanChatId,readToken)
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
    const idsAtRequestStart = new Set(messagesSnapshotRef.current.map(message => message.id))
    const isStaleLoad = () => loadSequenceRef.current !== loadSequence
    if (!user || !cleanChatId) {
      if (!isStaleLoad()) {
        setLoading(false)
      }
      return
    }

    if (!options?.silent) setLoading(true)
    setLoadError(null)
    try {
      if (useUbuntu) {
        const response = await loadFallbackChat(cleanChatId, options?.silent ? undefined : cached => {
          if (isStaleLoad()) return
          for (const message of cached.messages) idsAtRequestStart.add(String(message.id))
          const cachedNickname = textOf(cached.chat ?? {}, ['nickname'], '').trim()
          const cachedPublicName = cached.otherParticipant?.name || ''
          const cachedName = cachedNickname || cachedPublicName || cached.title || 'Chat privado'
          const previous = cached.otherParticipant && otherParticipantRef.current?.id === cached.otherParticipant.id ? otherParticipantRef.current : null
          setTitle(cachedName)
          setOtherParticipant(cached.otherParticipant ? { ...cached.otherParticipant, name: cachedName, avatarUrl: cached.otherParticipant.avatarUrl || previous?.avatarUrl || '', publicName: cachedPublicName || undefined, nickname: cachedNickname || undefined } : null)
          setMessages(current => mergeMessageSnapshot(current, cached.messages.map(mapMessage)))
          historyCursorRef.current = cached.historyPage?.nextCursor || null
          setHasOlderMessages(Boolean(cached.historyPage?.hasMore))
          if (cached.settings) setSettings(cached.settings)
          setLoading(false)
        }, CONVERSATION_PAGE_SIZE)
        if (isStaleLoad()) return
        const responseNickname = textOf(response.chat ?? {}, ['nickname'], '').trim()
        const responsePublicName = response.otherParticipant?.name || ''
        const responseName = responseNickname || responsePublicName || response.title || 'Chat privado'
        const previous = response.otherParticipant && otherParticipantRef.current?.id === response.otherParticipant.id ? otherParticipantRef.current : null
        setTitle(responseName)
        setOtherParticipant(response.otherParticipant ? { ...response.otherParticipant, name: responseName, avatarUrl: response.otherParticipant.avatarUrl || previous?.avatarUrl || '', publicName: responsePublicName || undefined, nickname: responseNickname || undefined } : null)
        const mappedMessages = response.messages.map(mapMessage)
        if (!expandedHistoryRef.current) {
          historyCursorRef.current = response.historyPage?.nextCursor || null
          setHasOlderMessages(Boolean(response.historyPage?.hasMore))
        }
        setMessages(current => options?.silent || expandedHistoryRef.current
          ? mergeLatestMessagePage(current, mappedMessages, Boolean(response.historyPage?.hasMore), idsAtRequestStart)
              .filter(message => ['pending', 'failed'].includes(message.status) || fallbackHistoryAllows(response, message.createdAt))
          : mergeMessageSnapshot(current, mappedMessages))
        setSettings({ ...initialChatSettings, ...response.settings })
        setScheduledJobs([])
        await loadPollStates(mappedMessages)
        setConsumedViewOnce({})
        return
      }

      if (!supabase) {
        if (!isStaleLoad()) {
          setLoadError('Supabase no está configurado.')
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
          setLoadError('No tienes acceso a este chat o no existe.')
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
        const [{ data: profileRows }, { data: participantRows, error: participantError }, nicknameResult] =
          await Promise.all([
            supabase.from('profiles').select('*').eq('id', otherId).limit(1),
            supabase
              .from('chat_participants')
              .select('last_read_at')
              .eq('chat_id', cleanChatId)
              .eq('user_id', otherId)
              .limit(1),
            Promise.resolve().then(() => supabase!.from('nicknames').select('nickname')
              .eq('user_id', user.id).eq('target_user_id', otherId).limit(1))
              .then(result => result.error && isMissingTableError(result.error) ? { data: [], error: null } : result)
              .catch(() => ({ data: [], error: null })),
          ])
        if (participantError && !isMissingTableError(participantError)) {
          throw participantError
        }
        const profile = ((profileRows ?? []) as GenericRow[])[0]
        const previous = otherParticipantRef.current?.id === otherId ? otherParticipantRef.current : null
        const publicName = textOf(profile ?? {}, ['displayName', 'display_name', 'username'], notificationParticipant?.id === otherId ? notificationParticipant.name : previous?.publicName || previous?.name || 'Chat privado')
        const nickname = textOf(((nicknameResult.data ?? []) as GenericRow[])[0] ?? {}, ['nickname'], '').trim()
        const displayName = nickname || publicName
        const rawAvatarUrl = textOf(profile ?? {}, ['avatar_url', 'photo_url', 'profile_image'], '')
        const avatarUrl = resolveSupabasePublicUrl('avatars', rawAvatarUrl) || previous?.avatarUrl || (notificationParticipant?.id === otherId ? notificationParticipant.avatarUrl : '')
        const participant = ((participantRows ?? []) as GenericRow[])[0]
        if (!isStaleLoad()) {
          setTitle(displayName)
          setOtherParticipant({
            id: otherId,
            name: displayName,
            publicName,
            nickname: nickname || undefined,
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
        .order('created_at', { ascending: false })
        .order('id', { ascending: false })
        .limit(CONVERSATION_PAGE_SIZE)
      if (msgError) throw msgError

      const newestFirst = (messageRows ?? []) as GenericRow[]
      const mappedMessages = newestFirst.map(mapMessage).reverse()
      if (isStaleLoad()) return
      if (!options?.silent || !expandedHistoryRef.current) setHasOlderMessages(newestFirst.length === CONVERSATION_PAGE_SIZE)
      setMessages(current => options?.silent
        ? mergeLatestMessagePage(current, mappedMessages, newestFirst.length === CONVERSATION_PAGE_SIZE, idsAtRequestStart)
        : mergeMessageSnapshot(current, mappedMessages))
      await Promise.all([
        loadPollStates(mappedMessages),
        loadConsumedViewOnce(mappedMessages),
        markAsDeliveredAndRead(),
      ])
    } catch (caught) {
      if (!isStaleLoad()) {
        if ([401, 403, 404].includes(Number((caught as { status?: number })?.status))) setMessages([])
        setLoadError(friendlyError(caught, 'No se pudo cargar la conversación'))
      }
    } finally {
      if (!isStaleLoad()) {
        setLoading(false)
      }
    }
  }, [cleanChatId, loadConsumedViewOnce, loadPollStates, markAsDeliveredAndRead, notificationParticipant, useUbuntu, user])

  const loadOlderMessages = useCallback(async () => {
    if ((!useUbuntu && !supabase) || !cleanChatId || loadingOlderMessagesRef.current) return
    const oldest = messages[0]
    if (!oldest?.createdAt || !hasOlderMessages) return
    const cursorDate = new Date(oldest.createdAt)
    if (Number.isNaN(cursorDate.getTime())) return

    const requestChatId = cleanChatId
    const requestLoadSequence = historyGenerationRef.current
    const thread = threadRef.current
    if (!thread) return
    loadingOlderMessagesRef.current = true
    expandedHistoryRef.current = true
    setError(null)
    setLoadingOlderMessages(true)
    shouldStickToBottomRef.current = false
    try {
      let olderPage: ChatMessage[]
      if (useUbuntu) {
        const cursor = historyCursorRef.current
        if (!cursor) return
        const response = await loadFallbackHistoryPage('chat', requestChatId, cursor, CONVERSATION_PAGE_SIZE)
        if (requestLoadSequence !== historyGenerationRef.current) return
        olderPage = response.messages.map(mapMessage)
        historyCursorRef.current = response.historyPage?.nextCursor || null
        setHasOlderMessages(Boolean(response.historyPage?.hasMore))
      } else {
      const cursorTimestamp = oldest.createdAt
      const cursorId = oldest.id
      const safeId = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(cursorId)
        ? cursorId
        : ''
      let query = supabase!.from('messages').select('*').eq('chat_id', requestChatId)
      query = safeId
        ? query.or(`created_at.lt.${cursorTimestamp},and(created_at.eq.${cursorTimestamp},id.lt.${safeId})`)
        : query.lt('created_at', cursorTimestamp)
      const { data, error: pageError } = await query
        .order('created_at', { ascending: false })
        .order('id', { ascending: false })
        .limit(CONVERSATION_PAGE_SIZE)
      if (pageError) throw pageError
      if (requestLoadSequence !== historyGenerationRef.current) return

      const descendingRows = (data ?? []) as GenericRow[]
      olderPage = descendingRows.map(mapMessage).reverse()
      setHasOlderMessages(descendingRows.length === CONVERSATION_PAGE_SIZE)
      }
      expandedHistoryRef.current = true
      if (olderPage.length === 0) return

      const viewportAnchor = captureConversationViewportAnchor(thread)
      setMessages(current => mergeOlderMessagePage(current, olderPage))
      setHistoryWindowSize(size => size + olderPage.length)
      window.requestAnimationFrame(() => window.requestAnimationFrame(() => {
        if (requestLoadSequence === historyGenerationRef.current && threadRef.current === thread) {
          restoreConversationViewportAnchor(thread, viewportAnchor)
        }
      }))
      await Promise.all([loadPollStates(olderPage), loadConsumedViewOnce(olderPage)])
    } catch (caught) {
      if (requestLoadSequence === historyGenerationRef.current) {
        setError(friendlyError(caught, 'No se pudieron cargar mensajes anteriores'))
      }
    } finally {
      if (requestLoadSequence === historyGenerationRef.current) {
        loadingOlderMessagesRef.current = false
        setLoadingOlderMessages(false)
      }
    }
  }, [cleanChatId, hasOlderMessages, loadConsumedViewOnce, loadPollStates, messages, useUbuntu])
  loadOlderMessagesRef.current = () => { void loadOlderMessages() }

  // Search and the media gallery retain access to the entire authorized history.
  // Ordinary chat entry only hydrates its recent page.
  useEffect(() => {
    if ((showMessageSearch || galleryMessageId || replyJumpId && !messageById.has(replyJumpId)) && hasOlderMessages && !loadingOlderMessages && !error) {
      loadOlderMessagesRef.current()
    }
  }, [showMessageSearch, galleryMessageId, replyJumpId, messageById, hasOlderMessages, loadingOlderMessages, error])

  useEffect(() => {
    void loadChat()
  }, [loadChat])

  useConversationRecovery(Boolean(useUbuntu && user && cleanChatId), () => loadChat({ silent: true }))

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
    if (!cleanChatId || !user) return
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

  const newestVisibleMessageId = messages[messages.length - 1]?.id
  useEffect(() => {
    if (newestVisibleMessageId && !loading && document.visibilityState === 'visible') void markAsDeliveredAndRead()
  }, [newestVisibleMessageId, loading, markAsDeliveredAndRead])

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
          if(textOf(row,['sender_id','senderId'],'')!==user.id&&!textOf(row,['read_at','readAt'],'')&&document.visibilityState==='visible')void markAsDeliveredAndRead()
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
  }, [cleanChatId, markAsDeliveredAndRead, refreshPollState, removeMessageFromState, upsertMessageInState, useUbuntu, user])

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
      const owner = preparationOwnerRef.current
      const queued = [...pendingAttachments]
      setUploading(true)
      try {
        for (let index = 0; index < queued.length; index += 1) {
          const attachment = queued[index]
          const animated = attachment.contentType === 'sticker' && await animatedImageFile(attachment.file)
          if (owner !== preparationOwnerRef.current) return
          const safeName = attachment.file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
          const filePath = `dm/${user.id}/${Date.now()}_${index}_${safeName}`
          const uploaded = await uploadFallbackFile('chat-media', attachment.file, filePath, undefined,
            attachment.contentType === 'sticker' ? { id: crypto.randomUUID(), title: attachment.caption.trim() || 'Sticker', isAnimated: animated } : undefined)
          if (owner !== preparationOwnerRef.current) return
          const caption =
            attachment.caption.trim() || (index === 0 ? textFallback.trim() : '') || (attachment.contentType === 'sticker' ? 'Sticker' : attachment.file.name)
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
    const owner = preparationOwnerRef.current
    const queued = [...pendingAttachments]
    setUploading(true)
    try {
      for (let index = 0; index < queued.length; index += 1) {
        const attachment = queued[index]
        if (owner !== preparationOwnerRef.current) return
        const safeName = attachment.file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
        const filePath = `dm/${user.id}/${Date.now()}_${index}_${safeName}`
        const { error: uploadError } = await supabase.storage
          .from('chat-media')
          .upload(filePath, attachment.file, { contentType: attachment.file.type || undefined })
        if (uploadError) throw uploadError
        const { data } = supabase.storage.from('chat-media').getPublicUrl(filePath)
        if (owner !== preparationOwnerRef.current) return
        const caption =
          attachment.caption.trim() || (index === 0 ? textFallback.trim() : '') || (attachment.contentType === 'sticker' ? 'Sticker' : attachment.file.name)
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
    if (editingMessageId && (!text || text.length > 4096)) { setError('Escribe entre 1 y 4096 caracteres para guardar la corrección.'); return }
    const submittedDraft = draft
    const draftRevisionAtSend = draftRevisionRef.current
    const sendingTextOnly = !editingMessageId && pendingAttachments.length === 0
    const sendOwner = preparationOwnerRef.current
    const submissionMask = sendingTextOnly ? {owner:sendOwner, revision:draftRevisionAtSend, value:submittedDraft} : null
    sendInFlightRef.current = true
    setError(null)
    setSending(true)
    // Hide the input immediately, but keep its durable draft until local acceptance.
    if (submissionMask) setSubmittedText(submissionMask)
    try {
      if (editingMessageId) {
        if (useUbuntu) {
          const response = await editFallbackChatMessage(cleanChatId, editingMessageId, text)
          if (sendOwner !== preparationOwnerRef.current) return
          loadSequenceRef.current += 1
          upsertMessageInState(mapMessage(response.message))
          setEditingMessageId(null)
          setEditingDraft('')
          return
        }
        if (!supabase) return
        if (!text) return
        const nowIso = new Date().toISOString()
        const { data: editedRow, error: editError } = await supabase
          .from('messages')
          .update({ content: text, is_edited: true, updated_at: nowIso })
          .eq('id', editingMessageId)
          .eq('chat_id', cleanChatId)
          .eq('sender_id', user?.id ?? '')
          .select('id').maybeSingle()
        if (editError) throw editError
        if (!editedRow) throw new Error('No se pudo guardar: el mensaje ya no está disponible para editar.')
        if (sendOwner !== preparationOwnerRef.current) return
        loadSequenceRef.current += 1
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
        if (sendOwner !== preparationOwnerRef.current) return
        if (draftRevisionRef.current === draftRevisionAtSend) setDraft(current => current === submittedDraft ? '' : current)
        setReplyToId(current => current === replyToId ? null : current)
      }
    } catch (caught) {
      if (sendOwner === preparationOwnerRef.current) setError(friendlyError(caught, editingMessageId ? 'No se pudo guardar la corrección. Tu texto sigue aquí para volver a intentarlo.' : 'No se pudo enviar el mensaje'))
    } finally {
      setSubmittedText(current => current === submissionMask ? null : current)
      sendInFlightRef.current = false
      setSending(false)
    }
  }

  function handleAttachFile(input: FileList | File[] | null) {
    setError(null)
    const selected = Array.from(input || [])
    startPreparation(progress => prepareAttachmentFiles(selected, progress))
  }

  async function toggleReaction(messageId: string, emoji: string) {
    if (useUbuntu) {
      if (!user || !cleanChatId) return
      const context = reactionContext.current
      const identity = getStorageReadIdentity()
      if (identity.provider !== 'fallback' || identity.accountId !== user.id) return
      const isCurrent = () => context === reactionContext.current && identity === getStorageReadIdentity()
      try {
        const response = await toggleFallbackChatReaction(cleanChatId, messageId, emoji)
        if (isCurrent()) upsertMessageInState(mapMessage(response.message))
      } catch (caught) {
        if (isCurrent()) setError(friendlyError(caught, 'No se pudo reaccionar'))
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

  async function sendForwardedMessage(message: ChatMessage) { setForwardMessages([message]) }

  async function deleteMessageForAll(message: ChatMessage) {
    if (!user) return
    const context = deletionContext.current
    const identity = getStorageReadIdentity()
    if (useUbuntu && (identity.provider !== 'fallback' || identity.accountId !== user.id)) return
    const isCurrent = () => context === deletionContext.current && identity === getStorageReadIdentity()
    if (useUbuntu) {
      try {
        const response = await deleteFallbackChatMessage(cleanChatId, message.id, true)
        if (isCurrent()) upsertMessageInState(mapMessage(response.message))
      } catch (caught) {
        if (isCurrent()) setError(friendlyError(caught, 'No se pudo borrar para todos'))
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
      if (!isCurrent()) return
      setMessages((current) =>
        current.map((entry) =>
          entry.id === message.id
            ? { ...entry, deletedForAll: true, content: '', mediaUrl: '', updatedAt: nowIso }
            : entry,
        ),
      )
    } catch (caught) {
      if (isCurrent()) setError(friendlyError(caught, 'No se pudo borrar para todos'))
    }
  }

  async function deleteMessageForMe(message: ChatMessage) {
    if (!user) return false
    const context = deletionContext.current
    const identity = getStorageReadIdentity()
    if (useUbuntu && (identity.provider !== 'fallback' || identity.accountId !== user.id)) return false
    const isCurrent = () => context === deletionContext.current && identity === getStorageReadIdentity()
    if (useUbuntu && ['pending','failed'].includes(message.status)) {
      try { await cancelPendingWebMessage('chat', cleanChatId, message.id); if (!isCurrent()) return false; setMessages(current => current.filter(item => item.id !== message.id)); return true }
      catch(caught) { if (isCurrent()) setError(friendlyError(caught, 'No se pudo cancelar el envío')); return false }
    }
    if (useUbuntu) {
      try {
        const response = await deleteFallbackChatMessage(cleanChatId, message.id, false)
        if (!isCurrent()) return false
        upsertMessageInState(mapMessage(response.message)); return true
      } catch (caught) {
        if (isCurrent()) setError(friendlyError(caught, 'No se pudo borrar para mi')); return false
      }
    }
    if (!supabase || !user) return false
    try {
      const nextDeletedBy = Array.from(new Set([...message.deletedBy, user.id]))
      const { error: deleteError } = await supabase
        .from('messages')
        .update({ deleted_by: nextDeletedBy, updated_at: new Date().toISOString() })
        .eq('id', message.id)
      if (deleteError) throw deleteError
      if (!isCurrent()) return false
      setMessages((current) =>
        current.map((entry) =>
          entry.id === message.id ? { ...entry, deletedBy: nextDeletedBy } : entry,
        ),
      )
      return true
    } catch (caught) {
      if (isCurrent()) setError(friendlyError(caught, 'No se pudo borrar para mi')); return false
    }
  }

  async function createChatPoll() {
    if (!user || (!supabase && !useUbuntu) || !cleanChatId || creatingPoll) return
    const question = pollQuestion.trim()
    const options = pollOptions
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.length > 0)

    if (!question || options.length < 2) {
      setError('La encuesta necesita pregunta y al menos 2 opciones')
      return
    }

    const context = pollContext.current
    const identity = getStorageReadIdentity()
    if (useUbuntu && (identity.provider !== 'fallback' || identity.accountId !== user.id)) return
    const isCurrent = () => context === pollContext.current && identity === getStorageReadIdentity()
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
      if (!isCurrent()) return
      if (pollError) throw pollError
      setPollQuestion('')
      setPollOptions('Si\nNo')
      setPollAllowsMultiple(false)
      setShowPollComposer(false)
      void loadChat({ silent: true })
    } catch (caught) {
      if (isCurrent()) setError(friendlyError(caught, 'No se pudo crear encuesta'))
    } finally {
      if (isCurrent()) setCreatingPoll(false)
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
    if (!user || (!supabase && !useUbuntu) || !cleanChatId || scheduling) return
    const content = scheduledDraft.trim()
    if (!content) return

    const scheduleIso = localDateTimeToIso(scheduledAt)
    if (scheduledAt.trim() && !scheduleIso) {
      setError('Fecha invalida para programacion')
      return
    }

    const context = scheduleContext.current
    const identity = getStorageReadIdentity()
    const isCurrent = () => context === scheduleContext.current && identity === getStorageReadIdentity()
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
          expectedIdentity: identity, expectedAccountId: user.id,
        })
      } else {
        if (!supabase) throw new Error('Servicio de mensajes no disponible')
        const { p_idempotency_key: _idempotencyKey, ...supabasePayload } = payload
        void _idempotencyKey
        const { error: scheduleError } = await supabase.rpc('secure_schedule_message_job', supabasePayload)
        if (scheduleError) throw scheduleError
      }
      if (!isCurrent()) return
      scheduleRequestKeyRef.current = null
      setScheduledDraft('')
      setScheduledAt('')
      await refreshScheduledJobs()
      if (isCurrent()) setShowScheduleComposer(false)
    } catch (caught) {
      if (isCurrent()) setError(friendlyError(caught, 'No se pudo programar mensaje'))
    } finally {
      if (isCurrent()) setScheduling(false)
    }
  }



  async function saveChatPreferences(patch: Partial<Pick<ChatSettings, 'isMuted' | 'isPinned' | 'isArchived' | 'isFavorite'>>) {
    if (!user || !cleanChatId) return
    const context = preferenceContext.current
    const isCurrent = () => context === preferenceContext.current
    if (useUbuntu) {
      if (savingSettings) return
      setSavingSettings(true)
      try {
        await saveFallbackChatPreferences(cleanChatId, patch)
        if (!isCurrent()) return
        setSettings(current => ({ ...current, ...patch }))
      } catch (caught) {
        if (!isCurrent()) return
        setError(friendlyError(caught, 'No se pudieron guardar los cambios del chat'))
      } finally { if (isCurrent()) setSavingSettings(false) }
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
      if (!isCurrent()) return
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
        if (!isCurrent()) return
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
          if (!isCurrent()) return
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
          if (!isCurrent()) return
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
      if (!isCurrent()) return
      reportSupabaseServiceRestrictionIfPresent('chat-preferences', caught)
      setError(friendlyError(caught, 'No se pudieron guardar preferencias'))
    } finally {
      if (isCurrent()) setSavingSettings(false)
    }
  }

  async function saveChatNickname() {
    if (!user || !otherParticipant || savingNickname) return
    const nickname = nicknameDraft.trim().slice(0, 120)
    const context = nicknameContext.current
    const identity = getStorageReadIdentity()
    const isCurrent = () => context === nicknameContext.current && identity === getStorageReadIdentity()
    setSavingNickname(true)
    setError(null)
    try {
      if (useUbuntu) {
        if (nickname) {
          await fallbackApiJson(`/v1/data/messages/nicknames/${encodeURIComponent(otherParticipant.id)}`, {
            method: 'PUT', body: JSON.stringify({ nickname }),
            expectedIdentity: identity, expectedAccountId: user.id,
          })
        } else {
          await fallbackApiJson(`/v1/data/messages/nicknames/${encodeURIComponent(otherParticipant.id)}`, { method: 'DELETE', expectedIdentity: identity, expectedAccountId: user.id })
        }
      } else {
        if (!supabase) return
        const result = nickname
          ? await supabase.from('nicknames').upsert({ user_id: user.id, target_user_id: otherParticipant.id, nickname }, { onConflict: 'user_id,target_user_id' })
          : await supabase.from('nicknames').delete().eq('user_id', user.id).eq('target_user_id', otherParticipant.id)
        if (result.error) throw result.error
      }
      if (!isCurrent()) return
      const nextName = nickname || otherParticipant.publicName || otherParticipant.name
      setOtherParticipant(current => current?.id === otherParticipant.id
        ? { ...current, name: nextName, nickname: nickname || undefined }
        : current)
      setTitle(nextName)
      setNicknameDialogOpen(false)
      window.dispatchEvent(new Event('novvin:conversation-preferences-changed'))
    } catch (caught) {
      if (isCurrent()) setError(friendlyError(caught, 'No se pudo guardar el apodo privado'))
    } finally {
      if (isCurrent()) setSavingNickname(false)
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

  async function importSticker(source: File | null) {
    if (!source || !user || !cleanChatId) return
    const owner = preparationOwnerRef.current
    const [file] = await normalizeClipboardFiles([source])
    if (owner !== preparationOwnerRef.current) return
    if (!isStickerImage(file)) {
      setError('El sticker debe ser una imagen PNG, WEBP, GIF, JPG o JPEG.')
      return
    }
    setUploading(true)
    setError(null)
    try {
      const isAnimated = await animatedImageFile(file)
      if (owner !== preparationOwnerRef.current) return
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `stickers/${user.id}/${Date.now()}_${safeName}`
      const mediaUrl = useUbuntu
        ? (await uploadFallbackFile('chat-media', file, filePath, undefined, { id: crypto.randomUUID(), title: file.name.replace(/\.[^.]+$/, '') || 'Sticker', isAnimated })).publicUrl
        : await (async () => {
            if (!supabase) throw new Error('Backend no configurado')
            const { error: uploadError } = await supabase.storage
              .from('chat-media')
              .upload(filePath, file, { contentType: file.type || undefined })
            if (uploadError) throw uploadError
            return supabase.storage.from('chat-media').getPublicUrl(filePath).data.publicUrl
          })()
      if (owner !== preparationOwnerRef.current) return
      if (!useUbuntu) {
        const saved = await backendRpc('secure_toggle_sticker_favorite', { p_sticker_id: crypto.randomUUID(), p_favorite: true, p_media_url: mediaUrl, p_title: file.name.replace(/\.[^.]+$/, '') || 'Sticker', p_is_animated: isAnimated })
        if (saved.error) throw saved.error
      }
      if (owner !== preparationOwnerRef.current) return
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
    if (!user || !jobId) return
    const context = scheduleContext.current
    const identity = getStorageReadIdentity()
    const isCurrent = () => context === scheduleContext.current && identity === getStorageReadIdentity()
    setError(null)
    try {
      if (useUbuntu) {
        await fallbackApiJson('/rest/v1/rpc/secure_cancel_scheduled_message_job', {
          method: 'POST',
          body: JSON.stringify({ p_job_id: jobId }),
          expectedIdentity: identity, expectedAccountId: user.id,
        })
      } else {
        if (!supabase) throw new Error('Servicio de mensajes no disponible')
        const { error: cancelError } = await supabase.rpc('secure_cancel_scheduled_message_job', {
          p_job_id: jobId,
        })
        if (cancelError) throw cancelError
      }
      if (isCurrent()) await refreshScheduledJobs()
    } catch (caught) {
      if (isCurrent()) setError(friendlyError(caught, 'No se pudo cancelar el mensaje programado'))
    }
  }

  if (loading && !otherParticipant) {
    return <StatusView title="Cargando conversacion" description="Espera un momento..." />
  }

  if (loadError) {
    return (
      <section className="section-stack">
        {!embedded ? (
          <Link to="/app/messages" className="inline-link">
            ← Volver a mensajes
          </Link>
        ) : null}
        <StatusView title="No se pudo abrir el chat" description={loadError} />
      </section>
    )
  }

  const rootClassName = [
    embedded ? 'section-stack chat-detail-embedded' : 'section-stack chat-detail-page',
    dragActive ? 'chat-drop-active' : '',
    profilePanelOpen ? 'chat-profile-open' : '',
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
      {showBirthday && user && cleanChatId && otherParticipant ? <BirthdayComposer key={cleanChatId} owner={user.id} chatId={cleanChatId} recipient={otherParticipant.name} onClose={closeBirthday} onSend={async content => { await sendMessage({ content, contentType: 'birthday_card' }) }}/> : null}
      {profilePanelOpen && otherParticipant ? <ChatProfilePanel key={otherParticipant.id} profileId={otherParticipant.id} onClose={() => setProfilePanelOpen(false)} /> : null}
      {dragActive ? (
        <div className="chat-drop-overlay">
          <strong>Suelta tus archivos aquí</strong>
          <small>Imágenes, videos, audios y documentos</small>
        </div>
      ) : null}
      <div className="chat-header-row row-between shadow-sm">
        <div className="row-actions">
          {!embedded ? (
            <Link to="/app/messages" className="icon-btn-sm" title="Volver" aria-label="Volver a Mensajes">
              <ArrowLeft size={20} aria-hidden="true" />
            </Link>
          ) : null}
          {otherParticipant ? (
            <button type="button" className="identity-link compact" onClick={() => setProfilePanelOpen(value => !value)} aria-expanded={profilePanelOpen} aria-label={`Ver perfil de ${otherParticipant.name}`}>
              <FallbackAvatar url={otherParticipant.avatarUrl} name={otherParticipant.name} className="avatar-sm" />
              <div className="col-stack-tight">
                <strong>{otherParticipant.name} <ProfileStatusBadges profileId={otherParticipant.id}/></strong>
                <small>Chat privado</small>
              </div>
            </button>
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
                title="Llamada de voz" aria-label="Llamada de voz"
              >
                <Phone size={20} aria-hidden="true" />
              </Link>
              <Link
                className="icon-btn header-icon-btn"
                to={`/app/calls?contextId=${encodeURIComponent(cleanChatId)}&target=${encodeURIComponent(otherParticipant.id)}&kind=video&name=${encodeURIComponent(otherParticipant.name)}`}
                title="Videollamada" aria-label="Videollamada"
              >
                <Video size={20} aria-hidden="true" />
              </Link>
            </>
          ) : null}
          <button
            className={showMessageSearch ? 'icon-btn header-icon-btn active' : 'icon-btn header-icon-btn'}
            type="button"
            title="Buscar en la conversación" aria-label="Buscar en la conversación" aria-expanded={showMessageSearch}
            onClick={() => {
              setShowMessageSearch((current) => !current)
              if (showMessageSearch) {
                setMessageSearchQuery('')
              } else {
                setShowQuickShare(false)
              }
            }}
          >
            <Search size={20} aria-hidden="true" />
          </button>
          <button
            className={showQuickShare ? 'icon-btn header-icon-btn active' : 'icon-btn header-icon-btn'}
            type="button"
            title="Menú" aria-label="Opciones del chat" aria-haspopup="menu" aria-expanded={showQuickShare} aria-controls="chat-options-menu"
            onClick={() => setShowQuickShare((c) => !c)}
          >
            <MoreVertical size={20} aria-hidden="true" />
          </button>
        </div>
      </div>

      {showMessageSearch ? (
        <div className="chat-search-row">
          <input
            ref={messageSearchInputRef}
            className="text-input chat-search-input"
            aria-label="Buscar en este chat"
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

      {showQuickShare ? <ChatOptionsMenu pinned={settings.isPinned} muted={settings.isMuted} favorite={settings.isFavorite} archived={settings.isArchived} busy={savingSettings}
        onPreference={patch => void saveChatPreferences(patch)} onInfo={() => setProfilePanelOpen(true)} onSearch={() => setShowMessageSearch(true)}
        onSelect={() => setSelectingMessages(true)} onAdvanced={() => setShowAdvancedTools(true)} onLeave={() => onCloseChat?onCloseChat():navigate('/app/messages')} onClose={() => setShowQuickShare(false)}
        onNickname={otherParticipant ? () => { setNicknameDraft(otherParticipant.nickname || ''); setNicknameDialogOpen(true) } : undefined} /> : null}
      {nicknameDialogOpen && otherParticipant ? (
        <div className="chat-tools-overlay chat-advanced-options fade-in" role="dialog" aria-modal="true" aria-label="Apodo privado" onKeyDown={event => { if (event.key === 'Escape') setNicknameDialogOpen(false) }}>
          <article className="surface-card section-stack shadow-lg">
            <div className="row-between"><h3>Apodo privado</h3><button type="button" className="icon-btn-sm" aria-label="Cerrar apodo privado" onClick={() => setNicknameDialogOpen(false)}><X size={18} aria-hidden="true" /></button></div>
            <p className="muted-line">Solo tú verás este apodo. Déjalo vacío para volver al nombre del perfil.</p>
            <input autoFocus className="text-input" maxLength={120} value={nicknameDraft} onChange={event => setNicknameDraft(event.target.value)} placeholder={otherParticipant.publicName || otherParticipant.name} aria-label="Apodo" />
            <div className="row-actions"><button type="button" className="ghost-btn" disabled={savingNickname} onClick={() => setNicknameDialogOpen(false)}>Cancelar</button><button type="button" className="primary-btn" disabled={savingNickname} onClick={() => void saveChatNickname()}>{savingNickname ? 'Guardando…' : 'Guardar apodo'}</button></div>
          </article>
        </div>
      ) : null}
      {showAdvancedTools ? (
        <div className="chat-tools-overlay chat-advanced-options fade-in" role="dialog" aria-label="Más opciones del chat" onKeyDown={event => { if(event.key === 'Escape') setShowAdvancedTools(false) }}>
          <article className="surface-card section-stack shadow-lg">
            <div className="row-between">
              <h3>Más opciones del chat</h3>
              <button className="icon-btn-sm" aria-label="Cerrar más opciones" onClick={() => setShowAdvancedTools(false)}><X size={18} aria-hidden="true" /></button>
            </div>

            <div className="grid-2-col">
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

      {selectingMessages ? <div className="message-selection-toolbar" role="region" aria-label="Mensajes seleccionados">
        <button type="button" className="icon-btn" aria-label="Cancelar selección" onClick={() => { setSelectingMessages(false); setSelectedMessageIds(new Set()) }}><X size={18} aria-hidden="true" /></button>
        <strong>{selectedMessageIds.size} seleccionados</strong>
        <button type="button" className="mini-action-btn" onClick={() => setSelectedMessageIds(new Set(renderedMessages.filter(item => !item.deletedForAll && !item.deletedBy.includes(user?.id ?? '')).map(item => item.id)))}>Seleccionar visibles</button>
        <button type="button" className="mini-action-btn" disabled={!selectedMessageIds.size} onClick={() => { void navigator.clipboard.writeText(messages.filter(item => selectedMessageIds.has(item.id)).map(item => item.content || item.mediaUrl).join('\n')).catch(() => setError('No se pudo copiar. Permite el acceso al portapapeles.')) }}>Copiar</button>
        <button type="button" className="mini-action-btn" disabled={!selectedMessageIds.size || selectionBusy} onClick={() => setForwardMessages(messages.filter(item => selectedMessageIds.has(item.id)))}>Reenviar</button>
        <button type="button" className="mini-action-btn danger" disabled={!selectedMessageIds.size || selectionBusy} onClick={async () => {
          if (!window.confirm(`¿Eliminar ${selectedMessageIds.size} mensajes de tu vista?`)) return
          const context = deletionContext.current
          const identity = getStorageReadIdentity()
          const isCurrent = () => context === deletionContext.current && identity === getStorageReadIdentity()
          setSelectionBusy(true)
          const completed = new Set<string>()
          try { for (const item of messages.filter(item => selectedMessageIds.has(item.id))) { if (!isCurrent() || !await deleteMessageForMe(item)) break; completed.add(item.id) } }
          finally { if (isCurrent()) { setSelectionBusy(false); setSelectedMessageIds(current => new Set([...current].filter(id => !completed.has(id)))); if(completed.size === selectedMessageIds.size) setSelectingMessages(false) } }
        }}>{selectionBusy ? 'Eliminando…' : 'Eliminar para mí'}</button>
      </div> : null}
      {forwardMessages.length ? <ForwardMessagesDialog messages={forwardMessages} onClose={() => setForwardMessages([])} /> : null}
      <div className="chat-thread-shell" inert={pendingAttachments.length > 0}>
        {useUbuntu && user ? <DirectMessageRequestNotice key={`${user.id}:${cleanChatId}`} accountId={user.id} chatId={cleanChatId} revision={messages.length}/> : null}
        <section className="chat-thread" ref={threadRef} onContextMenu={event => { if(event.target === event.currentTarget) { event.preventDefault(); setShowQuickShare(true) } }} onScroll={handleThreadScroll} onLoadCapture={keepLatestMessageVisible} onLoadedMetadataCapture={keepLatestMessageVisible}>
          {hasOlderMessages ? <button type="button" className="ghost-btn" disabled={loadingOlderMessages} onClick={() => loadOlderMessagesRef.current()}>
            {loadingOlderMessages ? 'Cargando mensajes…' : 'Ver mensajes anteriores'}
          </button> : renderedMessages.length < filteredMessages.length ? <button type="button" className="ghost-btn" onClick={() => {
            const thread = threadRef.current, height = thread?.scrollHeight || 0, top = thread?.scrollTop || 0
            shouldStickToBottomRef.current = false
            setHistoryWindowSize(size => size + 80)
            window.requestAnimationFrame(() => { if (thread) thread.scrollTop = top + thread.scrollHeight - height })
          }}>Ver mensajes anteriores</button> : null}
          {loading && filteredMessages.length === 0 ? <div className="chat-search-empty" role="status">Cargando mensajes…</div> : filteredMessages.length === 0 && normalizedMessageSearch ? (
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
          const mine = message.senderId === user?.id
          const album = albums.get(message.id)
          if (album && !selectingMessages && !replyJumpId && album.leader !== message.id) return null
          if (album && !selectingMessages && !replyJumpId) return <Fragment key={message.id}>{showDaySeparator ? <div className="chat-day-separator"><span>{formatConversationDay(message.createdAt)}</span></div> : null}<ChatMediaAlbum onPhotoOpen={openGallery} items={album.items} mine={message.senderId === user?.id} deliveryState={item => mineDeliveryState(item, toMillis(item.createdAt) > 0 && toMillis(otherParticipant?.lastReadAt ?? '') >= toMillis(item.createdAt))} onReply={item => beginReply(item.id)} onForward={item => void sendForwardedMessage(item)} onDelete={item => void deleteMessageForMe(item)} onDeleteForAll={item => void deleteMessageForAll(item)} onReact={(item, emoji) => void toggleReaction(item.id, emoji || '👍')} /></Fragment>

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
          const showMessageText = message.contentType !== 'sticker' && shouldRenderMessageText(message)
          const activeReactions = Object.keys(message.reactions).filter(
            (emoji) => reactionCount(message.reactions, emoji) > 0,
          )

          const inlinePhoto = Boolean(message.mediaUrl && !message.deletedForAll && !['sticker', 'location', 'contact', 'birthday_card', 'poll', 'raffle', 'question'].includes(message.contentType) && !isViewOnceType(message.contentType) && attachmentKind(message.contentType, message.mediaUrl) === 'image')
          const photoTimestamp = inlinePhoto ? <MessageTimestamp createdAt={message.createdAt} mine={mine} status={deliveryState || 'sent'} edited={message.isEdited} readTitle={readByOther ? `Leído por ${otherParticipant?.name || "contacto"}` : undefined} onRetry={() => void retryPendingWebMessage(message.id).catch(error => setError(friendlyError(error)))}/> : undefined
          return (
            <Fragment key={message.id}>
              {showDaySeparator ? (
                <div className="chat-day-separator">
                  <span>{formatConversationDay(message.createdAt)}</span>
                </div>
              ) : null}
              <article id={`message-${message.id}`} onDoubleClick={event => { if (shouldReplyToMessageDoubleClick(event.target, selectingMessages)) beginReply(message.id) }} title="Doble clic para responder" className={`${mine ? 'bubble bubble-me' : 'bubble bubble-them'}${message.contentType === 'sticker' && !message.deletedForAll ? ' sticker-bubble' : ''}${inlinePhoto ? ' image-bubble' : ''}${selectingMessages ? ' message-selectable' : ''}${selectedMessageIds.has(message.id) ? ' message-selected' : ''}${message.contentType === 'text' && !message.mediaUrl && !message.replyToId && !message.isForwarded && !message.deletedForAll && !activeReactions.length && !/https?:/.test(message.content) && !['pending','failed'].includes(message.status) ? ' text-bubble' : ''}`} >
                {selectingMessages ? <label className="message-select-control"><input type="checkbox" aria-label={`Seleccionar mensaje de ${mine ? 'ti' : title} a las ${new Date(message.createdAt).toLocaleTimeString('es')}`} checked={selectedMessageIds.has(message.id)} onChange={() => toggleSelectedMessage(message.id)} /></label> : null}
                {message.isForwarded ? <small>Reenviado</small> : null}
                {message.replyToId ? <MessageReplyPreview onJump={setReplyJumpId} message={replied || { id: message.replyToId, content: 'Mensaje anterior', contentType: 'text', mediaUrl: null, deletedForAll: false }} name={replied?.senderId === user?.id ? 'Tú' : title}/> : null}

                {message.deletedForAll ? (
                  <p className="muted-line">Mensaje eliminado para todos</p>
                ) : message.contentType === 'birthday_card' ? (
                  <BirthdayLetter content={message.content}/>
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
                          <a className="inline-link" href={messageLocationUrl(message.mediaUrl) || undefined} target="_blank" rel="noreferrer">
                            Abrir ubicacion en mapa
                          </a>
                        ) : null}
                      </>
                    ) : message.contentType === 'contact' ? (
                      <ContactMessageCard content={message.content} />
                    ) : (
                      <>
                        {message.mediaUrl && message.contentType !== 'sticker' && !isViewOnceType(message.contentType) ? (
                          <ChatMessageMedia message={message} onImageOpen={() => openGallery(message)} overlay={photoTimestamp} />
                        ) : null}
                        {showMessageText ? (
                          <MessageText text={message.content} className={message.mediaUrl ? 'message-caption' : undefined}/>
                        ) : null}
                      </>
                    )}
                    {message.mediaUrl && message.contentType === 'sticker' ? (
                      <MessageSticker url={message.mediaUrl} title={message.content || 'Sticker'} onReply={() => beginReply(message.id)} onForward={() => void sendForwardedMessage(message)} />
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
                  <MessageOptionsMenu mine={mine} onReact={emoji => void toggleReaction(message.id, emoji)}>
                    <button className="mini-action-btn" type="button" onClick={() => { setSelectingMessages(true); setSelectedMessageIds(new Set([message.id])) }}>Seleccionar mensaje</button>
                    <button className="mini-action-btn" type="button" onClick={() => void copyChatMessage(message.content, message.mediaUrl, message.contentType).catch(() => setError('No se pudo copiar. Permite el acceso al portapapeles o descarga la imagen.'))}>Copiar</button>
                    {message.mediaUrl && !['location', 'contact'].includes(message.contentType) && !message.contentType.startsWith('view_once') && !message.mediaUrl.startsWith('blob:') ? <MediaDownloadButton url={message.mediaUrl} name={fileNameFromUrl(message.mediaUrl)} /> : null}
                    <MessageReactionBar onReact={emoji=>void toggleReaction(message.id,emoji)}/>
                    <button className="mini-action-btn" type="button" onClick={() => beginReply(message.id)}>
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
                          setError(null)
                          setEditingMessageId(message.id)
                          requestAnimationFrame(() => document.querySelector<HTMLInputElement>('.chat-input-field')?.focus())
                          setEditingDraft(message.content)
                        }}
                      >
                        Editar
                      </button>
                    ) : null}
                    {mine ? (
                      <button className="mini-action-btn danger" type="button" onClick={() => void deleteMessageForAll(message)}>
                        Eliminar para todos
                      </button>
                    ) : null}
                    <button className="mini-action-btn" type="button" onClick={() => void deleteMessageForMe(message)}>
                      Eliminar para mí
                    </button>
                  </MessageOptionsMenu>
                ) : null}

                {mine && message.status==='failed' ? <p className="message-delivery-error" role="status">{messageDeliveryFeedback(message.deliveryError)}</p> : null}
                {mine && message.status==='pending' && message.mediaUrl ? <OutboxTransferStatus id={message.id}/> : null}
                {!inlinePhoto ? <MessageTimestamp createdAt={message.createdAt} mine={mine} status={deliveryState || 'sent'} edited={message.isEdited} readTitle={readByOther ? `Leído por ${otherParticipant?.name || "contacto"}` : undefined} onRetry={() => void retryPendingWebMessage(message.id).catch(error => setError(friendlyError(error)))}/> : null}
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

      {showStickers ? <StickerPicker onEmoji={emoji => { draftRevisionRef.current += 1; setDraft(current => current + emoji) }} onSelect={item => void sendSticker(item)} onImport={file => void importSticker(file)} onClose={() => setShowStickers(false)} /> : null}

      {!pendingAttachments.length ? <SpellingSuggestions text={composerText} caret={spellingCaret} onReplace={(text,caret)=>{draftRevisionRef.current += 1;if(editingMessageId)setEditingDraft(text);else setDraft(text);setSpellingCaret(caret);requestAnimationFrame(()=>{const input=document.querySelector<HTMLInputElement>('.chat-composer .chat-input-field');input?.focus();input?.setSelectionRange(caret,caret)})}}/> : null}
      {error ? <div className="chat-composer-error" role="alert"><span>{error}</span><button className="mini-action-btn" type="button" onClick={() => setError(null)}>Cerrar</button></div> : null}
      {preparingAttachments ? <p className="attachment-preparing" role="status">{preparationStatus}</p> : null}
      {isRecordingAudio ? <RecordingIndicator onStop={() => audioRecorderRef.current?.stop()} onCancel={() => { discardRecordingRef.current = true; audioRecorderRef.current?.stop() }} /> : null}
      {imageEditorFile ? <ChatImageEditor asSticker={pendingAttachments.some(item => item.file === imageEditorFile && item.contentType === 'sticker')} file={imageEditorFile} onClose={() => setImageEditorFile(null)} onSave={file => setPendingAttachments(current => current.map(entry => { if (entry.file !== imageEditorFile) return entry; if (entry.previewUrl) URL.revokeObjectURL(entry.previewUrl); return { ...entry, file, previewUrl: previewUrlForFile(file) } }))} /> : null}
      {galleryIndex !== null && gallery[galleryIndex] ? <MediaViewer items={gallery} initialIndex={galleryIndex} onClose={() => setGalleryMessageId(null)} /> : null}
      {pendingAttachments.length > 0 ? <ChatAttachmentPreview items={pendingAttachments} draft={draft} busy={sending || uploading} preparing={preparingAttachments} status={preparationStatus}
        onClose={() => { cancelPreparation(); clearPendingAttachments() }} onRemove={removePendingAttachment} onAdd={handleAttachFile} onSend={handleSend}
        onCaption={(id, caption) => { updatePendingAttachmentCaption(id, caption); if (pendingAttachments[0]?.id === id) setDraft(caption) }}
        onEdit={id => setImageEditorFile(pendingAttachments.find(item => item.id === id)?.file || null)}
        onToggleSticker={id => setPendingAttachments(current => current.map(item => item.id === id ? { ...item, contentType: item.contentType === 'sticker' ? 'image' : 'sticker' } : item))}
      /> : null}

      <form className="composer chat-composer shadow-lg" onSubmit={handleSend}>
        <button
          className="icon-btn"
          type="button"
          onClick={() => setShowStickers((c) => !c)}
          title="Stickers" aria-label="Stickers" aria-expanded={showStickers}
        >
          <Sticker size={22} aria-hidden="true" />
        </button>

        <details className="chat-attachment-menu"><summary className="icon-btn" aria-label="Adjuntar" title="Adjuntar"><Paperclip size={21} aria-hidden="true" /></summary><div className="chat-attachment-popover">
        <label className="chat-attachment-option" role="button" tabIndex={0} aria-label="Adjuntar archivos" title="Adjuntar archivos"
          onKeyDown={event => { if(event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.currentTarget.querySelector<HTMLInputElement>('input')?.click() } }}>
            <Files size={20} aria-hidden="true" />
          <span>Fotos, videos y documentos</span><input
            type="file"
            multiple
            hidden
            onChange={(event) => {
              const files = event.target.files
              handleAttachFile(files)
              event.currentTarget.value = '';event.currentTarget.closest('details')?.removeAttribute('open')
            }}
          />
        </label><FolderAttachmentPicker onFiles={handleAttachFile}/></div></details>

        <input
          ref={composerInputRef}
          className="text-input chat-input-field"
          onSelect={event=>setSpellingCaret(event.currentTarget.selectionStart ?? undefined)}
          lang="es" spellCheck={true} autoCorrect="on" autoCapitalize="sentences"
          disabled={Boolean(editingMessageId) && sending}
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

        <button
          className={isRecordingAudio ? 'icon-btn chat-mic-button recording' : 'icon-btn chat-mic-button'}
          type="button"
          title={isRecordingAudio ? 'Detener grabación' : 'Grabar audio'}
          onClick={() => void toggleAudioRecording()}
        >
          {isRecordingAudio ? <Square size={20} fill="currentColor" aria-hidden="true" /> : <Mic size={21} aria-hidden="true" />}
        </button>

        <button className="primary-btn circle-btn" type="submit" aria-label={editingMessageId ? 'Guardar corrección' : 'Enviar mensaje'} disabled={loading || sending || uploading || preparingAttachments}>
          {sending || uploading ? <LoaderCircle size={20} aria-hidden="true" />
            : editingMessageId ? <Check size={22} aria-hidden="true" /> : <Send size={21} aria-hidden="true" />}
        </button>
      </form>
      {viewOnceLease ? <ViewOnceMediaDialog lease={viewOnceLease} onClose={() => setViewOnceLease(null)} /> : null}
    </section>
  )
}
import { useMessageFocusFromUrl } from './useMessageFocusFromUrl'
