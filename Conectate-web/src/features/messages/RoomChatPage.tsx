import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../auth/AuthProvider'

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
  const { user } = useAuth()
  const config = roomConfigByScope[scope]
  const cleanRoomId = useMemo(() => roomId.trim().toLowerCase(), [roomId])
  const [title, setTitle] = useState(scope === 'group' ? 'Grupo' : 'Comunidad')
  const [messages, setMessages] = useState<RoomMessage[]>([])
  const [draft, setDraft] = useState('')
  const [replyToId, setReplyToId] = useState<string | null>(null)
  const [pollQuestion, setPollQuestion] = useState('')
  const [pollOptions, setPollOptions] = useState('Si\nNo')
  const [pollAllowsMultiple, setPollAllowsMultiple] = useState(false)
  const [creatingPoll, setCreatingPoll] = useState(false)
  const [pollStatesById, setPollStatesById] = useState<Record<string, PollState[]>>({})
  const [consumedViewOnce, setConsumedViewOnce] = useState<Record<string, true>>({})
  const [receiptsByMessage, setReceiptsByMessage] = useState<Record<string, MessageReceipt[]>>({})
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const messageById = useMemo(() => new Map(messages.map((message) => [message.id, message])), [messages])
  const replyToMessage = replyToId ? messageById.get(replyToId) ?? null : null
  const deviceId = useMemo(() => {
    const storageKey = 'conectate-web-device-id'
    const existing = localStorage.getItem(storageKey)
    if (existing && existing.trim().length > 0) return existing
    const generated = crypto.randomUUID()
    localStorage.setItem(storageKey, generated)
    return generated
  }, [])

  const loadPollStates = useCallback(async (rows: RoomMessage[]) => {
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
    async (rows: RoomMessage[]) => {
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
    [config.scopeType, user],
  )

  const markAsRead = useCallback(async () => {
    if (!supabase || !user || !cleanRoomId) return
    await supabase.rpc(config.markReadRpc, { [config.roomIdParam]: cleanRoomId })
  }, [cleanRoomId, config.markReadRpc, config.roomIdParam, user])

  const loadRoom = useCallback(async () => {
    if (!supabase || !user || !cleanRoomId) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const { data: roomRows, error: roomError } = await supabase
        .from(config.roomTable)
        .select('*')
        .eq('id', cleanRoomId)
        .limit(1)
      if (roomError) throw roomError
      const roomRow = ((roomRows ?? []) as GenericRow[])[0]
      if (!roomRow) {
        setError('No tienes acceso a esta sala o no existe.')
        setLoading(false)
        return
      }
      setTitle(textOf(roomRow, ['name', 'title', 'readable_id'], scope === 'group' ? 'Grupo' : 'Comunidad'))

      const { data: messageRows, error: msgError } = await supabase
        .from(config.messageTable)
        .select('*')
        .eq(config.roomForeignKey, cleanRoomId)
        .order('created_at', { ascending: true })
        .limit(600)
      if (msgError) throw msgError

      const mappedMessages = ((messageRows ?? []) as GenericRow[]).map(mapRoomMessage)
      setMessages(mappedMessages)
      await Promise.all([loadPollStates(mappedMessages), loadConsumedViewOnce(mappedMessages), markAsRead()])
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar la sala'))
    } finally {
      setLoading(false)
    }
  }, [
    cleanRoomId,
    config.messageTable,
    config.roomForeignKey,
    config.roomTable,
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
    const client = supabase
    if (!client || !user || !cleanRoomId) return
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
        () => {
          void loadRoom()
        },
      )
      .subscribe()
    return () => {
      void client.removeChannel(channel)
    }
  }, [cleanRoomId, config.messageTable, config.roomForeignKey, loadRoom, scope, user])

  async function sendMessage(
    content: string,
    mediaUrl = '',
    contentType = 'text',
    replyToMessageId: string | null = null,
    isForwarded = false,
    forwardedFromMessageId: string | null = null,
    forwardedFromUserId: string | null = null,
  ) {
    if (!supabase || !user || !cleanRoomId) return
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

  async function handleSend(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const text = draft.trim()
    if (!text || sending) return
    setSending(true)
    try {
      await sendMessage(text, '', 'text', replyToId)
      setDraft('')
      setReplyToId(null)
      await loadRoom()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar mensaje'))
    } finally {
      setSending(false)
    }
  }

  async function handleAttachFile(file: File | null) {
    if (!supabase || !user || !cleanRoomId || !file) return
    setUploading(true)
    try {
      const contentType = inferContentType(file)
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `${scope}/${user.id}/${Date.now()}_${safeName}`
      const { error: uploadError } = await supabase.storage
        .from(config.mediaBucket)
        .upload(filePath, file, { contentType: file.type || undefined })
      if (uploadError) throw uploadError
      const { data } = supabase.storage.from(config.mediaBucket).getPublicUrl(filePath)
      await sendMessage(file.name, data.publicUrl, contentType, replyToId)
      setReplyToId(null)
      await loadRoom()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo subir el archivo'))
    } finally {
      setUploading(false)
    }
  }

  async function toggleReaction(messageId: string, emoji: string) {
    if (!supabase) return
    const payload: Record<string, unknown> = { p_message_id: messageId, p_emoji: emoji }
    const { error: reactionError } = await supabase.rpc(config.reactionRpc, payload)
    if (reactionError) {
      setError(friendlyError(reactionError, 'No se pudo reaccionar'))
      return
    }
    await loadRoom()
  }

  async function handleAttachViewOnceFile(file: File | null) {
    if (!supabase || !user || !cleanRoomId || !file) return
    setUploading(true)
    try {
      const contentType = inferContentType(file)
      if (contentType !== 'image' && contentType !== 'video') {
        throw new Error('View-once solo permite imagen o video')
      }
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `${scope}/view-once/${user.id}/${Date.now()}_${safeName}`
      const { error: uploadError } = await supabase.storage
        .from(config.mediaBucket)
        .upload(filePath, file, { contentType: file.type || undefined })
      if (uploadError) throw uploadError
      const { data } = supabase.storage.from(config.mediaBucket).getPublicUrl(filePath)
      await sendMessage(
        file.name,
        data.publicUrl,
        contentType === 'image' ? 'view_once_image' : 'view_once_video',
        replyToId,
      )
      setReplyToId(null)
      await loadRoom()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar view-once'))
    } finally {
      setUploading(false)
    }
  }

  async function createPoll() {
    if (!supabase || !cleanRoomId || creatingPoll) return
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
        p_question: question,
        p_options: options,
        p_allows_multiple: pollAllowsMultiple,
      }
      const { error: pollError } = await supabase.rpc(config.pollCreateRpc, payload)
      if (pollError) throw pollError
      setPollQuestion('')
      setPollOptions('Si\nNo')
      setPollAllowsMultiple(false)
      await loadRoom()
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
      setPollStatesById((current) => ({
        ...current,
        [pollId]: ((data ?? []) as GenericRow[]).map(mapPollState),
      }))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo votar'))
    }
  }

  async function consumeViewOnce(message: RoomMessage) {
    if (!supabase || !isViewOnceType(message.contentType)) return
    try {
      const { data, error: consumeError } = await supabase.rpc('secure_consume_view_once_message', {
        p_scope_type: config.scopeType,
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
      await loadRoom()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo reenviar mensaje'))
    }
  }

  async function loadMessageReceipts(messageId: string) {
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

  if (!supabase) {
    return <StatusView title="Supabase no configurado" description="Configura .env para usar mensajes." />
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

  return (
    <section className={embedded ? 'section-stack chat-detail-embedded' : 'section-stack'}>
      <div className="row-between">
        {!embedded ? (
          <Link to="/app/messages" className="inline-link">
            ← Volver
          </Link>
        ) : null}
        <h2>{title}</h2>
      </div>

      <article className="surface-card section-stack">
        <h3>Encuesta en {scope === 'group' ? 'grupo' : 'comunidad'}</h3>
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
        <button className="ghost-btn" type="button" disabled={creatingPoll} onClick={() => void createPoll()}>
          {creatingPoll ? 'Creando...' : 'Crear encuesta'}
        </button>
      </article>

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
          const receipts = receiptsByMessage[message.id] ?? []
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
                  {message.content ? <p>{message.content}</p> : null}
                  {message.mediaUrl && isViewOnceType(message.contentType) ? (
                    consumed ? (
                      <small>Contenido view-once ya consumido.</small>
                    ) : (
                      <button className="ghost-btn" type="button" onClick={() => void consumeViewOnce(message)}>
                        Ver una vez
                      </button>
                    )
                  ) : null}
                  {message.mediaUrl && !isViewOnceType(message.contentType) ? (
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
                  <button className="mini-action-btn" type="button" onClick={() => void loadMessageReceipts(message.id)}>
                    Leidos
                  </button>
                </div>
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
              <small>
                {formatDateTime(message.createdAt)}
                {message.isEdited ? ' · editado' : ''}
                {' · '}
                {message.status}
                {message.deliveredAt ? ' · entregado' : ''}
                {message.readAt ? ' · leido' : ''}
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

      <form className="composer" onSubmit={handleSend}>
        <input
          className="text-input"
          placeholder={`Escribe en ${scope === 'group' ? 'grupo' : 'comunidad'}...`}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
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
          {sending ? 'Enviando...' : uploading ? 'Subiendo...' : 'Enviar'}
        </button>
      </form>
    </section>
  )
}
