import { useLegacyCallBroadcast } from '../features/calls/useLegacyCallBroadcast'
import { VoiceRoomHost } from '../features/voice/VoiceRoomHost'
import { LiveSessionHost } from '../features/live/LiveSessionHost'
import { AttachmentJobsStatus } from '../components/AttachmentJobsStatus'
import { MessageReactionNotice } from '../components/MessageReactionNotice'
import { dedupeInboxConversations } from '../features/messages/conversationInboxSupport'
import { voiceBackPath } from '../core/voiceDirectory'
import { sectionMenu } from '../core/sectionMenu'
import { StreamingImage } from '../components/StreamingMedia'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ArrowLeft,
  BarChart3,
  Bell,
  Eye,
  Gamepad2,
  Globe,
  LogOut,
  MessageSquareText,
  MoreVertical,
  Plus,
  Search,
  Settings,
  Users,
  Video,
  VideoIcon,
  Mic,
} from 'lucide-react'
import { SafeAvatar } from '../components/SafeAvatar'
import { NOVVIN_TABS, primarySection, openProfileVisits } from '../ui/novvin/navigation'
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
import {applyInboxUnreadSnapshot,applyRemoteConversationRead,inboxActivityEvent,inboxMessageTarget,invalidateInboxPreview,notifyInboxMessage,useInboxUnreadCounts} from '../core/inboxActivity'
import { textOf, type GenericRow } from '../core/utils'
import { useAuth } from '../core/auth/AuthProvider'
import { changeFallbackPassword, ensureFallbackAccessToken } from '../core/auth/fallbackAuth'
import { CallControlClient, type CallLeaveAction } from '../features/calls/callControl'
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
  | 'games'

const primaryNavItems = NOVVIN_TABS
const secondaryNavItems = [
  { to: '/app/assistant', label: 'Asistente de Novvin', icon: 'messages' as NavIconName },
  { to: '/app/search', label: 'Buscar', icon: 'search' as NavIconName },
  {
    to: '/app/notifications',
    label: 'Notificaciones',
    icon: 'notifications' as NavIconName,
  },
  { to: '/app/calls', label: 'Llamadas', icon: 'calls' as NavIconName },
  { to: '/app/voice', label: 'Salas de voz', icon: 'voice' as NavIconName },
  {
    to: '/app/create-space',
    label: 'Crear espacio',
    icon: 'voice' as NavIconName,
  },
  { to: '/app/live', label: 'En vivo', icon: 'videos' as NavIconName },
  {
    to: '/app/proximity',
    label: 'Personas cerca',
    icon: 'social' as NavIconName,
  },
  { to: '/app/settings', label: 'Ajustes', icon: 'settings' as NavIconName },
  {
    to: '/app/reports',
    label: 'Mis reportes',
    icon: 'notifications' as NavIconName,
  },
  { to: '/app/help', label: 'Ayuda', icon: 'settings' as NavIconName },
]

const localReadStorageKey = 'conectate-web-chat-last-read'
const mobileNavItems = primaryNavItems

type IncomingCallInvite = CallInvitePayload & {
  receivedAt: number
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

function screenTitle(path: string) {
  if (path === '/app/profile') return 'Perfil'
  if (path.startsWith('/app/profile/')) return 'Perfil'
  const titles: Record<string, string> = {
    social: 'Novvin',
    calls: 'Llamadas',
    voice: 'Salas de voz',
    live: 'En vivo',
    proximity: 'Personas cerca',
    games: 'Juegos',
    search: 'Buscar',
    settings: 'Ajustes',
    assistant: 'Asistente de Novvin',
    notifications: 'Notificaciones',
    follows: 'Amigos',
    'friend-requests': 'Solicitudes',
    'create-space': 'Crear',
    reports: 'Mis reportes',
    help: 'Ayuda',
    admin: 'Administración',
    moments: 'Momentos',
    ranking: 'Ranking',
    challenges: 'Rondas',
  }
  return titles[path.split('/')[2]] || 'Novvin'
}

function metadataBoolean(value: unknown): boolean {
  return value === true || String(value ?? '').toLowerCase() === 'true'
}

function passwordChangeErrorMessage(error: unknown): string {
  const code = error instanceof Error ? error.message : String(error ?? '')
  if (code === 'INVALID_CREDENTIALS') return 'La contraseña actual no coincide.'
  if (code === 'CURRENT_AND_NEW_PASSWORD_REQUIRED')
    return 'Escribe tu contraseña actual y una nueva contraseña.'
  if (code === 'NEW_PASSWORD_MUST_BE_DIFFERENT')
    return 'La contraseña nueva debe ser diferente a la actual.'
  if (code === 'AUTH_REQUIRED' || code === 'INVALID_TOKEN')
    return 'Tu sesión expiró. Vuelve a iniciar sesión.'
  return code || 'No se pudo actualizar la contraseña.'
}

function ShellIcon({ name }: { name: NavIconName }) {
  const icons = {
    social: Globe,
    videos: Video,
    messages: MessageSquareText,
    calls: VideoIcon,
    profile: Users,
    voice: Mic,
    search: Search,
    notifications: Bell,
    monetization: BarChart3,
    settings: Settings,
    games: Gamepad2,
  }
  const Icon = icons[name]
  return <Icon aria-hidden="true" />
}

export function AppLayout() {
  const { user, session, signOut, isFallbackSession } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [webPreferences] = useWebPreferences()
  const inboxCounts=useInboxUnreadCounts(user?.id||'')
  const messageUnreadCount=inboxCounts.chats+inboxCounts.groups+inboxCounts.communities
  const [notificationUnreadCount, setNotificationUnreadCount] = useState(0)
  const [isOnline, setIsOnline] = useState(isBrowserOnline())
  const [pendingDeferredMutations, setPendingDeferredMutations] = useState(
    getDeferredMutationCount(user?.id ?? 'signed-out'),
  )
  const [isMobileExploreOpen, setIsMobileExploreOpen] = useState(false)
  const [menuMode, setMenuMode] = useState<'navigation' | 'section'>('navigation')
  const mobileExploreButtonRef = useRef<HTMLButtonElement | null>(null)
  const mobileExploreCloseRef = useRef<HTMLButtonElement | null>(null)
  const [supabaseRestriction, setSupabaseRestriction] = useState(() =>
    getSupabaseRestrictionSnapshot(),
  )
  const [incomingCall, setIncomingCall] = useState<IncomingCallInvite | null>(null)
  const [callActionBusy, setCallActionBusy] = useState(false)
  const [callActionError, setCallActionError] = useState('')
  const callActionBusyRef = useRef(false)
  const dismissedCallIdsRef = useRef(new Set<string>())
  const incomingCallRef = useRef<IncomingCallInvite | null>(null)
  incomingCallRef.current = incomingCall
  const callAccessToken = useCallback(
    async () => (isFallbackSession ? ensureFallbackAccessToken() : (session?.access_token ?? '')),
    [isFallbackSession, session?.access_token],
  )
  const dismissIncomingCall = useCallback((callId: string) => {
    dismissedCallIdsRef.current.add(callId)
    if (dismissedCallIdsRef.current.size > 64) {
      const oldest = dismissedCallIdsRef.current.values().next().value
      if (oldest) dismissedCallIdsRef.current.delete(oldest)
    }
    if (incomingCallRef.current?.callId === callId) incomingCallRef.current = null
    setIncomingCall((current) => (current?.callId === callId ? null : current))
  }, [])
  const presentIncomingCall = useCallback(
    (invite: IncomingCallInvite) => {
      if (dismissedCallIdsRef.current.has(invite.callId)) return
      const current = incomingCallRef.current
      if (current?.callId === invite.callId) return
      if (current) {
        dismissedCallIdsRef.current.add(invite.callId)
        void callAccessToken()
          .then(async (token) => {
            await new CallControlClient(token).leave(invite.callId, 'busy')
            if (user)
              await sendCallResponse(
                supabase,
                invite.callerUserId,
                { callId: invite.callId, action: 'busy', fromUserId: user.id },
                token,
              )
          })
          .catch(() => undefined)
        return
      }
      incomingCallRef.current = invite
      setCallActionError('')
      setIncomingCall(invite)
    },
    [callAccessToken, user],
  )
  const [migrationPasswordOpen, setMigrationPasswordOpen] = useState(false)
  const [migrationCurrentPassword, setMigrationCurrentPassword] = useState('')
  const [migrationNewPassword, setMigrationNewPassword] = useState('')
  const [migrationConfirmPassword, setMigrationConfirmPassword] = useState('')
  const [migrationPasswordError, setMigrationPasswordError] = useState<string | null>(null)
  const [migrationPasswordInfo, setMigrationPasswordInfo] = useState<string | null>(null)
  const [savingMigrationPassword, setSavingMigrationPassword] = useState(false)
  const counterLoadSequence=useRef(0)

  const loadShellCounters = useCallback(async () => {
    const sequence=++counterLoadSequence.current,requestedAt=Date.now()
    if (isFallbackSession && user) {
      try {
        const [notifications, ...inboxes] = await Promise.all([
          loadFallbackNotifications(500),
          loadFallbackMessagesInbox('chats'),
          loadFallbackMessagesInbox('groups'),
          loadFallbackMessagesInbox('communities'),
        ])
        if(sequence!==counterLoadSequence.current)return
        setNotificationUnreadCount(
          Number.isFinite(Number(notifications.unreadCount))
            ? Number(notifications.unreadCount)
            : (notifications.items ?? []).filter((row) => !(row.is_read ?? row.isRead)).length,
        )
        ;(['chat','group','community'] as const).forEach((scope,index)=>applyInboxUnreadSnapshot(user.id,scope,dedupeInboxConversations(inboxes[index].items||[]),requestedAt))
      } catch (caught) {
        console.warn('loadShellCounters fallback failed', caught)
      }
      return
    }

    const client = supabase
    if (!client || !user) {
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

      if(sequence!==counterLoadSequence.current)return
      setNotificationUnreadCount((notificationRows ?? []).length)
      if (chatError) throw chatError

      const chats = (chatRows ?? []) as GenericRow[]
      const chatIds = chats.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      if (chatIds.length === 0) {
        applyInboxUnreadSnapshot(user.id,'chat',[])
        return
      }

      const { data: participantRows } = await client
        .from('chat_participants')
        .select('*')
        .eq('user_id', user.id)
        .in('chat_id', chatIds)

      const participantLastReadByChat = new Map<string, string>()
      const archivedByChat = new Map<string, boolean>()
      ;((participantRows ?? []) as GenericRow[]).forEach((row) => {
        const chatId = textOf(row, ['chat_id'], '')
        if (!chatId) return
        participantLastReadByChat.set(chatId, textOf(row, ['last_read_at'], ''))
        archivedByChat.set(chatId, metadataBoolean(row.is_archived))
      })

      const localReadByChat = readLocalReadByChat()

      const { data: messageRows, error: messageError } = await client
        .from('messages')
        .select('chat_id,sender_id,created_at,read_at,status')
        .in('chat_id', chatIds)
        .neq('sender_id', user.id)
      if (messageError) throw messageError

      const unreadIds=new Set<string>()
      const unreadDates=new Map<string,string>()
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
        unreadIds.add(chatId)
        const at=textOf(row,['created_at'],'');if(toMillis(at)>toMillis(unreadDates.get(chatId)||''))unreadDates.set(chatId,at)
      })

      if(sequence!==counterLoadSequence.current)return
      const inboxRows=chats.map(row=>{
        const id=textOf(row,['id'],'')
        const participants=Array.isArray(row.participants)?row.participants.map(String):[]
        return {id,kind:'chat',profileId:participants.find(participant=>participant.trim().toLowerCase()!==user.id.trim().toLowerCase())||null,unreadCount:unreadIds.has(id)?1:0,updatedAt:unreadDates.get(id)||'',isArchived:archivedByChat.get(id)||false,isPinned:false,isMuted:false,isFavorite:false,folders:[]}
      })
      applyInboxUnreadSnapshot(user.id,'chat',dedupeInboxConversations(inboxRows),requestedAt)
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('shell-counters', caught)
      console.warn('loadShellCounters failed', caught)
    }
  }, [isFallbackSession, user])

  useEffect(() => {
    void loadShellCounters()
  }, [loadShellCounters])

  useEffect(() => {
    let timer:ReturnType<typeof setTimeout>|undefined
    const reload = () => {clearTimeout(timer);timer=setTimeout(()=>void loadShellCounters(),120)}
    window.addEventListener('novvin:notifications-updated', reload)
    window.addEventListener(inboxActivityEvent,reload)
    window.addEventListener('novvin:realtime-ready',reload)
    return () => {clearTimeout(timer);window.removeEventListener('novvin:notifications-updated',reload);window.removeEventListener(inboxActivityEvent,reload);window.removeEventListener('novvin:realtime-ready',reload)}
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
      const unsubscribe=subscribeUbuntuRealtime({
        channel: `user:${user.id}`,
        onEvent: (event) => {
          if(event.payload?.event==='conversation_read' && event.payload.accountId===user.id){
            const scope=String(event.payload.kind||''),id=String(event.payload.conversationId||'')
            if(['chat','group','community'].includes(scope)&&id)applyRemoteConversationRead(user.id,scope as 'chat'|'group'|'community',id,Array.isArray(event.payload.messageIds)?event.payload.messageIds.filter((v):v is string=>typeof v==='string'):[],String(event.payload.through||''))
          }
          if(event.payload?.event==='message'){
            const target=inboxMessageTarget(event.payload.chatId)
            if(target){if(event.payload.action==='deleted')invalidateInboxPreview(user.id,target.scope,target.id);else notifyInboxMessage(user.id,target.scope,target.id,(event.payload.message||{}) as GenericRow,event.payload.action==='created')}
          }
          if (event.payload?.event === 'call_response' || event.payload?.event === 'call_cancel') {
            const callId = String(event.payload.callId ?? '').trim()
            dismissIncomingCall(callId)
          }
          if (event.payload?.event === 'call_invite') {
            const callId = String(event.payload.callId ?? '').trim()
            const callerUserId = String(event.payload.callerUserId ?? '').trim()
            if (!callId || !callerUserId || callerUserId === user.id) return
            presentIncomingCall({
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
          if(event.payload?.event!=='message')scheduleRefresh()
        },
      })
      return()=>{if(refreshTimer)clearTimeout(refreshTimer);unsubscribe()}
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
        {
          event: '*',
          schema: 'public',
          table: 'chat_participants',
          filter: `user_id=eq.${user.id}`,
        },
        scheduleRefresh,
      )
      .on(
        'postgres_changes',
        {
          event: '*',
          schema: 'public',
          table: 'notifications',
          filter: `recipient_id=eq.${user.id}`,
        },
        scheduleRefresh,
      )
    const subscribedChannel = subscribeRealtimeChannel(channel, {
      reason: 'shell-counters',
    })

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [dismissIncomingCall, isFallbackSession, loadShellCounters, presentIncomingCall, user])

  useEffect(() => {
    return onSupabaseRestrictionChanged(setSupabaseRestriction)
  }, [])

  const clearIncomingCallsOnSignOut = useCallback(() => setIncomingCall(null), [])
  useLegacyCallBroadcast({ client: supabase, userId: user?.id || '', isFallbackSession, incomingCallId: incomingCall?.callId, presentInvite: presentIncomingCall, dismissInvite: dismissIncomingCall, clearOnSignOut: clearIncomingCallsOnSignOut })

  const acceptIncomingCall = useCallback(async () => {
    const invite = incomingCall
    if (!invite || !user || callActionBusyRef.current) return
    callActionBusyRef.current = true
    setCallActionBusy(true)
    setCallActionError('')
    try {
      const token = await callAccessToken()
      await new CallControlClient(token).accept(invite.callId)
      if (incomingCallRef.current?.callId !== invite.callId) {
        await new CallControlClient(token).leave(invite.callId, 'end')
        return
      }
      dismissIncomingCall(invite.callId)
      const params = new URLSearchParams({
        callId: invite.callId,
        join: '1',
        target: invite.callerUserId,
        name: invite.callerName,
        contextId: invite.contextId ?? '',
        kind: invite.isVideo ? 'video' : 'audio',
      })
      navigate(`/app/calls?${params.toString()}`)
    } catch (error) {
      setCallActionError(
        error instanceof Error ? error.message : 'No se pudo confirmar la llamada.',
      )
    } finally {
      callActionBusyRef.current = false
      setCallActionBusy(false)
    }
  }, [callAccessToken, dismissIncomingCall, incomingCall, navigate, user])

  const declineIncomingCall = useCallback(
    async (action: CallLeaveAction = 'decline') => {
      const invite = incomingCall
      if (!invite || !user || callActionBusyRef.current) return
      callActionBusyRef.current = true
      setCallActionBusy(true)
      setCallActionError('')
      try {
        const token = await callAccessToken()
        await new CallControlClient(token).leave(invite.callId, action)
        dismissIncomingCall(invite.callId)
        await sendCallResponse(
          supabase,
          invite.callerUserId,
          { callId: invite.callId, action, fromUserId: user.id },
          token,
        )
      } catch (error) {
        setCallActionError(error instanceof Error ? error.message : 'No se pudo cerrar la llamada.')
      } finally {
        callActionBusyRef.current = false
        setCallActionBusy(false)
      }
    },
    [callAccessToken, dismissIncomingCall, incomingCall, user],
  )

  useEffect(() => {
    if (!incomingCall) return
    const invite = incomingCall
    const timer = setTimeout(
      () => {
        if (incomingCallRef.current?.callId !== invite.callId) return
        dismissIncomingCall(invite.callId)
        void callAccessToken()
          .then(async (token) => {
            await new CallControlClient(token).leave(invite.callId, 'missed')
            if (user)
              await sendCallResponse(
                supabase,
                invite.callerUserId,
                {
                  callId: invite.callId,
                  action: 'missed',
                  fromUserId: user.id,
                },
                token,
              )
          })
          .catch(() => undefined)
      },
      Math.max(0, 45000 - (Date.now() - invite.receivedAt)),
    )
    return () => clearTimeout(timer)
  }, [callAccessToken, dismissIncomingCall, incomingCall, user])

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
          const notificationId = String(
            (mutation.payload as { notificationId?: string }).notificationId ?? '',
          )
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
        const notificationId = String(
          (mutation.payload as { notificationId?: string }).notificationId ?? '',
        )
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
        const chatId = String((mutation.payload as { chatId?: string }).chatId ?? '')
          .trim()
          .toLowerCase()
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
      setPendingDeferredMutations(getDeferredMutationCount(user?.id ?? 'signed-out'))
    }
    syncPendingCounter()
    window.addEventListener('conectate-web-deferred-queue-updated', syncPendingCounter)
    return () => {
      window.removeEventListener('conectate-web-deferred-queue-updated', syncPendingCounter)
    }
  }, [user?.id])

  useEffect(() => {
    if (!isOnline || !user || (!isFallbackSession && !supabase)) return
    let isCancelled = false

    const flush = async () => {
      const result = await flushDeferredMutations(executeDeferredMutation, user.id)
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

  const isMessagesRoute = location.pathname.startsWith('/app/messages')
  const isSocialRoute = location.pathname.startsWith('/app/social')
  const isProfileRoute = location.pathname === '/app/profile'
  const activeSection = primarySection(location.pathname)
  const isVideosRoute = activeSection === '/app/videos'
  const isConversationRoute = /^\/app\/messages\/.+/.test(location.pathname)
  const contentShellClassName = isMessagesRoute
    ? 'content-shell full'
    : isVideosRoute
      ? 'content-shell video-content'
      : location.pathname.startsWith('/app/voice') ? 'content-shell voice-content' : 'content-shell wide'
  const requiresMigrationPasswordChange = Boolean(
    isFallbackSession && metadataBoolean(user?.user_metadata?.password_change_required),
  )

  const submitMigrationPasswordChange = useCallback(async () => {
    if (savingMigrationPassword) return
    const currentPassword = migrationCurrentPassword.trim()
    const nextPassword = migrationNewPassword.trim()
    const confirmation = migrationConfirmPassword.trim()

    if (!currentPassword || nextPassword.length < 8) {
      setMigrationPasswordError(
        'Escribe tu contraseña actual y una nueva de al menos 8 caracteres.',
      )
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
      setMigrationPasswordInfo(
        'Contraseña actualizada. Este cambio queda guardado para el servidor local.',
      )
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

  useEffect(() => {
    setIsMobileExploreOpen(false)
  }, [location.pathname])
  useEffect(() => {
    if (!isMobileExploreOpen) return
    mobileExploreCloseRef.current?.focus()
    const onEscape = (event: KeyboardEvent) => {
      if (event.key === 'Tab') {
        const elements = Array.from(
          document.querySelectorAll<HTMLElement>(
            '#mobile-explore-menu a, #mobile-explore-menu button',
          ),
        ).filter((node) => !node.hasAttribute('disabled'))
        const first = elements[0],
          last = elements[elements.length - 1]
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault()
          last?.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first?.focus()
        }
      }
      if (event.key === 'Escape') {
        setIsMobileExploreOpen(false)
        mobileExploreButtonRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onEscape)
    return () => window.removeEventListener('keydown', onEscape)
  }, [isMobileExploreOpen])

  useEffect(() => {
    const open = () => {
      if (document.activeElement instanceof HTMLButtonElement)
        mobileExploreButtonRef.current = document.activeElement
      setMenuMode('navigation')
      setIsMobileExploreOpen(true)
    }
    window.addEventListener('novvin:open-menu', open)
    return () => window.removeEventListener('novvin:open-menu', open)
  }, [])

  return (
    <div
      className={[
        'app-shell',
        'novvin-app',
        isVideosRoute ? 'route-videos' : '',
        isConversationRoute ? 'route-conversation' : '',
        isSocialRoute ? 'route-social' : '',
        location.pathname.startsWith('/app/profile') ? 'route-profile' : '',
        isMessagesRoute ? 'route-messages' : '',
        `theme-${webPreferences.themeMode}`,
        `accent-${webPreferences.accentTheme}`,
        `text-scale-${webPreferences.textScale}`,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {incomingCall ? (
        <div
          className="incoming-call-overlay"
          role="dialog"
          aria-modal="true"
          aria-label="Llamada entrante"
        >
          <div className="incoming-call-panel">
            <div className="incoming-call-avatar">
              {incomingCall.callerAvatarUrl ? (
                <StreamingImage src={incomingCall.callerAvatarUrl} alt="" />
              ) : (
                <span>{incomingCall.callerName.trim().charAt(0).toUpperCase() || 'U'}</span>
              )}
            </div>
            <div className="incoming-call-copy">
              <span>{incomingCall.isVideo ? 'Videollamada entrante' : 'Llamada entrante'}</span>
              <strong>{incomingCall.callerName}</strong>
              <p>{incomingCall.title || 'Novvin'}</p>
              {callActionError ? <p role="alert">{callActionError}</p> : null}
            </div>
            <div className="incoming-call-actions">
              <button
                className="incoming-call-btn decline"
                type="button"
                disabled={callActionBusy}
                onClick={() => void declineIncomingCall()}
              >
                Rechazar
              </button>
              <button
                className="incoming-call-btn accept"
                type="button"
                disabled={callActionBusy}
                onClick={() => void acceptIncomingCall()}
              >
                Aceptar
              </button>
            </div>
          </div>
        </div>
      ) : null}

      <section className="main-pane">
        {!isOnline ? (
          <div className="offline-banner offline">
            Sin conexión. Se guardarán acciones y se sincronizarán al reconectar.
          </div>
        ) : !isFallbackSession && supabaseRestriction.active ? (
          <div className="offline-banner restricted">
            No se pudo actualizar el contenido. Vuelve a intentarlo en unos minutos.
          </div>
        ) : pendingDeferredMutations > 0 ? (
          <div className="offline-banner syncing">
            Sincronizando {pendingDeferredMutations} acciones pendientes...
          </div>
        ) : null}

        {requiresMigrationPasswordChange ? (
          <section
            className="migration-password-notice"
            aria-label="Actualización de contraseña requerida"
          >
            <div className="migration-password-copy">
              <strong>Actualiza tu contraseña por migración</strong>
              <p>
                Las cuentas creadas antes del 25/05/2026 deben cambiar su contraseña una sola vez
                para usar el servidor local de forma segura.
              </p>
              {migrationPasswordError ? (
                <span className="form-error">{migrationPasswordError}</span>
              ) : null}
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
              <button
                className="primary-btn"
                type="button"
                onClick={() => setMigrationPasswordOpen(true)}
              >
                Cambiar ahora
              </button>
            )}
          </section>
        ) : migrationPasswordInfo ? (
          <div className="offline-banner syncing">{migrationPasswordInfo}</div>
        ) : null}

        {!isMessagesRoute && !isVideosRoute ? (
          <header className="novvin-topbar">
            <div className="novvin-topbar-title">
              {!isSocialRoute && !isProfileRoute && !location.pathname.startsWith('/app/games') ? (
                <button
                  className="icon-btn" type="button"
                  onClick={() => { const voiceTarget=voiceBackPath(location.pathname); if(voiceTarget)navigate(voiceTarget,{replace:true});else if (Number(window.history.state?.idx) > 0) navigate(-1); else navigate(location.pathname.startsWith('/app/settings') ? '/app/profile' : activeSection || '/app/messages') }}
                  aria-label="Volver"
                >
                  <ArrowLeft />
                </button>
              ) : null}
              <h1>{screenTitle(location.pathname)}</h1>
            </div>
            <div className="novvin-topbar-actions">
              {isSocialRoute ? (
                <>
                  <Link className="icon-btn" to="/app/ranking" aria-label="Ranking">
                    <BarChart3 />
                  </Link>
                  <Link
                    className="icon-btn"
                    to="/app/friend-requests"
                    aria-label="Solicitudes de amistad"
                  >
                    <Users />
                  </Link>
                  <Link className="icon-btn" to="/app/notifications" aria-label="Notificaciones">
                    <Bell />
                    {notificationUnreadCount > 0 ? <span className="nav-dot" /> : null}
                  </Link>
                  <Link
                    className="icon-btn"
                    to="/app/social?tab=posts&compose=1"
                    aria-label="Nueva publicación"
                  >
                    <Plus />
                  </Link>
                </>
              ) : null}
              {isProfileRoute ? (
                <>
                  <button
                    className="icon-btn"
                    type="button"
                    onClick={openProfileVisits}
                    aria-label="Visitas recientes"
                  >
                    <Eye />
                  </button>
                  <Link className="icon-btn" to="/app/notifications" aria-label="Notificaciones">
                    <Bell />
                  </Link>
                  <Link className="icon-btn" to="/app/follows" aria-label="Amigos">
                    <Users />
                  </Link>
                  <Link className="icon-btn" to="/app/settings" aria-label="Ajustes">
                    <Settings />
                  </Link>
                </>
              ) : null}
              <button
                ref={mobileExploreButtonRef}
                className="icon-btn"
                type="button"
                aria-label={`Opciones de ${screenTitle(location.pathname)}`}
                aria-expanded={isMobileExploreOpen}
                aria-controls="mobile-explore-menu"
                onClick={() => {setMenuMode('section');setIsMobileExploreOpen(true)}}
              >
                <MoreVertical />
              </button>
            </div>
          </header>
        ) : null}

        <main className="content-pane">
          <div className={contentShellClassName}>
            <Outlet />
            <VoiceRoomHost />
            <MessageReactionNotice />
            <LiveSessionHost />
            <AttachmentJobsStatus />
          </div>
        </main>

        <nav className="novvin-bottom-nav" aria-label="Navegación principal">
          {mobileNavItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={activeSection === item.to ? 'novvin-nav-item active' : 'novvin-nav-item'}
              aria-current={activeSection === item.to ? 'page' : undefined}
            >
              <span className="novvin-nav-pill">
                {item.icon === 'profile' ? (
                  <SafeAvatar
                    url={String(user?.user_metadata?.avatar_url || '')}
                    name={String(user?.user_metadata?.display_name || 'Perfil')}
                    size="sm"
                    className="novvin-nav-avatar"
                  />
                ) : (
                  <ShellIcon name={item.icon} />
                )}
                {(navBadgeByPath[item.to] || 0) > 0 ? (
                  item.icon === 'messages' ? (
                    <span className="nav-badge">
                      {navBadgeByPath[item.to] > 9 ? '9+' : navBadgeByPath[item.to]}
                    </span>
                  ) : (
                    <span className="nav-dot" />
                  )
                ) : null}
              </span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>
        {isMobileExploreOpen && (
          <div
            className="mobile-explore-backdrop"
            onClick={() => {
              setIsMobileExploreOpen(false)
              mobileExploreButtonRef.current?.focus()
            }}
          >
            <nav
              id="mobile-explore-menu"
              className="mobile-explore-panel"
              role="dialog"
              aria-modal="true"
              aria-label="Más funciones"
              onClick={(event) => event.stopPropagation()}
            >
              <div className="mobile-explore-head">
                <strong>{menuMode === 'section' ? screenTitle(location.pathname) : 'Explorar Novvin'}</strong>
                <button
                  ref={mobileExploreCloseRef}
                  type="button"
                  aria-label="Cerrar menú"
                  onClick={() => {
                    setIsMobileExploreOpen(false)
                    mobileExploreButtonRef.current?.focus()
                  }}
                >
                  Cerrar
                </button>
              </div>
              {menuMode === 'section' ? sectionMenu(location.pathname).map(item => item.to ? <Link key={item.label} to={item.to} className="mobile-explore-link" onClick={()=>setIsMobileExploreOpen(false)}>{item.label}</Link> : <button key={item.label} type="button" className="mobile-explore-link" onClick={()=>{setIsMobileExploreOpen(false);window.dispatchEvent(new CustomEvent('novvin:voice-action',{detail:item.voiceAction}))}}>{item.label}</button>) : secondaryNavItems.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className="mobile-explore-link"
                  onClick={() => setIsMobileExploreOpen(false)}
                >
                  <span className="nav-icon">
                    <ShellIcon name={item.icon} />
                  </span>
                  {item.label}
                </NavLink>
              ))}
              {menuMode === 'navigation' ? <button className="mobile-explore-link" type="button" onClick={() => void signOut()}>
                <LogOut aria-hidden="true" />
                Cerrar sesión
              </button> : null}
            </nav>
          </div>
        )}
      </section>
    </div>
  )
}
