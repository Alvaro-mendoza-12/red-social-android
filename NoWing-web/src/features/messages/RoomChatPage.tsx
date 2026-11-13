import { ArrowLeft, BarChart3, Check, CircleDot, Files, Import, LoaderCircle, Mic, MoreVertical, Paperclip, Search, Send, Square, Sticker } from 'lucide-react'
import { animatedImageFile } from '../../core/imageEditPolicy'
import { getStorageReadIdentity, subscribeStorageReadIdentity } from '../../core/media/storageReadSession'
import { messageDeliveryFeedback } from '../../core/outboxFailureMessage'
import { MessageTimestamp } from '../../components/MessageTimestamp'
import { ContactMessageCard } from '../../components/ContactMessageCard'
import { messageLocationUrl } from '../../core/messageLocation'
import { shouldReplyToMessageDoubleClick } from '../../core/chatMessageGestures'
import { ForwardMessagesDialog } from '../../components/ForwardMessagesDialog'
import { copyChatMessage } from '../../core/media/copyChatMessage'
import { clipboardFilesFrom, clipboardMayContainImage, clipboardHasStickerHint, readClipboardImages, canPasteIntoChat, pastedAsSticker, isCopiedStickerImage, isEmptyClipboardPaste, normalizeClipboardFiles, isStickerImage } from '../../core/chatClipboard'
import { newerMessage, mergeMessageSnapshot, mergeOlderMessagePage, mergeLatestMessagePage } from '../../core/conversationMessageMerge'
import {MessageOptionsMenu,MessageReactionBar} from '../../components/MessageOptionsMenu'
import {confirmInboxConversationRead,markInboxConversationRead} from '../../core/inboxActivity'
import { CommunityRaffles } from '../../components/CommunityRaffles'
import { CommunityQuestion, CollectionQuestionTools } from '../../components/CommunityQuestion'
import { MediaDownloadButton } from '../../components/MediaDownloadButton'
import { FolderAttachmentPicker } from '../../components/FolderAttachmentPicker'
import { OutboxTransferStatus } from '../../components/OutboxTransferStatus'
import { MessageSticker } from '../../components/MessageSticker'
import { useConversationDraft } from '../../core/conversationDrafts'
import { MessageText } from '../../components/MessageText'
import { MessageReplyPreview } from '../../components/MessageReplyPreview'
import { droppedAttachments, prepareAttachmentFiles } from '../../core/attachmentPreparation'
import { useAttachmentDraft, useAttachmentPreparation } from '../../core/attachmentJobs'
import { chatGallery } from '../../core/chatGallery'
import { MediaViewer } from '../../components/MediaViewer'
import { ChatImageEditor } from '../../components/ChatImageEditor'
import { ChatAttachmentPreview } from '../../components/ChatAttachmentPreview'
import { SpellingSuggestions } from '../../components/SpellingSuggestions'
import { RecordingIndicator } from '../../components/RecordingIndicator'
import { CollectionInfo } from '../../components/CollectionInfo'
import { chatMediaAlbums } from '../../core/chatMediaAlbums'
import { ChatMediaAlbum } from '../../components/ChatMediaAlbum'
import { backendRpc } from '../../core/backendRpc'
import { conversationWindow } from '../../core/conversationWindow'
import { captureConversationViewportAnchor, restoreConversationViewportAnchor } from '../../core/conversationViewport'
import { sharedVideoReference } from '../../core/sharedVideoReference'
import { SharedVideoCard } from '../../components/SharedVideoCard'
import { retryPendingWebMessage } from '../../core/webOutbox'
import { MentionSuggestions } from '../../components/MentionSuggestions'
import { insertMention, type MentionCandidate } from '../../core/mentionSuggestions'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
import { useConversationRecovery } from './useConversationRecovery'
import { useChatEntryFocus } from './useChatEntryFocus'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { Fragment, useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type DragEvent, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { SafeAvatar } from '../../components/SafeAvatar'
import { conversationAvatarSource } from '../../core/profileMediaFields'
import { StatusView } from '../../components/StatusView'
import { supabase, resolveSupabasePublicUrl } from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import {
  deleteFallbackRoomMessage,
  editFallbackRoomMessage,
  loadFallbackRoomChat,
  loadFallbackHistoryPage,
  fallbackHistoryAllows,
  sendFallbackRoomMessage,
  uploadFallbackFile,
} from '../../core/fallbackData'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import { ViewOnceMediaDialog } from '../../components/ViewOnceMediaDialog'
import { requestViewOnceMedia } from '../../core/media/viewOnceClient'
import type { ViewOnceLease } from '../../core/media/viewOnceLease'
import { ChatAttachmentMedia } from '../../components/ChatAttachmentMedia'
import {attachmentKind} from '../../core/attachmentKind'
import { StickerPicker } from '../../components/StickerPicker'

type RoomScope = 'group' | 'community'

type RoomMessage = {
  id: string
  senderId: string
  content: string
  contentType: string
  mediaUrl: string
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

const CONVERSATION_PAGE_SIZE = 80

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

type MessageReceipt = {
  userId: string
  username: string
  readAt: string
  deliveredAt: string
}

type RoomConfig = {
  roomTable: 'groups' | 'communities'
  messageTable: 'group_messages' | 'community_messages'
  roomForeignKey: 'group_id' | 'community_id'
  sendRpc: 'secure_send_group_message' | 'secure_send_community_message'
  reactionRpc: 'secure_toggle_group_message_reaction' | 'secure_toggle_community_message_reaction'
  markReadRpc: 'secure_mark_group_messages_read' | 'secure_mark_community_messages_read'
  pollCreateRpc: 'secure_create_group_poll_message' | 'secure_create_community_poll_message'
  receiptsRpc: 'secure_get_group_message_receipts' | 'secure_get_community_message_receipts'
  mediaBucket: 'group-media' | 'community-media'
  roomIdParam: 'p_group_id' | 'p_community_id'
  scopeType: 'group' | 'community'
}

const roomConfigByScope: Record<RoomScope, RoomConfig> = {
  group: {
    roomTable: 'groups',
    messageTable: 'group_messages',
    roomForeignKey: 'group_id',
    sendRpc: 'secure_send_group_message',
    reactionRpc: 'secure_toggle_group_message_reaction',
    markReadRpc: 'secure_mark_group_messages_read',
    pollCreateRpc: 'secure_create_group_poll_message',
    receiptsRpc: 'secure_get_group_message_receipts',
    mediaBucket: 'group-media',
    roomIdParam: 'p_group_id',
    scopeType: 'group',
  },
  community: {
    roomTable: 'communities',
    messageTable: 'community_messages',
    roomForeignKey: 'community_id',
    sendRpc: 'secure_send_community_message',
    reactionRpc: 'secure_toggle_community_message_reaction',
    markReadRpc: 'secure_mark_community_messages_read',
    pollCreateRpc: 'secure_create_community_poll_message',
    receiptsRpc: 'secure_get_community_message_receipts',
    mediaBucket: 'community-media',
    roomIdParam: 'p_community_id',
    scopeType: 'community',
  },
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

function normalizeReactions(input: unknown): Record<string, string[]> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return {}
  const result: Record<string, string[]> = {}
  for (const [emoji, users] of Object.entries(input as Record<string, unknown>)) {
    if (!Array.isArray(users)) continue
    result[emoji] = users.map((entry) => String(entry))
  }
  return result
}

function toBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') return value.toLowerCase() === 'true'
  if (typeof value === 'number') return value === 1
  return false
}

function normalizeStringArray(input: unknown): string[] {
  if (!Array.isArray(input)) return []
  return input.map((value) => String(value).trim()).filter((value) => value.length > 0)
}

function isViewOnceType(contentType: string): boolean {
  return contentType === 'view_once_image' || contentType === 'view_once_video'
}

function reactionCount(reactions: Record<string, string[]>, emoji: string): number {
  return reactions[emoji]?.length ?? 0
}

function toMillis(value: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function previewUrlForFile(file: File): string {
  if (file.type.startsWith('image/') || file.type.startsWith('video/') || file.type.startsWith('audio/')) {
    return URL.createObjectURL(file)
  }
  return ''
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

function mapRoomMessage(row: GenericRow): RoomMessage {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    senderId: textOf(row, ['sender_id'], ''),
    content: textOf(row, ['content'], ''),
    contentType: textOf(row, ['content_type'], 'text'),
    mediaUrl: textOf(row, ['media_url'], ''),
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

function mapReceipt(row: GenericRow): MessageReceipt {
  return {
    userId: textOf(row, ['user_id'], ''),
    username: textOf(row, ['username', 'full_name'], 'Usuario'),
    readAt: textOf(row, ['read_at'], ''),
    deliveredAt: textOf(row, ['delivered_at'], ''),
  }
}

type RoomChatPageProps = {
  focusRequest?: number
  scope: RoomScope
  roomId: string
  embedded?: boolean
}

export function RoomChatPage({ scope, roomId, embedded = false, focusRequest = 0 }: RoomChatPageProps) {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const config = roomConfigByScope[scope]
  const cleanRoomId = useMemo(() => roomId.trim().toLowerCase(), [roomId])
  const [roomAvatar, setRoomAvatar] = useState('')
  const [publicationAllowed, setCanPublish] = useState(true)
  const [adminsOnly, setAdminsOnly] = useState(false)
  const [roomCreatorId, setRoomCreatorId] = useState('')
  const [title, setTitle] = useState(scope === 'group' ? 'Grupo' : 'Comunidad')
  const [isMember, setIsMember] = useState(true)
  const [showInfo, setShowInfo] = useState(false)
  const [participants, setParticipants] = useState<Array<{ id: string; name: string; avatarUrl: string; role: string; username?: string }>>([])
  const myRole = participants.find(person => person.id === user?.id)?.role || ''
  const isRoomAdmin = ['owner', 'creator', 'admin'].includes(myRole) || roomCreatorId === user?.id
  const canPublish = publicationAllowed && (!adminsOnly || isRoomAdmin)
  const [mentionCandidates, setMentionCandidates] = useState<MentionCandidate[]>([])
  const [messages, setMessages] = useState<RoomMessage[]>([])
  const messagesSnapshotRef = useRef(messages)
  messagesSnapshotRef.current = messages
  const [hasOlderMessages, setHasOlderMessages] = useState(false)
  const [loadingOlderMessages, setLoadingOlderMessages] = useState(false)
  const [draft, setDraft] = useConversationDraft(user?.id, scope, cleanRoomId)
  const draftRevisionRef = useRef(0)
  const sendInFlightRef = useRef(false)
  const [replyToId, setReplyToId] = useState<string | null>(null)
  const [forwardMessages, setForwardMessages] = useState<RoomMessage[]>([])
  const [replyJumpId, setReplyJumpId] = useState<string | null>(null)
  useMessageFocusFromUrl(messages.length > 0, setReplyJumpId)

  const [editingMessageId, setEditingMessageId] = useState<string | null>(null)
  const [editingDraft, setEditingDraft] = useState('')
  const composerInputRef = useRef<HTMLInputElement | null>(null)
  const beginReply = useCallback((messageId: string) => {
    setEditingMessageId(null)
    setEditingDraft('')
    setReplyToId(messageId)
    composerInputRef.current?.focus({ preventScroll: true })
  }, [])
  const [pollQuestion, setPollQuestion] = useState('')
  const [pollOptions, setPollOptions] = useState('Si\nNo')
  const [showPollComposer, setShowPollComposer] = useState(false)
  const [pollAllowsMultiple, setPollAllowsMultiple] = useState(false)
  const [creatingPoll, setCreatingPoll] = useState(false)
  const [pollStatesById, setPollStatesById] = useState<Record<string, PollState[]>>({})
  const [consumedViewOnce, setConsumedViewOnce] = useState<Record<string, true>>({})
  const [viewOnceLease, setViewOnceLease] = useState<ViewOnceLease | null>(null)
  const viewOncePending = useRef(false)
  const [receiptsByMessage, setReceiptsByMessage] = useState<Record<string, MessageReceipt[]>>({})
  const [loading, setLoading] = useState(true)
  useChatEntryFocus(composerInputRef, user?.id ? `${user.id}/${scope}/${cleanRoomId}` : '', !loading && isMember && canPublish, focusRequest)
  const [sending, setSending] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [showStickers, setShowStickers] = useState(false)
  const [isRecordingAudio, setIsRecordingAudio] = useState(false)
  const [dragActive, setDragActive] = useState(false)
  const [pendingAttachments, setPendingAttachments] = useAttachmentDraft<PendingAttachment>(user?.id || '', scope, cleanRoomId)
  const [imageEditorFile, setImageEditorFile] = useState<File | null>(null)
  const [spellingCaret, setSpellingCaret] = useState<number|undefined>()
  const [galleryMessageId, setGalleryMessageId] = useState<string | null>(null)
  const discardRecordingRef = useRef(false)
  const preparationOwnerRef = useRef('')
  preparationOwnerRef.current = `${user?.id}/${scope}/${cleanRoomId}`
  const [showMessageSearch, setShowMessageSearch] = useState(false)
  const [messageSearchQuery, setMessageSearchQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [showQuickTools, setShowQuickTools] = useState(false)
  const [showScrollToBottom, setShowScrollToBottom] = useState(false)
  const normalizedMessageSearch = useMemo(() => messageSearchQuery.trim().toLowerCase(), [messageSearchQuery])
  const filteredMessages = useMemo(() => {
    if (!normalizedMessageSearch) return messages
    return messages.filter((message) => {
      const haystack = `${message.content} ${message.contentType} ${message.mediaUrl}`.toLowerCase()
      return haystack.includes(normalizedMessageSearch)
    })
  }, [messages, normalizedMessageSearch])
  const [historyWindowSize, setHistoryWindowSize] = useState(CONVERSATION_PAGE_SIZE)
  useEffect(() => setHistoryWindowSize(CONVERSATION_PAGE_SIZE), [cleanRoomId])
  const renderedMessages = useMemo(() => conversationWindow(filteredMessages, historyWindowSize, Boolean(normalizedMessageSearch)), [filteredMessages, historyWindowSize, normalizedMessageSearch])
  const albums = useMemo(() => chatMediaAlbums(renderedMessages, user?.id || ''), [renderedMessages, user?.id])
  const gallery = useMemo(() => chatGallery(messages, user?.id || ''), [messages, user?.id])
  const galleryIndex = galleryMessageId ? gallery.findIndex(item => item.id === galleryMessageId) : null
  const openGallery = (message: { id: string }) => setGalleryMessageId(message.id)
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

  const replyToMessage = replyToId ? messageById.get(replyToId) ?? null : null
  const threadRef = useRef<HTMLElement | null>(null)
  const loadingOlderMessagesRef = useRef(false)
  const historyCursorRef = useRef<string | null>(null)
  const expandedHistoryRef = useRef(false)
  const historyGenerationRef = useRef(0)
  const loadOlderMessagesRef = useRef<() => void>(() => {})
  const messageSearchInputRef = useRef<HTMLInputElement | null>(null)
  const shouldStickToBottomRef = useRef(true)
  const forceScrollToBottomRef = useRef(true)
  const pendingInitialBottomSnapRef = useRef(true)
  const audioRecorderRef = useRef<MediaRecorder | null>(null)
  const audioStreamRef = useRef<MediaStream | null>(null)
  const audioChunksRef = useRef<Blob[]>([])
  const pollIdentity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const pollContext = useRef(0)
  useEffect(() => {
    setPollQuestion('')
    setPollOptions('Si\nNo')
    setPollAllowsMultiple(false)
    setShowPollComposer(false)
    setCreatingPoll(false)
    return () => { pollContext.current += 1 }
  }, [user?.id, useUbuntu, cleanRoomId, scope, pollIdentity])
  const deletionContext = useRef(0)
  useEffect(() => () => { deletionContext.current += 1 }, [user?.id, useUbuntu, cleanRoomId, scope])
  const loadSequenceRef = useRef(0)
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
  }, [cleanRoomId, scope, user?.id])
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

  const upsertMessageInState = useCallback((nextMessage: RoomMessage) => {
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
    setReceiptsByMessage((current) => {
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
  const { start: startPreparation, cancel: cancelPreparation, preparing: preparingAttachments, status: preparationStatus } = useAttachmentPreparation(user?.id || '', scope, cleanRoomId, queueAttachmentFiles, setError)

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
  }, [user?.id, cleanRoomId, stopAudioStream])

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

  const loadPollStates = useCallback(async (rows: RoomMessage[]) => {
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
    setPollStatesById((current) => ({
      ...current,
      [pollId]: ((data ?? []) as GenericRow[]).map(mapPollState),
    }))
  }, [useUbuntu])

  const refreshMessageById = useCallback(
    async (messageId: string) => {
      if (useUbuntu) return
      if (!supabase || !messageId || !cleanRoomId) return
      const { data, error } = await supabase
        .from(config.messageTable)
        .select('*')
        .eq(config.roomForeignKey, cleanRoomId)
        .eq('id', messageId)
        .limit(1)
      if (error) return
      const row = ((data ?? []) as GenericRow[])[0]
      if (!row) {
        removeMessageFromState(messageId)
        return
      }
      const mapped = mapRoomMessage(row)
      upsertMessageInState(mapped)
      if (mapped.pollId) {
        void refreshPollState(mapped.pollId)
      }
    },
    [cleanRoomId, config.messageTable, config.roomForeignKey, refreshPollState, removeMessageFromState, upsertMessageInState, useUbuntu],
  )

  const loadConsumedViewOnce = useCallback(
    async (rows: RoomMessage[]) => {
      if (useUbuntu) {
        setConsumedViewOnce({})
        return
      }
      if (!supabase || !user) return
      const ids = rows.filter((message) => isViewOnceType(message.contentType)).map((message) => message.id)
      if (ids.length === 0) {
        return
      }
      const { data, error: consumeError } = await supabase
        .from('message_view_once_consumptions')
        .select('message_id')
        .eq('scope_type', config.scopeType)
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
    [config.scopeType, useUbuntu, user],
  )

  const markAsRead = useCallback(async () => {
    if ((!supabase && !useUbuntu) || !user || !cleanRoomId) return
    const readToken=markInboxConversationRead(user.id,scope,cleanRoomId)
    try{await backendRpc(config.markReadRpc, { [config.roomIdParam]: cleanRoomId })}
    finally{confirmInboxConversationRead(user.id,scope,cleanRoomId,readToken)}
  }, [cleanRoomId, config.markReadRpc, config.roomIdParam, scope, useUbuntu, user])

  const loadRoom = useCallback(async (options?: { silent?: boolean }) => {
    const loadSequence = ++loadSequenceRef.current
    const idsAtRequestStart = new Set(messagesSnapshotRef.current.map(message => message.id))
    const isStaleLoad = () => loadSequenceRef.current !== loadSequence
    if (!user || !cleanRoomId) {
      if (!isStaleLoad()) {
        setLoading(false)
      }
      return
    }
    if (!options?.silent) setLoading(true)
    setLoadError(null)
    try {
      if (useUbuntu) {
        const response = await loadFallbackRoomChat(scope, cleanRoomId, options?.silent ? undefined : cached => {
          if (isStaleLoad()) return
          for (const message of cached.messages) idsAtRequestStart.add(String(message.id))
          setTitle(cached.title || (scope === 'group' ? 'Grupo' : 'Comunidad'))
          setRoomAvatar(resolveSupabasePublicUrl(scope === 'group' ? 'groups' : 'communities', conversationAvatarSource(cached.room || cached.chat || {})))
          setParticipants(cached.participants || [])
          setMessages(current => mergeMessageSnapshot(current, cached.messages.map(mapRoomMessage)))
          historyCursorRef.current = cached.historyPage?.nextCursor || null
          setHasOlderMessages(Boolean(cached.historyPage?.hasMore))
          setLoading(false)
        }, CONVERSATION_PAGE_SIZE)
        if (isStaleLoad()) return
        setIsMember(response.historyAccess?.isMember !== false)
        setAdminsOnly(Boolean(response.room?.only_admins_can_talk))
        setRoomCreatorId(textOf(response.room || {}, ['creator_id', 'owner_id'], ''))
        setParticipants(response.participants || [])
        setMentionCandidates((response.participants || []).map(person => ({ ...person, username: person.username || "" })))
        setRoomAvatar(resolveSupabasePublicUrl(scope === 'group' ? 'groups' : 'communities',conversationAvatarSource(response.room || response.chat || {})))
        setTitle(response.title || (scope === 'group' ? 'Grupo' : 'Comunidad'))
        const mappedMessages = (response.messages ?? []).map(mapRoomMessage)
        if (!expandedHistoryRef.current) {
          historyCursorRef.current = response.historyPage?.nextCursor || null
          setHasOlderMessages(Boolean(response.historyPage?.hasMore))
        }
        setMessages(current => options?.silent || expandedHistoryRef.current
          ? mergeLatestMessagePage(current, mappedMessages, Boolean(response.historyPage?.hasMore), idsAtRequestStart)
              .filter(message => ['pending', 'failed'].includes(message.status) || fallbackHistoryAllows(response, message.createdAt))
          : mergeMessageSnapshot(current, mappedMessages))
        await loadPollStates(mappedMessages)
        if (!isStaleLoad() && response.historyAccess?.isMember !== false && navigator.onLine && document.visibilityState === 'visible') void markAsRead().catch(() => {})
        setConsumedViewOnce({})
        return
      }

      if (!supabase) {
        if (!isStaleLoad()) setLoadError('Supabase no está configurado.')
        return
      }

      const { data: roomRows, error: roomError } = await supabase
        .from(config.roomTable)
        .select('*')
        .eq('id', cleanRoomId)
        .limit(1)
      if (roomError) throw roomError
      const roomRow = ((roomRows ?? []) as GenericRow[])[0]
      if (!roomRow) {
        if (!isStaleLoad()) {
          setLoadError('No tienes acceso a esta sala o no existe.')
          setLoading(false)
        }
        return
      }
      if (!isStaleLoad()) {
        setRoomAvatar(resolveSupabasePublicUrl(scope === 'group' ? 'groups' : 'communities',conversationAvatarSource(roomRow)))
        setTitle(textOf(roomRow, ['name', 'title', 'readable_id'], scope === 'group' ? 'Grupo' : 'Comunidad'))
        setAdminsOnly(Boolean(roomRow.only_admins_can_talk))
        setRoomCreatorId(textOf(roomRow, ['creator_id', 'owner_id'], ''))
      }

      const { data: messageRows, error: msgError } = await supabase
        .from(config.messageTable)
        .select('*')
        .eq(config.roomForeignKey, cleanRoomId)
        .order('created_at', { ascending: false })
        .order('id', { ascending: false })
        .limit(CONVERSATION_PAGE_SIZE)
      if (msgError) throw msgError

      const newestFirst = (messageRows ?? []) as GenericRow[]
      const mappedMessages = newestFirst.map(mapRoomMessage).reverse()
      if (isStaleLoad()) return
      if (!options?.silent || !expandedHistoryRef.current) setHasOlderMessages(newestFirst.length === CONVERSATION_PAGE_SIZE)
      setMessages(current => options?.silent
        ? mergeLatestMessagePage(current, mappedMessages, newestFirst.length === CONVERSATION_PAGE_SIZE, idsAtRequestStart)
        : mergeMessageSnapshot(current, mappedMessages))
      await Promise.all([loadPollStates(mappedMessages), loadConsumedViewOnce(mappedMessages), markAsRead()])
    } catch (caught) {
      if (!isStaleLoad() && [401, 403, 404].includes(Number((caught as { status?: number })?.status))) setMessages([])
      if (!isStaleLoad() && !options?.silent) {
        setLoadError(friendlyError(caught, 'No se pudo cargar la sala'))
      }
    } finally {
      if (!isStaleLoad()) {
        setLoading(false)
      }
    }
  }, [
    cleanRoomId,
    config.messageTable,
    config.roomForeignKey,
    config.roomTable,
    useUbuntu,
    loadConsumedViewOnce,
    loadPollStates,
    markAsRead,
    scope,
    user,
  ])

  const loadOlderMessages = useCallback(async () => {
    if ((!useUbuntu && !supabase) || !cleanRoomId || loadingOlderMessagesRef.current) return
    const oldest = messages[0]
    if (!oldest?.createdAt || !hasOlderMessages) return
    const cursorDate = new Date(oldest.createdAt)
    if (Number.isNaN(cursorDate.getTime())) return

    const requestRoomId = cleanRoomId
    const requestScope = scope
    const requestLoadSequence = historyGenerationRef.current
    const thread = threadRef.current
    if (!thread) return
    loadingOlderMessagesRef.current = true
    expandedHistoryRef.current = true
    setError(null)
    setLoadingOlderMessages(true)
    shouldStickToBottomRef.current = false
    try {
      let olderPage: RoomMessage[]
      if (useUbuntu) {
        const cursor = historyCursorRef.current
        if (!cursor) return
        const response = await loadFallbackHistoryPage(requestScope, requestRoomId, cursor, CONVERSATION_PAGE_SIZE)
        if (requestLoadSequence !== historyGenerationRef.current) return
        olderPage = response.messages.map(mapRoomMessage)
        historyCursorRef.current = response.historyPage?.nextCursor || null
        setHasOlderMessages(Boolean(response.historyPage?.hasMore))
      } else {
      const cursorTimestamp = oldest.createdAt
      const cursorId = oldest.id
      const safeId = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(cursorId)
        ? cursorId
        : ''
      let query = supabase!.from(config.messageTable).select('*').eq(config.roomForeignKey, requestRoomId)
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
      olderPage = descendingRows.map(mapRoomMessage).reverse()
      setHasOlderMessages(descendingRows.length === CONVERSATION_PAGE_SIZE)
      }
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
  }, [cleanRoomId, config.messageTable, config.roomForeignKey, hasOlderMessages, loadConsumedViewOnce, loadPollStates, messages, scope, useUbuntu])
  loadOlderMessagesRef.current = () => { void loadOlderMessages() }

  useEffect(() => {
    if ((showMessageSearch || galleryMessageId || replyJumpId && !messageById.has(replyJumpId)) && hasOlderMessages && !loadingOlderMessages && !error) {
      loadOlderMessagesRef.current()
    }
  }, [showMessageSearch, galleryMessageId, replyJumpId, messageById, hasOlderMessages, loadingOlderMessages, error])

  useEffect(() => {
    void loadRoom()
  }, [loadRoom])

  useConversationRecovery(Boolean(useUbuntu && user && cleanRoomId && isMember), () => loadRoom({ silent: true }))

  useEffect(() => {
    forceScrollToBottomRef.current = true
    pendingInitialBottomSnapRef.current = true
    shouldStickToBottomRef.current = true
    setShowScrollToBottom(false)
    setShowMessageSearch(false)
    setMessageSearchQuery('')
  }, [cleanRoomId])

  useEffect(() => {
    if (showMessageSearch) return
    if (!messages.length && !forceScrollToBottomRef.current) return
    const shouldScroll = forceScrollToBottomRef.current || shouldStickToBottomRef.current
    if (!shouldScroll) return
    const behavior: ScrollBehavior = forceScrollToBottomRef.current ? 'auto' : 'smooth'
    const frame = window.requestAnimationFrame(() => {
      scrollThreadToBottom(behavior)
      forceScrollToBottomRef.current = false
    })
    return () => window.cancelAnimationFrame(frame)
  }, [messages, scrollThreadToBottom, showMessageSearch])

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
    if (!cleanRoomId || !user) return
    if (useUbuntu) return
    const syncReadState = () => {
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
      void markAsRead()
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
  }, [cleanRoomId, markAsRead, useUbuntu, user])

  useEffect(() => {
    const client = supabase
    if (!client || !user || !cleanRoomId || useUbuntu) return
    const channel = client
      .channel(`${scope}-live-${cleanRoomId}`)
      .on(
        'postgres_changes',
        {
          event: '*',
          schema: 'public',
          table: config.messageTable,
          filter: `${config.roomForeignKey}=eq.${cleanRoomId}`,
        },
        (payload) => {
          const row = ((payload.eventType === 'DELETE' ? payload.old : payload.new) ?? {}) as GenericRow
          const messageId = textOf(row, ['id'], '')
          if (!messageId) return
          if (payload.eventType === 'DELETE') {
            removeMessageFromState(messageId)
            return
          }
          const mapped = mapRoomMessage(row)
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
      .subscribe()
    return () => {
      void client.removeChannel(channel)
    }
  }, [
    cleanRoomId,
    config.messageTable,
    config.roomForeignKey,
    refreshPollState,
    removeMessageFromState,
    useUbuntu,
    scope,
    upsertMessageInState,
    user,
  ])

  useEffect(() => {
    if (!useUbuntu || !user || !cleanRoomId || !isMember) return
    const unsubscribe = subscribeUbuntuRealtime({
      channel: `chat:room:${scope}:${cleanRoomId}`,
      onEvent: (event) => {
        const row = (event.payload.message || event.payload.row) as GenericRow | undefined
        if (row && textOf(row, ['id'], '')) upsertMessageInState(mapRoomMessage(row))
        else void loadRoom({ silent: true })
      },
    })
    return unsubscribe
  }, [cleanRoomId, isMember, loadRoom, scope, upsertMessageInState, useUbuntu, user])

  useEffect(() => {
    const update = () => { void loadRoom({ silent: true }) }
    window.addEventListener('novvin:outbox-changed', update)
    return () => window.removeEventListener('novvin:outbox-changed', update)
  }, [loadRoom])

  async function sendMessage(
    content: string,
    mediaUrl = '',
    contentType = 'text',
    replyToMessageId: string | null = null,
    isForwarded = false,
    forwardedFromMessageId: string | null = null,
    forwardedFromUserId: string | null = null,
  ) {
    if (!user || !cleanRoomId) return
    forceScrollToBottomRef.current = true
    scrollThreadToBottom('auto')
    if (useUbuntu) {
      const response = await sendFallbackRoomMessage(scope, cleanRoomId, {
        content,
        contentType,
        mediaUrl: mediaUrl || null,
        replyToId: replyToMessageId,
        isForwarded,
        forwardedFromMessageId,
        forwardedFromUserId,
      })
      forceScrollToBottomRef.current = true
      upsertMessageInState(mapRoomMessage(response.message))
      return
    }
    if (!supabase) return
    const payload: Record<string, unknown> = {
      [config.roomIdParam]: cleanRoomId,
      p_content: content,
      p_content_type: contentType,
      p_media_url: mediaUrl || null,
      p_reply_to_id: replyToMessageId,
      p_is_forwarded: isForwarded,
      p_forwarded_from_message_id: forwardedFromMessageId,
      p_forwarded_from_user_id: forwardedFromUserId,
    }
    const { error: rpcError } = await supabase.rpc(config.sendRpc, payload)
    if (!rpcError) return

    const fallbackPayload: Record<string, unknown> = {
      [config.roomForeignKey]: cleanRoomId,
      sender_id: user.id,
      content,
      content_type: contentType,
      media_url: mediaUrl || null,
      reply_to_id: replyToMessageId,
      status: 'sent',
    }
    const { error: insertError } = await supabase.from(config.messageTable).insert(fallbackPayload)
    if (insertError) throw insertError
  }

  async function sendPendingAttachments(textFallback: string) {
    if (!user || !cleanRoomId || pendingAttachments.length === 0) return
    if (!useUbuntu && !supabase) return
    const owner = preparationOwnerRef.current
    const queued = [...pendingAttachments]
    setUploading(true)
    try {
      for (let index = 0; index < queued.length; index += 1) {
        const attachment = queued[index]
        const animated = attachment.contentType === 'sticker' && await animatedImageFile(attachment.file)
        if (owner !== preparationOwnerRef.current) return
        const safeName = attachment.file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
        const filePath = `${scope}/${user.id}/${Date.now()}_${index}_${safeName}`
        const publicUrl = useUbuntu
          ? (await uploadFallbackFile(attachment.contentType === 'sticker' ? 'chat-media' : config.mediaBucket, attachment.file, filePath, undefined,
              attachment.contentType === 'sticker' ? { id: crypto.randomUUID(), title: attachment.caption.trim() || 'Sticker', isAnimated: animated } : undefined)).publicUrl
          : await (async () => {
              const { error: uploadError } = await supabase!.storage
                .from(config.mediaBucket)
                .upload(filePath, attachment.file, { contentType: attachment.file.type || undefined })
              if (uploadError) throw uploadError
              const { data } = supabase!.storage.from(config.mediaBucket).getPublicUrl(filePath)
              return data.publicUrl
            })()
        if (owner !== preparationOwnerRef.current) return
        const caption =
          attachment.caption.trim() || (index === 0 ? textFallback.trim() : '') || (attachment.contentType === 'sticker' ? 'Sticker' : attachment.file.name)
        await sendMessage(caption, publicUrl, attachment.contentType, index === 0 ? replyToId : null)
        setPendingAttachments((current) => current.filter((entry) => entry.id !== attachment.id))
        if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl)
      }
      setReplyToId(null)
    } finally {
      setUploading(false)
    }
  }

  async function handleSend(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!isMember) return
    if (!user || !cleanRoomId || (!useUbuntu && !supabase)) {
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
    sendInFlightRef.current = true
    setError(null)
    setSending(true)
    try {
      if (editingMessageId) {
        const response = useUbuntu
          ? await editFallbackRoomMessage(scope, cleanRoomId, editingMessageId, text)
          : null
        if (sendOwner !== preparationOwnerRef.current) return
        loadSequenceRef.current += 1
        if (response) {
          upsertMessageInState(mapRoomMessage(response.message))
        } else if (supabase && user) {
          const nowIso = new Date().toISOString()
          const { data: editedRow, error: editError } = await supabase
            .from(config.messageTable)
            .update({ content: text, is_edited: true, updated_at: nowIso })
            .eq('id', editingMessageId)
            .eq(config.roomForeignKey, cleanRoomId)
            .eq('sender_id', user.id)
            .select('id').maybeSingle()
          if (editError) throw editError
          if (!editedRow) throw new Error('No se pudo guardar: el mensaje ya no está disponible para editar.')
          if (sendOwner !== preparationOwnerRef.current) return
          loadSequenceRef.current += 1
          setMessages((current) => current.map((entry) => entry.id === editingMessageId
            ? { ...entry, content: text, isEdited: true, updatedAt: nowIso }
            : entry))
        }
        setEditingMessageId(null)
        setEditingDraft('')
      } else if (pendingAttachments.length > 0) {
        await sendPendingAttachments(text)
        setDraft((current) => current === submittedDraft ? '' : current)
      } else {
        await sendMessage(text, '', 'text', replyToId)
        if (draftRevisionRef.current === draftRevisionAtSend) setDraft(current => current === submittedDraft ? '' : current)
        setReplyToId(null)
      }
    } catch (caught) {
      if (sendingTextOnly && draftRevisionRef.current === draftRevisionAtSend) {
        setDraft(submittedDraft)
      }
      if (sendOwner === preparationOwnerRef.current) setError(friendlyError(caught, editingMessageId ? 'No se pudo guardar la corrección. Tu texto sigue aquí para volver a intentarlo.' : 'No se pudo enviar el mensaje'))
    } finally {
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
    if ((!supabase && !useUbuntu) || !user || !isMember) return
    const owner = preparationOwnerRef.current
    const { data, error: reactionError } = await backendRpc(config.reactionRpc, { p_message_id: messageId, p_emoji: emoji })
    if (owner !== preparationOwnerRef.current) return
    if (reactionError) { setError(friendlyError(reactionError, 'No se pudo reaccionar')); return }
    if (useUbuntu) {
      const reactions = normalizeReactions((data as GenericRow | null)?.reactions)
      setMessages(current => current.map(message => message.id === messageId ? { ...message, reactions } : message))
    } else await refreshMessageById(messageId)
  }

  async function handleAttachViewOnceFile(file: File | null) {
    if (!user || !cleanRoomId || !file) return
    if (!useUbuntu && !supabase) return
    setUploading(true)
    try {
      const contentType = inferContentType(file)
      if (contentType !== 'image' && contentType !== 'video') {
        throw new Error('View-once solo permite imagen o video')
      }
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `${scope}/view-once/${user.id}/${Date.now()}_${safeName}`
      const publicUrl = useUbuntu
        ? (await uploadFallbackFile(config.mediaBucket, file, filePath)).publicUrl
        : await (async () => {
            const { error: uploadError } = await supabase!.storage
              .from(config.mediaBucket)
              .upload(filePath, file, { contentType: file.type || undefined })
            if (uploadError) throw uploadError
            const { data } = supabase!.storage.from(config.mediaBucket).getPublicUrl(filePath)
            return data.publicUrl
          })()
      await sendMessage(
        file.name,
        publicUrl,
        contentType === 'image' ? 'view_once_image' : 'view_once_video',
        replyToId,
      )
      setReplyToId(null)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar view-once'))
    } finally {
      setUploading(false)
    }
  }

  async function createPoll() {
    if (!user || (!supabase && !useUbuntu) || !cleanRoomId || creatingPoll) return
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
      const payload: Record<string, unknown> = {
        [config.roomIdParam]: cleanRoomId,
        p_message_id: crypto.randomUUID(),
        p_question: question,
        p_options: options,
        p_allows_multiple: pollAllowsMultiple,
      }
      const { error: pollError } = await backendRpc(config.pollCreateRpc, payload)
      if (!isCurrent()) return
      if (pollError) throw pollError
      setPollQuestion('')
      setPollOptions('Si\nNo')
      setPollAllowsMultiple(false)
      setShowPollComposer(false)
      void loadRoom({ silent: true })
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
      setPollStatesById((current) => ({
        ...current,
        [pollId]: ((data ?? []) as GenericRow[]).map(mapPollState),
      }))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo votar'))
    }
  }

  async function consumeViewOnce(message: RoomMessage) {
    if (!isViewOnceType(message.contentType) || viewOncePending.current || viewOnceLease) return
    viewOncePending.current = true
    try {
      const lease = await requestViewOnceMedia({ scope: config.scopeType, messageId: message.id, deviceId, kind: message.contentType === 'view_once_video' ? 'video' : 'image', useUbuntu })
      setConsumedViewOnce(current => ({ ...current, [message.id]: true }))
      setViewOnceLease(lease)
    } catch (caught) {
      if (caught instanceof Error && caught.message === 'VIEW_ONCE_CONSUMED') setConsumedViewOnce(current => ({ ...current, [message.id]: true }))
      setError(friendlyError(caught, 'No se pudo abrir el archivo de una sola visualización'))
    } finally { viewOncePending.current = false }
  }

  async function sendForwardedMessage(message: RoomMessage) { setForwardMessages([message]) }

  async function loadMessageReceipts(messageId: string) {
    if ((!supabase && !useUbuntu) || !messageId) return
    const owner = preparationOwnerRef.current
    try {
      const { data, error: receiptsError } = await backendRpc(config.receiptsRpc, { p_message_id: messageId })
      if (owner !== preparationOwnerRef.current) return
      if (receiptsError) throw receiptsError
      setReceiptsByMessage((current) => ({
        ...current,
        [messageId]: ((data ?? []) as GenericRow[]).map(mapReceipt),
      }))
    } catch (caught) {
      if (owner === preparationOwnerRef.current) setError(friendlyError(caught, 'No se pudieron cargar recibos'))
    }
  }

  if (!supabase && !useUbuntu) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL para usar mensajes con Ubuntu." />
  }

  async function deleteRoomMessage(message: RoomMessage, forEveryone: boolean) {
    if (!user) return
    const context = deletionContext.current
    const identity = getStorageReadIdentity()
    if (useUbuntu && (identity.provider !== 'fallback' || identity.accountId !== user.id)) return
    const isCurrent = () => context === deletionContext.current && identity === getStorageReadIdentity()
    try {
      if (useUbuntu) {
        const response = await deleteFallbackRoomMessage(scope, cleanRoomId, message.id, forEveryone)
        if (isCurrent()) upsertMessageInState(mapRoomMessage(response.message))
        return
      }
      if (!supabase) return
      const nowIso = new Date().toISOString()
      if (forEveryone) {
        const { error: deleteError } = await supabase
          .from(config.messageTable)
          .update({ deleted_for_all: true, content: '', media_url: null, updated_at: nowIso })
          .eq('id', message.id)
          .eq('sender_id', user.id)
        if (deleteError) throw deleteError
        if (!isCurrent()) return
        setMessages((current) => current.map((entry) => entry.id === message.id
          ? { ...entry, deletedForAll: true, content: '', mediaUrl: '', updatedAt: nowIso }
          : entry))
      } else {
        const deletedBy = Array.from(new Set([...message.deletedBy, user.id]))
        const { error: deleteError } = await supabase
          .from(config.messageTable)
          .update({ deleted_by: deletedBy, updated_at: nowIso })
          .eq('id', message.id)
        if (deleteError) throw deleteError
        if (!isCurrent()) return
        setMessages((current) => current.map((entry) => entry.id === message.id
          ? { ...entry, deletedBy, updatedAt: nowIso }
          : entry))
      }
    } catch (caught) {
      if (isCurrent()) setError(friendlyError(caught, forEveryone ? 'No se pudo borrar para todos' : 'No se pudo borrar para mi'))
    }
  }

  async function importRoomSticker(source: File | null) {
    if (!source || !user || !cleanRoomId) return
    const owner = preparationOwnerRef.current
    const [file] = await normalizeClipboardFiles([source])
    if (owner !== preparationOwnerRef.current) return
    if (!isStickerImage(file)) {
      setError('El sticker debe ser una imagen PNG, WEBP, GIF, JPG o JPEG.')
      return
    }
    setUploading(true)
    try {
      const isAnimated = await animatedImageFile(file)
      if (owner !== preparationOwnerRef.current) return
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `${scope}/stickers/${user.id}/${Date.now()}_${safeName}`
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
      await sendMessage(file.name.replace(/\.[^.]+$/, '') || 'Sticker', mediaUrl, 'sticker', replyToId)
      setReplyToId(null)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo importar el sticker'))
    } finally {
      setUploading(false)
    }
  }

  if (loading) {
    return <StatusView title="Cargando sala" description="Espera un momento..." />
  }

  if (loadError) {
    return (
      <section className="section-stack">
        {!embedded ? (
          <Link to="/app/messages" className="inline-link">
            ← Volver a mensajes
          </Link>
        ) : null}
        <StatusView title="No se pudo abrir la sala" description={loadError} />
      </section>
    )
  }

  const rootClassName = [
    embedded ? 'section-stack chat-detail-embedded room-chat-page' : 'section-stack room-chat-page',
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
          <strong>Suelta archivos para {scope === 'group' ? 'el grupo' : 'la comunidad'}</strong>
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
          {useUbuntu ? <button type="button" className="chat-room-identity" aria-label={`Información de ${title}`} onClick={()=>setShowInfo(true)}><SafeAvatar url={roomAvatar} name={title} size="md"/><span><strong>{title}</strong><small>{participants.length ? `${participants.length} miembros` : scope==='group'?'Grupo':'Comunidad'}</small></span></button> : <h2>{title}</h2>}
        </div>
        <div className="row-actions chat-head-actions">
          <button
            className={showMessageSearch ? 'icon-btn header-icon-btn active' : 'icon-btn header-icon-btn'}
            type="button"
            title="Buscar en la conversación" aria-label="Buscar en la conversación" aria-expanded={showMessageSearch}
            onClick={() => {
              setShowMessageSearch((current) => !current)
              if (showMessageSearch) {
                setMessageSearchQuery('')
              } else {
                setShowQuickTools(false)
              }
            }}
          >
            <Search size={20} aria-hidden="true" />
          </button>
          {useUbuntu && isMember ? <CommunityRaffles key={`raffles:${user?.id}:${scope}:${cleanRoomId}`} scope={scope} roomId={cleanRoomId}/> : null}
          {useUbuntu && isMember ? <CollectionQuestionTools key={`${user?.id}:${scope}:${cleanRoomId}`} scope={scope} roomId={cleanRoomId} onCreated={() => void loadRoom({silent:true})} onPolicy={setCanPublish}/> : null}
          <button
            className={showQuickTools ? 'icon-btn header-icon-btn active' : 'icon-btn header-icon-btn'}
            type="button"
            title="Opciones de la conversación" aria-label="Opciones de la conversación" aria-expanded={showQuickTools} aria-controls="room-chat-tools"
            onClick={() => setShowQuickTools((current) => !current)}
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
            aria-label="Buscar en esta conversación"
            placeholder={`Buscar en ${scope === 'group' ? 'grupo' : 'comunidad'}...`}
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

      {showQuickTools ? (
        <section id="room-chat-tools" className="surface-subcard chat-inline-tools">
          <div className="row-between">
            <strong>Herramientas</strong>
            <button className="mini-action-btn" type="button" onClick={() => setShowQuickTools(false)}>
              Cerrar
            </button>
          </div>
          <div className="row-actions wrap">
            <button className="ghost-btn-sm" type="button" onClick={() => setShowPollComposer((current) => !current)}>
              {showPollComposer ? 'Ocultar encuesta' : 'Nueva encuesta'}
            </button>
          </div>
        </section>
      ) : null}

      {showPollComposer ? (
        <article className="surface-subcard section-stack">
          <h3>Encuesta en {scope === 'group' ? 'grupo' : 'comunidad'}</h3>
          <input
            className="text-input tiny-input"
            placeholder="Pregunta"
            value={pollQuestion}
            onChange={(event) => setPollQuestion(event.target.value)}
          />
          <textarea
            className="text-input textarea-input"
            rows={3}
            value={pollOptions}
            onChange={(event) => setPollOptions(event.target.value)}
          />
          <label className="checkbox-inline">
            <input
              type="checkbox"
              checked={pollAllowsMultiple}
              onChange={(event) => setPollAllowsMultiple(event.target.checked)}
            />
            Permitir multiples
          </label>
          <button className="primary-btn-sm" type="button" disabled={creatingPoll} onClick={() => void createPoll()}>
            {creatingPoll ? 'Creando...' : 'Crear encuesta'}
          </button>
        </article>
      ) : null}

      {forwardMessages.length ? <ForwardMessagesDialog messages={forwardMessages} onClose={() => setForwardMessages([])} /> : null}
      <div className="chat-thread-shell" inert={pendingAttachments.length > 0}>
        <section className="chat-thread" ref={threadRef} onScroll={handleThreadScroll}>
          {hasOlderMessages ? <button type="button" className="ghost-btn" disabled={loadingOlderMessages} onClick={() => loadOlderMessagesRef.current()}>
            {loadingOlderMessages ? 'Cargando mensajes…' : 'Ver mensajes anteriores'}
          </button> : renderedMessages.length < filteredMessages.length ? <button type="button" className="ghost-btn" onClick={() => {
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
              <small className="muted-line">Todavía no hay mensajes en esta sala.</small>
            </div>
          ) : renderedMessages.map((message, index) => {
          const previousMessage = index > 0 ? renderedMessages[index - 1] : null
          const showDaySeparator =
            !previousMessage || dayKeyOf(previousMessage.createdAt) !== dayKeyOf(message.createdAt)
          const deletedForMe = user ? message.deletedBy.includes(user.id) : false
          if (deletedForMe) return null
          const album = albums.get(message.id)
          if (album && !replyJumpId && album.leader !== message.id) return null
          if (album && !replyJumpId) return <Fragment key={message.id}>{showDaySeparator ? <div className="chat-day-separator"><span>{formatConversationDay(message.createdAt)}</span></div> : null}<ChatMediaAlbum onPhotoOpen={openGallery} items={album.items} mine={message.senderId === user?.id} onReply={item => beginReply(item.id)} onForward={item => void sendForwardedMessage(item)} onDelete={item => void deleteRoomMessage(item, false)} onDeleteForAll={item => void deleteRoomMessage(item, true)} onReact={(item, emoji) => void toggleReaction(item.id, emoji || '👍')} /></Fragment>
          if (message.contentType === "system_membership") return <div className="room-system-notice" key={message.id}>{message.content}</div>
          const mine = message.senderId === user?.id
          const replied = message.replyToId ? messageById.get(message.replyToId) : null
          const pollRows =
            message.pollId && pollStatesById[message.pollId]
              ? [...pollStatesById[message.pollId]].sort((a, b) => a.displayOrder - b.displayOrder)
              : []
          const pollMeta = pollRows[0]
          const consumed = consumedViewOnce[message.id] === true
          const receipts = receiptsByMessage[message.id] ?? []
          const activeReactions = Object.keys(message.reactions).filter(
            (emoji) => reactionCount(message.reactions, emoji) > 0,
          )
          const inlinePhoto = Boolean(message.mediaUrl && !message.deletedForAll && !['sticker', 'location', 'contact', 'birthday_card', 'poll', 'raffle', 'question'].includes(message.contentType) && !isViewOnceType(message.contentType) && attachmentKind(message.contentType, message.mediaUrl) === 'image')
          const photoTimestamp = inlinePhoto ? <MessageTimestamp createdAt={message.createdAt} mine={mine} status={message.status} edited={message.isEdited} onRetry={() => void retryPendingWebMessage(message.id).catch(error => setError(friendlyError(error)))}/> : undefined
          return (
            <Fragment key={message.id}>
              {showDaySeparator ? (
                <div className="chat-day-separator">
                  <span>{formatConversationDay(message.createdAt)}</span>
                </div>
              ) : null}
              <article id={`message-${message.id}`} onDoubleClick={event => { if (shouldReplyToMessageDoubleClick(event.target)) beginReply(message.id) }} title="Doble clic para responder" className={`${mine ? 'bubble bubble-me' : 'bubble bubble-them'}${message.contentType === 'question' ? ' question-bubble' : ''}${message.contentType === 'sticker' && !message.deletedForAll ? ' sticker-bubble' : ''}${inlinePhoto ? ' image-bubble' : ''}${message.contentType === 'text' && !message.mediaUrl && !message.replyToId && !message.isForwarded && !message.deletedForAll && !activeReactions.length && !/https?:/.test(message.content) && !['pending','failed'].includes(message.status) ? ' text-bubble' : ''}`}>
                {message.isForwarded ? <small>Reenviado</small> : null}
                {message.replyToId ? <MessageReplyPreview onJump={setReplyJumpId} message={replied || { id: message.replyToId, content: 'Mensaje anterior', contentType: 'text', mediaUrl: null, deletedForAll: false }} name={replied?.senderId === user?.id ? 'Tú' : participants.find(person => person.id === replied?.senderId)?.name || 'Participante'}/> : null}
                {message.deletedForAll ? (
                  <p className="muted-line">Mensaje eliminado para todos</p>
                ) : sharedVideoReference(message.content) ? (
                  <SharedVideoCard reference={sharedVideoReference(message.content)!} />
                ) : message.contentType === 'raffle' ? (<div><strong>{message.content.split('\n')[0]}</strong><CommunityRaffles scope={scope} roomId={cleanRoomId} raffleId={message.mediaUrl} label="Ver sorteo y resultados"/></div>) : message.contentType === 'question' ? (<CommunityQuestion scope={scope} roomId={cleanRoomId} content={message.content}/>) : message.contentType === 'poll' && pollRows.length > 0 ? (
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
                    {message.contentType === 'contact' ? <ContactMessageCard content={message.content} /> : message.content && message.contentType !== 'sticker' ? <MessageText text={message.content} mentions={mentionCandidates}/> : null}
                    {message.contentType === 'location' && messageLocationUrl(message.mediaUrl) ? <a className="inline-link" href={messageLocationUrl(message.mediaUrl)!} target="_blank" rel="noreferrer">📍 Abrir ubicación</a> : null}
                    {message.mediaUrl && !['location', 'contact'].includes(message.contentType) && !isViewOnceType(message.contentType) ? (
                      message.contentType === 'sticker' ? <MessageSticker url={message.mediaUrl} title={message.content || 'Sticker'} onReply={() => beginReply(message.id)} onForward={() => void sendForwardedMessage(message)} /> : <ChatAttachmentMedia inlineDownload={false} url={message.mediaUrl} kind={attachmentKind(message.contentType, message.mediaUrl)} title={message.content || 'Archivo'} onImageOpen={() => openGallery(message)} overlay={photoTimestamp} />
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
                    <button className="mini-action-btn" type="button" onClick={() => void copyChatMessage(message.content, message.mediaUrl, message.contentType).catch(() => setError('No se pudo copiar. Permite el acceso al portapapeles o descarga la imagen.'))}>Copiar</button>
                    {message.mediaUrl && !['location', 'contact'].includes(message.contentType) && !message.contentType.startsWith('view_once') && !message.mediaUrl.startsWith('blob:') ? <MediaDownloadButton url={message.mediaUrl} name={message.content || 'archivo'} /> : null}
                    <MessageReactionBar onReact={emoji=>void toggleReaction(message.id,emoji)}/>
                    <button className="mini-action-btn" type="button" onClick={() => beginReply(message.id)}>
                      Responder
                    </button>
                    <button className="mini-action-btn" type="button" onClick={() => void sendForwardedMessage(message)}>
                      Reenviar
                    </button>
                    <button className="mini-action-btn" type="button" onClick={() => void loadMessageReceipts(message.id)}>
                      Leidos
                    </button>
                    {mine && message.contentType === 'text' ? (
                      <button className="mini-action-btn" type="button" onClick={() => {
                        setError(null)
                          setEditingMessageId(message.id)
                          requestAnimationFrame(() => document.querySelector<HTMLInputElement>('.chat-input-field')?.focus())
                        draftRevisionRef.current += 1
                        setEditingDraft(message.content)
                      }}>
                        Editar
                      </button>
                    ) : null}
                    {mine ? (
                      <button className="mini-action-btn danger" type="button" onClick={() => void deleteRoomMessage(message, true)}>
                        Borrar todos
                      </button>
                    ) : null}
                    <button className="mini-action-btn" type="button" onClick={() => void deleteRoomMessage(message, false)}>
                      Borrar mi vista
                    </button>
                  </MessageOptionsMenu>
                ) : null}
                {receipts.length > 0 ? (
                  <div className="receipt-stack">
                    {receipts.map((receipt) => (
                      <small key={`${message.id}-${receipt.userId}`}>
                        {receipt.username} · {receipt.readAt ? `Leido ${formatDateTime(receipt.readAt)}` : 'Entregado'}
                      </small>
                    ))}
                  </div>
                ) : null}
                {mine && message.status==='failed' ? <p className="message-delivery-error" role="status">{messageDeliveryFeedback(message.deliveryError)}</p> : null}
                {mine && message.status==='pending' && message.mediaUrl ? <OutboxTransferStatus id={message.id}/> : null}
                {!inlinePhoto ? <MessageTimestamp createdAt={message.createdAt} mine={mine} status={message.status} edited={message.isEdited} onRetry={() => void retryPendingWebMessage(message.id).catch(error => setError(friendlyError(error)))}/> : null}
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
          <button className="mini-action-btn" type="button" onClick={() => {
            setEditingMessageId(null)
            setEditingDraft('')
          }}>
            Cancelar
          </button>
        </div>
      ) : null}

      {showStickers ? <StickerPicker onEmoji={emoji => { draftRevisionRef.current += 1; setDraft(current => current + emoji) }} onSelect={item => {
        void sendMessage(item.title, item.mediaUrl, 'sticker', replyToId).then(() => { setShowStickers(false); setReplyToId(null) }).catch(caught => setError(friendlyError(caught, 'No se pudo enviar el sticker')))
      }} onImport={file => void importRoomSticker(file)} onClose={() => setShowStickers(false)} /> : null}

      {!pendingAttachments.length ? <SpellingSuggestions text={editingMessageId ? editingDraft : draft} caret={spellingCaret} onReplace={(text,caret)=>{draftRevisionRef.current += 1;if(editingMessageId)setEditingDraft(text);else setDraft(text);setSpellingCaret(caret);requestAnimationFrame(()=>{const input=document.querySelector<HTMLInputElement>('.chat-composer .chat-input-field');input?.focus();input?.setSelectionRange(caret,caret)})}}/> : null}
      {error ? <div className="chat-composer-error" role="alert"><span>{error}</span><button className="mini-action-btn" type="button" onClick={() => setError(null)}>Cerrar</button></div> : null}
      {preparingAttachments ? <p className="attachment-preparing" role="status">{preparationStatus}</p> : null}
      {isRecordingAudio ? <RecordingIndicator onStop={() => audioRecorderRef.current?.stop()} onCancel={() => { discardRecordingRef.current = true; audioRecorderRef.current?.stop() }} /> : null}
      {imageEditorFile ? <ChatImageEditor asSticker={pendingAttachments.some(item => item.file === imageEditorFile && item.contentType === 'sticker')} file={imageEditorFile} onClose={() => setImageEditorFile(null)} onSave={file => setPendingAttachments(current => current.map(entry => { if (entry.file !== imageEditorFile) return entry; if (entry.previewUrl) URL.revokeObjectURL(entry.previewUrl); return { ...entry, file, previewUrl: previewUrlForFile(file) } }))} /> : null}
      {galleryIndex !== null && gallery[galleryIndex] ? <MediaViewer items={gallery} initialIndex={galleryIndex} onClose={() => setGalleryMessageId(null)} /> : null}
      {pendingAttachments.length > 0 && isMember && canPublish ? <ChatAttachmentPreview items={pendingAttachments} draft={draft} busy={sending || uploading} preparing={preparingAttachments} status={preparationStatus}
        onClose={() => { cancelPreparation(); clearPendingAttachments() }} onRemove={removePendingAttachment} onAdd={handleAttachFile} onSend={handleSend}
        onCaption={(id, caption) => { updatePendingAttachmentCaption(id, caption); if (pendingAttachments[0]?.id === id) setDraft(caption) }}
        onEdit={id => setImageEditorFile(pendingAttachments.find(item => item.id === id)?.file || null)}
        onToggleSticker={id => setPendingAttachments(current => current.map(item => item.id === id ? { ...item, contentType: item.contentType === 'sticker' ? 'image' : 'sticker' } : item))}
      /> : null}

      {showInfo ? <CollectionInfo scope={scope} roomId={cleanRoomId} title={title} participants={participants} isMember={isMember} onClose={() => setShowInfo(false)} onChanged={() => void loadRoom({ silent: true })} /> : null}
      {isMember ? <MentionSuggestions text={draft} candidates={mentionCandidates} actorId={user?.id} onSelect={username => { draftRevisionRef.current += 1; setDraft(insertMention(draft, username)) }} /> : null}
      {!isMember ? <section className="retained-chat-notice" aria-live="polite">
        <strong>Ya no formas parte de este {scope === 'group' ? 'grupo' : 'comunidad'}.</strong>
        <p>Puedes consultar el historial anterior o borrar este chat de tu lista.</p>
        <button type="button" className="ghost-btn" onClick={async () => {
          try {
            await fallbackApiJson(`/v1/data/messages/${scope === 'group' ? 'groups' : 'communities'}/${encodeURIComponent(cleanRoomId)}/hide`, { method: 'POST', body: '{}' })
            window.dispatchEvent(new Event('novvin:inbox-changed'))
            window.location.assign('/app/messages')
          } catch { setError('No se pudo borrar el chat. Inténtalo de nuevo.') }
        }}>Borrar de mi lista</button>
      </section> : !canPublish ? <div className="composer muted-line"><p>{adminsOnly ? 'Solo los administradores pueden escribir. Puedes reaccionar y responder preguntas.' : 'Solo el creador escribe. Puedes reaccionar, crear encuestas y responder preguntas.'}</p>{!adminsOnly ? <button type="button" className="mini-action-btn" onClick={()=>setShowPollComposer(true)}>Crear encuesta</button> : null}</div> : <form className="composer chat-composer shadow-lg" onSubmit={handleSend}>
        <details className="chat-attachment-menu"><summary className="icon-btn" aria-label="Adjuntar" title="Adjuntar"><Paperclip size={21} aria-hidden="true" /></summary><div className="chat-attachment-popover"><FolderAttachmentPicker onFiles={handleAttachFile}/>
        <button type="button" className="chat-attachment-option" onClick={event => { setShowStickers(true); event.currentTarget.closest('details')?.removeAttribute('open') }}><Sticker size={20} aria-hidden="true" /><span>Buscar stickers</span></button>
        <label className="chat-attachment-option" tabIndex={0} role="button" onKeyDown={event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.currentTarget.querySelector<HTMLInputElement>('input')?.click()}}} title="Importar sticker" aria-label="Importar sticker">
          <Import size={20} aria-hidden="true" /><span>Importar sticker</span>
          <input
            type="file"
            hidden
            accept="image/png,image/webp,image/gif,image/jpeg,.png,.webp,.gif,.jpg,.jpeg"
            onChange={(event) => {
              void importRoomSticker(event.target.files?.[0] ?? null)
              event.currentTarget.value = '';event.currentTarget.closest('details')?.removeAttribute('open')
            }}
          />
        </label>
        <label className="chat-attachment-option" tabIndex={0} role="button" onKeyDown={event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.currentTarget.querySelector<HTMLInputElement>('input')?.click()}}} title="Adjuntar archivo"><span>Fotos, videos y documentos</span>
          <Files size={20} aria-hidden="true" />
          <input
            type="file"
            multiple
            hidden
            onChange={(event) => {
              const files = event.target.files
              handleAttachFile(files)
              event.currentTarget.value = '';event.currentTarget.closest('details')?.removeAttribute('open')
            }}
          />
        </label>



        <label className="chat-attachment-option" tabIndex={0} role="button" onKeyDown={event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.currentTarget.querySelector<HTMLInputElement>('input')?.click()}}} title="Foto o video de una sola vista"><span>Una sola vista</span>
          <CircleDot size={20} aria-hidden="true" />
          <input
            type="file"
            hidden
            accept="image/*,video/*"
            onChange={(event) => {
              const file = event.target.files?.[0] ?? null
              void handleAttachViewOnceFile(file)
              event.currentTarget.value = '';event.currentTarget.closest('details')?.removeAttribute('open')
            }}
          />
        </label>

        <button className="chat-attachment-option" type="button" title="Nueva encuesta" onClick={(event) => {event.currentTarget.closest('details')?.removeAttribute('open');setShowPollComposer((current) => !current)}}><span>Encuesta</span>
          <BarChart3 size={20} aria-hidden="true" />
        </button>

        </div></details>
        <button
          className={isRecordingAudio ? 'icon-btn chat-mic-button recording' : 'icon-btn chat-mic-button'}
          type="button"
          title={isRecordingAudio ? 'Detener grabación' : 'Grabar audio'}
          onClick={() => void toggleAudioRecording()}
        >
          {isRecordingAudio ? <Square size={20} fill="currentColor" aria-hidden="true" /> : <Mic size={21} aria-hidden="true" />}
        </button>
        <input
          ref={composerInputRef}
          className="text-input chat-input-field"
          onSelect={event=>setSpellingCaret(event.currentTarget.selectionStart ?? undefined)}
          lang="es" spellCheck={true} autoCorrect="on" autoCapitalize="sentences"
          disabled={Boolean(editingMessageId) && sending}
          placeholder={editingMessageId ? 'Edita tu mensaje...' : `Escribe en ${scope === 'group' ? 'grupo' : 'comunidad'}...`}
          value={editingMessageId ? editingDraft : draft}
          onChange={(event) => {
            draftRevisionRef.current += 1
            if (editingMessageId) setEditingDraft(event.target.value); else setDraft(event.target.value)
          }}
        />

        <button className="primary-btn circle-btn" type="submit" aria-label={editingMessageId ? 'Guardar corrección' : 'Enviar mensaje'} disabled={sending || uploading || preparingAttachments}>
          {sending || uploading ? <LoaderCircle size={20} aria-hidden="true" />
            : editingMessageId ? <Check size={22} aria-hidden="true" /> : <Send size={21} aria-hidden="true" />}
        </button>
      </form>}
      {viewOnceLease ? <ViewOnceMediaDialog lease={viewOnceLease} onClose={() => setViewOnceLease(null)} /> : null}
    </section>
  )
}
import { useMessageFocusFromUrl } from './useMessageFocusFromUrl'
