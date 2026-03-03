import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import {
  enqueueDeferredMutation,
  isBrowserOnline,
  isLikelyTransientError,
  runWithRetry,
} from '../../core/reliability'
import { supabase } from '../../core/supabase'
import {
  formatDateTime,
  friendlyError,
  isMissingTableError,
  stringArrayOf,
  textOf,
  type GenericRow,
} from '../../core/utils'
import { useAuth } from '../auth/AuthProvider'
import { ChatDetailPage } from './ChatDetailPage'
import { RoomChatPage } from './RoomChatPage'

type InboxTab = 'chats' | 'groups' | 'communities' | 'voice'

type ConversationItem = {
  id: string
  title: string
  subtitle: string
  updatedAt: string
  kind: 'chat' | 'group' | 'community' | 'voice'
  avatarUrl: string
  profileId: string | null
  unreadCount: number
  isPinned: boolean
  isMuted: boolean
  folders: string[]
  openPath?: string
}

type SelectedConversation = {
  id: string
  kind: 'chat' | 'group' | 'community'
}

type ChatFolder = {
  id: string
  name: string
  colorHex: string
}

type LocalReadByChat = Record<string, string>

const tabOptions: Array<{ key: InboxTab; label: string }> = [
  { key: 'chats', label: 'Chats' },
  { key: 'groups', label: 'Grupos' },
  { key: 'communities', label: 'Comunidad' },
  { key: 'voice', label: 'Voz' },
]

const localReadStorageKey = 'conectate-web-chat-last-read'

function profilePathFor(profileId: string, currentUserId?: string): string {
  const clean = profileId.trim()
  if (!clean) return '/app/profile'
  if (currentUserId && clean === currentUserId) return '/app/profile'
  return `/app/profile/${clean}`
}

function labelByKind(kind: ConversationItem['kind']): string {
  if (kind === 'chat') return 'Chat'
  if (kind === 'group') return 'Grupo'
  if (kind === 'community') return 'Comunidad'
  return 'Voz'
}

function initialsOf(name: string): string {
  const clean = name.trim()
  if (!clean) return 'U'
  return clean[0]?.toUpperCase() ?? 'U'
}

function toMillis(value: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function readLocalReadByChat(): LocalReadByChat {
  if (typeof window === 'undefined') return {}
  try {
    const raw = window.localStorage.getItem(localReadStorageKey)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    const entries = Object.entries(parsed as Record<string, unknown>)
      .filter(([chatId, value]) => typeof value === 'string' && Boolean(chatId.trim()))
      .map(([chatId, value]) => [chatId.trim(), String(value)] as const)
    return Object.fromEntries(entries)
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
  return textOf(row, ['avatar_url', 'photo_url', 'profile_image', 'image'], '')
}

async function fetchProfileMapByIds(ids: string[]): Promise<Map<string, { name: string; avatarUrl: string }>> {
  const map = new Map<string, { name: string; avatarUrl: string }>()
  if (!supabase || ids.length === 0) return map

  const uniqueIds = Array.from(new Set(ids.map(normalizeId).filter(Boolean)))
  if (uniqueIds.length === 0) return map

  const primary = await supabase.from('profiles').select('*').in('id', uniqueIds)
  if (!primary.error) {
    ;((primary.data ?? []) as GenericRow[]).forEach((profile) => {
      const id = normalizeId(textOf(profile, ['id'], ''))
      if (!id) return
      map.set(id, { name: nameFromProfile(profile), avatarUrl: avatarFromProfile(profile) })
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
          map.set(id, { name: nameFromProfile(profile), avatarUrl: avatarFromProfile(profile) })
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
  kind: ConversationItem['kind'],
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
      avatarUrl: textOf(
        row,
        ['avatar_url', 'photo_url', 'profile_image', 'icon_url', 'cover_url', 'image_url', 'image', 'group_image', 'community_image'],
        '',
      ),
      profileId: null,
      unreadCount: 0,
      isPinned: false,
      isMuted: false,
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
  return [...items].sort((a, b) => toMillis(b.updatedAt) - toMillis(a.updatedAt))
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
    .select(`id,${options.membershipKey},content,content_type,created_at,sender_id`)
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
      updatedAt: textOf(lastMessage ?? {}, ['created_at'], '') || textOf(room, ['updated_at', 'created_at'], ''),
      kind: options.kind,
      avatarUrl: textOf(
        room,
        [
          'avatar_url',
          'photo_url',
          'profile_image',
          'icon_url',
          'cover_url',
          'image_url',
          'image',
          'group_image',
          'community_image',
        ],
        '',
      ),
      profileId: null,
      unreadCount: 0,
      isPinned: false,
      isMuted: false,
      folders: [],
      openPath: `${options.openPathPrefix}${id}`,
    } satisfies ConversationItem
  })

  return sortByUpdatedAt(mapped)
}

export function MessagesPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState<InboxTab>('chats')
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [items, setItems] = useState<ConversationItem[]>([])
  const [folders, setFolders] = useState<ChatFolder[]>([])
  const [folderNameDraft, setFolderNameDraft] = useState('')
  const [creatingFolder, setCreatingFolder] = useState(false)
  const [folderSelectionByChat, setFolderSelectionByChat] = useState<Record<string, string>>({})
  const [markingAllRead, setMarkingAllRead] = useState(false)
  const [openingChatId, setOpeningChatId] = useState<string | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [localReadByChat, setLocalReadByChat] = useState<LocalReadByChat>(() => readLocalReadByChat())
  const [showUnreadOnly, setShowUnreadOnly] = useState(false)
  const [syncMessage, setSyncMessage] = useState<string | null>(null)
  const [selectedConversation, setSelectedConversation] = useState<SelectedConversation | null>(null)
  const inboxCacheKey = useMemo(
    () => `messages:${user?.id ?? 'anon'}:${activeTab}`,
    [activeTab, user?.id],
  )

  const title = useMemo(
    () => tabOptions.find((option) => option.key === activeTab)?.label ?? 'Mensajes',
    [activeTab],
  )
  const normalizedSearch = useMemo(() => searchQuery.trim().toLowerCase(), [searchQuery])
  const filteredItems = useMemo(() => {
    const base = normalizedSearch
      ? items.filter((item) => {
          const haystack = `${item.title} ${item.subtitle} ${item.folders.join(' ')} ${item.id}`.toLowerCase()
          return haystack.includes(normalizedSearch)
        })
      : items

    if (activeTab !== 'chats' || !showUnreadOnly) {
      return base
    }

    return base.filter((item) => item.unreadCount > 0)
  }, [activeTab, items, normalizedSearch, showUnreadOnly])

  useEffect(() => {
    if (activeTab !== 'chats') {
      setShowUnreadOnly(false)
    }
  }, [activeTab])

  const totalUnreadChats = useMemo(
    () => items.filter((item) => item.unreadCount > 0).length,
    [items],
  )

  const filteredUnreadChats = useMemo(
    () => filteredItems.filter((item) => item.unreadCount > 0).length,
    [filteredItems],
  )

  const filteredChatsCount = useMemo(
    () => filteredItems.length,
    [filteredItems],
  )

  const supportsSplitLayout = activeTab === 'chats' || activeTab === 'groups' || activeTab === 'communities'

  const selectedConversationItem = useMemo(() => {
    if (!selectedConversation) return null
    return filteredItems.find(
      (item) => item.id === selectedConversation.id && item.kind === selectedConversation.kind,
    ) ?? null
  }, [filteredItems, selectedConversation])

  useEffect(() => {
    if (!supportsSplitLayout) {
      setSelectedConversation(null)
      return
    }
    if (filteredItems.length === 0) {
      setSelectedConversation(null)
      return
    }
    if (selectedConversationItem) return
    const first = filteredItems[0]
    if (first.kind === 'chat' || first.kind === 'group' || first.kind === 'community') {
      setSelectedConversation({ id: first.id, kind: first.kind })
    }
  }, [filteredItems, selectedConversationItem, supportsSplitLayout])

  const loadCurrentTab = useCallback(async (options?: { silent?: boolean }) => {
    if (!supabase || !user) {
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
          { isMuted: boolean; isPinned: boolean; lastReadAt: string }
        >()
        if (chatIds.length > 0) {
          const { data: participantRows, error: participantError } = await supabase
            .from('chat_participants')
            .select('chat_id,is_muted,is_pinned,last_read_at')
            .eq('user_id', user.id)
            .in('chat_id', chatIds)
          if (participantError) {
            if (!isMissingTableError(participantError)) throw participantError
          } else {
            ;((participantRows ?? []) as GenericRow[]).forEach((row) => {
              const chatId = textOf(row, ['chat_id'], '')
              if (!chatId) return
              participantByChat.set(chatId, {
                isMuted: Boolean(row.is_muted),
                isPinned: Boolean(row.is_pinned),
                lastReadAt: textOf(row, ['last_read_at'], ''),
              })
            })
          }
        }

        const folderById = new Map<string, ChatFolder>()
        if (chatIds.length > 0) {
          const { data: folderRows, error: folderError } = await supabase
            .from('chat_folders')
            .select('*')
            .eq('user_id', user.id)
            .order('name', { ascending: true })
          if (folderError) {
            if (!isMissingTableError(folderError)) throw folderError
          } else {
            const mappedFolders = ((folderRows ?? []) as GenericRow[]).map((row) => ({
              id: textOf(row, ['id'], crypto.randomUUID()),
              name: textOf(row, ['name'], 'Carpeta'),
              colorHex: textOf(row, ['color_hex'], ''),
            }))
            mappedFolders.forEach((folder) => folderById.set(folder.id, folder))
            setFolders(mappedFolders)
          }
        } else {
          setFolders([])
        }

        const folderNamesByChat = new Map<string, string[]>()
        if (chatIds.length > 0 && folderById.size > 0) {
          const { data: folderChatRows, error: folderChatsError } = await supabase
            .from('chat_folder_chats')
            .select('folder_id,chat_id')
            .in('chat_id', chatIds)
          if (folderChatsError) {
            if (!isMissingTableError(folderChatsError)) throw folderChatsError
          } else {
            ;((folderChatRows ?? []) as GenericRow[]).forEach((row) => {
              const folderId = textOf(row, ['folder_id'], '')
              const chatId = textOf(row, ['chat_id'], '')
              if (!folderId || !chatId) return
              const folderName = folderById.get(folderId)?.name
              if (!folderName) return
              const current = folderNamesByChat.get(chatId) ?? []
              folderNamesByChat.set(chatId, Array.from(new Set([...current, folderName])))
            })
          }
        }

        const cleanCurrentUserId = normalizeId(user.id)
        const otherByChat = new Map<string, string>()
        if (chatIds.length > 0) {
          const { data: participantRows, error: participantRowsError } = await supabase
            .from('chat_participants')
            .select('chat_id,user_id')
            .in('chat_id', chatIds)
            .neq('user_id', user.id)
          if (participantRowsError) {
            if (!isMissingTableError(participantRowsError)) throw participantRowsError
          } else {
            ;((participantRows ?? []) as GenericRow[]).forEach((row) => {
              const chatId = textOf(row, ['chat_id'], '')
              const otherId = normalizeId(textOf(row, ['user_id'], ''))
              if (!chatId || !otherId || otherByChat.has(chatId)) return
              otherByChat.set(chatId, otherId)
            })
          }
        }

        chatRows.forEach((row) => {
          const chatId = textOf(row, ['id'], '')
          if (!chatId || otherByChat.has(chatId)) return
          const directOtherId = normalizeId(textOf(row, ['other_user_id', 'otherUserId', 'target_user_id'], ''))
          if (directOtherId && directOtherId !== cleanCurrentUserId) {
            otherByChat.set(chatId, directOtherId)
            return
          }
          const participants = stringArrayOf(row, ['participants']).map(normalizeId)
          const other = participants.find((id) => id !== cleanCurrentUserId)
          if (other) otherByChat.set(chatId, other)
        })

        if (chatIds.length > 0) {
          const { data: recentMessages, error: recentMessagesError } = await supabase
            .from('messages')
            .select('chat_id,sender_id,created_at')
            .in('chat_id', chatIds)
            .order('created_at', { ascending: false })
            .limit(800)
          if (recentMessagesError) {
            if (!isMissingTableError(recentMessagesError)) throw recentMessagesError
          } else {
            ;((recentMessages ?? []) as GenericRow[]).forEach((row) => {
              const chatId = textOf(row, ['chat_id'], '')
              const senderId = normalizeId(textOf(row, ['sender_id'], ''))
              if (!chatId || !senderId || senderId === cleanCurrentUserId || otherByChat.has(chatId)) return
              otherByChat.set(chatId, senderId)
            })
          }
        }

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

            if (readAt || status === 'read' || status === 'seen') {
              return
            }

            if (lastReadAtMillis > 0 && createdAtMillis > 0 && createdAtMillis <= lastReadAtMillis) {
              return
            }

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
          const fallbackAvatar = textOf(
            row,
            ['other_user_image', 'other_user_avatar', 'other_user_photo', 'avatar_url', 'photo_url', 'profile_image'],
            '',
          )
          return {
            id,
            title: isGroup
              ? textOf(row, ['group_name', 'name'], 'Grupo')
              : profile?.name || fallbackName,
            subtitle: textOf(row, ['last_message'], 'Sin mensajes'),
            updatedAt: textOf(row, ['last_message_at', 'updated_at', 'created_at'], ''),
            kind: 'chat',
            avatarUrl: profile?.avatarUrl || fallbackAvatar,
            profileId: other ?? null,
            unreadCount: unreadByChat.get(id) ?? 0,
            isMuted: participant?.isMuted ?? false,
            isPinned: participant?.isPinned ?? false,
            folders: folderNamesByChat.get(id) ?? [],
            openPath: `/app/messages/${id}`,
          } satisfies ConversationItem
        })

        mapped.sort((a, b) => {
          if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1
          return Number(new Date(b.updatedAt)) - Number(new Date(a.updatedAt))
        })
        setItems(mapped)
        writeCachedValue(inboxCacheKey, mapped)
      } else if (activeTab === 'groups') {
        setFolders([])
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
        setFolders([])
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
        setFolders([])
        const nextItems = await loadCollection('voice_rooms', ['name', 'title', 'readable_id'], ['topic'], 'voice')
        setItems(nextItems)
        writeCachedValue(inboxCacheKey, nextItems)
      }
    } catch (caught) {
      if (!options?.silent) {
        setError(friendlyError(caught, `No se pudo cargar ${title.toLowerCase()}`))
      }
    } finally {
      setLoading(false)
      setSyncing(false)
    }
  }, [activeTab, inboxCacheKey, localReadByChat, title, user])

  useEffect(() => {
    const cached = readCachedValue<ConversationItem[]>(inboxCacheKey, 1000 * 60 * 60 * 8)
    if (cached && cached.length > 0) {
      setItems(cached)
      setLoading(false)
      void loadCurrentTab({ silent: true })
      return
    }
    void loadCurrentTab({ silent: false })
  }, [inboxCacheKey, loadCurrentTab])

  async function updateChatPreferences(chatId: string, nextMuted: boolean, nextPinned: boolean) {
    if (!supabase || !user) return
    try {
      const { error: rpcError } = await supabase.rpc('secure_set_chat_preferences', {
        p_chat_id: chatId,
        p_is_muted: nextMuted,
        p_is_pinned: nextPinned,
      })
      if (rpcError && !isMissingTableError(rpcError)) {
        const nowIso = new Date().toISOString()
        const { error: fallbackError } = await supabase.from('chat_participants').upsert(
          {
            chat_id: chatId,
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
      await loadCurrentTab()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar mute/pin'))
    }
  }

  async function createFolder() {
    if (!supabase || !user || creatingFolder) return
    const name = folderNameDraft.trim()
    if (!name) return
    setCreatingFolder(true)
    setError(null)
    try {
      const { error: createError } = await supabase.from('chat_folders').insert({
        user_id: user.id,
        name,
      })
      if (createError) throw createError
      setFolderNameDraft('')
      await loadCurrentTab()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo crear carpeta'))
    } finally {
      setCreatingFolder(false)
    }
  }

  async function assignChatToFolder(chatId: string) {
    if (!supabase) return
    const folderId = folderSelectionByChat[chatId]
    if (!folderId) return
    try {
      const { error: assignError } = await supabase.from('chat_folder_chats').upsert(
        {
          folder_id: folderId,
          chat_id: chatId,
          sort_order: 0,
        },
        { onConflict: 'folder_id,chat_id' },
      )
      if (assignError) throw assignError
      await loadCurrentTab()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo asignar chat a carpeta'))
    }
  }

  async function markChatAsReadBestEffort(chatId: string) {
    if (!supabase || !user || !chatId) return
    const client = supabase
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
    setSyncMessage(null)

    if (!isBrowserOnline()) {
      enqueueDeferredMutation('chat_mark_read', { chatId: cleanChatId })
      setSyncMessage('Sin conexión: la lectura del chat quedó en cola de sincronización.')
      return
    }

    try {
      await runWithRetry(
        async () => {
          await client.rpc('secure_mark_messages_delivered', { p_chat_id: cleanChatId })
          await client.rpc('secure_mark_messages_read', { p_chat_id: cleanChatId })
          await client.from('chat_participants').upsert(
            {
              chat_id: cleanChatId,
              user_id: user.id,
              joined_at: nowIso,
              last_read_at: nowIso,
            },
            { onConflict: 'chat_id,user_id' },
          )
        },
        { maxRetries: 2, shouldRetry: isLikelyTransientError },
      )
    } catch (caught) {
      if (isLikelyTransientError(caught)) {
        enqueueDeferredMutation('chat_mark_read', { chatId: cleanChatId })
        setSyncMessage('Sincronización diferida: la lectura del chat se completará automáticamente.')
      }
    }
  }

  async function markAllChatsAsRead() {
    const client = supabase
    if (!client || !user || markingAllRead || activeTab !== 'chats') return
    const targetChatIds = items.filter((entry) => entry.unreadCount > 0).map((entry) => entry.id)
    if (targetChatIds.length === 0) return
    setMarkingAllRead(true)
    setError(null)
    setSyncMessage(null)
    try {
      const nowIso = new Date().toISOString()
      setLocalReadByChat((current) => {
        const next = { ...current }
        targetChatIds.forEach((chatId) => {
          next[chatId] = nowIso
        })
        writeLocalReadByChat(next)
        return next
      })
      setItems((current) => current.map((entry) => ({ ...entry, unreadCount: 0 })))

      if (!isBrowserOnline()) {
        targetChatIds.forEach((chatId) => {
          enqueueDeferredMutation('chat_mark_read', { chatId })
        })
        setSyncMessage('Sin conexión: el marcado global quedó en cola de sincronización.')
        return
      }

      await Promise.all(
        targetChatIds.map((chatId) =>
          runWithRetry(
            async () => {
              await client.rpc('secure_mark_messages_delivered', { p_chat_id: chatId })
              await client.rpc('secure_mark_messages_read', { p_chat_id: chatId })
              await client.from('chat_participants').upsert(
                {
                  chat_id: chatId,
                  user_id: user.id,
                  joined_at: nowIso,
                  last_read_at: nowIso,
                },
                { onConflict: 'chat_id,user_id' },
              )
            },
            { maxRetries: 2, shouldRetry: isLikelyTransientError },
          ),
        ),
      )
      await loadCurrentTab()
    } catch (caught) {
      if (isLikelyTransientError(caught)) {
        targetChatIds.forEach((chatId) => {
          enqueueDeferredMutation('chat_mark_read', { chatId })
        })
        setSyncMessage('Sincronización diferida: el marcado global se completará automáticamente.')
      } else {
        setError(friendlyError(caught, 'No se pudo marcar todo como leído'))
      }
    } finally {
      setMarkingAllRead(false)
    }
  }

  async function openChatWithReadSync(chatId: string) {
    if (!chatId || openingChatId) return
    setOpeningChatId(chatId)
    try {
      await markChatAsReadBestEffort(chatId)
      setSelectedConversation({ id: chatId, kind: 'chat' })
    } finally {
      setOpeningChatId(null)
    }
  }

  async function openConversation(item: ConversationItem) {
    if (item.kind === 'voice') {
      if (item.openPath) navigate(item.openPath)
      return
    }
    if (item.kind === 'chat') {
      await openChatWithReadSync(item.id)
      return
    }
    if (item.kind === 'group' || item.kind === 'community') {
      setSelectedConversation({ id: item.id, kind: item.kind })
      return
    }
    if (item.openPath) {
      navigate(item.openPath)
    }
  }

  useEffect(() => {
    const client = supabase
    if (!client || !user) return
    if (activeTab !== 'chats') return
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRefresh = () => {
      if (refreshTimer) {
        clearTimeout(refreshTimer)
      }
      refreshTimer = setTimeout(() => {
        void loadCurrentTab({ silent: true })
      }, 250)
    }
    const channel = client
      .channel('conectate-web-live-messages')
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'messages' },
        scheduleRefresh,
      )
      .on(
        'postgres_changes',
        { event: '*', schema: 'public', table: 'chat_participants', filter: `user_id=eq.${user.id}` },
        scheduleRefresh,
      )
      .subscribe()

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void client.removeChannel(channel)
    }
  }, [activeTab, loadCurrentTab, user])

  if (!supabase) {
    return (
      <StatusView
        title="Supabase no configurado"
        description="Debes configurar el .env para usar mensajes."
      />
    )
  }

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Mensajes</h2>
        <p>Chats, grupos, comunidad y voz en la versión web.</p>
      </header>
      {syncing ? <small>Sincronizando mensajes...</small> : null}

      {activeTab === 'chats' ? (
        <input
          className="text-input"
          placeholder="Buscar conversaciones"
          value={searchQuery}
          onChange={(event) => setSearchQuery(event.target.value)}
        />
      ) : null}

      {activeTab === 'chats' ? (
        <div className="row-actions">
          <button
            className={showUnreadOnly ? 'chip-btn active' : 'chip-btn'}
            type="button"
            onClick={() => setShowUnreadOnly((current) => !current)}
          >
            Solo no leídos ({showUnreadOnly ? filteredUnreadChats : totalUnreadChats})
          </button>
          <button className="ghost-btn" type="button" disabled={markingAllRead} onClick={() => void markAllChatsAsRead()}>
            {markingAllRead ? 'Marcando...' : 'Marcar todo como leído'}
          </button>
          <small>{filteredChatsCount} chats visibles</small>
        </div>
      ) : null}

      {syncMessage ? (
        <article className="surface-card">
          <small>{syncMessage}</small>
        </article>
      ) : null}

      <div className="chip-row">
        {tabOptions.map((tab) => (
          <button
            key={tab.key}
            className={tab.key === activeTab ? 'chip-btn active' : 'chip-btn'}
            onClick={() => setActiveTab(tab.key)}
            type="button"
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'chats' ? (
        <article className="surface-card section-stack">
          <h3>Carpetas</h3>
          <div className="composer">
            <input
              className="text-input"
              placeholder="Nombre de carpeta"
              value={folderNameDraft}
              onChange={(event) => setFolderNameDraft(event.target.value)}
            />
            <button className="ghost-btn" type="button" disabled={creatingFolder} onClick={() => void createFolder()}>
              {creatingFolder ? 'Creando...' : 'Crear'}
            </button>
          </div>
          {folders.length > 0 ? (
            <div className="row-actions">
              {folders.map((folder) => (
                <span key={folder.id} className="folder-tag">
                  {folder.name}
                </span>
              ))}
            </div>
          ) : (
            <small>No tienes carpetas creadas.</small>
          )}
        </article>
      ) : null}

      {supportsSplitLayout ? (
        <div className="messages-split-layout">
          <div className="messages-list-pane">
            {loading ? (
              <StatusView title={`Cargando ${title.toLowerCase()}`} description="Espera un momento..." />
            ) : error ? (
              <StatusView title="No se pudo cargar mensajes" description={error} />
            ) : filteredItems.length === 0 ? (
              <StatusView
                title={`${title} vacío`}
                description={normalizedSearch ? 'No hay resultados para tu búsqueda.' : 'No hay elementos todavía.'}
              />
            ) : (
              <div className="list-stack">
                {filteredItems.map((item) => {
                  const isSelected =
                    selectedConversation?.id === item.id &&
                    selectedConversation?.kind === item.kind
                  return (
                    <div
                      key={item.id}
                      className={isSelected ? 'surface-card conversation-card selected' : 'surface-card conversation-card'}
                    >
                      <div
                        className={item.openPath ? 'conversation-main conversation-main-clickable' : 'conversation-main'}
                        role={item.openPath ? 'button' : undefined}
                        tabIndex={item.openPath ? 0 : undefined}
                        onClick={() => void openConversation(item)}
                        onKeyDown={(event) => {
                          if (!item.openPath) return
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault()
                            void openConversation(item)
                          }
                        }}
                      >
                        {item.avatarUrl ? (
                          <img src={item.avatarUrl} alt={item.title} className="conversation-avatar" />
                        ) : (
                          <span className="conversation-avatar avatar-fallback">{initialsOf(item.title)}</span>
                        )}
                        <div className="conversation-copy">
                          <div className="row-between">
                            {item.profileId && activeTab === 'chats' ? (
                              <Link
                                className="inline-link"
                                to={profilePathFor(item.profileId, user?.id)}
                                onClick={(event) => event.stopPropagation()}
                              >
                                {item.title}
                              </Link>
                            ) : (
                              <h3>{item.title}</h3>
                            )}
                            <small>{formatDateTime(item.updatedAt)}</small>
                          </div>
                          <p>{item.subtitle}</p>
                          <div className="row-actions">
                            <span className="folder-tag">{labelByKind(item.kind)}</span>
                            {item.folders.map((folder) => (
                              <span key={`${item.id}-${folder}`} className="folder-tag">
                                {folder}
                              </span>
                            ))}
                          </div>
                        </div>
                      </div>
                      <div className="row-actions conversation-actions">
                        {item.isPinned ? <span className="folder-tag">Pineado</span> : null}
                        {item.isMuted ? <span className="folder-tag">Silenciado</span> : null}
                        {item.unreadCount > 0 ? <span className="unread-pill">{item.unreadCount}</span> : null}
                        {activeTab === 'chats' ? (
                          <>
                            <button
                              className="chip-btn"
                              type="button"
                              onClick={() => void updateChatPreferences(item.id, item.isMuted, !item.isPinned)}
                            >
                              {item.isPinned ? 'Despinear' : 'Pinear'}
                            </button>
                            <button
                              className="chip-btn"
                              type="button"
                              onClick={() => void updateChatPreferences(item.id, !item.isMuted, item.isPinned)}
                            >
                              {item.isMuted ? 'Activar' : 'Silenciar'}
                            </button>
                            {folders.length > 0 ? (
                              <>
                                <select
                                  className="text-input tiny-input"
                                  value={folderSelectionByChat[item.id] ?? ''}
                                  onChange={(event) =>
                                    setFolderSelectionByChat((current) => ({
                                      ...current,
                                      [item.id]: event.target.value,
                                    }))
                                  }
                                >
                                  <option value="">Carpeta</option>
                                  {folders.map((folder) => (
                                    <option key={folder.id} value={folder.id}>
                                      {folder.name}
                                    </option>
                                  ))}
                                </select>
                                <button className="chip-btn" type="button" onClick={() => void assignChatToFolder(item.id)}>
                                  Asignar
                                </button>
                              </>
                            ) : null}
                          </>
                        ) : null}
                        {item.openPath ? (
                          <button
                            className="ghost-btn"
                            type="button"
                            disabled={openingChatId === item.id}
                            onClick={() => void openConversation(item)}
                          >
                            {openingChatId === item.id ? 'Abriendo...' : isSelected ? 'Abierto' : 'Abrir'}
                          </button>
                        ) : null}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
          <div className="messages-detail-pane">
            {loading ? (
              <StatusView title="Cargando conversación" description="Espera un momento..." />
            ) : error ? (
              <StatusView title="No se pudo cargar conversación" description={error} />
            ) : !selectedConversationItem ? (
              <StatusView title="Selecciona una conversación" description="Elige un chat, grupo o comunidad para abrirlo aquí." />
            ) : selectedConversationItem.kind === 'chat' ? (
              <ChatDetailPage chatIdOverride={selectedConversationItem.id} embedded />
            ) : selectedConversationItem.kind === 'group' ? (
              <RoomChatPage scope="group" roomId={selectedConversationItem.id} embedded />
            ) : (
              <RoomChatPage scope="community" roomId={selectedConversationItem.id} embedded />
            )}
          </div>
        </div>
      ) : loading ? (
        <StatusView title={`Cargando ${title.toLowerCase()}`} description="Espera un momento..." />
      ) : error ? (
        <StatusView title="No se pudo cargar mensajes" description={error} />
      ) : filteredItems.length === 0 ? (
        <StatusView
          title={`${title} vacío`}
          description={normalizedSearch ? 'No hay resultados para tu búsqueda.' : 'No hay elementos todavía.'}
        />
      ) : (
        <div className="list-stack">
          {filteredItems.map((item) => (
            <div key={item.id} className="surface-card conversation-card">
              <div
                className={item.openPath ? 'conversation-main conversation-main-clickable' : 'conversation-main'}
                role={item.openPath ? 'button' : undefined}
                tabIndex={item.openPath ? 0 : undefined}
                onClick={() => void openConversation(item)}
                onKeyDown={(event) => {
                  if (!item.openPath) return
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    void openConversation(item)
                  }
                }}
              >
                {item.avatarUrl ? (
                  <img src={item.avatarUrl} alt={item.title} className="conversation-avatar" />
                ) : (
                  <span className="conversation-avatar avatar-fallback">{initialsOf(item.title)}</span>
                )}
                <div className="conversation-copy">
                  <div className="row-between">
                    <h3>{item.title}</h3>
                    <small>{formatDateTime(item.updatedAt)}</small>
                  </div>
                  <p>{item.subtitle}</p>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
