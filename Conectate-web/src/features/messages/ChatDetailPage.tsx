import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import {
  formatDateTime,
  friendlyError,
  isMissingTableError,
  textOf,
  type GenericRow,
} from '../../core/utils'
import { useAuth } from '../auth/AuthProvider'

type ChatMessage = {
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

type StickerItem = {
  stickerId: string
  title: string
  mediaUrl: string
  isFavorite: boolean
}

type ChatSettings = {
  isMuted: boolean
  isPinned: boolean
  translateEnabled: boolean
  targetLang: string
}

type ScheduledJob = {
  id: string
  content: string
  scheduledFor: string
  status: string
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

function inferContentType(file: File): 'image' | 'video' | 'audio' | 'file' {
  if (file.type.startsWith('image/')) return 'image'
  if (file.type.startsWith('video/')) return 'video'
  if (file.type.startsWith('audio/')) return 'audio'
  return 'file'
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
): 'sent' | 'delivered' | 'read' {
  if (readByOther || Boolean(message.readAt)) return 'read'
  const status = (message.status || '').toLowerCase()
  if (Boolean(message.deliveredAt) || status === 'delivered' || status === 'read' || status === 'received') {
    return 'delivered'
  }
  return 'sent'
}

function currentBrowserLocation(): Promise<GeolocationPosition> {
  if (typeof navigator === 'undefined' || !navigator.geolocation) {
    throw new Error('GEOLOCATION_NOT_AVAILABLE')
  }
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => resolve(position),
      (error) => reject(error),
      { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 },
    )
  })
}

function mapMessage(row: GenericRow): ChatMessage {
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

function mapSticker(row: GenericRow): StickerItem {
  return {
    stickerId: textOf(row, ['sticker_id'], ''),
    title: textOf(row, ['title'], 'Sticker'),
    mediaUrl: textOf(row, ['media_url', 'thumb_url'], ''),
    isFavorite: toBoolean(row.is_favorite),
  }
}

function mapScheduledJob(row: GenericRow): ScheduledJob {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    content: textOf(row, ['content'], ''),
    scheduledFor: textOf(row, ['scheduled_for'], ''),
    status: textOf(row, ['status'], 'pending'),
  }
}

function reactionCount(reactions: Record<string, string[]>, emoji: string): number {
  return reactions[emoji]?.length ?? 0
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

type ChatDetailPageProps = {
  chatIdOverride?: string
  embedded?: boolean
}

export function ChatDetailPage({ chatIdOverride, embedded = false }: ChatDetailPageProps = {}) {
  const { user } = useAuth()
  const { chatId: routeChatId = '' } = useParams()
  const chatId = chatIdOverride ?? routeChatId
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [title, setTitle] = useState('Chat')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [replyToId, setReplyToId] = useState<string | null>(null)
  const [sharingLocation, setSharingLocation] = useState(false)
  const [sendingContact, setSendingContact] = useState(false)
  const [contactName, setContactName] = useState('')
  const [contactPhone, setContactPhone] = useState('')
  const [showQuickShare, setShowQuickShare] = useState(false)
  const [editingMessageId, setEditingMessageId] = useState<string | null>(null)
  const [editingDraft, setEditingDraft] = useState('')
  const [pollQuestion, setPollQuestion] = useState('')
  const [pollOptions, setPollOptions] = useState('Si\nNo')
  const [pollAllowsMultiple, setPollAllowsMultiple] = useState(false)
  const [creatingPoll, setCreatingPoll] = useState(false)
  const [scheduledDraft, setScheduledDraft] = useState('')
  const [scheduledAt, setScheduledAt] = useState('')
  const [scheduling, setScheduling] = useState(false)
  const [scheduledJobs, setScheduledJobs] = useState<ScheduledJob[]>([])
  const [showStickers, setShowStickers] = useState(false)
  const [stickers, setStickers] = useState<StickerItem[]>([])
  const [stickerQuery, setStickerQuery] = useState('')
  const [loadingStickers, setLoadingStickers] = useState(false)
  const [pollStatesById, setPollStatesById] = useState<Record<string, PollState[]>>({})
  const [consumedViewOnce, setConsumedViewOnce] = useState<Record<string, true>>({})
  const [otherParticipant, setOtherParticipant] = useState<OtherParticipantState | null>(null)
  const [settings, setSettings] = useState<ChatSettings>({
    isMuted: false,
    isPinned: false,
    translateEnabled: false,
    targetLang: 'es',
  })
  const [savingSettings, setSavingSettings] = useState(false)

  const cleanChatId = useMemo(() => chatId.trim().toLowerCase(), [chatId])
  const messageById = useMemo(() => new Map(messages.map((message) => [message.id, message])), [messages])
  const replyToMessage = replyToId ? messageById.get(replyToId) ?? null : null
  const composerText = editingMessageId ? editingDraft : draft
  const deviceId = useMemo(() => {
    const storageKey = 'conectate-web-device-id'
    const existing = localStorage.getItem(storageKey)
    if (existing && existing.trim().length > 0) return existing
    const generated = crypto.randomUUID()
    localStorage.setItem(storageKey, generated)
    return generated
  }, [])

  const loadPollStates = useCallback(async (rows: ChatMessage[]) => {
    if (!supabase) return
    const client = supabase
    const pollIds = Array.from(
      new Set(rows.map((message) => message.pollId).filter((pollId): pollId is string => Boolean(pollId))),
    )
    if (pollIds.length === 0) {
      setPollStatesById({})
      return
    }

    const entries = await Promise.all(
      pollIds.map(async (pollId) => {
        const { data, error: pollError } = await client.rpc('secure_get_poll_state', { p_poll_id: pollId })
        if (pollError) return [pollId, [] as PollState[]] as const
        return [pollId, ((data ?? []) as GenericRow[]).map(mapPollState)] as const
      }),
    )

    setPollStatesById(Object.fromEntries(entries))
  }, [])

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

  const markAsDeliveredAndRead = useCallback(async () => {
    if (!supabase || !user || !cleanChatId) return
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
  }, [cleanChatId, user])

  const loadChatSettings = useCallback(async () => {
    if (!supabase || !user || !cleanChatId) return
    try {
      const { data: participantRows } = await supabase
        .from('chat_participants')
        .select('is_muted,is_pinned')
        .eq('chat_id', cleanChatId)
        .eq('user_id', user.id)
        .limit(1)
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
        translateEnabled,
        targetLang: targetLang || 'es',
      })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudieron cargar ajustes del chat'))
    }
  }, [cleanChatId, user])

  const loadScheduledJobs = useCallback(async () => {
    if (!supabase || !user || !cleanChatId) return
    try {
      const { data, error: jobsError } = await supabase
        .from('scheduled_message_jobs')
        .select('*')
        .eq('owner_id', user.id)
        .eq('scope_type', 'dm')
        .eq('target_id', cleanChatId)
        .order('scheduled_for', { ascending: true })
        .limit(20)
      if (jobsError) {
        if (isMissingTableError(jobsError)) return
        throw jobsError
      }
      setScheduledJobs(((data ?? []) as GenericRow[]).map(mapScheduledJob))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar mensajes programados'))
    }
  }, [cleanChatId, user])

  const loadChat = useCallback(async () => {
    if (!supabase || !user || !cleanChatId) {
      setLoading(false)
      return
    }

    setLoading(true)
    setError(null)
    try {
      const { data: chats, error: chatError } = await supabase
        .from('chats')
        .select('*')
        .eq('id', cleanChatId)
        .contains('participants', [user.id])
        .limit(1)
      if (chatError) throw chatError

      const chatRow = ((chats ?? []) as GenericRow[])[0]
      if (!chatRow) {
        setError('No tienes acceso a este chat o no existe.')
        setMessages([])
        setLoading(false)
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
        const avatarUrl = textOf(profile ?? {}, ['avatar_url', 'photo_url', 'profile_image'], '')
        const participant = ((participantRows ?? []) as GenericRow[])[0]
        setTitle(displayName)
        setOtherParticipant({
          id: otherId,
          name: displayName,
          avatarUrl,
          lastReadAt: textOf(participant ?? {}, ['last_read_at'], ''),
        })
      } else {
        setTitle('Chat privado')
        setOtherParticipant(null)
      }

      const { data: messageRows, error: msgError } = await supabase
        .from('messages')
        .select('*')
        .eq('chat_id', cleanChatId)
        .order('created_at', { ascending: true })
        .limit(500)
      if (msgError) throw msgError

      const mappedMessages = ((messageRows ?? []) as GenericRow[]).map(mapMessage)
      setMessages(mappedMessages)
      await Promise.all([
        loadPollStates(mappedMessages),
        loadConsumedViewOnce(mappedMessages),
        markAsDeliveredAndRead(),
      ])
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar la conversación'))
    } finally {
      setLoading(false)
    }
  }, [cleanChatId, loadConsumedViewOnce, loadPollStates, markAsDeliveredAndRead, user])

  useEffect(() => {
    void loadChat()
  }, [loadChat])

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
  }, [cleanChatId, markAsDeliveredAndRead, user])

  useEffect(() => {
    void loadChatSettings()
    void loadScheduledJobs()
  }, [loadChatSettings, loadScheduledJobs])

  useEffect(() => {
    const client = supabase
    if (!client || !user || !cleanChatId) return
    const channel = client
      .channel(`chat-live-${cleanChatId}`)
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'messages', filter: `chat_id=eq.${cleanChatId}` },
        () => {
          void loadChat()
        },
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'message_poll_votes' },
        () => {
          void loadChat()
        },
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'chat_participants', filter: `chat_id=eq.${cleanChatId}` },
        () => {
          void loadChat()
        },
      )
      .subscribe()

    return () => {
      void client.removeChannel(channel)
    }
  }, [cleanChatId, loadChat, user])

  async function sendMessage(input: SendMessageInput) {
    if (!supabase || !user || !cleanChatId) return
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

  async function handleSend(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!supabase) return
    const text = composerText.trim()
    if (!text || sending) return
    setSending(true)
    try {
      if (editingMessageId) {
        const nowIso = new Date().toISOString()
        const { error: editError } = await supabase
          .from('messages')
          .update({ content: text, is_edited: true, updated_at: nowIso })
          .eq('id', editingMessageId)
          .eq('chat_id', cleanChatId)
          .eq('sender_id', user?.id ?? '')
        if (editError) throw editError
        setEditingMessageId(null)
        setEditingDraft('')
      } else {
        await sendMessage({ content: text, replyToId })
        setReplyToId(null)
      }
      setDraft('')
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar el mensaje'))
    } finally {
      setSending(false)
    }
  }

  async function handleAttachFile(file: File | null) {
    if (!supabase || !user || !cleanChatId || !file) return
    setUploading(true)
    try {
      const contentType = inferContentType(file)
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `dm/${user.id}/${Date.now()}_${safeName}`
      const { error: uploadError } = await supabase.storage
        .from('chat-media')
        .upload(filePath, file, { contentType: file.type || undefined })
      if (uploadError) throw uploadError
      const { data } = supabase.storage.from('chat-media').getPublicUrl(filePath)
      await sendMessage({
        content: file.name,
        mediaUrl: data.publicUrl,
        contentType,
        mediaSize: file.size,
        replyToId,
      })
      setReplyToId(null)
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo subir el archivo'))
    } finally {
      setUploading(false)
    }
  }

  async function handleAttachViewOnceFile(file: File | null) {
    if (!supabase || !user || !cleanChatId || !file) return
    setUploading(true)
    try {
      const contentType = inferContentType(file)
      if (contentType !== 'image' && contentType !== 'video') {
        throw new Error('View-once solo permite imagen o video')
      }
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `view-once/${user.id}/${Date.now()}_${safeName}`
      const { error: uploadError } = await supabase.storage
        .from('chat-media')
        .upload(filePath, file, { contentType: file.type || undefined })
      if (uploadError) throw uploadError
      const { data } = supabase.storage.from('chat-media').getPublicUrl(filePath)
      await sendMessage({
        content: file.name,
        mediaUrl: data.publicUrl,
        contentType: contentType === 'image' ? 'view_once_image' : 'view_once_video',
        mediaSize: file.size,
        replyToId,
      })
      setReplyToId(null)
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar view-once'))
    } finally {
      setUploading(false)
    }
  }

  async function shareCurrentLocation() {
    if (!supabase || !user || !cleanChatId || sharingLocation) return
    setSharingLocation(true)
    setError(null)
    try {
      const position = await currentBrowserLocation()
      const latitude = position.coords.latitude
      const longitude = position.coords.longitude
      const mapUrl = `https://www.google.com/maps?q=${latitude},${longitude}`
      await sendMessage({
        content: `Ubicacion: ${latitude.toFixed(6)}, ${longitude.toFixed(6)}`,
        contentType: 'location',
        mediaUrl: mapUrl,
      })
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo compartir ubicacion'))
    } finally {
      setSharingLocation(false)
    }
  }

  async function shareContactCard() {
    if (!supabase || !user || !cleanChatId || sendingContact) return
    const name = contactName.trim()
    const phone = contactPhone.trim()
    if (!name || !phone) {
      setError('Completa nombre y telefono para enviar contacto')
      return
    }
    setSendingContact(true)
    setError(null)
    try {
      await sendMessage({
        content: `${name} · ${phone}`,
        contentType: 'contact',
      })
      setContactName('')
      setContactPhone('')
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo compartir contacto'))
    } finally {
      setSendingContact(false)
    }
  }

  async function toggleReaction(messageId: string, emoji: string) {
    if (!supabase) return
    const { error: reactionError } = await supabase.rpc('secure_toggle_message_reaction', {
      p_message_id: messageId,
      p_emoji: emoji,
    })
    if (reactionError) {
      setError(friendlyError(reactionError, 'No se pudo reaccionar'))
      return
    }
    await loadChat()
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
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo reenviar'))
    }
  }

  async function deleteMessageForAll(message: ChatMessage) {
    if (!supabase || !user || message.senderId !== user.id) return
    try {
      const nowIso = new Date().toISOString()
      const { error: deleteError } = await supabase
        .from('messages')
        .update({ deleted_for_all: true, content: '', media_url: null, updated_at: nowIso })
        .eq('id', message.id)
        .eq('sender_id', user.id)
      if (deleteError) throw deleteError
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo borrar para todos'))
    }
  }

  async function deleteMessageForMe(message: ChatMessage) {
    if (!supabase || !user) return
    try {
      const nextDeletedBy = Array.from(new Set([...message.deletedBy, user.id]))
      const { error: deleteError } = await supabase
        .from('messages')
        .update({ deleted_by: nextDeletedBy, updated_at: new Date().toISOString() })
        .eq('id', message.id)
      if (deleteError) throw deleteError
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo borrar para mi'))
    }
  }

  async function createChatPoll() {
    if (!supabase || !cleanChatId || creatingPoll) return
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
      const { error: pollError } = await supabase.rpc('secure_create_chat_poll_message', {
        p_chat_id: cleanChatId,
        p_question: question,
        p_options: options,
        p_allows_multiple: pollAllowsMultiple,
      })
      if (pollError) throw pollError
      setPollQuestion('')
      setPollOptions('Si\nNo')
      setPollAllowsMultiple(false)
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo crear encuesta'))
    } finally {
      setCreatingPoll(false)
    }
  }

  async function votePollOption(pollId: string, optionId: string, selected: boolean) {
    if (!supabase || !pollId || !optionId) return
    try {
      const { data, error: voteError } = await supabase.rpc('secure_vote_poll_option', {
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
    if (!supabase || !isViewOnceType(message.contentType)) return
    try {
      const { data, error: consumeError } = await supabase.rpc('secure_consume_view_once_message', {
        p_scope_type: 'dm',
        p_message_id: message.id,
        p_device_id: deviceId,
      })
      if (consumeError) throw consumeError
      const first = ((data ?? []) as GenericRow[])[0] ?? {}
      const allowed = toBoolean(first.allowed)
      const already = toBoolean(first.already_consumed)
      if (allowed || already) {
        setConsumedViewOnce((current) => ({ ...current, [message.id]: true }))
      }
      if (allowed && message.mediaUrl) {
        window.open(message.mediaUrl, '_blank', 'noopener,noreferrer')
      }
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo abrir view-once'))
    }
  }

  async function loadStickers(query: string) {
    if (!supabase) return
    setLoadingStickers(true)
    try {
      const { data, error: stickerError } = await supabase.rpc('secure_search_stickers', {
        p_query: query,
        p_limit: 30,
      })
      if (stickerError) throw stickerError
      setStickers(((data ?? []) as GenericRow[]).map(mapSticker).filter((item) => item.stickerId.length > 0))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar stickers'))
    } finally {
      setLoadingStickers(false)
    }
  }

  async function toggleStickerFavorite(stickerId: string) {
    if (!supabase) return
    try {
      const { error: favoriteError } = await supabase.rpc('secure_toggle_sticker_favorite', {
        p_sticker_id: stickerId,
      })
      if (favoriteError) throw favoriteError
      await loadStickers(stickerQuery)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar favorito'))
    }
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
      await loadChat()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar sticker'))
    }
  }

  async function scheduleMessage() {
    if (!supabase || !cleanChatId || scheduling) return
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
      const { error: scheduleError } = await supabase.rpc('secure_schedule_message_job', {
        p_scope_type: 'dm',
        p_target_id: cleanChatId,
        p_content: content,
        p_content_type: 'text',
        p_scheduled_for: scheduleIso,
        p_translate_to: settings.translateEnabled ? settings.targetLang : null,
      })
      if (scheduleError) throw scheduleError
      setScheduledDraft('')
      setScheduledAt('')
      await loadScheduledJobs()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo programar mensaje'))
    } finally {
      setScheduling(false)
    }
  }

  async function cancelScheduledJob(jobId: string) {
    if (!supabase) return
    try {
      const { error: cancelError } = await supabase
        .from('scheduled_message_jobs')
        .update({ status: 'cancelled', updated_at: new Date().toISOString() })
        .eq('id', jobId)
      if (cancelError) throw cancelError
      await loadScheduledJobs()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cancelar mensaje programado'))
    }
  }

  async function saveChatPreferences(nextMuted: boolean, nextPinned: boolean) {
    if (!supabase || !user || !cleanChatId || savingSettings) return
    setSavingSettings(true)
    setError(null)
    try {
      const { error: prefError } = await supabase.rpc('secure_set_chat_preferences', {
        p_chat_id: cleanChatId,
        p_is_muted: nextMuted,
        p_is_pinned: nextPinned,
      })
      if (prefError && !isMissingFunctionError(prefError)) throw prefError
      if (prefError && isMissingFunctionError(prefError)) {
        const nowIso = new Date().toISOString()
        const { error: fallbackError } = await supabase.from('chat_participants').upsert(
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
        if (fallbackError) throw fallbackError
      }
      setSettings((current) => ({ ...current, isMuted: nextMuted, isPinned: nextPinned }))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudieron guardar preferencias'))
    } finally {
      setSavingSettings(false)
    }
  }

  async function saveTranslationSettings() {
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

  if (!supabase) {
    return <StatusView title="Supabase no configurado" description="Configura .env para usar mensajes." />
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

  return (
    <section className={embedded ? 'section-stack chat-detail-embedded' : 'section-stack'}>
      <div className="row-between">
        {!embedded ? (
          <Link to="/app/messages" className="inline-link">
            ← Volver
          </Link>
        ) : null}
        {otherParticipant ? (
          <Link className="identity-link compact" to={profilePathFor(otherParticipant.id, user?.id)}>
            {otherParticipant.avatarUrl ? (
              <img src={otherParticipant.avatarUrl} alt={otherParticipant.name} className="avatar-sm" />
            ) : (
              <span className="avatar-sm avatar-fallback">{initialsOf(otherParticipant.name)}</span>
            )}
            <span>{otherParticipant.name}</span>
          </Link>
        ) : (
          <h2>{title}</h2>
        )}
      </div>

      <article className="surface-card section-stack">
        <div className="row-actions">
          <button
            type="button"
            className={settings.isPinned ? 'chip-btn active' : 'chip-btn'}
            disabled={savingSettings}
            onClick={() => void saveChatPreferences(settings.isMuted, !settings.isPinned)}
          >
            {settings.isPinned ? 'Despinear' : 'Pinear'}
          </button>
          <button
            type="button"
            className={settings.isMuted ? 'chip-btn active' : 'chip-btn'}
            disabled={savingSettings}
            onClick={() => void saveChatPreferences(!settings.isMuted, settings.isPinned)}
          >
            {settings.isMuted ? 'Quitar silencio' : 'Silenciar'}
          </button>
          <button
            type="button"
            className={showStickers ? 'chip-btn active' : 'chip-btn'}
            onClick={() => {
              const next = !showStickers
              setShowStickers(next)
              if (next) void loadStickers(stickerQuery)
            }}
          >
            Stickers
          </button>
        </div>
        <div className="composer">
          <label className="checkbox-inline">
            <input
              type="checkbox"
              checked={settings.translateEnabled}
              onChange={(event) =>
                setSettings((current) => ({ ...current, translateEnabled: event.target.checked }))
              }
            />
            Traduccion automatica
          </label>
          <input
            className="text-input tiny-input"
            placeholder="Idioma (es, en...)"
            value={settings.targetLang}
            onChange={(event) =>
              setSettings((current) => ({ ...current, targetLang: event.target.value }))
            }
          />
          <button className="ghost-btn" type="button" disabled={savingSettings} onClick={() => void saveTranslationSettings()}>
            Guardar
          </button>
        </div>
      </article>

      <article className="surface-card section-stack">
        <div className="row-between">
          <h3>Compartir rapido</h3>
          <button className={showQuickShare ? 'chip-btn active' : 'chip-btn'} type="button" onClick={() => setShowQuickShare((current) => !current)}>
            {showQuickShare ? 'Ocultar' : 'Mostrar'}
          </button>
        </div>
        {showQuickShare ? (
          <div className="section-stack">
            <div className="composer">
              <input
                className="text-input"
                placeholder="Nombre contacto"
                value={contactName}
                onChange={(event) => setContactName(event.target.value)}
              />
              <input
                className="text-input tiny-input"
                placeholder="Telefono"
                value={contactPhone}
                onChange={(event) => setContactPhone(event.target.value)}
              />
              <button className="ghost-btn" type="button" disabled={sendingContact} onClick={() => void shareContactCard()}>
                {sendingContact ? 'Enviando...' : 'Enviar contacto'}
              </button>
            </div>
            <button className="ghost-btn" type="button" disabled={sharingLocation} onClick={() => void shareCurrentLocation()}>
              {sharingLocation ? 'Compartiendo ubicacion...' : 'Compartir ubicacion'}
            </button>
          </div>
        ) : null}
      </article>

      <article className="surface-card section-stack">
        <h3>Encuesta</h3>
        <input
          className="text-input"
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
        <button className="ghost-btn" type="button" disabled={creatingPoll} onClick={() => void createChatPoll()}>
          {creatingPoll ? 'Creando...' : 'Crear encuesta'}
        </button>
      </article>

      <article className="surface-card section-stack">
        <h3>Programar mensaje</h3>
        <div className="composer">
          <input
            className="text-input"
            placeholder="Mensaje..."
            value={scheduledDraft}
            onChange={(event) => setScheduledDraft(event.target.value)}
          />
          <input
            className="text-input tiny-input"
            type="datetime-local"
            value={scheduledAt}
            onChange={(event) => setScheduledAt(event.target.value)}
          />
          <button className="ghost-btn" type="button" disabled={scheduling} onClick={() => void scheduleMessage()}>
            {scheduling ? 'Programando...' : 'Programar'}
          </button>
        </div>
        {scheduledJobs.length > 0 ? (
          <div className="list-stack">
            {scheduledJobs.map((job) => (
              <div key={job.id} className="surface-subcard row-between">
                <div>
                  <strong>{job.content}</strong>
                  <small>
                    {formatDateTime(job.scheduledFor)} · {job.status}
                  </small>
                </div>
                {job.status === 'pending' ? (
                  <button className="ghost-btn" type="button" onClick={() => void cancelScheduledJob(job.id)}>
                    Cancelar
                  </button>
                ) : null}
              </div>
            ))}
          </div>
        ) : null}
      </article>

      {showStickers ? (
        <article className="surface-card section-stack">
          <div className="composer">
            <input
              className="text-input"
              placeholder="Buscar stickers..."
              value={stickerQuery}
              onChange={(event) => setStickerQuery(event.target.value)}
            />
            <button className="ghost-btn" type="button" onClick={() => void loadStickers(stickerQuery)}>
              Buscar
            </button>
          </div>
          {loadingStickers ? <small>Cargando...</small> : null}
          <div className="sticker-grid">
            {stickers.map((sticker) => (
              <article key={sticker.stickerId} className="surface-subcard section-stack">
                {sticker.mediaUrl ? <img src={sticker.mediaUrl} alt={sticker.title} className="sticker-preview" /> : null}
                <strong>{sticker.title}</strong>
                <div className="row-actions">
                  <button className="ghost-btn" type="button" onClick={() => void sendSticker(sticker)}>
                    Enviar
                  </button>
                  <button
                    className={sticker.isFavorite ? 'chip-btn active' : 'chip-btn'}
                    type="button"
                    onClick={() => void toggleStickerFavorite(sticker.stickerId)}
                  >
                    {sticker.isFavorite ? '★' : '☆'}
                  </button>
                </div>
              </article>
            ))}
          </div>
        </article>
      ) : null}

      <section className="chat-thread">
        {messages.map((message) => {
          const deletedForMe = user ? message.deletedBy.includes(user.id) : false
          if (deletedForMe) return null

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

          return (
            <article key={message.id} className={mine ? 'bubble bubble-me' : 'bubble bubble-them'}>
              {message.isForwarded ? <small>Reenviado</small> : null}
              {replied ? <div className="reply-preview">Respuesta a: {replied.content || 'Archivo'}</div> : null}

              {message.deletedForAll ? (
                <p className="muted-line">Mensaje eliminado para todos</p>
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
                    <>{message.content ? <p>{message.content}</p> : null}</>
                  )}
                  {message.mediaUrl && message.contentType === 'sticker' ? (
                    <img src={message.mediaUrl} alt={message.content || 'Sticker'} className="sticker-preview" />
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
                  {message.mediaUrl &&
                  message.contentType !== 'sticker' &&
                  message.contentType !== 'location' &&
                  !isViewOnceType(message.contentType) ? (
                    <a className="inline-link" href={message.mediaUrl} target="_blank" rel="noreferrer">
                      Abrir archivo
                    </a>
                  ) : null}
                </>
              )}

              <div className="reaction-row">
                {['👍', '❤️', '😂'].map((emoji) => (
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

              {!message.deletedForAll ? (
                <div className="row-actions">
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
              ) : null}

              <small className="message-meta">
                {formatDateTime(message.createdAt)}
                {message.isEdited ? ' · editado' : ''}
                {mine ? (
                  <span className={`message-status ${deliveryState}`}>
                    {deliveryState === 'sent'
                      ? '✓ Enviado'
                      : deliveryState === 'delivered'
                        ? '✓✓ Entregado'
                        : `✓✓ Leido${
                            readByOther
                              ? ` por ${otherParticipant?.name ?? 'contacto'}${
                                  otherParticipant?.lastReadAt
                                    ? ` ${formatDateTime(otherParticipant.lastReadAt)}`
                                    : ''
                                }`
                              : ''
                          }`}
                  </span>
                ) : (
                  <>
                    {' · '}
                    {message.status}
                    {message.readAt ? ' · leido' : message.deliveredAt ? ' · entregado' : ''}
                  </>
                )}
              </small>
            </article>
          )
        })}
      </section>

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

      <form className="composer" onSubmit={handleSend}>
        <input
          className="text-input"
          placeholder={editingMessageId ? 'Edita tu mensaje...' : 'Escribe un mensaje...'}
          value={composerText}
          onChange={(event) => (editingMessageId ? setEditingDraft(event.target.value) : setDraft(event.target.value))}
        />
        <label className="ghost-btn file-pick-btn">
          Adjuntar
          <input
            type="file"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0] ?? null
              void handleAttachFile(file)
              event.currentTarget.value = ''
            }}
          />
        </label>
        <label className="ghost-btn file-pick-btn">
          View-once
          <input
            type="file"
            hidden
            accept="image/*,video/*"
            onChange={(event) => {
              const file = event.target.files?.[0] ?? null
              void handleAttachViewOnceFile(file)
              event.currentTarget.value = ''
            }}
          />
        </label>
        <button className="primary-btn" type="submit" disabled={sending || uploading}>
          {sending
            ? editingMessageId
              ? 'Guardando...'
              : 'Enviando...'
            : uploading
              ? 'Subiendo...'
              : editingMessageId
                ? 'Guardar'
                : 'Enviar'}
        </button>
      </form>
    </section>
  )
}
