import { CollectionInfo } from '../../components/CollectionInfo'
import { backendRpc } from '../../core/backendRpc'
import { conversationWindow } from '../../core/conversationWindow'
import { sharedVideoReference } from '../../core/sharedVideoReference'
import { SharedVideoCard } from '../../components/SharedVideoCard'
import { retryPendingWebMessage } from '../../core/webOutbox'
import { StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { MentionSuggestions } from '../../components/MentionSuggestions'
import { insertMention, type MentionCandidate } from '../../core/mentionSuggestions'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { MoreHorizontal } from 'lucide-react'
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
  sendFallbackRoomMessage,
  uploadFallbackFile,
} from '../../core/fallbackData'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import { ViewOnceMediaDialog } from '../../components/ViewOnceMediaDialog'
import { requestViewOnceMedia } from '../../core/media/viewOnceClient'
import type { ViewOnceLease } from '../../core/media/viewOnceLease'
import { ChatAttachmentMedia, attachmentKind } from '../../components/ChatAttachmentMedia'
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
  if (file.type.startsWith('image/') || file.type.startsWith('video/')) {
    return URL.createObjectURL(file)
  }
  return ''
}

function formatFileSize(sizeInBytes: number): string {
  if (sizeInBytes < 1024) return `${sizeInBytes} B`
  if (sizeInBytes < 1024 * 1024) return `${Math.round(sizeInBytes / 1024)} KB`
  return `${(sizeInBytes / (1024 * 1024)).toFixed(1)} MB`
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
  scope: RoomScope
  roomId: string
  embedded?: boolean
}

export function RoomChatPage({ scope, roomId, embedded = false }: RoomChatPageProps) {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const config = roomConfigByScope[scope]
  const cleanRoomId = useMemo(() => roomId.trim().toLowerCase(), [roomId])
  const [roomAvatar, setRoomAvatar] = useState('')
  const [title, setTitle] = useState(scope === 'group' ? 'Grupo' : 'Comunidad')
  const [isMember, setIsMember] = useState(true)
  const [showInfo, setShowInfo] = useState(false)
  const [participants, setParticipants] = useState<Array<{ id: string; name: string; avatarUrl: string; role: string; username?: string }>>([])
  const [mentionCandidates, setMentionCandidates] = useState<MentionCandidate[]>([])
  const [messages, setMessages] = useState<RoomMessage[]>([])
  const [draft, setDraft] = useState('')
  const draftRevisionRef = useRef(0)
  const sendInFlightRef = useRef(false)
  const [replyToId, setReplyToId] = useState<string | null>(null)
  const [editingMessageId, setEditingMessageId] = useState<string | null>(null)
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
  const [sending, setSending] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [showStickers, setShowStickers] = useState(false)
  const [isRecordingAudio, setIsRecordingAudio] = useState(false)
  const [dragActive, setDragActive] = useState(false)
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([])
  const [showMessageSearch, setShowMessageSearch] = useState(false)
  const [messageSearchQuery, setMessageSearchQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
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
  const [historyWindowSize, setHistoryWindowSize] = useState(80)
  useEffect(() => setHistoryWindowSize(80), [cleanRoomId])
  const renderedMessages = conversationWindow(filteredMessages, historyWindowSize, Boolean(normalizedMessageSearch))
  const messageById = useMemo(() => new Map(messages.map((message) => [message.id, message])), [messages])
  const replyToMessage = replyToId ? messageById.get(replyToId) ?? null : null
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
  }, [messages.length])

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

  const loadPollStates = useCallback(async (rows: RoomMessage[]) => {
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
        setConsumedViewOnce({})
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
      setConsumedViewOnce(consumed)
    },
    [config.scopeType, useUbuntu, user],
  )

  const markAsRead = useCallback(async () => {
    if (useUbuntu) return
    if (!supabase || !user || !cleanRoomId) return
    await supabase.rpc(config.markReadRpc, { [config.roomIdParam]: cleanRoomId })
  }, [cleanRoomId, config.markReadRpc, config.roomIdParam, useUbuntu, user])

  const loadRoom = useCallback(async (options?: { silent?: boolean }) => {
    const loadSequence = ++loadSequenceRef.current
    const isStaleLoad = () => loadSequenceRef.current !== loadSequence
    if (!user || !cleanRoomId) {
      if (!isStaleLoad()) {
        setLoading(false)
      }
      return
    }
    if (!options?.silent) setLoading(true)
    setError(null)
    try {
      if (useUbuntu) {
        const response = await loadFallbackRoomChat(scope, cleanRoomId)
        if (isStaleLoad()) return
        setIsMember(response.historyAccess?.isMember !== false)
        setParticipants(response.participants || [])
        setMentionCandidates((response.participants || []).map(person => ({ ...person, username: person.username || "" })))
        setRoomAvatar(resolveSupabasePublicUrl('avatars',conversationAvatarSource(response.room || response.chat || {})))
        setTitle(response.title || (scope === 'group' ? 'Grupo' : 'Comunidad'))
        const mappedMessages = (response.messages ?? []).map(mapRoomMessage)
        setMessages(mappedMessages)
        await loadPollStates(mappedMessages)
        setConsumedViewOnce({})
        return
      }

      if (!supabase) {
        if (!isStaleLoad()) setError('Supabase no está configurado.')
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
          setError('No tienes acceso a esta sala o no existe.')
          setLoading(false)
        }
        return
      }
      if (!isStaleLoad()) {
        setRoomAvatar(resolveSupabasePublicUrl('avatars',conversationAvatarSource(roomRow)))
        setTitle(textOf(roomRow, ['name', 'title', 'readable_id'], scope === 'group' ? 'Grupo' : 'Comunidad'))
      }

      const { data: messageRows, error: msgError } = await supabase
        .from(config.messageTable)
        .select('*')
        .eq(config.roomForeignKey, cleanRoomId)
        .order('created_at', { ascending: true })
        .limit(600)
      if (msgError) throw msgError

      const mappedMessages = ((messageRows ?? []) as GenericRow[]).map(mapRoomMessage)
      if (isStaleLoad()) return
      setMessages(mappedMessages)
      await Promise.all([loadPollStates(mappedMessages), loadConsumedViewOnce(mappedMessages), markAsRead()])
    } catch (caught) {
      if (!isStaleLoad() && !options?.silent) {
        setError(friendlyError(caught, 'No se pudo cargar la sala'))
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

  useEffect(() => {
    void loadRoom()
  }, [loadRoom])

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
    const intervalId = window.setInterval(() => {
      void loadRoom({ silent: true })
    }, 5000)
    return () => { unsubscribe(); window.clearInterval(intervalId) }
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
    const queued = [...pendingAttachments]
    setUploading(true)
    try {
      for (let index = 0; index < queued.length; index += 1) {
        const attachment = queued[index]
        const safeName = attachment.file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
        const filePath = `${scope}/${user.id}/${Date.now()}_${index}_${safeName}`
        const publicUrl = useUbuntu
          ? (await uploadFallbackFile(config.mediaBucket, attachment.file, filePath)).publicUrl
          : await (async () => {
              const { error: uploadError } = await supabase!.storage
                .from(config.mediaBucket)
                .upload(filePath, attachment.file, { contentType: attachment.file.type || undefined })
              if (uploadError) throw uploadError
              const { data } = supabase!.storage.from(config.mediaBucket).getPublicUrl(filePath)
              return data.publicUrl
            })()
        const caption =
          attachment.caption.trim() || (index === 0 ? textFallback.trim() : '') || attachment.file.name
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
    const text = draft.trim()
    if ((!text && pendingAttachments.length === 0) || sending || uploading || sendInFlightRef.current) return
    const submittedDraft = draft
    const draftRevisionAtSend = draftRevisionRef.current
    const sendingTextOnly = !editingMessageId && pendingAttachments.length === 0
    sendInFlightRef.current = true
    if (sendingTextOnly) setDraft('')
    setSending(true)
    try {
      if (editingMessageId) {
        const response = useUbuntu
          ? await editFallbackRoomMessage(scope, cleanRoomId, editingMessageId, text)
          : null
        if (response) {
          upsertMessageInState(mapRoomMessage(response.message))
        } else if (supabase && user) {
          const nowIso = new Date().toISOString()
          const { error: editError } = await supabase
            .from(config.messageTable)
            .update({ content: text, is_edited: true, updated_at: nowIso })
            .eq('id', editingMessageId)
            .eq(config.roomForeignKey, cleanRoomId)
            .eq('sender_id', user.id)
          if (editError) throw editError
          setMessages((current) => current.map((entry) => entry.id === editingMessageId
            ? { ...entry, content: text, isEdited: true, updatedAt: nowIso }
            : entry))
        }
        setEditingMessageId(null)
        setDraft((current) => current === submittedDraft ? '' : current)
      } else if (pendingAttachments.length > 0) {
        await sendPendingAttachments(text)
        setDraft((current) => current === submittedDraft ? '' : current)
      } else {
        await sendMessage(text, '', 'text', replyToId)
        setReplyToId(null)
      }
    } catch (caught) {
      if (sendingTextOnly && draftRevisionRef.current === draftRevisionAtSend) {
        setDraft(submittedDraft)
      }
      setError(friendlyError(caught, 'No se pudo enviar mensaje'))
    } finally {
      sendInFlightRef.current = false
      setSending(false)
    }
  }

  function handleAttachFile(input: FileList | File[] | null) {
    queueAttachmentFiles(input)
  }

  async function toggleReaction(messageId: string, emoji: string) {
    if (useUbuntu) return
    if (!supabase) return
    const payload: Record<string, unknown> = { p_message_id: messageId, p_emoji: emoji }
    const { error: reactionError } = await supabase.rpc(config.reactionRpc, payload)
    if (reactionError) {
      setError(friendlyError(reactionError, 'No se pudo reaccionar'))
      return
    }
    await refreshMessageById(messageId)
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
    if ((!supabase && !useUbuntu) || !cleanRoomId || creatingPoll) return
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
      const payload: Record<string, unknown> = {
        [config.roomIdParam]: cleanRoomId,
        p_message_id: crypto.randomUUID(),
        p_question: question,
        p_options: options,
        p_allows_multiple: pollAllowsMultiple,
      }
      const { error: pollError } = await backendRpc(config.pollCreateRpc, payload)
      if (pollError) throw pollError
      setPollQuestion('')
      setPollOptions('Si\nNo')
      setPollAllowsMultiple(false)
      setShowPollComposer(false)
      void loadRoom({ silent: true })
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

  async function sendForwardedMessage(message: RoomMessage) {
    try {
      await sendMessage(
        message.content || 'Mensaje reenviado',
        message.mediaUrl,
        message.contentType || 'text',
        null,
        true,
        message.id,
        message.senderId,
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo reenviar mensaje'))
    }
  }

  async function loadMessageReceipts(messageId: string) {
    if (useUbuntu) return
    if (!supabase || !messageId) return
    try {
      const { data, error: receiptsError } = await supabase.rpc(config.receiptsRpc, { p_message_id: messageId })
      if (receiptsError) throw receiptsError
      setReceiptsByMessage((current) => ({
        ...current,
        [messageId]: ((data ?? []) as GenericRow[]).map(mapReceipt),
      }))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudieron cargar recibos'))
    }
  }

  if (!supabase && !useUbuntu) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL para usar mensajes con Ubuntu." />
  }

  async function deleteRoomMessage(message: RoomMessage, forEveryone: boolean) {
    if (!user) return
    try {
      if (useUbuntu) {
        const response = await deleteFallbackRoomMessage(scope, cleanRoomId, message.id, forEveryone)
        upsertMessageInState(mapRoomMessage(response.message))
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
        setMessages((current) => current.map((entry) => entry.id === message.id
          ? { ...entry, deletedBy, updatedAt: nowIso }
          : entry))
      }
    } catch (caught) {
      setError(friendlyError(caught, forEveryone ? 'No se pudo borrar para todos' : 'No se pudo borrar para mi'))
    }
  }

  async function importRoomSticker(file: File | null) {
    if (!file || !user || !cleanRoomId) return
    if (!file.type.startsWith('image/')) {
      setError('El sticker debe ser una imagen PNG, WEBP, GIF, JPG o JPEG.')
      return
    }
    setUploading(true)
    try {
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `${scope}/stickers/${user.id}/${Date.now()}_${safeName}`
      const mediaUrl = useUbuntu
        ? (await uploadFallbackFile(config.mediaBucket, file, filePath)).publicUrl
        : await (async () => {
            if (!supabase) throw new Error('Backend no configurado')
            const { error: uploadError } = await supabase.storage
              .from(config.mediaBucket)
              .upload(filePath, file, { contentType: file.type || undefined })
            if (uploadError) throw uploadError
            return supabase.storage.from(config.mediaBucket).getPublicUrl(filePath).data.publicUrl
          })()
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

  if (error) {
    return (
      <section className="section-stack">
        {!embedded ? (
          <Link to="/app/messages" className="inline-link">
            ← Volver a mensajes
          </Link>
        ) : null}
        <StatusView title="No se pudo abrir la sala" description={error} />
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
            <Link to="/app/messages" className="icon-btn-sm" title="Volver">
              ←
            </Link>
          ) : null}
          {useUbuntu ? <button type="button" className="chat-room-identity" aria-label={`Información de ${title}`} onClick={()=>setShowInfo(true)}><SafeAvatar url={roomAvatar} name={title} size="md"/><span><strong>{title}</strong><small>{participants.length ? `${participants.length} miembros` : scope==='group'?'Grupo':'Comunidad'}</small></span></button> : <h2>{title}</h2>}
        </div>
        <div className="row-actions chat-head-actions">
          <button
            className={showMessageSearch ? 'icon-btn header-icon-btn active' : 'icon-btn header-icon-btn'}
            type="button"
            title="Buscar"
            onClick={() => {
              setShowMessageSearch((current) => !current)
              if (showMessageSearch) {
                setMessageSearchQuery('')
              } else {
                setShowQuickTools(false)
              }
            }}
          >
            <svg viewBox="0 0 24 24" width="20" height="20"><path d="M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" fill="currentColor" /></svg>
          </button>
          <button
            className={showQuickTools ? 'icon-btn header-icon-btn active' : 'icon-btn header-icon-btn'}
            type="button"
            title="Menú"
            onClick={() => setShowQuickTools((current) => !current)}
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
        <section className="surface-subcard chat-inline-tools">
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

      <div className="chat-thread-shell">
        <section className="chat-thread" ref={threadRef} onScroll={handleThreadScroll}>
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
              <small className="muted-line">Todavía no hay mensajes en esta sala.</small>
            </div>
          ) : renderedMessages.map((message, index) => {
          const previousMessage = index > 0 ? renderedMessages[index - 1] : null
          const showDaySeparator =
            !previousMessage || dayKeyOf(previousMessage.createdAt) !== dayKeyOf(message.createdAt)
          const deletedForMe = user ? message.deletedBy.includes(user.id) : false
          if (deletedForMe) return null
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
              <article className={mine ? 'bubble bubble-me' : 'bubble bubble-them'}>
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
                    {message.content ? <p>{message.content}</p> : null}
                    {message.mediaUrl && !isViewOnceType(message.contentType) ? (
                      <ChatAttachmentMedia url={message.mediaUrl} kind={attachmentKind(message.contentType, message.mediaUrl)} title={message.content || 'Archivo'} />
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
                    <button className="mini-action-btn" type="button" onClick={() => void loadMessageReceipts(message.id)}>
                      Leidos
                    </button>
                    {mine && message.contentType === 'text' ? (
                      <button className="mini-action-btn" type="button" onClick={() => {
                        setEditingMessageId(message.id)
                        draftRevisionRef.current += 1
                        setDraft(message.content)
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
                    </div>
                  </details>
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
                <small className="message-meta">
                  {Number.isFinite(Date.parse(message.createdAt)) ? new Date(message.createdAt).toLocaleTimeString('es',{hour:'2-digit',minute:'2-digit'}) : ''}{message.isEdited ? ' · editado' : ''}
                  {mine ? <span className={`message-status ${message.status}`} aria-label={message.status==='pending'?'Pendiente':message.status==='failed'?'Error al enviar':message.status==='read'?'Leído':message.status==='delivered'?'Entregado':'Enviado'}>{message.status==='pending'?'◷':message.status==='failed'?<button type="button" className="mini-action-btn" onClick={()=>void retryPendingWebMessage(message.id)}>Reintentar</button>:message.status==='sent'?'✓':'✓✓'}</span> : null}
                </small>
              </article>
            </Fragment>
          )
        })}
          <div className="chat-thread-bottom-anchor" />
        </section>
        {showScrollToBottom ? (
          <button className="chat-scroll-bottom-btn" type="button" onClick={() => scrollThreadToBottom('smooth')}>
            Ultimo ↓
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
            draftRevisionRef.current += 1
            setDraft('')
          }}>
            Cancelar
          </button>
        </div>
      ) : null}

      {showStickers ? <StickerPicker onSelect={item => {
        void sendMessage(item.title, item.mediaUrl, 'sticker', replyToId).then(() => { setShowStickers(false); setReplyToId(null) }).catch(caught => setError(friendlyError(caught, 'No se pudo enviar el sticker')))
      }} onImport={file => void importRoomSticker(file)} onClose={() => setShowStickers(false)} /> : null}

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
      </section> : <form className="composer chat-composer shadow-lg" onSubmit={handleSend}>
        <details className="chat-attachment-menu"><summary className="icon-btn" aria-label="Adjuntar">＋</summary><div className="chat-attachment-popover">
        <button type="button" className="chat-attachment-option" onClick={event => { setShowStickers(true); event.currentTarget.closest('details')?.removeAttribute('open') }}>Buscar stickers</button>
        <label className="chat-attachment-option" tabIndex={0} role="button" onKeyDown={event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.currentTarget.querySelector<HTMLInputElement>('input')?.click()}}} title="Importar sticker" aria-label="Importar sticker">
          <span aria-hidden="true">☺</span><span>Sticker</span>
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
          <svg viewBox="0 0 24 24" width="24" height="24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor" /></svg>
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
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path d="M12 4a8 8 0 018 8 8 8 0 01-8 8 8 8 0 01-8-8 8 8 0 018-8z" fill="none" stroke="currentColor" strokeWidth="1.8" />
            <circle cx="12" cy="12" r="2.5" fill="currentColor" />
          </svg>
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
          <svg viewBox="0 0 24 24" width="22" height="22"><path d="M4 5h16v2H4V5zm0 6h10v2H4v-2zm0 6h6v2H4v-2z" fill="currentColor" /></svg>
        </button>

        </div></details>
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
          placeholder={`Escribe en ${scope === 'group' ? 'grupo' : 'comunidad'}...`}
          value={draft}
          onChange={(event) => {
            draftRevisionRef.current += 1
            setDraft(event.target.value)
          }}
        />

        <button className="primary-btn circle-btn" type="submit" disabled={sending || uploading}>
          {sending || uploading ? (
            '...'
          ) : (
            <svg viewBox="0 0 24 24" width="24" height="24"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" fill="currentColor" /></svg>
          )}
        </button>
      </form>}
      {viewOnceLease ? <ViewOnceMediaDialog lease={viewOnceLease} onClose={() => setViewOnceLease(null)} /> : null}
    </section>
  )
}
