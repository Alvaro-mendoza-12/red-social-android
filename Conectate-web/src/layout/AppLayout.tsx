import { useCallback, useEffect, useMemo, useState } from 'react'
import { Bell, Plus, Settings, Trophy, Users } from 'lucide-react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import {
  type DeferredMutation,
  flushDeferredMutations,
  getDeferredMutationCount,
  isBrowserOnline,
} from '../core/reliability'
import {
  getSupabaseRestrictionSnapshot,
  onSupabaseRestrictionChanged,
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
} from '../core/supabase'
import { subscribeUbuntuRealtime } from '../core/ubuntuRealtime'
import { textOf, type GenericRow } from '../core/utils'
import { useAuth } from '../core/auth/AuthProvider'
import { changeFallbackPassword } from '../core/auth/fallbackAuth'
import {
  loadFallbackMessagesInbox,
  loadFallbackNotifications,
  markAllFallbackNotificationsRead,
  markFallbackNotificationRead,
} from '../core/fallbackData'
import { useWebPreferences } from '../core/webPreferences'
import { sendCallResponse, type CallInvitePayload } from '../features/calls/callRealtime'
import '../features/calls/incomingCallOverlay.css'

type NavIconName =
  | 'social'
  | 'videos'
  | 'messages'
  | 'calls'
  | 'profile'
  | 'voice'
  | 'search'
  | 'notifications'
  | 'monetization'
  | 'settings'

const primaryNavItems = [
  { to: '/app/messages', label: 'Mensajes', icon: 'messages' as NavIconName },
  { to: '/app/videos', label: 'Videos', icon: 'videos' as NavIconName },
  { to: '/app/social', label: 'Social', icon: 'social' as NavIconName },
  { to: '/app/profile', label: 'Perfil', icon: 'profile' as NavIconName },
]

const localReadStorageKey = 'conectate-web-chat-last-read'
const navCollapsedStorageKey = 'conectate-web-nav-collapsed'
const mobileNavItems = primaryNavItems

type IncomingCallInvite = CallInvitePayload & {
  receivedAt: number
}

type ShellRouteMeta = {
  kicker: string
  title: string
  subtitle: string
  layout: 'wide' | 'full'
}

const shellRouteMetaCatalog: Array<{ match: (pathname: string) => boolean; meta: ShellRouteMeta }> = [
  {
    match: (pathname) => pathname.startsWith('/app/messages'),
    meta: {
      kicker: 'Mensajería',
      title: 'Bandeja social',
      subtitle: 'Chats, grupos y comunidades con acceso rápido a llamadas, voz y perfiles.',
      layout: 'full',
    },
  },
  {
    match: (pathname) => pathname.startsWith('/app/social'),
    meta: {
      kicker: '',
      title: 'Conéctate',
      subtitle: '',
      layout: 'wide',
    },
  },
  {
    match: (pathname) => pathname.startsWith('/app/videos') || pathname.startsWith('/app/reels'),
    meta: {
      kicker: 'Videos',
      title: 'Videos cortos',
      subtitle: 'Explora reels, prepara publicaciones y revisa el contenido antes de compartir.',
      layout: 'wide',
    },
  },
  {
    match: (pathname) => pathname.startsWith('/app/calls'),
    meta: {
      kicker: 'Llamadas',
      title: 'Audio y video',
      subtitle: 'Inicia conversaciones 1:1 y vuelve a tus chats cuando necesites contexto.',
      layout: 'wide',
    },
  },
  {
    match: (pathname) => pathname.startsWith('/app/voice'),
    meta: {
      kicker: 'Voz',
      title: 'Salas de voz',
      subtitle: 'Entra a canales activos, escucha a la comunidad y retoma salas recientes.',
      layout: 'wide',
    },
  },
  {
    match: (pathname) => pathname.startsWith('/app/notifications'),
    meta: {
      kicker: 'Actividad',
      title: 'Alertas accionables',
      subtitle: 'Mensajes, social y avisos importantes agrupados para responder rápido.',
      layout: 'wide',
    },
  },
  {
    match: (pathname) => pathname.startsWith('/app/profile'),
    meta: {
      kicker: '',
      title: 'Perfil',
      subtitle: '',
      layout: 'wide',
    },
  },
  {
    match: (pathname) => pathname.startsWith('/app/settings') || pathname.startsWith('/app/monetization'),
    meta: {
      kicker: 'Control',
      title: 'Cuenta, wallet y ajustes',
      subtitle: 'Administra seguridad, preferencias, monetización y experiencia de lectura.',
      layout: 'wide',
    },
  },
]

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

function toMillis(value: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function readLocalReadByChat(): Record<string, string> {
  if (typeof window === 'undefined') return {}
  try {
    const raw = window.localStorage.getItem(localReadStorageKey)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return Object.fromEntries(
      Object.entries(parsed as Record<string, unknown>)
        .filter(([chatId, value]) => typeof value === 'string' && Boolean(chatId.trim()))
        .map(([chatId, value]) => [chatId.trim(), String(value)] as const),
    )
  } catch {
    return {}
  }
}

function readNavCollapsedPreference(): boolean {
  if (typeof window === 'undefined') return false
  try {
    return window.localStorage.getItem(navCollapsedStorageKey) === '1'
  } catch {
    return false
  }
}

function resolveShellRouteMeta(pathname: string): ShellRouteMeta {
  return (
    shellRouteMetaCatalog.find((entry) => entry.match(pathname))?.meta ?? {
      kicker: 'Conectate Web',
      title: 'Experiencia conectada',
      subtitle: 'Mensajes, social, videos y voz listos para trabajar como una app diaria.',
      layout: 'wide',
    }
  )
}

function metadataBoolean(value: unknown): boolean {
  return value === true || String(value ?? '').toLowerCase() === 'true'
}

function passwordChangeErrorMessage(error: unknown): string {
  const code = error instanceof Error ? error.message : String(error ?? '')
  if (code === 'INVALID_CREDENTIALS') return 'La contraseña actual no coincide.'
  if (code === 'CURRENT_AND_NEW_PASSWORD_REQUIRED') return 'Escribe tu contraseña actual y una nueva contraseña.'
  if (code === 'NEW_PASSWORD_MUST_BE_DIFFERENT') return 'La contraseña nueva debe ser diferente a la actual.'
  if (code === 'AUTH_REQUIRED' || code === 'INVALID_TOKEN') return 'Tu sesión expiró. Vuelve a iniciar sesión.'
  return code || 'No se pudo actualizar la contraseña.'
}

function ChevronIcon({ collapsed }: { collapsed: boolean }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      {collapsed ? (
        <path d="M9 6l6 6-6 6" />
      ) : (
        <path d="M15 6l-6 6 6 6" />
      )}
    </svg>
  )
}

function ShellIcon({ name }: { name: NavIconName }) {
  if (name === 'videos') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M4 6.5h16v11H4zM9.5 6.5l3 3M14.5 6.5l3 3M5.5 9.5h13M10.5 11.5l4 2.5-4 2.5z" />
      </svg>
    )
  }
  if (name === 'social') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M7 11a3 3 0 100-6 3 3 0 000 6zM17 10a2.5 2.5 0 100-5 2.5 2.5 0 000 5zM2.5 18.5a4.5 4.5 0 019 0M12.5 18.5a4 4 0 017.5-1.7" />
      </svg>
    )
  }
  if (name === 'messages') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M5 6.5h14v9H9l-4 3v-12zM9 10h6M9 13h4" />
      </svg>
    )
  }
  if (name === 'calls') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M6.8 4.8h3.1l1.2 3.6-1.8 1.8a15.2 15.2 0 006.6 6.6l1.8-1.8 3.6 1.2v3.1a1.7 1.7 0 01-1.9 1.7C10.4 20.3 3.7 13.6 3.1 6.7A1.7 1.7 0 014.8 4.8z" />
      </svg>
    )
  }
  if (name === 'profile') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M12 11a3.5 3.5 0 100-7 3.5 3.5 0 000 7zM5 20a7 7 0 0114 0" />
      </svg>
    )
  }
  if (name === 'voice') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M12 5a3 3 0 013 3v4a3 3 0 11-6 0V8a3 3 0 013-3zM6 11v1a6 6 0 0012 0v-1M12 18v3" />
      </svg>
    )
  }
  if (name === 'search') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M11 4a7 7 0 105.2 11.7L20 19.5" />
      </svg>
    )
  }
  if (name === 'notifications') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M6 16h12l-1.2-1.8a4 4 0 01-.8-2.2V10a4 4 0 10-8 0v2a4 4 0 01-.8 2.2L6 16zM10 18a2 2 0 004 0" />
      </svg>
    )
  }
  if (name === 'settings') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M12 8.5a3.5 3.5 0 100 7 3.5 3.5 0 000-7zM4.5 12l1.8-.7.4-1.1-1-1.6 1.8-1.8 1.6 1 .1-.1 1.1-.4L12 4.5l1.7.8 1.1.4.1.1 1.6-1 1.8 1.8-1 1.6.4 1.1 1.8.7-.8 2-1.8.7-.4 1.1 1 1.6-1.8 1.8-1.6-1-.1.1-1.1.4L12 19.5l-1.7-.8-1.1-.4-.1-.1-1.6 1-1.8-1.8 1-1.6-.4-1.1-1.8-.7z" />
      </svg>
    )
  }
  if (name === 'monetization') {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M12 3v18M7 7.5h7a3 3 0 010 6h-4a3 3 0 000 6h7M5 12h14" />
      </svg>
    )
  }
  return null
}

export function AppLayout() {
  const { user, signOut, isFallbackSession } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [webPreferences] = useWebPreferences()
  const [messageUnreadCount, setMessageUnreadCount] = useState(0)
  const [notificationUnreadCount, setNotificationUnreadCount] = useState(0)
  const [isOnline, setIsOnline] = useState(isBrowserOnline())
  const [pendingDeferredMutations, setPendingDeferredMutations] = useState(getDeferredMutationCount())
  const [isNavCollapsed, setIsNavCollapsed] = useState(() => readNavCollapsedPreference())
  const [supabaseRestriction, setSupabaseRestriction] = useState(() => getSupabaseRestrictionSnapshot())
  const [incomingCall, setIncomingCall] = useState<IncomingCallInvite | null>(null)
  const [migrationPasswordOpen, setMigrationPasswordOpen] = useState(false)
  const [migrationCurrentPassword, setMigrationCurrentPassword] = useState('')
  const [migrationNewPassword, setMigrationNewPassword] = useState('')
  const [migrationConfirmPassword, setMigrationConfirmPassword] = useState('')
  const [migrationPasswordError, setMigrationPasswordError] = useState<string | null>(null)
  const [migrationPasswordInfo, setMigrationPasswordInfo] = useState<string | null>(null)
  const [savingMigrationPassword, setSavingMigrationPassword] = useState(false)

  const loadShellCounters = useCallback(async () => {
    if (isFallbackSession && user) {
      try {
        const [notifications, inbox] = await Promise.all([
          loadFallbackNotifications(500),
          loadFallbackMessagesInbox('chats'),
        ])
        setNotificationUnreadCount(
          Number.isFinite(Number(notifications.unreadCount))
            ? Number(notifications.unreadCount)
            : (notifications.items ?? []).filter((row) => !(row.is_read ?? row.isRead)).length,
        )
        setMessageUnreadCount(
          (inbox.items ?? []).reduce(
            (total, item) => total + Math.max(0, Number(item.unreadCount) || 0),
            0,
          ),
        )
      } catch (caught) {
        console.warn('loadShellCounters fallback failed', caught)
      }
      return
    }

    const client = supabase
    if (!client || !user) {
      setMessageUnreadCount(0)
      setNotificationUnreadCount(0)
      return
    }

    try {
      const [{ data: notificationRows }, { data: chatRows, error: chatError }] = await Promise.all([
        client
          .from('notifications')
          .select('id')
          .eq('recipient_id', user.id)
          .eq('is_read', false)
          .limit(500),
        client
          .from('chats')
          .select('id,participants')
          .contains('participants', [user.id])
          .eq('is_group', false)
          .limit(120),
      ])

      setNotificationUnreadCount((notificationRows ?? []).length)
      if (chatError) throw chatError

      const chats = (chatRows ?? []) as GenericRow[]
      const chatIds = chats.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      if (chatIds.length === 0) {
        setMessageUnreadCount(0)
        return
      }

      const { data: participantRows } = await client
        .from('chat_participants')
        .select('chat_id,last_read_at')
        .eq('user_id', user.id)
        .in('chat_id', chatIds)

      const participantLastReadByChat = new Map<string, string>()
      ;((participantRows ?? []) as GenericRow[]).forEach((row) => {
        const chatId = textOf(row, ['chat_id'], '')
        if (!chatId) return
        participantLastReadByChat.set(chatId, textOf(row, ['last_read_at'], ''))
      })

      const localReadByChat = readLocalReadByChat()

      const { data: messageRows, error: messageError } = await client
        .from('messages')
        .select('chat_id,sender_id,created_at,read_at,status')
        .in('chat_id', chatIds)
        .neq('sender_id', user.id)
      if (messageError) throw messageError

      let unreadTotal = 0
      ;((messageRows ?? []) as GenericRow[]).forEach((row) => {
        const chatId = textOf(row, ['chat_id'], '')
        if (!chatId) return
        const status = textOf(row, ['status'], '').toLowerCase()
        if (textOf(row, ['read_at'], '') || status === 'read' || status === 'seen') {
          return
        }
        const lastReadMillis = Math.max(
          toMillis(participantLastReadByChat.get(chatId) ?? ''),
          toMillis(localReadByChat[chatId] ?? ''),
        )
        const createdAtMillis = toMillis(textOf(row, ['created_at'], ''))
        if (lastReadMillis > 0 && createdAtMillis > 0 && createdAtMillis <= lastReadMillis) {
          return
        }
        unreadTotal += 1
      })

      setMessageUnreadCount(unreadTotal)
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('shell-counters', caught)
      console.warn('loadShellCounters failed', caught)
    }
  }, [isFallbackSession, user])

  useEffect(() => {
    void loadShellCounters()
  }, [loadShellCounters])

  useEffect(() => {
    if (isFallbackSession && user) {
      let refreshTimer: ReturnType<typeof setTimeout> | null = null
      const scheduleRefresh = () => {
        if (refreshTimer) clearTimeout(refreshTimer)
        refreshTimer = setTimeout(() => {
          void loadShellCounters()
        }, 250)
      }
      return subscribeUbuntuRealtime({
        channel: `user:${user.id}`,
        onEvent: (event) => {
          if (event.payload?.event === 'call_invite') {
            const callId = String(event.payload.callId ?? '').trim()
            const callerUserId = String(event.payload.callerUserId ?? '').trim()
            if (!callId || !callerUserId || callerUserId === user.id) return
            setIncomingCall({
              callId,
              title: String(event.payload.title ?? '').trim() || 'Llamada',
              callerUserId,
              callerName: String(event.payload.callerName ?? '').trim() || 'Usuario',
              callerAvatarUrl: String(event.payload.callerAvatarUrl ?? '').trim(),
              contextType: String(event.payload.contextType ?? '').trim() || 'dm',
              contextId: String(event.payload.contextId ?? '').trim(),
              isVideo: Boolean(event.payload.isVideo),
              receivedAt: Date.now(),
            })
          }
          scheduleRefresh()
        },
      })
    }
    const client = supabase
    if (isFallbackSession || !client || !user) return
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRefresh = () => {
      if (refreshTimer) {
        clearTimeout(refreshTimer)
      }
      refreshTimer = setTimeout(() => {
        void loadShellCounters()
      }, 250)
    }

    const channel = client
      .channel(`conectate-web-shell-counters-${user.id}`)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'messages' }, scheduleRefresh)
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'chat_participants', filter: `user_id=eq.${user.id}` },
        scheduleRefresh,
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'notifications', filter: `recipient_id=eq.${user.id}` },
        scheduleRefresh,
      )
    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: 'shell-counters' })

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [isFallbackSession, loadShellCounters, user])

  useEffect(() => {
    return onSupabaseRestrictionChanged(setSupabaseRestriction)
  }, [])

  useEffect(() => {
    const client = supabase
    if (!client || !user) {
      setIncomingCall(null)
      return
    }

    const channel = client
      .channel(`conectate-web-call-user-${user.id}`)
      .on('broadcast', { event: 'call_invite' }, (event) => {
        const payload = (event.payload ?? {}) as Partial<CallInvitePayload>
        const callId = String(payload.callId ?? '').trim()
        const callerUserId = String(payload.callerUserId ?? '').trim()
        if (!callId || !callerUserId || callerUserId === user.id) return

        setIncomingCall({
          callId,
          title: String(payload.title ?? '').trim() || 'Llamada',
          callerUserId,
          callerName: String(payload.callerName ?? '').trim() || 'Usuario',
          callerAvatarUrl: String(payload.callerAvatarUrl ?? '').trim(),
          contextType: String(payload.contextType ?? '').trim() || 'dm',
          contextId: String(payload.contextId ?? '').trim(),
          isVideo: Boolean(payload.isVideo),
          receivedAt: Date.now(),
        })
      })
      .on('broadcast', { event: 'call_response' }, (event) => {
        const payload = (event.payload ?? {}) as { callId?: string; action?: string }
        if (incomingCall?.callId && payload.callId === incomingCall.callId) {
          setIncomingCall(null)
        }
      })

    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: 'web-call-user' })
    return () => {
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [incomingCall?.callId, user])

  const acceptIncomingCall = useCallback(() => {
    const invite = incomingCall
    const client = supabase
    if (!invite || !client || !user) return
    setIncomingCall(null)
    void sendCallResponse(client, invite.callerUserId, {
      callId: invite.callId,
      action: 'accept',
      fromUserId: user.id,
    })
    const params = new URLSearchParams({
      callId: invite.callId,
      join: '1',
      target: invite.callerUserId,
      name: invite.callerName,
      kind: invite.isVideo ? 'video' : 'audio',
    })
    navigate(`/app/calls?${params.toString()}`)
  }, [incomingCall, navigate, user])

  const declineIncomingCall = useCallback(() => {
    const invite = incomingCall
    const client = supabase
    if (!invite || !client || !user) return
    setIncomingCall(null)
    void sendCallResponse(client, invite.callerUserId, {
      callId: invite.callId,
      action: 'decline',
      fromUserId: user.id,
    })
  }, [incomingCall, user])

  useEffect(() => {
    if (typeof window === 'undefined') return
    const onLocalReadUpdated = () => {
      void loadShellCounters()
    }
    window.addEventListener('conectate-web-local-read-updated', onLocalReadUpdated)
    return () => {
      window.removeEventListener('conectate-web-local-read-updated', onLocalReadUpdated)
    }
  }, [loadShellCounters])

  const executeDeferredMutation = useCallback(
    async (mutation: DeferredMutation) => {
      if (isFallbackSession && user) {
        if (mutation.type === 'notification_mark_read') {
          const notificationId = String((mutation.payload as { notificationId?: string }).notificationId ?? '')
          if (notificationId) {
            await markFallbackNotificationRead(notificationId)
          }
          return
        }

        if (mutation.type === 'notification_mark_all') {
          await markAllFallbackNotificationsRead()
          return
        }
      }

      const client = supabase
      if (!client || !user) {
        throw new Error('AUTH_REQUIRED_FOR_DEFERRED_MUTATION')
      }
      const nowIso = new Date().toISOString()

      if (mutation.type === 'notification_mark_read') {
        const notificationId = String((mutation.payload as { notificationId?: string }).notificationId ?? '')
        if (!notificationId) return

        const rpcResult = await client.rpc('secure_mark_notifications_read', {
          p_notification_ids: [notificationId],
          p_mark_all: false,
        })
        if (rpcResult.error && !isMissingFunctionError(rpcResult.error)) {
          throw rpcResult.error
        }
        if (rpcResult.error && isMissingFunctionError(rpcResult.error)) {
          const { error } = await client
            .from('notifications')
            .update({ is_read: true })
            .eq('id', notificationId)
            .eq('recipient_id', user.id)
          if (error) throw error
        }
        return
      }

      if (mutation.type === 'notification_mark_all') {
        const rpcResult = await client.rpc('secure_mark_notifications_read', {
          p_notification_ids: [],
          p_mark_all: true,
        })
        if (rpcResult.error && !isMissingFunctionError(rpcResult.error)) {
          throw rpcResult.error
        }
        if (rpcResult.error && isMissingFunctionError(rpcResult.error)) {
          const { error } = await client
            .from('notifications')
            .update({ is_read: true })
            .eq('recipient_id', user.id)
            .eq('is_read', false)
          if (error) throw error
        }
        return
      }

      if (mutation.type === 'chat_mark_read') {
        const chatId = String((mutation.payload as { chatId?: string }).chatId ?? '').trim().toLowerCase()
        if (!chatId) return
        const { error: deliveredError } = await client.rpc('secure_mark_messages_delivered', {
          p_chat_id: chatId,
        })
        if (deliveredError && !isMissingFunctionError(deliveredError)) {
          throw deliveredError
        }
        const { error: readError } = await client.rpc('secure_mark_messages_read', {
          p_chat_id: chatId,
        })
        if (readError && !isMissingFunctionError(readError)) {
          throw readError
        }
        const { error: participantError } = await client.from('chat_participants').upsert(
          {
            chat_id: chatId,
            user_id: user.id,
            joined_at: nowIso,
            last_read_at: nowIso,
          },
          { onConflict: 'chat_id,user_id' },
        )
        if (participantError) throw participantError
      }
    },
    [isFallbackSession, user],
  )

  useEffect(() => {
    if (typeof window === 'undefined') return
    const onOnline = () => setIsOnline(true)
    const onOffline = () => setIsOnline(false)
    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)
    return () => {
      window.removeEventListener('online', onOnline)
      window.removeEventListener('offline', onOffline)
    }
  }, [])

  useEffect(() => {
    if (typeof window === 'undefined') return
    const syncPendingCounter = () => {
      setPendingDeferredMutations(getDeferredMutationCount())
    }
    window.addEventListener('conectate-web-deferred-queue-updated', syncPendingCounter)
    return () => {
      window.removeEventListener('conectate-web-deferred-queue-updated', syncPendingCounter)
    }
  }, [])

  useEffect(() => {
    if (!isOnline || !user || (!isFallbackSession && !supabase)) return
    let isCancelled = false

    const flush = async () => {
      const result = await flushDeferredMutations(executeDeferredMutation)
      if (isCancelled) return
      setPendingDeferredMutations(result.remaining)
      if (result.processed > 0) {
        await loadShellCounters()
      }
    }

    void flush()
    const intervalId = setInterval(() => {
      if (!isCancelled && isBrowserOnline()) {
        void flush()
      }
    }, 25_000)

    return () => {
      isCancelled = true
      clearInterval(intervalId)
    }
  }, [executeDeferredMutation, isFallbackSession, isOnline, loadShellCounters, user])

  useEffect(() => {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(navCollapsedStorageKey, isNavCollapsed ? '1' : '0')
    } catch {
      // Ignore local storage write failures.
    }
  }, [isNavCollapsed])

  const isMessagesRoute = location.pathname.startsWith('/app/messages')
  const isSocialRoute = location.pathname.startsWith('/app/social')
  const isProfileRoute = location.pathname === '/app/profile'
  const shellMeta = useMemo(() => resolveShellRouteMeta(location.pathname), [location.pathname])
  const contentShellClassName = shellMeta.layout === 'full' ? 'content-shell full' : 'content-shell wide'
  const requiresMigrationPasswordChange = Boolean(
    isFallbackSession && metadataBoolean(user?.user_metadata?.password_change_required),
  )

  const submitMigrationPasswordChange = useCallback(async () => {
    if (savingMigrationPassword) return
    const currentPassword = migrationCurrentPassword.trim()
    const nextPassword = migrationNewPassword.trim()
    const confirmation = migrationConfirmPassword.trim()

    if (!currentPassword || nextPassword.length < 8) {
      setMigrationPasswordError('Escribe tu contraseña actual y una nueva de al menos 8 caracteres.')
      return
    }
    if (nextPassword !== confirmation) {
      setMigrationPasswordError('La confirmación no coincide con la contraseña nueva.')
      return
    }

    setSavingMigrationPassword(true)
    setMigrationPasswordError(null)
    setMigrationPasswordInfo(null)
    try {
      await changeFallbackPassword(currentPassword, nextPassword)
      setMigrationCurrentPassword('')
      setMigrationNewPassword('')
      setMigrationConfirmPassword('')
      setMigrationPasswordOpen(false)
      setMigrationPasswordInfo('Contraseña actualizada. Este cambio queda guardado para el servidor local.')
    } catch (caught) {
      setMigrationPasswordError(passwordChangeErrorMessage(caught))
    } finally {
      setSavingMigrationPassword(false)
    }
  }, [
    migrationConfirmPassword,
    migrationCurrentPassword,
    migrationNewPassword,
    savingMigrationPassword,
  ])

  const navBadgeByPath: Record<string, number> = {
    '/app/messages': messageUnreadCount,
    '/app/social': notificationUnreadCount,
    '/app/notifications': notificationUnreadCount,
  }

  return (
    <div
      className={[
        'app-shell',
        isNavCollapsed ? 'nav-collapsed' : '',
        isMessagesRoute ? 'route-messages' : '',
        `theme-${webPreferences.themeMode}`,
        `accent-${webPreferences.accentTheme}`,
        `text-scale-${webPreferences.textScale}`,
      ].filter(Boolean).join(' ')}
    >
      {incomingCall ? (
        <div className="incoming-call-overlay" role="dialog" aria-modal="true" aria-label="Llamada entrante">
          <div className="incoming-call-panel">
            <div className="incoming-call-avatar">
              {incomingCall.callerAvatarUrl ? (
                <img src={incomingCall.callerAvatarUrl} alt="" />
              ) : (
                <span>{incomingCall.callerName.trim().charAt(0).toUpperCase() || 'U'}</span>
              )}
            </div>
            <div className="incoming-call-copy">
              <span>{incomingCall.isVideo ? 'Videollamada entrante' : 'Llamada entrante'}</span>
              <strong>{incomingCall.callerName}</strong>
              <p>{incomingCall.title || 'Conectate'}</p>
            </div>
            <div className="incoming-call-actions">
              <button className="incoming-call-btn decline" type="button" onClick={declineIncomingCall}>
                Rechazar
              </button>
              <button className="incoming-call-btn accept" type="button" onClick={acceptIncomingCall}>
                Aceptar
              </button>
            </div>
          </div>
        </div>
      ) : null}

      <aside className={isNavCollapsed ? 'left-nav collapsed' : 'left-nav'}>
        <button
          className="nav-edge-toggle"
          type="button"
          onClick={() => setIsNavCollapsed((current) => !current)}
          title={isNavCollapsed ? 'Abrir menú' : 'Cerrar menú'}
          aria-label={isNavCollapsed ? 'Abrir menú' : 'Cerrar menú'}
        >
          <ChevronIcon collapsed={isNavCollapsed} />
        </button>

        <div className={isNavCollapsed ? 'brand-block rail-brand' : 'brand-block row-between'}>
          <Link to="/app/messages" className={isNavCollapsed ? 'brand-link compact' : 'brand-link'}>
            <img src="/conectate-logo.svg" alt="Conectate" className="brand-logo" />
            {!isNavCollapsed ? (
              <span className="brand-copy">
                <strong>Conectate</strong>
                <small>Web</small>
              </span>
            ) : null}
          </Link>
        </div>
        <nav>
          {primaryNavItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => (isActive ? 'nav-item active' : 'nav-item')}
              title={isNavCollapsed ? item.label : undefined}
            >
              <span className="nav-item-inner">
                <span className="nav-item-label">
                  <span className="nav-icon">
                    <ShellIcon name={item.icon} />
                  </span>
                  {!isNavCollapsed ? <span>{item.label}</span> : null}
                </span>
                {navBadgeByPath[item.to] > 0
                  ? isNavCollapsed
                    ? <span className="nav-dot" />
                    : <span className="nav-counter">{navBadgeByPath[item.to] > 99 ? '99+' : navBadgeByPath[item.to]}</span>
                  : null}
              </span>
            </NavLink>
          ))}
        </nav>
        <div className="left-nav-footer">
          <button
            className="rail-logout-btn"
            type="button"
            onClick={() => void signOut()}
            title="Cerrar sesión"
          >
            <span className="nav-icon">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="M17 16l4-4-4-4M21 12H9M13 16a9 9 0 110-8"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  fill="none"
                />
              </svg>
            </span>
            {!isNavCollapsed ? <span>Cerrar sesión</span> : null}
          </button>
        </div>
      </aside>

      <section className="main-pane">
        {!isOnline ? (
          <div className="offline-banner offline">
            Sin conexión. Se guardarán acciones y se sincronizarán al reconectar.
          </div>
        ) : !isFallbackSession && supabaseRestriction.active ? (
          <div className="offline-banner restricted">
            Supabase está restringido por cuota/servicio. La web sigue abierta, pero Realtime y storage se pausan hasta resolverlo.
          </div>
        ) : pendingDeferredMutations > 0 ? (
          <div className="offline-banner syncing">
            Sincronizando {pendingDeferredMutations} acciones pendientes...
          </div>
        ) : null}

        {requiresMigrationPasswordChange ? (
          <section className="migration-password-notice" aria-label="Actualización de contraseña requerida">
            <div className="migration-password-copy">
              <strong>Actualiza tu contraseña por migración</strong>
              <p>
                Las cuentas creadas antes del 25/05/2026 deben cambiar su contraseña una sola vez para
                usar el servidor local de forma segura.
              </p>
              {migrationPasswordError ? <span className="form-error">{migrationPasswordError}</span> : null}
            </div>
            {migrationPasswordOpen ? (
              <form
                className="migration-password-form"
                onSubmit={(event) => {
                  event.preventDefault()
                  void submitMigrationPasswordChange()
                }}
              >
                <input
                  className="text-input"
                  type="password"
                  autoComplete="current-password"
                  placeholder="Contraseña actual"
                  value={migrationCurrentPassword}
                  onChange={(event) => setMigrationCurrentPassword(event.target.value)}
                />
                <input
                  className="text-input"
                  type="password"
                  autoComplete="new-password"
                  placeholder="Nueva contraseña"
                  value={migrationNewPassword}
                  onChange={(event) => setMigrationNewPassword(event.target.value)}
                />
                <input
                  className="text-input"
                  type="password"
                  autoComplete="new-password"
                  placeholder="Confirmar contraseña"
                  value={migrationConfirmPassword}
                  onChange={(event) => setMigrationConfirmPassword(event.target.value)}
                />
                <div className="migration-password-actions">
                  <button className="primary-btn" type="submit" disabled={savingMigrationPassword}>
                    {savingMigrationPassword ? 'Actualizando...' : 'Guardar'}
                  </button>
                  <button
                    className="ghost-btn"
                    type="button"
                    onClick={() => {
                      setMigrationPasswordOpen(false)
                      setMigrationPasswordError(null)
                    }}
                  >
                    Luego
                  </button>
                </div>
              </form>
            ) : (
              <button className="primary-btn" type="button" onClick={() => setMigrationPasswordOpen(true)}>
                Cambiar ahora
              </button>
            )}
          </section>
        ) : migrationPasswordInfo ? (
          <div className="offline-banner syncing">{migrationPasswordInfo}</div>
        ) : null}

        {!isMessagesRoute ? (
          <header className="shell-topbar">
            <div className="shell-topbar-copy">
              {shellMeta.kicker ? <span className="shell-kicker">{shellMeta.kicker}</span> : null}
              <div className="shell-heading-row">
                <div>
                  <h1>{shellMeta.title}</h1>
                  {shellMeta.subtitle ? <p>{shellMeta.subtitle}</p> : null}
                </div>
                <div className="shell-toolbar">
                  {isSocialRoute ? (
                    <>
                      <Link className="shell-tool-link" to="/app/challenges/ranking" title="Ranking" aria-label="Ranking">
                        <Trophy aria-hidden="true" />
                      </Link>
                      <Link className="shell-tool-link" to="/app/friend-requests" title="Solicitudes de amistad" aria-label="Solicitudes de amistad">
                        <Users aria-hidden="true" />
                      </Link>
                      <Link className="shell-tool-link" to="/app/notifications" title="Notificaciones" aria-label="Notificaciones">
                        <Bell aria-hidden="true" />
                        {notificationUnreadCount > 0 ? <span className="shell-inline-counter">{notificationUnreadCount > 99 ? '99+' : notificationUnreadCount}</span> : null}
                      </Link>
                      <Link className="shell-tool-link" to="/app/social?tab=posts&compose=1" title="Nueva publicación" aria-label="Nueva publicación">
                        <Plus aria-hidden="true" />
                      </Link>
                    </>
                  ) : isProfileRoute ? (
                    <>
                      <Link className="shell-tool-link" to="/app/notifications" title="Notificaciones" aria-label="Notificaciones"><Bell aria-hidden="true" /></Link>
                      <Link className="shell-tool-link" to="/app/follows" title="Amigos" aria-label="Amigos"><Users aria-hidden="true" /></Link>
                      <Link className="shell-tool-link" to="/app/settings" title="Ajustes" aria-label="Ajustes"><Settings aria-hidden="true" /></Link>
                    </>
                  ) : null}
                </div>
              </div>
            </div>
            {/* Status strip removed — no existe en la APK móvil */}
          </header>
        ) : null}

        <main className="content-pane">
          <div className={contentShellClassName}>
            <Outlet />
          </div>
        </main>

        <nav className="mobile-nav">
          {mobileNavItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => (isActive ? 'mobile-item active' : 'mobile-item')}
            >
              <span className="mobile-item-inner">
                <span className="mobile-nav-icon">
                  <ShellIcon name={item.icon} />
                </span>
                <span className="mobile-item-label">{item.label}</span>
                {navBadgeByPath[item.to] > 0 ? (
                  <span className="mobile-counter">{navBadgeByPath[item.to] > 99 ? '99+' : navBadgeByPath[item.to]}</span>
                ) : null}
              </span>
            </NavLink>
          ))}
        </nav>
      </section>
    </div>
  )
}
