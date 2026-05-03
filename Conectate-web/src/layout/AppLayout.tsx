import { useCallback, useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useNavigate, useLocation } from 'react-router-dom'
import {
  type DeferredMutation,
  flushDeferredMutations,
  getDeferredMutationCount,
  isBrowserOnline,
} from '../core/reliability'
import { supabase } from '../core/supabase'
import { textOf, type GenericRow } from '../core/utils'
import { useAuth } from '../core/auth/AuthProvider'
import { ModernSidebar } from '../components/modern/Layout/ModernSidebar'
import { ModernHeader } from '../components/modern/Layout/ModernHeader'
import { ModernNavigation } from '../components/modern/Layout/ModernNavigation'
import { cn } from '../core/utils/cn'

type NavIconName =
  | 'social'
  | 'videos'
  | 'messages'
  | 'profile'
  | 'voice'
  | 'search'
  | 'notifications'
  | 'settings'

const primaryNavItems = [
  { to: '/app/messages', label: 'Mensajes', icon: 'messages' as NavIconName },
  { to: '/app/videos', label: 'Videos', icon: 'videos' as NavIconName },
  { to: '/app/social', label: 'Social', icon: 'social' as NavIconName },
  { to: '/app/profile', label: 'Perfil', icon: 'profile' as NavIconName },
]

const secondaryNavItems = [
  { to: '/app/voice', label: 'Salas de voz', icon: 'voice' as NavIconName },
  { to: '/app/search', label: 'Buscar', icon: 'search' as NavIconName },
  { to: '/app/notifications', label: 'Notificaciones', icon: 'notifications' as NavIconName },
  { to: '/app/settings', label: 'Ajustes', icon: 'settings' as NavIconName },
]

const localReadStorageKey = 'conectate-web-chat-last-read'
const navCollapsedStorageKey = 'conectate-web-nav-collapsed'
const mobileNavItems = primaryNavItems

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
  return null
}

export function AppLayout() {
  const { user, signOut } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [currentView, setCurrentView] = useState<'mobile' | 'tablet' | 'desktop'>('desktop')
  const [messageUnreadCount, setMessageUnreadCount] = useState(0)
  const [notificationUnreadCount, setNotificationUnreadCount] = useState(0)
  const [isOnline, setIsOnline] = useState(isBrowserOnline())
  const [pendingDeferredMutations, setPendingDeferredMutations] = useState(getDeferredMutationCount())
  const [isNavCollapsed, setIsNavCollapsed] = useState(() => readNavCollapsedPreference())
  const [sidebarAvatarUrl, setSidebarAvatarUrl] = useState('')

  useEffect(() => {
    const handleResize = () => {
      if (window.innerWidth < 768) {
        setCurrentView('mobile')
      } else if (window.innerWidth < 1024) {
        setCurrentView('tablet')
      } else {
        setCurrentView('desktop')
      }
    }

    handleResize()
    window.addEventListener('resize', handleResize)
    return () => {
      window.removeEventListener('resize', handleResize)
    }
  }, [])

  const handleMenuClick = useCallback(() => {
    setSidebarOpen(!sidebarOpen)
  }, [sidebarOpen])

  const closeSidebar = useCallback(() => {
    setSidebarOpen(false)
  }, [])

  const isMobileView = currentView === 'mobile'
  const isTabletView = currentView === 'tablet'
  const isDesktopView = currentView === 'desktop'

  // Determine if we should show modern layout or legacy layout
  const useModernLayout = true // Force modern layout

  const loadShellCounters = useCallback(async () => {
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
      console.warn('loadShellCounters failed', caught)
    }
  }, [user])

  useEffect(() => {
    void loadShellCounters()
  }, [loadShellCounters])

  useEffect(() => {
    const client = supabase
    if (!client || !user) return
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
      .subscribe()

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void client.removeChannel(channel)
    }
  }, [loadShellCounters, user])

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
    [user],
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
    if (!isOnline || !user || !supabase) return
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
  }, [executeDeferredMutation, isOnline, loadShellCounters, user])

  useEffect(() => {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(navCollapsedStorageKey, isNavCollapsed ? '1' : '0')
    } catch {
      // Ignore local storage write failures.
    }
  }, [isNavCollapsed])

  useEffect(() => {
    const client = supabase
    if (!client || !user) {
      setSidebarAvatarUrl('')
      return
    }
    let cancelled = false
    const metadataAvatar = String(user.user_metadata?.avatar_url ?? user.user_metadata?.picture ?? '').trim()

    const loadAvatar = async () => {
      const profileResult = await client
        .from('profiles')
        .select('*')
        .eq('id', user.id)
        .limit(1)
      if (cancelled) return

      const profileRow = ((profileResult.data ?? []) as GenericRow[])[0]
      const primaryResolved = textOf(profileRow ?? {}, ['avatar_url', 'photo_url', 'profile_image', 'image'], '')
      if (primaryResolved) {
        setSidebarAvatarUrl(primaryResolved)
        return
      }

      const publicResult = await client
        .from('profiles_public')
        .select('*')
        .eq('id', user.id)
        .limit(1)
      if (cancelled) return

      const publicRow = ((publicResult.data ?? []) as GenericRow[])[0]
      const publicResolved = textOf(publicRow ?? {}, ['avatar_url', 'photo_url', 'profile_image', 'image'], '')
      setSidebarAvatarUrl(publicResolved || metadataAvatar)
    }

    void loadAvatar()
    return () => {
      cancelled = true
    }
  }, [user])

  const navBadgeByPath: Record<string, number> = {
    '/app/messages': messageUnreadCount,
    '/app/social': notificationUnreadCount,
  }

  return (
    <div className={isNavCollapsed ? 'app-shell nav-collapsed' : 'app-shell'}>
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
        <div className={isNavCollapsed ? 'nav-section-label rail-hidden' : 'nav-section-label'}>
          Explorar
        </div>
        <nav>
          {secondaryNavItems.map((item) => (
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
                {item.to === '/app/notifications' && navBadgeByPath['/app/social'] > 0
                  ? isNavCollapsed
                    ? <span className="nav-dot" />
                    : <span className="nav-counter">{navBadgeByPath['/app/social'] > 99 ? '99+' : navBadgeByPath['/app/social']}</span>
                  : null}
              </span>
            </NavLink>
          ))}
        </nav>

        <div className="left-nav-footer">
          <Link className="rail-profile-link" to="/app/profile" title="Perfil">
            {sidebarAvatarUrl ? (
              <img src={sidebarAvatarUrl} alt="Mi perfil" className="rail-profile-avatar" />
            ) : (
              <span className="rail-profile-avatar avatar-fallback">
                {(user?.email ?? 'U').trim().charAt(0).toUpperCase()}
              </span>
            )}
            {!isNavCollapsed ? <span>Mi perfil</span> : null}
          </Link>
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
        ) : pendingDeferredMutations > 0 ? (
          <div className="offline-banner syncing">
            Sincronizando {pendingDeferredMutations} acciones pendientes...
          </div>
        ) : null}

        <main className="content-pane">
          <Outlet />
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
