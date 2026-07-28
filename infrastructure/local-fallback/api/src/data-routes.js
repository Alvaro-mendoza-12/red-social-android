const crypto = require('crypto')
const { mergeProfileRows } = require('./profile-merge')
const { callAiService } = require('./ai-routes')
const {
  ensureNotificationDigestTables,
  registerNotificationDigestRoutes,
  shouldDeferNotificationPush,
} = require('./notification-digest-routes')

function quoteIdentifier(value) {
  return `"${String(value).replace(/"/g, '""')}"`
}

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function validUuid(value) {
  const text = normalizeId(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function parseCell(value) {
  if (value == null) return ''
  if (value instanceof Date) return value.toISOString()
  if (typeof value !== 'string') return value

  const trimmed = value.trim()
  if (!trimmed) return ''
  if (trimmed === 'true') return true
  if (trimmed === 'false') return false
  if (trimmed === 'null') return null

  if (
    (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
    (trimmed.startsWith('[') && trimmed.endsWith(']'))
  ) {
    try {
      return JSON.parse(trimmed)
    } catch {
      return trimmed
    }
  }

  return trimmed
}

function normalizeImportedRow(row) {
  return Object.fromEntries(
    Object.entries(row || {})
      .filter(([key]) => key !== '__source_file' && key !== '__imported_at')
      .map(([key, value]) => [key, parseCell(value)]),
  )
}

function textOf(row, keys, fallback = '') {
  for (const key of keys) {
    const value = row?.[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
    if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  }
  return fallback
}

function cleanText(value, maxLength = 160) {
  const text = String(value || '').trim()
  return text ? text.slice(0, maxLength) : ''
}

function boolOf(row, keys, fallback = false) {
  for (const key of keys) {
    const value = row?.[key]
    if (typeof value === 'boolean') return value
    if (typeof value === 'string') {
      const lower = value.trim().toLowerCase()
      if (lower === 'true') return true
      if (lower === 'false') return false
    }
    if (typeof value === 'number') return value === 1
  }
  return fallback
}

function numberOf(row, keys, fallback = 0) {
  for (const key of keys) {
    const value = row?.[key]
    if (typeof value === 'number' && Number.isFinite(value)) return value
    if (typeof value === 'string') {
      const parsed = Number(value)
      if (Number.isFinite(parsed)) return parsed
    }
  }
  return fallback
}

function asArray(value) {
  if (Array.isArray(value)) return value.map((entry) => String(entry).trim()).filter(Boolean)
  if (typeof value !== 'string') return []

  const trimmed = value.trim()
  if (!trimmed) return []
  if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
    try {
      const parsed = JSON.parse(trimmed)
      return Array.isArray(parsed) ? parsed.map((entry) => String(entry).trim()).filter(Boolean) : []
    } catch {
      return []
    }
  }
  if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
    return trimmed
      .slice(1, -1)
      .split(',')
      .map((entry) => entry.replace(/^"|"$/g, '').trim())
      .filter(Boolean)
  }
  return []
}

function arrayOf(row, keys) {
  for (const key of keys) {
    const values = asArray(row?.[key])
    if (values.length > 0) return values
  }
  return []
}

function toMillis(value) {
  const text = String(value || '').trim()
  if (!text) return 0
  const parsed = Date.parse(text)
  return Number.isNaN(parsed) ? 0 : parsed
}

const PRESENCE_ONLINE_TTL_MS = Math.max(30_000, Number(process.env.PRESENCE_ONLINE_TTL_MS) || 90_000)
const TTL_CACHE_MAX_ENTRIES = Math.max(100, Number(process.env.TTL_CACHE_MAX_ENTRIES) || 600)
const ttlCache = new Map()

function cacheGet(key) {
  const entry = ttlCache.get(key)
  if (!entry) return null
  if (entry.expiresAt <= Date.now()) {
    ttlCache.delete(key)
    return null
  }
  ttlCache.delete(key)
  ttlCache.set(key, entry)
  return entry.value
}

function cacheSet(key, value, ttlMs) {
  if (ttlMs <= 0) return value
  while (ttlCache.size >= TTL_CACHE_MAX_ENTRIES) {
    const oldestKey = ttlCache.keys().next().value
    if (!oldestKey) break
    ttlCache.delete(oldestKey)
  }
  ttlCache.set(key, { value, expiresAt: Date.now() + ttlMs })
  return value
}

async function cacheGetOrSet(key, ttlMs, loader) {
  const cached = cacheGet(key)
  if (cached) return cached.map((row) => ({ ...row }))
  const rows = await loader()
  cacheSet(key, rows.map((row) => ({ ...row })), ttlMs)
  return rows
}

function cacheDeletePrefix(prefix) {
  for (const key of Array.from(ttlCache.keys())) {
    if (key.startsWith(prefix)) ttlCache.delete(key)
  }
}

function invalidateImportedDataCache() {
  cacheDeletePrefix('importedRows:')
  cacheDeletePrefix('fallbackUserProfileRows:')
}

function clearImportedRowCaches(...tableNames) {
  for (const tableName of tableNames) {
    cacheDeletePrefix(`importedRows:${tableName}:`)
  }
}

function clearProfileCaches() {
  cacheDeletePrefix('fallbackUserProfileRows:')
  cacheDeletePrefix('allPublicProfiles:')
}

function clearVoiceCaches() {
  cacheDeletePrefix('localVoiceRooms:')
  cacheDeletePrefix('localVoiceParticipants:')
}

function setPrivateShortCache(res, seconds = 10) {
  res.setHeader('Cache-Control', `private, max-age=${seconds}, stale-while-revalidate=${seconds * 2}`)
}

function presenceLastSeen(row, fallback = '') {
  return textOf(row, ['last_seen', 'lastSeen', 'last_seen_at', 'lastSeenAt'], fallback)
}

function presenceFields(row) {
  const lastSeen = presenceLastSeen(row, textOf(row, ['updated_at', 'created_at'], ''))
  const lastSeenMs = toMillis(lastSeen)
  const isFresh = lastSeenMs > 0 && Date.now() - lastSeenMs <= PRESENCE_ONLINE_TTL_MS
  return {
    is_online: boolOf(row, ['is_online', 'isOnline'], false) && isFresh,
    last_seen: lastSeen || null,
  }
}

function sortByDateDesc(rows, keys) {
  return [...rows].sort((a, b) => {
    const left = keys.reduce((max, key) => Math.max(max, toMillis(a[key])), 0)
    const right = keys.reduce((max, key) => Math.max(max, toMillis(b[key])), 0)
    return right - left
  })
}

function previewFromMessage(row) {
  const content = textOf(row, ['content'], '')
  if (content) return content
  const contentType = textOf(row, ['content_type'], '').toLowerCase()
  if (contentType === 'image') return 'Imagen'
  if (contentType === 'video') return 'Video'
  if (contentType === 'audio') return 'Audio'
  if (contentType === 'sticker') return 'Sticker'
  if (contentType === 'poll') return 'Encuesta'
  if (contentType.startsWith('view_once')) return 'View once'
  return 'Sin actividad reciente'
}

function cleanSet(values) {
  return new Set(values.map(normalizeId).filter(Boolean))
}

function rowMatchesAny(row, keys, values) {
  const set = values instanceof Set ? values : cleanSet(values)
  return keys.some((key) => {
    const value = normalizeId(row?.[key])
    return value && set.has(value)
  })
}

function rowParticipantsContain(row, values) {
  const set = values instanceof Set ? values : cleanSet(values)
  const participants = arrayOf(row, ['participants', 'participant_ids', 'user_ids', 'members'])
  return participants.some((participant) => set.has(normalizeId(participant)))
}

function recommendationPreferenceScore({ behavioralTagOverlap = 0, profileTermOverlap = 0 } = {}) {
  const behavioralMatches = Math.min(3, Math.max(0, Number(behavioralTagOverlap) || 0))
  const explicitMatches = Math.min(3, Math.max(0, Number(profileTermOverlap) || 0))
  return behavioralMatches * 24 + explicitMatches * 90
}

function sortMessagesNewestFirst(rows) {
  return [...rows].sort((a, b) => {
    const left = toMillis(a.created_at) || toMillis(a.updated_at)
    const right = toMillis(b.created_at) || toMillis(b.updated_at)
    return right - left
  })
}

function messageVisibleTo(row, identity) {
  if (boolOf(row, ['deleted_for_all', 'deletedForAll'], false)) return false
  const deletedBy = cleanSet(arrayOf(row, ['deleted_by', 'deletedBy']))
  if (deletedBy.size === 0 || !identity?.ids) return true
  return !Array.from(identity.ids).some((id) => deletedBy.has(normalizeId(id)))
}

function normalizeMessageRow(row) {
  const normalized = normalizeImportedRow(row)
  normalized.deleted_by = arrayOf(normalized, ['deleted_by', 'deletedBy'])
  normalized.deleted_for_all = boolOf(normalized, ['deleted_for_all', 'deletedForAll'], false)
  normalized.is_edited = boolOf(normalized, ['is_edited', 'isEdited'], false)
  normalized.reactions = normalized.reactions && typeof normalized.reactions === 'object' && !Array.isArray(normalized.reactions)
    ? normalized.reactions
    : {}
  normalized.is_forwarded = boolOf(normalized, ['is_forwarded', 'isForwarded'], false)
  return normalized
}

function profileName(row, fallback = 'Usuario') {
  return textOf(row, ['display_name', 'displayName', 'full_name', 'username', 'name', 'email'], fallback)
}

function profileAvatar(row) {
  return textOf(row, ['avatar_url', 'photo_url', 'profile_image', 'picture', 'image'], '')
}

function profileId(row) {
  return normalizeId(textOf(row, ['supabase_user_id', 'supabaseUserId', 'id', 'user_id', 'profile_id'], ''))
}

function indexProfiles(rows) {
  const byId = new Map()
  for (const row of rows) {
    for (const key of ['id', 'user_id', 'profile_id', 'supabase_user_id', 'email', 'username', 'display_name']) {
      const id = normalizeId(row[key])
      if (id) byId.set(id, row)
    }
  }
  return byId
}

async function tableExists(pool, tableName) {
  const cacheKey = `tableExists:${tableName}`
  const cached = cacheGet(cacheKey)
  if (typeof cached === 'boolean') return cached
  const { rows } = await pool.query(
    `SELECT 1
     FROM information_schema.tables
     WHERE table_schema = 'supabase_import'
       AND table_name = $1
     LIMIT 1`,
    [tableName],
  )
  return cacheSet(cacheKey, Boolean(rows[0]), 5 * 60_000)
}

async function columnExists(pool, tableName, columnName) {
  const cacheKey = `columnExists:${tableName}:${columnName}`
  const cached = cacheGet(cacheKey)
  if (typeof cached === 'boolean') return cached
  const { rows } = await pool.query(
    `SELECT 1
     FROM information_schema.columns
     WHERE table_schema = 'supabase_import'
       AND table_name = $1
       AND column_name = $2
     LIMIT 1`,
    [tableName, columnName],
  )
  return cacheSet(cacheKey, Boolean(rows[0]), 5 * 60_000)
}

async function importedRows(pool, tableName, limit = 2000) {
  if (!(await tableExists(pool, tableName))) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 2000, 10_000))
  return cacheGetOrSet(
    `importedRows:${tableName}:${safeLimit}`,
    60_000,
    async () => {
      const { rows } = await pool.query(
        `SELECT to_jsonb(t) AS row
         FROM supabase_import.${quoteIdentifier(tableName)} AS t
         LIMIT $1`,
        [safeLimit],
      )
      return rows.map((entry) => normalizeImportedRow(entry.row))
    },
  )
}

async function localMessages(pool, chatIds) {
  const ids = Array.from(cleanSet(chatIds))
  if (ids.length === 0) return []
  const { rows } = await pool.query(
    `SELECT
        id::text,
        chat_id,
        sender_id,
        content,
        content_type,
        media_url,
        thumbnail_url,
        media_size,
        status,
        reply_to_id,
        poll_id,
        delivered_at,
        read_at,
        reactions,
        deleted_by,
        deleted_for_all,
        is_edited,
        is_forwarded,
        forwarded_from_message_id,
        forwarded_from_user_id,
        created_at,
        updated_at
     FROM fallback_messages
     WHERE lower(chat_id) = ANY($1::text[])
     ORDER BY created_at ASC`,
    [ids],
  )
  return rows.map(normalizeMessageRow)
}

function chatParticipantKey(participants) {
  return Array.from(cleanSet(participants)).sort().join(':')
}

async function localChats(pool, limit = 5000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 5000, 10_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        participants,
        participant_key,
        is_group,
        group_name,
        last_message,
        last_message_at,
        last_message_sender_id,
        last_message_id,
        created_at,
        updated_at
     FROM fallback_chats
     ORDER BY updated_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localChatParticipants(pool, chatIds = []) {
  const ids = Array.from(cleanSet(chatIds))
  const { rows } = await pool.query(
    `SELECT
        chat_id::text,
        user_id,
        joined_at,
        last_read_at,
        is_muted,
        is_pinned,
        is_archived,
        is_favorite,
        cleared_at
     FROM fallback_chat_participants
     WHERE ($1::text[] = '{}'::text[] OR lower(chat_id::text) = ANY($1::text[]))
     ORDER BY joined_at DESC
     LIMIT 10000`,
    [ids],
  )
  return rows.map(normalizeImportedRow)
}

async function localHiddenChats(pool, userId) {
  const cleanUserId = normalizeId(userId)
  if (!cleanUserId) return new Map()
  const { rows } = await pool.query(
    `SELECT chat_id::text, hidden_at
     FROM fallback_chat_hidden
     WHERE lower(user_id) = $1`,
    [cleanUserId],
  )
  return new Map(
    rows
      .map(normalizeImportedRow)
      .map((row) => [normalizeId(textOf(row, ['chat_id', 'chatId'], '')), textOf(row, ['hidden_at', 'hiddenAt'], '')])
      .filter(([chatId]) => chatId),
  )
}

async function ensureFallbackChatExists(pool, chatId, sourceRow = {}, participantRows = []) {
  const cleanChatId = normalizeId(chatId)
  const uuidChatId = validUuid(cleanChatId)
  if (!uuidChatId) return false

  const rowParticipants = arrayOf(sourceRow, ['participants']).map(normalizeId).filter(Boolean)
  const participantIds = participantRows
    .filter((row) => normalizeId(textOf(row, ['chat_id', 'chatId'], '')) === cleanChatId)
    .map((row) => normalizeId(textOf(row, ['user_id', 'profile_id', 'userId', 'profileId'], '')))
    .filter(Boolean)
  const participants = Array.from(cleanSet([...rowParticipants, ...participantIds]))
  // This helper materializes imported/migrated chats into fallback_chats so
  // per-user preferences can reference them. Do not claim participant_key here:
  // an existing local DM can already own the same key, and hide/archive/favorite
  // must never fail because of that uniqueness constraint.
  const participantKey = null
  const lastMessageAt = textOf(sourceRow, ['last_message_at', 'lastMessageAt', 'updated_at', 'updatedAt', 'created_at', 'createdAt'], '') || null

  await pool.query(
    `INSERT INTO fallback_chats(
       id,
       participants,
       participant_key,
       is_group,
       group_name,
       last_message,
       last_message_at,
       last_message_sender_id,
       last_message_id,
       created_at,
       updated_at
     )
     VALUES (
       $1::uuid,
       $2::text[],
       $3,
       $4,
       $5,
       $6,
       $7::timestamptz,
       $8,
       $9,
       COALESCE($10::timestamptz, NOW()),
       NOW()
     )
     ON CONFLICT (id) DO UPDATE SET
       participants = CASE
         WHEN cardinality(EXCLUDED.participants) > 0 THEN EXCLUDED.participants
         ELSE fallback_chats.participants
       END,
       participant_key = COALESCE(EXCLUDED.participant_key, fallback_chats.participant_key),
       is_group = EXCLUDED.is_group OR fallback_chats.is_group,
       group_name = COALESCE(NULLIF(EXCLUDED.group_name, ''), fallback_chats.group_name),
       last_message = COALESCE(NULLIF(EXCLUDED.last_message, ''), fallback_chats.last_message),
       last_message_at = GREATEST(
         COALESCE(fallback_chats.last_message_at, 'epoch'::timestamptz),
         COALESCE(EXCLUDED.last_message_at, 'epoch'::timestamptz)
       ),
       last_message_sender_id = COALESCE(NULLIF(EXCLUDED.last_message_sender_id, ''), fallback_chats.last_message_sender_id),
       last_message_id = COALESCE(NULLIF(EXCLUDED.last_message_id, ''), fallback_chats.last_message_id),
       updated_at = NOW()`,
    [
      uuidChatId,
      participants,
      participantKey,
      boolOf(sourceRow, ['is_group', 'isGroup'], false),
      textOf(sourceRow, ['group_name', 'groupName', 'name', 'title'], ''),
      textOf(sourceRow, ['last_message', 'lastMessage'], ''),
      lastMessageAt,
      textOf(sourceRow, ['last_message_sender_id', 'lastMessageSenderId'], ''),
      textOf(sourceRow, ['last_message_id', 'lastMessageId'], ''),
      textOf(sourceRow, ['created_at', 'createdAt'], '') || null,
    ],
  )
  return true
}

async function localNicknames(pool, userId) {
  const cleanUserId = normalizeId(userId)
  if (!cleanUserId) return new Map()
  const { rows } = await pool.query(
    `SELECT target_user_id, nickname
     FROM fallback_nicknames
     WHERE lower(user_id) = $1`,
    [cleanUserId],
  )
  return new Map(
    rows
      .map(normalizeImportedRow)
      .map((row) => [normalizeId(textOf(row, ['target_user_id', 'targetUserId'], '')), textOf(row, ['nickname'], '')])
      .filter(([target, nickname]) => target && nickname),
  )
}

async function privateChatAccessContext(pool, chatId, identity) {
  const cleanChatId = normalizeId(chatId)
  const [fallbackChatRows, importedChatRows, importedParticipants, fallbackParticipants] = await Promise.all([
    localChats(pool, 5000),
    importedRows(pool, 'chats', 5000),
    importedRows(pool, 'chat_participants', 10_000),
    localChatParticipants(pool, [cleanChatId]),
  ])
  const chat = [...fallbackChatRows, ...importedChatRows]
    .find((row) => normalizeId(textOf(row, ['id'], '')) === cleanChatId)
  const participantRows = [...importedParticipants, ...fallbackParticipants]
    .filter((row) => normalizeId(textOf(row, ['chat_id'], '')) === cleanChatId)
  const hasAccess =
    (chat && rowParticipantsContain(chat, identity.ids)) ||
    participantRows.some((row) => rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids))

  return { chat, participantRows, hasAccess }
}

function recipientIdsForChat(chat, participantRows, identity = null) {
  return Array.from(new Set([
    ...participantRows
      .map((row) => normalizeId(textOf(row, ['user_id', 'profile_id'], ''))),
    ...arrayOf(chat || {}, ['participants']).map(normalizeId),
    ...(identity?.values || []),
  ].filter(Boolean)))
}

async function blockRelationshipForPair(pool, identity, targetUserId) {
  const cleanTargetId = normalizeId(targetUserId)
  if (!cleanTargetId || identity.ids.has(cleanTargetId)) {
    return { blockedByMe: false, blockedMe: false, isBlocked: false }
  }
  const { rows } = await pool.query(
    `SELECT lower(blocker_id) AS blocker_id, lower(blocked_id) AS blocked_id
     FROM fallback_user_blocks
     WHERE (
        lower(blocker_id) = ANY($1::text[]) AND lower(blocked_id) = $2
     ) OR (
        lower(blocker_id) = $2 AND lower(blocked_id) = ANY($1::text[])
     )`,
    [identity.values, cleanTargetId],
  )
  const blockedByMe = rows.some((row) => identity.ids.has(normalizeId(row.blocker_id)) && normalizeId(row.blocked_id) === cleanTargetId)
  const blockedMe = rows.some((row) => normalizeId(row.blocker_id) === cleanTargetId && identity.ids.has(normalizeId(row.blocked_id)))
  return { blockedByMe, blockedMe, isBlocked: blockedByMe || blockedMe }
}

async function blockRelationshipForChat(pool, chat, participantRows, identity) {
  if (boolOf(chat || {}, ['is_group', 'isGroup'], false)) {
    return { blockedByMe: false, blockedMe: false, isBlocked: false }
  }
  const participants = recipientIdsForChat(chat, participantRows, null)
  const targetIds = participants.filter((id) => id && !identity.ids.has(id))
  for (const targetId of targetIds) {
    const relationship = await blockRelationshipForPair(pool, identity, targetId)
    if (relationship.isBlocked) return relationship
  }
  return { blockedByMe: false, blockedMe: false, isBlocked: false }
}

async function getFallbackMessage(pool, chatId, messageId) {
  const { rows } = await pool.query(
    `SELECT
        id::text,
        chat_id,
        sender_id,
        content,
        content_type,
        media_url,
        thumbnail_url,
        media_size,
        status,
        reply_to_id,
        poll_id,
        delivered_at,
        read_at,
        reactions,
        deleted_by,
        deleted_for_all,
        is_edited,
        is_forwarded,
        forwarded_from_message_id,
        forwarded_from_user_id,
        created_at,
        updated_at
     FROM fallback_messages
     WHERE lower(chat_id) = $1 AND lower(id::text) = $2
     LIMIT 1`,
    [normalizeId(chatId), normalizeId(messageId)],
  )
  return rows[0] ? normalizeMessageRow(rows[0]) : null
}

async function updateFallbackChatLastMessage(pool, chatId) {
  const cleanChatId = normalizeId(chatId)
  const { rows } = await pool.query(
    `SELECT
        id::text,
        chat_id,
        sender_id,
        content,
        content_type,
        media_url,
        thumbnail_url,
        media_size,
        status,
        reply_to_id,
        poll_id,
        delivered_at,
        read_at,
        reactions,
        deleted_by,
        deleted_for_all,
        is_edited,
        is_forwarded,
        forwarded_from_message_id,
        forwarded_from_user_id,
        created_at,
        updated_at
     FROM fallback_messages
     WHERE lower(chat_id) = $1 AND deleted_for_all = FALSE
     ORDER BY created_at DESC
     LIMIT 1`,
    [cleanChatId],
  )
  const latest = rows[0] ? normalizeMessageRow(rows[0]) : null
  await pool.query(
    `UPDATE fallback_chats
     SET last_message = $2,
         last_message_at = $3::timestamptz,
         last_message_sender_id = $4,
         last_message_id = $5,
         updated_at = NOW()
     WHERE lower(id::text) = $1`,
    [
      cleanChatId,
      latest ? previewFromMessage(latest) : null,
      latest ? textOf(latest, ['created_at'], '') || null : null,
      latest ? textOf(latest, ['sender_id'], '') : null,
      latest ? textOf(latest, ['id'], '') : null,
    ],
  )
  return latest
}

function toggleReactionMap(current, emoji, userId) {
  const cleanEmoji = String(emoji || '').trim().slice(0, 16)
  const cleanUserId = normalizeId(userId)
  if (!cleanEmoji || !cleanUserId) return current || {}

  const next = {}
  let removedSameReaction = false
  for (const [key, rawUsers] of Object.entries(current || {})) {
    const users = asArray(rawUsers).map(normalizeId).filter(Boolean)
    const withoutUser = users.filter((id) => id !== cleanUserId)
    if (key === cleanEmoji && withoutUser.length !== users.length) removedSameReaction = true
    if (withoutUser.length > 0) next[key] = Array.from(new Set(withoutUser))
  }
  if (!removedSameReaction) {
    next[cleanEmoji] = Array.from(new Set([...(next[cleanEmoji] || []), cleanUserId]))
  }
  return next
}

async function localCollectionMessages(pool, kind, roomId) {
  const cleanKind = String(kind || '').trim().toLowerCase()
  const cleanRoomId = normalizeId(roomId)
  if (!cleanRoomId || !['group', 'community'].includes(cleanKind)) return []
  const { rows } = await pool.query(
    `SELECT
        id::text,
        room_id,
        CASE WHEN room_kind = 'group' THEN room_id ELSE NULL END AS group_id,
        CASE WHEN room_kind = 'community' THEN room_id ELSE NULL END AS community_id,
        channel_id,
        sender_id,
        content,
        content_type,
        media_url,
        media_size,
        status,
        delivered_at,
        read_at,
        reactions,
        reply_to_id,
        poll_id,
        is_forwarded,
        forwarded_from_message_id,
        forwarded_from_user_id,
        created_at,
        updated_at,
        deleted_by,
        deleted_for_all,
        is_edited
     FROM fallback_room_messages
     WHERE room_kind = $1 AND lower(room_id) = $2
     ORDER BY created_at ASC`,
    [cleanKind, cleanRoomId],
  )
  return rows.map(normalizeImportedRow)
}

async function localCollectionRooms(pool, kind, limit = 2000) {
  const cleanKind = String(kind || '').trim().toLowerCase()
  if (!['group', 'community'].includes(cleanKind)) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 2000, 10_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        room_kind,
        name,
        image,
        description,
        category,
        is_public,
        creator_id,
        is_deleted,
        only_admins_can_talk,
        daily_message_limit,
        created_at,
        updated_at
     FROM fallback_collection_rooms
     WHERE room_kind = $1 AND is_deleted = FALSE
     ORDER BY updated_at DESC, created_at DESC
     LIMIT $2`,
    [cleanKind, safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localDeletedCollectionRoomIds(pool, kind) {
  const cleanKind = String(kind || '').trim().toLowerCase()
  if (!['group', 'community'].includes(cleanKind)) return new Set()
  const { rows } = await pool.query(
    `SELECT lower(id::text) AS id
     FROM fallback_collection_rooms
     WHERE room_kind = $1 AND is_deleted = TRUE`,
    [cleanKind],
  )
  return new Set(rows.map((row) => normalizeId(row.id)).filter(Boolean))
}

async function localCollectionMembers(pool, kind, limit = 10_000) {
  const cleanKind = String(kind || '').trim().toLowerCase()
  if (!['group', 'community'].includes(cleanKind)) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT
        room_id AS group_id,
        room_id AS community_id,
        room_id,
        user_id,
        role,
        joined_at,
        is_deleted,
        rest_until

     FROM fallback_collection_members
     WHERE room_kind = $1
     ORDER BY joined_at ASC
     LIMIT $2`,
    [cleanKind, safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localPosts(pool, limit = 5000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 5000, 10_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        user_id,
        user_name,
        user_profile_image,
        content,
        media_url,
        media_urls,
        media_type,
        visibility,
        remix_allowed,
        remix_audience,
        remix_revoked_at,
        remix_source_post_id,
        remix_root_post_id,
        remix_source_user_id,
        remix_source_user_name,
        remix_depth,
        created_at,
        updated_at,
        0::int AS like_count,
        0::int AS comment_count,
        TRUE AS local_only
     FROM fallback_posts
     ORDER BY created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localVideos(pool, limit = 5000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 5000, 10_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        user_id,
        user_name,
        user_profile_image,
        title,
        description,
        video_url,
        cover_url,
        thumbnail_url,
        duration_seconds,
        trim_start_seconds,
        trim_end_seconds,
        views,
        like_count,
        comment_count,
        share_count,
        is_public,
        tags,
        created_at,
        updated_at,
        TRUE AS local_only
     FROM fallback_videos
     ORDER BY created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localVideoLikes(pool, videoIds, limit = 10_000) {
  const ids = Array.from(cleanSet(videoIds))
  if (ids.length === 0) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT video_id, user_id, created_at
     FROM fallback_video_likes
     WHERE lower(video_id) = ANY($1::text[])
     ORDER BY created_at DESC
     LIMIT $2`,
    [ids.map((id) => id.toLowerCase()), safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localCollectionReadStates(pool, kind, userId) {
  const cleanKind = String(kind || '').trim().toLowerCase()
  const cleanUserId = normalizeId(userId)
  if (!cleanUserId || !['group', 'community'].includes(cleanKind)) return new Map()
  const { rows } = await pool.query(
    `SELECT
        room_id,
        last_read_at
     FROM fallback_collection_read_states
     WHERE room_kind = $1 AND lower(user_id) = $2`,
    [cleanKind, cleanUserId],
  )
  return new Map(
    rows
      .map(normalizeImportedRow)
      .map((row) => [normalizeId(textOf(row, ['room_id'], '')), textOf(row, ['last_read_at'], '')])
      .filter(([roomId]) => roomId),
  )
}

async function localVideoShares(pool, videoIds, limit = 10_000) {
  const ids = Array.from(cleanSet(videoIds))
  if (ids.length === 0) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        post_id AS video_id,
        user_id,
        content_type,
        created_at,
        TRUE AS local_only
     FROM fallback_shares
     WHERE content_type = 'video'
       AND lower(post_id) = ANY($1::text[])
     ORDER BY created_at DESC
     LIMIT $2`,
    [ids, safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localVideoMetrics(pool, videoIds) {
  const ids = Array.from(cleanSet(videoIds))
  if (ids.length === 0) return new Map()
  const { rows } = await pool.query(
    `SELECT lower(video_id) AS video_id,
            views_delta,
            share_delta
     FROM fallback_video_metrics
     WHERE lower(video_id) = ANY($1::text[])`,
    [ids],
  )
  return new Map(rows.map((row) => [
    row.video_id,
    {
      viewsDelta: Number(row.views_delta) || 0,
      shareDelta: Number(row.share_delta) || 0,
    },
  ]))
}

async function localVideoDeletionIds(pool, videoIds = null) {
  const ids = videoIds ? Array.from(cleanSet(videoIds)) : []
  const result = ids.length > 0
    ? await pool.query(
        `SELECT lower(video_id) AS video_id
         FROM fallback_video_deletions
         WHERE lower(video_id) = ANY($1::text[])`,
        [ids],
      )
    : await pool.query(`SELECT lower(video_id) AS video_id FROM fallback_video_deletions`)
  return new Set(result.rows.map((row) => row.video_id).filter(Boolean))
}

async function importedVideoComments(pool, limit = 10_000) {
  const [videoComments, genericComments, postComments] = await Promise.all([
    importedRows(pool, 'video_comments', limit),
    importedRows(pool, 'comments', limit),
    importedRows(pool, 'post_comments', limit),
  ])
  return [...videoComments, ...genericComments, ...postComments]
}

async function localVideoComments(pool, videoId, limit = 5000) {
  const cleanVideoId = normalizeId(videoId)
  if (!cleanVideoId) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 5000, 20_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        video_id,
        user_id,
        user_name,
        user_profile_image,
        content,
        content_type,
        media_url,
        parent_comment_id,
        created_at,
        updated_at
     FROM fallback_video_comments
     WHERE lower(video_id) = $1 AND is_deleted = FALSE
     ORDER BY created_at ASC
     LIMIT $2`,
    [cleanVideoId, safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localVideoCommentCounts(pool, videoIds) {
  const ids = Array.from(cleanSet(videoIds))
  if (ids.length === 0) return new Map()
  const { rows } = await pool.query(
    `SELECT lower(video_id) AS video_id, COUNT(*)::int AS total
     FROM fallback_video_comments
     WHERE lower(video_id) = ANY($1::text[]) AND is_deleted = FALSE
     GROUP BY lower(video_id)`,
    [ids],
  )
  return new Map(rows.map((row) => [row.video_id, Number(row.total) || 0]))
}

async function syncLocalVideoCommentCount(pool, videoId) {
  const cleanVideoId = normalizeId(videoId)
  if (!cleanVideoId) return 0
  const { rows } = await pool.query(
    `SELECT COUNT(*)::int AS total
     FROM fallback_video_comments
     WHERE lower(video_id) = $1 AND is_deleted = FALSE`,
    [cleanVideoId],
  )
  const importedTotal = importedVideoCommentRows(
    await importedVideoComments(pool, 10_000),
    [cleanVideoId],
  ).length
  const total = (Number(rows[0]?.total) || 0) + importedTotal
  await pool.query(
    `UPDATE fallback_videos
     SET comment_count = $2,
         updated_at = NOW()
     WHERE lower(id::text) = $1`,
    [cleanVideoId, total],
  )
  return total
}

function videoIdOf(row) {
  return normalizeId(textOf(row, [
    'video_id',
    'videoId',
    'video',
    'video_uuid',
    'videoUuid',
    'reel_id',
    'reelId',
    'media_id',
    'mediaId',
    'content_id',
    'contentId',
    'target_id',
    'targetId',
    'entity_id',
    'entityId',
    'resource_id',
    'resourceId',
    'object_id',
    'objectId',
    'post_id',
    'postId',
  ], ''))
}

function parentCommentIdOf(row) {
  const value = normalizeId(textOf(row, ['parent_comment_id', 'parent_id', 'parentId', 'reply_to_id', 'replyToId'], ''))
  if (!value || value === '0' || value === 'null' || value === 'undefined' || value === 'none') return null
  return value
}

function importedVideoCommentRows(rows, videoIds = null) {
  const filterIds = videoIds ? cleanSet(videoIds) : null
  return rows
    .filter((row) => !boolOf(row, ['is_deleted', 'deleted'], false))
    .filter((row) => {
      const videoId = videoIdOf(row)
      return videoId && (!filterIds || filterIds.has(videoId))
    })
}

function videoCommentCountsFromRows(rows) {
  const counts = new Map()
  for (const row of rows) {
    const videoId = videoIdOf(row)
    if (!videoId) continue
    counts.set(videoId, (counts.get(videoId) || 0) + 1)
  }
  return counts
}

function mergeCountMaps(...maps) {
  const merged = new Map()
  for (const map of maps) {
    for (const [key, value] of map.entries()) {
      merged.set(key, (merged.get(key) || 0) + (Number(value) || 0))
    }
  }
  return merged
}

function videoOwnerIds(row) {
  return cleanSet([
    textOf(row, ['user_id', 'userId', 'profile_id', 'author_id'], ''),
    textOf(row, ['owner_id', 'creator_id'], ''),
  ])
}

async function videoNotificationOwnerId(pool, videoId, identity) {
  const cleanVideoId = normalizeId(videoId)
  if (!cleanVideoId) return ''
  const [fallbackVideos, importedVideos] = await Promise.all([
    localVideos(pool, 5000),
    importedRows(pool, 'videos', 5000),
  ])
  const row = [...fallbackVideos, ...importedVideos]
    .find((entry) => normalizeId(textOf(entry, ['id'], '')) === cleanVideoId)
  if (!row) return ''
  return Array.from(videoOwnerIds(row))
    .find((ownerId) => ownerId && !identity.ids.has(ownerId)) || ''
}

function profileAliasSet(profile) {
  return cleanSet([
    textOf(profile, ['id'], ''),
    textOf(profile, ['user_id', 'userId'], ''),
    textOf(profile, ['profile_id', 'profileId'], ''),
    textOf(profile, ['supabase_user_id', 'supabaseUserId'], ''),
    textOf(profile, ['email'], ''),
    textOf(profile, ['username', 'handle'], ''),
  ])
}

function ownerMatchesRequestedProfile(row, requestedIds) {
  if (!requestedIds || requestedIds.size === 0) return true
  const owners = videoOwnerIds(row)
  return Array.from(owners).some((id) => requestedIds.has(id))
}

function mergeRowsById(rows) {
  const byId = new Map()
  for (const row of rows) {
    const id = normalizeId(textOf(row, ['id'], ''))
    if (!id) continue
    byId.set(id, { ...(byId.get(id) || {}), ...row })
  }
  return Array.from(byId.values())
}

function playbackUrlWithVersion(rawUrl, row) {
  const url = String(rawUrl || '').trim()
  if (!url || !url.includes('/storage/v1/object/public/')) return url
  const versionSource =
    textOf(row, ['created_at', 'createdAt', 'id'], '') ||
    Date.now().toString()
  const version = encodeURIComponent(String(versionSource).replace(/[^0-9a-zA-Z._:-]/g, ''))
  if (!version) return url
  const [base, hash = ''] = url.split('#')
  const separator = base.includes('?') ? '&' : '?'
  return `${base}${separator}v=${version}${hash ? `#${hash}` : ''}`
}

function enrichVideo(row, profilesById, likeCounts, likedVideoIds) {
  const id = textOf(row, ['id'], '')
  const userId = textOf(row, ['user_id', 'userId'], '')
  const profile = profilesById.get(normalizeId(userId)) || {}
  const cover = textOf(row, ['cover_url', 'thumbnail_url', 'coverUrl', 'thumbnailUrl'], '')
  const videoUrl = textOf(row, ['video_url', 'videoUrl', 'media_url', 'url'], '')
  return {
    ...row,
    id,
    user_id: userId,
    user_name: profileName(profile, textOf(row, ['user_name', 'userName'], 'Usuario')),
    user_profile_image:
      textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], '') ||
      textOf(row, ['user_profile_image', 'userProfileImage'], ''),
    avatar_media_type:
      textOf(profile, ['avatar_media_type', 'avatarMediaType'], '') ||
      textOf(row, ['avatar_media_type', 'avatarMediaType'], ''),
    avatar_video_muted: boolOf(
      profile,
      ['avatar_video_muted', 'avatarVideoMuted'],
      boolOf(row, ['avatar_video_muted', 'avatarVideoMuted'], true),
    ),
    title: textOf(row, ['title', 'caption'], 'Video'),
    description: textOf(row, ['description', 'content'], ''),
    video_url: playbackUrlWithVersion(videoUrl, row),
    cover_url: cover,
    thumbnail_url: cover,
    duration_seconds: numberOf(row, ['duration_seconds', 'duration'], 0),
    trim_start_seconds: numberOf(row, ['trim_start_seconds', 'trimStartSeconds'], 0),
    trim_end_seconds: numberOf(row, ['trim_end_seconds', 'trimEndSeconds'], 0),
    views: numberOf(row, ['views', 'view_count', 'viewCount'], 0),
    like_count: likeCounts.get(id) ?? numberOf(row, ['like_count', 'likeCount'], 0),
    comment_count: numberOf(row, ['comment_count', 'commentCount'], 0),
    share_count: numberOf(row, ['share_count', 'shareCount'], 0),
    is_public: boolOf(row, ['is_public', 'isPublic'], true),
    liked_by_me: likedVideoIds.has(id),
    created_at: textOf(row, ['created_at', 'updated_at'], ''),
    updated_at: textOf(row, ['updated_at', 'created_at'], ''),
  }
}

function mapVideoComment(row, profilesById, currentIdentity) {
  const id = textOf(row, ['id'], '')
  const userId = textOf(row, ['user_id', 'profile_id', 'author_id'], '')
  const profile = profilesById.get(normalizeId(userId)) || {}
  const parentCommentId = parentCommentIdOf(row)
  return {
    ...row,
    id,
    video_id: videoIdOf(row),
    user_id: userId,
    user_name: profileName(profile, textOf(row, ['user_name', 'display_name', 'author_name'], 'Usuario')),
    user_profile_image:
      profileAvatar(profile) || textOf(row, ['user_profile_image', 'user_avatar_url', 'avatar_url'], ''),
    content: textOf(row, ['content', 'comment', 'comment_text', 'body', 'message', 'text'], ''),
    content_type: textOf(row, ['content_type', 'contentType'], 'text') || 'text',
    media_url: textOf(row, ['media_url', 'audio_url', 'file_url'], ''),
    parent_comment_id: parentCommentId,
    likes: numberOf(row, ['likes', 'like_count'], 0),
    is_mine: currentIdentity ? rowMatchesAny(row, ['user_id', 'profile_id', 'author_id'], currentIdentity.ids) : false,
    created_at: textOf(row, ['created_at', 'updated_at'], ''),
    updated_at: textOf(row, ['updated_at', 'created_at'], ''),
  }
}

function challengeId(row) {
  return normalizeId(textOf(row, ['id', 'challenge_id'], ''))
}

const CHALLENGE_ACTIVE_INTERVAL_SQL = "15 minutes"
const CHALLENGE_VOTING_INTERVAL_SQL = "30 seconds"
const CHALLENGE_PODIUM_INTERVAL_SQL = "6 seconds"
const DEFAULT_CHALLENGE_MASTERS = [
  {
    title: 'Canta tu cancion favorita',
    description: 'Graba un audio o video corto cantando una parte de tu cancion favorita.',
    challenge_type: 'audio',
    difficulty: 'normal',
  },
  {
    title: 'Foto con algo verde',
    description: 'Toma una foto donde aparezca claramente algo verde.',
    challenge_type: 'camera',
    difficulty: 'easy',
  },
  {
    title: 'Adivinanza rapida',
    description: 'Responde: Blanco por dentro, verde por fuera. Si quieres que te lo diga, espera. ¿Que es?',
    challenge_type: 'text',
    difficulty: 'easy',
  },
  {
    title: 'Trabalenguas express',
    description: 'Di rapido: Tres tristes tigres tragaban trigo en un trigal.',
    challenge_type: 'audio',
    difficulty: 'hard',
  },
  {
    title: 'Dibuja en 30 segundos',
    description: 'Dibuja una casa, un sol y una persona usando el editor de dibujo.',
    challenge_type: 'drawing',
    difficulty: 'normal',
  },
  {
    title: 'Objeto misterioso',
    description: 'Sube una foto de un objeto cotidiano desde un angulo dificil de reconocer.',
    challenge_type: 'camera',
    difficulty: 'normal',
  },
  {
    title: 'Mini historia',
    description: 'Escribe una historia de maximo tres lineas que empiece con: Hoy paso algo raro...',
    challenge_type: 'text',
    difficulty: 'normal',
  },
  {
    title: 'Imita un sonido',
    description: 'Graba un audio imitando un sonido y deja que los demas voten.',
    challenge_type: 'audio',
    difficulty: 'normal',
  },
  {
    title: 'Reto creativo del dia',
    description: 'Sube una foto, video, audio, dibujo o texto y compite en el ranking.',
    challenge_type: 'mixed',
    difficulty: 'normal',
  },
]

async function localChallengeMasters(pool, limit = 2000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 2000, 10_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        title,
        description,
        challenge_type,
        difficulty,
        creator_id,
        created_at,
        updated_at
     FROM fallback_challenges_master
     ORDER BY created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localActiveChallenges(pool, limit = 2000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 2000, 10_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        challenge_id::text,
        expires_at,
        voting_expires_at,
        status,
        winner_ids,
        created_at,
        updated_at
     FROM fallback_active_challenges
     ORDER BY created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function challengeWinnerIds(pool, activeChallengeId, limit = 3) {
  const activeId = normalizeId(activeChallengeId)
  if (!activeId) return []
  const { rows } = await pool.query(
    `SELECT
        s.user_id,
        COUNT(v.submission_id)::int AS vote_count,
        MIN(s.created_at) AS first_submitted_at
     FROM fallback_challenge_submissions s
     LEFT JOIN fallback_challenge_votes v
       ON lower(v.submission_id) = lower(s.id::text)
     WHERE lower(s.active_challenge_id) = $1
     GROUP BY s.user_id
     ORDER BY vote_count DESC, first_submitted_at ASC
     LIMIT $2`,
    [activeId, Math.max(1, Math.min(Number(limit) || 3, 10))],
  )
  return rows
    .map((row) => normalizeId(row.user_id))
    .filter(Boolean)
}

async function purgeChallengeSession(pool, activeChallengeId) {
  const activeId = normalizeId(activeChallengeId)
  if (!activeId) return
  await pool.query(
    `DELETE FROM fallback_challenge_votes
     WHERE lower(submission_id) IN (
       SELECT lower(id::text)
       FROM fallback_challenge_submissions
       WHERE lower(active_challenge_id) = $1
     )`,
    [activeId],
  )
  await pool.query(
    `DELETE FROM fallback_challenge_submissions
     WHERE lower(active_challenge_id) = $1`,
    [activeId],
  )
  await pool.query(
    `DELETE FROM fallback_active_challenges
     WHERE lower(id::text) = $1`,
    [activeId],
  )
}

async function ensureDefaultChallengeMasters(pool, creatorId = 'system') {
  let masters = await localChallengeMasters(pool, 500)
  if (masters.length >= 8) return masters

  const existingTitles = new Set(
    masters
      .map((row) => textOf(row, ['title'], '').trim().toLowerCase())
      .filter(Boolean),
  )

  for (const master of DEFAULT_CHALLENGE_MASTERS) {
    const titleKey = master.title.trim().toLowerCase()
    if (existingTitles.has(titleKey)) continue
    await pool.query(
      `INSERT INTO fallback_challenges_master(title, description, challenge_type, difficulty, creator_id)
       VALUES ($1, $2, $3, $4, $5)`,
      [master.title, master.description, master.challenge_type, master.difficulty, creatorId],
    )
    existingTitles.add(titleKey)
  }

  return localChallengeMasters(pool, 500)
}

async function hasChallengeSubmissions(pool, activeChallengeId) {
  const activeId = normalizeId(activeChallengeId)
  if (!activeId) return false
  const { rows } = await pool.query(
    `SELECT 1
     FROM fallback_challenge_submissions
     WHERE lower(active_challenge_id) = $1
     LIMIT 1`,
    [activeId],
  )
  return rows.length > 0
}

async function ensureActiveChallenge(pool, user) {
  const now = new Date()
  const identity = userIdentity(user)
  let masters = await ensureDefaultChallengeMasters(pool, identity.displayUserId)
  const activeRows = await localActiveChallenges(pool, 50)
  const existing = activeRows.find((row) => {
    const status = textOf(row, ['status'], '').toLowerCase()
    const expiresAt = toMillis(row.expires_at)
    const votingExpiresAt = toMillis(row.voting_expires_at)
    if (status === 'voting') return !votingExpiresAt || votingExpiresAt > now.getTime()
    if (status === 'completed') return expiresAt && expiresAt > now.getTime()
    return status === 'active' && (!expiresAt || expiresAt > now.getTime())
  })
  if (existing) {
    const status = textOf(existing, ['status'], '').toLowerCase()
    const expiresAt = toMillis(existing.expires_at)
    const maxActiveExpiresAt = now.getTime() + 15 * 60 * 1000
    const existingMaster = masters.find((row) => challengeId(row) === normalizeId(textOf(existing, ['challenge_id'], '')))
    const existingTitle = textOf(existingMaster || {}, ['title'], '').trim().toLowerCase()
    if (
      status === 'active' &&
      existingTitle === 'reto creativo del dia' &&
      masters.length > 1 &&
      !(await hasChallengeSubmissions(pool, textOf(existing, ['id'], '')))
    ) {
      await purgeChallengeSession(pool, textOf(existing, ['id'], ''))
    } else {
    if (status === 'active' && (!expiresAt || expiresAt > maxActiveExpiresAt)) {
      const { rows } = await pool.query(
        `UPDATE fallback_active_challenges
         SET expires_at = NOW() + INTERVAL '${CHALLENGE_ACTIVE_INTERVAL_SQL}',
             voting_expires_at = NOW() + INTERVAL '${CHALLENGE_ACTIVE_INTERVAL_SQL}' + INTERVAL '${CHALLENGE_VOTING_INTERVAL_SQL}',
             updated_at = NOW()
         WHERE id = $1::uuid
         RETURNING id::text, challenge_id::text, expires_at, voting_expires_at, status, winner_ids, created_at, updated_at`,
        [textOf(existing, ['id'], '')],
      )
      if (rows[0]) return normalizeImportedRow(rows[0])
    }
    return existing
    }
  }

  const expiredCompleted = activeRows.find((row) => {
    const status = textOf(row, ['status'], '').toLowerCase()
    const expiresAt = toMillis(row.expires_at)
    return status === 'completed' && expiresAt && expiresAt <= now.getTime()
  })
  if (expiredCompleted) {
    await purgeChallengeSession(pool, textOf(expiredCompleted, ['id'], ''))
  }

  const expiredVoting = activeRows.find((row) => {
    const status = textOf(row, ['status'], '').toLowerCase()
    const votingExpiresAt = toMillis(row.voting_expires_at)
    return status === 'voting' && votingExpiresAt && votingExpiresAt <= now.getTime()
  })
  if (expiredVoting) {
    const winners = await challengeWinnerIds(pool, textOf(expiredVoting, ['id'], ''), 3)
    const { rows } = await pool.query(
      `UPDATE fallback_active_challenges
       SET status = 'completed',
           winner_ids = $2::jsonb,
           expires_at = NOW() + INTERVAL '${CHALLENGE_PODIUM_INTERVAL_SQL}',
           updated_at = NOW()
       WHERE id = $1::uuid
       RETURNING id::text, challenge_id::text, expires_at, voting_expires_at, status, winner_ids, created_at, updated_at`,
      [textOf(expiredVoting, ['id'], ''), JSON.stringify(winners)],
    )
    if (rows[0]) return normalizeImportedRow(rows[0])
  }

  const expiredActive = activeRows.find((row) => {
    const status = textOf(row, ['status'], '').toLowerCase()
    const expiresAt = toMillis(row.expires_at)
    return status === 'active' && expiresAt && expiresAt <= now.getTime()
  })
  if (expiredActive) {
    const { rows } = await pool.query(
      `UPDATE fallback_active_challenges
       SET status = 'voting',
           voting_expires_at = CASE
             WHEN voting_expires_at IS NULL OR voting_expires_at <= NOW()
             THEN NOW() + INTERVAL '${CHALLENGE_VOTING_INTERVAL_SQL}'
             ELSE voting_expires_at
           END,
           updated_at = NOW()
       WHERE id = $1::uuid
       RETURNING id::text, challenge_id::text, expires_at, voting_expires_at, status, winner_ids, created_at, updated_at`,
      [textOf(expiredActive, ['id'], '')],
    )
    if (rows[0]) return normalizeImportedRow(rows[0])
  }

  const availableMasters = masters.length > 1
    ? masters.filter((row) => textOf(row, ['title'], '').trim().toLowerCase() !== 'reto creativo del dia')
    : masters
  const masterPool = availableMasters.length > 0 ? availableMasters : masters
  const master = masterPool[Math.floor(Math.random() * masterPool.length)]
  const { rows } = await pool.query(
    `INSERT INTO fallback_active_challenges(challenge_id, expires_at, voting_expires_at, status)
     VALUES ($1::uuid, NOW() + INTERVAL '${CHALLENGE_ACTIVE_INTERVAL_SQL}', NOW() + INTERVAL '${CHALLENGE_ACTIVE_INTERVAL_SQL}' + INTERVAL '${CHALLENGE_VOTING_INTERVAL_SQL}', 'active')
     RETURNING id::text, challenge_id::text, expires_at, voting_expires_at, status, winner_ids, created_at, updated_at`,
    [challengeId(master)],
  )
  return normalizeImportedRow(rows[0])
}

function mapChallenge(row) {
  return {
    id: textOf(row, ['id'], ''),
    title: textOf(row, ['title'], 'Reto'),
    description: textOf(row, ['description'], ''),
    challenge_type: textOf(row, ['challenge_type', 'challengeType'], 'mixed'),
    difficulty: textOf(row, ['difficulty'], 'normal'),
    creator_id: textOf(row, ['creator_id', 'user_id'], ''),
    participantCount: numberOf(row, ['participant_count', 'participantCount'], 0),
    isCompleted: boolOf(row, ['is_completed', 'isCompleted'], false),
    created_at: textOf(row, ['created_at'], ''),
    updated_at: textOf(row, ['updated_at'], ''),
  }
}

function mapActiveChallenge(row, master) {
  const challengeIdValue = textOf(row, ['challenge_id', 'challengeId'], '')
  const fallbackMaster = master || {
    id: challengeIdValue,
    title: 'Reto creativo del dia',
    description: 'Sube una foto, video, audio, dibujo o texto y compite en el ranking.',
    challenge_type: 'mixed',
    difficulty: 'normal',
  }
  return {
    id: textOf(row, ['id'], ''),
    challenge_id: challengeIdValue,
    challenge: mapChallenge(fallbackMaster),
    expires_at: textOf(row, ['expires_at', 'expiresAt'], ''),
    voting_expires_at: textOf(row, ['voting_expires_at', 'votingExpiresAt'], ''),
    status: textOf(row, ['status'], 'active'),
    winner_ids: arrayOf(row, ['winner_ids', 'winnerIds']),
  }
}

function mapChallengeSubmission(row, profilesById, voteCounts, votedSubmissionIds) {
  const id = textOf(row, ['id'], '')
  const userId = textOf(row, ['user_id', 'profile_id'], '')
  const profile = profilesById.get(normalizeId(userId)) || {}
  return {
    ...row,
    id,
    challenge_id: textOf(row, ['challenge_id', 'challengeId'], ''),
    active_challenge_id: textOf(row, ['active_challenge_id', 'activeChallengeId'], ''),
    user_id: userId,
    user_data: {
      id: userId,
      display_name: profileName(profile, textOf(row, ['user_name'], 'Usuario')),
      username: textOf(profile, ['username'], ''),
      avatar_url: profileAvatar(profile),
    },
    content_url: textOf(row, ['content_url', 'contentUrl', 'media_url'], '') || null,
    text_content: textOf(row, ['text_content', 'textContent', 'content'], '') || null,
    vote_count: voteCounts.get(id) ?? numberOf(row, ['vote_count', 'voteCount'], 0),
    voted_by_me: votedSubmissionIds.has(id),
    created_at: textOf(row, ['created_at'], ''),
  }
}

function normalizePostMediaUrls(row) {
  const urls = []
  const mediaUrls = row?.media_urls
  if (Array.isArray(mediaUrls)) {
    for (const entry of mediaUrls) {
      if (typeof entry === 'string' && entry.trim()) urls.push(entry.trim())
      if (entry && typeof entry === 'object') {
        const url = textOf(entry, ['url', 'media_url', 'public_url'], '')
        if (url) urls.push(url)
      }
    }
  }
  const single = textOf(row, ['media_url', 'image_url', 'video_url'], '')
  if (single) urls.push(single)
  return Array.from(new Set(urls))
}

async function localPostComments(pool, postIds, limit = 10_000) {
  const ids = Array.from(cleanSet(postIds))
  if (ids.length === 0) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        post_id,
        user_id,
        content,
        parent_id,
        media_url,
        media_type,
        created_at,
        updated_at,
        TRUE AS local_only
     FROM fallback_post_comments
     WHERE is_deleted = FALSE
       AND lower(post_id) = ANY($1::text[])
     ORDER BY created_at ASC
     LIMIT $2`,
    [ids, safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localPostReactions(pool, postIds, limit = 10_000) {
  const ids = Array.from(cleanSet(postIds))
  if (ids.length === 0) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT
        post_id,
        user_id,
        reaction_type,
        created_at,
        updated_at,
        TRUE AS local_only
     FROM fallback_post_reactions
     WHERE lower(post_id) = ANY($1::text[])
     ORDER BY updated_at DESC
     LIMIT $2`,
    [ids, safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localPostShares(pool, postIds, limit = 10_000) {
  const ids = Array.from(cleanSet(postIds))
  if (ids.length === 0) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        post_id,
        user_id,
        content_type,
        created_at,
        TRUE AS local_only
     FROM fallback_shares
     WHERE content_type = 'post'
       AND lower(post_id) = ANY($1::text[])
     ORDER BY created_at DESC
     LIMIT $2`,
    [ids, safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function fallbackUserProfileRows(pool, limit = 5000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 5000, 10_000))
  return cacheGetOrSet(
    `fallbackUserProfileRows:${safeLimit}`,
    10_000,
    async () => {
      const { rows } = await pool.query(
        `SELECT
            u.id::text AS id,
            u.supabase_user_id::text AS supabase_user_id,
            COALESCE(o.profile->>'email', u.email) AS email,
            COALESCE(o.profile->>'display_name', o.profile->>'displayName', o.profile->>'full_name', u.display_name) AS display_name,
            COALESCE(o.profile->>'full_name', o.profile->>'fullName', o.profile->>'display_name', u.display_name) AS full_name,
            COALESCE(o.profile->>'username', split_part(u.email, '@', 1)) AS username,
            COALESCE(o.profile->>'bio', '') AS bio,
            COALESCE(o.profile->>'country', '') AS country,
            COALESCE(o.profile->>'city', '') AS city,
            COALESCE(o.profile->>'status', '') AS status,
            COALESCE(o.profile->'interests', '[]'::jsonb) AS interests,
            COALESCE(o.profile->'hobbies', '[]'::jsonb) AS hobbies,
            COALESCE(o.profile->'linked_games', o.profile->'linkedGames', '[]'::jsonb) AS linked_games,
            COALESCE(o.profile->'game_logo_urls', o.profile->'gameLogoUrls', '{}'::jsonb) AS game_logo_urls,
            COALESCE(o.profile->>'favorite_music_url', o.profile->>'favoriteMusicUrl', '') AS favorite_music_url,
            COALESCE(o.profile->>'favorite_book', o.profile->>'favoriteBook', '') AS favorite_book,
            COALESCE(o.profile->>'favorite_sport', o.profile->>'favoriteSport', '') AS favorite_sport,
            COALESCE(o.profile->>'career', '') AS career,
            COALESCE(o.profile->>'availability_schedule', o.profile->>'availabilitySchedule', '') AS availability_schedule,
            COALESCE(o.profile->'profile_section_order', o.profile->'profileSectionOrder', '[]'::jsonb) AS profile_section_order,
            COALESCE(o.profile->'profile_section_visibility', o.profile->'profileSectionVisibility', '{}'::jsonb) AS profile_section_visibility,
            COALESCE(o.profile->>'avatar_url', o.profile->>'avatarUrl', o.profile->>'photo_url', o.profile->>'profile_image', u.avatar_url) AS avatar_url,
            COALESCE(o.profile->>'cover_url', o.profile->>'coverUrl') AS cover_url,
            COALESCE(o.profile->>'profile_theme', o.profile->>'profileTheme', 'conectate') AS profile_theme,
            COALESCE(o.profile->>'profile_effect', o.profile->>'profileEffect', 'none') AS profile_effect,
            COALESCE(o.profile->>'profile_welcome', o.profile->>'profileWelcome') AS profile_welcome,
            COALESCE(o.profile->>'is_verified', o.profile->>'isVerified', 'false') AS is_verified,
            CASE
              WHEN jsonb_typeof(o.profile->'badges') = 'array' THEN o.profile->'badges'
              ELSE '[]'::jsonb
            END AS badges,
            COALESCE(o.profile->>'profile_frame', o.profile->>'profileFrame', '') AS profile_frame,
            COALESCE(
              o.profile->'__clear_fields',
              to_jsonb(ARRAY_REMOVE(ARRAY[
                CASE WHEN o.profile ? 'profile_welcome' AND BTRIM(COALESCE(o.profile->>'profile_welcome', '')) = '' THEN 'profile_welcome' END,
                CASE WHEN o.profile ? 'avatar_url' AND BTRIM(COALESCE(o.profile->>'avatar_url', '')) = '' THEN 'avatar_url' END,
                CASE WHEN o.profile ? 'avatar_media_type' AND BTRIM(COALESCE(o.profile->>'avatar_media_type', '')) = '' THEN 'avatar_media_type' END,
                CASE WHEN o.profile ? 'cover_url' AND BTRIM(COALESCE(o.profile->>'cover_url', '')) = '' THEN 'cover_url' END
              ]::text[], NULL))
            ) AS __clear_fields,
            COALESCE(o.profile->>'is_private', o.profile->>'isPrivate', 'false') AS is_private,
            COALESCE(o.profile->>'favorites_public', o.profile->>'favoritesPublic', 'false') AS favorites_public,
            COALESCE(o.profile->>'break_mode', o.profile->>'breakMode', 'off') AS break_mode,
            COALESCE(o.profile->>'profile_visibility', o.profile->>'profileVisibility', 'everyone') AS profile_visibility,
            COALESCE(o.profile->>'is_online', o.profile->>'isOnline', 'false') AS is_online,
            COALESCE(o.profile->>'last_seen', o.profile->>'lastSeen', u.last_login_at::text, u.updated_at::text, u.created_at::text) AS last_seen,
            COALESCE(o.profile->>'last_seen_visibility', o.profile->>'lastSeenVisibility', 'everyone') AS last_seen_visibility,
            COALESCE(o.profile->>'online_status_visibility', o.profile->>'onlineStatusVisibility', 'everyone') AS online_status_visibility,
            u.created_at,
            COALESCE(o.updated_at, u.last_login_at, u.created_at) AS updated_at
         FROM fallback_users u
         LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
         ORDER BY COALESCE(o.updated_at, u.created_at) DESC
         LIMIT $1`,
        [safeLimit],
      )
      return rows.map(normalizeImportedRow)
    },
  )
}

async function localFollows(pool, limit = 10_000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        follower_id,
        following_id,
        created_at,
        TRUE AS local_only
     FROM fallback_follows
     ORDER BY created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

function normalizeFeedMode(value) {
  const mode = String(value || '').trim().toLowerCase()
  return ['discover', 'following'].includes(mode) ? mode : 'all'
}

function normalizeSavedCollectionName(value) {
  return cleanText(value, 60).replace(/\s+/g, ' ')
}

function normalizeSavedTags(value) {
  return Array.from(new Set(asArray(value)
    .flatMap((entry) => String(entry || '').split(','))
    .map((entry) => cleanText(entry, 30).toLowerCase())
    .filter((entry) => entry.length >= 2)))
    .slice(0, 12)
}

function feedPostVisible(row, {
  identityIds = new Set(),
  followingIds = new Set(),
  mode = 'all',
  requestedUserId = '',
} = {}) {
  const ownerId = postOwnerId(row)
  const isMine = Boolean(ownerId && identityIds.has(ownerId))
  const isFollowing = Boolean(ownerId && followingIds.has(ownerId))
  const visibility = textOf(row, ['visibility'], 'public').toLowerCase()
  if (requestedUserId && ownerId !== requestedUserId) return false
  if (!isMine && visibility === 'private') return false
  if (!isMine && visibility === 'followers' && !isFollowing) return false
  if (!isMine && visibility !== 'public' && visibility !== '' && visibility !== 'followers') return false
  if (requestedUserId) return true
  if (mode === 'following') return isMine || isFollowing
  if (mode === 'discover') return isMine || !isFollowing
  return true
}

async function ensureDefaultSavedCollection(pool, identity) {
  const { rows } = await pool.query(
    `INSERT INTO fallback_saved_collections(user_id, name, is_default)
     VALUES ($1, 'Guardados', TRUE)
     ON CONFLICT DO NOTHING
     RETURNING id::text, user_id, name, is_default, created_at, updated_at`,
    [identity.displayUserId],
  )
  if (rows[0]) return rows[0]
  const existing = await pool.query(
    `SELECT id::text, user_id, name, is_default, created_at, updated_at
     FROM fallback_saved_collections
     WHERE lower(user_id) = ANY($1::text[]) AND is_default = TRUE
     ORDER BY created_at
     LIMIT 1`,
    [identity.values],
  )
  return existing.rows[0] || null
}

async function ownedSavedCollection(pool, identity, collectionId) {
  const normalizedId = normalizeId(collectionId)
  if (!normalizedId) return null
  const { rows } = await pool.query(
    `SELECT id::text, user_id, name, is_default, created_at, updated_at
     FROM fallback_saved_collections
     WHERE lower(id::text) = $1 AND lower(user_id) = ANY($2::text[])
     LIMIT 1`,
    [normalizedId, identity.values],
  )
  return rows[0] || null
}

async function localReports(pool, limit = 10_000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        reporter_id,
        reporter_name,
        reported_user_id,
        reported_user_name,
        reason,
        description,
        evidence_url,
        content_type,
        content_id,
        status,
        created_at,
        updated_at,
        TRUE AS local_only
     FROM fallback_reports
     ORDER BY created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

async function localNotifications(pool, limit = 10_000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 10_000, 50_000))
  const { rows } = await pool.query(
    `SELECT
        id::text,
        recipient_id,
        actor_id,
        actor_name,
        actor_avatar_url,
        type,
        target_id,
        target_type,
        content,
        is_read,
        extra_data,
        created_at,
        updated_at,
        TRUE AS local_only
     FROM fallback_notifications
     ORDER BY created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
}

function notificationRecipientId(row) {
  return normalizeId(textOf(row, ['recipient_id', 'user_id', 'profile_id', 'to_user_id'], ''))
}

function normalizeNotification(row, profilesById) {
  const actorId = normalizeId(textOf(row, ['actor_id', 'sender_id', 'from_user_id'], ''))
  const actorProfile = actorId ? profilesById.get(actorId) : null
  const extraData = row?.extra_data && typeof row.extra_data === 'object' ? row.extra_data : {}
  return {
    ...row,
    id: textOf(row, ['id'], `${notificationRecipientId(row)}-${textOf(row, ['created_at'], '')}-${actorId}`),
    recipient_id: notificationRecipientId(row),
    actor_id: actorId,
    actor_name: textOf(row, ['actor_name', 'sender_name'], '') || profileName(actorProfile, actorId || 'Sistema'),
    actor_avatar_url: textOf(row, ['actor_avatar_url', 'sender_avatar_url'], '') || profileAvatar(actorProfile),
    type: textOf(row, ['type', 'notification_type'], 'activity'),
    target_id: normalizeId(textOf(row, ['target_id', 'post_id', 'video_id', 'chat_id', 'group_id', 'community_id'], '')),
    target_type: textOf(row, ['target_type'], ''),
    content: textOf(row, ['content', 'message', 'body', 'title'], ''),
    is_read: boolOf(row, ['is_read', 'read'], false),
    extra_data: extraData,
    created_at: textOf(row, ['created_at'], new Date().toISOString()),
  }
}

async function createLocalNotification(pool, input) {
  const recipientId = normalizeId(input?.recipientId || input?.recipient_id || '')
  const actorId = normalizeId(input?.actorId || input?.actor_id || '')
  const type = cleanText(input?.type || 'activity', 80)
  if (!recipientId || !type || recipientId === actorId) return null

  const breakState = await pool.query(
    `SELECT COALESCE(o.profile->>'break_mode', o.profile->>'breakMode', 'off') AS break_mode
     FROM fallback_users u
     LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
     WHERE lower(u.id::text) = $1 OR lower(COALESCE(u.supabase_user_id::text, '')) = $1
     LIMIT 1`,
    [recipientId],
  )
  const breakMode = normalizeId(breakState.rows[0]?.break_mode || 'off')
  if (breakMode === 'mute' || breakMode === 'hidden') return null

  const targetId = cleanText(input?.targetId || input?.target_id, 160) || null
  const targetType = cleanText(input?.targetType || input?.target_type, 80) || null
  const content = cleanText(input?.content, 700) || null
  const actorName = cleanText(input?.actorName || input?.actor_name, 160) || null
  const actorAvatarUrl = cleanText(input?.actorAvatarUrl || input?.actor_avatar_url, 700) || null
  const originalExtraData = input?.extraData && typeof input.extraData === 'object' ? input.extraData : {}
  const delivery = await shouldDeferNotificationPush(
    pool,
    {
      recipient_id: recipientId,
      type,
      target_id: targetId,
      target_type: targetType,
    },
    originalExtraData,
  )
  const extraData = delivery.defer
    ? {
        ...originalExtraData,
        digest_deferred: true,
        digest_category: delivery.category,
      }
    : originalExtraData

  const { rows } = await pool.query(
    `INSERT INTO fallback_notifications(
        recipient_id,
        actor_id,
        actor_name,
        actor_avatar_url,
        type,
        target_id,
        target_type,
        content,
        extra_data
     )
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb)
     RETURNING id::text,
       recipient_id,
       actor_id,
       actor_name,
       actor_avatar_url,
       type,
       target_id,
       target_type,
       content,
       is_read,
       extra_data,
       created_at,
       updated_at`,
    [
      recipientId,
      actorId || null,
      actorName,
      actorAvatarUrl,
      type,
      targetId,
      targetType,
      content,
      JSON.stringify(extraData),
    ],
  )
  const notification = normalizeImportedRow(rows[0])
  try {
    const { publishRealtimeEvent } = require('./realtime-publish')
    const isCallInvite = type === 'call_invite'
    const isCallResponse = type === 'call_response' || type === 'call_cancel'
    const envelope = {
      event: isCallInvite ? 'call_invite' : isCallResponse ? 'call_response' : 'notification',
      notification,
      extraData,
    }
    if (isCallInvite) {
      Object.assign(envelope, {
        callId: extraData.call_id || extraData.callId || targetId,
        title: extraData.call_title || extraData.callTitle || content,
        callerUserId: actorId,
        callerName: actorName,
        callerAvatarUrl: actorAvatarUrl,
        contextType: extraData.call_context_type || extraData.callContextType || 'dm',
        contextId: extraData.call_context_id || extraData.callContextId || '',
        isVideo: Boolean(extraData.call_is_video ?? extraData.callIsVideo),
      })
    } else if (isCallResponse) {
      Object.assign(envelope, {
        callId: extraData.call_id || extraData.callId || targetId,
        action: extraData.call_action || extraData.callAction || (type === 'call_cancel' ? 'cancel' : ''),
        fromUserId: extraData.from_user_id || extraData.fromUserId || actorId,
      })
    }
    void publishRealtimeEvent(`user:${recipientId}`, envelope)
  } catch {
    // Realtime es best-effort; la notificación HTTP ya quedó persistida.
  }
  try {
    const { sendPushForNotification } = require('./push-notifications')
    void sendPushForNotification(pool, notification, extraData).catch((error) => {
      console.error(`[push] Notification ${notification.id} could not be delivered: ${error.message}`)
    })
  } catch (error) {
    console.error(`[push] Notification sender could not be loaded: ${error.message}`)
  }
  return notification
}

async function createLocalNotificationBestEffort(pool, input) {
  try {
    return await createLocalNotification(pool, input)
  } catch (error) {
    console.error(
      `[notification] Could not persist ${cleanText(input?.type || 'activity', 80)} notification: ${error?.message || error}`,
    )
    return null
  }
}

async function localProfileOverride(pool, userId) {
  const { rows } = await pool.query(
    `SELECT profile
     FROM fallback_profile_overrides
     WHERE user_id = $1
     LIMIT 1`,
    [userId],
  )
  return normalizeImportedRow(rows[0]?.profile || {})
}

function normalizeProfileForPublic(row, fallbackId = '') {
  const id = profileId(row) || normalizeId(fallbackId)
  const lastSeenVisibility = textOf(row, ['last_seen_visibility', 'lastSeenVisibility'], 'everyone')
  const onlineStatusVisibility = textOf(row, ['online_status_visibility', 'onlineStatusVisibility'], 'everyone')
  const presence = lastSeenVisibility === 'everyone' || onlineStatusVisibility === 'everyone'
    ? presenceFields(row)
    : { is_online: false, last_seen: null }
  const publicBadges = Array.isArray(row?.badges)
    ? row.badges.filter((badge) => badge && typeof badge === 'object').slice(0, 12)
    : []
  const profileFrame = cleanText(row?.profile_frame ?? row?.profileFrame, 40).toLowerCase()
  return {
    id,
    user_id: textOf(row, ['user_id', 'userId'], ''),
    profile_id: textOf(row, ['profile_id', 'profileId'], ''),
    supabase_user_id: textOf(row, ['supabase_user_id', 'supabaseUserId'], ''),
    display_name: profileName(row),
    full_name: textOf(row, ['full_name'], profileName(row)),
    username: textOf(row, ['username', 'handle'], ''),
    bio: textOf(row, ['bio', 'description', 'about'], ''),
    avatar_url: profileAvatar(row),
    avatar_media_type: textOf(row, ['avatar_media_type', 'avatarMediaType'], ''),
    avatar_video_muted: boolOf(row, ['avatar_video_muted', 'avatarVideoMuted'], true),
    avatar_duration_ms: numberOf(row, ['avatar_duration_ms', 'avatarDurationMs'], 0),
    cover_url: textOf(row, ['cover_url', 'banner_url'], ''),
    profile_theme: textOf(row, ['profile_theme', 'profileTheme'], 'conectate'),
    profile_effect: textOf(row, ['profile_effect', 'profileEffect'], 'none'),
    profile_welcome: textOf(row, ['profile_welcome', 'profileWelcome'], ''),
    country: textOf(row, ['country'], ''),
    city: textOf(row, ['city'], ''),
    status: textOf(row, ['status'], ''),
    interests: arrayOf(row, ['interests']).map((value) => cleanText(value, 80)).filter(Boolean).slice(0, 40),
    hobbies: arrayOf(row, ['hobbies']).map((value) => cleanText(value, 80)).filter(Boolean).slice(0, 40),
    linked_games: arrayOf(row, ['linked_games', 'linkedGames']).map((value) => cleanText(value, 80)).filter(Boolean).slice(0, 20),
    game_logo_urls: row?.game_logo_urls && typeof row.game_logo_urls === 'object' && !Array.isArray(row.game_logo_urls)
      ? row.game_logo_urls
      : {},
    favorite_music_url: textOf(row, ['favorite_music_url', 'favoriteMusicUrl'], ''),
    favorite_book: textOf(row, ['favorite_book', 'favoriteBook'], ''),
    favorite_sport: textOf(row, ['favorite_sport', 'favoriteSport'], ''),
    career: textOf(row, ['career'], ''),
    availability_schedule: textOf(row, ['availability_schedule', 'availabilitySchedule'], ''),
    profile_section_order: normalizeProfileSectionOrder(row?.profile_section_order ?? row?.profileSectionOrder),
    profile_section_visibility: normalizeProfileSectionVisibility(row?.profile_section_visibility ?? row?.profileSectionVisibility),
    followers_count: numberOf(row, ['followers_count', 'follower_count'], 0),
    following_count: numberOf(row, ['following_count'], 0),
    post_count: numberOf(row, ['post_count', 'posts_count'], 0),
    is_verified: boolOf(row, ['is_verified', 'verified'], false),
    badges: publicBadges,
    profile_frame: new Set(['gold', 'neon', 'ruby', 'ocean']).has(profileFrame) ? profileFrame : '',
    is_private: boolOf(row, ['is_private', 'private'], false),
    favorites_public: boolOf(row, ['favorites_public', 'favoritesPublic'], false),
    break_mode: textOf(row, ['break_mode', 'breakMode'], 'off'),
    profile_visibility: textOf(row, ['profile_visibility', 'profileVisibility'], 'everyone'),
    ...presence,
    last_seen_visibility: lastSeenVisibility,
    online_status_visibility: onlineStatusVisibility,
    created_at: textOf(row, ['created_at'], ''),
    updated_at: textOf(row, ['updated_at'], textOf(row, ['created_at'], '')),
  }
}

async function allPublicProfiles(pool) {
  const cached = cacheGet('allPublicProfiles:10000')
  if (cached) return cached.map((row) => ({ ...row }))
  const [importedProfiles, fallbackProfiles] = await Promise.all([
    importedRows(pool, 'profiles', 10_000),
    fallbackUserProfileRows(pool, 10_000),
  ])
  const byCanonicalId = new Map()
  const aliasToCanonicalId = new Map()

  const upsertProfile = (row, preferLatest = false) => {
    const profile = normalizeProfileForPublic(row)
    const aliases = profileAliasSet(profile)
    if (aliases.size === 0) return
    const knownCanonicalId = Array.from(aliases).map((alias) => aliasToCanonicalId.get(alias)).find(Boolean)
    const canonicalId = knownCanonicalId || normalizeId(profile.supabase_user_id) || profile.id || Array.from(aliases)[0]
    const existing = byCanonicalId.get(canonicalId) || {}
    // Merge the raw candidate before normalizing it. Normalization supplies empty
    // media defaults, so spreading a normalized fallback row would erase a real
    // imported avatar/cover even when the fallback source did not provide one.
    const mergedRow = preferLatest
      ? mergeProfileRows(existing, row)
      : mergeProfileRows(row, existing)
    const merged = normalizeProfileForPublic(mergedRow, canonicalId)
    byCanonicalId.set(canonicalId, merged)
    for (const alias of profileAliasSet(merged)) aliasToCanonicalId.set(alias, canonicalId)
    for (const alias of aliases) aliasToCanonicalId.set(alias, canonicalId)
  }

  for (const row of importedProfiles) upsertProfile(row, false)
  for (const row of fallbackProfiles) upsertProfile(row, true)

  return cacheSet('allPublicProfiles:10000', Array.from(byCanonicalId.values()), 15_000)
}

function profileMatchesQuery(profile, query) {
  if (!query) return true
  const haystack = [
    profile.id,
    profile.email,
    profile.display_name,
    profile.full_name,
    profile.username,
    profile.bio,
  ].map((entry) => String(entry || '').toLowerCase()).join(' ')
  return haystack.includes(query)
}

function followMatches(row, followerIds, followingIds) {
  const follower = normalizeId(textOf(row, ['follower_id', 'user_id'], ''))
  const following = normalizeId(textOf(row, ['following_id', 'target_user_id'], ''))
  return follower && following && followerIds.has(follower) && followingIds.has(following)
}

async function combinedFollows(pool) {
  const [imported, local] = await Promise.all([
    importedRows(pool, 'follows', 20_000),
    localFollows(pool, 20_000),
  ])
  return [...imported, ...local]
}

async function getCurrentUser(pool, req) {
  const { rows } = await pool.query(
    `SELECT id,
            email,
            display_name,
            avatar_url,
            supabase_user_id,
            sync_source,
            sync_status,
            created_at,
            updated_at,
            last_login_at
     FROM fallback_users
     WHERE id = $1
     LIMIT 1`,
    [req.auth.sub],
  )
  return rows[0] || null
}

async function fallbackUserByProfileAlias(pool, rawProfileId) {
  const profileId = normalizeId(rawProfileId)
  if (!profileId) return null
  const { rows } = await pool.query(
    `SELECT id,
            email,
            display_name,
            avatar_url,
            supabase_user_id,
            sync_source,
            sync_status,
            created_at,
            updated_at,
            last_login_at
     FROM fallback_users
     WHERE lower(id::text) = $1
        OR lower(COALESCE(supabase_user_id::text, '')) = $1
        OR lower(email) = $1
        OR lower(split_part(email, '@', 1)) = $1
        OR lower(COALESCE(display_name, '')) = $1
     ORDER BY updated_at DESC NULLS LAST, created_at DESC
     LIMIT 1`,
    [profileId],
  )
  return rows[0] || null
}

function userIdentity(user) {
  const ids = cleanSet([user.id, user.supabase_user_id])
  const displayUserId = normalizeId(user.supabase_user_id || user.id)
  return {
    ids,
    values: Array.from(ids),
    localUserId: normalizeId(user.id),
    preferredUserId: displayUserId,
    displayUserId,
    email: normalizeId(user.email),
  }
}

function fallbackProfileRow(user) {
  return {
    id: user.supabase_user_id || user.id,
    email: user.email,
    display_name: user.display_name || user.email,
    full_name: user.display_name || user.email,
    avatar_url: user.avatar_url || '',
    avatar_media_type: 'image',
    avatar_video_muted: true,
    avatar_duration_ms: 0,
    is_online: false,
    last_seen: user.last_login_at || user.updated_at || user.created_at,
    last_seen_visibility: 'everyone',
    online_status_visibility: 'everyone',
    created_at: user.created_at,
    updated_at: user.updated_at || user.last_login_at || user.created_at,
  }
}

function buildProfileForUser(user, importedProfile = {}, overrideProfile = {}) {
  const base = Object.keys(importedProfile || {}).length > 0 ? importedProfile : fallbackProfileRow(user)
  const avatarExplicitlyOverridden = Object.prototype.hasOwnProperty.call(overrideProfile || {}, 'avatar_url')
  const merged = {
    ...base,
    ...overrideProfile,
  }
  return {
    ...merged,
    id: textOf(merged, ['supabase_user_id', 'supabaseUserId', 'id'], user.supabase_user_id || user.id),
    email: textOf(merged, ['email'], user.email),
    display_name: profileName(merged, user.display_name || user.email),
    full_name: textOf(merged, ['full_name'], profileName(merged, user.display_name || user.email)),
    avatar_url: avatarExplicitlyOverridden
      ? String(overrideProfile.avatar_url || '').trim()
      : profileAvatar(merged),
    avatar_media_type: textOf(merged, ['avatar_media_type', 'avatarMediaType'], ''),
    avatar_video_muted: boolOf(merged, ['avatar_video_muted', 'avatarVideoMuted'], true),
    avatar_duration_ms: numberOf(merged, ['avatar_duration_ms', 'avatarDurationMs'], 0),
    ...presenceFields(merged),
    last_seen_visibility: textOf(merged, ['last_seen_visibility', 'lastSeenVisibility'], 'everyone'),
    online_status_visibility: textOf(merged, ['online_status_visibility', 'onlineStatusVisibility'], 'everyone'),
    created_at: textOf(merged, ['created_at'], user.created_at),
    updated_at: textOf(merged, ['updated_at'], new Date().toISOString()),
  }
}

const PROFILE_THEME_IDS = new Set([
  'conectate', 'oceano', 'jade', 'solar', 'sakura', 'cobalto', 'bosque', 'aurora',
  'grafito', 'rubi', 'ciruela', 'artico', 'neon', 'terracota', 'tinta',
  'laguna', 'citrico', 'orquidea', 'carmesi', 'prisma', 'melon', 'acero', 'selva', 'noche',
])
const PROFILE_EFFECT_IDS = new Set([
  'none', 'sparkles', 'constellation', 'confetti',
  'waves', 'orbit', 'rain', 'equalizer',
])

function profilePatchFromBody(body) {
  const patch = {}

  const stringFields = [
    ['display_name', ['display_name', 'displayName', 'full_name', 'fullName']],
    ['bio', ['bio']],
    ['country', ['country']],
    ['city', ['city']],
    ['status', ['status']],
    ['username', ['username']],
    ['favorite_music_url', ['favorite_music_url', 'favoriteMusicUrl']],
    ['favorite_book', ['favorite_book', 'favoriteBook']],
    ['favorite_sport', ['favorite_sport', 'favoriteSport']],
    ['career', ['career']],
    ['availability_schedule', ['availability_schedule', 'availabilitySchedule']],
    ['avatar_url', ['avatar_url', 'avatarUrl']],
    ['avatar_media_type', ['avatar_media_type', 'avatarMediaType']],
    ['cover_url', ['cover_url', 'coverUrl']],
    ['profile_visibility', ['profile_visibility', 'profileVisibility']],
    ['last_seen_visibility', ['last_seen_visibility', 'lastSeenVisibility']],
    ['online_status_visibility', ['online_status_visibility', 'onlineStatusVisibility']],
  ]

  for (const [targetKey, sourceKeys] of stringFields) {
    for (const sourceKey of sourceKeys) {
      if (Object.prototype.hasOwnProperty.call(body || {}, sourceKey)) {
        if (body[sourceKey] == null) break
        const value = cleanText(body[sourceKey], targetKey.endsWith('_url') ? 700 : 280)
        if (value || body[sourceKey] === '') patch[targetKey] = value
        break
      }
    }
  }

  for (const [field, aliases] of [
    ['interests', ['interests']],
    ['hobbies', ['hobbies']],
    ['linked_games', ['linked_games', 'linkedGames']],
  ]) {
    const sourceKey = aliases.find((key) => Object.prototype.hasOwnProperty.call(body || {}, key))
    if (!sourceKey || body[sourceKey] == null) continue
    const values = Array.isArray(body[sourceKey]) ? body[sourceKey] : []
    patch[field] = [...new Set(values.map((value) => cleanText(value, 80)).filter(Boolean))].slice(0, 40)
  }

  const gameLogosKey = ['game_logo_urls', 'gameLogoUrls']
    .find((key) => Object.prototype.hasOwnProperty.call(body || {}, key))
  if (gameLogosKey && body[gameLogosKey] != null) {
    const rawLogos = body[gameLogosKey] && typeof body[gameLogosKey] === 'object' && !Array.isArray(body[gameLogosKey])
      ? body[gameLogosKey]
      : {}
    const allowedHosts = new Set(
      String(process.env.AUTHORIZED_GAME_ICON_HOSTS || 'cdn.cloudflare.steamstatic.com,shared.akamai.steamstatic.com,images.igdb.com')
        .split(',')
        .map((host) => host.trim().toLowerCase())
        .filter(Boolean),
    )
    patch.game_logo_urls = Object.fromEntries(
      Object.entries(rawLogos)
        .slice(0, 20)
        .map(([game, rawUrl]) => {
          const key = cleanText(game, 80)
          const url = cleanText(rawUrl, 700)
          try {
            const parsed = new URL(url)
            if (parsed.protocol !== 'https:' || !allowedHosts.has(parsed.hostname.toLowerCase())) return null
            return key ? [key, parsed.toString()] : null
          } catch {
            return null
          }
        })
        .filter(Boolean),
    )
  }

  const sectionOrderKey = ['profile_section_order', 'profileSectionOrder']
    .find((key) => Object.prototype.hasOwnProperty.call(body || {}, key))
  if (sectionOrderKey && body[sectionOrderKey] != null) {
    patch.profile_section_order = normalizeProfileSectionOrder(body[sectionOrderKey])
  }

  const sectionVisibilityKey = ['profile_section_visibility', 'profileSectionVisibility']
    .find((key) => Object.prototype.hasOwnProperty.call(body || {}, key))
  if (sectionVisibilityKey && body[sectionVisibilityKey] != null) {
    patch.profile_section_visibility = normalizeProfileSectionVisibility(body[sectionVisibilityKey])
  }

  if (Object.prototype.hasOwnProperty.call(body || {}, 'is_private') && body.is_private != null) {
    patch.is_private = Boolean(body.is_private)
  } else if (Object.prototype.hasOwnProperty.call(body || {}, 'isPrivate') && body.isPrivate != null) {
    patch.is_private = Boolean(body.isPrivate)
  }

  if (Object.prototype.hasOwnProperty.call(body || {}, 'favorites_public') && body.favorites_public != null) {
    patch.favorites_public = Boolean(body.favorites_public)
  } else if (Object.prototype.hasOwnProperty.call(body || {}, 'favoritesPublic') && body.favoritesPublic != null) {
    patch.favorites_public = Boolean(body.favoritesPublic)
  }

  const breakModeKey = ['break_mode', 'breakMode']
    .find((key) => Object.prototype.hasOwnProperty.call(body || {}, key))
  if (breakModeKey && body[breakModeKey] != null) {
    const breakMode = cleanText(body[breakModeKey], 20).toLowerCase()
    if (new Set(['off', 'mute', 'hidden']).has(breakMode)) patch.break_mode = breakMode
  }

  if (body?.remove_avatar === true || body?.removeAvatar === true) {
    patch.avatar_url = ''
    patch.avatar_media_type = ''
    patch.avatar_duration_ms = 0
  }
  if (body?.remove_cover === true || body?.removeCover === true) {
    patch.cover_url = ''
  }

  const appearanceFields = [
    ['profile_theme', ['profile_theme', 'profileTheme'], PROFILE_THEME_IDS, 40],
    ['profile_effect', ['profile_effect', 'profileEffect'], PROFILE_EFFECT_IDS, 40],
  ]
  for (const [targetKey, sourceKeys, allowedValues, maxLength] of appearanceFields) {
    const sourceKey = sourceKeys.find((key) => Object.prototype.hasOwnProperty.call(body || {}, key))
    if (!sourceKey || body[sourceKey] == null) continue
    const value = cleanText(body[sourceKey], maxLength).toLowerCase()
    if (allowedValues.has(value)) patch[targetKey] = value
  }

  const welcomeKey = ['profile_welcome', 'profileWelcome']
    .find((key) => Object.prototype.hasOwnProperty.call(body || {}, key))
  if (welcomeKey && body[welcomeKey] != null) {
    patch.profile_welcome = cleanText(body[welcomeKey], 120)
  }

  if (Object.prototype.hasOwnProperty.call(body || {}, 'avatar_video_muted') && body.avatar_video_muted != null) {
    patch.avatar_video_muted = Boolean(body.avatar_video_muted)
  } else if (Object.prototype.hasOwnProperty.call(body || {}, 'avatarVideoMuted') && body.avatarVideoMuted != null) {
    patch.avatar_video_muted = Boolean(body.avatarVideoMuted)
  }

  const avatarDurationKey = ['avatar_duration_ms', 'avatarDurationMs']
    .find((key) => Object.prototype.hasOwnProperty.call(body || {}, key))
  if (avatarDurationKey && body[avatarDurationKey] != null) {
    const avatarDurationMs = Number(body[avatarDurationKey])
    if (Number.isFinite(avatarDurationMs) && avatarDurationMs >= 0) {
      patch.avatar_duration_ms = Math.min(2_000, Math.round(avatarDurationMs))
    }
  }

  if (patch.display_name && !patch.full_name) {
    patch.full_name = patch.display_name
  }

  return patch
}

function profileClearFields(profile) {
  const clearableFields = [
    'bio', 'country', 'city', 'status',
    'interests', 'hobbies', 'linked_games', 'game_logo_urls',
    'favorite_music_url', 'favorite_book', 'favorite_sport', 'career', 'availability_schedule',
    'profile_section_order', 'profile_section_visibility',
    'profile_welcome', 'avatar_url', 'avatar_media_type', 'cover_url',
  ]
  return clearableFields.filter((field) => {
    if (!Object.prototype.hasOwnProperty.call(profile || {}, field)) return false
    const value = profile[field]
    if (value == null) return true
    if (typeof value === 'string') return value.trim() === ''
    if (Array.isArray(value)) return value.length === 0
    return typeof value === 'object' && Object.keys(value).length === 0
  })
}

function isOwnedBy(row, identity) {
  return rowMatchesAny(row, ['user_id', 'owner_id', 'author_id', 'creator_id', 'profile_id', 'id'], identity.ids)
}

function countRows(rows, predicate) {
  return rows.reduce((total, row) => total + (predicate(row) ? 1 : 0), 0)
}

const POST_REACTION_TYPES = new Set(['LIKE', 'LOVE', 'HAHA', 'WOW', 'SAD', 'ANGRY', 'CARE', 'FIRE'])

function buildPostReactionState(rows, identity) {
  const latestByPostAndUser = new Map()

  for (const row of rows) {
    const postId = postIdOf(row)
    const userId = normalizeId(textOf(row, ['user_id', 'profile_id', 'owner_id'], ''))
    if (!postId || !userId) continue
    const requestedType = cleanText(row?.reaction_type || row?.reactionType || 'LIKE', 40).toUpperCase()
    const reactionType = POST_REACTION_TYPES.has(requestedType) ? requestedType : 'LIKE'
    latestByPostAndUser.set(`${postId}:${userId}`, {
      row,
      postId,
      userId,
      reactionType,
    })
  }

  const counts = new Map()
  const totals = new Map()
  const mine = new Map()
  const items = new Map()
  for (const snapshot of latestByPostAndUser.values()) {
    const postCounts = counts.get(snapshot.postId) || {}
    postCounts[snapshot.reactionType] = (postCounts[snapshot.reactionType] || 0) + 1
    counts.set(snapshot.postId, postCounts)
    totals.set(snapshot.postId, (totals.get(snapshot.postId) || 0) + 1)
    if (rowMatchesAny(snapshot.row, ['user_id', 'profile_id', 'owner_id'], identity.ids)) {
      mine.set(snapshot.postId, snapshot.reactionType)
    }
    const postItems = items.get(snapshot.postId) || []
    postItems.push(snapshot)
    items.set(snapshot.postId, postItems)
  }

  return { counts, totals, mine, items }
}

function enrichPost(
  row,
  profilesById,
  likeCounts,
  commentCounts,
  likedPostIds,
  reactionCounts = new Map(),
  myReactions = new Map(),
) {
  const authorId = normalizeId(textOf(row, ['user_id', 'owner_id', 'author_id', 'creator_id'], ''))
  const profile = authorId ? profilesById.get(authorId) : null
  const postId = textOf(row, ['id'], '')
  const mediaUrls = normalizePostMediaUrls(row)
  const countsForPost = reactionCounts.get(postId) || {}
  const reactionTotal = Object.values(countsForPost).reduce((total, count) => total + Number(count || 0), 0)
  const myReaction = myReactions.get(postId) || null

  return {
    ...row,
    user_id: authorId || textOf(row, ['user_id'], ''),
    user_name: textOf(row, ['user_name', 'display_name'], '') || profileName(profile, 'Usuario'),
    user_profile_image: profileAvatar(profile) || textOf(row, ['user_profile_image', 'avatar_url'], ''),
    media_urls: mediaUrls,
    media_url: textOf(row, ['media_url'], '') || mediaUrls[0] || '',
    visibility: textOf(row, ['visibility'], 'public'),
    like_count: reactionTotal > 0
      ? reactionTotal
      : likeCounts.has(postId)
        ? likeCounts.get(postId)
        : numberOf(row, ['like_count', 'likes_count'], 0),
    comment_count: commentCounts.has(postId)
      ? commentCounts.get(postId)
      : numberOf(row, ['comment_count', 'comments_count'], 0),
    liked_by_me: Boolean(myReaction) || likedPostIds.has(postId),
    my_reaction: myReaction,
    reaction_counts: countsForPost,
  }
}

function postIdOf(row) {
  return normalizeId(textOf(row, ['post_id', 'id'], ''))
}

function postOwnerId(row) {
  return normalizeId(textOf(row, ['user_id', 'owner_id', 'author_id', 'creator_id'], ''))
}

function canMutateLocalPost(row, identity) {
  return Boolean(row?.local_only && rowMatchesAny(row, ['user_id', 'owner_id', 'author_id', 'creator_id'], identity.ids))
}

function parsePublicStorageObjectUrl(rawUrl, allowedBuckets = []) {
  const value = String(rawUrl || '').trim()
  if (!value) return null
  try {
    const parsed = new URL(value)
    if (!new Set(['http:', 'https:']).has(parsed.protocol)) return null
    const marker = '/storage/v1/object/public/'
    const markerIndex = parsed.pathname.indexOf(marker)
    if (markerIndex < 0) return null
    const segments = parsed.pathname
      .slice(markerIndex + marker.length)
      .split('/')
      .filter(Boolean)
      .map((segment) => decodeURIComponent(segment))
    const bucket = String(segments.shift() || '').trim().toLowerCase()
    const objectPath = segments.join('/').trim()
    const allowed = new Set(allowedBuckets.map((entry) => String(entry || '').trim().toLowerCase()).filter(Boolean))
    if (!bucket || !objectPath || (allowed.size > 0 && !allowed.has(bucket))) return null
    if (objectPath.split('/').some((segment) => !segment || segment === '.' || segment === '..')) return null
    return { bucket, objectPath }
  } catch {
    return null
  }
}

const profileSectionIds = ['welcome', 'music', 'interests', 'hobbies', 'career', 'games', 'sports', 'books', 'availability']

function normalizeProfileSectionOrder(value) {
  const values = Array.isArray(value) ? value : []
  const allowed = new Set(profileSectionIds)
  const unique = [...new Set(values.map((entry) => cleanText(entry, 32).toLowerCase()).filter((entry) => allowed.has(entry)))]
  return [...unique, ...profileSectionIds.filter((entry) => !unique.includes(entry))]
}

function normalizeProfileSectionVisibility(value) {
  const source = value && typeof value === 'object' && !Array.isArray(value) ? value : {}
  return Object.fromEntries(profileSectionIds.map((section) => {
    const raw = cleanText(source[section], 20).toLowerCase()
    const normalized = raw === 'connections' ? 'followers' : raw
    return [section, new Set(['everyone', 'followers', 'only_me']).has(normalized) ? normalized : 'everyone']
  }))
}

function profileForViewer(profile, { viewerIsTarget = false, isFollowing = false } = {}) {
  const visible = { ...profile }
  const visibility = normalizeProfileSectionVisibility(profile?.profile_section_visibility)
  const canSee = (section) => viewerIsTarget || visibility[section] === 'everyone' || (visibility[section] === 'followers' && isFollowing)
  if (!canSee('welcome')) visible.profile_welcome = ''
  if (!canSee('music')) visible.favorite_music_url = ''
  if (!canSee('interests')) visible.interests = []
  if (!canSee('hobbies')) visible.hobbies = []
  if (!canSee('career')) visible.career = ''
  if (!canSee('games')) {
    visible.linked_games = []
    visible.game_logo_urls = {}
  }
  if (!canSee('sports')) visible.favorite_sport = ''
  if (!canSee('books')) visible.favorite_book = ''
  if (!canSee('availability')) visible.availability_schedule = ''
  return visible
}

function profileSearchCard(profile) {
  const visible = profileForViewer(profile)
  return {
    id: visible.id,
    user_id: visible.user_id,
    profile_id: visible.profile_id,
    supabase_user_id: visible.supabase_user_id,
    display_name: visible.display_name,
    full_name: visible.full_name,
    username: visible.username,
    bio: visible.bio,
    avatar_url: visible.avatar_url,
    avatar_media_type: visible.avatar_media_type,
    avatar_video_muted: visible.avatar_video_muted,
    avatar_duration_ms: visible.avatar_duration_ms,
    country: visible.country,
    city: visible.city,
    status: visible.status,
    is_verified: visible.is_verified,
    badges: visible.badges,
    profile_frame: visible.profile_frame,
    is_private: visible.is_private,
    break_mode: visible.break_mode,
    profile_visibility: visible.profile_visibility,
    is_online: visible.is_online,
    last_seen: visible.last_seen,
    followers_count: numberOf(visible, ['followers_count', 'follower_count'], 0),
    following_count: numberOf(visible, ['following_count'], 0),
    post_count: numberOf(visible, ['post_count', 'posts_count'], 0),
    updated_at: visible.updated_at,
  }
}

function rankPublicProfiles({ profiles, follows, posts, metric = 'followers', limit = 100 }) {
  const cleanMetric = new Set(['followers', 'following', 'posts']).has(metric) ? metric : 'followers'
  const boundedLimit = Math.max(1, Math.min(Number(limit) || 100, 250))
  const profilesByKey = new Map()
  const keyByAlias = new Map()
  const counters = new Map()

  for (const profile of profiles || []) {
    const aliases = profileAliasSet(profile)
    const key = normalizeId(profile?.id) || Array.from(aliases)[0]
    if (!key) continue
    profilesByKey.set(key, profile)
    counters.set(key, { followers: 0, following: 0, posts: 0 })
    for (const alias of aliases) keyByAlias.set(alias, key)
  }

  const seenFollows = new Set()
  for (const row of follows || []) {
    const followerAlias = normalizeId(textOf(row, ['follower_id', 'user_id'], ''))
    const followingAlias = normalizeId(textOf(row, ['following_id', 'target_user_id'], ''))
    const followerKey = keyByAlias.get(followerAlias)
    const followingKey = keyByAlias.get(followingAlias)
    if (!followerKey || !followingKey || followerKey === followingKey) continue
    const pairKey = `${followerKey}:${followingKey}`
    if (seenFollows.has(pairKey)) continue
    seenFollows.add(pairKey)
    counters.get(followerKey).following += 1
    counters.get(followingKey).followers += 1
  }

  const seenPosts = new Set()
  for (const row of posts || []) {
    const ownerAlias = normalizeId(textOf(row, ['user_id', 'owner_id', 'author_id', 'creator_id', 'profile_id'], ''))
    const ownerKey = keyByAlias.get(ownerAlias)
    if (!ownerKey) continue
    const postId = normalizeId(textOf(row, ['id', 'post_id'], '')) || `${ownerKey}:${seenPosts.size}`
    if (seenPosts.has(postId)) continue
    seenPosts.add(postId)
    counters.get(ownerKey).posts += 1
  }

  return Array.from(profilesByKey.entries())
    .map(([key, profile]) => {
      const count = counters.get(key)
      return profileSearchCard({
        ...profile,
        followers_count: count.followers,
        following_count: count.following,
        post_count: count.posts,
      })
    })
    .filter((profile) => textOf(profile, ['break_mode', 'breakMode'], 'off').toLowerCase() !== 'hidden')
    .sort((a, b) => {
      const field = cleanMetric === 'posts' ? 'post_count' : `${cleanMetric}_count`
      const delta = numberOf(b, [field], 0) - numberOf(a, [field], 0)
      if (delta !== 0) return delta
      return toMillis(b.updated_at) - toMillis(a.updated_at)
    })
    .slice(0, boundedLimit)
}

async function currentUserOwnsStorageObject(pool, currentUserId, rawUrl, allowedBuckets) {
  const reference = parsePublicStorageObjectUrl(rawUrl, allowedBuckets)
  if (!reference) return false
  // La capa de storage antepone el owner-id al subir (name = "<ownerId>/<ruta>"), pero la URL
  // publica que envia el cliente a veces OMITE ese segmento. Se acepta tanto la ruta tal cual como
  // la forma prefijada con el owner-id, para no rechazar un objeto que SI pertenece al usuario.
  // Sigue exigiendo owner_id = usuario, asi que es imposible reclamar un objeto ajeno.
  const candidateNames = [reference.objectPath]
  if (!reference.objectPath.startsWith(`${currentUserId}/`)) {
    candidateNames.push(`${currentUserId}/${reference.objectPath}`)
  }
  const { rows } = await pool.query(
    `SELECT 1
     FROM fallback_storage_objects
     WHERE bucket_id = $1
       AND owner_id = $2::uuid
       AND name = ANY($3::text[])
     LIMIT 1`,
    [reference.bucket, currentUserId, candidateNames],
  )
  return Boolean(rows[0])
}

async function resolveOwnedStorageUrl(pool, currentUserId, rawUrl, allowedBuckets) {
  const reference = parsePublicStorageObjectUrl(rawUrl, allowedBuckets)
  if (!reference) return null
  const candidateNames = [reference.objectPath]
  if (!reference.objectPath.startsWith(`${currentUserId}/`)) {
    candidateNames.push(`${currentUserId}/${reference.objectPath}`)
  }
  const { rows } = await pool.query(
    `SELECT name FROM fallback_storage_objects
     WHERE bucket_id = $1 AND owner_id = $2::uuid AND name = ANY($3::text[])
     ORDER BY array_position($3::text[], name) LIMIT 1`,
    [reference.bucket, currentUserId, candidateNames],
  )
  if (!rows[0]) return null
  const actualName = String(rows[0].name)
  const raw = String(rawUrl).trim()
  if (actualName === reference.objectPath) return raw
  // El cliente omite el prefijo owner-id en la URL; se reescribe para apuntar al objeto REAL
  // (sin el prefijo, el servido de imagenes devuelve 404 y se ve la inicial por defecto).
  const marker = `/storage/v1/object/public/${reference.bucket}/`
  const idx = raw.indexOf(marker)
  if (idx < 0) return raw
  const base = raw.slice(0, idx + marker.length)
  const encoded = actualName.split('/').map((seg) => encodeURIComponent(seg)).join('/')
  return base + encoded
}

async function currentUserOwnsStorageObjects(pool, currentUserId, rawUrls, allowedBuckets) {
  const urls = [...new Set(asArray(rawUrls).map((value) => String(value || '').trim()).filter(Boolean))]
  if (urls.length === 0) return true
  const ownership = await Promise.all(
    urls.map((url) => currentUserOwnsStorageObject(pool, currentUserId, url, allowedBuckets)),
  )
  return ownership.every(Boolean)
}

async function currentUserMayUseReportEvidence(pool, currentUserId, rawUrl) {
  if (!String(rawUrl || '').trim()) return true
  // Keep owned legacy avatar evidence compatible while clients migrate to the dedicated bucket.
  return currentUserOwnsStorageObject(pool, currentUserId, rawUrl, ['report-evidence', 'avatars'])
}

const messageMediaBuckets = [
  'chat-media',
  'group-media',
  'community-media',
  'groups',
  'communities',
  'group-audio',
]

async function storageObjectExists(pool, rawUrl, allowedBuckets) {
  const reference = parsePublicStorageObjectUrl(rawUrl, allowedBuckets)
  if (!reference) return false
  const { rows } = await pool.query(
    `SELECT 1
     FROM fallback_storage_objects
     WHERE bucket_id = $1 AND name = $2
     LIMIT 1`,
    [reference.bucket, reference.objectPath],
  )
  return Boolean(rows[0])
}

function sameStorageObjectReference(firstUrl, secondUrl, allowedBuckets = messageMediaBuckets) {
  const first = parsePublicStorageObjectUrl(firstUrl, allowedBuckets)
  const second = parsePublicStorageObjectUrl(secondUrl, allowedBuckets)
  return Boolean(
    first &&
    second &&
    first.bucket === second.bucket &&
    first.objectPath === second.objectPath,
  )
}

async function forwardedMessageMediaIsAccessible(pool, user, identity, sourceMessageId, mediaUrl) {
  const sourceId = normalizeId(sourceMessageId)
  if (!sourceId || !parsePublicStorageObjectUrl(mediaUrl, messageMediaBuckets)) return false

  const { rows } = await pool.query(
    `SELECT 'chat'::text AS kind, chat_id AS scope_id, media_url
     FROM fallback_messages
     WHERE lower(id::text) = $1
     UNION ALL
     SELECT room_kind AS kind, room_id AS scope_id, media_url
     FROM fallback_room_messages
     WHERE lower(id::text) = $1
     LIMIT 1`,
    [sourceId],
  )
  let source = rows[0]

  if (!source) {
    const importedSources = [
      ...(await importedRows(pool, 'messages', 10_000)).map((row) => ({
        ...row,
        kind: 'chat',
        scope_id: textOf(row, ['chat_id'], ''),
      })),
      ...(await importedRows(pool, 'group_messages', 10_000)).map((row) => ({
        ...row,
        kind: 'group',
        scope_id: textOf(row, ['group_id'], ''),
      })),
      ...(await importedRows(pool, 'community_messages', 10_000)).map((row) => ({
        ...row,
        kind: 'community',
        scope_id: textOf(row, ['community_id'], ''),
      })),
    ]
    source = importedSources.find((row) => normalizeId(textOf(row, ['id'], '')) === sourceId)
  }

  const sourceMediaUrl = textOf(source || {}, ['media_url', 'file_url', 'attachment_url'], '')
  if (!source || !sameStorageObjectReference(sourceMediaUrl, mediaUrl)) return false

  const kind = normalizeId(textOf(source, ['kind', 'room_kind'], ''))
  const scopeId = normalizeId(textOf(source, ['scope_id', 'chat_id', 'group_id', 'community_id', 'room_id'], ''))
  if (!scopeId) return false
  if (kind === 'chat') {
    return (await privateChatAccessContext(pool, scopeId, identity)).hasAccess
  }
  if (kind === 'group' || kind === 'community') {
    return Boolean(await loadCollectionRoomPayload(pool, user, kind, scopeId))
  }
  return false
}

async function currentUserMayReferenceMessageMedia(
  pool,
  user,
  identity,
  { mediaUrl, contentType, allowedOwnedBuckets, isForwarded = false, forwardedFromMessageId = null },
) {
  if (!mediaUrl) return true
  if (await currentUserOwnsStorageObject(pool, user.id, mediaUrl, allowedOwnedBuckets)) return true
  if (
    normalizeId(contentType) === 'sticker' &&
    await storageObjectExists(pool, mediaUrl, ['chat-media'])
  ) {
    return true
  }
  if (!isForwarded) return false
  return forwardedMessageMediaIsAccessible(pool, user, identity, forwardedFromMessageId, mediaUrl)
}

async function visibleVideoForIdentity(pool, videoId, identity) {
  const cleanVideoId = normalizeId(videoId)
  if (!cleanVideoId) return null
  const [importedVideos, fallbackVideos, deletedIds] = await Promise.all([
    importedRows(pool, 'videos', 5000),
    localVideos(pool, 5000),
    localVideoDeletionIds(pool, [cleanVideoId]),
  ])
  if (deletedIds.has(cleanVideoId)) return null
  const row = [...fallbackVideos, ...importedVideos]
    .find((entry) => normalizeId(textOf(entry, ['id'], '')) === cleanVideoId)
  if (!row) return null
  const isPublic = boolOf(row, ['is_public', 'isPublic'], true)
  const isOwner = rowMatchesAny(row, ['user_id', 'userId', 'profile_id', 'owner_id', 'creator_id'], identity.ids)
  return isPublic || isOwner ? row : null
}

async function localVideoFavorites(pool, videoIds, limit = 10_000) {
  const ids = Array.from(cleanSet(videoIds))
  if (ids.length === 0) return []
  const { rows } = await pool.query(
    `SELECT video_id, user_id, created_at
     FROM fallback_video_favorites
     WHERE lower(video_id) = ANY($1::text[])
     ORDER BY created_at DESC
     LIMIT $2`,
    [ids, Math.max(1, Math.min(Number(limit) || 10_000, 20_000))],
  )
  return rows.map(normalizeImportedRow)
}

async function localVideoCommentReactions(pool, commentIds, identity) {
  const ids = Array.from(cleanSet(commentIds))
  if (ids.length === 0) return new Map()
  const { rows } = await pool.query(
    `SELECT lower(comment_id) AS comment_id, reaction_type, user_id
     FROM fallback_video_comment_reactions
     WHERE lower(comment_id) = ANY($1::text[])`,
    [ids],
  )
  const result = new Map()
  for (const row of rows) {
    const state = result.get(row.comment_id) || { reactions: {}, myReaction: null }
    state.reactions[row.reaction_type] = (state.reactions[row.reaction_type] || 0) + 1
    if (identity && identity.ids.has(normalizeId(row.user_id))) state.myReaction = row.reaction_type
    result.set(row.comment_id, state)
  }
  return result
}

function canMutatePost(row, identity) {
  return Boolean(rowMatchesAny(row, ['user_id', 'owner_id', 'author_id', 'creator_id', 'profile_id'], identity.ids))
}

function countPostInteractions(rows, identity, postIdKeys = ['post_id']) {
  const counts = new Map()
  const mine = new Set()

  for (const row of rows) {
    const postId = normalizeId(textOf(row, postIdKeys, ''))
    if (!postId) continue
    counts.set(postId, (counts.get(postId) || 0) + 1)
    if (rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids)) {
      mine.add(postId)
    }
  }

  return { counts, mine }
}

function enrichPostComment(row, profilesById, identity = null) {
  const userId = normalizeId(textOf(row, ['user_id', 'profile_id', 'author_id'], ''))
  const profile = userId ? profilesById.get(userId) : null
  const createdAt = textOf(row, ['created_at', 'updated_at'], new Date().toISOString())

  return {
    ...row,
    id: textOf(row, ['id'], `${textOf(row, ['post_id'], 'post')}-${createdAt}-${userId}`),
    post_id: normalizeId(textOf(row, ['post_id'], '')),
    user_id: userId,
    user_name: profileName(profile, textOf(row, ['user_name', 'display_name', 'author_name'], userId || 'Usuario')),
    user_avatar_url: profileAvatar(profile) || textOf(row, ['user_avatar_url', 'avatar_url', 'user_profile_image'], ''),
    content: textOf(row, ['content', 'text', 'body'], ''),
    is_mine: identity ? rowMatchesAny(row, ['user_id', 'profile_id', 'author_id', 'owner_id', 'creator_id'], identity.ids) : false,
    created_at: createdAt,
    updated_at: textOf(row, ['updated_at', 'created_at'], createdAt),
  }
}

async function loadPostDetailPayload(pool, user, rawPostId) {
  const postId = normalizeId(rawPostId)
  if (!postId) return null

  const identity = userIdentity(user)
  const [importedProfiles, localProfiles, importedPosts, fallbackPosts, importedLikes, importedReactions, localReactions, importedComments, localComments, importedShares, localShares] =
    await Promise.all([
      importedRows(pool, 'profiles', 5000),
      fallbackUserProfileRows(pool, 5000),
      importedRows(pool, 'posts', 10_000),
      localPosts(pool, 10_000),
      importedRows(pool, 'post_likes', 50_000),
      importedRows(pool, 'post_reactions', 50_000),
      localPostReactions(pool, [postId], 50_000),
      importedRows(pool, 'post_comments', 50_000),
      localPostComments(pool, [postId], 50_000),
      importedRows(pool, 'shares', 50_000),
      localPostShares(pool, [postId], 50_000),
    ])

  const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
  const posts = [...importedPosts, ...fallbackPosts]
  const post = posts.find((row) => normalizeId(textOf(row, ['id'], '')) === postId)
  if (!post) return null

  const relevantLikes = [
    ...importedLikes.filter((row) => postIdOf(row) === postId),
    ...importedReactions.filter((row) => postIdOf(row) === postId),
    ...localReactions,
  ]
  const relevantComments = [
    ...importedComments.filter((row) => postIdOf(row) === postId),
    ...localComments,
  ]
  const commentIds = relevantComments.map((row) => normalizeId(textOf(row, ['id'], ''))).filter(Boolean)
  const commentReactionRows = commentIds.length > 0
    ? (await pool.query(
      `SELECT comment_id, user_id, reaction_type
       FROM fallback_post_comment_reactions
       WHERE lower(comment_id) = ANY($1::text[])`,
      [commentIds],
    )).rows
    : []
  const commentReactionCounts = new Map()
  const myCommentReactions = new Map()
  for (const reaction of commentReactionRows) {
    const commentId = normalizeId(reaction.comment_id)
    const type = String(reaction.reaction_type || '').toUpperCase()
    if (!commentReactionCounts.has(commentId)) commentReactionCounts.set(commentId, { LIKE: 0, DISLIKE: 0 })
    const counts = commentReactionCounts.get(commentId)
    if (type === 'LIKE' || type === 'DISLIKE') counts[type] += 1
    if (rowMatchesAny(reaction, ['user_id'], identity.ids)) myCommentReactions.set(commentId, type)
  }
  const relevantShares = [
    ...importedShares.filter((row) => postIdOf(row) === postId),
    ...localShares,
  ]

  const { counts: likeCounts, mine: likedPostIds } = countPostInteractions(relevantLikes, identity)
  const reactionState = buildPostReactionState(relevantLikes, identity)
  const commentCounts = new Map([[postId, relevantComments.length]])
  const { counts: shareCounts, mine: sharedPostIds } = countPostInteractions(relevantShares, identity)
  const enrichedPost = enrichPost(
    post,
    profilesById,
    likeCounts,
    commentCounts,
    likedPostIds,
    reactionState.counts,
    reactionState.mine,
  )

  return {
    post: {
      ...enrichedPost,
      share_count: numberOf(enrichedPost, ['share_count', 'shares_count'], shareCounts.get(postId) || 0),
      shared_by_me: sharedPostIds.has(postId),
    },
    comments: relevantComments
      .map((row) => {
        const enriched = enrichPostComment(row, profilesById, identity)
        const commentId = normalizeId(enriched.id)
        return {
          ...enriched,
          reaction_counts: commentReactionCounts.get(commentId) || { LIKE: 0, DISLIKE: 0 },
          my_reaction: myCommentReactions.get(commentId) || null,
        }
      })
      .sort((a, b) => toMillis(textOf(a, ['created_at'], '')) - toMillis(textOf(b, ['created_at'], ''))),
  }
}

function collectionConfig(kind) {
  const cleanKind = String(kind || '').trim().toLowerCase()
  if (cleanKind === 'group' || cleanKind === 'groups') {
    return {
      kind: 'group',
      roomTable: 'groups',
      memberTable: 'group_members',
      messageTable: 'group_messages',
      roomKey: 'group_id',
      titleFallback: 'Grupo',
      openPathPrefix: '/app/messages/group/',
    }
  }
  if (cleanKind === 'community' || cleanKind === 'communities') {
    return {
      kind: 'community',
      roomTable: 'communities',
      memberTable: 'community_members',
      messageTable: 'community_messages',
      roomKey: 'community_id',
      titleFallback: 'Comunidad',
      openPathPrefix: '/app/messages/community/',
    }
  }
  return null
}

function messageCollectionFromRequest(req) {
  const explicit = req.params?.collection
  if (explicit) return explicit
  const match = String(req.path || '').match(/\/v1\/data\/messages\/([^/]+)/)
  return match?.[1] || ''
}

function memberUserId(row) {
  return normalizeId(textOf(row, ['user_id', 'profile_id', 'member_id'], ''))
}

function normalizeCollectionRole(role, { isCreator = false } = {}) {
  if (isCreator) return 'owner'
  const cleanRole = String(role || '').trim().toLowerCase()
  if (cleanRole === 'owner' || cleanRole === 'creator') return 'owner'
  if (cleanRole === 'admin') return 'admin'
  if (cleanRole === 'moderator' || cleanRole === 'mod') return 'moderator'
  return 'member'
}

function buildPublicSemanticSearchCorpus({ profiles = [], posts = [], videos = [], maximum = 800 } = {}) {
  const boundedMaximum = Math.max(1, Math.min(Number(maximum) || 800, 800))
  const profilesById = indexProfiles(profiles)
  const hiddenOwnerIds = new Set()
  for (const profile of profiles) {
    if (textOf(profile, ['break_mode', 'breakMode'], 'off').toLowerCase() !== 'hidden') continue
    profileAliasSet(profile).forEach((alias) => hiddenOwnerIds.add(alias))
  }

  const postDocuments = mergeRowsById(posts)
    .filter((row) => textOf(row, ['visibility'], 'public').toLowerCase() === 'public')
    .filter((row) => !hiddenOwnerIds.has(postOwnerId(row)))
    .map((row) => {
      const id = normalizeId(textOf(row, ['id', 'post_id'], ''))
      const authorId = postOwnerId(row)
      const author = profilesById.get(authorId) || {}
      const content = cleanText(textOf(row, ['content', 'caption', 'description'], ''), 800)
      const tags = arrayOf(row, ['tags', 'hashtags'])
      const mediaUrls = normalizePostMediaUrls(row)
      return {
        searchId: `post:${id}`,
        id,
        type: 'post',
        title: cleanText(content, 120) || 'Publicación',
        text: content,
        tags,
        excerpt: cleanText(content, 240),
        thumbnail_url: mediaUrls[0] || '',
        author_id: authorId,
        author_name: profileName(author, textOf(row, ['user_name', 'display_name'], 'Usuario')),
        author_avatar_url: profileAvatar(author) || textOf(row, ['user_profile_image', 'avatar_url'], ''),
        created_at: textOf(row, ['created_at', 'updated_at'], ''),
      }
    })
    .filter((document) => document.id && (document.text || document.tags.length > 0))

  const videoDocuments = mergeRowsById(videos)
    .filter((row) => boolOf(row, ['is_public', 'isPublic'], true))
    .filter((row) => !hiddenOwnerIds.has(normalizeId(textOf(row, ['user_id', 'owner_id', 'author_id'], ''))))
    .map((row) => {
      const id = normalizeId(textOf(row, ['id', 'video_id'], ''))
      const authorId = normalizeId(textOf(row, ['user_id', 'owner_id', 'author_id'], ''))
      const author = profilesById.get(authorId) || {}
      const title = cleanText(textOf(row, ['title', 'caption'], ''), 200)
      const description = cleanText(textOf(row, ['description', 'content'], ''), 800)
      const tags = arrayOf(row, ['tags', 'hashtags'])
      return {
        searchId: `video:${id}`,
        id,
        type: 'video',
        title: title || 'Video',
        text: [title, description].filter(Boolean).join(' '),
        tags,
        excerpt: cleanText(description || title, 240),
        thumbnail_url: textOf(row, ['thumbnail_url', 'thumbnailUrl', 'poster_url', 'cover_url'], ''),
        author_id: authorId,
        author_name: profileName(author, textOf(row, ['user_name', 'display_name'], 'Usuario')),
        author_avatar_url: profileAvatar(author) || textOf(row, ['user_profile_image', 'avatar_url'], ''),
        created_at: textOf(row, ['created_at', 'updated_at'], ''),
      }
    })
    .filter((document) => document.id && (document.text || document.tags.length > 0))

  return [...postDocuments, ...videoDocuments]
    .sort((left, right) => toMillis(right.created_at) - toMillis(left.created_at))
    .slice(0, boundedMaximum)
}

function isCollectionAdminRole(role, { isCreator = false } = {}) {
  if (isCreator) return true
  return ['owner', 'admin'].includes(normalizeCollectionRole(role))
}

function collectionRoleCapabilities(role, { isCreator = false } = {}) {
  const normalizedRole = normalizeCollectionRole(role, { isCreator })
  const isOwner = normalizedRole === 'owner'
  const isAdmin = normalizedRole === 'admin'
  const isModerator = normalizedRole === 'moderator'
  return {
    role: normalizedRole,
    label: normalizedRole === 'owner'
      ? 'Propietario'
      : normalizedRole === 'admin'
        ? 'Administrador'
        : normalizedRole === 'moderator'
          ? 'Moderador'
          : 'Miembro',
    canPost: true,
    canModerateMessages: isOwner || isAdmin || isModerator,
    canManageMembers: isOwner || isAdmin,
    canManageRoles: isOwner || isAdmin,
    canManageChannels: isOwner || isAdmin,
    canManageEvents: isOwner || isAdmin,
    canEditCommunity: isOwner || isAdmin,
    canPauseChannels: isOwner || isAdmin || isModerator,
  }
}

function normalizeCommunityChannelType(type) {
  const normalized = String(type || '').trim().toLowerCase()
  return ['text', 'media', 'voice'].includes(normalized) ? normalized : 'text'
}

function communityChannelPayload(row, { canManage = false, canPause = false } = {}) {
  const normalizedRow = normalizeImportedRow(row)
  const pausedUntil = textOf(normalizedRow, ['paused_until', 'pausedUntil'], null)
  const pausedUntilMillis = pausedUntil ? new Date(pausedUntil).getTime() : 0
  return {
    id: textOf(normalizedRow, ['id'], ''),
    communityId: textOf(normalizedRow, ['community_id', 'communityId'], ''),
    name: textOf(normalizedRow, ['name'], 'General'),
    description: textOf(normalizedRow, ['description'], null),
    type: normalizeCommunityChannelType(textOf(normalizedRow, ['channel_type', 'type'], 'text')),
    position: numberOf(normalizedRow, ['position'], 0),
    isDefault: boolOf(normalizedRow, ['is_default', 'isDefault'], false),
    pausedUntil,
    pauseReason: textOf(normalizedRow, ['pause_reason', 'pauseReason'], null),
    isPaused: Number.isFinite(pausedUntilMillis) && pausedUntilMillis > Date.now(),
    canManage,
    canPause,
  }
}

function communityMemberProfilePayload(row, { communityId = '', userId = '' } = {}) {
  const normalizedRow = normalizeImportedRow(row)
  return {
    communityId: textOf(normalizedRow, ['community_id', 'communityId'], communityId),
    userId: textOf(normalizedRow, ['user_id', 'userId'], userId),
    alias: textOf(normalizedRow, ['alias'], null),
    bio: textOf(normalizedRow, ['bio'], null),
    distinctive: textOf(normalizedRow, ['distinctive'], null),
    updatedAt: textOf(normalizedRow, ['updated_at', 'updatedAt'], null),
  }
}

function normalizeCommunityAttendanceStatus(status) {
  return String(status || '').trim().toLowerCase() === 'going' ? 'going' : 'not_going'
}

function communityEventPayload(
  row,
  {
    attendanceCount = 0,
    currentAttendance = null,
    reminderMinutes = null,
    reminderSentAt = null,
    canManage = false,
  } = {},
) {
  const normalizedRow = normalizeImportedRow(row)
  const startsAt = textOf(normalizedRow, ['starts_at', 'startsAt'], '')
  const startsAtMillis = new Date(startsAt).getTime()
  const reminderAtMillis = currentAttendance === 'going'
    && Number.isFinite(startsAtMillis)
    && Number.isFinite(Number(reminderMinutes))
    ? startsAtMillis - Number(reminderMinutes) * 60_000
    : Number.NaN
  const reminderAt = Number.isFinite(reminderAtMillis)
    ? new Date(reminderAtMillis).toISOString()
    : null
  return {
    id: textOf(normalizedRow, ['id'], ''),
    communityId: textOf(normalizedRow, ['community_id', 'communityId'], ''),
    title: textOf(normalizedRow, ['title'], 'Evento'),
    description: textOf(normalizedRow, ['description'], null),
    startsAt,
    endsAt: textOf(normalizedRow, ['ends_at', 'endsAt'], null),
    location: textOf(normalizedRow, ['location'], null),
    createdBy: textOf(normalizedRow, ['created_by', 'createdBy'], ''),
    isCancelled: boolOf(normalizedRow, ['is_cancelled', 'isCancelled'], false),
    attendanceCount: Math.max(0, Number(attendanceCount) || 0),
    currentAttendance,
    reminderMinutes: Number.isFinite(Number(reminderMinutes)) ? Number(reminderMinutes) : null,
    reminderAt,
    reminderSentAt: reminderSentAt ? parseCell(reminderSentAt) : null,
    canManage,
    createdAt: textOf(normalizedRow, ['created_at', 'createdAt'], null),
    updatedAt: textOf(normalizedRow, ['updated_at', 'updatedAt'], null),
  }
}

async function ensureDefaultCommunityChannel(pool, roomId, actorId = '') {
  const normalizedRoomId = normalizeId(roomId)
  if (!normalizedRoomId) return null
  await pool.query(
    `INSERT INTO fallback_community_channels(
       community_id, name, description, channel_type, position, is_default, created_by
     )
     SELECT $1, 'General', 'Conversación principal de la comunidad', 'text', 0, TRUE, $2
     WHERE NOT EXISTS (
       SELECT 1
       FROM fallback_community_channels
       WHERE lower(community_id) = $1 AND is_default = TRUE AND is_deleted = FALSE
     )
     ON CONFLICT DO NOTHING`,
    [normalizedRoomId, normalizeId(actorId)],
  )
  const { rows } = await pool.query(
    `SELECT *
     FROM fallback_community_channels
     WHERE lower(community_id) = $1 AND is_default = TRUE AND is_deleted = FALSE
     ORDER BY created_at ASC
     LIMIT 1`,
    [normalizedRoomId],
  )
  return rows[0] || null
}

function roomIdOf(row, key) {
  return normalizeId(textOf(row, [key, 'room_id'], ''))
}

function deduplicateMembers(members, roomKey) {
  const map = new Map()
  for (const row of members) {
    const roomId = roomIdOf(row, roomKey)
    const userId = memberUserId(row)
    if (roomId && userId) map.set(`${roomId}_${userId}`, row)
  }
  return Array.from(map.values()).filter((row) => !boolOf(row, ['is_deleted', 'deleted'], false))
}

function collectionRoomTitle(row, fallback) {
  return textOf(row, ['name', 'title', 'group_name', 'community_name', 'readable_id'], fallback)
}

function collectionRoomAvatar(row) {
  return textOf(row, ['avatar_url', 'cover_url', 'image_url', 'group_image', 'community_image', 'image'], '')
}

function enrichCollectionMessage(row, config, profilesById) {
  const senderId = normalizeId(textOf(row, ['sender_id', 'user_id', 'profile_id', 'author_id'], ''))
  const profile = senderId ? profilesById.get(senderId) : null
  const roomId = textOf(row, [config.roomKey, 'room_id'], '')
  const createdAt = textOf(row, ['created_at', 'updated_at'], new Date().toISOString())
  return {
    ...row,
    id: textOf(row, ['id'], `${config.kind}-${roomId}-${createdAt}-${senderId}`),
    [config.roomKey]: roomId,
    sender_id: senderId,
    content: textOf(row, ['content', 'text', 'message'], ''),
    content_type: textOf(row, ['content_type', 'message_type', 'type'], 'text'),
    media_url: textOf(row, ['media_url', 'file_url', 'attachment_url', 'url'], ''),
    media_size: numberOf(row, ['media_size', 'size', 'size_bytes'], 0),
    status: textOf(row, ['status'], 'sent'),
    reply_to_id: textOf(row, ['reply_to_id'], ''),
    poll_id: textOf(row, ['poll_id'], ''),
    created_at: createdAt,
    updated_at: textOf(row, ['updated_at', 'created_at'], createdAt),
    sender_name: textOf(row, ['sender_name', 'user_name', 'display_name'], '') || profileName(profile, senderId || 'Usuario'),
    sender_image: textOf(row, ['sender_image', 'user_avatar_url', 'avatar_url'], '') || profileAvatar(profile),
    reactions: row.reactions && typeof row.reactions === 'object' ? row.reactions : {},
    deleted_by: Array.isArray(row.deleted_by) ? row.deleted_by : [],
    deleted_for_all: boolOf(row, ['deleted_for_all'], false),
    is_edited: boolOf(row, ['is_edited'], false),
    is_forwarded: boolOf(row, ['is_forwarded'], false),
    forwarded_from_message_id: textOf(row, ['forwarded_from_message_id'], ''),
    forwarded_from_user_id: textOf(row, ['forwarded_from_user_id'], ''),
  }
}

async function loadCollectionRoomPayload(pool, user, kind, roomId, { allowPrivatePreview = false } = {}) {
  const config = collectionConfig(kind)
  const cleanRoomId = normalizeId(roomId)
  if (!config || !cleanRoomId) return null

  const identity = userIdentity(user)
  const [importedProfiles, localProfiles, importedRooms, importedMembers, importedMessages, localRooms, localMembers, deletedLocalRoomIds] = await Promise.all([
    importedRows(pool, 'profiles', 5000),
    fallbackUserProfileRows(pool, 5000),
    importedRows(pool, config.roomTable, 5000),
    importedRows(pool, config.memberTable, 10_000),
    importedRows(pool, config.messageTable, 10_000),
    localCollectionRooms(pool, config.kind, 5000),
    localCollectionMembers(pool, config.kind, 10_000),
    localDeletedCollectionRoomIds(pool, config.kind),
  ])
  const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
  const rooms = mergeRowsById([...importedRooms, ...localRooms])
    .filter((row) => {
      const id = normalizeId(textOf(row, ['id'], ''))
      return id && !deletedLocalRoomIds.has(id) && !boolOf(row, ['is_deleted', 'deleted'], false)
    })
  const members = deduplicateMembers([...importedMembers, ...localMembers], config.roomKey)
    .filter((row) => !deletedLocalRoomIds.has(roomIdOf(row, config.roomKey)))
  const room = rooms.find((row) => normalizeId(textOf(row, ['id'], '')) === cleanRoomId)
  if (!room) return null

  const roomMembers = members.filter((row) => roomIdOf(row, config.roomKey) === cleanRoomId)
  const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
  const isMember = roomMembers.some((row) => identity.ids.has(memberUserId(row)))
  const isPublicRoom = boolOf(room, ['is_public', 'public'], config.kind === 'community')
  const hasAccess = isCreator || isMember || isPublicRoom || roomMembers.length === 0 || allowPrivatePreview
  if (!hasAccess) return null

  const importedRoomMessages = importedMessages.filter((row) => roomIdOf(row, config.roomKey) === cleanRoomId)
  const messages = [
    ...importedRoomMessages,
    ...(await localCollectionMessages(pool, config.kind, cleanRoomId)),
  ]
    .map((row) => enrichCollectionMessage(row, config, profilesById))
    .filter((message) => messageVisibleTo(message, identity))
    .sort((a, b) => toMillis(textOf(a, ['created_at'], '')) - toMillis(textOf(b, ['created_at'], '')))

  return {
    chat: room,
    room,
    title: collectionRoomTitle(room, config.titleFallback),
    otherParticipant: null,
    participants: roomMembers.map((row) => {
      const id = memberUserId(row)
      const profile = id ? profilesById.get(id) : null
      const isRoomCreator = normalizeId(textOf(room, ['creator_id', 'owner_id', 'user_id'], '')) === id
      return {
        id,
        name: textOf(row, ['user_name', 'display_name', 'name'], '') || profileName(profile, id || 'Usuario'),
        avatarUrl: textOf(row, ['user_avatar_url', 'avatar_url'], '') || profileAvatar(profile),
        role: normalizeCollectionRole(textOf(row, ['role'], 'member'), { isCreator: isRoomCreator }),
        joinedAt: textOf(row, ['joined_at', 'created_at'], ''),
        restUntil: textOf(row, ['rest_until', 'restUntil'], '') || null,
      }
    }),
    messages,
    settings: {
      isMuted: false,
      isPinned: false,
      isArchived: false,
      isFavorite: false,
      translateEnabled: false,
      targetLang: 'es',
    },
  }
}

function voiceRoomId(row) {
  return normalizeId(textOf(row, ['id', 'room_id', 'voice_room_id'], ''))
}

function voiceRoomReadableId(row) {
  return normalizeId(textOf(row, ['readable_id', 'readableId', 'agora_channel_name', 'agoraChannelName'], ''))
}

function voiceRoomMatches(row, cleanRoomId) {
  if (!cleanRoomId) return false
  return voiceRoomId(row) === cleanRoomId || voiceRoomReadableId(row) === cleanRoomId
}

function voiceRoomMode(row) {
  return textOf(row, ['room_mode', 'roomMode', 'mode'], 'casual').trim().toLowerCase() === 'stage'
    ? 'stage'
    : 'casual'
}

function voiceParticipantRole(row, roomMode = 'casual') {
  const role = textOf(row, ['role', 'stage_role', 'stageRole'], '').trim().toLowerCase()
  if (role === 'moderator' || role === 'speaker' || role === 'audience') return role
  return roomMode === 'stage' ? 'audience' : 'speaker'
}

function voiceParticipantRoomId(row) {
  return normalizeId(textOf(row, ['room_id', 'voice_room_id'], ''))
}

async function localVoiceParticipants(pool, roomId = '') {
  const cleanRoomId = normalizeId(roomId)
  return cacheGetOrSet(
    `localVoiceParticipants:${cleanRoomId || 'all'}`,
    2_000,
    async () => {
      const { rows } = await pool.query(
        `SELECT
            id::text,
            room_id,
            user_id,
            user_name,
            user_avatar_url,
            socket_id,
            is_muted,
            is_speaking,
            role,
            hand_raised,
            joined_at,
            updated_at
         FROM fallback_voice_room_participants
         WHERE ($1::text = '' OR lower(room_id) = $1)
           AND updated_at >= NOW() - INTERVAL '2 minutes'
         ORDER BY joined_at ASC`,
        [cleanRoomId],
      )
      return rows.map(normalizeImportedRow)
    },
  )
}

async function localVoiceRooms(pool, limit = 1000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 1000, 10_000))
  return cacheGetOrSet(
    `localVoiceRooms:${safeLimit}`,
    2_000,
    async () => {
      const { rows } = await pool.query(
        `SELECT
            id::text,
            name,
            description,
            creator_id,
            max_participants,
            is_public,
            is_active,
            agora_channel_name,
            readable_id,
            room_mode,
            created_at,
            updated_at
         FROM fallback_voice_rooms
         WHERE is_active = TRUE
         ORDER BY updated_at DESC, created_at DESC
         LIMIT $1`,
        [safeLimit],
      )
      return rows.map(normalizeImportedRow)
    },
  )
}

function mapVoiceParticipant(row, profilesById) {
  const userId = normalizeId(textOf(row, ['user_id', 'profile_id'], ''))
  const profile = userId ? profilesById.get(userId) : null
  const roomId = textOf(row, ['room_id', 'voice_room_id'], '')
  return {
    ...row,
    id: textOf(row, ['id'], userId || `${roomId}-participant` || `${Date.now()}`),
    room_id: roomId,
    user_id: userId,
    socket_id: textOf(row, ['socket_id'], ''),
    user_name: textOf(row, ['user_name', 'display_name', 'name'], '') || profileName(profile, userId || 'Usuario'),
    user_avatar_url: textOf(row, ['user_avatar_url', 'avatar_url'], '') || profileAvatar(profile),
    is_muted: boolOf(row, ['is_muted'], false),
    is_speaking: boolOf(row, ['is_speaking'], false),
    role: voiceParticipantRole(row),
    hand_raised: boolOf(row, ['hand_raised'], false),
    joined_at: textOf(row, ['joined_at', 'created_at'], ''),
  }
}

function mapVoiceRoom(row, profilesById, participantCount, participantRows = []) {
  const creatorId = normalizeId(textOf(row, ['creator_id', 'owner_id', 'user_id'], ''))
  const creatorProfile = creatorId ? profilesById.get(creatorId) : null
  const id = voiceRoomId(row)
  // Presence is live state. Historical CSV counters must never appear as listeners.
  const count = Math.max(0, Number(participantCount || 0))
  const mode = voiceRoomMode(row)
  const speakerIds = participantRows
    .filter((participant) => ['moderator', 'speaker'].includes(voiceParticipantRole(participant, mode)))
    .map((participant) => normalizeId(textOf(participant, ['user_id', 'profile_id'], '')))
    .filter(Boolean)
  const raisedHandCount = participantRows.filter((participant) => boolOf(participant, ['hand_raised'], false)).length
  return {
    ...row,
    id,
    name: textOf(row, ['name', 'title', 'readable_id'], 'Sala de voz'),
    title: textOf(row, ['title', 'name', 'readable_id'], 'Sala de voz'),
    topic: textOf(row, ['topic', 'description'], 'Sala disponible'),
    description: textOf(row, ['description', 'topic'], ''),
    creator_id: creatorId,
    creator_name: textOf(row, ['creator_name', 'owner_name'], '') || profileName(creatorProfile, creatorId || 'Creador'),
    creator_avatar_url: textOf(row, ['creator_avatar_url', 'avatar_url'], '') || profileAvatar(creatorProfile),
    current_participants: count,
    participant_count: count,
    max_participants: Math.max(1, numberOf(row, ['max_participants', 'capacity'], 10)),
    room_mode: mode,
    speaker_ids: speakerIds,
    raised_hand_count: raisedHandCount,
    updated_at: textOf(row, ['updated_at', 'created_at'], ''),
    created_at: textOf(row, ['created_at'], textOf(row, ['updated_at'], '')),
  }
}

async function publishVoiceRoomsLiveEvent(payload) {
  try {
    const { publishRealtimeEvent } = require('./realtime-publish')
    await publishRealtimeEvent('voice-rooms:live', {
      event: 'voice-room',
      ...(payload || {}),
    })
  } catch (error) {
    console.warn('[voice-rooms-live]', error?.message || error)
  }
}

async function ensureLocalDataTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_chats (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      participants TEXT[] NOT NULL DEFAULT '{}'::text[],
      participant_key TEXT UNIQUE,
      is_group BOOLEAN NOT NULL DEFAULT FALSE,
      group_name TEXT,
      last_message TEXT,
      last_message_at TIMESTAMPTZ,
      last_message_sender_id TEXT,
      last_message_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS participants TEXT[] NOT NULL DEFAULT '{}'::text[]`,
    `ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS participant_key TEXT UNIQUE`,
    `ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS is_group BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS group_name TEXT`,
    `ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS last_message TEXT`,
    `ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS last_message_sender_id TEXT`,
    `ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS last_message_id TEXT`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_chats_participant_key
     ON fallback_chats(participant_key)
     WHERE participant_key IS NOT NULL`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_chats_updated
     ON fallback_chats(updated_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_chat_participants (
      chat_id UUID NOT NULL REFERENCES fallback_chats(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_read_at TIMESTAMPTZ,
      is_muted BOOLEAN NOT NULL DEFAULT FALSE,
      is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
      is_archived BOOLEAN NOT NULL DEFAULT FALSE,
      is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
      cleared_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (chat_id, user_id)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_chat_participants ADD COLUMN IF NOT EXISTS is_muted BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_chat_participants ADD COLUMN IF NOT EXISTS is_pinned BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_chat_participants ADD COLUMN IF NOT EXISTS is_archived BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_chat_participants ADD COLUMN IF NOT EXISTS is_favorite BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_chat_participants ADD COLUMN IF NOT EXISTS last_read_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_chat_participants ADD COLUMN IF NOT EXISTS cleared_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_chat_participants ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_chat_participants ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_chat_participants_user
     ON fallback_chat_participants(lower(user_id), joined_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_chat_hidden (
      chat_id UUID NOT NULL,
      user_id TEXT NOT NULL,
      hidden_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (chat_id, user_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_chat_hidden_user
     ON fallback_chat_hidden(lower(user_id), hidden_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_nicknames (
      user_id TEXT NOT NULL,
      target_user_id TEXT NOT NULL,
      nickname TEXT NOT NULL DEFAULT '',
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, target_user_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_nicknames_user
     ON fallback_nicknames(lower(user_id), lower(target_user_id))`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_user_blocks (
      blocker_id TEXT NOT NULL,
      blocked_id TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (blocker_id, blocked_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_user_blocks_blocked
     ON fallback_user_blocks(lower(blocked_id), lower(blocker_id))`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_messages (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      chat_id TEXT NOT NULL,
      sender_id TEXT NOT NULL,
      content TEXT NOT NULL DEFAULT '',
      content_type TEXT NOT NULL DEFAULT 'text',
      media_url TEXT,
      thumbnail_url TEXT,
      media_size BIGINT NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'sent',
      reply_to_id TEXT,
      poll_id TEXT,
      delivered_at TIMESTAMPTZ,
      read_at TIMESTAMPTZ,
      reactions JSONB NOT NULL DEFAULT '{}'::jsonb,
      deleted_by JSONB NOT NULL DEFAULT '[]'::jsonb,
      deleted_for_all BOOLEAN NOT NULL DEFAULT FALSE,
      is_edited BOOLEAN NOT NULL DEFAULT FALSE,
      is_forwarded BOOLEAN NOT NULL DEFAULT FALSE,
      forwarded_from_message_id TEXT,
      forwarded_from_user_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS media_size BIGINT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS thumbnail_url TEXT`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS poll_id TEXT`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS reactions JSONB NOT NULL DEFAULT '{}'::jsonb`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS deleted_by JSONB NOT NULL DEFAULT '[]'::jsonb`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS deleted_for_all BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS is_edited BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS is_forwarded BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS forwarded_from_message_id TEXT`,
    `ALTER TABLE fallback_messages ADD COLUMN IF NOT EXISTS forwarded_from_user_id TEXT`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_messages_chat_created
     ON fallback_messages(lower(chat_id), created_at)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_posts (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      user_name TEXT NOT NULL DEFAULT 'Usuario',
      user_profile_image TEXT,
      content TEXT NOT NULL DEFAULT '',
      media_url TEXT,
      media_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
      media_type TEXT,
      visibility TEXT NOT NULL DEFAULT 'public',
      remix_allowed BOOLEAN NOT NULL DEFAULT FALSE,
      remix_audience TEXT NOT NULL DEFAULT 'public',
      remix_revoked_at TIMESTAMPTZ,
      remix_source_post_id TEXT,
      remix_root_post_id TEXT,
      remix_source_user_id TEXT,
      remix_source_user_name TEXT,
      remix_depth INTEGER NOT NULL DEFAULT 0,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'public'`,
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_allowed BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_audience TEXT NOT NULL DEFAULT 'public'`,
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_revoked_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_source_post_id TEXT`,
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_root_post_id TEXT`,
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_source_user_id TEXT`,
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_source_user_name TEXT`,
    `ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS remix_depth INTEGER NOT NULL DEFAULT 0`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_posts_created
     ON fallback_posts(created_at DESC)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_posts_remix_source
     ON fallback_posts(lower(remix_source_post_id))
     WHERE remix_source_post_id IS NOT NULL`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_posts_remix_root
     ON fallback_posts(lower(remix_root_post_id))
     WHERE remix_root_post_id IS NOT NULL`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_saved_collections (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      name TEXT NOT NULL,
      is_default BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_saved_collections_user_name
     ON fallback_saved_collections(lower(user_id), lower(name))`,
  )
  await pool.query(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_saved_collections_default
     ON fallback_saved_collections(lower(user_id))
     WHERE is_default = TRUE`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_saved_posts (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      collection_id UUID NOT NULL REFERENCES fallback_saved_collections(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      post_id TEXT NOT NULL,
      tags JSONB NOT NULL DEFAULT '[]'::jsonb,
      note TEXT NOT NULL DEFAULT '',
      saved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (collection_id, post_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_saved_posts_user
     ON fallback_saved_posts(lower(user_id), saved_at DESC)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_saved_posts_post
     ON fallback_saved_posts(lower(post_id))`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_post_comments (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      post_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      content TEXT NOT NULL DEFAULT '',
      parent_id TEXT,
      media_url TEXT,
      media_type TEXT,
      is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS post_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS content TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS parent_id TEXT`,
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS media_url TEXT`,
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS media_type TEXT`,
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_post_comments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_post_comments_post_created
     ON fallback_post_comments(lower(post_id), is_deleted, created_at)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_post_comment_reactions (
      comment_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      reaction_type TEXT NOT NULL CHECK (reaction_type IN ('LIKE', 'DISLIKE')),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (comment_id, user_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_post_comment_reactions_comment
     ON fallback_post_comment_reactions(lower(comment_id))`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_post_reactions (
      post_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      reaction_type TEXT NOT NULL DEFAULT 'LIKE',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (post_id, user_id)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_post_reactions ADD COLUMN IF NOT EXISTS post_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_post_reactions ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_post_reactions ADD COLUMN IF NOT EXISTS reaction_type TEXT NOT NULL DEFAULT 'LIKE'`,
    `ALTER TABLE fallback_post_reactions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_post_reactions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_post_reactions_post
     ON fallback_post_reactions(lower(post_id), updated_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_shares (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      post_id TEXT NOT NULL,
      content_type TEXT NOT NULL DEFAULT 'post',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (user_id, post_id, content_type)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_shares ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_shares ADD COLUMN IF NOT EXISTS post_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_shares ADD COLUMN IF NOT EXISTS content_type TEXT NOT NULL DEFAULT 'post'`,
    `ALTER TABLE fallback_shares ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_shares_post
     ON fallback_shares(content_type, lower(post_id), created_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_follows (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      follower_id TEXT NOT NULL,
      following_id TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (follower_id, following_id)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_follows ADD COLUMN IF NOT EXISTS follower_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_follows ADD COLUMN IF NOT EXISTS following_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_follows ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(`
    DELETE FROM fallback_follows
    WHERE COALESCE(NULLIF(trim(follower_id), ''), '') = ''
       OR COALESCE(NULLIF(trim(following_id), ''), '') = ''
       OR lower(follower_id) = lower(following_id)
  `)
  await pool.query(`
    DELETE FROM fallback_follows a
    USING fallback_follows b
    WHERE a.ctid < b.ctid
      AND lower(a.follower_id) = lower(b.follower_id)
      AND lower(a.following_id) = lower(b.following_id)
  `)
  await pool.query(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_follows_unique_pair
     ON fallback_follows(lower(follower_id), lower(following_id))`,
  )
  await pool.query(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_follows_unique_pair_raw
     ON fallback_follows(follower_id, following_id)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_follows_follower
     ON fallback_follows(lower(follower_id), created_at DESC)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_follows_following
     ON fallback_follows(lower(following_id), created_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_reports (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      reporter_id TEXT NOT NULL,
      reporter_name TEXT NOT NULL DEFAULT 'Usuario',
      reported_user_id TEXT NOT NULL,
      reported_user_name TEXT NOT NULL DEFAULT 'Usuario',
      reason TEXT NOT NULL,
      description TEXT,
      evidence_url TEXT,
      content_type TEXT NOT NULL DEFAULT 'user',
      content_id TEXT,
      status TEXT NOT NULL DEFAULT 'pendiente',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS reporter_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS reporter_name TEXT NOT NULL DEFAULT 'Usuario'`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS reported_user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS reported_user_name TEXT NOT NULL DEFAULT 'Usuario'`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS reason TEXT NOT NULL DEFAULT 'otro'`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS description TEXT`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS evidence_url TEXT`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS content_type TEXT NOT NULL DEFAULT 'user'`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS content_id TEXT`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'pendiente'`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_reports ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_reports_reporter
     ON fallback_reports(lower(reporter_id), created_at DESC)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_reports_reported
     ON fallback_reports(lower(reported_user_id), created_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_notifications (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      recipient_id TEXT NOT NULL,
      actor_id TEXT,
      actor_name TEXT,
      actor_avatar_url TEXT,
      type TEXT NOT NULL DEFAULT 'activity',
      target_id TEXT,
      target_type TEXT,
      content TEXT,
      is_read BOOLEAN NOT NULL DEFAULT FALSE,
      extra_data JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS recipient_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS actor_id TEXT`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS actor_name TEXT`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS actor_avatar_url TEXT`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'activity'`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS target_id TEXT`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS target_type TEXT`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS content TEXT`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS is_read BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS extra_data JSONB NOT NULL DEFAULT '{}'::jsonb`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_notifications ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_notifications_recipient
     ON fallback_notifications(lower(recipient_id), is_read, created_at DESC)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_notifications_target
     ON fallback_notifications(target_type, lower(target_id), created_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_fcm_tokens (
      user_id TEXT NOT NULL,
      token TEXT NOT NULL,
      platform TEXT NOT NULL DEFAULT 'android',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, token)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_fcm_tokens_user
     ON fallback_fcm_tokens(lower(user_id), updated_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_room_messages (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      room_kind TEXT NOT NULL CHECK (room_kind IN ('group', 'community')),
      room_id TEXT NOT NULL,
      channel_id TEXT,
      sender_id TEXT NOT NULL,
      content TEXT NOT NULL DEFAULT '',
      content_type TEXT NOT NULL DEFAULT 'text',
      media_url TEXT,
      media_size BIGINT NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'sent',
      delivered_at TIMESTAMPTZ,
      read_at TIMESTAMPTZ,
      reactions JSONB NOT NULL DEFAULT '{}'::jsonb,
      reply_to_id TEXT,
      poll_id TEXT,
      is_edited BOOLEAN NOT NULL DEFAULT FALSE,
      deleted_for_all BOOLEAN NOT NULL DEFAULT FALSE,
      deleted_by JSONB NOT NULL DEFAULT '[]'::jsonb,
      is_forwarded BOOLEAN NOT NULL DEFAULT FALSE,
      forwarded_from_message_id TEXT,
      forwarded_from_user_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS room_kind TEXT NOT NULL DEFAULT 'group'`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS room_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS channel_id TEXT`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS sender_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS content TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS content_type TEXT NOT NULL DEFAULT 'text'`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS media_url TEXT`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS media_size BIGINT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'sent'`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS reactions JSONB NOT NULL DEFAULT '{}'::jsonb`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS reply_to_id TEXT`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS poll_id TEXT`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS is_edited BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS deleted_for_all BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS deleted_by JSONB NOT NULL DEFAULT '[]'::jsonb`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS is_forwarded BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS forwarded_from_message_id TEXT`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS forwarded_from_user_id TEXT`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_room_messages_room_created
     ON fallback_room_messages(room_kind, lower(room_id), created_at)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_room_messages_channel_created
     ON fallback_room_messages(lower(channel_id), created_at)
     WHERE channel_id IS NOT NULL`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_community_channels (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      community_id TEXT NOT NULL,
      name TEXT NOT NULL,
      description TEXT,
      channel_type TEXT NOT NULL DEFAULT 'text'
        CHECK (channel_type IN ('text', 'media', 'voice')),
      position INTEGER NOT NULL DEFAULT 0,
      is_default BOOLEAN NOT NULL DEFAULT FALSE,
      paused_until TIMESTAMPTZ,
      pause_reason TEXT,
      paused_by TEXT,
      created_by TEXT NOT NULL DEFAULT '',
      is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS community_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS name TEXT NOT NULL DEFAULT 'Canal'`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS description TEXT`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS channel_type TEXT NOT NULL DEFAULT 'text'`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS position INTEGER NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS paused_until TIMESTAMPTZ`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS pause_reason TEXT`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS paused_by TEXT`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS created_by TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_community_channels ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_community_channels_default
     ON fallback_community_channels(lower(community_id))
     WHERE is_default = TRUE AND is_deleted = FALSE`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_community_channels_room_position
     ON fallback_community_channels(lower(community_id), is_deleted, position, created_at)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_community_member_profiles (
      community_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      alias TEXT,
      bio TEXT,
      distinctive TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (community_id, user_id)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_community_member_profiles ADD COLUMN IF NOT EXISTS community_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_community_member_profiles ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_community_member_profiles ADD COLUMN IF NOT EXISTS alias TEXT`,
    `ALTER TABLE fallback_community_member_profiles ADD COLUMN IF NOT EXISTS bio TEXT`,
    `ALTER TABLE fallback_community_member_profiles ADD COLUMN IF NOT EXISTS distinctive TEXT`,
    `ALTER TABLE fallback_community_member_profiles ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_community_member_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_community_member_profiles_room
     ON fallback_community_member_profiles(lower(community_id), lower(user_id))`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_community_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      community_id TEXT NOT NULL,
      title TEXT NOT NULL,
      description TEXT,
      starts_at TIMESTAMPTZ NOT NULL,
      ends_at TIMESTAMPTZ,
      location TEXT,
      created_by TEXT NOT NULL,
      is_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS community_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT 'Evento'`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS description TEXT`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS starts_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS ends_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS location TEXT`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS created_by TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS is_cancelled BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_community_events ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_community_events_room_start
     ON fallback_community_events(lower(community_id), is_cancelled, starts_at)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_community_event_attendees (
      event_id UUID NOT NULL,
      user_id TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'going'
        CHECK (status IN ('going', 'not_going')),
      reminder_minutes INTEGER,
      reminder_sent_at TIMESTAMPTZ,
      responded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (event_id, user_id)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_community_event_attendees ADD COLUMN IF NOT EXISTS event_id UUID`,
    `ALTER TABLE fallback_community_event_attendees ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_community_event_attendees ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'going'`,
    `ALTER TABLE fallback_community_event_attendees ADD COLUMN IF NOT EXISTS reminder_minutes INTEGER`,
    `ALTER TABLE fallback_community_event_attendees ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_community_event_attendees ADD COLUMN IF NOT EXISTS responded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_community_event_attendees ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_community_event_attendees_due
     ON fallback_community_event_attendees(status, reminder_sent_at, event_id)
     WHERE status = 'going' AND reminder_minutes IS NOT NULL AND reminder_sent_at IS NULL`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_collection_rooms (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      room_kind TEXT NOT NULL CHECK (room_kind IN ('group', 'community')),
      name TEXT NOT NULL,
      image TEXT,
      description TEXT,
      category TEXT,
      is_public BOOLEAN NOT NULL DEFAULT TRUE,
      creator_id TEXT NOT NULL,
      is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
      only_admins_can_talk BOOLEAN NOT NULL DEFAULT FALSE,
      daily_message_limit INTEGER NOT NULL DEFAULT 0,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS room_kind TEXT NOT NULL DEFAULT 'group'`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS name TEXT NOT NULL DEFAULT 'Sin nombre'`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS image TEXT`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS description TEXT`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS category TEXT`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT TRUE`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS creator_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS only_admins_can_talk BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS daily_message_limit INTEGER NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_collection_rooms ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_collection_rooms_kind_created
     ON fallback_collection_rooms(room_kind, is_deleted, created_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_collection_members (
      room_kind TEXT NOT NULL CHECK (room_kind IN ('group', 'community')),
      room_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      role TEXT NOT NULL DEFAULT 'member',
      joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
      rest_until TIMESTAMPTZ,
      PRIMARY KEY (room_kind, room_id, user_id)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS room_kind TEXT NOT NULL DEFAULT 'group'`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS room_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'member'`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS rest_until TIMESTAMPTZ`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_collection_members_user
     ON fallback_collection_members(room_kind, lower(user_id), lower(room_id))`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_collection_invites (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      room_kind TEXT NOT NULL CHECK (room_kind IN ('group', 'community')),
      room_id TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      created_by TEXT NOT NULL,
      expires_at TIMESTAMPTZ NOT NULL,
      max_uses INTEGER NOT NULL DEFAULT 100,
      use_count INTEGER NOT NULL DEFAULT 0,
      revoked_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_collection_invites_room
     ON fallback_collection_invites(room_kind, lower(room_id), expires_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_collection_read_states (
      room_kind TEXT NOT NULL CHECK (room_kind IN ('group', 'community')),
      room_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      last_read_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (room_kind, room_id, user_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_collection_read_states_user
     ON fallback_collection_read_states(room_kind, lower(user_id), lower(room_id))`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_voice_rooms (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      name TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      creator_id TEXT NOT NULL,
      max_participants INTEGER NOT NULL DEFAULT 50,
      is_public BOOLEAN NOT NULL DEFAULT TRUE,
      is_active BOOLEAN NOT NULL DEFAULT TRUE,
      agora_channel_name TEXT NOT NULL DEFAULT '',
      readable_id TEXT,
      room_mode TEXT NOT NULL DEFAULT 'casual',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS name TEXT NOT NULL DEFAULT 'Sala de voz'`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS creator_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS max_participants INTEGER NOT NULL DEFAULT 50`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT TRUE`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS agora_channel_name TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS readable_id TEXT`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS room_mode TEXT NOT NULL DEFAULT 'casual'`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_voice_rooms_active_created
     ON fallback_voice_rooms(is_active, created_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_voice_room_participants (
      id UUID NOT NULL DEFAULT gen_random_uuid(),
      room_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      user_name TEXT NOT NULL DEFAULT 'Usuario',
      user_avatar_url TEXT NOT NULL DEFAULT '',
      socket_id TEXT NOT NULL DEFAULT '',
      is_muted BOOLEAN NOT NULL DEFAULT FALSE,
      is_speaking BOOLEAN NOT NULL DEFAULT FALSE,
      role TEXT NOT NULL DEFAULT 'speaker',
      hand_raised BOOLEAN NOT NULL DEFAULT FALSE,
      joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (room_id, user_id)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS id UUID NOT NULL DEFAULT gen_random_uuid()`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS room_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS user_name TEXT NOT NULL DEFAULT 'Usuario'`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS user_avatar_url TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS socket_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS is_muted BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS is_speaking BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'speaker'`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS hand_raised BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_voice_room_participants ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(`
    DELETE FROM fallback_voice_room_participants a
    USING fallback_voice_room_participants b
    WHERE a.ctid < b.ctid
      AND lower(a.room_id) = lower(b.room_id)
      AND lower(a.user_id) = lower(b.user_id)
  `)
  await pool.query(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_voice_room_participants_room_user
     ON fallback_voice_room_participants(room_id, user_id)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_voice_room_participants_room
     ON fallback_voice_room_participants(lower(room_id), joined_at)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_profile_overrides (
      user_id UUID PRIMARY KEY REFERENCES fallback_users(id) ON DELETE CASCADE,
      profile JSONB NOT NULL DEFAULT '{}'::jsonb,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_videos (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      user_name TEXT NOT NULL DEFAULT 'Usuario',
      user_profile_image TEXT,
      title TEXT NOT NULL DEFAULT '',
      description TEXT NOT NULL DEFAULT '',
      video_url TEXT NOT NULL,
      cover_url TEXT,
      thumbnail_url TEXT,
      duration_seconds INT NOT NULL DEFAULT 0,
      trim_start_seconds INT NOT NULL DEFAULT 0,
      trim_end_seconds INT NOT NULL DEFAULT 0,
      views INT NOT NULL DEFAULT 0,
      like_count INT NOT NULL DEFAULT 0,
      comment_count INT NOT NULL DEFAULT 0,
      share_count INT NOT NULL DEFAULT 0,
      is_public BOOLEAN NOT NULL DEFAULT TRUE,
      tags JSONB NOT NULL DEFAULT '[]'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS user_name TEXT NOT NULL DEFAULT 'Usuario'`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS user_profile_image TEXT`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS video_url TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS cover_url TEXT`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS thumbnail_url TEXT`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS duration_seconds INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS trim_start_seconds INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS trim_end_seconds INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS views INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS like_count INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS comment_count INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS share_count INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT TRUE`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]'::jsonb`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_videos_created
     ON fallback_videos(created_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_metrics (
      video_id TEXT PRIMARY KEY,
      views_delta INT NOT NULL DEFAULT 0,
      share_delta INT NOT NULL DEFAULT 0,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_video_metrics ADD COLUMN IF NOT EXISTS views_delta INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_video_metrics ADD COLUMN IF NOT EXISTS share_delta INT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_video_metrics ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_deletions (
      video_id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_video_deletions ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_video_deletions ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_likes (
      video_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (video_id, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_collection_message_receipts (
      room_kind TEXT NOT NULL CHECK (room_kind IN ('group', 'community')),
      room_id TEXT NOT NULL,
      message_id UUID NOT NULL REFERENCES fallback_room_messages(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      delivered_at TIMESTAMPTZ,
      read_at TIMESTAMPTZ,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (room_kind, message_id, user_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_collection_message_receipts_room
     ON fallback_collection_message_receipts(room_kind, lower(room_id), lower(user_id), updated_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_favorites (
      video_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (video_id, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_views (
      video_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (video_id, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_comments (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      video_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      user_name TEXT NOT NULL DEFAULT 'Usuario',
      user_profile_image TEXT,
      content TEXT NOT NULL DEFAULT '',
      content_type TEXT NOT NULL DEFAULT 'text',
      media_url TEXT,
      parent_comment_id TEXT,
      is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS video_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS user_name TEXT NOT NULL DEFAULT 'Usuario'`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS user_profile_image TEXT`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS content TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS content_type TEXT NOT NULL DEFAULT 'text'`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS media_url TEXT`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS parent_comment_id TEXT`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_video_comments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_video_comments_video_created
     ON fallback_video_comments(lower(video_id), is_deleted, created_at)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_comment_reactions (
      comment_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      reaction_type TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (comment_id, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_comment_reports (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      comment_id TEXT NOT NULL,
      video_id TEXT NOT NULL DEFAULT '',
      reporter_id TEXT NOT NULL,
      reason TEXT NOT NULL,
      details TEXT NOT NULL DEFAULT '',
      status TEXT NOT NULL DEFAULT 'pending',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (comment_id, reporter_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_challenges_master (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      title TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      challenge_type TEXT NOT NULL DEFAULT 'mixed',
      difficulty TEXT NOT NULL DEFAULT 'normal',
      creator_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_active_challenges (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      challenge_id UUID NOT NULL REFERENCES fallback_challenges_master(id) ON DELETE CASCADE,
      expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '24 hours'),
      voting_expires_at TIMESTAMPTZ,
      status TEXT NOT NULL DEFAULT 'active',
      winner_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_challenge_submissions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      challenge_id TEXT NOT NULL,
      active_challenge_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      content_url TEXT,
      text_content TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (active_challenge_id, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_challenge_votes (
      submission_id TEXT NOT NULL,
      voter_id TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (submission_id, voter_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_challenge_submissions_active
     ON fallback_challenge_submissions(lower(active_challenge_id), created_at DESC)`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_challenge_votes_submission
     ON fallback_challenge_votes(lower(submission_id), created_at DESC)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_friends (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      sender_id TEXT NOT NULL,
      receiver_id TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (sender_id, receiver_id)
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_friends_receiver
     ON fallback_friends(lower(receiver_id), created_at DESC)`,
  )
  const { ensureMomentsTables } = require('./moments-routes')
  const { ensureAdminModerationTables } = require('./admin-moderation-routes')
  const { ensureLiveTables } = require('./live-routes')
  const { ensureProximityTables } = require('./proximity-routes')
  await ensureMomentsTables(pool)
  await ensureAdminModerationTables(pool)
  await ensureLiveTables(pool)
  await ensureProximityTables(pool)
  await ensureNotificationDigestTables(pool)
}

function registerDataRoutes(app, { pool, authRequired, appealAuthRequired, deleteExternalAuthUser }) {
  const { publishChatMessageEvent, publishRealtimeEvent } = require('./realtime-publish')
  const { registerMomentsRoutes } = require('./moments-routes')
  const { registerAdminModerationRoutes } = require('./admin-moderation-routes')
  const { registerLiveRoutes } = require('./live-routes')
  const { registerProximityRoutes } = require('./proximity-routes')
  registerMomentsRoutes(app, {
    pool,
    authRequired,
    getCurrentUser,
    userIdentity,
    createLocalNotification: createLocalNotificationBestEffort,
    currentUserOwnsStorageObject,
  })
  registerAdminModerationRoutes(app, {
    pool,
    authRequired,
    appealAuthRequired,
    getCurrentUser,
    userIdentity,
    deleteExternalAuthUser,
    invalidateProfileCache: invalidateImportedDataCache,
  })
  registerLiveRoutes(app, { pool, authRequired, getCurrentUser, userIdentity })
  registerProximityRoutes(app, { pool, authRequired, getCurrentUser, userIdentity })
  registerNotificationDigestRoutes(app, {
    pool,
    authRequired,
    getCurrentUser,
    userIdentity,
  })
  app.post('/v1/data/messages/translate', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const text = cleanText(req.body?.text, 1200)
      const target = cleanText(req.body?.target, 8).toLowerCase()
      const source = cleanText(req.body?.source, 8).toLowerCase() || 'auto'
      const allowedLanguages = new Set(['auto', 'es', 'en', 'pt', 'fr'])
      if (!text || !allowedLanguages.has(target) || !allowedLanguages.has(source)) {
        return res.status(400).json({ error: 'TRANSLATION_REQUEST_INVALID' })
      }
      if (source === target) return res.json({ translatedText: text, source, target })

      const translatorUrl = String(process.env.TRANSLATION_INTERNAL_URL || '').replace(/\/+$/, '')
      if (!translatorUrl) return res.status(503).json({ error: 'TRANSLATION_UNAVAILABLE' })
      const response = await fetch(`${translatorUrl}/translate`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ q: text, source, target, format: 'text' }),
        signal: AbortSignal.timeout(20_000),
      })
      const body = await response.json().catch(() => ({}))
      const translatedText = cleanText(body?.translatedText, 2400)
      if (!response.ok || !translatedText) {
        return res.status(503).json({ error: 'TRANSLATION_UNAVAILABLE' })
      }
      res.json({ translatedText, source: body?.detectedLanguage?.language || source, target })
    } catch (error) {
      if (error?.name === 'TimeoutError') return res.status(504).json({ error: 'TRANSLATION_TIMEOUT' })
      next(error)
    }
  })
  app.get('/v1/data/profile/me', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const [profiles, importedPosts, fallbackPosts, videos, chats, follows, importedReports, fallbackReports] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        importedRows(pool, 'posts', 5000),
        localPosts(pool, 5000),
        importedRows(pool, 'videos', 5000),
        importedRows(pool, 'chats', 5000),
        combinedFollows(pool),
        importedRows(pool, 'reports', 5000),
        localReports(pool, 5000),
      ])
      const posts = [...importedPosts, ...fallbackPosts]
      const reports = [...importedReports, ...fallbackReports]

      const profile =
        profiles.find((row) => rowMatchesAny(row, ['id', 'user_id', 'profile_id', 'supabase_user_id'], identity.ids)) ||
        profiles.find((row) => normalizeId(row.email) === identity.email) ||
        fallbackProfileRow(user)
      const overrideProfile = await localProfileOverride(pool, user.id)
      const mergedProfile = buildProfileForUser(user, profile, overrideProfile)

      const myPosts = sortByDateDesc(
        posts.filter((row) => isOwnedBy(row, identity)),
        ['created_at', 'updated_at'],
      ).slice(0, 30)

      const myReports = sortByDateDesc(
        reports.filter((row) => rowMatchesAny(row, ['reporter_id', 'user_id'], identity.ids)),
        ['created_at', 'updated_at'],
      ).slice(0, 12)

      res.setHeader('Cache-Control', 'private, no-store, max-age=0')
      res.setHeader('Pragma', 'no-cache')
      res.vary('Authorization')
      res.json({
        profile: mergedProfile,
        posts: myPosts,
        reports: myReports,
        stats: {
          posts: myPosts.length || countRows(posts, (row) => isOwnedBy(row, identity)),
          videos: countRows(videos, (row) => isOwnedBy(row, identity)),
          chats: countRows(chats, (row) => rowParticipantsContain(row, identity.ids)),
          followers: countRows(follows, (row) => rowMatchesAny(row, ['following_id', 'target_user_id'], identity.ids)),
          following: countRows(follows, (row) => rowMatchesAny(row, ['follower_id', 'user_id'], identity.ids)),
          reportsSent: myReports.length || countRows(reports, (row) => rowMatchesAny(row, ['reporter_id', 'user_id'], identity.ids)),
        },
      })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/profile/me', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profiles = await importedRows(pool, 'profiles', 5000)
      const importedProfile =
        profiles.find((row) => rowMatchesAny(row, ['id', 'user_id', 'profile_id', 'supabase_user_id'], identity.ids)) ||
        profiles.find((row) => normalizeId(row.email) === identity.email) ||
        fallbackProfileRow(user)

      const patch = profilePatchFromBody(req.body || {})
      if (Object.keys(patch).length === 0) {
        return res.status(400).json({ error: 'PROFILE_PATCH_EMPTY' })
      }

      // La propiedad del objeto de storage solo se valida cuando el avatar/portada CAMBIA de verdad.
      // El cliente reenvía el avatar_url/cover_url actual en cada guardado de perfil; si esa URL es
      // heredada (p. ej. migrada desde Supabase, sin fila en fallback_storage_objects) la validación
      // fallaba y bloqueaba TODO el guardado del perfil (PROFILE_AVATAR_NOT_OWNED) aunque el usuario
      // solo cambiara la bio u otros campos. Comparar contra el valor actual evita ese bloqueo sin
      // debilitar la seguridad: seguir es imposible poner un avatar/portada nuevo que no sea tuyo.
      const currentProfileOverride = await localProfileOverride(pool, user.id)
      const trimUrl = (value) => String(value || '').trim()
      const currentProfile = buildProfileForUser(user, importedProfile, currentProfileOverride)
      const currentAvatarUrl = trimUrl(currentProfile.avatar_url)
      const currentCoverUrl = trimUrl(currentProfile.cover_url)

      // Valida propiedad Y normaliza la URL al objeto REAL (con owner-id). Si el cliente manda la
      // URL sin el prefijo owner-id, se reescribe para que la foto SE PUEDA MOSTRAR.
      if (patch.avatar_url && trimUrl(patch.avatar_url) !== currentAvatarUrl) {
        const resolvedAvatar = await resolveOwnedStorageUrl(pool, user.id, patch.avatar_url, ['avatars'])
        if (!resolvedAvatar) {
          return res.status(403).json({ error: 'PROFILE_AVATAR_NOT_OWNED' })
        }
        patch.avatar_url = resolvedAvatar
      }
      if (patch.cover_url && trimUrl(patch.cover_url) !== currentCoverUrl) {
        const resolvedCover = await resolveOwnedStorageUrl(pool, user.id, patch.cover_url, ['avatars'])
        if (!resolvedCover) {
          return res.status(403).json({ error: 'PROFILE_COVER_NOT_OWNED' })
        }
        patch.cover_url = resolvedCover
      }

      patch.updated_at = new Date().toISOString()
      patch.__clear_fields = profileClearFields({ ...currentProfileOverride, ...patch })

      await pool.query(
        `INSERT INTO fallback_profile_overrides(user_id, profile, updated_at)
         VALUES ($1, $2::jsonb, NOW())
         ON CONFLICT (user_id)
         DO UPDATE SET
           profile = fallback_profile_overrides.profile || EXCLUDED.profile,
           updated_at = NOW()`,
        [user.id, JSON.stringify(patch)],
      )
      clearProfileCaches()

      const nextDisplayName = patch.display_name || patch.full_name || null
      const hasAvatarPatch = Object.prototype.hasOwnProperty.call(patch, 'avatar_url')
      const nextAvatarUrl = hasAvatarPatch ? patch.avatar_url : null
      await pool.query(
        `UPDATE fallback_users
         SET display_name = COALESCE($2, display_name),
             avatar_url = COALESCE($3, avatar_url)
         WHERE id = $1`,
        [user.id, nextDisplayName, nextAvatarUrl],
      )
      if (nextDisplayName || hasAvatarPatch) {
        await Promise.all([
          pool.query(
            `UPDATE fallback_videos
             SET user_name = COALESCE($2, user_name),
                 user_profile_image = COALESCE($3, user_profile_image),
                 updated_at = NOW()
             WHERE lower(user_id) = ANY($1::text[])`,
            [identity.values, nextDisplayName, nextAvatarUrl],
          ),
          pool.query(
            `UPDATE fallback_video_comments
             SET user_name = COALESCE($2, user_name),
                 user_profile_image = COALESCE($3, user_profile_image),
                 updated_at = NOW()
             WHERE lower(user_id) = ANY($1::text[])`,
            [identity.values, nextDisplayName, nextAvatarUrl],
          ),
          pool.query(
            `UPDATE fallback_posts
             SET user_name = COALESCE($2, user_name),
                 user_profile_image = COALESCE($3, user_profile_image),
                 updated_at = NOW()
             WHERE lower(user_id) = ANY($1::text[])`,
            [identity.values, nextDisplayName, nextAvatarUrl],
          ),
          pool.query(
            `UPDATE fallback_post_comments
             SET updated_at = NOW()
             WHERE lower(user_id) = ANY($1::text[])`,
            [identity.values],
          ),
          pool.query(
            `UPDATE fallback_notifications
             SET actor_name = COALESCE($2, actor_name),
                 actor_avatar_url = COALESCE($3, actor_avatar_url),
                 updated_at = NOW()
             WHERE lower(actor_id) = ANY($1::text[])`,
            [identity.values, nextDisplayName, nextAvatarUrl],
          ),
          pool.query(
            `UPDATE fallback_voice_room_participants
             SET user_name = COALESCE($2, user_name),
                 user_avatar_url = COALESCE($3, user_avatar_url)
             WHERE lower(user_id) = ANY($1::text[])`,
            [identity.values, nextDisplayName, nextAvatarUrl],
          ),
        ])
      }

      const refreshedUser = {
        ...user,
        display_name: nextDisplayName || user.display_name,
        avatar_url: hasAvatarPatch ? nextAvatarUrl : user.avatar_url,
      }
      const current = buildProfileForUser(refreshedUser, importedProfile, await localProfileOverride(pool, user.id))
      res.json({ profile: current, stats: {} })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/profile/login-emails', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const { rows } = await pool.query(
        `SELECT email, created_at
         FROM fallback_profile_login_emails
         WHERE user_id = $1
         ORDER BY created_at ASC`,
        [user.id],
      )
      res.json({ items: rows.map((row) => String(row.email || '').trim().toLowerCase()).filter(Boolean) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/profile/login-emails', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const email = String(req.body?.email || '').trim().toLowerCase()
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        return res.status(400).json({ error: 'EMAIL_INVALID' })
      }
      if (email === String(user.email || '').trim().toLowerCase()) {
        return res.status(409).json({ error: 'PRIMARY_EMAIL_NOT_ALLOWED' })
      }
      const existingOwner = await pool.query(
        `SELECT id FROM fallback_users WHERE lower(email) = $1 LIMIT 1`,
        [email],
      )
      if (existingOwner.rows.length > 0) return res.status(409).json({ error: 'EMAIL_ALREADY_IN_USE' })
      const count = await pool.query(
        `SELECT COUNT(*)::int AS total FROM fallback_profile_login_emails WHERE user_id = $1`,
        [user.id],
      )
      if ((Number(count.rows[0]?.total) || 0) >= 3) {
        return res.status(409).json({ error: 'MAX_ADDITIONAL_EMAILS_REACHED' })
      }
      await pool.query(
        `INSERT INTO fallback_profile_login_emails(user_id, email)
         VALUES ($1, $2)
         ON CONFLICT (user_id, email) DO NOTHING`,
        [user.id, email],
      )
      res.status(201).json({ ok: true, email })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'EMAIL_ALREADY_IN_USE' })
      next(error)
    }
  })

  app.delete('/v1/data/profile/login-emails/:email', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const email = String(req.params.email || '').trim().toLowerCase()
      await pool.query(
        `DELETE FROM fallback_profile_login_emails WHERE user_id = $1 AND lower(email) = $2`,
        [user.id, email],
      )
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/presence', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const isOnline = Boolean(req.body?.isOnline ?? req.body?.is_online ?? req.body?.online_status)
      const now = new Date().toISOString()
      const patch = {
        is_online: isOnline,
        last_seen: now,
        updated_at: now,
      }

      await pool.query(
        `INSERT INTO fallback_profile_overrides(user_id, profile, updated_at)
         VALUES ($1, $2::jsonb, NOW())
         ON CONFLICT (user_id)
         DO UPDATE SET
           profile = fallback_profile_overrides.profile || EXCLUDED.profile,
           updated_at = NOW()`,
        [user.id, JSON.stringify(patch)],
      )
      clearProfileCaches()

      await pool.query(
        `UPDATE fallback_users
         SET last_login_at = $2,
             updated_at = $2
         WHERE id = $1`,
        [user.id, now],
      )

      const refreshedUser = {
        ...user,
        last_login_at: now,
        updated_at: now,
      }
      const current = buildProfileForUser(refreshedUser, fallbackProfileRow(refreshedUser), await localProfileOverride(pool, user.id))
      res.json({
        ok: true,
        isOnline: current.is_online,
        lastSeen: current.last_seen,
        profile: current,
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/profiles/search', authRequired, async (req, res, next) => {
    try {
      const query = cleanText(req.query.q || req.query.query || '', 120).toLowerCase()
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 120, 250))
      const profiles = await allPublicProfiles(pool)
      const items = profiles
        .filter((profile) => textOf(profile, ['break_mode', 'breakMode'], 'off').toLowerCase() !== 'hidden')
        .filter((profile) => profileMatchesQuery(profile, query))
        .sort((a, b) => toMillis(b.updated_at || b.created_at) - toMillis(a.updated_at || a.created_at))
        .slice(0, limit)
        .map(profileSearchCard)
      setPrivateShortCache(res, 10)
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/profiles/ranking', authRequired, async (req, res, next) => {
    try {
      const metric = cleanText(req.query.metric || 'followers', 20).toLowerCase()
      if (!['followers', 'following', 'posts'].includes(metric)) {
        return res.status(400).json({ error: 'RANKING_METRIC_INVALID' })
      }
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 100, 250))
      const [profiles, follows, importedPosts, fallbackPosts] = await Promise.all([
        allPublicProfiles(pool),
        combinedFollows(pool),
        importedRows(pool, 'posts', 20_000),
        localPosts(pool, 20_000),
      ])
      const items = rankPublicProfiles({
        profiles,
        follows,
        posts: [...importedPosts, ...fallbackPosts],
        metric,
        limit,
      })
      setPrivateShortCache(res, 10)
      res.json({ items, metric, limit })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/profiles/:profileId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const profileId = normalizeId(req.params.profileId)
      if (!profileId) return res.status(400).json({ error: 'PROFILE_ID_REQUIRED' })

      const identity = userIdentity(user)
      const [profiles, importedPosts, fallbackPosts, follows, importedReports, fallbackReports] = await Promise.all([
        allPublicProfiles(pool),
        importedRows(pool, 'posts', 5000),
        localPosts(pool, 5000),
        combinedFollows(pool),
        importedRows(pool, 'reports', 5000),
        localReports(pool, 5000),
      ])
      const reportRows = [...importedReports, ...fallbackReports]
      let rawProfile = profiles.find((row) => profileAliasSet(row).has(profileId))
      if (!rawProfile) {
        const fallbackUser = await fallbackUserByProfileAlias(pool, profileId)
        if (fallbackUser) {
          rawProfile = buildProfileForUser(
            fallbackUser,
            fallbackProfileRow(fallbackUser),
            await localProfileOverride(pool, fallbackUser.id),
          )
          clearProfileCaches()
        }
      }
      if (!rawProfile) return res.status(404).json({ error: 'PROFILE_NOT_FOUND' })

      const targetIds = cleanSet([rawProfile.id, rawProfile.user_id, rawProfile.profile_id, rawProfile.supabase_user_id, profileId])
      const viewerIsTarget = Array.from(targetIds).some((id) => identity.ids.has(id))
      if (!viewerIsTarget && textOf(rawProfile, ['break_mode', 'breakMode'], 'off').toLowerCase() === 'hidden') {
        return res.status(404).json({ error: 'PROFILE_HIDDEN' })
      }
      const isFollowing = follows.some((row) => followMatches(row, identity.ids, targetIds))
      const profileVisibility = textOf(rawProfile, ['profile_visibility', 'profileVisibility'], 'everyone').toLowerCase()
      if (!viewerIsTarget && new Set(['nobody', 'only_me']).has(profileVisibility)) {
        return res.status(404).json({ error: 'PROFILE_HIDDEN' })
      }
      const profile = profileForViewer(rawProfile, { viewerIsTarget, isFollowing })
      const canSeePrivateContent = viewerIsTarget || isFollowing || !boolOf(rawProfile, ['is_private', 'private'], false)
      const canSeeProfileContent = canSeePrivateContent && (
        viewerIsTarget ||
        profileVisibility === 'everyone' ||
        profileVisibility === '' ||
        (profileVisibility === 'followers' && isFollowing)
      )
      const posts = (canSeeProfileContent ? [...importedPosts, ...fallbackPosts] : [])
        .filter((row) => rowMatchesAny(row, ['user_id', 'owner_id', 'author_id', 'creator_id', 'profile_id'], targetIds))
        .filter((row) => {
          const visibility = textOf(row, ['visibility'], 'public').toLowerCase()
          return viewerIsTarget || isFollowing || visibility === 'public' || visibility === ''
        })

      const postIds = posts.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const [fallbackLikes, fallbackComments, importedLikes, importedReactions, importedComments] = await Promise.all([
        localPostReactions(pool, postIds, 10_000),
        localPostComments(pool, postIds, 10_000),
        importedRows(pool, 'post_likes', 10_000),
        importedRows(pool, 'post_reactions', 10_000),
        importedRows(pool, 'post_comments', 10_000),
      ])
      const profilesById = indexProfiles(profiles)
      const likeCounts = new Map()
      const commentCounts = new Map()
      const likedPostIds = new Set()
      const allReactionRows = [...importedLikes, ...importedReactions, ...fallbackLikes]
      for (const row of allReactionRows) {
        const id = textOf(row, ['post_id'], '')
        if (!id || (postIds.length > 0 && !postIds.includes(id))) continue
        likeCounts.set(id, (likeCounts.get(id) || 0) + 1)
        if (rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids)) likedPostIds.add(id)
      }
      for (const row of [...importedComments, ...fallbackComments]) {
        const id = textOf(row, ['post_id'], '')
        if (!id || (postIds.length > 0 && !postIds.includes(id))) continue
        commentCounts.set(id, (commentCounts.get(id) || 0) + 1)
      }
      const reactionState = buildPostReactionState(allReactionRows, identity)

      setPrivateShortCache(res, 10)
      res.json({
        profile,
        posts: sortByDateDesc(posts, ['created_at', 'updated_at'])
          .slice(0, 30)
          .map((row) => enrichPost(
            row,
            profilesById,
            likeCounts,
            commentCounts,
            likedPostIds,
            reactionState.counts,
            reactionState.mine,
          )),
        isFollowing,
        stats: {
          posts: posts.length,
          followers: countRows(follows, (row) => followMatches(row, cleanSet([textOf(row, ['follower_id', 'user_id'], '')]), targetIds)),
          following: countRows(follows, (row) => followMatches(row, targetIds, cleanSet([textOf(row, ['following_id', 'target_user_id'], '')]))),
          reports: countRows(reportRows, (row) => rowMatchesAny(row, ['reported_user_id', 'target_user_id', 'user_id'], targetIds)),
        },
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/follows', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const tab = String(req.query.tab || 'following').toLowerCase() === 'followers' ? 'followers' : 'following'
      const identity = userIdentity(user)
      const requestedProfileId = normalizeId(req.query.profileId || req.query.profile_id || '')
      const [profiles, follows] = await Promise.all([allPublicProfiles(pool), combinedFollows(pool)])
      const profilesById = indexProfiles(profiles)
      const profile = requestedProfileId
        ? profiles.find((row) => profileAliasSet(row).has(requestedProfileId))
        : null
      const sourceIds = profile ? profileAliasSet(profile) : identity.ids
      const items = follows
        .filter((row) => rowMatchesAny(row, [tab === 'following' ? 'follower_id' : 'following_id'], sourceIds))
        .map((row) => {
          const targetId = normalizeId(textOf(row, [tab === 'following' ? 'following_id' : 'follower_id'], ''))
          const profile = targetId ? profilesById.get(targetId) : null
          return profile ? profileSearchCard(normalizeProfileForPublic(profile, targetId)) : null
        })
        .filter(Boolean)
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/profiles/:profileId/follow', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profileId = normalizeId(req.params.profileId)
      if (!profileId) return res.status(400).json({ error: 'PROFILE_ID_REQUIRED' })
      if (identity.ids.has(profileId)) return res.status(400).json({ error: 'CANNOT_FOLLOW_SELF' })

      await pool.query(
        `INSERT INTO fallback_follows(follower_id, following_id)
         VALUES ($1, $2)
         ON CONFLICT (follower_id, following_id) DO NOTHING`,
        [identity.displayUserId, profileId],
      )
      await createLocalNotificationBestEffort(pool, {
        recipientId: profileId,
        actorId: identity.displayUserId,
        actorName: user.display_name || user.email || 'Usuario',
        actorAvatarUrl: user.avatar_url || '',
        type: 'follow',
        targetId: identity.displayUserId,
        targetType: 'profile',
        content: `${user.display_name || user.email || 'Alguien'} empezó a seguirte.`,
      })
      res.json({ ok: true, isFollowing: true })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/profiles/:profileId/follow', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profileId = normalizeId(req.params.profileId)
      if (!profileId) return res.status(400).json({ error: 'PROFILE_ID_REQUIRED' })

      await pool.query(
        `DELETE FROM fallback_follows
         WHERE lower(follower_id) = ANY($1::text[])
           AND lower(following_id) = $2`,
        [identity.values, profileId],
      )
      res.json({ ok: true, isFollowing: false })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/reports/users', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const reportedUserId = normalizeId(req.body?.reportedUserId || req.body?.reported_user_id || '')
      const reason = cleanText(req.body?.reason, 120)
      if (!reportedUserId || !reason) return res.status(400).json({ error: 'REPORTED_USER_AND_REASON_REQUIRED' })
      if (identity.ids.has(reportedUserId)) return res.status(400).json({ error: 'CANNOT_REPORT_SELF' })
      const reportedUser = await fallbackUserByProfileAlias(pool, reportedUserId)
      if (!reportedUser) return res.status(404).json({ error: 'REPORTED_USER_NOT_FOUND' })

      const reportedUserName = cleanText(req.body?.reportedUserName || req.body?.reported_user_name, 160) || reportedUserId
      const description = cleanText(req.body?.description || req.body?.details, 1200) || null
      const evidenceUrl = cleanText(req.body?.evidenceUrl || req.body?.evidence_url, 700) || null
      if (!(await currentUserMayUseReportEvidence(pool, user.id, evidenceUrl))) {
        return res.status(403).json({ error: 'REPORT_EVIDENCE_STORAGE_OWNERSHIP_REQUIRED' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_reports(
            reporter_id,
            reporter_name,
            reported_user_id,
            reported_user_name,
            reason,
            description,
            evidence_url,
            content_type,
            content_id,
            status
         )
         VALUES ($1, $2, $3, $4, $5, $6, $7, 'user', $3, 'pendiente')
         RETURNING id::text, reporter_id, reporter_name, reported_user_id, reported_user_name, reason, description, evidence_url, content_type, content_id, status, created_at, updated_at`,
        [
          identity.displayUserId,
          user.display_name || user.email || 'Usuario',
          reportedUserId,
          reportedUserName,
          reason,
          description,
          evidenceUrl,
        ],
      )
      res.status(201).json({ report: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/reports/content', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const contentType = cleanText(req.body?.contentType || req.body?.content_type, 40).toLowerCase()
      const contentId = cleanText(req.body?.contentId || req.body?.content_id, 160)
      const reportedUserId = normalizeId(req.body?.reportedUserId || req.body?.reported_user_id || '')
      const reason = cleanText(req.body?.reason, 120)
      if (!['post', 'comment'].includes(contentType)) return res.status(400).json({ error: 'INVALID_CONTENT_TYPE' })
      if (!contentId || !reportedUserId || !reason) return res.status(400).json({ error: 'CONTENT_REPORT_REQUIRED_FIELDS' })
      if (identity.ids.has(reportedUserId)) return res.status(400).json({ error: 'CANNOT_REPORT_SELF' })
      const reportedUser = await fallbackUserByProfileAlias(pool, reportedUserId)
      if (!reportedUser) return res.status(404).json({ error: 'REPORTED_USER_NOT_FOUND' })

      let contentOwnerId = ''

      if (contentType === 'post') {
        const existing = await loadPostDetailPayload(pool, user, contentId)
        if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })
        contentOwnerId = normalizeId(existing.post?.user_id)
      } else {
        const { rows } = await pool.query(
          `SELECT id::text, user_id FROM fallback_post_comments WHERE lower(id::text) = $1 AND is_deleted = FALSE LIMIT 1`,
          [normalizeId(contentId)],
        )
        if (rows.length > 0) {
          contentOwnerId = normalizeId(rows[0].user_id)
        } else {
          const imported = (await importedRows(pool, 'post_comments', 50_000))
            .find((row) => normalizeId(textOf(row, ['id'], '')) === normalizeId(contentId))
          if (!imported) return res.status(404).json({ error: 'COMMENT_NOT_FOUND' })
          contentOwnerId = normalizeId(textOf(imported, ['user_id', 'author_id', 'owner_id'], ''))
        }
      }

      const reportedIdentity = userIdentity(reportedUser)
      const reportedAliases = cleanSet([
        ...reportedIdentity.values,
        reportedIdentity.email,
        reportedUser.display_name,
      ])
      if (!contentOwnerId || !reportedAliases.has(contentOwnerId)) {
        return res.status(400).json({ error: 'REPORTED_USER_CONTENT_OWNER_MISMATCH' })
      }

      const reportedUserName = cleanText(req.body?.reportedUserName || req.body?.reported_user_name, 160) || reportedUserId
      const description = cleanText(req.body?.description || req.body?.details, 1200) || null
      const evidenceUrl = cleanText(req.body?.evidenceUrl || req.body?.evidence_url, 700) || null
      if (!(await currentUserMayUseReportEvidence(pool, user.id, evidenceUrl))) {
        return res.status(403).json({ error: 'REPORT_EVIDENCE_STORAGE_OWNERSHIP_REQUIRED' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_reports(
            reporter_id,
            reporter_name,
            reported_user_id,
            reported_user_name,
            reason,
            description,
            evidence_url,
            content_type,
            content_id,
            status
         )
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'pendiente')
         RETURNING id::text, reporter_id, reporter_name, reported_user_id, reported_user_name, reason, description, evidence_url, content_type, content_id, status, created_at, updated_at`,
        [
          identity.displayUserId,
          user.display_name || user.email || 'Usuario',
          reportedUserId,
          reportedUserName,
          reason,
          description,
          evidenceUrl,
          contentType,
          contentId,
        ],
      )
      res.status(201).json({ report: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/profiles/:profileId/block', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profileId = normalizeId(req.params.profileId)
      if (!profileId) return res.status(400).json({ error: 'PROFILE_ID_REQUIRED' })
      res.json(await blockRelationshipForPair(pool, identity, profileId))
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/profiles/:profileId/block', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profileId = normalizeId(req.params.profileId)
      if (!profileId) return res.status(400).json({ error: 'PROFILE_ID_REQUIRED' })
      if (identity.ids.has(profileId)) return res.status(400).json({ error: 'CANNOT_BLOCK_SELF' })
      await pool.query(
        `INSERT INTO fallback_user_blocks(blocker_id, blocked_id, created_at)
         VALUES ($1, $2, NOW())
         ON CONFLICT (blocker_id, blocked_id) DO NOTHING`,
        [identity.displayUserId, profileId],
      )
      res.json(await blockRelationshipForPair(pool, identity, profileId))
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/profiles/:profileId/block', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profileId = normalizeId(req.params.profileId)
      if (!profileId) return res.status(400).json({ error: 'PROFILE_ID_REQUIRED' })
      await pool.query(
        `DELETE FROM fallback_user_blocks
         WHERE lower(blocker_id) = ANY($1::text[])
           AND lower(blocked_id) = $2`,
        [identity.values, profileId],
      )
      res.json(await blockRelationshipForPair(pool, identity, profileId))
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/notifications', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 200, 500))
      const unreadOnly = String(req.query.unreadOnly || req.query.unread_only || '').toLowerCase() === 'true'
      const [profiles, importedNotifications, fallbackNotifications] = await Promise.all([
        allPublicProfiles(pool),
        importedRows(pool, 'notifications', 20_000),
        localNotifications(pool, 20_000),
      ])
      const profilesById = indexProfiles(profiles)
      const matchingNotifications = sortByDateDesc(
        [...importedNotifications, ...fallbackNotifications]
          .filter((row) => rowMatchesAny(row, ['recipient_id', 'user_id', 'profile_id', 'to_user_id'], identity.ids))
          .map((row) => normalizeNotification(row, profilesById))
          .filter((row) => !unreadOnly || !row.is_read),
        ['created_at', 'updated_at'],
      )
      const unreadCount = matchingNotifications.filter((row) => !row.is_read).length
      const items = matchingNotifications.slice(0, limit)

      res.json({
        items,
        unreadCount,
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/notifications', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const recipientId = normalizeId(req.body?.recipientId || req.body?.recipient_id || '')
      if (!recipientId) return res.status(400).json({ error: 'RECIPIENT_ID_REQUIRED' })

      const notification = await createLocalNotification(pool, {
        recipientId,
        actorId: identity.displayUserId,
        actorName: user.display_name || user.email || 'Usuario',
        actorAvatarUrl: user.avatar_url || '',
        type: req.body?.type,
        targetId: req.body?.targetId || req.body?.target_id,
        targetType: req.body?.targetType || req.body?.target_type,
        content: req.body?.content,
        extraData: req.body?.extraData || req.body?.extra_data,
      })
      if (!notification) return res.status(400).json({ error: 'NOTIFICATION_NOT_CREATED' })
      res.status(201).json({ notification })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/notifications/:notificationId/read', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const notificationId = validUuid(req.params.notificationId)
      if (!notificationId) return res.status(400).json({ error: 'NOTIFICATION_ID_REQUIRED' })

      await pool.query(
        `UPDATE fallback_notifications
         SET is_read = TRUE, updated_at = NOW()
         WHERE id = $1
           AND lower(recipient_id) = ANY($2::text[])`,
        [notificationId, identity.values],
      )
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/notifications/read-all', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      await pool.query(
        `UPDATE fallback_notifications
         SET is_read = TRUE, updated_at = NOW()
         WHERE lower(recipient_id) = ANY($1::text[])
           AND is_read = FALSE`,
        [identity.values],
      )
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/notifications/fcm-tokens', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const token = cleanText(req.body?.token || req.query?.token, 600)
      if (!token) return res.status(400).json({ error: 'TOKEN_REQUIRED' })
      await pool.query(
        `DELETE FROM fallback_fcm_tokens
         WHERE lower(user_id) = $1 AND token = $2`,
        [identity.displayUserId, token],
      )
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/notifications/:notificationId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.notificationId)
      const del = await pool.query(
        `DELETE FROM fallback_notifications WHERE id = $1 AND lower(recipient_id) = ANY($2::text[])`,
        [cleanId, identity.values],
      )
      res.json({ ok: true, deleted: del.rowCount })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/notifications', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const del = await pool.query(
        `DELETE FROM fallback_notifications WHERE lower(recipient_id) = ANY($1::text[])`,
        [identity.values],
      )
      res.json({ ok: true, deleted: del.rowCount })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/notifications/fcm-tokens', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const token = cleanText(req.body?.token, 600)
      const platform = cleanText(req.body?.platform, 40) || 'android'
      if (!token) return res.status(400).json({ error: 'TOKEN_REQUIRED' })
      await pool.query(
        `INSERT INTO fallback_fcm_tokens(user_id, token, platform, created_at, updated_at)
         VALUES ($1, $2, $3, NOW(), NOW())
         ON CONFLICT (user_id, token)
         DO UPDATE SET platform = EXCLUDED.platform, updated_at = NOW()`,
        [identity.displayUserId, token, platform],
      )
      res.status(201).json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/search/semantic', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const query = cleanText(req.query.q || req.query.query || '', 200)
      if (query.length < 2) return res.status(400).json({ error: 'SEMANTIC_QUERY_TOO_SHORT' })
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 24, 50))
      const [profiles, importedPosts, fallbackPosts, importedVideos, fallbackVideos] = await Promise.all([
        allPublicProfiles(pool),
        importedRows(pool, 'posts', 5000),
        localPosts(pool, 5000),
        importedRows(pool, 'videos', 5000),
        localVideos(pool, 5000),
      ])
      const mergedVideos = mergeRowsById([...importedVideos, ...fallbackVideos])
      const videoIds = mergedVideos.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const deletedVideoIds = await localVideoDeletionIds(pool, videoIds)
      const corpus = buildPublicSemanticSearchCorpus({
        profiles,
        posts: [...importedPosts, ...fallbackPosts],
        videos: mergedVideos.filter((row) =>
          !deletedVideoIds.has(normalizeId(textOf(row, ['id'], ''))),
        ),
      })
      const ranked = await callAiService('/v1/semantic-rank', {
        query,
        limit,
        documents: corpus.map((document) => ({
          id: document.searchId,
          title: document.title,
          text: document.text,
          tags: document.tags,
        })),
      }, process.env.AI_SEMANTIC_SEARCH_TIMEOUT_MS || 5000)
      const bySearchId = new Map(corpus.map((document) => [document.searchId, document]))
      const items = (Array.isArray(ranked.results) ? ranked.results : [])
        .map((result) => {
          const document = bySearchId.get(String(result?.id || ''))
          if (!document) return null
          const { searchId, text, tags, ...publicDocument } = document
          return {
            ...publicDocument,
            score: Number(result.score) || 0,
            reason: cleanText(result.reason, 40) || 'semantic_similarity',
          }
        })
        .filter(Boolean)
      setPrivateShortCache(res, 10)
      res.json({
        items,
        model: cleanText(ranked.model, 80) || 'novvin-semantic-local-v1',
        publicOnly: true,
      })
    } catch (error) {
      if (new Set(['AI_SERVICE_TIMEOUT', 'AI_SERVICE_UNAVAILABLE']).has(error?.code)) {
        return res.status(503).json({ error: 'SEMANTIC_SEARCH_UNAVAILABLE' })
      }
      next(error)
    }
  })

  app.get('/v1/data/saved-collections', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      await ensureDefaultSavedCollection(pool, identity)
      const search = cleanText(req.query.q, 100).toLowerCase()
      const [
        collectionsResult,
        itemsResult,
        importedProfiles,
        localProfiles,
        importedPosts,
        fallbackPosts,
        importedFollows,
        fallbackFollows,
      ] =
        await Promise.all([
          pool.query(
            `SELECT id::text, user_id, name, is_default, created_at, updated_at
             FROM fallback_saved_collections
             WHERE lower(user_id) = ANY($1::text[])
             ORDER BY is_default DESC, updated_at DESC, lower(name)`,
            [identity.values],
          ),
          pool.query(
            `SELECT
                p.id::text,
                p.collection_id::text,
                p.post_id,
                p.tags,
                p.note,
                p.saved_at,
                p.updated_at
             FROM fallback_saved_posts p
             JOIN fallback_saved_collections c ON c.id = p.collection_id
             WHERE lower(c.user_id) = ANY($1::text[])
             ORDER BY p.saved_at DESC
             LIMIT 1000`,
            [identity.values],
          ),
          importedRows(pool, 'profiles', 5000),
          fallbackUserProfileRows(pool, 5000),
          importedRows(pool, 'posts', 10_000),
          localPosts(pool, 10_000),
          importedRows(pool, 'follows', 20_000),
          localFollows(pool, 20_000),
        ])
      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      const followingIds = cleanSet(
        [...importedFollows, ...fallbackFollows]
          .filter((row) => rowMatchesAny(row, ['follower_id', 'user_id'], identity.ids))
          .map((row) => textOf(row, ['following_id', 'target_user_id'], '')),
      )
      const postsById = new Map(
        [...importedPosts, ...fallbackPosts]
          .map((post) => [normalizeId(textOf(post, ['id'], '')), post])
          .filter(([id]) => Boolean(id)),
      )
      const allItems = itemsResult.rows.map((row) => {
        const postId = normalizeId(row.post_id)
        const candidatePost = postsById.get(postId)
        const post = candidatePost && feedPostVisible(candidatePost, {
          identityIds: identity.ids,
          followingIds,
          mode: 'all',
        })
          ? candidatePost
          : null
        const authorId = post ? postOwnerId(post) : ''
        const profile = authorId ? profilesById.get(authorId) : null
        const mediaUrls = post ? normalizePostMediaUrls(post) : []
        return {
          id: textOf(row, ['id'], ''),
          collection_id: textOf(row, ['collection_id'], ''),
          post_id: postId,
          tags: asArray(row.tags).map((tag) => String(tag || '')).filter(Boolean),
          note: textOf(row, ['note'], ''),
          saved_at: textOf(row, ['saved_at'], ''),
          updated_at: textOf(row, ['updated_at'], ''),
          post: post
            ? {
              id: postId,
              author_id: authorId,
              author_name: textOf(post, ['user_name', 'display_name'], '') || profileName(profile, 'Usuario'),
              content: textOf(post, ['content', 'caption', 'description'], ''),
              media_type: textOf(post, ['media_type', 'content_type'], ''),
              thumbnail_url: mediaUrls[0] || textOf(post, ['media_url', 'thumbnail_url'], ''),
              created_at: textOf(post, ['created_at', 'updated_at'], ''),
              available: true,
            }
            : {
              id: postId,
              author_id: '',
              author_name: 'Publicación no disponible',
              content: '',
              media_type: '',
              thumbnail_url: '',
              created_at: '',
              available: false,
            },
        }
      })
      const collectionNameById = new Map(
        collectionsResult.rows.map((collection) => [normalizeId(collection.id), String(collection.name || '')]),
      )
      const items = search
        ? allItems.filter((item) => [
          collectionNameById.get(normalizeId(item.collection_id)) || '',
          item.note,
          item.tags.join(' '),
          item.post.author_name,
          item.post.content,
        ].join(' ').toLowerCase().includes(search))
        : allItems
      const counts = new Map()
      allItems.forEach((item) => {
        const collectionId = normalizeId(item.collection_id)
        counts.set(collectionId, (counts.get(collectionId) || 0) + 1)
      })
      const collections = collectionsResult.rows.map((row) => ({
        ...row,
        item_count: counts.get(normalizeId(row.id)) || 0,
      }))
      res.json({ collections, items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/saved-collections', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const name = normalizeSavedCollectionName(req.body?.name)
      if (!name) return res.status(400).json({ error: 'COLLECTION_NAME_REQUIRED' })
      const count = await pool.query(
        `SELECT COUNT(*)::int AS count
         FROM fallback_saved_collections
         WHERE lower(user_id) = ANY($1::text[])`,
        [identity.values],
      )
      if (Number(count.rows[0]?.count || 0) >= 50) {
        return res.status(409).json({ error: 'COLLECTION_LIMIT_REACHED' })
      }
      const duplicate = await pool.query(
        `SELECT 1 FROM fallback_saved_collections
         WHERE lower(user_id) = ANY($1::text[]) AND lower(name) = lower($2)
         LIMIT 1`,
        [identity.values, name],
      )
      if (duplicate.rows.length > 0) {
        return res.status(409).json({ error: 'COLLECTION_NAME_EXISTS' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_saved_collections(user_id, name, is_default)
         VALUES ($1, $2, FALSE)
         RETURNING id::text, user_id, name, is_default, created_at, updated_at`,
        [identity.displayUserId, name],
      )
      res.status(201).json({ collection: { ...rows[0], item_count: 0 } })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/saved-collections/:collectionId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const collection = await ownedSavedCollection(pool, identity, req.params.collectionId)
      if (!collection) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      const name = normalizeSavedCollectionName(req.body?.name)
      if (!name) return res.status(400).json({ error: 'COLLECTION_NAME_REQUIRED' })
      const duplicate = await pool.query(
        `SELECT 1 FROM fallback_saved_collections
         WHERE lower(user_id) = ANY($1::text[])
           AND lower(name) = lower($2)
           AND lower(id::text) <> $3
         LIMIT 1`,
        [identity.values, name, normalizeId(collection.id)],
      )
      if (duplicate.rows.length > 0) {
        return res.status(409).json({ error: 'COLLECTION_NAME_EXISTS' })
      }
      const { rows } = await pool.query(
        `UPDATE fallback_saved_collections
         SET name = $1, updated_at = NOW()
         WHERE id = $2::uuid
         RETURNING id::text, user_id, name, is_default, created_at, updated_at`,
        [name, collection.id],
      )
      res.json({ collection: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/saved-collections/:collectionId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const collection = await ownedSavedCollection(pool, identity, req.params.collectionId)
      if (!collection) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      if (collection.is_default) {
        return res.status(409).json({ error: 'DEFAULT_COLLECTION_CANNOT_BE_DELETED' })
      }
      await pool.query('DELETE FROM fallback_saved_collections WHERE id = $1::uuid', [collection.id])
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/saved-collections/:collectionId/items', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const collection = await ownedSavedCollection(pool, identity, req.params.collectionId)
      if (!collection) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      const postId = normalizeId(req.body?.postId || req.body?.post_id)
      if (!postId) return res.status(400).json({ error: 'POST_ID_REQUIRED' })
      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })
      const followRows = await combinedFollows(pool)
      const followingIds = cleanSet(
        followRows
          .filter((row) => rowMatchesAny(row, ['follower_id', 'user_id'], identity.ids))
          .map((row) => textOf(row, ['following_id', 'target_user_id'], '')),
      )
      if (!feedPostVisible(existing.post, {
        identityIds: identity.ids,
        followingIds,
        mode: 'all',
      })) {
        return res.status(404).json({ error: 'POST_NOT_FOUND' })
      }
      const tags = normalizeSavedTags(req.body?.tags)
      const note = cleanText(req.body?.note, 500)
      const { rows } = await pool.query(
        `INSERT INTO fallback_saved_posts(collection_id, user_id, post_id, tags, note)
         VALUES ($1::uuid, $2, $3, $4::jsonb, $5)
         ON CONFLICT (collection_id, post_id)
         DO UPDATE SET tags = EXCLUDED.tags, note = EXCLUDED.note, updated_at = NOW()
         RETURNING id::text, collection_id::text, post_id, tags, note, saved_at, updated_at`,
        [collection.id, identity.displayUserId, postId, JSON.stringify(tags), note],
      )
      await pool.query(
        'UPDATE fallback_saved_collections SET updated_at = NOW() WHERE id = $1::uuid',
        [collection.id],
      )
      res.status(201).json({ item: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/saved-collections/:collectionId/items/:postId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const collection = await ownedSavedCollection(pool, identity, req.params.collectionId)
      if (!collection) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      const postId = normalizeId(req.params.postId)
      const result = await pool.query(
        `DELETE FROM fallback_saved_posts
         WHERE collection_id = $1::uuid AND lower(post_id) = $2`,
        [collection.id, postId],
      )
      res.json({ ok: true, deleted: result.rowCount })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/feed', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 60, 200))
      const offset = Math.max(0, Number(req.query.offset) || 0)
      const requestedUserId = normalizeId(req.query.userId || req.query.user_id || '')
      const mode = normalizeFeedMode(req.query.mode)
      const [
        importedProfiles,
        localProfiles,
        importedPosts,
        fallbackPosts,
        likes,
        reactions,
        comments,
        importedFollows,
        fallbackFollows,
      ] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
        importedRows(pool, 'posts', 5000),
        localPosts(pool, 5000),
        importedRows(pool, 'post_likes', 10_000),
        importedRows(pool, 'post_reactions', 10_000),
        importedRows(pool, 'post_comments', 10_000),
        importedRows(pool, 'follows', 20_000),
        localFollows(pool, 20_000),
      ])
      const followingIds = cleanSet(
        [...importedFollows, ...fallbackFollows]
          .filter((row) => rowMatchesAny(row, ['follower_id', 'user_id'], identity.ids))
          .map((row) => textOf(row, ['following_id', 'target_user_id'], '')),
      )
      const posts = [...importedPosts, ...fallbackPosts].filter((row) => feedPostVisible(row, {
        identityIds: identity.ids,
        followingIds,
        mode,
        requestedUserId,
      }))
      const postIds = posts.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const [fallbackLikes, fallbackComments] = await Promise.all([
        localPostReactions(pool, postIds, 10_000),
        localPostComments(pool, postIds, 10_000),
      ])

      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      const likeCounts = new Map()
      const commentCounts = new Map()
      const likedPostIds = new Set()

      const allReactionRows = [...likes, ...reactions, ...fallbackLikes]
      for (const row of allReactionRows) {
        const postId = textOf(row, ['post_id'], '')
        if (!postId) continue
        likeCounts.set(postId, (likeCounts.get(postId) || 0) + 1)
        if (rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids)) {
          likedPostIds.add(postId)
        }
      }

      for (const row of [...comments, ...fallbackComments]) {
        const postId = textOf(row, ['post_id'], '')
        if (!postId) continue
        commentCounts.set(postId, (commentCounts.get(postId) || 0) + 1)
      }
      const reactionState = buildPostReactionState(allReactionRows, identity)

      const items = sortByDateDesc(posts, ['created_at', 'updated_at'])
        .slice(offset, offset + limit)
        .map((row) => enrichPost(
          row,
          profilesById,
          likeCounts,
          commentCounts,
          likedPostIds,
          reactionState.counts,
          reactionState.mine,
        ))

      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/feed/posts', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const content = String(req.body?.content || '').trim()
      const requestedMediaUrls = [
        ...asArray(req.body?.mediaUrls),
        ...asArray(req.body?.media_urls),
      ].map((url) => String(url || '').trim()).filter(Boolean)
      const explicitMediaUrl = String(req.body?.mediaUrl || req.body?.media_url || '').trim()
      const mediaUrls = Array.from(new Set([explicitMediaUrl, ...requestedMediaUrls].filter(Boolean)))
      const mediaUrl = mediaUrls[0] || ''
      const mediaType = String(req.body?.mediaType || '').trim() || null
      const rawVisibility = String(req.body?.visibility || 'public').trim().toLowerCase()
      const visibility = ['public', 'followers', 'private'].includes(rawVisibility) ? rawVisibility : 'public'
      const remixAllowed = req.body?.remixAllowed === true || req.body?.remix_allowed === true
      const rawRemixAudience = String(req.body?.remixAudience || req.body?.remix_audience || 'public').trim().toLowerCase()
      const remixAudience = ['public', 'followers'].includes(rawRemixAudience) ? rawRemixAudience : 'public'
      if (!content && !mediaUrl) {
        return res.status(400).json({ error: 'POST_CONTENT_OR_MEDIA_REQUIRED' })
      }
      if (mediaUrls.length > 10) {
        return res.status(400).json({ error: 'POST_MEDIA_LIMIT_EXCEEDED' })
      }
      if (!(await currentUserOwnsStorageObjects(pool, user.id, mediaUrls, ['post-media']))) {
        return res.status(403).json({ error: 'POST_MEDIA_STORAGE_OWNERSHIP_REQUIRED' })
      }

      const { rows } = await pool.query(
        `INSERT INTO fallback_posts(
            user_id,
            user_name,
            user_profile_image,
            content,
            media_url,
            media_urls,
            media_type,
            visibility,
            remix_allowed,
            remix_audience
         )
         VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7, $8, $9, $10)
         RETURNING
            id::text,
            user_id,
            user_name,
            user_profile_image,
            content,
            media_url,
            media_urls,
            media_type,
            visibility,
            remix_allowed,
            remix_audience,
            remix_revoked_at,
            remix_source_post_id,
            remix_root_post_id,
            remix_source_user_id,
            remix_source_user_name,
            remix_depth,
            created_at,
            updated_at,
            0::int AS like_count,
            0::int AS comment_count,
            TRUE AS local_only`,
        [
          identity.displayUserId,
          user.display_name || user.email || 'Usuario',
          user.avatar_url || null,
          content,
          mediaUrl || null,
          JSON.stringify(mediaUrls.map((url) => ({ url, media_type: mediaType || 'file' }))),
          mediaType,
          visibility,
          remixAllowed,
          remixAudience,
        ],
      )

      res.status(201).json({ post: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/feed/posts/:postId/remix-permission', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      if (!postId) return res.status(400).json({ error: 'POST_ID_REQUIRED' })
      if (typeof req.body?.allowed !== 'boolean') {
        return res.status(400).json({ error: 'REMIX_PERMISSION_ALLOWED_REQUIRED' })
      }

      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!canMutatePost(existing.post, identity)) {
        return res.status(403).json({ error: 'REMIX_PERMISSION_REQUIRES_OWNER' })
      }
      if (!existing.post.local_only) {
        return res.status(409).json({ error: 'REMIX_PERMISSION_IMPORT_UNSUPPORTED' })
      }

      const rawAudience = String(req.body?.audience || 'public').trim().toLowerCase()
      if (!['public', 'followers'].includes(rawAudience)) {
        return res.status(400).json({ error: 'INVALID_REMIX_AUDIENCE' })
      }

      await pool.query(
        `UPDATE fallback_posts
         SET remix_allowed = $1,
             remix_audience = $2,
             remix_revoked_at = CASE WHEN $1 THEN NULL ELSE NOW() END,
             updated_at = NOW()
         WHERE lower(id::text) = $3`,
        [req.body.allowed, rawAudience, postId],
      )
      const payload = await loadPostDetailPayload(pool, user, postId)
      res.json({ post: payload?.post || existing.post })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/feed/posts/:postId/remix', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      if (!postId) return res.status(400).json({ error: 'POST_ID_REQUIRED' })
      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })

      const source = existing.post
      const identity = userIdentity(user)
      const sourceOwnerId = postOwnerId(source)
      const isSourceOwner = Boolean(sourceOwnerId && identity.ids.has(sourceOwnerId))
      const follows = await combinedFollows(pool)
      const followsSource = follows.some((row) =>
        rowMatchesAny(row, ['follower_id', 'user_id'], identity.ids) &&
        normalizeId(textOf(row, ['following_id', 'target_user_id'], '')) === sourceOwnerId,
      )
      const sourceVisibility = textOf(source, ['visibility'], 'public').toLowerCase()
      const canViewSource = isSourceOwner ||
        sourceVisibility === 'public' ||
        (sourceVisibility === 'followers' && followsSource)
      if (!canViewSource) {
        return res.status(403).json({ error: 'REMIX_SOURCE_NOT_VISIBLE' })
      }
      if (!boolOf(source, ['remix_allowed', 'remixAllowed'], false) ||
          textOf(source, ['remix_revoked_at', 'remixRevokedAt'], '')) {
        return res.status(409).json({ error: 'REMIX_NOT_ALLOWED' })
      }
      const remixAudience = textOf(source, ['remix_audience', 'remixAudience'], 'public').toLowerCase()
      if (remixAudience === 'followers' && !isSourceOwner && !followsSource) {
        return res.status(403).json({ error: 'REMIX_FOLLOWERS_ONLY' })
      }

      const sourceDepth = Math.max(0, numberOf(source, ['remix_depth', 'remixDepth'], 0))
      if (sourceDepth >= 20) {
        return res.status(409).json({ error: 'REMIX_CHAIN_LIMIT_REACHED' })
      }
      const content = cleanText(req.body?.content, 5000)
      const rawVisibility = cleanText(req.body?.visibility || 'public', 40).toLowerCase()
      if (!['public', 'followers', 'private'].includes(rawVisibility)) {
        return res.status(400).json({ error: 'INVALID_POST_VISIBILITY' })
      }
      const mediaUrls = normalizePostMediaUrls(source)
      const mediaType = textOf(source, ['media_type', 'mediaType'], '') || null
      const sourceUserName = textOf(source, ['user_name', 'display_name'], 'Usuario')
      const sourceRootId = normalizeId(textOf(source, ['remix_root_post_id', 'remixRootPostId'], '')) || postId

      const { rows } = await pool.query(
        `INSERT INTO fallback_posts(
            user_id,
            user_name,
            user_profile_image,
            content,
            media_url,
            media_urls,
            media_type,
            visibility,
            remix_source_post_id,
            remix_root_post_id,
            remix_source_user_id,
            remix_source_user_name,
            remix_depth
         )
         VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7, $8, $9, $10, $11, $12, $13)
         RETURNING
            id::text,
            user_id,
            user_name,
            user_profile_image,
            content,
            media_url,
            media_urls,
            media_type,
            visibility,
            remix_allowed,
            remix_audience,
            remix_revoked_at,
            remix_source_post_id,
            remix_root_post_id,
            remix_source_user_id,
            remix_source_user_name,
            remix_depth,
            created_at,
            updated_at,
            0::int AS like_count,
            0::int AS comment_count,
            TRUE AS local_only`,
        [
          identity.displayUserId,
          user.display_name || user.email || 'Usuario',
          user.avatar_url || null,
          content,
          mediaUrls[0] || null,
          JSON.stringify(mediaUrls.map((url) => ({ url, media_type: mediaType || 'file' }))),
          mediaType,
          rawVisibility,
          postId,
          sourceRootId,
          sourceOwnerId,
          sourceUserName,
          sourceDepth + 1,
        ],
      )
      await createLocalNotificationBestEffort(pool, {
        recipientId: sourceOwnerId,
        actorId: identity.displayUserId,
        actorName: user.display_name || user.email || 'Usuario',
        actorAvatarUrl: user.avatar_url || '',
        type: 'remix_post',
        targetId: textOf(rows[0], ['id'], ''),
        targetType: 'post',
        content: 'Creó un remix de tu publicación.',
        extraData: { source_post_id: postId, remix_post_id: textOf(rows[0], ['id'], '') },
      })
      res.status(201).json({ post: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/feed/posts/:postId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const payload = await loadPostDetailPayload(pool, user, req.params.postId)
      if (!payload) return res.status(404).json({ error: 'POST_NOT_FOUND' })

      res.json(payload)
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/feed/posts/:postId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      if (!postId) return res.status(400).json({ error: 'POST_ID_REQUIRED' })

      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })

      const identity = userIdentity(user)
      if (!canMutatePost(existing.post, identity)) {
        return res.status(403).json({ error: 'POST_EDIT_REQUIRES_OWNER' })
      }

      const patch = {}
      if (Object.prototype.hasOwnProperty.call(req.body || {}, 'content')) {
        patch.content = cleanText(req.body.content, 5000)
      }
      if (Object.prototype.hasOwnProperty.call(req.body || {}, 'visibility')) {
        const visibility = cleanText(req.body.visibility, 40).toLowerCase()
        if (!['public', 'followers', 'private'].includes(visibility)) {
          return res.status(400).json({ error: 'INVALID_POST_VISIBILITY' })
        }
        patch.visibility = visibility
      }
      if (Object.keys(patch).length === 0) {
        return res.status(400).json({ error: 'POST_PATCH_EMPTY' })
      }

      if (existing.post.local_only) {
        const assignments = []
        const values = []
        Object.entries(patch).forEach(([key, value]) => {
          values.push(value)
          assignments.push(`${quoteIdentifier(key)} = $${values.length}`)
        })
        values.push(postId)

        const { rowCount } = await pool.query(
          `UPDATE fallback_posts
           SET ${assignments.join(', ')}, updated_at = NOW()
           WHERE lower(id::text) = $${values.length}`,
          values,
        )
        if (!rowCount) return res.status(404).json({ error: 'POST_NOT_FOUND' })
      } else {
        if (!(await tableExists(pool, 'posts'))) {
          return res.status(404).json({ error: 'POST_IMPORT_TABLE_NOT_FOUND' })
        }
        const assignments = []
        const values = []
        for (const [key, value] of Object.entries(patch)) {
          if (await columnExists(pool, 'posts', key)) {
            values.push(value)
            assignments.push(`${quoteIdentifier(key)} = $${values.length}`)
          }
        }
        if (await columnExists(pool, 'posts', 'updated_at')) {
          assignments.push('updated_at = NOW()')
        }
        if (assignments.length === 0) {
          return res.status(400).json({ error: 'POST_PATCH_UNSUPPORTED_FOR_IMPORT' })
        }
        values.push(postId)
        const { rowCount } = await pool.query(
          `UPDATE supabase_import.posts
           SET ${assignments.join(', ')}
           WHERE lower(id::text) = $${values.length}`,
          values,
        )
        if (!rowCount) return res.status(404).json({ error: 'POST_NOT_FOUND' })
      }

      clearImportedRowCaches('posts')
      const payload = await loadPostDetailPayload(pool, user, postId)
      res.json({ post: payload?.post || existing.post })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/feed/posts/:postId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      if (!postId) return res.status(400).json({ error: 'POST_ID_REQUIRED' })

      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })

      const identity = userIdentity(user)
      if (!canMutatePost(existing.post, identity)) {
        return res.status(403).json({ error: 'POST_DELETE_REQUIRES_OWNER' })
      }

      if (existing.post.local_only) {
        await pool.query('DELETE FROM fallback_saved_posts WHERE lower(post_id) = $1', [postId])
        await pool.query('DELETE FROM fallback_post_reactions WHERE lower(post_id) = $1', [postId])
        await pool.query('DELETE FROM fallback_post_comments WHERE lower(post_id) = $1', [postId])
        await pool.query('DELETE FROM fallback_shares WHERE content_type = $2 AND lower(post_id) = $1', [postId, 'post'])
        const { rowCount } = await pool.query(
          `DELETE FROM fallback_posts
           WHERE lower(id::text) = $1`,
          [postId],
        )
        if (!rowCount) return res.status(404).json({ error: 'POST_NOT_FOUND' })
      } else {
        await pool.query('DELETE FROM fallback_saved_posts WHERE lower(post_id) = $1', [postId])
        const relatedTables = [
          ['post_comments', 'post_id'],
          ['post_likes', 'post_id'],
          ['post_reactions', 'post_id'],
          ['shares', 'post_id'],
        ]
        for (const [tableName, columnName] of relatedTables) {
          if ((await tableExists(pool, tableName)) && (await columnExists(pool, tableName, columnName))) {
            await pool.query(
              `DELETE FROM supabase_import.${quoteIdentifier(tableName)}
               WHERE lower(${quoteIdentifier(columnName)}::text) = $1`,
              [postId],
            )
          }
        }
        const { rowCount } = await pool.query(
          `DELETE FROM supabase_import.posts
           WHERE lower(id::text) = $1`,
          [postId],
        )
        if (!rowCount) return res.status(404).json({ error: 'POST_NOT_FOUND' })
      }

      clearImportedRowCaches('posts', 'post_comments', 'post_likes', 'post_reactions', 'shares')
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/feed/posts/:postId/comments', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      const content = cleanText(req.body?.content, 4000)
      const parentId = cleanText(req.body?.parentId || req.body?.parent_id, 120) || null
      const mediaUrl = cleanText(req.body?.mediaUrl || req.body?.media_url, 700) || null
      const mediaType = cleanText(req.body?.mediaType || req.body?.media_type, 80) || null
      if (!postId || (!content && !mediaUrl)) {
        return res.status(400).json({ error: 'POST_ID_AND_CONTENT_OR_MEDIA_REQUIRED' })
      }

      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })

      const identity = userIdentity(user)
      if (mediaUrl && !(await currentUserOwnsStorageObject(pool, user.id, mediaUrl, ['post-comments-media']))) {
        return res.status(403).json({ error: 'POST_COMMENT_MEDIA_STORAGE_OWNERSHIP_REQUIRED' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_post_comments(post_id, user_id, content, parent_id, media_url, media_type)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id::text, post_id, user_id, content, parent_id, media_url, media_type, created_at, updated_at`,
        [postId, identity.displayUserId, content, parentId, mediaUrl, mediaType],
      )
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      await createLocalNotificationBestEffort(pool, {
        recipientId: normalizeId(textOf(existing.post, ['user_id', 'owner_id', 'author_id', 'creator_id'], '')),
        actorId: identity.displayUserId,
        actorName: user.display_name || user.email || 'Usuario',
        actorAvatarUrl: user.avatar_url || '',
        type: 'comment_post',
        targetId: postId,
        targetType: 'post',
        content: content || 'Comentó tu publicación.',
        extraData: { post_id: postId, comment_id: textOf(rows[0], ['id'], '') },
      })
      res.status(201).json({ comment: enrichPostComment(normalizeImportedRow(rows[0]), profilesById, identity) })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/feed/comments/:commentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      if (!commentId) return res.status(400).json({ error: 'COMMENT_ID_REQUIRED' })

      const { rows: commentRows } = await pool.query(
        `SELECT id::text, post_id, user_id, content, parent_id, media_url, media_type, created_at, updated_at
         FROM fallback_post_comments
         WHERE lower(id::text) = $1 AND is_deleted = FALSE
         LIMIT 1`,
        [commentId],
      )
      let comment = commentRows[0]
      let commentSource = 'fallback'
      if (!comment) {
        const importedComment = (await importedRows(pool, 'post_comments', 50_000))
          .find((row) => normalizeId(textOf(row, ['id'], '')) === commentId && !boolOf(row, ['is_deleted', 'deleted'], false))
        if (importedComment) {
          comment = importedComment
          commentSource = 'imported'
        }
      }
      if (!comment) return res.status(404).json({ error: 'COMMENT_NOT_FOUND' })

      const isCommentAuthor = rowMatchesAny(
        comment,
        ['user_id', 'userId', 'profile_id', 'profileId', 'author_id', 'authorId', 'owner_id', 'ownerId', 'creator_id', 'creatorId'],
        identity.ids,
      )
      let isPostOwner = false
      const commentPostId = normalizeId(comment.post_id)
      if (commentPostId) {
        const existing = await loadPostDetailPayload(pool, user, commentPostId)
        isPostOwner = Boolean(
          existing?.post &&
            rowMatchesAny(existing.post, ['user_id', 'owner_id', 'author_id', 'creator_id', 'profile_id'], identity.ids),
        )
      }

      if (!isCommentAuthor && !isPostOwner) {
        return res.status(403).json({ error: 'COMMENT_DELETE_DENIED' })
      }

      await pool.query('DELETE FROM fallback_post_comment_reactions WHERE lower(comment_id) = $1', [commentId])

      const { rowCount } = commentSource === 'fallback'
        ? await pool.query(
          `UPDATE fallback_post_comments
           SET is_deleted = TRUE, updated_at = NOW()
           WHERE lower(id::text) = $1 AND is_deleted = FALSE`,
          [commentId],
        )
        : await pool.query(
          `DELETE FROM supabase_import.post_comments
           WHERE lower(id::text) = $1`,
          [commentId],
        )
      if (!rowCount) return res.status(404).json({ error: 'COMMENT_NOT_FOUND' })
      if (commentSource === 'imported') clearImportedRowCaches('post_comments')
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/feed/comments/:commentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      if (!commentId) return res.status(400).json({ error: 'COMMENT_ID_REQUIRED' })

      const content = cleanText(req.body?.content || '')
      if (!content) return res.status(400).json({ error: 'CONTENT_REQUIRED' })

      const local = await pool.query(
        `SELECT * FROM fallback_post_comments
         WHERE lower(id::text) = $1 AND is_deleted = FALSE
         LIMIT 1`,
        [commentId],
      )
      let comment = local.rows[0]
      let source = 'fallback'
      if (!comment) {
        comment = (await importedRows(pool, 'post_comments', 50_000))
          .find((row) => normalizeId(textOf(row, ['id'], '')) === commentId)
        source = 'imported'
      }
      if (!comment) return res.status(404).json({ error: 'COMMENT_NOT_FOUND' })
      if (!rowMatchesAny(comment, ['user_id', 'profile_id', 'author_id', 'owner_id'], identity.ids)) {
        return res.status(403).json({ error: 'COMMENT_EDIT_REQUIRES_AUTHOR' })
      }
      const rawCreatedAt = comment.created_at ?? comment.createdAt
      const createdAt = rawCreatedAt instanceof Date
        ? rawCreatedAt.getTime()
        : Date.parse(String(rawCreatedAt || ''))
      if (!createdAt || Date.now() - createdAt >= 5 * 60 * 60 * 1000) {
        return res.status(403).json({ error: 'COMMENT_EDIT_WINDOW_EXPIRED' })
      }

      let updated
      if (source === 'fallback') {
        const { rows } = await pool.query(
          `UPDATE fallback_post_comments
           SET content = $1, updated_at = NOW()
           WHERE lower(id::text) = $2
           RETURNING *`,
          [content, commentId],
        )
        updated = rows[0]
      } else {
        const { rows } = await pool.query(
          `UPDATE supabase_import.post_comments
           SET content = $1, updated_at = NOW()
           WHERE lower(id::text) = $2
           RETURNING *`,
          [content, commentId],
        )
        updated = rows[0]
        clearImportedRowCaches('post_comments')
      }
      res.json({ comment: updated })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/feed/comments/:commentId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      const reactionType = String(req.body?.reactionType || req.body?.reaction_type || '').trim().toUpperCase()
      if (!commentId || !new Set(['LIKE', 'DISLIKE']).has(reactionType)) {
        return res.status(400).json({ error: 'COMMENT_REACTION_INVALID' })
      }
      const local = await pool.query(
        `SELECT 1 FROM fallback_post_comments WHERE lower(id::text) = $1 AND is_deleted = FALSE LIMIT 1`,
        [commentId],
      )
      const importedExists = local.rows.length > 0 || (await importedRows(pool, 'post_comments', 50_000))
        .some((row) => normalizeId(textOf(row, ['id'], '')) === commentId)
      if (!importedExists) return res.status(404).json({ error: 'COMMENT_NOT_FOUND' })

      await pool.query(
        `INSERT INTO fallback_post_comment_reactions(comment_id, user_id, reaction_type)
         VALUES ($1, $2, $3)
         ON CONFLICT (comment_id, user_id)
         DO UPDATE SET reaction_type = EXCLUDED.reaction_type, updated_at = NOW()`,
        [commentId, identity.displayUserId, reactionType],
      )
      res.json({ ok: true, reaction_type: reactionType })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/feed/comments/:commentId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      if (!commentId) return res.status(400).json({ error: 'COMMENT_ID_REQUIRED' })
      await pool.query(
        `DELETE FROM fallback_post_comment_reactions
         WHERE lower(comment_id) = $1 AND lower(user_id) = ANY($2::text[])`,
        [commentId, Array.from(identity.ids)],
      )
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/feed/posts/:postId/reactions', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      if (!postId) return res.status(400).json({ error: 'POST_ID_REQUIRED' })
      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })

      const requestedType = cleanText(req.query.type || '', 40).toUpperCase()
      if (requestedType && !POST_REACTION_TYPES.has(requestedType)) {
        return res.status(400).json({ error: 'POST_REACTION_INVALID' })
      }
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 100, 200))
      const offset = Math.max(0, Number(req.query.offset) || 0)
      const identity = userIdentity(user)
      const [importedProfiles, localProfiles, importedLikes, importedReactions, fallbackReactions] =
        await Promise.all([
          importedRows(pool, 'profiles', 5000),
          fallbackUserProfileRows(pool, 5000),
          importedRows(pool, 'post_likes', 50_000),
          importedRows(pool, 'post_reactions', 50_000),
          localPostReactions(pool, [postId], 50_000),
        ])
      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      const relevantRows = [
        ...importedLikes.filter((row) => postIdOf(row) === postId),
        ...importedReactions.filter((row) => postIdOf(row) === postId),
        ...fallbackReactions,
      ]
      const state = buildPostReactionState(relevantRows, identity)
      const allItems = (state.items.get(postId) || [])
        .filter((snapshot) => !requestedType || snapshot.reactionType === requestedType)
        .sort((left, right) => {
          const leftAt = textOf(left.row, ['updated_at', 'created_at'], '')
          const rightAt = textOf(right.row, ['updated_at', 'created_at'], '')
          return toMillis(rightAt) - toMillis(leftAt)
        })
        .map((snapshot) => {
          const profile = profilesById.get(snapshot.userId)
          return {
            user_id: snapshot.userId,
            user_name: profileName(profile, textOf(snapshot.row, ['user_name', 'display_name'], 'Usuario')),
            user_avatar_url: profileAvatar(profile) || textOf(snapshot.row, ['user_avatar_url', 'avatar_url'], ''),
            is_verified: boolOf(profile, ['is_verified', 'verified'], false),
            reaction_type: snapshot.reactionType,
            reacted_at: textOf(snapshot.row, ['updated_at', 'created_at'], ''),
          }
        })

      res.json({
        items: allItems.slice(offset, offset + limit),
        total: allItems.length,
        reaction_counts: state.counts.get(postId) || {},
      })
    } catch (error) {
      next(error)
    }
  })


  app.post('/v1/data/feed/posts/:postId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      if (!postId) return res.status(400).json({ error: 'POST_ID_REQUIRED' })
      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })

      const identity = userIdentity(user)
      const reactionType = cleanText(req.body?.reactionType || req.body?.reaction_type || 'LIKE', 40).toUpperCase() || 'LIKE'
      if (!POST_REACTION_TYPES.has(reactionType)) {
        return res.status(400).json({ error: 'POST_REACTION_INVALID' })
      }
      await pool.query(
        `INSERT INTO fallback_post_reactions(post_id, user_id, reaction_type, created_at, updated_at)
         VALUES ($1, $2, $3, NOW(), NOW())
         ON CONFLICT (post_id, user_id)
         DO UPDATE SET reaction_type = EXCLUDED.reaction_type, updated_at = NOW()`,
        [postId, identity.displayUserId, reactionType],
      )
      await createLocalNotificationBestEffort(pool, {
        recipientId: normalizeId(textOf(existing.post, ['user_id', 'owner_id', 'author_id', 'creator_id'], '')),
        actorId: identity.displayUserId,
        actorName: user.display_name || user.email || 'Usuario',
        actorAvatarUrl: user.avatar_url || '',
        type: 'like_post',
        targetId: postId,
        targetType: 'post',
        content: `${user.display_name || user.email || 'Alguien'} reaccionó a tu publicación.`,
        extraData: { post_id: postId, reaction_type: reactionType },
      })

      const payload = await loadPostDetailPayload(pool, user, postId)
      res.json({ ok: true, post: payload?.post || existing.post })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/feed/posts/:postId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      const identity = userIdentity(user)
      await pool.query(
        `DELETE FROM fallback_post_reactions
         WHERE lower(post_id) = $1 AND lower(user_id) = $2`,
        [postId, identity.displayUserId],
      )
      const payload = postId ? await loadPostDetailPayload(pool, user, postId) : null
      res.json({ ok: true, post: payload?.post || null })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/feed/posts/:postId/share', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const postId = normalizeId(req.params.postId)
      if (!postId) return res.status(400).json({ error: 'POST_ID_REQUIRED' })
      const existing = await loadPostDetailPayload(pool, user, postId)
      if (!existing) return res.status(404).json({ error: 'POST_NOT_FOUND' })

      const identity = userIdentity(user)
      await pool.query(
        `INSERT INTO fallback_shares(user_id, post_id, content_type, created_at)
         VALUES ($1, $2, 'post', NOW())
         ON CONFLICT (user_id, post_id, content_type) DO NOTHING`,
        [identity.displayUserId, postId],
      )
      await createLocalNotificationBestEffort(pool, {
        recipientId: normalizeId(textOf(existing.post, ['user_id', 'owner_id', 'author_id', 'creator_id'], '')),
        actorId: identity.displayUserId,
        actorName: user.display_name || user.email || 'Usuario',
        actorAvatarUrl: user.avatar_url || '',
        type: 'share_post',
        targetId: postId,
        targetType: 'post',
        content: `${user.display_name || user.email || 'Alguien'} compartió tu publicación.`,
        extraData: { post_id: postId },
      })
      const payload = await loadPostDetailPayload(pool, user, postId)
      res.status(201).json({ ok: true, post: payload?.post || existing.post })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/messages/inbox', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const tab = String(req.query.tab || 'chats')

      if (tab === 'voice') {
        const [importedProfiles, localProfiles, importedRooms, importedParticipants, fallbackParticipants, fallbackRooms] = await Promise.all([
          importedRows(pool, 'profiles', 5000),
          fallbackUserProfileRows(pool, 5000),
          importedRows(pool, 'voice_rooms', 1000),
          importedRows(pool, 'voice_room_participants', 10_000),
          localVoiceParticipants(pool),
          localVoiceRooms(pool, 1000),
        ])
        const rooms = [...importedRooms, ...fallbackRooms]
        const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
        const countByRoom = new Map()
        for (const row of [...importedParticipants, ...fallbackParticipants]) {
          const id = voiceParticipantRoomId(row)
          if (!id) continue
          countByRoom.set(id, (countByRoom.get(id) || 0) + 1)
        }
        const mappedRooms = rooms.map((row) => {
          const id = voiceRoomId(row)
          return mapVoiceRoom(row, profilesById, countByRoom.get(id) || 0)
        })
        return res.json({
          items: sortByDateDesc(mappedRooms, ['updated_at', 'created_at']).slice(0, 40).map((row) => {
            const id = textOf(row, ['id'], '')
            return {
              id,
              title: textOf(row, ['title', 'name', 'readable_id'], 'Sala de voz'),
              subtitle: textOf(row, ['topic', 'description'], 'Sala disponible'),
              updatedAt: textOf(row, ['updated_at', 'created_at'], ''),
              kind: 'voice',
              avatarUrl: textOf(row, ['creator_avatar_url', 'avatar_url', 'cover_url', 'image_url'], ''),
              profileId: null,
              unreadCount: 0,
              isPinned: false,
              isMuted: false,
              isArchived: false,
              isFavorite: false,
              folders: [],
              openPath: '/app/voice',
            }
          }),
        })
      }

      if (tab === 'groups' || tab === 'communities') {
        const config = collectionConfig(tab)
        const [importedMemberships, importedRooms, importedMessages, localRooms, localMemberships, deletedLocalRoomIds] = await Promise.all([
          importedRows(pool, config.memberTable, 5000),
          importedRows(pool, config.roomTable, 2000),
          importedRows(pool, config.messageTable, 10_000),
          localCollectionRooms(pool, config.kind, 2000),
          localCollectionMembers(pool, config.kind, 10_000),
          localDeletedCollectionRoomIds(pool, config.kind),
        ])
        const memberships = deduplicateMembers([...importedMemberships, ...localMemberships], config.roomKey)
          .filter((row) => {
            const roomId = roomIdOf(row, config.roomKey)
            const status = textOf(row, ['status', 'member_status'], '').toLowerCase()
            return roomId && !deletedLocalRoomIds.has(roomId) && !['left', 'removed', 'deleted'].includes(status)
          })
        const rooms = mergeRowsById([...importedRooms, ...localRooms])
          .filter((row) => {
            const roomId = normalizeId(textOf(row, ['id'], ''))
            return roomId && !deletedLocalRoomIds.has(roomId) && !boolOf(row, ['is_deleted', 'deleted'], false)
          })
        const roomIds = cleanSet(
          memberships
            .filter((row) => rowMatchesAny(row, ['user_id', 'profile_id', 'member_id'], identity.ids))
            .map((row) => textOf(row, [config.roomKey, 'room_id'], '')),
        )
        const readStateByRoom = await localCollectionReadStates(pool, config.kind, identity.displayUserId)
        const lastReadByRoom = new Map()
        for (const row of memberships) {
          const userId = normalizeId(textOf(row, ['user_id', 'profile_id', 'member_id'], ''))
          if (!identity.ids.has(userId)) continue
          const roomId = roomIdOf(row, config.roomKey)
          if (!roomId) continue
          const lastReadAt = textOf(row, ['last_read_at', 'lastReadAt'], '')
          if (lastReadAt) lastReadByRoom.set(roomId, lastReadAt)
        }
        for (const [roomId, lastReadAt] of readStateByRoom.entries()) {
          const current = lastReadByRoom.get(roomId)
          if (!current || toMillis(lastReadAt) > toMillis(current)) lastReadByRoom.set(roomId, lastReadAt)
        }
        const importedLatestByRoom = new Map()
        for (const message of sortMessagesNewestFirst(importedMessages)) {
          const roomId = roomIdOf(message, config.roomKey)
          if (roomId && !importedLatestByRoom.has(roomId)) importedLatestByRoom.set(roomId, message)
        }
        const localMessagesByRoom = new Map()
        for (const id of rooms.map((row) => normalizeId(textOf(row, ['id'], ''))).filter(Boolean)) {
          const localRows = await localCollectionMessages(pool, config.kind, id)
          localMessagesByRoom.set(id, localRows)
        }
        const localLatestByRoom = new Map(
          Array.from(localMessagesByRoom.entries())
            .filter(([, rows]) => rows.length > 0)
            .map(([roomId, rows]) => [roomId, rows[rows.length - 1]]),
        )
        const items = rooms
          .filter((row) => {
            const cleanId = normalizeId(textOf(row, ['id'], ''))
            const creatorId = normalizeId(textOf(row, ['creator_id', 'owner_id', 'user_id'], ''))
            const isMember = roomIds.has(cleanId)
            const isCreator = creatorId && identity.ids.has(creatorId)
            const isPublicRoom = boolOf(row, ['is_public', 'public'], config.kind === 'community') && !boolOf(row, ['is_private'], false)
            return config.kind === 'community'
              ? true
              : isMember
          })
          .map((row) => {
          const id = textOf(row, ['id'], '')
          const cleanId = normalizeId(id)
          const creatorId = normalizeId(textOf(row, ['creator_id', 'owner_id', 'user_id'], ''))
          const isCreator = creatorId && identity.ids.has(creatorId)
          const currentMembership = memberships.find((membership) =>
            roomIdOf(membership, config.roomKey) === cleanId &&
            rowMatchesAny(membership, ['user_id', 'profile_id', 'member_id'], identity.ids),
          )
          const currentRole = isCreator
            ? 'owner'
            : (roomIds.has(cleanId)
              ? normalizeCollectionRole(textOf(currentMembership || {}, ['role'], 'member'))
              : '')
          const isPublicRoom = boolOf(row, ['is_public', 'public'], config.kind === 'community') && !boolOf(row, ['is_private'], false)
          const importedLatest = importedLatestByRoom.get(cleanId)
          const localLatest = localLatestByRoom.get(cleanId)
          const latest = toMillis(textOf(localLatest || {}, ['created_at'], '')) > toMillis(textOf(importedLatest || {}, ['created_at'], ''))
            ? localLatest
            : importedLatest
          const lastReadAt = toMillis(lastReadByRoom.get(cleanId))
          const allRoomMessages = [
            ...importedMessages.filter((message) => roomIdOf(message, config.roomKey) === cleanId),
            ...(localMessagesByRoom.get(cleanId) || []),
          ]
          const unreadCount = roomIds.has(cleanId)
            ? allRoomMessages.reduce((count, message) => {
                const senderId = normalizeId(textOf(message, ['sender_id'], ''))
                const messageAt = toMillis(textOf(message, ['created_at', 'updated_at'], ''))
                if (senderId && identity.ids.has(senderId)) return count
                if (lastReadAt && messageAt && messageAt <= lastReadAt) return count
                return count + 1
              }, 0)
            : 0
          return {
            id,
            title: collectionRoomTitle(row, config.titleFallback),
            subtitle: previewFromMessage(latest || {}) || textOf(row, ['last_message', 'description', 'about'], 'Sin actividad reciente'),
            description: textOf(row, ['description', 'about'], ''),
            updatedAt: textOf(latest || {}, ['created_at', 'updated_at'], '') || textOf(row, ['last_message_at', 'updated_at', 'created_at'], ''),
            kind: config.kind,
            avatarUrl: collectionRoomAvatar(row),
            profileId: null,
            unreadCount,
            lastMessageId: textOf(latest || {}, ['id'], ''),
            lastMessageSenderId: textOf(latest || {}, ['sender_id'], ''),
            lastMessageContentType: textOf(latest || {}, ['content_type'], 'text') || 'text',
            isMember: roomIds.has(cleanId) || Boolean(isCreator),
            isPinned: false,
            isMuted: false,
            isArchived: false,
            isFavorite: false,
            isPublic: isPublicRoom,
            onlyAdminsCanTalk: boolOf(row, ['only_admins_can_talk', 'onlyAdminsCanTalk'], false),
            dailyMessageLimit: Math.max(0, Math.min(numberOf(row, ['daily_message_limit', 'dailyMessageLimit'], 0), 1000)),
            restUntil: textOf(currentMembership || {}, ['rest_until', 'restUntil'], '') || null,
            creator_id: creatorId || null,
            current_user_role: currentRole || null,
            folders: [],
            openPath: `${config.openPathPrefix}${id}`,
          }
        }).sort((a, b) => toMillis(b.updatedAt) - toMillis(a.updatedAt)).slice(0, 80)
        return res.json({ items })
      }

      const [profiles, localProfiles, chats, importedParticipants, importedMessages, fallbackChats] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
        importedRows(pool, 'chats', 5000),
        importedRows(pool, 'chat_participants', 10_000),
        importedRows(pool, 'messages', 10_000),
        localChats(pool, 5000),
      ])
      const nicknamesByTarget = await localNicknames(pool, identity.displayUserId)
      const hiddenByChat = await localHiddenChats(pool, identity.displayUserId)
      const chatIdsFromLocal = fallbackChats.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const importedChatIds = chats.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      // Load localChatParticipants for ALL chats (imported + local) so cleared_at is always applied
      const allChatIdsForParticipants = Array.from(cleanSet([...chatIdsFromLocal, ...importedChatIds]))
      const participants = [
        ...importedParticipants,
        ...(await localChatParticipants(pool, allChatIdsForParticipants)),
      ]
      const allChats = [...chats, ...fallbackChats]

      const participantChatIds = cleanSet(
        participants
          .filter((row) => rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids))
          .map((row) => textOf(row, ['chat_id'], '')),
      )

      const chatRows = allChats.filter((row) => {
        const chatId = normalizeId(textOf(row, ['id'], ''))
        const isGroup = boolOf(row, ['is_group'], false)
        return !isGroup && (rowParticipantsContain(row, identity.ids) || participantChatIds.has(chatId))
      })
      const chatIds = chatRows.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const chatIdSet = cleanSet(chatIds)
      const messages = [
        ...importedMessages.filter((row) => chatIdSet.has(normalizeId(textOf(row, ['chat_id'], '')))),
        ...(await localMessages(pool, chatIds)),
      ].filter((message) => messageVisibleTo(message, identity))
      const profilesById = indexProfiles([...profiles, ...localProfiles])

      const participantByChat = new Map()
      const otherByChat = new Map()
      const lastReadByChat = new Map()
      for (const row of participants) {
        const chatId = normalizeId(textOf(row, ['chat_id'], ''))
        const userId = normalizeId(textOf(row, ['user_id', 'profile_id'], ''))
        if (!chatId || !userId) continue
        if (identity.ids.has(userId)) {
          participantByChat.set(chatId, row)
          const lastReadAt = textOf(row, ['last_read_at', 'lastReadAt'], '')
          if (lastReadAt) lastReadByChat.set(chatId, lastReadAt)
        } else if (!otherByChat.has(chatId)) {
          otherByChat.set(chatId, userId)
        }
      }

      const latestByChat = new Map()
      const unreadByChat = new Map()
      for (const message of sortMessagesNewestFirst(messages)) {
        const chatId = normalizeId(textOf(message, ['chat_id'], ''))
        if (!chatId) continue
        const messageAt = toMillis(textOf(message, ['created_at', 'updated_at'], ''))
        const clearedAt = toMillis(textOf(participantByChat.get(chatId) || {}, ['cleared_at', 'clearedAt'], ''))
        if (clearedAt && messageAt && messageAt <= clearedAt) continue

        if (!latestByChat.has(chatId)) latestByChat.set(chatId, message)
        const senderId = normalizeId(textOf(message, ['sender_id'], ''))
        const readAt = textOf(message, ['read_at'], '')
        const status = textOf(message, ['status'], '').toLowerCase()
        const lastReadAt = toMillis(lastReadByChat.get(chatId))
        if (lastReadAt && messageAt && messageAt <= lastReadAt) continue
        if (senderId && !identity.ids.has(senderId) && !readAt && status !== 'read' && status !== 'seen') {
          unreadByChat.set(chatId, (unreadByChat.get(chatId) || 0) + 1)
        }
      }

      const items = chatRows
        .map((row) => {
          const id = textOf(row, ['id'], '')
          const cleanChatId = normalizeId(id)
          let otherId = otherByChat.get(cleanChatId)
          if (!otherId) {
            otherId = arrayOf(row, ['participants']).map(normalizeId).find((entry) => !identity.ids.has(entry)) || ''
          }
          const otherProfile = otherId ? profilesById.get(otherId) : null
          const participant = participantByChat.get(cleanChatId) || {}
          const myClearedAt = toMillis(textOf(participant, ['cleared_at', 'clearedAt'], ''))
          let latest = latestByChat.get(cleanChatId) || {}
          if (myClearedAt && toMillis(textOf(latest, ['created_at', 'updated_at'], '')) <= myClearedAt) {
            latest = {} // Hide latest message if it was cleared
          }
          const effectiveUpdatedAt =
            textOf(latest, ['created_at', 'updated_at'], '') ||
            (myClearedAt ? textOf(participant, ['cleared_at', 'clearedAt'], '') : '') ||
            textOf(row, ['last_message_at', 'updated_at', 'created_at'], '')
          const hiddenAt = hiddenByChat.get(cleanChatId)
          if (hiddenAt && toMillis(hiddenAt) >= toMillis(effectiveUpdatedAt)) return null
          const fallbackName = textOf(row, ['other_user_name', 'display_name', 'user_name', 'name'], 'Chat privado')
          const nickname = nicknamesByTarget.get(normalizeId(otherId)) || ''
          const hasVisibleLatest = Boolean(textOf(latest, ['id'], ''))
          if (!hasVisibleLatest) return null
          return {
            id,
            title: nickname || (otherProfile ? profileName(otherProfile, fallbackName) : fallbackName),
            subtitle: hasVisibleLatest ? previewFromMessage(latest) : 'Sin mensajes',
            updatedAt: effectiveUpdatedAt,
            kind: 'chat',
            avatarUrl: otherProfile ? profileAvatar(otherProfile) : textOf(row, ['other_user_image', 'avatar_url'], ''),
            profileId: otherId || null,
            nickname: nickname || null,
            clearedAt: myClearedAt ? textOf(participant, ['cleared_at', 'clearedAt'], '') : null,
            unreadCount: unreadByChat.get(cleanChatId) || 0,
            lastMessageId: hasVisibleLatest ? textOf(latest, ['id'], '') : '',
            lastMessageSenderId: hasVisibleLatest ? textOf(latest, ['sender_id'], '') : '',
            lastMessageContentType: hasVisibleLatest ? textOf(latest, ['content_type'], 'text') || 'text' : 'text',
            isPinned: boolOf(participant, ['is_pinned'], false),
            isMuted: boolOf(participant, ['is_muted'], false),
            isArchived: boolOf(participant, ['is_archived'], false),
            isFavorite: boolOf(participant, ['is_favorite'], false),
            folders: [],
            openPath: `/app/messages/${id}`,
          }
        })
        .filter(Boolean)
        .sort((a, b) => toMillis(b.updatedAt) - toMillis(a.updatedAt))
        .slice(0, 80)

      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/chats', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const profiles = await allPublicProfiles(pool)
      const resolveParticipantId = (value) => {
        const clean = normalizeId(value)
        if (!clean) return ''
        if (clean.startsWith('placeholder_')) return ''
        const profile = profiles.find((row) => profileAliasSet(row).has(clean))
        return profile ? profileId(profile) : clean
      }
      const requestedTargetParticipants = [
        ...asArray(req.body?.participants),
        req.body?.participantId,
        req.body?.participant_id,
      ]
      if (requestedTargetParticipants.length === 0) {
        return res.status(400).json({ error: 'DIRECT_CHAT_TARGET_REQUIRED' })
      }
      const participants = Array.from(cleanSet([
        ...requestedTargetParticipants.map(resolveParticipantId),
        identity.displayUserId,
      ])).sort()
      if (participants.length !== 2 || !participants.includes(identity.displayUserId)) {
        return res.status(400).json({ error: 'DIRECT_CHAT_REQUIRES_TWO_PARTICIPANTS' })
      }

      const participantKey = chatParticipantKey(participants)
      let chatRow = null
      const existingChat = await pool.query(
        `SELECT
            id::text,
            participants,
            participant_key,
            is_group,
            group_name,
            last_message,
            last_message_at,
            last_message_sender_id,
            last_message_id,
            created_at,
            updated_at
         FROM fallback_chats
         WHERE participant_key = $1
         LIMIT 1`,
        [participantKey],
      )
      chatRow = existingChat.rows[0] || null

      if (!chatRow) {
        try {
          const inserted = await pool.query(
            `INSERT INTO fallback_chats(participants, participant_key, is_group, created_at, updated_at)
             VALUES ($1::text[], $2, FALSE, NOW(), NOW())
             RETURNING
                id::text,
                participants,
                participant_key,
                is_group,
                group_name,
                last_message,
                last_message_at,
                last_message_sender_id,
                last_message_id,
                created_at,
                updated_at`,
            [participants, participantKey],
          )
          chatRow = inserted.rows[0]
        } catch (error) {
          if (error?.code !== '23505') throw error
          const recovered = await pool.query(
            `SELECT
                id::text,
                participants,
                participant_key,
                is_group,
                group_name,
                last_message,
                last_message_at,
                last_message_sender_id,
                last_message_id,
                created_at,
                updated_at
             FROM fallback_chats
             WHERE participant_key = $1
             LIMIT 1`,
            [participantKey],
          )
          chatRow = recovered.rows[0] || null
        }
      }

      if (!chatRow) return res.status(500).json({ error: 'DIRECT_CHAT_CREATE_FAILED' })
      const chat = normalizeImportedRow(chatRow)
      await pool.query(
        `DELETE FROM fallback_chat_hidden
         WHERE lower(chat_id::text) = $1
           AND lower(user_id) = $2`,
        [normalizeId(chat.id), identity.displayUserId],
      )
      for (const participantId of participants) {
        await pool.query(
          `INSERT INTO fallback_chat_participants(chat_id, user_id, joined_at)
           VALUES ($1::uuid, $2, NOW())
           ON CONFLICT (chat_id, user_id) DO NOTHING`,
          [chat.id, participantId],
        )
      }
      res.status(201).json({ chat })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/chats/:chatId/clear', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const cleanChatId = normalizeId(req.params.chatId)
      const uuidChatId = validUuid(cleanChatId)
      if (!cleanChatId || !uuidChatId) return res.status(400).json({ error: 'CHAT_ID_REQUIRED' })

      const now = new Date().toISOString()

      // Mark cleared_at in fallback_chat_participants (create row if missing)
      await pool.query(
        `INSERT INTO fallback_chat_participants(chat_id, user_id, cleared_at, created_at, updated_at)
         VALUES ($1::uuid, $2, $3::timestamptz, NOW(), NOW())
         ON CONFLICT (chat_id, user_id) DO UPDATE SET
           cleared_at = GREATEST(
             COALESCE(fallback_chat_participants.cleared_at, 'epoch'::timestamptz),
             EXCLUDED.cleared_at
           ),
           updated_at = NOW()`,
        [uuidChatId, identity.displayUserId, now],
      )

      res.json({ ok: true, chatId: cleanChatId, clearedAt: now })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/messages/chats/:chatId', authRequired, async (req, res, next) => {

    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const cleanChatId = normalizeId(req.params.chatId)
      const uuidChatId = validUuid(cleanChatId)
      if (!cleanChatId || !uuidChatId) return res.status(400).json({ error: 'CHAT_ID_REQUIRED' })

      const identity = userIdentity(user)
      const [fallbackChatRows, importedChatRows, importedParticipants, fallbackParticipants] = await Promise.all([
        localChats(pool, 5000),
        importedRows(pool, 'chats', 5000),
        importedRows(pool, 'chat_participants', 10_000),
        localChatParticipants(pool, [cleanChatId]),
      ])
      const chat = [...fallbackChatRows, ...importedChatRows]
        .find((row) => normalizeId(textOf(row, ['id'], '')) === cleanChatId)
      const participantRows = [...importedParticipants, ...fallbackParticipants]
        .filter((row) => normalizeId(textOf(row, ['chat_id'], '')) === cleanChatId)
      const hasAccess =
        (chat && rowParticipantsContain(chat, identity.ids)) ||
        participantRows.some((row) => rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids))
      if (!hasAccess) return res.status(403).json({ error: 'CHAT_ACCESS_DENIED' })

      await ensureFallbackChatExists(pool, cleanChatId, chat || {}, participantRows)
      await pool.query(
        `INSERT INTO fallback_chat_hidden(chat_id, user_id, hidden_at)
         VALUES ($1::uuid, $2, NOW())
         ON CONFLICT (chat_id, user_id) DO UPDATE SET hidden_at = NOW()`,
        [uuidChatId, identity.displayUserId],
      )
      await pool.query(
        `INSERT INTO fallback_chat_participants(chat_id, user_id, joined_at, cleared_at, last_read_at, created_at, updated_at)
         VALUES ($1::uuid, $2, NOW(), NOW(), NOW(), NOW(), NOW())
         ON CONFLICT (chat_id, user_id) DO UPDATE SET
           cleared_at = GREATEST(
             COALESCE(fallback_chat_participants.cleared_at, 'epoch'::timestamptz),
             EXCLUDED.cleared_at
           ),
           last_read_at = GREATEST(
             COALESCE(fallback_chat_participants.last_read_at, 'epoch'::timestamptz),
             EXCLUDED.last_read_at
           ),
           updated_at = NOW()`,
        [uuidChatId, identity.displayUserId],
      )
      res.json({ ok: true, chatId: cleanChatId, hidden: true })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/messages/chats/:chatId/preferences', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const cleanChatId = normalizeId(req.params.chatId)
      const uuidChatId = validUuid(cleanChatId)
      if (!cleanChatId || !uuidChatId) return res.status(400).json({ error: 'CHAT_ID_REQUIRED' })

      const identity = userIdentity(user)
      const [fallbackChatRows, importedChatRows, importedParticipants, fallbackParticipants] = await Promise.all([
        localChats(pool, 5000),
        importedRows(pool, 'chats', 5000),
        importedRows(pool, 'chat_participants', 10_000),
        localChatParticipants(pool, [cleanChatId]),
      ])
      const chat = [...fallbackChatRows, ...importedChatRows]
        .find((row) => normalizeId(textOf(row, ['id'], '')) === cleanChatId)
      const participantRows = [...importedParticipants, ...fallbackParticipants]
        .filter((row) => normalizeId(textOf(row, ['chat_id'], '')) === cleanChatId)
      const hasAccess =
        (chat && rowParticipantsContain(chat, identity.ids)) ||
        participantRows.some((row) => rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids))
      if (!hasAccess) return res.status(403).json({ error: 'CHAT_ACCESS_DENIED' })

      const allowed = [
        ['isMuted', 'is_muted'],
        ['isPinned', 'is_pinned'],
        ['isArchived', 'is_archived'],
        ['isFavorite', 'is_favorite'],
      ]
      const assignments = []
      const values = [uuidChatId, identity.displayUserId]
      for (const [camelKey, column] of allowed) {
        if (typeof req.body?.[camelKey] === 'boolean') {
          values.push(Boolean(req.body[camelKey]))
          assignments.push(`${column} = $${values.length}`)
        }
      }
      if (assignments.length === 0) return res.status(400).json({ error: 'NO_PREFERENCES_TO_UPDATE' })

      await ensureFallbackChatExists(pool, cleanChatId, chat || {}, participantRows)
      await pool.query(
        `INSERT INTO fallback_chat_participants(chat_id, user_id, joined_at)
         VALUES ($1::uuid, $2, NOW())
         ON CONFLICT (chat_id, user_id) DO NOTHING`,
        [uuidChatId, identity.displayUserId],
      )
      await pool.query(
        `UPDATE fallback_chat_participants
         SET ${assignments.join(', ')}
         WHERE chat_id = $1::uuid
           AND lower(user_id) = $2`,
        values,
      )
      res.json({ ok: true, chatId: cleanChatId })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/chats/:chatId/read', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const cleanChatId = normalizeId(req.params.chatId)
      const uuidChatId = validUuid(cleanChatId)
      if (!cleanChatId || !uuidChatId) return res.status(400).json({ error: 'CHAT_ID_REQUIRED' })

      const identity = userIdentity(user)
      const localParticipants = await localChatParticipants(pool, [cleanChatId])
      const importedParticipants = (await importedRows(pool, 'chat_participants', 10_000))
        .filter((row) => normalizeId(textOf(row, ['chat_id'], '')) === cleanChatId)
      const participantRows = [...importedParticipants, ...localParticipants]
      const participantIds = Array.from(cleanSet(
        participantRows.map((row) => textOf(row, ['user_id', 'profile_id'], '')),
      ))
      if (participantRows.length > 0) {
        const hasAccess = participantRows.some((row) => rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids))
        if (!hasAccess) return res.status(403).json({ error: 'CHAT_ACCESS_DENIED' })
      }

      const fallbackChat = (await localChats(pool, 5000))
        .find((row) => normalizeId(textOf(row, ['id'], '')) === cleanChatId)
      if (!fallbackChat) {
        await pool.query(
          `INSERT INTO fallback_chats(id, participants, is_group, created_at, updated_at)
           VALUES ($1::uuid, $2::text[], FALSE, NOW(), NOW())
           ON CONFLICT (id) DO UPDATE SET
             participants = CASE
               WHEN array_length(fallback_chats.participants, 1) IS NULL OR array_length(fallback_chats.participants, 1) = 0
               THEN EXCLUDED.participants
               ELSE fallback_chats.participants
             END,
             updated_at = GREATEST(fallback_chats.updated_at, EXCLUDED.updated_at)`,
          [uuidChatId, participantIds.length > 0 ? participantIds : [identity.displayUserId]],
        )
      }

      await pool.query(
        `UPDATE fallback_messages
         SET status = 'read',
             delivered_at = COALESCE(delivered_at, NOW()),
             read_at = COALESCE(read_at, NOW()),
             updated_at = NOW()
         WHERE lower(chat_id) = $1
           AND NOT (lower(sender_id) = ANY($2::text[]))`,
        [cleanChatId, Array.from(identity.ids)],
      )

      await pool.query(
        `INSERT INTO fallback_chat_participants(chat_id, user_id, joined_at, last_read_at)
         VALUES ($1::uuid, $2, NOW(), NOW())
         ON CONFLICT (chat_id, user_id) DO UPDATE SET
           last_read_at = CASE
             WHEN fallback_chat_participants.last_read_at IS NULL
               OR fallback_chat_participants.last_read_at < EXCLUDED.last_read_at
             THEN EXCLUDED.last_read_at
             ELSE fallback_chat_participants.last_read_at
           END`,
        [uuidChatId, identity.displayUserId],
      )

      res.json({ ok: true, chatId: cleanChatId, lastReadAt: new Date().toISOString() })
    } catch (error) {
      next(error)
    }
  })

  app.post(['/v1/data/messages/groups/:roomId/read', '/v1/data/messages/communities/:roomId/read'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(messageCollectionFromRequest(req))
      const roomId = normalizeId(req.params.roomId)
      if (!config || !roomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const identity = userIdentity(user)
      const memberships = deduplicateMembers([
        ...(await importedRows(pool, config.memberTable, 5000)),
        ...(await localCollectionMembers(pool, config.kind, 10_000)),
      ], config.roomKey).filter((row) => roomIdOf(row, config.roomKey) === roomId)
      const rooms = [
        ...(await importedRows(pool, config.roomTable, 5000)),
        ...(await localCollectionRooms(pool, config.kind, 5000)),
      ]
      const room = rooms.find((row) => normalizeId(textOf(row, ['id'], '')) === roomId)
      if (!room) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      const hasMembership = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids) || memberships.some((row) => {
        const status = textOf(row, ['status', 'member_status'], '').toLowerCase()
        return rowMatchesAny(row, ['user_id', 'profile_id', 'member_id'], identity.ids) &&
          !['left', 'removed', 'deleted'].includes(status)
      })
      if (!hasMembership) return res.status(403).json({ error: 'ROOM_ACCESS_DENIED' })

      await pool.query(
        `UPDATE fallback_room_messages
         SET status = 'read',
             delivered_at = COALESCE(delivered_at, NOW()),
             read_at = COALESCE(read_at, NOW()),
             updated_at = NOW()
         WHERE room_kind = $1 AND lower(room_id) = $2
           AND NOT (lower(sender_id) = ANY($3::text[]))`,
        [config.kind, roomId, Array.from(identity.ids)],
      )

      await pool.query(
        `INSERT INTO fallback_collection_message_receipts(
           room_kind, room_id, message_id, user_id, delivered_at, read_at, updated_at
         )
         SELECT room_kind, room_id, id, $3, NOW(), NOW(), NOW()
         FROM fallback_room_messages
         WHERE room_kind = $1 AND lower(room_id) = $2
           AND NOT (lower(sender_id) = ANY($4::text[]))
         ON CONFLICT (room_kind, message_id, user_id) DO UPDATE SET
           delivered_at = COALESCE(fallback_collection_message_receipts.delivered_at, EXCLUDED.delivered_at),
           read_at = COALESCE(fallback_collection_message_receipts.read_at, EXCLUDED.read_at),
           updated_at = NOW()`,
        [config.kind, roomId, identity.displayUserId, Array.from(identity.ids)],
      )

      await pool.query(
        `INSERT INTO fallback_collection_read_states(room_kind, room_id, user_id, last_read_at, updated_at)
         VALUES ($1, $2, $3, NOW(), NOW())
         ON CONFLICT (room_kind, room_id, user_id) DO UPDATE SET
           last_read_at = CASE
             WHEN fallback_collection_read_states.last_read_at < EXCLUDED.last_read_at
             THEN EXCLUDED.last_read_at
             ELSE fallback_collection_read_states.last_read_at
           END,
           updated_at = NOW()`,
        [config.kind, roomId, identity.displayUserId],
      )

      res.json({ ok: true, roomId, kind: config.kind, lastReadAt: new Date().toISOString() })
    } catch (error) {
      next(error)
    }
  })

  app.post(['/v1/data/messages/groups', '/v1/data/messages/communities'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(messageCollectionFromRequest(req))
      if (!config) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })

      const name = cleanText(req.body?.name, 120)
      if (!name) return res.status(400).json({ error: 'NAME_REQUIRED' })

      const identity = userIdentity(user)
      const requestedId = validUuid(req.body?.id)
      const description = cleanText(req.body?.description, 500) || null
      const image = cleanText(req.body?.image || req.body?.imageUrl, 700) || null
      const category = config.kind === 'community' ? cleanText(req.body?.category, 80) || null : null
      const isPublic = config.kind === 'community'
        ? (Object.prototype.hasOwnProperty.call(req.body || {}, 'isPublic') ? Boolean(req.body.isPublic) : Boolean(req.body?.is_public ?? true))
        : false
      const memberIds = Array.from(cleanSet([...asArray(req.body?.members), ...asArray(req.body?.memberIds), identity.displayUserId]))

      const { rows } = await pool.query(
        `INSERT INTO fallback_collection_rooms(
            id,
            room_kind,
            name,
            image,
            description,
            category,
            is_public,
            creator_id,
            is_deleted,
            only_admins_can_talk,
            daily_message_limit,
            created_at,
            updated_at
         )
         VALUES (COALESCE($1::uuid, gen_random_uuid()), $2, $3, $4, $5, $6, $7, $8, FALSE, FALSE, 0, NOW(), NOW())
         ON CONFLICT (id) DO UPDATE SET
            room_kind = EXCLUDED.room_kind,
            name = EXCLUDED.name,
            image = EXCLUDED.image,
            description = EXCLUDED.description,
            category = EXCLUDED.category,
            is_public = EXCLUDED.is_public,
            creator_id = EXCLUDED.creator_id,
            is_deleted = FALSE,
            updated_at = NOW()
         RETURNING
            id::text,
            room_kind,
            name,
            image,
            description,
            category,
            is_public,
            creator_id,
            is_deleted,
            only_admins_can_talk,
            daily_message_limit,
            created_at,
            updated_at`,
        [requestedId, config.kind, name, image, description, category, isPublic, identity.displayUserId],
      )
      const roomId = textOf(normalizeImportedRow(rows[0]), ['id'], '')

      for (const memberId of memberIds) {
        await pool.query(
          `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at)
           VALUES ($1, $2, $3, $4, NOW())
           ON CONFLICT (room_kind, room_id, user_id)
           DO UPDATE SET role = EXCLUDED.role`,
          [config.kind, roomId, memberId, memberId === identity.displayUserId ? 'owner' : 'member'],
        )
      }

      res.status(201).json({ room: normalizeImportedRow(rows[0]), id: roomId })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/messages/communities/explore', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const q = String(req.query.q || '').trim().toLowerCase()

      const [importedRooms, localRooms, deletedRoomIds] = await Promise.all([
        importedRows(pool, 'communities', 5000),
        localCollectionRooms(pool, 'community', 5000),
        localDeletedCollectionRoomIds(pool, 'community')
      ])

      const results = mergeRowsById([...importedRooms, ...localRooms])
        .filter((row) => {
           const roomId = normalizeId(textOf(row, ['id'], ''))
           if (!roomId || deletedRoomIds.has(roomId) || boolOf(row, ['is_deleted', 'deleted'], false)) return false
           if (!boolOf(row, ['is_public', 'isPublic'], true)) return false

           if (q) {
             const name = textOf(row, ['name'], '').toLowerCase()
             const desc = textOf(row, ['description'], '').toLowerCase()
             const category = textOf(row, ['category'], '').toLowerCase()
             return name.includes(q) || desc.includes(q) || category.includes(q)
           }
           return true
        })
        .map(row => ({
           id: textOf(row, ['id'], ''),
           name: textOf(row, ['name'], ''),
           description: textOf(row, ['description'], null),
           image: textOf(row, ['image', 'image_url', 'imageUrl'], null),
           category: textOf(row, ['category'], null),
           is_public: boolOf(row, ['is_public', 'isPublic'], true),
           creator_id: textOf(row, ['creator_id', 'owner_id'], ''),
           is_deleted: false,
           created_at: textOf(row, ['created_at', 'createdAt'], new Date().toISOString()),
           updated_at: textOf(row, ['updated_at', 'updatedAt'], new Date().toISOString())
        }))

      res.json(results)
    } catch (error) {
      next(error)
    }
  })

  app.get(['/v1/data/messages/groups/:roomId', '/v1/data/messages/communities/:roomId'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const payload = await loadCollectionRoomPayload(pool, user, messageCollectionFromRequest(req), req.params.roomId)
      if (!payload) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      res.json(payload)
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/messages/communities/:roomId/roles', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const roomId = normalizeId(req.params.roomId)
      if (!validUuid(roomId)) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const payload = await loadCollectionRoomPayload(pool, user, 'community', roomId)
      if (!payload) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })

      const identity = userIdentity(user)
      const room = payload.room || payload.chat || {}
      const me = (payload.participants || [])
        .find((participant) => identity.ids.has(normalizeId(participant.id)))
      const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
      if (!isCollectionAdminRole(me?.role, { isCreator })) {
        return res.status(403).json({ error: 'ROLE_PREVIEW_DENIED' })
      }

      res.json({
        currentRole: normalizeCollectionRole(me?.role, { isCreator }),
        roles: ['owner', 'admin', 'moderator', 'member']
          .map((role) => collectionRoleCapabilities(role)),
      })
    } catch (error) {
      next(error)
    }
  })

  async function communityChannelContext(req, res) {
    const user = await getCurrentUser(pool, req)
    if (!user) {
      res.status(404).json({ error: 'USER_NOT_FOUND' })
      return null
    }
    const roomId = normalizeId(req.params.roomId)
    if (!validUuid(roomId)) {
      res.status(400).json({ error: 'ROOM_ID_REQUIRED' })
      return null
    }
    const payload = await loadCollectionRoomPayload(pool, user, 'community', roomId)
    if (!payload) {
      res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      return null
    }
    const identity = userIdentity(user)
    const room = payload.room || payload.chat || {}
    const me = (payload.participants || [])
      .find((participant) => identity.ids.has(normalizeId(participant.id)))
    const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
    if (!isCreator && !me) {
      res.status(403).json({ error: 'ROOM_ACCESS_DENIED' })
      return null
    }
    const role = normalizeCollectionRole(me?.role, { isCreator })
    const capabilities = collectionRoleCapabilities(role)
    const defaultChannel = await ensureDefaultCommunityChannel(pool, roomId, identity.displayUserId)
    return { user, roomId, identity, role, capabilities, defaultChannel }
  }

  app.get('/v1/data/messages/communities/:roomId/channels', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      const { rows } = await pool.query(
        `SELECT *
         FROM fallback_community_channels
         WHERE lower(community_id) = $1 AND is_deleted = FALSE
         ORDER BY is_default DESC, position ASC, created_at ASC`,
        [context.roomId],
      )
      res.json({
        currentRole: context.role,
        channels: rows.map((row) => communityChannelPayload(row, {
          canManage: context.capabilities.canManageChannels,
          canPause: context.capabilities.canPauseChannels,
        })),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/communities/:roomId/channels', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      if (!context.capabilities.canManageChannels) {
        return res.status(403).json({ error: 'CHANNEL_MANAGE_DENIED' })
      }
      const name = cleanText(req.body?.name, 60)
      const rawType = String(req.body?.type || req.body?.channelType || '').trim().toLowerCase()
      if (!name) return res.status(400).json({ error: 'CHANNEL_NAME_REQUIRED' })
      if (!['text', 'media', 'voice'].includes(rawType)) {
        return res.status(400).json({ error: 'CHANNEL_TYPE_INVALID' })
      }
      const description = cleanText(req.body?.description, 240) || null
      const position = Math.max(1, Math.min(Math.trunc(Number(req.body?.position) || 100), 10_000))
      const { rows } = await pool.query(
        `INSERT INTO fallback_community_channels(
           community_id, name, description, channel_type, position, created_by
         )
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING *`,
        [context.roomId, name, description, rawType, position, context.identity.displayUserId],
      )
      res.status(201).json({
        channel: communityChannelPayload(rows[0], { canManage: true, canPause: true }),
      })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/messages/communities/:roomId/channels/:channelId', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      if (!context.capabilities.canManageChannels) {
        return res.status(403).json({ error: 'CHANNEL_MANAGE_DENIED' })
      }
      const channelId = normalizeId(req.params.channelId)
      if (!validUuid(channelId)) return res.status(400).json({ error: 'CHANNEL_ID_REQUIRED' })
      const name = cleanText(req.body?.name, 60)
      const rawType = String(req.body?.type || req.body?.channelType || '').trim().toLowerCase()
      if (!name) return res.status(400).json({ error: 'CHANNEL_NAME_REQUIRED' })
      if (!['text', 'media', 'voice'].includes(rawType)) {
        return res.status(400).json({ error: 'CHANNEL_TYPE_INVALID' })
      }
      const description = cleanText(req.body?.description, 240) || null
      const position = Math.max(0, Math.min(Math.trunc(Number(req.body?.position) || 0), 10_000))
      const { rows } = await pool.query(
        `UPDATE fallback_community_channels
         SET name = CASE WHEN is_default THEN 'General' ELSE $3 END,
             description = $4,
             channel_type = CASE WHEN is_default THEN 'text' ELSE $5 END,
             position = CASE WHEN is_default THEN 0 ELSE $6 END,
             updated_at = NOW()
         WHERE lower(community_id) = $1
           AND lower(id::text) = $2
           AND is_deleted = FALSE
         RETURNING *`,
        [context.roomId, channelId, name, description, rawType, position],
      )
      if (!rows[0]) return res.status(404).json({ error: 'CHANNEL_NOT_FOUND' })
      res.json({
        channel: communityChannelPayload(rows[0], { canManage: true, canPause: true }),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/communities/:roomId/channels/:channelId/pause', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      if (!context.capabilities.canPauseChannels) {
        return res.status(403).json({ error: 'CHANNEL_PAUSE_DENIED' })
      }
      const channelId = normalizeId(req.params.channelId)
      if (!validUuid(channelId)) return res.status(400).json({ error: 'CHANNEL_ID_REQUIRED' })
      const pause = req.body?.paused !== false && req.body?.pausedUntil !== null
      const reason = pause ? cleanText(req.body?.reason, 240) : null
      let pausedUntil = null
      if (pause) {
        if (!reason) return res.status(400).json({ error: 'CHANNEL_PAUSE_REASON_REQUIRED' })
        const requestedUntil = req.body?.pausedUntil ? new Date(String(req.body.pausedUntil)) : null
        const requestedMinutes = Math.trunc(Number(req.body?.minutes) || 60)
        const fallbackUntil = new Date(Date.now() + Math.max(5, Math.min(requestedMinutes, 43_200)) * 60_000)
        const chosen = requestedUntil && Number.isFinite(requestedUntil.getTime()) ? requestedUntil : fallbackUntil
        if (chosen.getTime() <= Date.now() || chosen.getTime() > Date.now() + 30 * 24 * 60 * 60 * 1000) {
          return res.status(400).json({ error: 'CHANNEL_PAUSE_UNTIL_INVALID' })
        }
        pausedUntil = chosen.toISOString()
      }
      const { rows } = await pool.query(
        `UPDATE fallback_community_channels
         SET paused_until = $3::timestamptz,
             pause_reason = $4,
             paused_by = $5,
             updated_at = NOW()
         WHERE lower(community_id) = $1
           AND lower(id::text) = $2
           AND is_deleted = FALSE
         RETURNING *`,
        [context.roomId, channelId, pausedUntil, reason, pause ? context.identity.displayUserId : null],
      )
      if (!rows[0]) return res.status(404).json({ error: 'CHANNEL_NOT_FOUND' })
      res.json({
        channel: communityChannelPayload(rows[0], {
          canManage: context.capabilities.canManageChannels,
          canPause: true,
        }),
      })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/messages/communities/:roomId/channels/:channelId', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      if (!context.capabilities.canManageChannels) {
        return res.status(403).json({ error: 'CHANNEL_MANAGE_DENIED' })
      }
      const channelId = normalizeId(req.params.channelId)
      if (!validUuid(channelId)) return res.status(400).json({ error: 'CHANNEL_ID_REQUIRED' })
      const { rows } = await pool.query(
        `UPDATE fallback_community_channels
         SET is_deleted = TRUE, updated_at = NOW()
         WHERE lower(community_id) = $1
           AND lower(id::text) = $2
           AND is_default = FALSE
           AND is_deleted = FALSE
         RETURNING id::text`,
        [context.roomId, channelId],
      )
      if (!rows[0]) return res.status(409).json({ error: 'DEFAULT_CHANNEL_CANNOT_BE_DELETED' })
      await pool.query(
        `UPDATE fallback_room_messages
         SET channel_id = $3::uuid, updated_at = NOW()
         WHERE room_kind = 'community'
           AND lower(room_id) = $1
           AND lower(COALESCE(channel_id::text, '')) = $2`,
        [context.roomId, channelId, context.defaultChannel.id],
      )
      res.json({ ok: true, channelId })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/messages/communities/:roomId/member-profiles', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      const { rows } = await pool.query(
        `SELECT *
         FROM fallback_community_member_profiles
         WHERE lower(community_id) = $1
         ORDER BY updated_at DESC`,
        [context.roomId],
      )
      res.json({
        profile: rows
          .map((row) => communityMemberProfilePayload(row))
          .find((profile) => context.identity.ids.has(normalizeId(profile.userId)))
          || communityMemberProfilePayload(null, {
            communityId: context.roomId,
            userId: context.identity.displayUserId,
          }),
        profiles: rows.map((row) => communityMemberProfilePayload(row)),
      })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/messages/communities/:roomId/member-profile', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      const current = await pool.query(
        `SELECT *
         FROM fallback_community_member_profiles
         WHERE lower(community_id) = $1 AND lower(user_id) = ANY($2::text[])
         ORDER BY updated_at DESC
         LIMIT 1`,
        [context.roomId, Array.from(context.identity.ids)],
      )
      const existing = current.rows[0] || {}
      const has = (key) => Object.prototype.hasOwnProperty.call(req.body || {}, key)
      const alias = has('alias')
        ? cleanText(req.body.alias, 50) || null
        : textOf(existing, ['alias'], null)
      const bio = has('bio')
        ? cleanText(req.body.bio, 240) || null
        : textOf(existing, ['bio'], null)
      const distinctive = has('distinctive')
        ? cleanText(req.body.distinctive, 24) || null
        : textOf(existing, ['distinctive'], null)
      const persistedUserId = normalizeId(textOf(
        existing,
        ['user_id'],
        context.identity.displayUserId,
      )) || context.identity.displayUserId
      const { rows } = await pool.query(
        `INSERT INTO fallback_community_member_profiles(
           community_id, user_id, alias, bio, distinctive
         )
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (community_id, user_id)
         DO UPDATE SET alias = EXCLUDED.alias,
           bio = EXCLUDED.bio,
           distinctive = EXCLUDED.distinctive,
           updated_at = NOW()
         RETURNING *`,
        [context.roomId, persistedUserId, alias, bio, distinctive],
      )
      res.json({ profile: communityMemberProfilePayload(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  async function loadCommunityEvent(context, eventId) {
    const { rows } = await pool.query(
      `SELECT *
       FROM fallback_community_events
       WHERE lower(community_id) = $1 AND lower(id::text) = $2
       LIMIT 1`,
      [context.roomId, eventId],
    )
    const event = rows[0]
    if (!event) return null
    const attendance = await pool.query(
      `SELECT user_id, status, reminder_minutes, reminder_sent_at
       FROM fallback_community_event_attendees
       WHERE event_id = $1::uuid`,
      [eventId],
    )
    const mine = attendance.rows.find((row) =>
      context.identity.ids.has(normalizeId(row.user_id)),
    )
    return communityEventPayload(event, {
      attendanceCount: attendance.rows.filter((row) => row.status === 'going').length,
      currentAttendance: mine?.status || null,
      reminderMinutes: mine?.reminder_minutes ?? null,
      reminderSentAt: mine?.reminder_sent_at || null,
      canManage: context.capabilities.canManageEvents,
    })
  }

  app.get('/v1/data/messages/communities/:roomId/events', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      const { rows } = await pool.query(
        `SELECT *
         FROM fallback_community_events
         WHERE lower(community_id) = $1
           AND starts_at >= NOW() - INTERVAL '24 hours'
         ORDER BY is_cancelled ASC, starts_at ASC
         LIMIT 100`,
        [context.roomId],
      )
      const events = await Promise.all(
        rows.map((row) => loadCommunityEvent(context, normalizeId(row.id))),
      )
      res.json({
        currentRole: context.role,
        canManageEvents: context.capabilities.canManageEvents,
        events: events.filter(Boolean),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/communities/:roomId/events', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      if (!context.capabilities.canManageEvents) {
        return res.status(403).json({ error: 'EVENT_MANAGE_DENIED' })
      }
      const title = cleanText(req.body?.title, 100)
      if (!title) return res.status(400).json({ error: 'EVENT_TITLE_REQUIRED' })
      const startsAt = new Date(String(req.body?.startsAt || req.body?.starts_at || ''))
      if (!Number.isFinite(startsAt.getTime()) || startsAt.getTime() < Date.now() - 5 * 60_000) {
        return res.status(400).json({ error: 'EVENT_START_INVALID' })
      }
      const rawEndsAt = req.body?.endsAt || req.body?.ends_at
      const endsAt = rawEndsAt ? new Date(String(rawEndsAt)) : null
      if (endsAt && (!Number.isFinite(endsAt.getTime()) || endsAt <= startsAt)) {
        return res.status(400).json({ error: 'EVENT_END_INVALID' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_community_events(
           community_id, title, description, starts_at, ends_at, location, created_by
         )
         VALUES ($1, $2, $3, $4::timestamptz, $5::timestamptz, $6, $7)
         RETURNING *`,
        [
          context.roomId,
          title,
          cleanText(req.body?.description, 500) || null,
          startsAt.toISOString(),
          endsAt?.toISOString() || null,
          cleanText(req.body?.location, 160) || null,
          context.identity.displayUserId,
        ],
      )
      const event = communityEventPayload(rows[0], {
        canManage: true,
      })
      res.status(201).json({ event })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/messages/communities/:roomId/events/:eventId', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      if (!context.capabilities.canManageEvents) {
        return res.status(403).json({ error: 'EVENT_MANAGE_DENIED' })
      }
      const eventId = normalizeId(req.params.eventId)
      if (!validUuid(eventId)) return res.status(400).json({ error: 'EVENT_ID_REQUIRED' })
      const existing = await loadCommunityEvent(context, eventId)
      if (!existing) return res.status(404).json({ error: 'EVENT_NOT_FOUND' })
      const title = Object.prototype.hasOwnProperty.call(req.body || {}, 'title')
        ? cleanText(req.body.title, 100)
        : existing.title
      if (!title) return res.status(400).json({ error: 'EVENT_TITLE_REQUIRED' })
      const startsAt = new Date(String(req.body?.startsAt || existing.startsAt))
      if (!Number.isFinite(startsAt.getTime())) {
        return res.status(400).json({ error: 'EVENT_START_INVALID' })
      }
      const rawEndsAt = Object.prototype.hasOwnProperty.call(req.body || {}, 'endsAt')
        ? req.body.endsAt
        : existing.endsAt
      const endsAt = rawEndsAt ? new Date(String(rawEndsAt)) : null
      if (endsAt && (!Number.isFinite(endsAt.getTime()) || endsAt <= startsAt)) {
        return res.status(400).json({ error: 'EVENT_END_INVALID' })
      }
      const { rows } = await pool.query(
        `UPDATE fallback_community_events
         SET title = $3,
             description = $4,
             starts_at = $5::timestamptz,
             ends_at = $6::timestamptz,
             location = $7,
             updated_at = NOW()
         WHERE lower(community_id) = $1 AND lower(id::text) = $2
         RETURNING *`,
        [
          context.roomId,
          eventId,
          title,
          Object.prototype.hasOwnProperty.call(req.body || {}, 'description')
            ? cleanText(req.body.description, 500) || null
            : existing.description,
          startsAt.toISOString(),
          endsAt?.toISOString() || null,
          Object.prototype.hasOwnProperty.call(req.body || {}, 'location')
            ? cleanText(req.body.location, 160) || null
            : existing.location,
        ],
      )
      await pool.query(
        `UPDATE fallback_community_event_attendees
         SET reminder_sent_at = NULL, updated_at = NOW()
         WHERE event_id = $1::uuid AND reminder_minutes IS NOT NULL`,
        [eventId],
      )
      res.json({
        event: communityEventPayload(rows[0], {
          attendanceCount: existing.attendanceCount,
          currentAttendance: existing.currentAttendance,
          reminderMinutes: existing.reminderMinutes,
          canManage: true,
        }),
      })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/messages/communities/:roomId/events/:eventId', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      if (!context.capabilities.canManageEvents) {
        return res.status(403).json({ error: 'EVENT_MANAGE_DENIED' })
      }
      const eventId = normalizeId(req.params.eventId)
      if (!validUuid(eventId)) return res.status(400).json({ error: 'EVENT_ID_REQUIRED' })
      const { rows } = await pool.query(
        `UPDATE fallback_community_events
         SET is_cancelled = TRUE, updated_at = NOW()
         WHERE lower(community_id) = $1 AND lower(id::text) = $2 AND is_cancelled = FALSE
         RETURNING *`,
        [context.roomId, eventId],
      )
      if (!rows[0]) return res.status(404).json({ error: 'EVENT_NOT_FOUND' })
      res.json({ event: communityEventPayload(rows[0], { canManage: true }) })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/messages/communities/:roomId/events/:eventId/attendance', authRequired, async (req, res, next) => {
    try {
      const context = await communityChannelContext(req, res)
      if (!context) return
      const eventId = normalizeId(req.params.eventId)
      if (!validUuid(eventId)) return res.status(400).json({ error: 'EVENT_ID_REQUIRED' })
      const existing = await loadCommunityEvent(context, eventId)
      if (!existing || existing.isCancelled) return res.status(404).json({ error: 'EVENT_NOT_FOUND' })
      const status = normalizeCommunityAttendanceStatus(req.body?.status)
      const requestedReminder = req.body?.reminderMinutes
      const reminderMinutes = status === 'going' && requestedReminder != null
        ? Math.trunc(Number(requestedReminder))
        : null
      if (reminderMinutes != null && ![0, 15, 30, 60, 1440].includes(reminderMinutes)) {
        return res.status(400).json({ error: 'EVENT_REMINDER_INVALID' })
      }
      const reminderAt = reminderMinutes == null
        ? null
        : new Date(new Date(existing.startsAt).getTime() - reminderMinutes * 60_000)
      if (reminderAt && reminderAt.getTime() <= Date.now()) {
        return res.status(400).json({ error: 'EVENT_REMINDER_TOO_LATE' })
      }
      await pool.query(
        `INSERT INTO fallback_community_event_attendees(
           event_id, user_id, status, reminder_minutes, reminder_sent_at
         )
         VALUES ($1::uuid, $2, $3, $4, NULL)
         ON CONFLICT (event_id, user_id)
         DO UPDATE SET status = EXCLUDED.status,
           reminder_minutes = EXCLUDED.reminder_minutes,
           reminder_sent_at = NULL,
           responded_at = NOW(),
           updated_at = NOW()`,
        [eventId, context.identity.displayUserId, status, reminderMinutes],
      )
      res.json({ event: await loadCommunityEvent(context, eventId) })
    } catch (error) {
      next(error)
    }
  })

  app.get(['/v1/data/messages/groups/:roomId/preview', '/v1/data/messages/communities/:roomId/preview'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const kind = collectionConfig(messageCollectionFromRequest(req))?.kind || ''
      const roomId = normalizeId(req.params.roomId)
      if (!['group', 'community'].includes(kind) || !validUuid(roomId)) {
        return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })
      }

      if (kind === 'group') {
        const token = String(req.query?.token || '').trim()
        if (token.length < 32 || token.length > 200) {
          return res.status(400).json({ error: 'GROUP_INVITE_INVALID' })
        }
        const tokenHash = crypto.createHash('sha256').update(token).digest('hex')
        const invite = await pool.query(
          `SELECT id
           FROM fallback_collection_invites
           WHERE room_kind = 'group'
             AND lower(room_id) = $1
             AND token_hash = $2
             AND revoked_at IS NULL
             AND expires_at > NOW()
             AND use_count < max_uses
           LIMIT 1`,
          [roomId, tokenHash],
        )
        if (invite.rows.length === 0) {
          return res.status(410).json({ error: 'GROUP_INVITE_EXPIRED_OR_USED' })
        }
      }

      const payload = await loadCollectionRoomPayload(
        pool,
        user,
        kind,
        roomId,
        { allowPrivatePreview: kind === 'group' },
      )
      if (!payload) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })

      const identity = userIdentity(user)
      const participants = (payload.participants || []).map((participant) => ({
        id: normalizeId(participant.id),
        name: cleanText(participant.name, 100) || 'Usuario',
        avatarUrl: cleanText(participant.avatarUrl, 700),
        role: cleanText(participant.role, 30) || 'member',
      }))
      const room = payload.room || payload.chat || {}
      res.json({
        kind,
        roomId,
        name: collectionRoomTitle(room, kind === 'group' ? 'Grupo' : 'Comunidad'),
        description: cleanText(textOf(room, ['description', 'bio'], ''), 500),
        imageUrl: cleanText(textOf(room, ['image', 'image_url', 'avatar_url', 'photo_url'], ''), 700),
        memberCount: participants.length,
        participants: participants.slice(0, 24),
        isMember: participants.some((participant) => identity.ids.has(participant.id)),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/groups/:roomId/invites', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const roomId = normalizeId(req.params.roomId)
      if (!validUuid(roomId)) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })
      const identity = userIdentity(user)
      const payload = await loadCollectionRoomPayload(pool, user, 'group', roomId)
      if (!payload) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      const room = payload.room || payload.chat || {}
      const me = (payload.participants || []).find((participant) => identity.ids.has(normalizeId(participant.id)))
      const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
      if (!isCollectionAdminRole(me?.role, { isCreator })) {
        return res.status(403).json({ error: 'GROUP_INVITE_CREATE_DENIED' })
      }

      const requestedHours = Number(req.body?.expiresInHours || 72)
      const expiresInHours = Math.max(1, Math.min(Number.isFinite(requestedHours) ? Math.trunc(requestedHours) : 72, 168))
      const requestedUses = Number(req.body?.maxUses || 100)
      const maxUses = Math.max(1, Math.min(Number.isFinite(requestedUses) ? Math.trunc(requestedUses) : 100, 500))
      const token = crypto.randomBytes(32).toString('base64url')
      const tokenHash = crypto.createHash('sha256').update(token).digest('hex')
      const { rows } = await pool.query(
        `INSERT INTO fallback_collection_invites(
           room_kind, room_id, token_hash, created_by, expires_at, max_uses
         )
         VALUES ('group', $1, $2, $3, NOW() + ($4::text || ' hours')::interval, $5)
         RETURNING id::text, expires_at, max_uses`,
        [roomId, tokenHash, identity.displayUserId, String(expiresInHours), maxUses],
      )
      res.status(201).json({
        inviteId: rows[0]?.id,
        token,
        roomId,
        expiresAt: rows[0]?.expires_at,
        maxUses: rows[0]?.max_uses,
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/groups/:roomId/join-invite', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const roomId = normalizeId(req.params.roomId)
      const token = String(req.body?.token || '').trim()
      if (!validUuid(roomId) || token.length < 32 || token.length > 200) {
        return res.status(400).json({ error: 'GROUP_INVITE_INVALID' })
      }
      const identity = userIdentity(user)
      const tokenHash = crypto.createHash('sha256').update(token).digest('hex')

      await client.query('BEGIN')
      const inviteResult = await client.query(
        `UPDATE fallback_collection_invites
         SET use_count = use_count + 1
         WHERE room_kind = 'group'
           AND lower(room_id) = $1
           AND token_hash = $2
           AND revoked_at IS NULL
           AND expires_at > NOW()
           AND use_count < max_uses
         RETURNING id::text`,
        [roomId, tokenHash],
      )
      if (inviteResult.rows.length === 0) {
        await client.query('ROLLBACK')
        return res.status(410).json({ error: 'GROUP_INVITE_EXPIRED_OR_USED' })
      }
      const roomResult = await client.query(
        `SELECT id::text FROM fallback_collection_rooms
         WHERE room_kind = 'group' AND lower(id::text) = $1 AND is_deleted = FALSE
         LIMIT 1`,
        [roomId],
      )
      if (roomResult.rows.length === 0) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      }
      await client.query(
        `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at, is_deleted)
         VALUES ('group', $1, $2, 'member', NOW(), FALSE)
         ON CONFLICT (room_kind, room_id, user_id)
         DO UPDATE SET is_deleted = FALSE`,
        [roomId, identity.displayUserId],
      )
      await client.query('COMMIT')
      res.json({ ok: true, roomId })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.patch('/v1/data/messages/groups/:roomId/member-settings', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const roomId = normalizeId(req.params.roomId)
      if (!validUuid(roomId)) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })
      const identity = userIdentity(user)
      const rawRestUntil = req.body?.restUntil ?? req.body?.rest_until ?? null
      let restUntil = null
      if (rawRestUntil != null && String(rawRestUntil).trim()) {
        const parsed = new Date(String(rawRestUntil))
        const maxAllowed = Date.now() + (30 * 24 * 60 * 60 * 1000)
        if (!Number.isFinite(parsed.getTime()) || parsed.getTime() <= Date.now() || parsed.getTime() > maxAllowed) {
          return res.status(400).json({ error: 'REST_UNTIL_INVALID' })
        }
        restUntil = parsed.toISOString()
      }
      const { rows } = await pool.query(
        `UPDATE fallback_collection_members
         SET rest_until = $3::timestamptz
         WHERE room_kind = 'group'
           AND lower(room_id) = $1
           AND lower(user_id) = ANY($2::text[])
           AND is_deleted = FALSE
         RETURNING rest_until`,
        [roomId, Array.from(identity.ids), restUntil],
      )
      if (rows.length === 0) return res.status(403).json({ error: 'ROOM_ACCESS_DENIED' })
      res.json({ ok: true, roomId, restUntil: rows[0]?.rest_until || null })
    } catch (error) {
      next(error)
    }
  })

  app.post(['/v1/data/messages/groups/:roomId/join', '/v1/data/messages/communities/:roomId/join'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(messageCollectionFromRequest(req))
      const roomId = normalizeId(req.params.roomId)
      if (!config || !roomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const identity = userIdentity(user)
      const targetUserId = normalizeId(req.body?.userId) || identity.displayUserId
      const requestedRole = cleanText(req.body?.role, 40).toLowerCase()
      const hasRequestedRole = requestedRole.length > 0
      if (hasRequestedRole && !['admin', 'moderator', 'member'].includes(requestedRole)) {
        return res.status(400).json({ error: 'ROLE_INVALID' })
      }
      const role = ['admin', 'moderator', 'member'].includes(requestedRole)
        ? requestedRole
        : 'member'
      const room = await loadCollectionRoomPayload(pool, user, config.kind, roomId)
      if (!room) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      const roomRow = room.room || room.chat || {}
      const isSelf = identity.ids.has(targetUserId)
      const creatorId = normalizeId(textOf(roomRow, ['creator_id', 'owner_id', 'user_id'], ''))
      if (creatorId && targetUserId === creatorId && !isSelf) {
        return res.status(403).json({ error: 'CREATOR_ROLE_IMMUTABLE' })
      }
      if (creatorId && targetUserId === creatorId && role !== 'member') {
        return res.status(403).json({ error: 'CREATOR_ROLE_IMMUTABLE' })
      }
      if (!isSelf) {
        const membersRows = await pool.query(
          `SELECT role FROM fallback_collection_members
           WHERE room_kind = $1 AND lower(room_id) = $2 AND lower(user_id) = ANY($3)`,
          [config.kind, roomId, Array.from(identity.ids)],
        )
        const myRole = membersRows.rows[0]?.role?.toLowerCase()
        const isCreator = rowMatchesAny(roomRow, ['creator_id', 'owner_id', 'user_id'], identity.ids)
        const isAdmin = isCollectionAdminRole(myRole, { isCreator })
        if (!isAdmin) return res.status(403).json({ error: 'ADD_MEMBER_DENIED' })
      }
      const effectiveRole = isSelf && role !== 'member' ? 'member' : role
      const mayReplaceExistingRole = !isSelf && hasRequestedRole
      await pool.query(
        `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at, is_deleted)
         VALUES ($1, $2, $3, $4, NOW(), FALSE)
         ON CONFLICT (room_kind, room_id, user_id)
         DO UPDATE SET
            role = CASE
              WHEN $5::boolean THEN EXCLUDED.role
              WHEN EXCLUDED.role = 'member' AND fallback_collection_members.role IN ('owner', 'admin', 'moderator', 'creator')
              THEN fallback_collection_members.role
              ELSE EXCLUDED.role
            END,
            is_deleted = FALSE`,
        [config.kind, roomId, targetUserId, effectiveRole, mayReplaceExistingRole],
      )

      if (targetUserId && !identity.ids.has(targetUserId)) {
        await createLocalNotificationBestEffort(pool, {
          recipientId: targetUserId,
          actorId: identity.displayUserId,
          actorName: user.display_name || user.email || 'Usuario',
          actorAvatarUrl: user.avatar_url || '',
          type: `${config.kind}_member_added`,
          targetId: roomId,
          targetType: config.kind,
          content: room?.title || config.titleFallback,
          extraData: {
            [`${config.kind}_id`]: roomId,
            notification_type: `${config.kind}_member_added`,
          },
        })
      }

      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.post(['/v1/data/messages/groups/:roomId/leave', '/v1/data/messages/communities/:roomId/leave'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(messageCollectionFromRequest(req))
      const roomId = normalizeId(req.params.roomId)
      if (!config || !roomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const identity = userIdentity(user)
      const targetUserId = normalizeId(req.body?.userId) || identity.displayUserId
      const isSelf = identity.ids.has(targetUserId)
      const room = await loadCollectionRoomPayload(pool, user, config.kind, roomId)
      if (!room) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      const roomRow = room.room || room.chat || {}
      const creatorId = normalizeId(textOf(roomRow, ['creator_id', 'owner_id', 'user_id'], ''))
      if (creatorId && targetUserId === creatorId) {
        return res.status(403).json({ error: 'CREATOR_CANNOT_BE_REMOVED' })
      }

      if (!isSelf) {
        const membersRows = await pool.query(
          `SELECT role FROM fallback_collection_members
           WHERE room_kind = $1 AND lower(room_id) = $2 AND lower(user_id) = ANY($3)`,
          [config.kind, roomId, Array.from(identity.ids)],
        )
        const myRole = membersRows.rows[0]?.role?.toLowerCase()
        const isCreator = rowMatchesAny(roomRow, ['creator_id', 'owner_id', 'user_id'], identity.ids)
        const isAdmin = isCollectionAdminRole(myRole, { isCreator })
        if (!isAdmin) return res.status(403).json({ error: 'REMOVE_MEMBER_DENIED' })
      }

      await pool.query(
        `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at, is_deleted)
         VALUES ($1, $2, $3, 'member', NOW(), TRUE)
         ON CONFLICT (room_kind, room_id, user_id)
         DO UPDATE SET is_deleted = TRUE`,
        [config.kind, roomId, targetUserId],
      )

      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.delete(['/v1/data/messages/groups/:roomId', '/v1/data/messages/communities/:roomId'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(messageCollectionFromRequest(req))
      const roomId = normalizeId(req.params.roomId)
      const uuidRoomId = validUuid(roomId)
      if (!config || !roomId || !uuidRoomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const identity = userIdentity(user)
      const [importedRooms, localRows, importedMembers, localMembers] = await Promise.all([
        importedRows(pool, config.roomTable, 5000),
        pool.query(
          `SELECT
              id::text,
              room_kind,
              name,
              image,
              description,
              category,
              is_public,
              creator_id,
              is_deleted,
              only_admins_can_talk,
              daily_message_limit,
              created_at,
              updated_at
           FROM fallback_collection_rooms
           WHERE room_kind = $1 AND lower(id::text) = $2
           LIMIT 1`,
          [config.kind, roomId],
        ).then((result) => result.rows.map(normalizeImportedRow)),
        importedRows(pool, config.memberTable, 10_000),
        localCollectionMembers(pool, config.kind, 10_000),
      ])
      const room = mergeRowsById([...importedRooms, ...localRows])
        .find((row) => normalizeId(textOf(row, ['id'], '')) === roomId)
      if (!room) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })

      const members = deduplicateMembers([...importedMembers, ...localMembers], config.roomKey)
        .filter((row) => roomIdOf(row, config.roomKey) === roomId)
      const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
      if (!isCreator) return res.status(403).json({ error: 'ROOM_DELETE_DENIED' })

      await pool.query(
        `INSERT INTO fallback_collection_rooms(
            id,
            room_kind,
            name,
            image,
            description,
            category,
            is_public,
            creator_id,
            is_deleted,
            only_admins_can_talk,
            daily_message_limit,
            created_at,
            updated_at
         )
         VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, TRUE, $9, $10, NOW(), NOW())
         ON CONFLICT (id) DO UPDATE SET
            room_kind = EXCLUDED.room_kind,
            name = COALESCE(NULLIF(EXCLUDED.name, ''), fallback_collection_rooms.name),
            image = COALESCE(EXCLUDED.image, fallback_collection_rooms.image),
            description = COALESCE(EXCLUDED.description, fallback_collection_rooms.description),
            category = COALESCE(EXCLUDED.category, fallback_collection_rooms.category),
            is_public = EXCLUDED.is_public,
            creator_id = COALESCE(NULLIF(EXCLUDED.creator_id, ''), fallback_collection_rooms.creator_id),
            is_deleted = TRUE,
            updated_at = NOW()`,
        [
          uuidRoomId,
          config.kind,
          collectionRoomTitle(room, config.titleFallback),
          collectionRoomAvatar(room) || null,
          cleanText(textOf(room, ['description', 'about'], ''), 500) || null,
          config.kind === 'community' ? cleanText(textOf(room, ['category'], ''), 80) || null : null,
          boolOf(room, ['is_public', 'public'], true),
          normalizeId(textOf(room, ['creator_id', 'owner_id', 'user_id'], '')) || identity.displayUserId,
          boolOf(room, ['only_admins_can_talk'], false),
          Math.max(0, Math.min(numberOf(room, ['daily_message_limit', 'dailyMessageLimit'], 0), 1000)),
        ],
      )
      await pool.query(
        `DELETE FROM fallback_collection_members
         WHERE room_kind = $1 AND lower(room_id) = $2`,
        [config.kind, roomId],
      )
      if (config.kind === 'community') {
        await pool.query(
          `DELETE FROM fallback_community_event_attendees
           WHERE event_id IN (
             SELECT id FROM fallback_community_events WHERE lower(community_id) = $1
           )`,
          [roomId],
        )
        await pool.query(`DELETE FROM fallback_community_events WHERE lower(community_id) = $1`, [roomId])
        await pool.query(`DELETE FROM fallback_community_member_profiles WHERE lower(community_id) = $1`, [roomId])
        await pool.query(`DELETE FROM fallback_community_channels WHERE lower(community_id) = $1`, [roomId])
      }

      res.json({ ok: true, roomId, deleted: true })
    } catch (error) {
      next(error)
    }
  })

  app.post(['/v1/data/messages/groups/:roomId/messages', '/v1/data/messages/communities/:roomId/messages'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(messageCollectionFromRequest(req))
      const roomId = normalizeId(req.params.roomId)
      const content = String(req.body?.content || '').trim()
      const contentType = String(req.body?.contentType || 'text').trim() || 'text'
      const mediaUrl = String(req.body?.mediaUrl || '').trim()
      const thumbnailUrl = String(req.body?.thumbnailUrl || req.body?.thumbnail_url || '').trim()
      const mediaSize = Math.max(0, Number(req.body?.mediaSize || 0))
      const replyToId = String(req.body?.replyToId || '').trim() || null
      const pollId = String(req.body?.pollId || '').trim() || null
      const isForwarded = Boolean(req.body?.isForwarded || req.body?.is_forwarded)
      const forwardedFromMessageId = String(req.body?.forwardedFromMessageId || req.body?.forwarded_from_message_id || '').trim() || null
      const forwardedFromUserId = String(req.body?.forwardedFromUserId || req.body?.forwarded_from_user_id || '').trim() || null
      const requestedChannelId = config?.kind === 'community'
        ? normalizeId(req.body?.channelId || req.body?.channel_id)
        : ''
      const requestedId = validUuid(req.body?.id)
      if (!config || !roomId || (!content && !mediaUrl)) {
        return res.status(400).json({ error: 'ROOM_ID_AND_CONTENT_OR_MEDIA_REQUIRED' })
      }

      const existingRoom = await loadCollectionRoomPayload(pool, user, config.kind, roomId)
      if (!existingRoom) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })

      const identity = userIdentity(user)
      const roomRow = existingRoom.room || existingRoom.chat || {}
      const myParticipant = (existingRoom.participants || []).find((participant) =>
        identity.ids.has(normalizeId(participant.id)),
      )
      const myRole = String(myParticipant?.role || '').toLowerCase()
      const isCreator = rowMatchesAny(roomRow, ['creator_id', 'owner_id', 'user_id'], identity.ids)
      const isAdmin = isCollectionAdminRole(myRole, { isCreator })
      let selectedChannel = null
      if (config.kind === 'community') {
        const defaultChannel = await ensureDefaultCommunityChannel(pool, roomId, identity.displayUserId)
        const channelId = requestedChannelId || normalizeId(defaultChannel?.id)
        if (!validUuid(channelId)) return res.status(404).json({ error: 'CHANNEL_NOT_FOUND' })
        const channelResult = await pool.query(
          `SELECT *
           FROM fallback_community_channels
           WHERE lower(community_id) = $1
             AND lower(id::text) = $2
             AND is_deleted = FALSE
           LIMIT 1`,
          [roomId, channelId],
        )
        selectedChannel = channelResult.rows[0] || null
        if (!selectedChannel) return res.status(404).json({ error: 'CHANNEL_NOT_FOUND' })
        const pausedUntil = selectedChannel.paused_until ? new Date(selectedChannel.paused_until).getTime() : 0
        if (Number.isFinite(pausedUntil) && pausedUntil > Date.now()) {
          return res.status(423).json({
            error: 'CHANNEL_PAUSED',
            reason: cleanText(selectedChannel.pause_reason, 240),
            pausedUntil: new Date(pausedUntil).toISOString(),
          })
        }
        if (normalizeCommunityChannelType(selectedChannel.channel_type) === 'voice') {
          return res.status(409).json({ error: 'VOICE_CHANNEL_DOES_NOT_ACCEPT_MESSAGES' })
        }
      }
      const onlyAdminsCanTalk = boolOf(roomRow, ['only_admins_can_talk', 'onlyAdminsCanTalk'], false)
      if (onlyAdminsCanTalk && !isAdmin) {
        return res.status(403).json({ error: 'ONLY_ADMINS_CAN_TALK' })
      }
      const allowedOwnedBuckets = config.kind === 'community'
        ? ['community-media', 'communities']
        : ['group-media', 'groups', 'group-audio']
      if (!(await currentUserMayReferenceMessageMedia(pool, user, identity, {
        mediaUrl,
        contentType,
        allowedOwnedBuckets,
        isForwarded,
        forwardedFromMessageId,
      }))) {
        return res.status(403).json({ error: 'COLLECTION_MEDIA_REFERENCE_FORBIDDEN' })
      }
      const dailyMessageLimit = config.kind === 'community'
        ? Math.max(0, Math.min(numberOf(roomRow, ['daily_message_limit', 'dailyMessageLimit'], 0), 1000))
        : 0
      if (dailyMessageLimit > 0 && !isAdmin) {
        const { rows: usageRows } = await pool.query(
          `SELECT COUNT(*)::integer AS sent_count
           FROM fallback_room_messages
           WHERE room_kind = 'community'
             AND lower(room_id) = $1
             AND lower(sender_id) = ANY($2::text[])
             AND created_at >= NOW() - INTERVAL '24 hours'`,
          [roomId, Array.from(identity.ids)],
        )
        const sentCount = Number(usageRows[0]?.sent_count || 0)
        if (sentCount >= dailyMessageLimit) {
          return res.status(429).json({
            error: 'COMMUNITY_DAILY_MESSAGE_LIMIT_REACHED',
            limit: dailyMessageLimit,
            used: sentCount,
          })
        }
      }
      const senderId = identity.displayUserId
      const { rows } = await pool.query(
        `INSERT INTO fallback_room_messages(
            id,
             room_kind,
             room_id,
             channel_id,
             sender_id,
            content,
            content_type,
            media_url,
            media_size,
            reply_to_id,
            poll_id,
            is_forwarded,
            forwarded_from_message_id,
            forwarded_from_user_id
         )
         VALUES (COALESCE($1::uuid, gen_random_uuid()), $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
         ON CONFLICT (id) DO UPDATE SET
            room_kind = EXCLUDED.room_kind,
            room_id = EXCLUDED.room_id,
            channel_id = EXCLUDED.channel_id,
            sender_id = EXCLUDED.sender_id,
            content = EXCLUDED.content,
            content_type = EXCLUDED.content_type,
            media_url = EXCLUDED.media_url,
            media_size = EXCLUDED.media_size,
            reply_to_id = EXCLUDED.reply_to_id,
            poll_id = EXCLUDED.poll_id,
            is_forwarded = EXCLUDED.is_forwarded,
            forwarded_from_message_id = EXCLUDED.forwarded_from_message_id,
            forwarded_from_user_id = EXCLUDED.forwarded_from_user_id,
            status = 'sent',
            updated_at = NOW()
         RETURNING
            id::text,
            room_id,
            CASE WHEN room_kind = 'group' THEN room_id ELSE NULL END AS group_id,
            CASE WHEN room_kind = 'community' THEN room_id ELSE NULL END AS community_id,
            channel_id,
            sender_id,
            content,
            content_type,
            media_url,
            media_size,
            status,
            reply_to_id,
            poll_id,
            is_forwarded,
            forwarded_from_message_id,
            forwarded_from_user_id,
            created_at,
            updated_at`,
        [
          requestedId,
          config.kind,
          roomId,
          config.kind === 'community' ? normalizeId(selectedChannel?.id) : null,
          senderId,
          content,
          contentType,
          mediaUrl || null,
          mediaSize,
          replyToId,
          pollId,
          isForwarded,
          forwardedFromMessageId,
          forwardedFromUserId,
        ],
      )

      const profilesById = indexProfiles([
        ...(await importedRows(pool, 'profiles', 5000)),
        ...(await fallbackUserProfileRows(pool, 5000)),
      ])
      const roomRecipients = (existingRoom.participants || [])
        .map((participant) => normalizeId(participant.id))
        .filter((participantId) => participantId && !identity.ids.has(participantId))
      const notificationRecipients = (existingRoom.participants || [])
        .filter((participant) => {
          const restUntil = toMillis(participant.restUntil)
          return !restUntil || restUntil <= Date.now()
        })
        .map((participant) => normalizeId(participant.id))
        .filter((participantId) => participantId && !identity.ids.has(participantId))
      const roomMessage = enrichCollectionMessage(normalizeImportedRow(rows[0]), config, profilesById)
      // Publicar primero el mensaje completo. Así Room puede recibirlo antes
      // que la notificación sea tocada y FCM queda como respaldo/wake-up.
      await publishChatMessageEvent({
        chatId: `room:${config.kind}:${roomId}`,
        message: roomMessage,
        recipientIds: roomRecipients,
      })
      await Promise.all(
        notificationRecipients.map((participantId) =>
          createLocalNotificationBestEffort(pool, {
            recipientId: participantId,
            actorId: identity.displayUserId,
            actorName: user.display_name || user.email || 'Usuario',
            actorAvatarUrl: user.avatar_url || '',
            type: 'message',
            targetId: roomId,
            targetType: config.kind,
            content: content || previewFromMessage({ content_type: contentType }),
            extraData: {
              [`${config.kind}_id`]: roomId,
              channel_id: textOf(rows[0], ['channel_id'], ''),
              message_id: textOf(rows[0], ['id'], ''),
              content_type: textOf(rows[0], ['content_type'], 'text'),
              media_url: textOf(rows[0], ['media_url'], ''),
              media_size: String(numberOf(rows[0], ['media_size'], 0)),
              reply_to_id: textOf(rows[0], ['reply_to_id'], ''),
              poll_id: textOf(rows[0], ['poll_id'], ''),
              is_forwarded: String(boolOf(rows[0], ['is_forwarded'], false)),
              forwarded_from_message_id: textOf(rows[0], ['forwarded_from_message_id'], ''),
              forwarded_from_user_id: textOf(rows[0], ['forwarded_from_user_id'], ''),
              created_at: textOf(rows[0], ['created_at'], ''),
            },
          }),
        ),
      )
      res.status(201).json({ message: roomMessage })
    } catch (error) {
      next(error)
    }
  })

  async function loadCollectionMessageMutationContext(req, res) {
    const user = await getCurrentUser(pool, req)
    if (!user) {
      res.status(404).json({ error: 'USER_NOT_FOUND' })
      return null
    }

    const config = collectionConfig(messageCollectionFromRequest(req))
    const roomId = normalizeId(req.params.roomId)
    const messageId = normalizeId(req.params.messageId)
    if (!config || !roomId || !messageId) {
      res.status(400).json({ error: 'ROOM_AND_MESSAGE_ID_REQUIRED' })
      return null
    }

    const roomPayload = await loadCollectionRoomPayload(pool, user, config.kind, roomId)
    if (!roomPayload) {
      res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      return null
    }

    const identity = userIdentity(user)
    const room = roomPayload.room || roomPayload.chat || {}
    const participants = roomPayload.participants || []
    const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
    const isMember = participants.some((participant) =>
      identity.ids.has(normalizeId(participant.id || participant.user_id || participant.profile_id)),
    )
    if (!isCreator && !isMember) {
      res.status(403).json({ error: 'ROOM_ACCESS_DENIED' })
      return null
    }

    const { rows } = await pool.query(
      `SELECT *
       FROM fallback_room_messages
       WHERE room_kind = $1
         AND lower(room_id) = $2
         AND lower(id::text) = $3
       LIMIT 1`,
      [config.kind, roomId, messageId],
    )
    const existing = rows[0] ? normalizeImportedRow(rows[0]) : null
    if (!existing) {
      res.status(404).json({ error: 'MESSAGE_NOT_FOUND' })
      return null
    }

    const recipientIds = participants
      .map((participant) => normalizeId(participant.id || participant.user_id || participant.profile_id))
      .filter((participantId) => participantId && !identity.ids.has(participantId))

    return { user, identity, config, roomId, messageId, existing, recipientIds }
  }

  app.patch(
    ['/v1/data/messages/groups/:roomId/messages/:messageId', '/v1/data/messages/communities/:roomId/messages/:messageId'],
    authRequired,
    async (req, res, next) => {
      try {
        const context = await loadCollectionMessageMutationContext(req, res)
        if (!context) return
        const { identity, config, roomId, messageId, existing, recipientIds } = context
        const content = cleanText(req.body?.content, 4096)
        if (!content) return res.status(400).json({ error: 'MESSAGE_CONTENT_REQUIRED' })
        if (!rowMatchesAny(existing, ['sender_id'], identity.ids)) {
          return res.status(403).json({ error: 'MESSAGE_EDIT_DENIED' })
        }
        if (boolOf(existing, ['deleted_for_all'], false)) {
          return res.status(409).json({ error: 'MESSAGE_DELETED' })
        }
        if (textOf(existing, ['content_type'], 'text').toLowerCase() !== 'text') {
          return res.status(400).json({ error: 'ONLY_TEXT_MESSAGES_CAN_BE_EDITED' })
        }

        const { rows } = await pool.query(
          `UPDATE fallback_room_messages
           SET content = $4,
               is_edited = TRUE,
               updated_at = NOW()
           WHERE room_kind = $1 AND lower(room_id) = $2 AND lower(id::text) = $3
           RETURNING *`,
          [config.kind, roomId, messageId, content],
        )
        const profilesById = indexProfiles(await allPublicProfiles(pool))
        const message = enrichCollectionMessage(normalizeImportedRow(rows[0]), config, profilesById)
        void publishChatMessageEvent({
          chatId: `room:${config.kind}:${roomId}`,
          message,
          recipientIds,
          action: 'updated',
        })
        res.json({ message })
      } catch (error) {
        next(error)
      }
    },
  )

  app.post(
    ['/v1/data/messages/groups/:roomId/messages/:messageId/delete', '/v1/data/messages/communities/:roomId/messages/:messageId/delete'],
    authRequired,
    async (req, res, next) => {
      try {
        const context = await loadCollectionMessageMutationContext(req, res)
        if (!context) return
        const { identity, config, roomId, messageId, existing, recipientIds } = context
        const forEveryone = Boolean(req.body?.forEveryone || req.body?.for_everyone)
        const isSender = rowMatchesAny(existing, ['sender_id'], identity.ids)
        if (forEveryone && !isSender) {
          return res.status(403).json({ error: 'MESSAGE_DELETE_FOR_ALL_DENIED' })
        }

        let rows
        if (forEveryone) {
          ;({ rows } = await pool.query(
            `UPDATE fallback_room_messages
             SET deleted_for_all = TRUE,
                 updated_at = NOW()
             WHERE room_kind = $1 AND lower(room_id) = $2 AND lower(id::text) = $3
             RETURNING *`,
            [config.kind, roomId, messageId],
          ))
        } else {
          const deletedBy = Array.from(new Set([
            ...arrayOf(existing, ['deleted_by', 'deletedBy']).map(normalizeId),
            identity.displayUserId,
          ].filter(Boolean)))
          ;({ rows } = await pool.query(
            `UPDATE fallback_room_messages
             SET deleted_by = $4::jsonb,
                 updated_at = NOW()
             WHERE room_kind = $1 AND lower(room_id) = $2 AND lower(id::text) = $3
             RETURNING *`,
            [config.kind, roomId, messageId, JSON.stringify(deletedBy)],
          ))
        }

        const profilesById = indexProfiles(await allPublicProfiles(pool))
        const message = enrichCollectionMessage(normalizeImportedRow(rows[0]), config, profilesById)
        if (forEveryone) {
          void publishChatMessageEvent({
            chatId: `room:${config.kind}:${roomId}`,
            message,
            recipientIds,
            action: 'deleted',
          })
        } else {
          void publishRealtimeEvent(`user:${identity.displayUserId}`, {
            event: 'message',
            action: 'deleted',
            chatId: `room:${config.kind}:${roomId}`,
            message,
            scope: config.kind,
          })
        }
        res.json({ message })
      } catch (error) {
        next(error)
      }
    },
  )

  app.post('/v1/data/voice/rooms', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const name = cleanText(req.body?.name, 120) || 'Sala de voz'
      const description = cleanText(req.body?.description, 500)
      const requestedId = validUuid(req.body?.id)
      const requestedMaxParticipants = Number(req.body?.maxParticipants || req.body?.max_participants || 50)
      const maxParticipants = Number.isFinite(requestedMaxParticipants)
        ? Math.max(2, Math.min(requestedMaxParticipants, 200))
        : 50
      const isPublic = Object.prototype.hasOwnProperty.call(req.body || {}, 'isPublic')
        ? Boolean(req.body.isPublic)
        : Boolean(req.body?.is_public ?? true)
      const requestedRoomMode = String(req.body?.roomMode || req.body?.room_mode || 'casual').trim().toLowerCase()
      if (!['casual', 'stage'].includes(requestedRoomMode)) {
        return res.status(400).json({ error: 'VOICE_ROOM_MODE_INVALID' })
      }

      const { rows } = await pool.query(
        `INSERT INTO fallback_voice_rooms(
            id,
            name,
            description,
            creator_id,
            max_participants,
            is_public,
            is_active,
            agora_channel_name,
            readable_id,
            room_mode,
            created_at,
            updated_at
         )
         VALUES (
            COALESCE($1::uuid, gen_random_uuid()),
            $2,
            $3,
            $4,
            $5,
            $6,
            TRUE,
            COALESCE(NULLIF($7, ''), 'room_' || COALESCE($1::text, gen_random_uuid()::text)),
            NULLIF($8, ''),
            $9,
            NOW(),
            NOW()
         )
         ON CONFLICT (id) DO UPDATE SET
            name = EXCLUDED.name,
            description = EXCLUDED.description,
            creator_id = EXCLUDED.creator_id,
            max_participants = EXCLUDED.max_participants,
            is_public = EXCLUDED.is_public,
            room_mode = EXCLUDED.room_mode,
            is_active = TRUE,
            updated_at = NOW()
         RETURNING
            id::text,
            name,
            description,
            creator_id,
            max_participants,
            is_public,
            is_active,
            agora_channel_name,
            readable_id,
            room_mode,
            created_at,
            updated_at`,
        [
          requestedId,
          name,
          description,
          identity.displayUserId,
          maxParticipants,
          isPublic,
          String(req.body?.agoraChannelName || req.body?.agora_channel_name || '').trim(),
          String(req.body?.readableId || req.body?.readable_id || '').trim(),
          requestedRoomMode,
        ],
      )

      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      const room = mapVoiceRoom(normalizeImportedRow(rows[0]), profilesById, 0)
      clearVoiceCaches()
      void publishVoiceRoomsLiveEvent({ action: 'created', roomId: room.id, room })
      res.status(201).json({ room })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/voice/rooms', authRequired, async (_req, res, next) => {
    try {
      const [importedProfiles, localProfiles, importedRooms, fallbackParticipants, fallbackRooms] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
        importedRows(pool, 'voice_rooms', 1000),
        localVoiceParticipants(pool),
        localVoiceRooms(pool, 1000),
      ])
      const rooms = [...importedRooms, ...fallbackRooms]
      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      const countByRoom = new Map()
      const participantsByRoom = new Map()
      for (const row of fallbackParticipants) {
        const id = voiceParticipantRoomId(row)
        if (!id) continue
        countByRoom.set(id, (countByRoom.get(id) || 0) + 1)
        participantsByRoom.set(id, [...(participantsByRoom.get(id) || []), row])
      }
      const items = sortByDateDesc(
        rooms.map((row) => {
          const id = voiceRoomId(row)
          return mapVoiceRoom(row, profilesById, countByRoom.get(id) || 0, participantsByRoom.get(id) || [])
        }),
        ['updated_at', 'created_at'],
      )
      setPrivateShortCache(res, 2)
      res.json({ rooms: items, items })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/voice/rooms/:roomId', authRequired, async (req, res, next) => {
    try {
      const cleanRoomId = normalizeId(req.params.roomId)
      const [importedProfiles, localProfiles, importedRooms, fallbackParticipants, fallbackRooms] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
        importedRows(pool, 'voice_rooms', 1000),
        localVoiceParticipants(pool),
        localVoiceRooms(pool, 1000),
      ])
      const rooms = [...importedRooms, ...fallbackRooms]
      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      const room = rooms.find((row) => voiceRoomMatches(row, cleanRoomId))
      if (!room) return res.status(404).json({ error: 'VOICE_ROOM_NOT_FOUND' })
      const actualRoomId = voiceRoomId(room) || cleanRoomId
      const participantRows = fallbackParticipants
        .filter((row) => voiceParticipantRoomId(row) === actualRoomId)
      const participants = participantRows
        .map((row) => mapVoiceParticipant(row, profilesById))
      setPrivateShortCache(res, 2)
      res.json({
        room: mapVoiceRoom(room, profilesById, participants.length, participantRows),
        participants,
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/voice/rooms/:roomId/join', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const cleanRoomId = normalizeId(req.params.roomId)
      if (!cleanRoomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const rooms = [...(await importedRows(pool, 'voice_rooms', 1000)), ...(await localVoiceRooms(pool, 1000))]
      const room = rooms.find((row) => voiceRoomMatches(row, cleanRoomId))
      if (!room) return res.status(404).json({ error: 'VOICE_ROOM_NOT_FOUND' })
      const actualRoomId = voiceRoomId(room) || cleanRoomId

      const identity = userIdentity(user)
      const mode = voiceRoomMode(room)
      const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
      const existingParticipant = await pool.query(
        `SELECT role, hand_raised
         FROM fallback_voice_room_participants
         WHERE lower(room_id) = $1 AND lower(user_id) = $2
         LIMIT 1`,
        [actualRoomId, identity.displayUserId],
      )
      const existingRole = voiceParticipantRole(existingParticipant.rows[0] || {}, mode)
      const role = mode === 'casual'
        ? 'speaker'
        : isCreator
          ? 'moderator'
          : ['moderator', 'speaker', 'audience'].includes(existingRole)
            ? existingRole
            : 'audience'
      const isMuted = role === 'audience' ? true : Boolean(req.body?.isMuted)
      const handRaised = mode === 'stage'
        && role === 'audience'
        && Boolean(existingParticipant.rows[0]?.hand_raised)
      await pool.query(
        `INSERT INTO fallback_voice_room_participants(
            room_id,
            user_id,
            user_name,
            user_avatar_url,
            socket_id,
            is_muted,
            is_speaking,
            role,
            hand_raised,
            joined_at,
            updated_at
         )
         VALUES ($1, $2, $3, $4, $5, $6, FALSE, $7, $8, NOW(), NOW())
         ON CONFLICT (room_id, user_id)
         DO UPDATE SET
            user_name = EXCLUDED.user_name,
            user_avatar_url = EXCLUDED.user_avatar_url,
            socket_id = EXCLUDED.socket_id,
            is_muted = EXCLUDED.is_muted,
            role = EXCLUDED.role,
            hand_raised = EXCLUDED.hand_raised,
            updated_at = NOW()`,
        [
          actualRoomId,
          identity.displayUserId,
          String(req.body?.userName || user.display_name || user.email || 'Usuario').trim(),
          String(req.body?.userAvatarUrl || user.avatar_url || '').trim(),
          String(req.body?.socketId || '').trim(),
          isMuted,
          role,
          handRaised,
        ],
      )
      await pool.query(
        `UPDATE fallback_voice_rooms
         SET updated_at = NOW()
         WHERE lower(id::text) = $1`,
        [actualRoomId],
      )
      clearVoiceCaches()
      const payload = await localVoiceParticipants(pool, actualRoomId)
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      const participants = payload.map((row) => mapVoiceParticipant(row, profilesById))
      void publishVoiceRoomsLiveEvent({ action: 'joined', roomId: actualRoomId, participants })
      res.json({ participants })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/voice/rooms/:roomId/stage/request', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const cleanRoomId = normalizeId(req.params.roomId)
      const rooms = [...(await importedRows(pool, 'voice_rooms', 1000)), ...(await localVoiceRooms(pool, 1000))]
      const room = rooms.find((row) => voiceRoomMatches(row, cleanRoomId))
      if (!room) return res.status(404).json({ error: 'VOICE_ROOM_NOT_FOUND' })
      if (voiceRoomMode(room) !== 'stage') {
        return res.status(409).json({ error: 'VOICE_STAGE_REQUIRED' })
      }

      const actualRoomId = voiceRoomId(room) || cleanRoomId
      const identity = userIdentity(user)
      const participantResult = await pool.query(
        `SELECT *
         FROM fallback_voice_room_participants
         WHERE lower(room_id) = $1 AND lower(user_id) = $2
         LIMIT 1`,
        [actualRoomId, identity.displayUserId],
      )
      const participant = participantResult.rows[0]
      if (!participant) return res.status(409).json({ error: 'VOICE_STAGE_JOIN_REQUIRED' })
      if (voiceParticipantRole(participant, 'stage') !== 'audience') {
        return res.status(409).json({ error: 'VOICE_STAGE_ALREADY_SPEAKER' })
      }

      const handRaised = Boolean(req.body?.handRaised ?? req.body?.hand_raised ?? true)
      await pool.query(
        `UPDATE fallback_voice_room_participants
         SET hand_raised = $3, is_muted = TRUE, updated_at = NOW()
         WHERE lower(room_id) = $1 AND lower(user_id) = $2`,
        [actualRoomId, identity.displayUserId, handRaised],
      )
      clearVoiceCaches()
      const payload = await localVoiceParticipants(pool, actualRoomId)
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      const participants = payload.map((row) => mapVoiceParticipant(row, profilesById))
      void publishVoiceRoomsLiveEvent({
        action: handRaised ? 'hand-raised' : 'hand-lowered',
        roomId: actualRoomId,
        userId: identity.displayUserId,
        participants,
      })
      res.json({ participants, handRaised })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/voice/rooms/:roomId/stage/participants/:userId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const cleanRoomId = normalizeId(req.params.roomId)
      const targetUserId = normalizeId(req.params.userId)
      const rooms = [...(await importedRows(pool, 'voice_rooms', 1000)), ...(await localVoiceRooms(pool, 1000))]
      const room = rooms.find((row) => voiceRoomMatches(row, cleanRoomId))
      if (!room) return res.status(404).json({ error: 'VOICE_ROOM_NOT_FOUND' })
      if (voiceRoomMode(room) !== 'stage') {
        return res.status(409).json({ error: 'VOICE_STAGE_REQUIRED' })
      }
      if (!targetUserId) return res.status(400).json({ error: 'VOICE_STAGE_PARTICIPANT_REQUIRED' })

      const actualRoomId = voiceRoomId(room) || cleanRoomId
      const identity = userIdentity(user)
      const actorIsCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
      const participantsResult = await pool.query(
        `SELECT *
         FROM fallback_voice_room_participants
         WHERE lower(room_id) = $1`,
        [actualRoomId],
      )
      const actor = participantsResult.rows.find((row) => rowMatchesAny(row, ['user_id'], identity.ids))
      const target = participantsResult.rows.find(
        (row) => normalizeId(textOf(row, ['user_id'], '')) === targetUserId,
      )
      if (!target) return res.status(404).json({ error: 'VOICE_STAGE_PARTICIPANT_NOT_FOUND' })
      const actorIsModerator = actorIsCreator || voiceParticipantRole(actor || {}, 'stage') === 'moderator'
      if (!actorIsModerator) return res.status(403).json({ error: 'VOICE_STAGE_MODERATION_DENIED' })

      const targetIsCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], [
        normalizeId(textOf(target, ['user_id'], '')),
      ])
      const targetRole = voiceParticipantRole(target, 'stage')
      if (targetIsCreator || (!actorIsCreator && targetRole === 'moderator')) {
        return res.status(403).json({ error: 'VOICE_STAGE_MODERATOR_IMMUTABLE' })
      }

      const hasRole = Object.prototype.hasOwnProperty.call(req.body || {}, 'role')
      const requestedRole = hasRole ? String(req.body.role || '').trim().toLowerCase() : targetRole
      if (!['moderator', 'speaker', 'audience'].includes(requestedRole)) {
        return res.status(400).json({ error: 'VOICE_STAGE_ROLE_INVALID' })
      }
      if (requestedRole === 'moderator' && !actorIsCreator) {
        return res.status(403).json({ error: 'VOICE_STAGE_MODERATION_DENIED' })
      }
      const hasMuted = Object.prototype.hasOwnProperty.call(req.body || {}, 'isMuted')
        || Object.prototype.hasOwnProperty.call(req.body || {}, 'is_muted')
      if (!hasRole && !hasMuted) {
        return res.status(400).json({ error: 'VOICE_STAGE_UPDATE_REQUIRED' })
      }
      const requestedMuted = Boolean(req.body?.isMuted ?? req.body?.is_muted)
      const isMuted = requestedRole === 'audience'
        ? true
        : hasMuted
          ? requestedMuted
          : Boolean(target.is_muted)

      await pool.query(
        `UPDATE fallback_voice_room_participants
         SET role = $3,
             is_muted = $4,
             is_speaking = CASE WHEN $4 THEN FALSE ELSE is_speaking END,
             hand_raised = FALSE,
             updated_at = NOW()
         WHERE lower(room_id) = $1 AND lower(user_id) = $2`,
        [actualRoomId, targetUserId, requestedRole, isMuted],
      )
      clearVoiceCaches()
      const payload = await localVoiceParticipants(pool, actualRoomId)
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      const participants = payload.map((row) => mapVoiceParticipant(row, profilesById))
      void publishVoiceRoomsLiveEvent({
        action: 'stage-participant-updated',
        roomId: actualRoomId,
        userId: targetUserId,
        role: requestedRole,
        isMuted,
        participants,
      })
      res.json({ participants })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/voice/rooms/:roomId/leave', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanRoomId = normalizeId(req.params.roomId)
      const rooms = [...(await importedRows(pool, 'voice_rooms', 1000)), ...(await localVoiceRooms(pool, 1000))]
      const room = rooms.find((row) => voiceRoomMatches(row, cleanRoomId))
      const actualRoomId = room ? voiceRoomId(room) || cleanRoomId : cleanRoomId
      await pool.query(
        `DELETE FROM fallback_voice_room_participants
         WHERE lower(room_id) = $1 AND lower(user_id) = $2`,
        [actualRoomId, identity.displayUserId],
      )
      await pool.query(
        `UPDATE fallback_voice_rooms
         SET updated_at = NOW()
         WHERE lower(id::text) = $1`,
        [actualRoomId],
      )
      clearVoiceCaches()
      void publishVoiceRoomsLiveEvent({ action: 'left', roomId: actualRoomId, userId: identity.displayUserId })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/voice/rooms/:roomId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const cleanRoomId = normalizeId(req.params.roomId)
      if (!cleanRoomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const identity = userIdentity(user)
      const rooms = [...(await importedRows(pool, 'voice_rooms', 1000)), ...(await localVoiceRooms(pool, 1000))]
      const room = rooms.find((row) => voiceRoomMatches(row, cleanRoomId))
      if (!room) return res.status(404).json({ error: 'VOICE_ROOM_NOT_FOUND' })
      const actualRoomId = voiceRoomId(room) || cleanRoomId
      if (!rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)) {
        return res.status(403).json({ error: 'VOICE_ROOM_DELETE_DENIED' })
      }

      const uuidRoomId = validUuid(actualRoomId)
      if (uuidRoomId) {
        await pool.query(
          `UPDATE fallback_voice_rooms
           SET is_active = FALSE, updated_at = NOW()
           WHERE lower(id::text) = $1`,
          [actualRoomId],
        )
      }
      await pool.query(
        `DELETE FROM fallback_voice_room_participants
         WHERE lower(room_id) = $1`,
        [actualRoomId],
      )
      clearVoiceCaches()
      void publishVoiceRoomsLiveEvent({ action: 'deleted', roomId: actualRoomId })
      res.json({ ok: true, roomId: actualRoomId, deleted: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/videos', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 80, 200))
      const offset = Math.max(0, Number(req.query.offset) || 0)
      const requestedUserId = normalizeId(req.query.userId || req.query.user_id || '')
      const requestedSharedBy = normalizeId(req.query.sharedBy || req.query.shared_by || '')
      const requestedFavoritedBy = normalizeId(req.query.favoritedBy || req.query.favorited_by || '')
      const searchQuery = cleanText(req.query.q || req.query.query || '', 120).toLowerCase()
      const recommended = /^(1|true|yes)$/i.test(String(req.query.recommended || ''))
      const [publicProfiles, importedVideos, fallbackVideos] = await Promise.all([
        allPublicProfiles(pool),
        importedRows(pool, 'videos', 5000),
        localVideos(pool, 5000),
      ])
      const requestedOwnerIds = cleanSet([requestedUserId])
      if (requestedUserId) {
        for (const profile of publicProfiles) {
          const aliases = profileAliasSet(profile)
          if (aliases.has(requestedUserId)) {
            for (const alias of aliases) requestedOwnerIds.add(alias)
          }
        }
      }
      const requestedSharedByIds = cleanSet([requestedSharedBy])
      if (requestedSharedBy) {
        for (const profile of publicProfiles) {
          const aliases = profileAliasSet(profile)
          if (aliases.has(requestedSharedBy)) {
            for (const alias of aliases) requestedSharedByIds.add(alias)
          }
        }
      }
      const requestedFavoritedByIds = cleanSet([requestedFavoritedBy])
      let requestedFavoritesProfile = null
      if (requestedFavoritedBy) {
        for (const profile of publicProfiles) {
          const aliases = profileAliasSet(profile)
          if (!aliases.has(requestedFavoritedBy)) continue
          requestedFavoritesProfile = profile
          for (const alias of aliases) requestedFavoritedByIds.add(alias)
          break
        }
        const viewingOwnFavorites = Array.from(requestedFavoritedByIds).some((id) => identity.ids.has(id))
        const favoritesProfileHidden = textOf(
          requestedFavoritesProfile || {},
          ['break_mode', 'breakMode'],
          'off',
        ).toLowerCase() === 'hidden'
        if (!viewingOwnFavorites && favoritesProfileHidden) {
          return res.status(404).json({ error: 'PROFILE_HIDDEN' })
        }
        const favoritesArePublic = boolOf(requestedFavoritesProfile || {}, ['favorites_public', 'favoritesPublic'], false)
        if (!viewingOwnFavorites && !favoritesArePublic) {
          return res.status(403).json({ error: 'PROFILE_FAVORITES_PRIVATE' })
        }
      }
      const mergedVideos = mergeRowsById([...importedVideos, ...fallbackVideos])
      const mergedVideoIds = mergedVideos.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const deletedVideoIds = await localVideoDeletionIds(pool, mergedVideoIds)
      let videos = mergedVideos.filter((row) => {
        if (deletedVideoIds.has(normalizeId(textOf(row, ['id'], '')))) return false
        const ownerId = textOf(row, ['user_id', 'userId'], '')
        const isPublic = boolOf(row, ['is_public', 'isPublic'], true)
        if (requestedUserId && !ownerMatchesRequestedProfile(row, requestedOwnerIds)) return false
        if (searchQuery) {
          const haystack = [
            textOf(row, ['title', 'caption'], ''),
            textOf(row, ['description', 'content'], ''),
            ...arrayOf(row, ['tags']),
          ].join(' ').toLowerCase()
          if (!haystack.includes(searchQuery)) return false
        }
        return isPublic || rowMatchesAny(row, ['user_id', 'userId'], identity.ids)
      })
      if (requestedSharedBy) {
        const [importedSharesForProfile, fallbackSharesForProfile] = await Promise.all([
          importedRows(pool, 'shares', 10_000),
          localVideoShares(pool, mergedVideoIds, 10_000),
        ])
        const sharedVideoIds = new Set(
          [...importedSharesForProfile, ...fallbackSharesForProfile]
            .filter((row) => {
              const contentType = textOf(row, ['content_type', 'contentType', 'type'], '').toLowerCase()
              if (contentType && contentType !== 'video') return false
              return rowMatchesAny(row, ['user_id', 'profile_id'], requestedSharedByIds)
            })
            .map((row) => normalizeId(textOf(row, ['video_id', 'videoId', 'post_id', 'content_id'], '')))
            .filter(Boolean),
        )
        videos = videos.filter((row) => sharedVideoIds.has(normalizeId(textOf(row, ['id'], ''))))
      }
      if (requestedFavoritedBy) {
        const favoriteRowsForProfile = await localVideoFavorites(pool, mergedVideoIds)
        const requestedFavoriteVideoIds = new Set(
          favoriteRowsForProfile
            .filter((row) => rowMatchesAny(row, ['user_id'], requestedFavoritedByIds))
            .map((row) => normalizeId(textOf(row, ['video_id'], '')))
            .filter(Boolean),
        )
        videos = videos.filter((row) => requestedFavoriteVideoIds.has(normalizeId(textOf(row, ['id'], ''))))
      }
      const videoIds = videos.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const [importedLikes, fallbackLikes, importedVideoCommentRowsAll, localCommentCounts, importedShares, fallbackShares, metricRows] = await Promise.all([
        importedRows(pool, 'video_likes', 10_000),
        localVideoLikes(pool, videoIds, 10_000),
        importedVideoComments(pool, 10_000),
        localVideoCommentCounts(pool, videoIds),
        importedRows(pool, 'shares', 10_000),
        localVideoShares(pool, videoIds, 10_000),
        localVideoMetrics(pool, videoIds),
      ])
      const profilesById = indexProfiles(publicProfiles)
      const favoriteRows = await localVideoFavorites(pool, videoIds)
      const favoriteCounts = new Map()
      const favoriteVideoIds = new Set()
      for (const favoriteRow of favoriteRows) {
        const favoriteVideoId = normalizeId(textOf(favoriteRow, ['video_id'], ''))
        favoriteCounts.set(favoriteVideoId, (favoriteCounts.get(favoriteVideoId) || 0) + 1)
        if (rowMatchesAny(favoriteRow, ['user_id'], identity.ids)) favoriteVideoIds.add(favoriteVideoId)
      }
      const recentViews = recommended
        ? (await pool.query(
          `SELECT video_id, user_id, viewed_at
           FROM fallback_video_views
           WHERE lower(user_id) = ANY($1::text[])
             AND viewed_at >= NOW() - INTERVAL '30 days'`,
          [Array.from(identity.ids)],
        )).rows
        : []
      const likeCounts = new Map()
      const likedVideoIds = new Set()
      for (const row of [...importedLikes, ...fallbackLikes]) {
        const videoId = textOf(row, ['video_id', 'videoId'], '')
        if (!videoId) continue
        likeCounts.set(videoId, (likeCounts.get(videoId) || 0) + 1)
        if (rowMatchesAny(row, ['user_id', 'userId'], identity.ids)) {
          likedVideoIds.add(videoId)
        }
      }
      const importedCommentCounts = videoCommentCountsFromRows(importedVideoCommentRows(importedVideoCommentRowsAll, videoIds))
      const commentCounts = mergeCountMaps(importedCommentCounts, localCommentCounts)
      const relevantImportedShares = importedShares.filter((row) => {
        const contentType = textOf(row, ['content_type', 'contentType', 'type'], '').toLowerCase()
        return contentType === 'video' || textOf(row, ['video_id', 'videoId'], '')
      })
      const { counts: shareCounts, mine: sharedVideoIds } = countPostInteractions(
        [...relevantImportedShares, ...fallbackShares],
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const { counts: importedShareCounts } = countPostInteractions(
        relevantImportedShares,
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const { counts: localShareCounts } = countPostInteractions(
        fallbackShares,
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      let rankedItems = sortByDateDesc(videos, ['created_at', 'updated_at'])
        .map((row) => {
          const video = enrichVideo(row, profilesById, likeCounts, likedVideoIds)
          const cleanVideoId = normalizeId(video.id)
          const metrics = metricRows.get(cleanVideoId) || { viewsDelta: 0, shareDelta: 0 }
          const mergedCommentCount = commentCounts.get(cleanVideoId) || 0
          const mergedShareCount = shareCounts.get(cleanVideoId)
          return {
            ...video,
            views: video.local_only ? video.views : video.views + metrics.viewsDelta,
            comment_count: mergedCommentCount,
            share_count: Math.max(video.share_count, importedShareCounts.get(cleanVideoId) || 0) + (localShareCounts.get(cleanVideoId) || 0),
            shared_by_me: sharedVideoIds.has(cleanVideoId),
            favorite_count: favoriteCounts.get(cleanVideoId) || 0,
            favorited_by_me: favoriteVideoIds.has(cleanVideoId),
          }
        })
      if (recommended) {
        const viewerProfile = publicProfiles.find((profile) =>
          Array.from(profileAliasSet(profile)).some((alias) => identity.ids.has(alias))
        ) || {}
        const rawProfileTerms = [
          ...arrayOf(viewerProfile, ['interests']),
          ...arrayOf(viewerProfile, ['hobbies']),
          ...arrayOf(viewerProfile, ['linked_games', 'linkedGames']),
          textOf(viewerProfile, ['favorite_sport', 'favoriteSport'], ''),
          textOf(viewerProfile, ['favorite_book', 'favoriteBook'], ''),
          textOf(viewerProfile, ['career'], ''),
        ].map((term) => normalizeId(term)).filter(Boolean)
        const profileTerms = new Set(
          rawProfileTerms.flatMap((term) => [term, ...term.split(/[^\p{L}\p{N}]+/u)])
            .filter((term) => term.length >= 3),
        )
        const interactedIds = new Set(recentViews.map((row) => normalizeId(row.video_id)))
        for (const like of [...importedLikes, ...fallbackLikes]) {
          if (rowMatchesAny(like, ['user_id', 'userId'], identity.ids)) {
            interactedIds.add(normalizeId(textOf(like, ['video_id', 'videoId'], '')))
          }
        }
        favoriteVideoIds.forEach((id) => interactedIds.add(id))
        const tagsByVideo = new Map(videos.map((row) => [
          normalizeId(textOf(row, ['id'], '')),
          new Set(arrayOf(row, ['tags']).map((tag) => normalizeId(tag)).filter(Boolean)),
        ]))
        const interestTags = new Set()
        interactedIds.forEach((id) => tagsByVideo.get(id)?.forEach((tag) => interestTags.add(tag)))
        const creatorCounts = new Map()
        videos.forEach((row) => {
          const creator = normalizeId(textOf(row, ['user_id', 'userId'], ''))
          creatorCounts.set(creator, (creatorCounts.get(creator) || 0) + 1)
        })
        const daySeed = new Date().toISOString().slice(0, 10)
        const now = Date.now()
        rankedItems = rankedItems
          .map((video) => {
            const tags = tagsByVideo.get(normalizeId(video.id)) || new Set()
            const overlap = Array.from(tags).filter((tag) => interestTags.has(tag)).length
            const searchable = [
              video.title || '',
              video.description || '',
              ...Array.from(tags),
            ].join(' ').toLowerCase()
            const profileOverlap = Array.from(profileTerms)
              .filter((term) => searchable.includes(term))
              .length
            const createdMs = Date.parse(video.created_at || video.updated_at || '') || 0
            const ageDays = createdMs > 0 ? Math.max(0, (now - createdMs) / 86_400_000) : 365
            const freshness = 24 * Math.exp(-ageDays / 10)
            const engagement = Math.log1p(
              Math.max(0, video.like_count || 0) * 2 +
              Math.max(0, video.comment_count || 0) * 3 +
              Math.max(0, video.share_count || 0) * 4 +
              Math.max(0, video.views || 0) * 0.05,
            ) * 5
            const creator = normalizeId(video.user_id)
            const discovery = 10 / Math.sqrt(Math.max(1, creatorCounts.get(creator) || 1))
            const stableExplore = crypto
              .createHash('sha256')
              .update(`${identity.displayUserId}:${video.id}:${daySeed}`)
              .digest()
              .readUInt32BE(0) / 0xffffffff * 5
            return {
              video,
              score: recommendationPreferenceScore({
                behavioralTagOverlap: overlap,
                profileTermOverlap: profileOverlap,
              }) + freshness + engagement + discovery + stableExplore,
            }
          })
          .sort((left, right) => right.score - left.score)
          .map((entry) => entry.video)
      }
      const items = rankedItems.slice(offset, offset + limit)
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/videos/:videoId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      const [importedProfiles, localProfiles, importedVideos, fallbackVideos, importedLikes, fallbackLikes] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
        importedRows(pool, 'videos', 5000),
        localVideos(pool, 5000),
        importedRows(pool, 'video_likes', 10_000),
        localVideoLikes(pool, [cleanId], 10_000),
      ])
      const deletedVideoIds = await localVideoDeletionIds(pool, [cleanId])
      if (deletedVideoIds.has(cleanId)) return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      const row = [...importedVideos, ...fallbackVideos].find((entry) => normalizeId(textOf(entry, ['id'], '')) === cleanId)
      if (!row) return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      if (!boolOf(row, ['is_public', 'isPublic'], true) &&
          !rowMatchesAny(row, ['user_id', 'userId', 'profile_id', 'owner_id', 'creator_id'], identity.ids)) {
        return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      }
      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      const likeCounts = new Map()
      const likedVideoIds = new Set()
      for (const likeRow of [...importedLikes, ...fallbackLikes]) {
        const videoId = textOf(likeRow, ['video_id', 'videoId'], '')
        if (normalizeId(videoId) !== cleanId) continue
        likeCounts.set(videoId, (likeCounts.get(videoId) || 0) + 1)
        if (rowMatchesAny(likeRow, ['user_id', 'userId'], identity.ids)) likedVideoIds.add(videoId)
      }
      const [importedVideoCommentRowsAll, localCommentCounts, importedShares, fallbackShares, metricRows] = await Promise.all([
        importedVideoComments(pool, 10_000),
        localVideoCommentCounts(pool, [cleanId]),
        importedRows(pool, 'shares', 10_000),
        localVideoShares(pool, [cleanId], 10_000),
        localVideoMetrics(pool, [cleanId]),
      ])
      const favoriteRows = await localVideoFavorites(pool, [cleanId])
      const importedCommentCounts = videoCommentCountsFromRows(importedVideoCommentRows(importedVideoCommentRowsAll, [cleanId]))
      const commentCounts = mergeCountMaps(importedCommentCounts, localCommentCounts)
      const relevantImportedShares = importedShares.filter((shareRow) => {
        const contentType = textOf(shareRow, ['content_type', 'contentType', 'type'], '').toLowerCase()
        return contentType === 'video' || normalizeId(textOf(shareRow, ['video_id', 'videoId'], '')) === cleanId
      })
      const { counts: shareCounts, mine: sharedVideoIds } = countPostInteractions(
        [...relevantImportedShares, ...fallbackShares],
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const { counts: importedShareCounts } = countPostInteractions(
        relevantImportedShares,
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const { counts: localShareCounts } = countPostInteractions(
        fallbackShares,
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const video = enrichVideo(row, profilesById, likeCounts, likedVideoIds)
      const metrics = metricRows.get(cleanId) || { viewsDelta: 0, shareDelta: 0 }
      res.json({
        video: {
          ...video,
          views: video.local_only ? video.views : video.views + metrics.viewsDelta,
          comment_count: commentCounts.get(cleanId) || 0,
          share_count: Math.max(video.share_count, importedShareCounts.get(cleanId) || 0) + (localShareCounts.get(cleanId) || 0),
          shared_by_me: sharedVideoIds.has(cleanId),
          favorite_count: favoriteRows.length,
          favorited_by_me: favoriteRows.some((favorite) => rowMatchesAny(favorite, ['user_id'], identity.ids)),
        },
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/videos', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const title = cleanText(req.body?.title || 'Nuevo reel', 120) || 'Nuevo reel'
      const description = cleanText(req.body?.description, 1_000)
      const videoUrl = String(req.body?.videoUrl || req.body?.video_url || '').trim()
      if (!videoUrl) return res.status(400).json({ error: 'VIDEO_URL_REQUIRED' })
      const coverUrl = String(req.body?.coverUrl || req.body?.cover_url || req.body?.thumbnailUrl || req.body?.thumbnail_url || '').trim() || null
      const durationSeconds = Number(req.body?.durationSeconds ?? req.body?.duration_seconds ?? req.body?.duration ?? 0)
      if (!Number.isFinite(durationSeconds) || durationSeconds < 3 || durationSeconds > 45) {
        return res.status(400).json({ error: 'INVALID_VIDEO_DURATION', message: 'El video debe durar entre 3 y 45 segundos.' })
      }
      const trimStart = Number(req.body?.trimStartSeconds ?? req.body?.trim_start_seconds ?? 0)
      const trimEnd = Number(req.body?.trimEndSeconds ?? req.body?.trim_end_seconds ?? durationSeconds)
      if (
        !Number.isFinite(trimStart) ||
        !Number.isFinite(trimEnd) ||
        trimStart < 0 ||
        trimEnd > durationSeconds ||
        trimEnd - trimStart < 3
      ) {
        return res.status(400).json({ error: 'INVALID_VIDEO_TRIM', message: 'El recorte debe conservar al menos 3 segundos y quedar dentro del video.' })
      }
      if (!(await currentUserOwnsStorageObject(pool, user.id, videoUrl, ['videos']))) {
        return res.status(403).json({ error: 'VIDEO_STORAGE_OWNERSHIP_REQUIRED' })
      }
      if (coverUrl && !(await currentUserOwnsStorageObject(pool, user.id, coverUrl, ['videos', 'thumbnails']))) {
        return res.status(403).json({ error: 'COVER_STORAGE_OWNERSHIP_REQUIRED' })
      }
      const isPublic = req.body?.isPublic ?? req.body?.is_public
      const tags = [...new Set(asArray(req.body?.tags).map((tag) => cleanText(tag, 40)).filter(Boolean))].slice(0, 12)
      const { rows } = await pool.query(
        `INSERT INTO fallback_videos(
            user_id, user_name, user_profile_image, title, description,
            video_url, cover_url, thumbnail_url, duration_seconds,
            trim_start_seconds, trim_end_seconds, is_public, tags
         )
         VALUES ($1, $2, $3, $4, $5, $6, $7, $7, $8, $9, $10, $11, $12::jsonb)
         RETURNING *`,
        [
          identity.displayUserId,
          user.display_name || user.email || 'Usuario',
          user.avatar_url || null,
          title,
          description,
          videoUrl,
          coverUrl,
          durationSeconds,
          trimStart,
          trimEnd,
          isPublic === false ? false : true,
          JSON.stringify(tags),
        ],
      )
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      res.status(201).json({ video: enrichVideo(normalizeImportedRow(rows[0]), profilesById, new Map(), new Set()) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/videos/:videoId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      const likeInsert = await pool.query(
        `INSERT INTO fallback_video_likes(video_id, user_id)
         VALUES ($1, $2)
         ON CONFLICT (video_id, user_id) DO NOTHING
         RETURNING video_id`,
        [cleanId, identity.displayUserId],
      )
      if (likeInsert.rowCount > 0) {
        const recipientId = await videoNotificationOwnerId(pool, cleanId, identity)
        if (recipientId) {
          await createLocalNotificationBestEffort(pool, {
            recipientId,
            actorId: identity.displayUserId,
            actorName: user.display_name || user.email || 'Usuario',
            actorAvatarUrl: user.avatar_url || '',
            type: 'like_video',
            targetId: cleanId,
            targetType: 'video',
            content: 'Le gustó tu video.',
            extraData: { video_id: cleanId },
          })
        }
      }
      res.json({ ok: true, liked: true })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/videos/:videoId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      await pool.query(
        `DELETE FROM fallback_video_likes
         WHERE lower(video_id) = $1 AND lower(user_id) = $2`,
        [normalizeId(req.params.videoId), identity.displayUserId],
      )
      res.json({ ok: true, liked: false })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/videos/:videoId/favorite', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      if (!cleanId) return res.status(400).json({ error: 'VIDEO_ID_REQUIRED' })
      if (!(await visibleVideoForIdentity(pool, cleanId, identity))) {
        return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      }
      await pool.query(
        `INSERT INTO fallback_video_favorites(video_id, user_id)
         VALUES ($1, $2)
         ON CONFLICT (video_id, user_id) DO NOTHING`,
        [cleanId, identity.displayUserId],
      )
      const { rows } = await pool.query(
        `SELECT COUNT(*)::int AS total FROM fallback_video_favorites WHERE lower(video_id) = $1`,
        [cleanId],
      )
      res.json({ ok: true, favorited_by_me: true, favorite_count: Number(rows[0]?.total) || 0 })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/videos/:videoId/favorite', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      if (!(await visibleVideoForIdentity(pool, cleanId, identity))) {
        return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      }
      await pool.query(
        `DELETE FROM fallback_video_favorites WHERE lower(video_id) = $1 AND lower(user_id) = $2`,
        [cleanId, identity.displayUserId],
      )
      const { rows } = await pool.query(
        `SELECT COUNT(*)::int AS total FROM fallback_video_favorites WHERE lower(video_id) = $1`,
        [cleanId],
      )
      res.json({ ok: true, favorited_by_me: false, favorite_count: Number(rows[0]?.total) || 0 })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/videos/:videoId/view', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)

      await pool.query(
        `INSERT INTO fallback_video_views(video_id, user_id, viewed_at)
         VALUES ($1, $2, NOW())
         ON CONFLICT (video_id, user_id) DO UPDATE SET viewed_at = NOW()`,
        [cleanId, identity.displayUserId],
      )

      const localUpdate = await pool.query(
        `UPDATE fallback_videos
         SET views = views + 1,
             updated_at = NOW()
         WHERE lower(id::text) = $1
         RETURNING views`,
        [cleanId],
      )
      if (localUpdate.rows[0]) {
        return res.json({ ok: true, view_count: Number(localUpdate.rows[0].views) || 0 })
      }

      const metrics = await pool.query(
        `INSERT INTO fallback_video_metrics(video_id, views_delta, updated_at)
         VALUES ($1, 1, NOW())
         ON CONFLICT (video_id)
         DO UPDATE SET views_delta = fallback_video_metrics.views_delta + 1,
                       updated_at = NOW()
         RETURNING views_delta`,
        [cleanId],
      )
      const importedVideos = await importedRows(pool, 'videos', 5000)
      const videoRow = importedVideos
        .find((row) => normalizeId(textOf(row, ['id'], '')) === cleanId) || {}
      const baseViews = numberOf(videoRow, ['views', 'view_count', 'viewCount'], 0)
      const viewsDelta = Number(metrics.rows[0]?.views_delta) || 0
      res.json({ ok: true, view_count: baseViews + viewsDelta })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/videos/:videoId/share', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      if (!cleanId) return res.status(400).json({ error: 'VIDEO_ID_REQUIRED' })

      const shareInsert = await pool.query(
        `INSERT INTO fallback_shares(user_id, post_id, content_type, created_at)
         VALUES ($1, $2, 'video', NOW())
         ON CONFLICT (user_id, post_id, content_type) DO NOTHING
         RETURNING post_id`,
        [identity.displayUserId, cleanId],
      )
      if (shareInsert.rowCount > 0) {
        const recipientId = await videoNotificationOwnerId(pool, cleanId, identity)
        if (recipientId) {
          await createLocalNotificationBestEffort(pool, {
            recipientId,
            actorId: identity.displayUserId,
            actorName: user.display_name || user.email || 'Usuario',
            actorAvatarUrl: user.avatar_url || '',
            type: 'share_video',
            targetId: cleanId,
            targetType: 'video',
            content: 'Añadió tu video a su perfil.',
            extraData: { video_id: cleanId },
          })
        }
      }
      const [importedShares, fallbackShares, importedVideos, fallbackVideos] = await Promise.all([
        importedRows(pool, 'shares', 10_000),
        localVideoShares(pool, [cleanId], 10_000),
        importedRows(pool, 'videos', 5000),
        localVideos(pool, 5000),
      ])
      const videoRow = [...fallbackVideos, ...importedVideos]
        .find((row) => normalizeId(textOf(row, ['id'], '')) === cleanId) || {}
      const relevantImportedShares = importedShares.filter((row) => {
        const contentType = textOf(row, ['content_type', 'contentType', 'type'], '').toLowerCase()
        return contentType === 'video' || normalizeId(textOf(row, ['video_id', 'videoId'], '')) === cleanId
      })
      const { counts, mine } = countPostInteractions(
        [...relevantImportedShares, ...fallbackShares],
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const { counts: importedShareCounts } = countPostInteractions(
        relevantImportedShares,
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const { counts: localShareCounts } = countPostInteractions(
        fallbackShares,
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const baseShareCount = numberOf(videoRow, ['share_count', 'shareCount'], 0)
      res.json({
        ok: true,
        share_count: Math.max(baseShareCount, importedShareCounts.get(cleanId) || 0) + (localShareCounts.get(cleanId) || 0),
        shared_by_me: mine.has(cleanId),
      })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/videos/:videoId/share', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      if (!cleanId) return res.status(400).json({ error: 'VIDEO_ID_REQUIRED' })

      await pool.query(
        `DELETE FROM fallback_shares
         WHERE lower(user_id) = $1
           AND lower(post_id) = $2
           AND content_type = 'video'`,
        [identity.displayUserId, cleanId],
      )

      const [importedShares, fallbackShares, importedVideos, fallbackVideos] = await Promise.all([
        importedRows(pool, 'shares', 10_000),
        localVideoShares(pool, [cleanId], 10_000),
        importedRows(pool, 'videos', 5000),
        localVideos(pool, 5000),
      ])
      const videoRow = [...fallbackVideos, ...importedVideos]
        .find((row) => normalizeId(textOf(row, ['id'], '')) === cleanId) || {}
      const relevantImportedShares = importedShares.filter((row) => {
        const contentType = textOf(row, ['content_type', 'contentType', 'type'], '').toLowerCase()
        return contentType === 'video' || normalizeId(textOf(row, ['video_id', 'videoId'], '')) === cleanId
      })
      const { counts: importedShareCounts } = countPostInteractions(
        relevantImportedShares,
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const { counts: localShareCounts } = countPostInteractions(
        fallbackShares,
        identity,
        ['video_id', 'videoId', 'post_id', 'content_id'],
      )
      const baseShareCount = numberOf(videoRow, ['share_count', 'shareCount'], 0)
      res.json({
        ok: true,
        share_count: Math.max(baseShareCount, importedShareCounts.get(cleanId) || 0) + (localShareCounts.get(cleanId) || 0),
        shared_by_me: false,
      })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/videos/:videoId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      if (!cleanId) return res.status(400).json({ error: 'VIDEO_ID_REQUIRED' })

      const [importedVideos, fallbackVideos] = await Promise.all([
        importedRows(pool, 'videos', 5000),
        localVideos(pool, 5000),
      ])
      const row = [...fallbackVideos, ...importedVideos]
        .find((entry) => normalizeId(textOf(entry, ['id'], '')) === cleanId)
      if (!row) return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      if (!rowMatchesAny(row, ['user_id', 'userId', 'profile_id', 'author_id', 'owner_id', 'creator_id'], identity.ids)) {
        return res.status(403).json({ error: 'VIDEO_NOT_OWNED' })
      }

      await pool.query(`DELETE FROM fallback_videos WHERE lower(id::text) = $1`, [cleanId])
      await pool.query(
        `INSERT INTO fallback_video_deletions(video_id, user_id, deleted_at)
         VALUES ($1, $2, NOW())
         ON CONFLICT (video_id)
         DO UPDATE SET user_id = EXCLUDED.user_id, deleted_at = NOW()`,
        [cleanId, identity.displayUserId],
      )
      await Promise.all([
        pool.query(`DELETE FROM fallback_video_likes WHERE lower(video_id) = $1`, [cleanId]),
        pool.query(`DELETE FROM fallback_video_comments WHERE lower(video_id) = $1`, [cleanId]),
        pool.query(`DELETE FROM fallback_video_favorites WHERE lower(video_id) = $1`, [cleanId]),
        pool.query(`DELETE FROM fallback_video_views WHERE lower(video_id) = $1`, [cleanId]),
        pool.query(`DELETE FROM fallback_video_comment_reports WHERE lower(video_id) = $1`, [cleanId]),
        pool.query(`DELETE FROM fallback_shares WHERE content_type = 'video' AND lower(post_id) = $1`, [cleanId]),
        pool.query(`DELETE FROM fallback_video_metrics WHERE lower(video_id) = $1`, [cleanId]),
      ])
      res.json({ ok: true, deleted: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/videos/:videoId/comments', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      if (!(await visibleVideoForIdentity(pool, cleanId, identity))) {
        return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      }
      const [importedProfiles, localProfiles, importedCommentRowsAll, localComments] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
        importedVideoComments(pool, 10_000),
        localVideoComments(pool, cleanId, Number(req.query.limit) || 5000),
      ])
      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      const comments = sortByDateDesc(
        [...importedVideoCommentRows(importedCommentRowsAll, [cleanId]), ...localComments],
        ['created_at', 'updated_at'],
      ).reverse()
      const mappedComments = comments.map((row) => mapVideoComment(row, profilesById, identity))
      const reactionState = await localVideoCommentReactions(pool, mappedComments.map((comment) => comment.id), identity)
      res.json({
        items: mappedComments.map((comment) => {
          const state = reactionState.get(normalizeId(comment.id)) || { reactions: {}, myReaction: null }
          return { ...comment, reactions: state.reactions, my_reaction: state.myReaction }
        }),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/videos/:videoId/comments', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const cleanId = normalizeId(req.params.videoId)
      const content = String(req.body?.content || '').trim()
      const contentType = String(req.body?.contentType || req.body?.content_type || 'text').trim() || 'text'
      const mediaUrl = String(req.body?.mediaUrl || req.body?.media_url || '').trim() || null
      const parentCommentId = String(req.body?.parentId || req.body?.parent_comment_id || '').trim() || null
      if (!cleanId || (!content && !mediaUrl)) {
        return res.status(400).json({ error: 'VIDEO_ID_AND_CONTENT_OR_MEDIA_REQUIRED' })
      }
      if (!(await visibleVideoForIdentity(pool, cleanId, identity))) {
        return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      }
      if (!new Set(['text', 'audio', 'sticker']).has(contentType)) {
        return res.status(400).json({ error: 'VIDEO_COMMENT_CONTENT_TYPE_INVALID' })
      }
      if (!(await currentUserMayReferenceMessageMedia(pool, user, identity, {
        mediaUrl,
        contentType,
        allowedOwnedBuckets: ['video-comments-media'],
      }))) {
        return res.status(403).json({ error: 'VIDEO_COMMENT_MEDIA_REFERENCE_FORBIDDEN' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_video_comments(
            video_id,
            user_id,
            user_name,
            user_profile_image,
            content,
            content_type,
            media_url,
            parent_comment_id
         )
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
         RETURNING id::text, video_id, user_id, user_name, user_profile_image, content,
                   content_type, media_url, parent_comment_id, created_at, updated_at`,
        [
          cleanId,
          identity.displayUserId,
          user.display_name || user.email || 'Usuario',
          user.avatar_url || null,
          content,
          contentType,
          mediaUrl,
          parentCommentId,
        ],
      )
      const recipientId = await videoNotificationOwnerId(pool, cleanId, identity)
      if (recipientId) {
        await createLocalNotificationBestEffort(pool, {
          recipientId,
          actorId: identity.displayUserId,
          actorName: user.display_name || user.email || 'Usuario',
          actorAvatarUrl: user.avatar_url || '',
          type: 'comment_video',
          targetId: cleanId,
          targetType: 'video',
          content: 'Comentó tu video.',
          extraData: { video_id: cleanId, comment_id: rows[0]?.id },
        })
      }
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      const commentCount = await syncLocalVideoCommentCount(pool, cleanId)
      void publishRealtimeEvent(`video-comments:${cleanId}`, {
        event: 'video_comment_created',
        videoId: cleanId,
        commentId: rows[0]?.id,
        commentCount,
      })
      res.status(201).json({
        comment: mapVideoComment(normalizeImportedRow(rows[0]), profilesById, identity),
        comment_count: commentCount,
      })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/videos/comments/:commentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      const content = cleanText(req.body?.content, 2000)
      if (!commentId || !content) return res.status(400).json({ error: 'COMMENT_ID_AND_CONTENT_REQUIRED' })
      const { rows } = await pool.query(
        `UPDATE fallback_video_comments
         SET content = $2, updated_at = NOW()
         WHERE lower(id::text) = $1
           AND lower(user_id) = $3
           AND is_deleted = FALSE
           AND created_at >= NOW() - INTERVAL '5 hours'
           AND content_type = 'text'
         RETURNING id::text, video_id, updated_at`,
        [commentId, content, identity.displayUserId],
      )
      if (!rows[0]) return res.status(403).json({ error: 'VIDEO_COMMENT_EDIT_DENIED_OR_EXPIRED' })
      const videoId = normalizeId(rows[0].video_id)
      void publishRealtimeEvent(`video-comments:${videoId}`, { event: 'video_comment_updated', videoId, commentId })
      res.json({ ok: true, commentId, updatedAt: rows[0].updated_at })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/videos/comments/:commentId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      const reactionType = cleanText(req.body?.reactionType || req.body?.reaction_type, 16).toUpperCase()
      const allowed = new Set(['LIKE', 'LOVE', 'LAUGH', 'WOW', 'SAD'])
      if (!commentId || !allowed.has(reactionType)) return res.status(400).json({ error: 'VIDEO_COMMENT_REACTION_INVALID' })
      const { rows: comments } = await pool.query(
        `SELECT video_id FROM fallback_video_comments WHERE lower(id::text) = $1 AND is_deleted = FALSE LIMIT 1`,
        [commentId],
      )
      if (!comments[0]) return res.status(404).json({ error: 'VIDEO_COMMENT_NOT_FOUND' })
      await pool.query(
        `INSERT INTO fallback_video_comment_reactions(comment_id, user_id, reaction_type)
         VALUES ($1, $2, $3)
         ON CONFLICT (comment_id, user_id)
         DO UPDATE SET reaction_type = EXCLUDED.reaction_type, updated_at = NOW()`,
        [commentId, identity.displayUserId, reactionType],
      )
      const videoId = normalizeId(comments[0].video_id)
      void publishRealtimeEvent(`video-comments:${videoId}`, { event: 'video_comment_reacted', videoId, commentId })
      res.json({ ok: true, reaction_type: reactionType })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/videos/comments/:commentId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      const { rows: comments } = await pool.query(
        `SELECT video_id FROM fallback_video_comments WHERE lower(id::text) = $1 LIMIT 1`,
        [commentId],
      )
      await pool.query(
        `DELETE FROM fallback_video_comment_reactions WHERE lower(comment_id) = $1 AND lower(user_id) = $2`,
        [commentId, identity.displayUserId],
      )
      const videoId = normalizeId(comments[0]?.video_id)
      if (videoId) void publishRealtimeEvent(`video-comments:${videoId}`, { event: 'video_comment_reacted', videoId, commentId })
      res.json({ ok: true, reaction_type: null })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/videos/comments/:commentId/report', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      const reason = cleanText(req.body?.reason, 120)
      const details = cleanText(req.body?.details, 1000)
      if (!commentId || !reason) return res.status(400).json({ error: 'COMMENT_ID_AND_REASON_REQUIRED' })
      const { rows: comments } = await pool.query(
        `SELECT video_id FROM fallback_video_comments WHERE lower(id::text) = $1 AND is_deleted = FALSE LIMIT 1`,
        [commentId],
      )
      let comment = comments[0]
      if (!comment) {
        const importedComments = await importedVideoComments(pool, 10_000)
        comment = importedComments.find((row) => normalizeId(textOf(row, ['id'], '')) === commentId)
      }
      if (!comment) return res.status(404).json({ error: 'VIDEO_COMMENT_NOT_FOUND' })
      const videoId = videoIdOf(comment)
      const result = await pool.query(
        `INSERT INTO fallback_video_comment_reports(comment_id, video_id, reporter_id, reason, details)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (comment_id, reporter_id) DO NOTHING
         RETURNING id::text`,
        [commentId, videoId, identity.displayUserId, reason, details],
      )
      if (result.rowCount === 0) return res.status(409).json({ error: 'VIDEO_COMMENT_ALREADY_REPORTED' })
      res.status(201).json({ ok: true, reportId: result.rows[0].id })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/videos/comments/:commentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = normalizeId(req.params.commentId)
      if (!commentId) return res.status(400).json({ error: 'COMMENT_ID_REQUIRED' })

      const { rows: commentRows } = await pool.query(
        `SELECT id::text, video_id, user_id, user_name, user_profile_image, content,
                content_type, media_url, parent_comment_id, created_at, updated_at
         FROM fallback_video_comments
         WHERE lower(id::text) = $1 AND is_deleted = FALSE
         LIMIT 1`,
        [commentId],
      )
      let comment = commentRows[0]
      let commentSource = 'fallback'

      if (!comment) {
        const hasImport = await tableExists(pool, 'video_comments')
        if (hasImport) {
          const { rows: impRows } = await pool.query(
            `SELECT * FROM supabase_import.video_comments
             WHERE lower(id::text) = $1`,
            [commentId]
          )
          if (impRows.length > 0) {
            comment = impRows[0]
            commentSource = 'imported'
          }
        }
      }

      if (!comment) return res.status(404).json({ error: 'VIDEO_COMMENT_NOT_FOUND' })

      const isCommentAuthor = rowMatchesAny(comment, ['user_id', 'profile_id', 'author_id', 'owner_id', 'creator_id'], identity.ids)
      let isVideoOwner = false
      const cleanVideoId = normalizeId(comment.video_id)

      if (cleanVideoId) {
        let videoRow = null
        const { rows: fbVideos } = await pool.query(
          `SELECT * FROM fallback_videos WHERE lower(id::text) = $1 LIMIT 1`,
          [cleanVideoId]
        )
        if (fbVideos.length > 0) {
          videoRow = fbVideos[0]
        } else {
          const hasVidImport = await tableExists(pool, 'videos')
          if (hasVidImport) {
            const { rows: impVids } = await pool.query(
              `SELECT * FROM supabase_import.videos WHERE lower(id::text) = $1 LIMIT 1`,
              [cleanVideoId]
            ).catch(() => ({ rows: [] })) // Ignore errors if columns differ slightly

            if (impVids.length > 0) videoRow = impVids[0]
            else {
                // Fallback to select * if specific columns fail
                const { rows: impVidsAll } = await pool.query(
                  `SELECT * FROM supabase_import.videos WHERE lower(id::text) = $1 LIMIT 1`,
                  [cleanVideoId]
                )
                if (impVidsAll.length > 0) videoRow = impVidsAll[0]
            }
          }
        }

        isVideoOwner = Boolean(
          videoRow &&
            rowMatchesAny(
              videoRow,
              ['user_id', 'userId', 'profile_id', 'profileId', 'author_id', 'authorId', 'owner_id', 'ownerId', 'creator_id', 'creatorId', 'uploaded_by', 'uploadedBy', 'uploader_id', 'uploaderId'],
              identity.ids,
            ),
        )
      }

      if (!isCommentAuthor && !isVideoOwner) {
        return res.status(403).json({ error: 'VIDEO_COMMENT_DELETE_DENIED' })
      }

      const { rowCount } = commentSource === 'fallback'
        ? await pool.query(
          `UPDATE fallback_video_comments
           SET is_deleted = TRUE, updated_at = NOW()
           WHERE lower(id::text) = $1 AND is_deleted = FALSE`,
          [commentId],
        )
        : await pool.query(
          `DELETE FROM supabase_import.video_comments
           WHERE lower(id::text) = $1`,
          [commentId],
        )
      if (rowCount === 0) return res.status(404).json({ error: 'VIDEO_COMMENT_NOT_FOUND' })
      await pool.query(`DELETE FROM fallback_video_comment_reactions WHERE lower(comment_id) = $1`, [commentId])
      if (commentSource === 'imported') clearImportedRowCaches('video_comments')
      const commentCount = cleanVideoId
        ? await syncLocalVideoCommentCount(pool, cleanVideoId)
        : 0
      if (cleanVideoId) {
        void publishRealtimeEvent(`video-comments:${cleanVideoId}`, {
          event: 'video_comment_deleted',
          videoId: cleanVideoId,
          commentId,
          commentCount,
        })
      }
      res.json({ ok: true, comment_count: commentCount })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/challenges/active', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const active = await ensureActiveChallenge(pool, user)
      const masters = await localChallengeMasters(pool, 500)
      const master = masters.find((row) => challengeId(row) === normalizeId(textOf(active, ['challenge_id'], '')))
      res.json({ activeChallenge: mapActiveChallenge(active, master) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/challenges/master', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const title = cleanText(req.body?.title, 180)
      if (!title) return res.status(400).json({ error: 'TITLE_REQUIRED' })
      const description = cleanText(req.body?.description, 2000)
      const challengeType = cleanText(req.body?.type || req.body?.challengeType || req.body?.challenge_type || 'mixed', 80) || 'mixed'
      const difficulty = cleanText(req.body?.difficulty || 'normal', 80) || 'normal'
      const { rows } = await pool.query(
        `INSERT INTO fallback_challenges_master(title, description, challenge_type, difficulty, creator_id)
         VALUES ($1, $2, $3, $4, $5)
         RETURNING id::text, title, description, challenge_type, difficulty, creator_id, created_at, updated_at`,
        [title, description, challengeType, difficulty, identity.displayUserId],
      )
      res.status(201).json({ challenge: mapChallenge(normalizeImportedRow(rows[0])) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/challenges/master/mine', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const masters = await localChallengeMasters(pool, 1000)
      res.json({ items: masters.filter((row) => rowMatchesAny(row, ['creator_id'], identity.ids)).map(mapChallenge) })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/challenges/master/:challengeId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const { rowCount } = await pool.query(
        `DELETE FROM fallback_challenges_master
         WHERE lower(id::text) = $1 AND lower(creator_id) = $2`,
        [normalizeId(req.params.challengeId), identity.displayUserId],
      )
      if (rowCount === 0) return res.status(404).json({ error: 'CHALLENGE_NOT_FOUND' })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/challenges/:activeChallengeId/submissions', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const activeId = normalizeId(req.params.activeChallengeId)
      const { rows: submissionRows } = await pool.query(
        `SELECT id::text, challenge_id, active_challenge_id, user_id, content_url, text_content, created_at, updated_at
         FROM fallback_challenge_submissions
         WHERE lower(active_challenge_id) = $1 OR lower(challenge_id) = $1
         ORDER BY created_at DESC`,
        [activeId],
      )
      const submissionIds = submissionRows.map((row) => normalizeId(row.id)).filter(Boolean)
      const [{ rows: voteRows }, importedProfiles, localProfiles] = await Promise.all([
        submissionIds.length > 0
          ? pool.query(
              `SELECT submission_id, voter_id FROM fallback_challenge_votes
               WHERE lower(submission_id) = ANY($1::text[])`,
              [submissionIds],
            )
          : Promise.resolve({ rows: [] }),
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
      ])
      const voteCounts = new Map()
      const votedSubmissionIds = new Set()
      for (const vote of voteRows) {
        const submissionId = normalizeId(vote.submission_id)
        voteCounts.set(submissionId, (voteCounts.get(submissionId) || 0) + 1)
        if (identity.ids.has(normalizeId(vote.voter_id))) votedSubmissionIds.add(submissionId)
      }
      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      res.json({
        items: submissionRows
          .map(normalizeImportedRow)
          .map((row) => mapChallengeSubmission(row, profilesById, voteCounts, votedSubmissionIds)),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/challenges/:activeChallengeId/submissions', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const activeId = normalizeId(req.params.activeChallengeId)
      const activeRows = await localActiveChallenges(pool, 500)
      const active = activeRows.find((row) => normalizeId(textOf(row, ['id'], '')) === activeId)
      const challengeIdValue = String(req.body?.challengeId || req.body?.challenge_id || textOf(active || {}, ['challenge_id']) || activeId).trim()
      const contentUrl = String(req.body?.contentUrl || req.body?.content_url || '').trim() || null
      const textContent = String(req.body?.textContent || req.body?.text_content || '').trim() || null
      if (!activeId || (!contentUrl && !textContent)) {
        return res.status(400).json({ error: 'ACTIVE_CHALLENGE_AND_CONTENT_REQUIRED' })
      }
      if (contentUrl && !(await currentUserOwnsStorageObject(pool, user.id, contentUrl, ['challenges']))) {
        return res.status(403).json({ error: 'CHALLENGE_MEDIA_STORAGE_OWNERSHIP_REQUIRED' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_challenge_submissions(challenge_id, active_challenge_id, user_id, content_url, text_content)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (active_challenge_id, user_id)
         DO UPDATE SET
            challenge_id = EXCLUDED.challenge_id,
            content_url = EXCLUDED.content_url,
            text_content = EXCLUDED.text_content,
            updated_at = NOW()
         RETURNING id::text, challenge_id, active_challenge_id, user_id, content_url, text_content, created_at, updated_at`,
        [challengeIdValue, activeId, identity.displayUserId, contentUrl, textContent],
      )
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      res.status(201).json({
        submission: mapChallengeSubmission(normalizeImportedRow(rows[0]), profilesById, new Map(), new Set()),
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/challenges/:activeChallengeId/submissions/me', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const activeId = normalizeId(req.params.activeChallengeId)
      const { rows } = await pool.query(
        `SELECT id::text, challenge_id, active_challenge_id, user_id, content_url, text_content, created_at, updated_at
         FROM fallback_challenge_submissions
         WHERE (lower(active_challenge_id) = $1 OR lower(challenge_id) = $1)
           AND lower(user_id) = $2
         LIMIT 1`,
        [activeId, identity.displayUserId],
      )
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      res.json({
        submission: rows[0]
          ? mapChallengeSubmission(normalizeImportedRow(rows[0]), profilesById, new Map(), new Set())
          : null,
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/challenges/:activeChallengeId/participation', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const activeId = normalizeId(req.params.activeChallengeId)
      const { rows } = await pool.query(
        `SELECT 1
         FROM fallback_challenge_submissions
         WHERE (lower(active_challenge_id) = $1 OR lower(challenge_id) = $1)
           AND lower(user_id) = $2
         LIMIT 1`,
        [activeId, identity.displayUserId],
      )
      res.json({ participating: rows.length > 0 })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/challenges/submissions/:submissionId/vote', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const submissionId = normalizeId(req.params.submissionId)
      const existing = await pool.query(
        `SELECT 1 FROM fallback_challenge_votes
         WHERE lower(submission_id) = $1 AND lower(voter_id) = $2
         LIMIT 1`,
        [submissionId, identity.displayUserId],
      )
      let votedByMe = true
      if (existing.rows.length > 0) {
        await pool.query(
          `DELETE FROM fallback_challenge_votes
           WHERE lower(submission_id) = $1 AND lower(voter_id) = $2`,
          [submissionId, identity.displayUserId],
        )
        votedByMe = false
      } else {
        await pool.query(
          `INSERT INTO fallback_challenge_votes(submission_id, voter_id)
           VALUES ($1, $2)
           ON CONFLICT (submission_id, voter_id) DO NOTHING`,
          [submissionId, identity.displayUserId],
        )
      }
      const { rows } = await pool.query(
        `SELECT COUNT(*)::int AS total
         FROM fallback_challenge_votes
         WHERE lower(submission_id) = $1`,
        [submissionId],
      )
      res.json({ vote_count: Number(rows[0]?.total || 0), voted_by_me: votedByMe })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/friends', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const tab = String(req.query.tab || 'incoming').trim().toLowerCase()
      const [imported, localRows] = await Promise.all([
        importedRows(pool, 'friends', 5000),
        pool.query(
          `SELECT id::text, sender_id, receiver_id, status, created_at
           FROM fallback_friends
           ORDER BY created_at DESC
           LIMIT 5000`,
        ).then((result) => result.rows.map(normalizeImportedRow)),
      ])
      const merged = [...imported, ...localRows].filter((row) => {
        if (tab === 'outgoing') return rowMatchesAny(row, ['sender_id'], identity.ids)
        if (tab === 'accepted') return row.status === 'accepted' && (rowMatchesAny(row, ['sender_id', 'receiver_id'], identity.ids))
        return row.status === 'pending' && rowMatchesAny(row, ['receiver_id'], identity.ids)
      })
      res.json({ items: sortByDateDesc(merged, ['created_at']) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/friends', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const receiverId = normalizeId(req.body?.receiverId || req.body?.receiver_id || '')
      if (!receiverId || receiverId === identity.displayUserId) {
        return res.status(400).json({ error: 'INVALID_RECEIVER' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_friends(sender_id, receiver_id, status)
         VALUES ($1, $2, 'pending')
         ON CONFLICT (sender_id, receiver_id) DO UPDATE SET status = EXCLUDED.status
         RETURNING id::text, sender_id, receiver_id, status, created_at`,
        [identity.displayUserId, receiverId],
      )
      res.status(201).json({ request: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/friends/:requestId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const status = String(req.body?.status || '').trim().toLowerCase()
      if (!['accepted', 'rejected'].includes(status)) {
        return res.status(400).json({ error: 'INVALID_STATUS' })
      }
      const { rows } = await pool.query(
        `UPDATE fallback_friends
         SET status = $3
         WHERE id::text = $1 AND lower(receiver_id) = $2
         RETURNING id::text, sender_id, receiver_id, status, created_at`,
        [normalizeId(req.params.requestId), identity.displayUserId, status],
      )
      if (!rows[0]) return res.status(404).json({ error: 'REQUEST_NOT_FOUND' })
      res.json({ request: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/friends/:requestId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const requestId = normalizeId(req.params.requestId)
      const { rows } = await pool.query(
        `DELETE FROM fallback_friends
         WHERE id::text = $1
           AND (lower(sender_id) = $2 OR lower(receiver_id) = $2)
         RETURNING id::text, sender_id, receiver_id, status, created_at`,
        [requestId, identity.displayUserId],
      )
      if (!rows[0]) return res.status(404).json({ error: 'REQUEST_NOT_FOUND' })
      res.json({ request: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/friends/users/:targetUserId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const targetUserId = normalizeId(req.params.targetUserId)
      if (!targetUserId) return res.status(400).json({ error: 'TARGET_USER_REQUIRED' })
      const { rows } = await pool.query(
        `DELETE FROM fallback_friends
         WHERE (
           (lower(sender_id) = $1 AND lower(receiver_id) = $2)
           OR (lower(sender_id) = $2 AND lower(receiver_id) = $1)
         )
         RETURNING id::text, sender_id, receiver_id, status, created_at`,
        [identity.displayUserId, targetUserId],
      )
      res.json({ items: rows.map(normalizeImportedRow), deleted: rows.length })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/typing', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const targetType = String(req.body?.targetType || req.body?.target_type || '').trim().toLowerCase()
      const conversationId = normalizeId(req.body?.conversationId || req.body?.conversation_id || '')
      const isTyping = Boolean(req.body?.isTyping ?? req.body?.is_typing)
      const displayName = String(req.body?.displayName || req.body?.display_name || user.display_name || user.email || '').trim()
      const channelPrefixByType = {
        chat: 'typing-chat',
        group: 'typing-group',
        community: 'typing-community',
      }
      const channelPrefix = channelPrefixByType[targetType]
      if (!channelPrefix || !conversationId) return res.status(400).json({ error: 'INVALID_TYPING_TARGET' })

      const { publishRealtimeEvent } = require('./realtime-publish')
      const payload = {
        event: 'typing',
        conversationId,
        targetType,
        userId: identity.displayUserId,
        displayName,
        isTyping,
        timestamp: Date.now(),
      }
      const published = await publishRealtimeEvent(`${channelPrefix}:${conversationId}`, payload)
      res.json({ ok: true, published })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/messages/nicknames/:targetUserId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const targetUserId = normalizeId(req.params.targetUserId)
      const nickname = cleanText(req.body?.nickname, 120)
      if (!targetUserId) return res.status(400).json({ error: 'TARGET_USER_REQUIRED' })

      if (!nickname) {
        await pool.query(
          `DELETE FROM fallback_nicknames
           WHERE lower(user_id) = $1 AND lower(target_user_id) = $2`,
          [identity.displayUserId, targetUserId],
        )
        return res.json({ ok: true, nickname: null })
      }

      const { rows } = await pool.query(
        `INSERT INTO fallback_nicknames(user_id, target_user_id, nickname, updated_at)
         VALUES ($1, $2, $3, NOW())
         ON CONFLICT (user_id, target_user_id)
         DO UPDATE SET nickname = EXCLUDED.nickname, updated_at = NOW()
         RETURNING user_id, target_user_id, nickname, updated_at`,
        [identity.displayUserId, targetUserId, nickname],
      )
      res.json({ ok: true, nickname: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/messages/nicknames/:targetUserId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const targetUserId = normalizeId(req.params.targetUserId)
      if (!targetUserId) return res.status(400).json({ error: 'TARGET_USER_REQUIRED' })
      await pool.query(
        `DELETE FROM fallback_nicknames
         WHERE lower(user_id) = $1 AND lower(target_user_id) = $2`,
        [identity.displayUserId, targetUserId],
      )
      res.json({ ok: true, nickname: null })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/messages/chats/:chatId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const chatId = normalizeId(req.params.chatId)
      const [profiles, localProfiles, importedChats, importedParticipants, importedMessages, fallbackChats] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
        importedRows(pool, 'chats', 5000),
        importedRows(pool, 'chat_participants', 10_000),
        importedRows(pool, 'messages', 10_000),
        localChats(pool, 5000),
      ])
      const chats = [...importedChats, ...fallbackChats]
      const participants = [
        ...importedParticipants,
        ...(await localChatParticipants(pool, [chatId])),
      ]

      const chat = chats.find((row) => normalizeId(textOf(row, ['id'], '')) === chatId)
      const memberRows = participants.filter((row) => normalizeId(textOf(row, ['chat_id'], '')) === chatId)
      const hasAccess =
        (chat && rowParticipantsContain(chat, identity.ids)) ||
        memberRows.some((row) => rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids))

      if (!chat || !hasAccess) {
        return res.status(404).json({ error: 'CHAT_NOT_FOUND' })
      }

      let otherId = memberRows
        .map((row) => normalizeId(textOf(row, ['user_id', 'profile_id'], '')))
        .find((entry) => entry && !identity.ids.has(entry))
      if (!otherId) {
        otherId = arrayOf(chat, ['participants']).map(normalizeId).find((entry) => !identity.ids.has(entry)) || ''
      }

      const profilesById = indexProfiles([...profiles, ...localProfiles])
      const otherProfile = otherId ? profilesById.get(otherId) : null
      const nicknamesByTarget = await localNicknames(pool, identity.displayUserId)
      const nickname = nicknamesByTarget.get(normalizeId(otherId)) || ''
      const otherParticipantRow = memberRows.find((row) => {
        const id = normalizeId(textOf(row, ['user_id', 'profile_id'], ''))
        return id && !identity.ids.has(id)
      })
      const myParticipantRow = memberRows.find((row) => {
        const id = normalizeId(textOf(row, ['user_id', 'profile_id'], ''))
        return id && identity.ids.has(id)
      })
      const myClearedAt = toMillis(textOf(myParticipantRow || {}, ['cleared_at', 'clearedAt'], ''))

      const messages = [
        ...importedMessages.filter((row) => normalizeId(textOf(row, ['chat_id'], '')) === chatId),
        ...(await localMessages(pool, [chatId])),
      ].sort((a, b) => toMillis(textOf(a, ['created_at'], '')) - toMillis(textOf(b, ['created_at'], '')))
       .filter((msg) => messageVisibleTo(msg, identity))
       .filter((msg) => !myClearedAt || toMillis(textOf(msg, ['created_at'], '')) > myClearedAt)

      res.json({
        chat: { ...chat, nickname: nickname || null, cleared_at: textOf(myParticipantRow || {}, ['cleared_at', 'clearedAt'], '') || null },
        title: nickname || (otherProfile ? profileName(otherProfile, 'Chat privado') : textOf(chat, ['name', 'group_name'], 'Chat privado')),
        otherParticipant: otherId
          ? {
              id: otherId,
              name: otherProfile ? profileName(otherProfile, 'Usuario') : otherId,
              avatarUrl: otherProfile ? profileAvatar(otherProfile) : '',
              lastReadAt: textOf(otherParticipantRow || {}, ['last_read_at'], ''),
            }
          : null,
        messages,
        settings: {
          isMuted: false,
          isPinned: false,
          isArchived: false,
          isFavorite: false,
          translateEnabled: false,
          targetLang: 'es',
        },
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/chats/:chatId/messages', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const chatId = normalizeId(req.params.chatId)
      const content = String(req.body?.content || '').trim()
      const contentType = String(req.body?.contentType || 'text').trim() || 'text'
      const mediaUrl = String(req.body?.mediaUrl || '').trim()
      const thumbnailUrl = String(req.body?.thumbnailUrl || req.body?.thumbnail_url || '').trim()
      const mediaSize = Math.max(0, Number(req.body?.mediaSize || 0))
      const replyToId = String(req.body?.replyToId || '').trim() || null
      const isForwarded = Boolean(req.body?.isForwarded || req.body?.is_forwarded)
      const forwardedFromMessageId = cleanText(req.body?.forwardedFromMessageId || req.body?.forwarded_from_message_id, 80) || null
      const forwardedFromUserId = normalizeId(req.body?.forwardedFromUserId || req.body?.forwarded_from_user_id) || null
      const requestedId = validUuid(req.body?.id)
      if (!chatId || (!content && !mediaUrl)) {
        return res.status(400).json({ error: 'CHAT_ID_AND_CONTENT_OR_MEDIA_REQUIRED' })
      }

      const identity = userIdentity(user)
      const accessContext = await privateChatAccessContext(pool, chatId, identity)
      if (!accessContext.hasAccess) return res.status(403).json({ error: 'CHAT_ACCESS_DENIED' })
      const blockRelationship = await blockRelationshipForChat(
        pool,
        accessContext.chat,
        accessContext.participantRows,
        identity,
      )
      if (blockRelationship.isBlocked) {
        return res.status(403).json({
          error: 'CHAT_BLOCKED_BY_RECIPIENT',
          blockedByMe: blockRelationship.blockedByMe,
          blockedMe: blockRelationship.blockedMe,
        })
      }
      if (!(await currentUserMayReferenceMessageMedia(pool, user, identity, {
        mediaUrl,
        contentType,
        allowedOwnedBuckets: ['chat-media'],
        isForwarded,
        forwardedFromMessageId,
      }))) {
        return res.status(403).json({ error: 'CHAT_MEDIA_REFERENCE_FORBIDDEN' })
      }
      if (thumbnailUrl && !(await currentUserOwnsStorageObject(
        pool,
        user.id,
        thumbnailUrl,
        ['thumbnails'],
      ))) {
        return res.status(403).json({ error: 'CHAT_THUMBNAIL_REFERENCE_FORBIDDEN' })
      }
      const senderId = identity.displayUserId
      const { rows } = await pool.query(
        `INSERT INTO fallback_messages(
            id,
            chat_id,
            sender_id,
            content,
            content_type,
            media_url,
            thumbnail_url,
            media_size,
            reply_to_id,
            is_forwarded,
            forwarded_from_message_id,
            forwarded_from_user_id
         )
         VALUES (COALESCE($1::uuid, gen_random_uuid()), $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
         ON CONFLICT (id) DO UPDATE SET
            chat_id = EXCLUDED.chat_id,
            sender_id = EXCLUDED.sender_id,
            content = EXCLUDED.content,
            content_type = EXCLUDED.content_type,
            media_url = EXCLUDED.media_url,
            thumbnail_url = EXCLUDED.thumbnail_url,
            media_size = EXCLUDED.media_size,
            reply_to_id = EXCLUDED.reply_to_id,
            is_forwarded = EXCLUDED.is_forwarded,
            forwarded_from_message_id = EXCLUDED.forwarded_from_message_id,
            forwarded_from_user_id = EXCLUDED.forwarded_from_user_id,
            status = 'sent',
            updated_at = NOW()
         RETURNING
            id::text,
            chat_id,
            sender_id,
            content,
            content_type,
            media_url,
            thumbnail_url,
            media_size,
            status,
            reply_to_id,
            poll_id,
            delivered_at,
            read_at,
            reactions,
            deleted_by,
            deleted_for_all,
            is_edited,
            is_forwarded,
            forwarded_from_message_id,
            forwarded_from_user_id,
            created_at,
            updated_at`,
        [
          requestedId,
          chatId,
          senderId,
          content,
          contentType,
          mediaUrl || null,
          thumbnailUrl || null,
          mediaSize,
          replyToId,
          isForwarded,
          forwardedFromMessageId,
          forwardedFromUserId,
        ],
      )
      const insertedMessage = normalizeMessageRow(rows[0])
      await pool.query(
        `DELETE FROM fallback_chat_hidden
         WHERE lower(chat_id::text) = $1
           AND lower(user_id) = $2`,
        [chatId, senderId],
      )
      await pool.query(
        `UPDATE fallback_chats
         SET last_message = $2,
             last_message_at = COALESCE($3::timestamptz, NOW()),
             last_message_sender_id = $4,
             last_message_id = $5,
             updated_at = NOW()
         WHERE lower(id::text) = $1
           AND (
             fallback_chats.last_message_at IS NULL
             OR fallback_chats.last_message_at <= COALESCE($3::timestamptz, NOW())
           )`,
        [
          chatId,
          previewFromMessage(insertedMessage),
          textOf(insertedMessage, ['created_at'], '') || null,
          senderId,
          textOf(insertedMessage, ['id'], ''),
        ],
      )
      const [importedChats, importedParticipants, fallbackChats, fallbackParticipants] = await Promise.all([
        importedRows(pool, 'chats', 5000),
        importedRows(pool, 'chat_participants', 10_000),
        localChats(pool, 5000),
        localChatParticipants(pool, [chatId]),
      ])
      const chat = [...importedChats, ...fallbackChats].find((row) => normalizeId(textOf(row, ['id'], '')) === chatId)
      const participants = [...importedParticipants, ...fallbackParticipants]
      const recipientIds = new Set([
        ...participants
          .filter((row) => normalizeId(textOf(row, ['chat_id'], '')) === chatId)
          .map((row) => normalizeId(textOf(row, ['user_id', 'profile_id'], ''))),
        ...arrayOf(chat || {}, ['participants']).map(normalizeId),
      ])
      const recipientList = Array.from(recipientIds).filter(
        (recipientId) => recipientId && !identity.ids.has(recipientId),
      )
      // El evento autoritativo debe adelantarse a FCM para evitar que un
      // placeholder parcial pise citas o que el tap abra un chat aún vacío.
      await publishChatMessageEvent({
        chatId,
        message: insertedMessage,
        recipientIds: recipientList,
      })
      await Promise.all(
        recipientList.map((recipientId) =>
          createLocalNotificationBestEffort(pool, {
            recipientId,
            actorId: identity.displayUserId,
            actorName: user.display_name || user.email || 'Usuario',
            actorAvatarUrl: user.avatar_url || '',
            type: 'message',
            targetId: chatId,
            targetType: 'chat',
            content: content || previewFromMessage({ content_type: contentType }),
            extraData: {
              chat_id: chatId,
              message_id: textOf(insertedMessage, ['id'], ''),
              content_type: textOf(insertedMessage, ['content_type'], 'text'),
              media_url: textOf(insertedMessage, ['media_url'], ''),
              thumbnail_url: textOf(insertedMessage, ['thumbnail_url'], ''),
              media_size: String(numberOf(insertedMessage, ['media_size'], 0)),
              reply_to_id: textOf(insertedMessage, ['reply_to_id'], ''),
              poll_id: textOf(insertedMessage, ['poll_id'], ''),
              is_forwarded: String(boolOf(insertedMessage, ['is_forwarded'], false)),
              forwarded_from_message_id: textOf(insertedMessage, ['forwarded_from_message_id'], ''),
              forwarded_from_user_id: textOf(insertedMessage, ['forwarded_from_user_id'], ''),
              created_at: textOf(insertedMessage, ['created_at'], ''),
            },
          }),
        ),
      )
      res.status(201).json({ message: insertedMessage })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/messages/chats/:chatId/messages/:messageId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const chatId = normalizeId(req.params.chatId)
      const messageId = normalizeId(req.params.messageId)
      const content = cleanText(req.body?.content, 4096)
      if (!chatId || !messageId) return res.status(400).json({ error: 'MESSAGE_ID_REQUIRED' })
      if (!content) return res.status(400).json({ error: 'MESSAGE_CONTENT_REQUIRED' })

      const { chat, participantRows, hasAccess } = await privateChatAccessContext(pool, chatId, identity)
      if (!hasAccess) return res.status(403).json({ error: 'CHAT_ACCESS_DENIED' })

      const existing = await getFallbackMessage(pool, chatId, messageId)
      if (!existing) return res.status(404).json({ error: 'MESSAGE_NOT_FOUND' })
      if (!rowMatchesAny(existing, ['sender_id'], identity.ids)) return res.status(403).json({ error: 'MESSAGE_EDIT_DENIED' })
      if (boolOf(existing, ['deleted_for_all'], false)) return res.status(409).json({ error: 'MESSAGE_DELETED' })
      if (textOf(existing, ['content_type'], 'text').toLowerCase() !== 'text') {
        return res.status(400).json({ error: 'ONLY_TEXT_MESSAGES_CAN_BE_EDITED' })
      }

      const { rows } = await pool.query(
        `UPDATE fallback_messages
         SET content = $3,
             is_edited = TRUE,
             updated_at = NOW()
         WHERE lower(chat_id) = $1 AND lower(id::text) = $2
         RETURNING
            id::text,
            chat_id,
            sender_id,
            content,
            content_type,
            media_url,
            media_size,
            status,
            reply_to_id,
            delivered_at,
            read_at,
            reactions,
            deleted_by,
            deleted_for_all,
            is_edited,
            is_forwarded,
            forwarded_from_message_id,
            forwarded_from_user_id,
            created_at,
            updated_at`,
        [chatId, messageId, content],
      )
      const updatedMessage = normalizeMessageRow(rows[0])
      await pool.query(
        `UPDATE fallback_chats
         SET last_message = $3,
             updated_at = NOW()
         WHERE lower(id::text) = $1 AND lower(COALESCE(last_message_id, '')) = $2`,
        [chatId, messageId, previewFromMessage(updatedMessage)],
      )

      void publishChatMessageEvent({
        chatId,
        message: updatedMessage,
        recipientIds: recipientIdsForChat(chat, participantRows, identity),
        action: 'updated',
      })
      res.json({ message: updatedMessage })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/chats/:chatId/messages/:messageId/delete', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const chatId = normalizeId(req.params.chatId)
      const messageId = normalizeId(req.params.messageId)
      const forEveryone = Boolean(req.body?.forEveryone || req.body?.for_everyone)
      if (!chatId || !messageId) return res.status(400).json({ error: 'MESSAGE_ID_REQUIRED' })

      const { chat, participantRows, hasAccess } = await privateChatAccessContext(pool, chatId, identity)
      if (!hasAccess) return res.status(403).json({ error: 'CHAT_ACCESS_DENIED' })

      const existing = await getFallbackMessage(pool, chatId, messageId)
      if (!existing) return res.status(404).json({ error: 'MESSAGE_NOT_FOUND' })
      const isSender = rowMatchesAny(existing, ['sender_id'], identity.ids)
      if (forEveryone && !isSender) return res.status(403).json({ error: 'MESSAGE_DELETE_FOR_ALL_DENIED' })

      let updatedMessage
      if (forEveryone) {
        const { rows } = await pool.query(
          `UPDATE fallback_messages
           SET deleted_for_all = TRUE,
               updated_at = NOW()
           WHERE lower(chat_id) = $1 AND lower(id::text) = $2
           RETURNING
              id::text,
              chat_id,
              sender_id,
              content,
              content_type,
              media_url,
              media_size,
              status,
              reply_to_id,
              delivered_at,
              read_at,
              reactions,
              deleted_by,
              deleted_for_all,
              is_edited,
              is_forwarded,
              forwarded_from_message_id,
              forwarded_from_user_id,
              created_at,
              updated_at`,
          [chatId, messageId],
        )
        updatedMessage = normalizeMessageRow(rows[0])
        await updateFallbackChatLastMessage(pool, chatId)
        void publishChatMessageEvent({
          chatId,
          message: updatedMessage,
          recipientIds: recipientIdsForChat(chat, participantRows, identity),
          action: 'deleted',
        })
      } else {
        const deletedBy = Array.from(new Set([
          ...arrayOf(existing, ['deleted_by', 'deletedBy']).map(normalizeId),
          identity.displayUserId,
        ].filter(Boolean)))
        const { rows } = await pool.query(
          `UPDATE fallback_messages
           SET deleted_by = $3::jsonb,
               updated_at = NOW()
           WHERE lower(chat_id) = $1 AND lower(id::text) = $2
           RETURNING
              id::text,
              chat_id,
              sender_id,
              content,
              content_type,
              media_url,
              media_size,
              status,
              reply_to_id,
              delivered_at,
              read_at,
              reactions,
              deleted_by,
              deleted_for_all,
              is_edited,
              is_forwarded,
              forwarded_from_message_id,
              forwarded_from_user_id,
              created_at,
              updated_at`,
          [chatId, messageId, JSON.stringify(deletedBy)],
        )
        updatedMessage = normalizeMessageRow(rows[0])
        void publishRealtimeEvent(`user:${identity.displayUserId}`, {
          event: 'message',
          action: 'deleted',
          chatId,
          message: updatedMessage,
          scope: 'inbox',
        })
      }

      res.json({ message: updatedMessage })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/chats/:chatId/messages/:messageId/reactions', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const chatId = normalizeId(req.params.chatId)
      const messageId = normalizeId(req.params.messageId)
      const emoji = String(req.body?.emoji || '').trim().slice(0, 16)
      if (!chatId || !messageId) return res.status(400).json({ error: 'MESSAGE_ID_REQUIRED' })
      if (!emoji) return res.status(400).json({ error: 'REACTION_REQUIRED' })

      const { chat, participantRows, hasAccess } = await privateChatAccessContext(pool, chatId, identity)
      if (!hasAccess) return res.status(403).json({ error: 'CHAT_ACCESS_DENIED' })

      const existing = await getFallbackMessage(pool, chatId, messageId)
      if (!existing || !messageVisibleTo(existing, identity)) return res.status(404).json({ error: 'MESSAGE_NOT_FOUND' })
      const nextReactions = toggleReactionMap(existing.reactions || {}, emoji, identity.displayUserId)
      const { rows } = await pool.query(
        `UPDATE fallback_messages
         SET reactions = $3::jsonb,
             updated_at = NOW()
         WHERE lower(chat_id) = $1 AND lower(id::text) = $2
         RETURNING
            id::text,
            chat_id,
            sender_id,
            content,
            content_type,
            media_url,
            media_size,
            status,
            reply_to_id,
            delivered_at,
            read_at,
            reactions,
            deleted_by,
            deleted_for_all,
            is_edited,
            is_forwarded,
            forwarded_from_message_id,
            forwarded_from_user_id,
            created_at,
            updated_at`,
        [chatId, messageId, JSON.stringify(nextReactions)],
      )
      const updatedMessage = normalizeMessageRow(rows[0])
      void publishChatMessageEvent({
        chatId,
        message: updatedMessage,
        recipientIds: recipientIdsForChat(chat, participantRows, identity),
        action: 'updated',
      })
      res.json({ message: updatedMessage })
    } catch (error) {
      next(error)
    }
  })
  app.post(['/v1/data/messages/groups/:roomId/update', '/v1/data/messages/communities/:roomId/update'], authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const config = collectionConfig(messageCollectionFromRequest(req))
      const identity = userIdentity(user)
      const roomId = normalizeId(req.params.roomId)
      const uuidRoomId = validUuid(roomId)
      if (!config || !roomId || !uuidRoomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const { name, description, is_public, only_admins_can_talk, daily_message_limit, image } = req.body

      const [importedRooms, localRooms, importedMembers, localMembers] = await Promise.all([
        importedRows(pool, config.roomTable, 5000),
        localCollectionRooms(pool, config.kind, 5000),
        importedRows(pool, config.memberTable, 10_000),
        localCollectionMembers(pool, config.kind, 10_000),
      ])
      const room = mergeRowsById([...importedRooms, ...localRooms])
        .find((row) => normalizeId(textOf(row, ['id'], '')) === roomId)
      if (!room) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })

      const members = deduplicateMembers([...importedMembers, ...localMembers], config.roomKey)
        .filter((row) => roomIdOf(row, config.roomKey) === roomId)
      const myMember = members.find((row) => identity.ids.has(memberUserId(row)))
      const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
      const isAdmin = isCollectionAdminRole(textOf(myMember || {}, ['role'], 'member'), { isCreator })
      if (!isAdmin) {
        return res.status(403).json({ error: 'ACCESS_DENIED' })
      }

      const has = (key) => Object.prototype.hasOwnProperty.call(req.body || {}, key)
      const nextName = cleanText(name, 120) || collectionRoomTitle(room, config.titleFallback)
      const nextDescription = has('description')
        ? cleanText(description, 500) || null
        : cleanText(textOf(room, ['description', 'about'], ''), 500) || null
      const nextImage = has('image')
        ? cleanText(image, 700) || null
        : collectionRoomAvatar(room) || null
      const nextIsPublic = has('is_public') || has('isPublic')
        ? Boolean(is_public ?? req.body?.isPublic)
        : boolOf(room, ['is_public', 'isPublic', 'public'], config.kind === 'community')
      const nextOnlyAdminsCanTalk = has('only_admins_can_talk') || has('onlyAdminsCanTalk')
        ? Boolean(only_admins_can_talk ?? req.body?.onlyAdminsCanTalk)
        : boolOf(room, ['only_admins_can_talk', 'onlyAdminsCanTalk'], false)
      const requestedDailyLimit = Number(daily_message_limit ?? req.body?.dailyMessageLimit)
      const nextDailyMessageLimit = config.kind === 'community' && (has('daily_message_limit') || has('dailyMessageLimit'))
        ? Math.max(0, Math.min(Number.isFinite(requestedDailyLimit) ? Math.trunc(requestedDailyLimit) : 0, 1000))
        : Math.max(0, Math.min(numberOf(room, ['daily_message_limit', 'dailyMessageLimit'], 0), 1000))
      const creatorId = normalizeId(textOf(room, ['creator_id', 'owner_id', 'user_id'], '')) || identity.displayUserId

      const { rows } = await pool.query(
        `INSERT INTO fallback_collection_rooms(
            id,
            room_kind,
            name,
            image,
            description,
            category,
            is_public,
            creator_id,
            is_deleted,
            only_admins_can_talk,
            daily_message_limit,
            created_at,
            updated_at
         )
         VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, FALSE, $9, $10, COALESCE($11::timestamptz, NOW()), NOW())
         ON CONFLICT (id) DO UPDATE SET
            room_kind = EXCLUDED.room_kind,
            name = EXCLUDED.name,
            image = EXCLUDED.image,
            description = EXCLUDED.description,
            category = EXCLUDED.category,
            is_public = EXCLUDED.is_public,
            creator_id = COALESCE(NULLIF(EXCLUDED.creator_id, ''), fallback_collection_rooms.creator_id),
            is_deleted = FALSE,
            only_admins_can_talk = EXCLUDED.only_admins_can_talk,
            daily_message_limit = EXCLUDED.daily_message_limit,
            updated_at = NOW()
         RETURNING id::text, room_kind, name, image, description, category, is_public, creator_id,
                   is_deleted, only_admins_can_talk, daily_message_limit, created_at, updated_at`,
        [
          uuidRoomId,
          config.kind,
          nextName,
          nextImage,
          nextDescription,
          config.kind === 'community' ? cleanText(textOf(room, ['category'], ''), 80) || null : null,
          nextIsPublic,
          creatorId,
          nextOnlyAdminsCanTalk,
          nextDailyMessageLimit,
          textOf(room, ['created_at', 'createdAt'], ''),
        ]
      )
      await pool.query(
        `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at, is_deleted)
         VALUES ($1, $2, $3, $4, NOW(), FALSE)
         ON CONFLICT (room_kind, room_id, user_id)
         DO UPDATE SET role = CASE
             WHEN fallback_collection_members.role IN ('owner', 'creator') THEN fallback_collection_members.role
             ELSE EXCLUDED.role
           END,
           is_deleted = FALSE`,
        [config.kind, roomId, identity.displayUserId, isCreator ? 'owner' : 'admin'],
      )
      res.json({ success: true, room: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })
}

async function processDueCommunityEventReminders(pool, { limit = 100 } = {}) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 100, 500))
  const client = await pool.connect()
  let dueRows = []
  try {
    await client.query('BEGIN')
    const { rows } = await client.query(
      `WITH due AS (
         SELECT a.event_id, a.user_id
         FROM fallback_community_event_attendees a
         JOIN fallback_community_events e ON e.id = a.event_id
         WHERE a.status = 'going'
           AND a.reminder_minutes IS NOT NULL
           AND a.reminder_sent_at IS NULL
           AND e.is_cancelled = FALSE
           AND e.starts_at > NOW()
           AND e.starts_at - (a.reminder_minutes * INTERVAL '1 minute') <= NOW()
         ORDER BY e.starts_at ASC
         FOR UPDATE OF a SKIP LOCKED
         LIMIT $1
       )
       UPDATE fallback_community_event_attendees a
       SET reminder_sent_at = NOW(), updated_at = NOW()
       FROM due
       WHERE a.event_id = due.event_id AND a.user_id = due.user_id
       RETURNING a.event_id::text, a.user_id, a.reminder_sent_at`,
      [safeLimit],
    )
    dueRows = rows.map(normalizeImportedRow)
    await client.query('COMMIT')
  } catch (error) {
    await client.query('ROLLBACK')
    throw error
  } finally {
    client.release()
  }

  for (const due of dueRows) {
    const eventResult = await pool.query(
      `SELECT id::text, community_id, title, starts_at
       FROM fallback_community_events
       WHERE id = $1::uuid
       LIMIT 1`,
      [due.event_id],
    )
    const event = normalizeImportedRow(eventResult.rows[0])
    if (!event?.id) continue
    await createLocalNotificationBestEffort(pool, {
      recipientId: due.user_id,
      type: 'community_event_reminder',
      targetId: event.id,
      targetType: 'community_event',
      content: `Próximamente: ${textOf(event, ['title'], 'evento de la comunidad')}`,
      extraData: {
        event_id: event.id,
        community_id: textOf(event, ['community_id'], ''),
        starts_at: textOf(event, ['starts_at'], ''),
        open_path: `/app/messages/community/${textOf(event, ['community_id'], '')}`,
      },
    })
  }
  return { processed: dueRows.length }
}

module.exports = {
  buildPublicSemanticSearchCorpus,
  buildProfileForUser,
  collectionRoleCapabilities,
  communityChannelPayload,
  communityEventPayload,
  communityMemberProfilePayload,
  createLocalNotificationBestEffort,
  currentUserMayUseReportEvidence,
  currentUserOwnsStorageObject,
  currentUserOwnsStorageObjects,
  currentUserMayReferenceMessageMedia,
  ensureLocalDataTables,
  feedPostVisible,
  invalidateImportedDataCache,
  normalizeFeedMode,
  normalizeCollectionRole,
  normalizeCommunityChannelType,
  normalizeSavedCollectionName,
  normalizeSavedTags,
  normalizeProfileSectionOrder,
  normalizeProfileSectionVisibility,
  parsePublicStorageObjectUrl,
  profileClearFields,
  profilePatchFromBody,
  rankPublicProfiles,
  sameStorageObjectReference,
  storageObjectExists,
  profileForViewer,
  processDueCommunityEventReminders,
  recommendationPreferenceScore,
  registerDataRoutes,
  userIdentity,
}
