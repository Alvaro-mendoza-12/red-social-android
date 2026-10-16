import { readConversationDraft, useDraftRevision } from '../../core/conversationDrafts'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Search, MessageSquarePlus, MoreVertical, Phone, Plus } from 'lucide-react'
import { openNovvinMenu } from '../../ui/novvin/navigation'
import { StatusView } from '../../components/StatusView'
import { SafeAvatar } from '../../components/SafeAvatar'
import {
  enqueueDeferredMutation,
  isBrowserOnline,
  isLikelyTransientError,
  runWithRetry,
} from '../../core/reliability'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import {
  loadFallbackMessagesInbox,
  markFallbackChatRead,
  searchFallbackProfiles,
  saveFallbackChatPreferences,
} from '../../core/fallbackData'
import {
  avatarMediaTypeSource,
  conversationAvatarSource,
  profileAvatarSource,
} from '../../core/profileMediaFields'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
  resolveSupabasePublicUrl,
} from '../../core/supabase'
import { useWebPreferences } from '../../core/webPreferences'
import {
  friendlyError,
  isMissingTableError,
  stringArrayOf,
  textOf,
  type GenericRow,
} from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { fallbackApiJson, isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'
import { ChatDetailPage } from './ChatDetailPage'
import { dedupeInboxConversations } from './conversationInboxSupport'
import { getOrCreateDirectChat } from './directChat'
import { RoomChatPage } from './RoomChatPage'

type InboxTab = 'chats' | 'groups' | 'communities' | 'voice'
type ConversationKind = 'chat' | 'group' | 'community' | 'voice'

type ConversationItem = {
  id: string
  title: string
  subtitle: string
  updatedAt: string
  kind: ConversationKind
  avatarUrl: string
  avatarMediaType?: string
  profileId: string | null
  unreadCount: number
  isPinned: boolean
  isMuted: boolean
  isArchived: boolean
  isFavorite: boolean
  folders: string[]
  openPath?: string
}

type SelectedConversation = {
  id: string
  kind: ConversationKind
}

type LocalReadByChat = Record<string, string>

type DiscoverUserItem = {
  profileId: string
  title: string
  subtitle: string
  avatarUrl: string
}

const tabOptions: Array<{ key: InboxTab; label: string }> = [
  { key: 'chats', label: 'Mensajes' },
  { key: 'groups', label: 'Grupos' },
  { key: 'communities', label: 'Comunidades' },
  { key: 'voice', label: 'Voz' },
]

const localReadStorageKey = 'conectate-web-chat-last-read'

function FallbackAvatar({
  url,
  name,
  className,
  mediaType,
}: {
  url: string
  name: string
  className: string
  mediaType?: string
}) {
  return <SafeAvatar url={url} mediaType={mediaType} name={name} className={className} size="md" />
}

function inboxTime(value: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ''
  const now = new Date()
  if (date.toDateString() === now.toDateString())
    return date.toLocaleTimeString('es', {
      hour: '2-digit',
      minute: '2-digit',
    })
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  return date.toDateString() === yesterday.toDateString()
    ? 'Ayer'
    : date.toLocaleDateString('es', { day: 'numeric', month: 'numeric' })
}

function toMillis(value: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function isMissingColumnError(error: unknown): boolean {
  const message =
    error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return message.includes('column') || message.includes('42703')
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

function readLocalReadByChat(): LocalReadByChat {
  if (typeof window === 'undefined') return {}
  try {
    const raw = window.localStorage.getItem(localReadStorageKey)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return parsed as LocalReadByChat
  } catch {
    return {}
  }
}

function writeLocalReadByChat(next: LocalReadByChat) {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(localReadStorageKey, JSON.stringify(next))
    window.dispatchEvent(new Event('conectate-web-local-read-updated'))
  } catch {
    // Ignore local storage write failures.
  }
}

function normalizeId(value: string): string {
  return value.trim().toLowerCase()
}

function nameFromProfile(row: GenericRow): string {
  return textOf(row, ['display_name', 'displayName', 'full_name', 'username', 'name'], 'Usuario')
}

function avatarFromProfile(row: GenericRow): string {
  return resolveSupabasePublicUrl('avatars', profileAvatarSource(row))
}

function normalizeConversationAvatar<T extends { avatarUrl: string }>(item: T): T {
  const sourceRow = item as unknown as GenericRow
  const avatarUrl = resolveSupabasePublicUrl(
    'avatars',
    conversationAvatarSource(sourceRow, item.avatarUrl),
  )
  const currentMediaType = 'avatarMediaType' in item ? String(item.avatarMediaType ?? '') : ''
  const avatarMediaType = avatarMediaTypeSource(sourceRow, avatarUrl, currentMediaType)
  return avatarUrl === item.avatarUrl && avatarMediaType === currentMediaType
    ? item
    : { ...item, avatarUrl, avatarMediaType }
}

async function normalizeFallbackInboxItems(items: ConversationItem[]): Promise<ConversationItem[]> {
  const normalized = items.map(normalizeConversationAvatar)
  const missingIds = normalized.filter(item => !item.avatarUrl && item.profileId).map(item => item.profileId!)
  if (!missingIds.length) return normalized
  try {
    // Resolve only these identities instead of downloading whole feeds to find an avatar.
    const profiles = await fetchProfileMapByIds(missingIds)
    return normalized.map(item => {
      const profile = item.profileId ? profiles.get(normalizeId(item.profileId)) : undefined
      return item.avatarUrl || !profile?.avatarUrl ? item : { ...item, avatarUrl: profile.avatarUrl, avatarMediaType: profile.avatarMediaType }
    })
  } catch { return normalized }
}

function usernameFromProfile(row: GenericRow): string {
  return textOf(row, ['username', 'user_name', 'handle'], '')
}

function profileSearchHaystack(row: GenericRow): string {
  return [
    textOf(row, ['display_name', 'displayName', 'full_name', 'username', 'name', 'email'], ''),
    textOf(row, ['bio', 'about'], ''),
    textOf(row, ['id'], ''),
  ]
    .join(' ')
    .toLowerCase()
}

function sanitizeSearchInput(input: string): string {
  return input
    .replace(/[,%()]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

async function searchProfilesInTable(
  table: 'profiles' | 'profiles_public',
  rawQuery: string,
): Promise<GenericRow[]> {
  if (!supabase) return []
  const safeQuery = sanitizeSearchInput(rawQuery)
  if (!safeQuery) return []

  const ilikePattern = `%${safeQuery}%`
  const columns = [
    `display_name.ilike.${ilikePattern}`,
    `full_name.ilike.${ilikePattern}`,
    `username.ilike.${ilikePattern}`,
    `name.ilike.${ilikePattern}`,
    `email.ilike.${ilikePattern}`,
  ].join(',')

  const { data, error } = await supabase.from(table).select('*').or(columns).limit(40)

  if (!error) {
    return (data ?? []) as GenericRow[]
  }

  if (isMissingTableError(error)) {
    return []
  }

  const fallback = await supabase.from(table).select('*').limit(300)
  if (fallback.error) return []
  const queryLower = safeQuery.toLowerCase()
  return ((fallback.data ?? []) as GenericRow[]).filter((row) =>
    profileSearchHaystack(row).includes(queryLower),
  )
}

async function searchUsersGlobally(
  rawQuery: string,
  currentUserId: string,
  existingProfileIds: Set<string>,
): Promise<DiscoverUserItem[]> {
  if (!supabase) return []
  const query = sanitizeSearchInput(rawQuery).toLowerCase()
  if (!query) return []

  const rowsByTable = await Promise.all([
    searchProfilesInTable('profiles', query),
    searchProfilesInTable('profiles_public', query),
  ])

  const deduped = new Map<string, DiscoverUserItem>()
  const cleanCurrentUserId = normalizeId(currentUserId)

  rowsByTable.flat().forEach((row) => {
    const profileId = normalizeId(textOf(row, ['id'], ''))
    if (!profileId || profileId === cleanCurrentUserId || existingProfileIds.has(profileId)) return
    if (deduped.has(profileId)) return

    const title = nameFromProfile(row).trim() || 'Usuario'
    const username = usernameFromProfile(row).trim()
    const subtitle = username ? `@${username.replace(/^@+/, '')}` : 'Toca para iniciar chat'
    deduped.set(profileId, {
      profileId,
      title,
      subtitle,
      avatarUrl: avatarFromProfile(row),
    })
  })

  return Array.from(deduped.values())
    .sort((a, b) => a.title.localeCompare(b.title, 'es', { sensitivity: 'base' }))
    .slice(0, 25)
}

async function fetchProfileMapByIds(
  ids: string[],
): Promise<Map<string, { name: string; avatarUrl: string; avatarMediaType: string }>> {
  const map = new Map<string, { name: string; avatarUrl: string; avatarMediaType: string }>()
  if (ids.length === 0) return map

  const uniqueIds = Array.from(new Set(ids.map(normalizeId).filter(Boolean)))
  if (uniqueIds.length === 0) return map

  if (isFallbackAuthConfigured) {
    const params = new URLSearchParams({ select: '*', id: `in.(${uniqueIds.join(',')})` })
    const rows = await fallbackApiJson<GenericRow[]>(`/rest/v1/profiles_public?${params}`)
    rows.forEach(profile => {
      const id = normalizeId(textOf(profile, ['id'], ''))
      if (id) {
        const avatarUrl = avatarFromProfile(profile)
        map.set(id, { name: nameFromProfile(profile), avatarUrl, avatarMediaType: avatarMediaTypeSource(profile, avatarUrl) })
      }
    })
    return map
  }
  if (!supabase) return map

  const primary = await supabase.from('profiles').select('*').in('id', uniqueIds)
  if (!primary.error) {
    ;((primary.data ?? []) as GenericRow[]).forEach((profile) => {
      const id = normalizeId(textOf(profile, ['id'], ''))
      if (!id) return
      const avatarUrl = avatarFromProfile(profile)
      map.set(id, {
        name: nameFromProfile(profile),
        avatarUrl,
        avatarMediaType: avatarMediaTypeSource(profile, avatarUrl),
      })
    })
  }

  if (map.size < uniqueIds.length) {
    const missingIds = uniqueIds.filter((id) => !map.has(id))
    if (missingIds.length > 0) {
      const fallback = await supabase.from('profiles_public').select('*').in('id', missingIds)
      if (!fallback.error) {
        ;((fallback.data ?? []) as GenericRow[]).forEach((profile) => {
          const id = normalizeId(textOf(profile, ['id'], ''))
          if (!id) return
          const avatarUrl = avatarFromProfile(profile)
          map.set(id, {
            name: nameFromProfile(profile),
            avatarUrl,
            avatarMediaType: avatarMediaTypeSource(profile, avatarUrl),
          })
        })
      }
    }
  }

  return map
}

async function loadCollection(
  table: string,
  titleKeys: string[],
  subtitleKeys: string[],
  kind: ConversationKind,
): Promise<ConversationItem[]> {
  if (!supabase) return []
  const { data, error } = await supabase
    .from(table)
    .select('*')
    .order('updated_at', { ascending: false })
    .limit(40)
  if (error) return []

  return ((data ?? []) as GenericRow[]).map((row) => {
    const id = textOf(row, ['id'], crypto.randomUUID())
    const openPath =
      table === 'groups'
        ? `/app/messages/group/${id}`
        : table === 'communities'
          ? `/app/messages/community/${id}`
          : table === 'voice_rooms'
            ? '/app/voice'
            : undefined

    return {
      id,
      title: textOf(row, titleKeys, table),
      subtitle: textOf(row, subtitleKeys, 'Sin actividad reciente'),
      updatedAt: textOf(row, ['updated_at', 'created_at'], ''),
      kind,
      avatarUrl: resolveSupabasePublicUrl('avatars', conversationAvatarSource(row)),
      avatarMediaType: avatarMediaTypeSource(row, conversationAvatarSource(row)),
      profileId: null,
      unreadCount: 0,
      isPinned: false,
      isMuted: false,
      isArchived: false,
      isFavorite: false,
      folders: [],
      openPath,
    }
  })
}

function previewFromMessage(row: GenericRow): string {
  const content = textOf(row, ['content'], '').trim()
  if (content) return content
  const contentType = textOf(row, ['content_type'], '').toLowerCase()
  if (contentType === 'sticker') return 'Sticker'
  if (contentType === 'image') return 'Imagen'
  if (contentType === 'video') return 'Video'
  if (contentType === 'audio') return 'Audio'
  if (contentType === 'poll') return 'Encuesta'
  if (contentType.startsWith('view_once')) return 'View once'
  return 'Sin actividad reciente'
}

function sortByUpdatedAt(items: ConversationItem[]): ConversationItem[] {
  return [...items].sort((a, b) => Number(b.isPinned) - Number(a.isPinned) || toMillis(b.updatedAt) - toMillis(a.updatedAt))
}

async function loadRoomCollectionByMembership(options: {
  userId: string
  membershipTable: 'group_members' | 'community_members'
  membershipKey: 'group_id' | 'community_id'
  roomTable: 'groups' | 'communities'
  messageTable: 'group_messages' | 'community_messages'
  titleFallback: string
  kind: 'group' | 'community'
  openPathPrefix: '/app/messages/group/' | '/app/messages/community/'
}): Promise<ConversationItem[]> {
  if (!supabase) return []

  const membershipResult = await supabase
    .from(options.membershipTable)
    .select(options.membershipKey)
    .eq('user_id', options.userId)
    .limit(300)

  if (membershipResult.error) {
    if (isMissingTableError(membershipResult.error)) {
      return loadCollection(
        options.roomTable,
        ['name', 'title'],
        ['description', 'about'],
        options.kind,
      )
    }
    throw membershipResult.error
  }

  const roomIds = Array.from(
    new Set(
      ((membershipResult.data ?? []) as GenericRow[])
        .map((row) => textOf(row, [options.membershipKey], ''))
        .filter(Boolean),
    ),
  )

  if (roomIds.length === 0) return []

  const { data: roomRows, error: roomError } = await supabase
    .from(options.roomTable)
    .select('*')
    .in('id', roomIds)
    .limit(300)
  if (roomError) throw roomError

  const { data: messageRows, error: messageError } = await supabase
    .from(options.messageTable)
    .select(`id, ${options.membershipKey}, content, content_type, created_at, sender_id`)
    .in(options.membershipKey, roomIds)
    .order('created_at', { ascending: false })
    .limit(800)
  if (messageError && !isMissingTableError(messageError)) throw messageError

  const lastMessageByRoom = new Map<string, GenericRow>()
  const senderIds = new Set<string>()
  ;((messageRows ?? []) as GenericRow[]).forEach((row) => {
    const roomId = textOf(row, [options.membershipKey], '')
    if (!roomId || lastMessageByRoom.has(roomId)) return
    lastMessageByRoom.set(roomId, row)
    const senderId = textOf(row, ['sender_id'], '')
    if (senderId) senderIds.add(senderId)
  })

  const profileNameById = new Map<string, string>()
  if (senderIds.size > 0) {
    const profileMap = await fetchProfileMapByIds(Array.from(senderIds))
    profileMap.forEach((value, key) => {
      const id = normalizeId(key)
      if (!id) return
      profileNameById.set(id, value.name || id)
    })
  }

  const mapped = ((roomRows ?? []) as GenericRow[]).map((room) => {
    const id = textOf(room, ['id'], crypto.randomUUID())
    const lastMessage = lastMessageByRoom.get(id)
    const lastSenderId = textOf(lastMessage ?? {}, ['sender_id'], '')
    const lastSenderName = profileNameById.get(lastSenderId)
    const preview = lastMessage
      ? `${lastSenderName ? `${lastSenderName}: ` : ''}${previewFromMessage(lastMessage)}`
      : textOf(room, ['description', 'about'], 'Sin actividad reciente')

    return {
      id,
      title: textOf(
        room,
        ['name', 'title', 'group_name', 'community_name', 'display_name'],
        options.titleFallback,
      ),
      subtitle: preview,
      updatedAt:
        textOf(lastMessage ?? {}, ['created_at'], '') ||
        textOf(room, ['updated_at', 'created_at'], ''),
      kind: options.kind,
      avatarUrl: resolveSupabasePublicUrl('avatars', conversationAvatarSource(room)),
      avatarMediaType: avatarMediaTypeSource(room, conversationAvatarSource(room)),
      profileId: null,
      unreadCount: 0,
      isPinned: false,
      isMuted: false,
      isArchived: false,
      isFavorite: false,
      folders: [],
      openPath: `${options.openPathPrefix}${id}`,
    } satisfies ConversationItem
  })

  return sortByUpdatedAt(mapped)
}

export function MessagesPage() {
  useDraftRevision()
  const { user, isFallbackSession } = useAuth()
  const [webPreferences] = useWebPreferences()
  const navigate = useNavigate()
  const searchInputRef = useRef<HTMLInputElement | null>(null)
  const [activeTab, setActiveTab] = useState<InboxTab>('chats')
  const [isCompactViewport, setIsCompactViewport] = useState(() => {
    if (typeof window === 'undefined') return false
    return window.matchMedia('(max-width: 980px)').matches
  })
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [items, setItems] = useState<ConversationItem[]>([])
  const [openingChatId, setOpeningChatId] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [discoverUsers, setDiscoverUsers] = useState<DiscoverUserItem[]>([])
  const [discoverLoading, setDiscoverLoading] = useState(false)
  const [discoverError, setDiscoverError] = useState<string | null>(null)
  const [localReadByChat, setLocalReadByChat] = useState<LocalReadByChat>(() =>
    readLocalReadByChat(),
  )
  const [showUnreadOnly, setShowUnreadOnly] = useState(false)
  const [showArchived, setShowArchived] = useState(false)
  const [showFavoritesOnly, setShowFavoritesOnly] = useState(false)
  const [openChatMenuId, setOpenChatMenuId] = useState<string | null>(null)
  const [selectedConversation, setSelectedConversation] = useState<SelectedConversation | null>(
    null,
  )

  const inboxCacheKey = useMemo(
    () => `messages:${user?.id ?? 'anon'}:${activeTab}`,
    [activeTab, user?.id],
  )

  const normalizedSearch = useMemo(() => searchQuery.trim().toLowerCase(), [searchQuery])
  const knownChatProfileIds = useMemo(() => {
    const next = new Set<string>()
    items.forEach((item) => {
      if (item.kind !== 'chat' || !item.profileId) return
      const id = normalizeId(item.profileId)
      if (id) next.add(id)
    })
    return next
  }, [items])

  const filteredItems = useMemo(() => {
    const base = normalizedSearch
      ? items.filter((item) => {
          const haystack =
            `${item.title} ${item.subtitle} ${item.folders.join(' ')} ${item.id}`.toLowerCase()
          return haystack.includes(normalizedSearch)
        })
      : items

    if (activeTab !== 'chats') return base

    return base.filter((item) => {
      if (showArchived !== item.isArchived) return false
      if (showUnreadOnly && item.unreadCount <= 0) return false
      if (showFavoritesOnly && !item.isFavorite) return false
      return true
    })
  }, [activeTab, items, normalizedSearch, showArchived, showFavoritesOnly, showUnreadOnly])

  const showGlobalSearchResults = activeTab === 'chats' && normalizedSearch.length >= 2
  const showNoResultsState =
    filteredItems.length === 0 &&
    (!showGlobalSearchResults || (!discoverLoading && discoverUsers.length === 0 && !discoverError))

  useEffect(() => {
    if (activeTab !== 'chats') {
      setShowUnreadOnly(false)
      setShowArchived(false)
      setShowFavoritesOnly(false)
      setSearchQuery('')
    }
    setOpenChatMenuId(null)
  }, [activeTab])

  useEffect(() => {
    if (!user || activeTab !== 'chats' || isFallbackSession) {
      setDiscoverUsers([])
      setDiscoverLoading(false)
      setDiscoverError(null)
      return
    }

    if (normalizedSearch.length < 2) {
      setDiscoverUsers([])
      setDiscoverLoading(false)
      setDiscoverError(null)
      return
    }

    let cancelled = false
    const timer = window.setTimeout(async () => {
      setDiscoverLoading(true)
      setDiscoverError(null)
      try {
        const nextUsers = await searchUsersGlobally(normalizedSearch, user.id, knownChatProfileIds)
        if (!cancelled) setDiscoverUsers(nextUsers)
      } catch (caught) {
        if (!cancelled) {
          setDiscoverUsers([])
          setDiscoverError(friendlyError(caught, 'No se pudo buscar usuarios'))
        }
      } finally {
        if (!cancelled) setDiscoverLoading(false)
      }
    }, 240)

    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [activeTab, isFallbackSession, knownChatProfileIds, normalizedSearch, user])

  const totalUnreadChats = useMemo(
    () => items.filter((item) => item.unreadCount > 0).length,
    [items],
  )
  const title = tabOptions.find((tab) => tab.key === activeTab)?.label || 'Mensajes'
  const supportsSplitLayout =
    !isCompactViewport &&
    (activeTab === 'chats' || activeTab === 'groups' || activeTab === 'communities')

  useEffect(() => {
    if (typeof window === 'undefined') return
    const mediaQuery = window.matchMedia('(max-width: 980px)')
    const syncViewport = () => setIsCompactViewport(mediaQuery.matches)
    syncViewport()
    mediaQuery.addEventListener('change', syncViewport)
    return () => {
      mediaQuery.removeEventListener('change', syncViewport)
    }
  }, [])

  const selectedConversationItem = useMemo(() => {
    if (!selectedConversation) return null
    return (
      items.find(
        (item) => item.id === selectedConversation.id && item.kind === selectedConversation.kind,
      ) ?? null
    )
  }, [items, selectedConversation])

  useEffect(() => {
    if (!supportsSplitLayout) {
      setSelectedConversation(null)
      return
    }
    if (!selectedConversation) return
    if (filteredItems.length === 0 || !selectedConversationItem) {
      setSelectedConversation(null)
    }
  }, [filteredItems, selectedConversation, selectedConversationItem, supportsSplitLayout])

  const loadCurrentTab = useCallback(
    async (options?: { silent?: boolean }) => {
      if (!user) {
        setLoading(false)
        return
      }
      if (options?.silent) {
        setSyncing(true)
      } else {
        setLoading(true)
        setError(null)
      }
      try {
        if (isFallbackSession) {
          const response = await loadFallbackMessagesInbox(activeTab)
          const normalizedItems = await normalizeFallbackInboxItems(response.items)
          const dedupedItems = sortByUpdatedAt(dedupeInboxConversations(normalizedItems))
          setItems(dedupedItems)
          writeCachedValue(inboxCacheKey, dedupedItems)
          return
        }

        if (!supabase) {
          setLoading(false)
          return
        }

        if (activeTab === 'chats') {
          const { data: chatData, error: chatError } = await supabase
            .from('chats')
            .select('*')
            .contains('participants', [user.id])
            .eq('is_group', false)
            .order('last_message_at', { ascending: false })
            .limit(50)

          if (chatError) throw chatError

          const chatRows = (chatData ?? []) as GenericRow[]
          const chatIds = chatRows.map((row) => textOf(row, ['id'], '')).filter(Boolean)

          const participantByChat = new Map<
            string,
            {
              isMuted: boolean
              isPinned: boolean
              isArchived: boolean
              isFavorite: boolean
              lastReadAt: string
            }
          >()
          if (chatIds.length > 0) {
            let participantResult: { data: unknown; error: unknown } = await supabase
              .from('chat_participants')
              .select('chat_id,is_muted,is_pinned,is_archived,is_favorite,last_read_at')
              .eq('user_id', user.id)
              .in('chat_id', chatIds)
            if (participantResult.error && isMissingColumnError(participantResult.error)) {
              participantResult = await supabase
                .from('chat_participants')
                .select('chat_id,is_muted,is_pinned,is_archived,last_read_at')
                .eq('user_id', user.id)
                .in('chat_id', chatIds)
            }
            if (participantResult.error && isMissingColumnError(participantResult.error)) {
              participantResult = await supabase
                .from('chat_participants')
                .select('chat_id,is_muted,is_pinned,last_read_at')
                .eq('user_id', user.id)
                .in('chat_id', chatIds)
            }
            const { data: participantRows, error: participantError } = participantResult
            if (participantError) {
              if (!isMissingTableError(participantError)) throw participantError
            } else {
              ;((participantRows ?? []) as GenericRow[]).forEach((row) => {
                const chatId = textOf(row, ['chat_id'], '')
                if (!chatId) return
                participantByChat.set(chatId, {
                  isMuted: Boolean(row.is_muted),
                  isPinned: Boolean(row.is_pinned),
                  isArchived: Boolean(row.is_archived),
                  isFavorite: Boolean(row.is_favorite),
                  lastReadAt: textOf(row, ['last_read_at'], ''),
                })
              })
            }
          }

          const folderNamesByChat = new Map<string, string[]>()
          const cleanCurrentUserId = normalizeId(user.id)
          const otherByChat = new Map<string, string>()

          if (chatIds.length > 0) {
            const { data: participantRows, error: pError } = await supabase
              .from('chat_participants')
              .select('chat_id,user_id')
              .in('chat_id', chatIds)
              .neq('user_id', user.id)
            if (pError && !isMissingTableError(pError)) throw pError
            ;((participantRows ?? []) as GenericRow[]).forEach((row) => {
              const chatId = textOf(row, ['chat_id'], '')
              const otherId = normalizeId(textOf(row, ['user_id'], ''))
              if (!chatId || !otherId || otherByChat.has(chatId)) return
              otherByChat.set(chatId, otherId)
            })
          }

          chatRows.forEach((row) => {
            const chatId = textOf(row, ['id'], '')
            if (!chatId || otherByChat.has(chatId)) return
            const participants = stringArrayOf(row, ['participants']).map(normalizeId)
            const other = participants.find((id) => id !== cleanCurrentUserId)
            if (other) otherByChat.set(chatId, other)
          })

          const profileById = await fetchProfileMapByIds(Array.from(otherByChat.values()))

          const unreadByChat = new Map<string, number>()
          if (chatIds.length > 0) {
            const { data: unreadRows, error: unreadError } = await supabase
              .from('messages')
              .select('chat_id,sender_id,created_at,read_at,status')
              .in('chat_id', chatIds)
              .neq('sender_id', user.id)
            if (unreadError) throw unreadError
            ;((unreadRows ?? []) as GenericRow[]).forEach((row) => {
              const chatId = textOf(row, ['chat_id'], '')
              if (!chatId) return
              const participant = participantByChat.get(chatId)
              const lastReadAtMillis = Math.max(
                toMillis(participant?.lastReadAt ?? ''),
                toMillis(localReadByChat[chatId] ?? ''),
              )
              const createdAtMillis = toMillis(textOf(row, ['created_at'], ''))
              const readAt = textOf(row, ['read_at'], '')
              const status = textOf(row, ['status'], '').toLowerCase()
              if (readAt || status === 'read' || status === 'seen') return
              if (
                lastReadAtMillis > 0 &&
                createdAtMillis > 0 &&
                createdAtMillis <= lastReadAtMillis
              )
                return
              unreadByChat.set(chatId, (unreadByChat.get(chatId) ?? 0) + 1)
            })
          }

          const mapped = chatRows.map((row) => {
            const id = textOf(row, ['id'], crypto.randomUUID())
            const isGroup = Boolean(row.is_group)
            const other = otherByChat.get(id) ?? null
            const profile = other ? profileById.get(normalizeId(other)) : null
            const participant = participantByChat.get(id)
            const fallbackName = textOf(
              row,
              ['other_user_name', 'display_name', 'user_name', 'name', 'group_name'],
              'Chat privado',
            )
            const fallbackAvatar = resolveSupabasePublicUrl(
              'avatars',
              conversationAvatarSource(row),
            )
            return {
              id,
              title: isGroup
                ? textOf(row, ['group_name', 'name'], 'Grupo')
                : profile?.name || fallbackName,
              subtitle: textOf(row, ['last_message'], 'Sin mensajes'),
              updatedAt: textOf(row, ['last_message_at', 'updated_at', 'created_at'], ''),
              kind: 'chat' as const,
              avatarUrl: profile?.avatarUrl || fallbackAvatar,
              avatarMediaType:
                profile?.avatarMediaType || avatarMediaTypeSource(row, fallbackAvatar),
              profileId: other ?? null,
              unreadCount: unreadByChat.get(id) ?? 0,
              isMuted: participant?.isMuted ?? false,
              isPinned: participant?.isPinned ?? false,
              isArchived: participant?.isArchived ?? false,
              isFavorite: participant?.isFavorite ?? false,
              folders: folderNamesByChat.get(id) ?? [],
              openPath: `/app/messages/${id}`,
            } satisfies ConversationItem
          })

          mapped.sort((a, b) => {
            if (a.isFavorite !== b.isFavorite) return a.isFavorite ? -1 : 1
            if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1
            return toMillis(b.updatedAt) - toMillis(a.updatedAt)
          })
          const dedupedItems = dedupeInboxConversations(mapped)
          setItems(dedupedItems)
          writeCachedValue(inboxCacheKey, dedupedItems)
        } else if (activeTab === 'groups') {
          const nextItems = await loadRoomCollectionByMembership({
            userId: user.id,
            membershipTable: 'group_members',
            membershipKey: 'group_id',
            roomTable: 'groups',
            messageTable: 'group_messages',
            titleFallback: 'Grupo',
            kind: 'group',
            openPathPrefix: '/app/messages/group/',
          })
          setItems(nextItems)
          writeCachedValue(inboxCacheKey, nextItems)
        } else if (activeTab === 'communities') {
          const nextItems = await loadRoomCollectionByMembership({
            userId: user.id,
            membershipTable: 'community_members',
            membershipKey: 'community_id',
            roomTable: 'communities',
            messageTable: 'community_messages',
            titleFallback: 'Comunidad',
            kind: 'community',
            openPathPrefix: '/app/messages/community/',
          })
          setItems(nextItems)
          writeCachedValue(inboxCacheKey, nextItems)
        } else {
          const nextItems = await loadCollection(
            'voice_rooms',
            ['name', 'title', 'readable_id'],
            ['topic'],
            'voice',
          )
          setItems(nextItems)
          writeCachedValue(inboxCacheKey, nextItems)
        }
      } catch (caught) {
        reportSupabaseServiceRestrictionIfPresent(`messages-${activeTab}`, caught)
        if (!options?.silent) {
          setError(friendlyError(caught, `No se pudo cargar ${title.toLowerCase()}`))
        }
      } finally {
        setLoading(false)
        setSyncing(false)
      }
    },
    [activeTab, inboxCacheKey, isFallbackSession, localReadByChat, title, user],
  )

  useEffect(() => {
    const cached = readCachedValue<ConversationItem[]>(inboxCacheKey, 1000 * 60 * 60 * 8)
    if (cached && cached.length > 0) {
      setItems(cached.map(normalizeConversationAvatar))
      setLoading(false)
      void loadCurrentTab({ silent: true })
      return
    }
    void loadCurrentTab({ silent: false })
  }, [inboxCacheKey, loadCurrentTab])

  useEffect(() => {
    const reload = () => void loadCurrentTab({ silent: true })
    window.addEventListener('novvin:conversation-preferences-changed', reload)
    return () => window.removeEventListener('novvin:conversation-preferences-changed', reload)
  }, [loadCurrentTab])

  const updateChatPreferences = async (
    chatId: string,
    patch: Partial<Pick<ConversationItem, 'isMuted' | 'isPinned' | 'isArchived' | 'isFavorite'>>,
  ) => {
    if (!user) return
    const current = items.find((item) => item.id === chatId)
    const nextMuted = patch.isMuted ?? current?.isMuted ?? false
    const nextPinned = patch.isPinned ?? current?.isPinned ?? false
    const nextArchived = patch.isArchived ?? current?.isArchived ?? false
    const nextFavorite = patch.isFavorite ?? current?.isFavorite ?? false
    if (isFallbackSession) {
      try {
        await saveFallbackChatPreferences(chatId, patch)
        setItems(currentItems => sortByUpdatedAt(currentItems.map(item => item.id === chatId ? { ...item, ...patch } : item)))
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudieron guardar los cambios del chat'))
      }
      return
    }
    if (!supabase) return
    try {
      const { error: rpcError } = await supabase.rpc('secure_set_chat_preferences', {
        p_chat_id: chatId,
        p_is_muted: nextMuted,
        p_is_pinned: nextPinned,
        p_is_archived: nextArchived,
        p_is_favorite: nextFavorite,
      })
      if (rpcError && !isMissingFunctionError(rpcError)) throw rpcError
      if (rpcError && isMissingFunctionError(rpcError)) {
        const basePayload = {
          chat_id: chatId,
          user_id: user.id,
          joined_at: new Date().toISOString(),
          is_muted: nextMuted,
          is_pinned: nextPinned,
          is_archived: nextArchived,
          is_favorite: nextFavorite,
        }
        const fullFallback = await supabase
          .from('chat_participants')
          .upsert(basePayload, { onConflict: 'chat_id,user_id' })
        if (fullFallback.error && !isMissingColumnError(fullFallback.error))
          throw fullFallback.error
        if (fullFallback.error && isMissingColumnError(fullFallback.error)) {
          const archiveFallback = await supabase.from('chat_participants').upsert(
            {
              chat_id: chatId,
              user_id: user.id,
              joined_at: new Date().toISOString(),
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
            void loadCurrentTab({ silent: true })
            return
          }
          const { error: legacyFallbackError } = await supabase.from('chat_participants').upsert(
            {
              chat_id: chatId,
              user_id: user.id,
              joined_at: new Date().toISOString(),
              is_muted: nextMuted,
              is_pinned: nextPinned,
            },
            { onConflict: 'chat_id,user_id' },
          )
          if (legacyFallbackError) throw legacyFallbackError
        }
      }
      void loadCurrentTab({ silent: true })
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('messages-preferences', caught)
      setError(friendlyError(caught, 'No se pudieron actualizar preferencias'))
    }
  }

  async function markChatAsReadBestEffort(chatId: string) {
    if (!user || !chatId) return
    const nowIso = new Date().toISOString()
    const cleanChatId = chatId.trim().toLowerCase()
    setLocalReadByChat((current) => {
      const next = { ...current, [cleanChatId]: nowIso }
      writeLocalReadByChat(next)
      return next
    })
    setItems((current) =>
      current.map((entry) => (entry.id === cleanChatId ? { ...entry, unreadCount: 0 } : entry)),
    )

    if (isFallbackSession) {
      try {
        await markFallbackChatRead(cleanChatId)
        void loadCurrentTab({ silent: true })
      } catch (caught) {
        console.warn('markFallbackChatRead failed', caught)
      }
      return
    }
    if (!supabase) return

    const client = supabase

    if (!isBrowserOnline()) {
      enqueueDeferredMutation('chat_mark_read', { chatId: cleanChatId }, user.id)
      return
    }

    try {
      await runWithRetry(
        async () => {
          await client.rpc('secure_mark_messages_delivered', {
            p_chat_id: cleanChatId,
          })
          await client.rpc('secure_mark_messages_read', {
            p_chat_id: cleanChatId,
          })
          await client
            .from('chat_participants')
            .upsert(
              { chat_id: cleanChatId, user_id: user.id, last_read_at: nowIso },
              { onConflict: 'chat_id,user_id' },
            )
        },
        { maxRetries: 2, shouldRetry: isLikelyTransientError },
      )
    } catch (caught) {
      if (isLikelyTransientError(caught)) {
        enqueueDeferredMutation('chat_mark_read', { chatId: cleanChatId }, user.id)
      }
    }
  }

  async function openConversation(item: ConversationItem) {
    setOpenChatMenuId(null)
    if (item.kind === 'voice') {
      if (item.openPath) navigate(item.openPath)
      return
    }
    if (item.kind === 'chat') {
      if (item.id && !openingChatId) {
        setOpeningChatId(item.id)
        try {
          await markChatAsReadBestEffort(item.id)
          if (supportsSplitLayout) {
            setSelectedConversation({ id: item.id, kind: 'chat' })
          } else if (item.openPath) {
            navigate(item.openPath)
          } else {
            navigate(`/app/messages/${item.id}`)
          }
        } finally {
          setOpeningChatId(null)
        }
      }
      return
    }
    if (item.kind === 'group' || item.kind === 'community') {
      if (supportsSplitLayout) {
        setSelectedConversation({ id: item.id, kind: item.kind })
      } else {
        navigate(item.openPath || `/app/messages/${item.kind}/${encodeURIComponent(item.id)}`)
      }
      return
    }
    if (item.openPath) {
      navigate(item.openPath)
    }
  }

  async function openDiscoveredUser(item: DiscoverUserItem) {
    if (!user || openingChatId) return
    const cleanProfileId = normalizeId(item.profileId)
    if (!cleanProfileId) return

    const existingChat = items.find(
      (entry) => entry.kind === 'chat' && normalizeId(entry.profileId ?? '') === cleanProfileId,
    )
    if (existingChat) {
      await openConversation(existingChat)
      return
    }

    setOpeningChatId(`discover:${cleanProfileId}`)
    try {
      const chatId = await getOrCreateDirectChat(user.id, cleanProfileId)
      await markChatAsReadBestEffort(chatId)
      await loadCurrentTab({ silent: true })
      if (supportsSplitLayout) {
        setSelectedConversation({ id: chatId, kind: 'chat' })
      } else {
        navigate(`/app/messages/${chatId}`)
      }
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo iniciar el chat'))
    } finally {
      setOpeningChatId(null)
    }
  }

  useEffect(() => {
    if (!supabase || !user || isFallbackSession) return
    const client = supabase
    const scheduleRefresh = () => {
      const timer = setTimeout(() => void loadCurrentTab({ silent: true }), 2000)
      return timer
    }
    let refreshTimer: ReturnType<typeof setTimeout>
    const channel = client
      .channel('conectate-web-live-messages')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'messages' }, () => {
        if (refreshTimer) clearTimeout(refreshTimer)
        refreshTimer = scheduleRefresh()
      })
    const subscribedChannel = subscribeRealtimeChannel(channel, {
      reason: 'messages-live',
    })
    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [isFallbackSession, loadCurrentTab, user])

  useEffect(() => {
    const closeMenuOnOutsideClick = (event: MouseEvent) => {
      const target = event.target as HTMLElement | null
      if (!target) return
      if (target.closest('.conversation-row-menu') || target.closest('.more-btn')) return
      setOpenChatMenuId(null)
    }
    window.addEventListener('mousedown', closeMenuOnOutsideClick)
    return () => {
      window.removeEventListener('mousedown', closeMenuOnOutsideClick)
    }
  }, [])

  if (!supabase && !isFallbackSession) {
    return (
      <StatusView
        title="Supabase no configurado"
        description="Debes configurar el .env para usar mensajes."
      />
    )
  }

  const renderConversationRow = (item: ConversationItem, isSelected = false) => (
    <div
      key={item.id}
      className={
        openChatMenuId === item.id
          ? isSelected
            ? 'conversation-row selected menu-open'
            : 'conversation-row menu-open'
          : isSelected
            ? 'conversation-row selected'
            : 'conversation-row'
      }
      role="button"
      tabIndex={0}
      aria-label={`Abrir conversación con ${item.title}`}
      onKeyDown={(event) => {
        if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault()
          void openConversation(item)
        }
      }}
      onClick={() => void openConversation(item)}
    >
      <FallbackAvatar
        url={item.avatarUrl}
        mediaType={item.avatarMediaType}
        name={item.title}
        className="conversation-avatar"
      />
      <div className="conversation-copy">
        <div className="row-between">
          <h3>{item.title}</h3>
          <small className="timestamp">{inboxTime(item.updatedAt)}</small>
        </div>
        <p className={item.kind!=='voice'&&readConversationDraft(user?.id,item.kind,item.id)?'conversation-draft':undefined}>{item.kind!=='voice'&&readConversationDraft(user?.id,item.kind,item.id)?`Borrador: ${readConversationDraft(user?.id,item.kind,item.id)}`:item.subtitle}</p>
      </div>
      <div className="conversation-status-col">
        {item.unreadCount > 0 && (
          <span className="unread-pill">{item.unreadCount > 99 ? '99+' : item.unreadCount}</span>
        )}
        {item.isFavorite && (
          <span className="icon-tiny" title="Favorito">
            ★
          </span>
        )}
        {item.isPinned && <span className="icon-tiny">📌</span>}
        {item.isMuted && (
          <span className="icon-tiny" title="Silenciado">
            🔕
          </span>
        )}
        {item.isArchived && (
          <span className="icon-tiny" title="Archivado">
            🗄
          </span>
        )}
        {item.kind === 'chat' ? (
          <>
            <button
              className="icon-btn-sm more-btn"
              onClick={(e) => {
                e.stopPropagation()
                setOpenChatMenuId((current) => (current === item.id ? null : item.id))
              }}
              aria-label="Opciones"
              title="Opciones"
            >
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <circle cx="12" cy="5" r="1.8" fill="currentColor" />
                <circle cx="12" cy="12" r="1.8" fill="currentColor" />
                <circle cx="12" cy="19" r="1.8" fill="currentColor" />
              </svg>
            </button>
            {openChatMenuId === item.id ? (
              <div className="conversation-row-menu" onClick={(e) => e.stopPropagation()}>
                <button
                  type="button"
                  onClick={() => {
                    void updateChatPreferences(item.id, {
                      isPinned: !item.isPinned,
                    })
                    setOpenChatMenuId(null)
                  }}
                >
                  {item.isPinned ? 'Despinear chat' : 'Pinear chat'}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    void updateChatPreferences(item.id, {
                      isMuted: !item.isMuted,
                    })
                    setOpenChatMenuId(null)
                  }}
                >
                  {item.isMuted ? 'Activar notificaciones' : 'Silenciar chat'}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    void updateChatPreferences(item.id, {
                      isFavorite: !item.isFavorite,
                    })
                    setOpenChatMenuId(null)
                  }}
                >
                  {item.isFavorite ? 'Quitar favorito' : 'Marcar favorito'}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    void updateChatPreferences(item.id, {
                      isArchived: !item.isArchived,
                    })
                    setOpenChatMenuId(null)
                  }}
                >
                  {item.isArchived ? 'Sacar de archivados' : 'Archivar chat'}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    void markChatAsReadBestEffort(item.id)
                    setOpenChatMenuId(null)
                  }}
                >
                  Marcar como leído
                </button>
              </div>
            ) : null}
          </>
        ) : null}
      </div>
    </div>
  )

  const renderDiscoveredUserRow = (item: DiscoverUserItem) => (
    <button
      key={`discover-${item.profileId}`}
      className="conversation-row discover-row"
      type="button"
      onClick={() => void openDiscoveredUser(item)}
      disabled={openingChatId === `discover:${item.profileId}`}
    >
      <FallbackAvatar url={item.avatarUrl} name={item.title} className="conversation-avatar" />
      <div className="conversation-copy">
        <div className="row-between">
          <h3>{item.title}</h3>
          <small className="timestamp">Usuario</small>
        </div>
        <p>{item.subtitle}</p>
      </div>
      <div className="conversation-status-col">
        <span className="folder-tag">Nuevo chat</span>
      </div>
    </button>
  )

  const renderInbox = () => (
    <>
      <header className="messages-inbox-head">
        <h1>Novvin</h1>
        <div className="row-actions messages-head-actions"><Link className="icon-btn" to="/app/assistant" aria-label="Asistente de Novvin" title="Asistente de Novvin">✦</Link>
          <Link className="icon-btn" to="/app/calls" aria-label="Llamadas" title="Llamadas">
            <Phone size={21} />
          </Link>
          <button
            className="icon-btn"
            type="button"
            aria-label={activeTab === 'chats' ? 'Nuevo mensaje' : 'Crear espacio'}
            title="Nuevo mensaje"
            onClick={() => {
              if (activeTab !== 'chats') {
                navigate(
                  `/app/create-space?type=${activeTab === 'groups' ? 'group' : activeTab === 'communities' ? 'community' : 'voice'}`,
                )
                return
              }
              setShowArchived(false)
              setShowFavoritesOnly(false)
              setShowUnreadOnly(false)
              setSearchQuery('')
              searchInputRef.current?.focus()
            }}
          >
            {activeTab === 'chats' ? <MessageSquarePlus size={22} /> : <Plus size={22} />}
          </button>
          <button className="icon-btn" type="button" aria-label="Menú" onClick={openNovvinMenu}>
            <MoreVertical size={22} />
          </button>
        </div>
      </header>
      <div className="tab-pill-row messages-tab-row" aria-label="Conversaciones">
        {tabOptions.map((tab) => (
          <button
            key={tab.key}
            className={tab.key === activeTab ? 'tab-pill active' : 'tab-pill'}
            aria-pressed={tab.key === activeTab}
            onClick={() => setActiveTab(tab.key)}
            type="button"
          >
            {tab.label}
            {tab.key === 'chats' && totalUnreadChats > 0 ? <span className="tab-dot" /> : null}
          </button>
        ))}
      </div>
      {activeTab === 'chats' ? (
        <div className="inbox-controls">
          <label className="inbox-search">
            <Search size={19} />
            <input
              ref={searchInputRef}
              className="text-input inbox-search-input"
              placeholder="Buscar o iniciar un chat"
              aria-label="Buscar conversaciones o personas"
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
            />
          </label>
          <div className="inbox-filter-row" aria-label="Filtrar conversaciones">
            <button
              type="button"
              className={
                !showUnreadOnly && !showFavoritesOnly && !showArchived
                  ? 'chip-btn active'
                  : 'chip-btn'
              }
              onClick={() => {
                setShowUnreadOnly(false)
                setShowFavoritesOnly(false)
                setShowArchived(false)
              }}
            >
              Todos
            </button>
            <button
              type="button"
              className={showUnreadOnly ? 'chip-btn active' : 'chip-btn'}
              aria-pressed={showUnreadOnly}
              onClick={() => { setShowUnreadOnly(current => !current); setShowFavoritesOnly(false); setShowArchived(false) }}
            >
              No leídos
            </button>
            <button
              type="button"
              className={showFavoritesOnly ? 'chip-btn active' : 'chip-btn'}
              aria-pressed={showFavoritesOnly}
              onClick={() => { setShowFavoritesOnly(current => !current); setShowUnreadOnly(false); setShowArchived(false) }}
            >
              Favoritos
            </button>
            <button
              type="button"
              className={showArchived ? 'chip-btn active' : 'chip-btn'}
              aria-pressed={showArchived}
              onClick={() => { setShowArchived(current => !current); setShowUnreadOnly(false); setShowFavoritesOnly(false) }}
            >
              Archivados
            </button>
          </div>
        </div>
      ) : null}
      <span className="sr-only" role="status">
        {syncing ? 'Actualizando conversaciones' : ''}
      </span>
      <div className="conversation-scroll">
        {loading ? (
          <StatusView title="Cargando conversaciones" description="Un momento…" />
        ) : error ? (
          <StatusView title="No se pudieron cargar las conversaciones" description={error} />
        ) : showNoResultsState ? (
          <StatusView
            title="Sin conversaciones"
            description={
              searchQuery
                ? 'No hay coincidencias para esta búsqueda.'
                : 'Busca a una persona para empezar a conversar.'
            }
          />
        ) : (
          <div className="conversation-list">
            {filteredItems.map((item) =>
              renderConversationRow(
                item,
                selectedConversation?.id === item.id && selectedConversation?.kind === item.kind,
              ),
            )}
            {showGlobalSearchResults ? (
              <div className="discover-section">
                <small className="discover-title">Personas</small>
                {discoverLoading ? (
                  <small>Buscando…</small>
                ) : discoverError ? (
                  <small className="error-text">{discoverError}</small>
                ) : (
                  discoverUsers.map(renderDiscoveredUserRow)
                )}
              </div>
            ) : null}
          </div>
        )}
      </div>
    </>
  )

  return (
    <section className="messages-page">
      {supportsSplitLayout ? (
        <div
          className={
            webPreferences.compactInbox
              ? 'messages-split-layout compact-inbox-enabled'
              : 'messages-split-layout'
          }
        >
          <aside className="messages-list-pane" aria-label="Lista de conversaciones">
            {renderInbox()}
          </aside>
          <section className="messages-detail-pane" aria-label="Conversación">
            {!selectedConversationItem ? (
              <div className="messages-empty-state">
                <img src="/novvin-logo.png" alt="" />
                <h2>Novvin para web</h2>
                <p>Selecciona un chat para seguir la conversación.</p>
              </div>
            ) : selectedConversationItem.kind === 'chat' ? (
              <ChatDetailPage
                key={`chat-${selectedConversationItem.id}`}
                chatIdOverride={selectedConversationItem.id}
                embedded
              />
            ) : (
              <RoomChatPage
                key={`${selectedConversationItem.kind}-${selectedConversationItem.id}`}
                scope={selectedConversationItem.kind === 'group' ? 'group' : 'community'}
                roomId={selectedConversationItem.id}
                embedded
              />
            )}
          </section>
        </div>
      ) : (
        <div className="messages-list-pane">{renderInbox()}</div>
      )}
    </section>
  )
}
