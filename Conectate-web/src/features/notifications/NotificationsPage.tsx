import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import {
  enqueueDeferredMutation,
  isBrowserOnline,
  isLikelyTransientError,
  runWithRetry,
} from '../../core/reliability'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
} from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { readFallbackSession } from '../../core/auth/fallbackAuth'
import {
  loadFallbackNotifications,
  markAllFallbackNotificationsRead,
  markFallbackNotificationRead,
} from '../../core/fallbackData'

type NotificationItem = {
  id: string
  type: string
  content: string
  actorId: string
  targetType: string
  targetId: string
  chatId: string
  postId: string
  videoId: string
  groupId: string
  communityId: string
  createdAt: string
  isRead: boolean
}

type NotificationsFilter = 'all' | 'unread' | 'messages' | 'social' | 'moderation'

type NotificationAction = {
  label: string
  path: string
}

function isMissingFunctionError(error: unknown): boolean {
  const message =
    error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return (
    message.includes('could not find') ||
    message.includes('function') ||
    message.includes('does not exist') ||
    message.includes('42883')
  )
}

function toBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value === 1
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    return normalized === 'true' || normalized === '1' || normalized === 't'
  }
  return false
}

function parseExtraData(input: unknown): Record<string, string> {
  if (!input) return {}
  if (typeof input === 'string') {
    try {
      const parsed = JSON.parse(input)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
      return Object.fromEntries(
        Object.entries(parsed as Record<string, unknown>).map(([key, value]) => [key, String(value ?? '')]),
      )
    } catch {
      return {}
    }
  }
  if (typeof input === 'object' && !Array.isArray(input)) {
    return Object.fromEntries(
      Object.entries(input as Record<string, unknown>).map(([key, value]) => [key, String(value ?? '')]),
    )
  }
  return {}
}

function mapNotification(row: GenericRow): NotificationItem {
  const extra = parseExtraData(row.extra_data)
  const targetType = textOf(row, ['target_type'], '').toLowerCase()
  const targetId = textOf(row, ['target_id'], extra.target_id ?? '')
  const fallbackMessageTarget = textOf(extra, ['chat_id', 'group_id', 'community_id'], targetId)
  const fallbackPostId = textOf(extra, ['post_id'], '')
  const fallbackVideoId = textOf(extra, ['video_id'], '')
  const inferredPostId = targetType === 'post' ? targetId : ''
  const inferredVideoId = targetType === 'video' ? targetId : ''
  const inferredChatId =
    targetType === 'chat' || targetType === 'group' || targetType === 'community' ? targetId : ''

  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    type: textOf(row, ['type'], 'activity'),
    content: textOf(row, ['content'], ''),
    actorId: textOf(row, ['actor_id'], ''),
    targetType,
    targetId,
    chatId: textOf(row, ['chat_id'], fallbackMessageTarget || inferredChatId),
    postId: textOf(row, ['post_id'], fallbackPostId || inferredPostId),
    videoId: textOf(row, ['video_id'], fallbackVideoId || inferredVideoId),
    groupId: textOf(row, ['group_id'], textOf(extra, ['group_id'], '')),
    communityId: textOf(row, ['community_id'], textOf(extra, ['community_id'], '')),
    createdAt: textOf(row, ['created_at'], ''),
    isRead: toBoolean(row.is_read),
  }
}

function typeLabel(type: string): string {
  switch (type) {
    case 'follow':
      return 'Nuevo seguidor'
    case 'like_post':
      return 'Like en publicación'
    case 'comment_post':
      return 'Comentario en publicación'
    case 'share_post':
      return 'Compartieron tu publicación'
    case 'like_video':
      return 'Like en video'
    case 'comment_video':
      return 'Comentario en video'
    case 'message':
      return 'Mensaje'
    case 'friend_request':
      return 'Solicitud de amistad'
    case 'friend_request_accepted':
      return 'Solicitud aceptada'
    case 'admin_warning_video':
      return 'Advertencia de moderación'
    case 'new_message':
      return 'Nuevo mensaje'
    default:
      return type || 'Actividad'
  }
}

function isMessageNotification(item: NotificationItem): boolean {
  const type = item.type.toLowerCase()
  return (
    type.includes('message') ||
    item.targetType === 'chat' ||
    item.targetType === 'group' ||
    item.targetType === 'community'
  )
}

function isModerationNotification(item: NotificationItem): boolean {
  const type = item.type.toLowerCase()
  return (
    type.includes('admin') ||
    type.includes('moderation') ||
    type.includes('warning') ||
    type.includes('report')
  )
}

function matchesFilter(item: NotificationItem, filter: NotificationsFilter): boolean {
  if (filter === 'all') return true
  if (filter === 'unread') return !item.isRead
  if (filter === 'messages') return isMessageNotification(item)
  if (filter === 'moderation') return isModerationNotification(item)
  return !isMessageNotification(item) && !isModerationNotification(item)
}

function resolveNotificationAction(item: NotificationItem): NotificationAction | null {
  const type = item.type.toLowerCase()
  const targetType = item.targetType.toLowerCase()
  const messageTargetId = item.chatId || item.groupId || item.communityId || item.targetId
  const postTargetId = item.postId || (targetType === 'post' ? item.targetId : '')
  const videoTargetId = item.videoId || (targetType === 'video' ? item.targetId : '')

  if (postTargetId) return { label: 'Abrir post', path: `/app/feed/post/${postTargetId}` }
  if (videoTargetId) return { label: 'Abrir video', path: `/app/reels/${videoTargetId}` }

  if (targetType === 'group' && messageTargetId) {
    return { label: 'Abrir grupo', path: `/app/messages/group/${messageTargetId}` }
  }
  if (targetType === 'community' && messageTargetId) {
    return { label: 'Abrir comunidad', path: `/app/messages/community/${messageTargetId}` }
  }
  if ((targetType === 'chat' || type.includes('message')) && messageTargetId) {
    return { label: 'Abrir chat', path: `/app/messages/${messageTargetId}` }
  }

  if (type === 'friend_request' || type === 'friend_request_accepted') {
    return { label: 'Ver solicitudes', path: '/app/friend-requests' }
  }
  if (type === 'follow') {
    return { label: 'Ver social', path: '/app/follows' }
  }
  if (targetType === 'profile' && item.targetId) {
    return { label: 'Ver perfil', path: `/app/profile/${item.targetId}` }
  }
  if (item.actorId) {
    return { label: 'Ver perfil', path: `/app/profile/${item.actorId}` }
  }

  return null
}

export function NotificationsPage() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const isUsingFallback = Boolean(readFallbackSession())
  const [items, setItems] = useState<NotificationItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [actionMessage, setActionMessage] = useState<string | null>(null)
  const [activeFilter, setActiveFilter] = useState<NotificationsFilter>('all')
  const [query, setQuery] = useState('')
  const [visibleCount, setVisibleCount] = useState(40)
  const [markingVisible, setMarkingVisible] = useState(false)
  const [openingId, setOpeningId] = useState<string | null>(null)

  const unreadCount = useMemo(() => items.filter((item) => !item.isRead).length, [items])
  const normalizedQuery = useMemo(() => query.trim().toLowerCase(), [query])
  const filterCounts = useMemo(() => {
    const unread = items.filter((item) => !item.isRead).length
    const messages = items.filter((item) => isMessageNotification(item)).length
    const moderation = items.filter((item) => isModerationNotification(item)).length
    const social = items.filter(
      (item) => !isMessageNotification(item) && !isModerationNotification(item),
    ).length
    return {
      all: items.length,
      unread,
      messages,
      social,
      moderation,
    }
  }, [items])
  const filteredItems = useMemo(
    () =>
      items.filter((item) => {
        if (!matchesFilter(item, activeFilter)) return false
        if (!normalizedQuery) return true
        const haystack =
          `${typeLabel(item.type)} ${item.content} ${item.actorId} ${item.targetType} ${item.targetId}`.toLowerCase()
        return haystack.includes(normalizedQuery)
      }),
    [activeFilter, items, normalizedQuery],
  )
  const visibleItems = useMemo(() => filteredItems.slice(0, visibleCount), [filteredItems, visibleCount])
  const hasMore = filteredItems.length > visibleCount

  useEffect(() => {
    setVisibleCount(40)
  }, [activeFilter, normalizedQuery])

  const loadNotifications = useCallback(async () => {
    if (!user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      if (isUsingFallback) {
        const payload = await loadFallbackNotifications(200)
        setItems(((payload.items ?? []) as GenericRow[]).map(mapNotification))
        return
      }

      if (!supabase) {
        setItems([])
        return
      }

      const { data, error: queryError } = await supabase
        .from('notifications')
        .select('*')
        .eq('recipient_id', user.id)
        .order('created_at', { ascending: false })
        .limit(200)
      if (queryError) throw queryError
      setItems(((data ?? []) as GenericRow[]).map(mapNotification))
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('notifications-load', caught)
      if (isMissingTableError(caught)) {
        setError('La tabla notifications no existe en este entorno Supabase.')
      } else {
        setError(friendlyError(caught, 'No se pudieron cargar notificaciones'))
      }
    } finally {
      setLoading(false)
    }
  }, [isUsingFallback, user])

  useEffect(() => {
    void loadNotifications()
  }, [loadNotifications])

  useEffect(() => {
    const client = supabase
    if (isUsingFallback || !client || !user) return
    const channel = client
      .channel('web-notifications-live')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'notifications', filter: `recipient_id=eq.${user.id}` },
        () => {
          void loadNotifications()
        },
      )
    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: 'notifications-live' })
    return () => {
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [isUsingFallback, loadNotifications, user])

  async function markNotificationsReadRemote(notificationIds: string[], markAll: boolean): Promise<void> {
    if (!user) {
      throw new Error('AUTH_REQUIRED')
    }
    if (isUsingFallback) {
      if (markAll) {
        await markAllFallbackNotificationsRead()
        return
      }
      await Promise.all(notificationIds.map((notificationId) => markFallbackNotificationRead(notificationId)))
      return
    }
    if (!supabase) {
      throw new Error('SUPABASE_NOT_CONFIGURED')
    }
    const client = supabase

    const rpcResult = await client.rpc('secure_mark_notifications_read', {
      p_notification_ids: markAll ? [] : notificationIds,
      p_mark_all: markAll,
    })
    if (rpcResult.error && !isMissingFunctionError(rpcResult.error)) {
      throw rpcResult.error
    }
    if (!rpcResult.error) {
      return
    }

    if (markAll) {
      const { error: fallbackError } = await client
        .from('notifications')
        .update({ is_read: true })
        .eq('recipient_id', user.id)
        .eq('is_read', false)
      if (fallbackError) throw fallbackError
      return
    }

    if (notificationIds.length === 1) {
      const { error: fallbackError } = await client
        .from('notifications')
        .update({ is_read: true })
        .eq('id', notificationIds[0])
        .eq('recipient_id', user.id)
      if (fallbackError) throw fallbackError
      return
    }

    const { error: fallbackError } = await client
      .from('notifications')
      .update({ is_read: true })
      .eq('recipient_id', user.id)
      .in('id', notificationIds)
    if (fallbackError) throw fallbackError
  }

  async function markAsRead(notificationId: string, silent = false): Promise<boolean> {
    if (!user || (!isUsingFallback && !supabase)) return false
    if (!notificationId.trim()) return false

    setItems((prev) => prev.map((item) => (item.id === notificationId ? { ...item, isRead: true } : item)))
    setActionMessage(null)

    if (!isBrowserOnline()) {
      enqueueDeferredMutation('notification_mark_read', { notificationId })
      if (!silent) {
        setActionMessage('Sin conexión: la notificación se marcará en cuanto vuelva la red.')
      }
      return true
    }

    try {
      await runWithRetry(() => markNotificationsReadRemote([notificationId], false), {
        maxRetries: 2,
        shouldRetry: isLikelyTransientError,
      })
      return true
    } catch (caught) {
      if (isLikelyTransientError(caught)) {
        enqueueDeferredMutation('notification_mark_read', { notificationId })
        if (!silent) {
          setActionMessage('Sincronización diferida: se completará automáticamente.')
        }
        return true
      }
      setItems((prev) => prev.map((item) => (item.id === notificationId ? { ...item, isRead: false } : item)))
      if (!silent) {
        setActionMessage(friendlyError(caught, 'No se pudo marcar notificación'))
      }
      return false
    }
  }

  async function markAllAsRead() {
    if (!user || (!isUsingFallback && !supabase)) return
    const previous = items
    setItems((prev) => prev.map((item) => ({ ...item, isRead: true })))
    setActionMessage(null)

    if (!isBrowserOnline()) {
      enqueueDeferredMutation('notification_mark_all', {})
      setActionMessage('Sin conexión: el marcado global quedó en cola de sincronización.')
      return
    }

    try {
      await runWithRetry(() => markNotificationsReadRemote([], true), {
        maxRetries: 2,
        shouldRetry: isLikelyTransientError,
      })
    } catch (caught) {
      if (isLikelyTransientError(caught)) {
        enqueueDeferredMutation('notification_mark_all', {})
        setActionMessage('Sincronización diferida: se completará automáticamente.')
        return
      }
      setItems(previous)
      setActionMessage(friendlyError(caught, 'No se pudieron marcar todas como leídas'))
    }
  }

  async function markVisibleAsRead() {
    if (!user || (!isUsingFallback && !supabase) || markingVisible) return
    const unreadIds = filteredItems.filter((item) => !item.isRead).map((item) => item.id)
    if (unreadIds.length === 0) return

    setMarkingVisible(true)
    setActionMessage(null)
    const previous = items
    setItems((prev) =>
      prev.map((item) => (unreadIds.includes(item.id) ? { ...item, isRead: true } : item)),
    )
    try {
      if (!isBrowserOnline()) {
        unreadIds.forEach((notificationId) => {
          enqueueDeferredMutation('notification_mark_read', { notificationId })
        })
        setActionMessage('Sin conexión: el marcado visible quedó en cola de sincronización.')
        return
      }

      await runWithRetry(() => markNotificationsReadRemote(unreadIds, false), {
        maxRetries: 2,
        shouldRetry: isLikelyTransientError,
      })
    } catch (caught) {
      if (isLikelyTransientError(caught)) {
        unreadIds.forEach((notificationId) => {
          enqueueDeferredMutation('notification_mark_read', { notificationId })
        })
        setActionMessage('Sincronización diferida: se completará automáticamente.')
      } else {
        setItems(previous)
        setActionMessage(friendlyError(caught, 'No se pudieron marcar las notificaciones visibles'))
      }
    } finally {
      setMarkingVisible(false)
    }
  }

  async function openItem(item: NotificationItem) {
    const action = resolveNotificationAction(item)
    if (!action || openingId) return

    setOpeningId(item.id)
    setActionMessage(null)
    try {
      if (!item.isRead) {
        await markAsRead(item.id, true)
      }
      navigate(action.path)
    } finally {
      setOpeningId(null)
    }
  }

  if (!isUsingFallback && !supabase) {
    return <StatusView title="Supabase no configurado" description="Configura .env para usar notificaciones." />
  }
  if (loading) return <StatusView title="Cargando notificaciones" description="Espera un momento..." />
  if (error) return <StatusView title="Notificaciones no disponibles" description={error} />

  return (
    <section className="section-stack">
      <header className="section-header row-between">
        <div>
          <h2>Notificaciones</h2>
          <p>
            {unreadCount} sin leer · {filteredItems.length} visibles
          </p>
        </div>
      </header>

      <article className="surface-card notification-toolbar">
        <input
          className="text-input"
          placeholder="Buscar por tipo, actor o contenido"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <div className="chip-row">
          <button
            className={activeFilter === 'all' ? 'chip-btn active' : 'chip-btn'}
            type="button"
            onClick={() => setActiveFilter('all')}
          >
            Todas ({filterCounts.all})
          </button>
          <button
            className={activeFilter === 'unread' ? 'chip-btn active' : 'chip-btn'}
            type="button"
            onClick={() => setActiveFilter('unread')}
          >
            Sin leer ({filterCounts.unread})
          </button>
          <button
            className={activeFilter === 'messages' ? 'chip-btn active' : 'chip-btn'}
            type="button"
            onClick={() => setActiveFilter('messages')}
          >
            Mensajes ({filterCounts.messages})
          </button>
          <button
            className={activeFilter === 'social' ? 'chip-btn active' : 'chip-btn'}
            type="button"
            onClick={() => setActiveFilter('social')}
          >
            Social ({filterCounts.social})
          </button>
          <button
            className={activeFilter === 'moderation' ? 'chip-btn active' : 'chip-btn'}
            type="button"
            onClick={() => setActiveFilter('moderation')}
          >
            Moderación ({filterCounts.moderation})
          </button>
        </div>
        <div className="row-actions">
          <button className="ghost-btn" type="button" disabled={markingVisible} onClick={() => void markVisibleAsRead()}>
            {markingVisible ? 'Procesando...' : 'Marcar visibles leídas'}
          </button>
          <button className="ghost-btn" type="button" onClick={() => void markAllAsRead()}>
            Marcar todo leído
          </button>
        </div>
      </article>

      {actionMessage ? (
        <article className="surface-card">
          <small>{actionMessage}</small>
        </article>
      ) : null}

      {items.length === 0 ? (
        <StatusView title="Sin notificaciones" description="Cuando ocurra actividad te llegará aquí." />
      ) : filteredItems.length === 0 ? (
        <StatusView title="Sin resultados" description="Ajusta tus filtros o búsqueda." />
      ) : (
        <div className="list-stack">
          {visibleItems.map((item) => {
            const action = resolveNotificationAction(item)
            const isOpening = openingId === item.id
            return (
              <article key={item.id} className={item.isRead ? 'surface-card' : 'surface-card unread-card'}>
              <div className="row-between">
                <strong>{typeLabel(item.type)}</strong>
                <small>{formatDateTime(item.createdAt)}</small>
              </div>
              <p>{item.content || `Actividad por @${item.actorId}`}</p>
              <div className="notification-meta">
                <small>Actor: {item.actorId || 'sistema'}</small>
                <small>Target: {item.targetType || 'general'}</small>
                <span className={item.isRead ? 'notification-state read' : 'notification-state unread'}>
                  {item.isRead ? 'Leída' : 'Sin leer'}
                </span>
              </div>
              <div className="row-actions">
                {action ? (
                  <button className="ghost-btn" type="button" disabled={isOpening} onClick={() => void openItem(item)}>
                    {isOpening ? 'Abriendo...' : action.label}
                  </button>
                ) : null}
                {!item.isRead ? (
                  <button className="ghost-btn" type="button" onClick={() => void markAsRead(item.id)}>
                    Marcar leída
                  </button>
                ) : null}
              </div>
            </article>
            )
          })}
          {hasMore ? (
            <div className="notification-list-footer">
              <button className="ghost-btn" type="button" onClick={() => setVisibleCount((current) => current + 40)}>
                Cargar más
              </button>
            </div>
          ) : null}
        </div>
      )}
    </section>
  )
}
