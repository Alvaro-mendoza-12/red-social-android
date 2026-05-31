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

function profileName(row, fallback = 'Usuario') {
  return textOf(row, ['display_name', 'displayName', 'full_name', 'username', 'name', 'email'], fallback)
}

function profileAvatar(row) {
  return textOf(row, ['avatar_url', 'photo_url', 'profile_image', 'picture', 'image'], '')
}

function profileId(row) {
  return normalizeId(textOf(row, ['id', 'user_id', 'profile_id', 'supabase_user_id'], ''))
}

function indexProfiles(rows) {
  const byId = new Map()
  for (const row of rows) {
    for (const key of ['id', 'user_id', 'profile_id', 'supabase_user_id']) {
      const id = normalizeId(row[key])
      if (id && !byId.has(id)) byId.set(id, row)
    }
  }
  return byId
}

async function tableExists(pool, tableName) {
  const { rows } = await pool.query(
    `SELECT 1
     FROM information_schema.tables
     WHERE table_schema = 'supabase_import'
       AND table_name = $1
     LIMIT 1`,
    [tableName],
  )
  return Boolean(rows[0])
}

async function importedRows(pool, tableName, limit = 2000) {
  if (!(await tableExists(pool, tableName))) return []
  const safeLimit = Math.max(1, Math.min(Number(limit) || 2000, 10_000))
  const { rows } = await pool.query(
    `SELECT to_jsonb(t) AS row
     FROM supabase_import.${quoteIdentifier(tableName)} AS t
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map((entry) => normalizeImportedRow(entry.row))
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
        media_size,
        status,
        reply_to_id,
        created_at,
        updated_at
     FROM fallback_messages
     WHERE lower(chat_id) = ANY($1::text[])
     ORDER BY created_at ASC`,
    [ids],
  )
  return rows.map(normalizeImportedRow)
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
        updated_at,
        '{}'::jsonb AS reactions,
        '[]'::jsonb AS deleted_by,
        FALSE AS deleted_for_all,
        FALSE AS is_edited
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
        joined_at
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
  const { rows } = await pool.query(
    `SELECT
        id::text AS id,
        supabase_user_id::text AS supabase_user_id,
        email,
        display_name,
        display_name AS full_name,
        avatar_url,
        created_at,
        last_login_at AS updated_at
     FROM fallback_users
     ORDER BY created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
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
            last_login_at
     FROM fallback_users
     WHERE id = $1
     LIMIT 1`,
    [req.auth.sub],
  )
  return rows[0] || null
}

function userIdentity(user) {
  const ids = cleanSet([user.id, user.supabase_user_id])
  const displayUserId = normalizeId(user.supabase_user_id || user.id)
  return {
    ids,
    values: Array.from(ids),
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
    created_at: user.created_at,
    updated_at: user.last_login_at || user.created_at,
  }
}

function buildProfileForUser(user, importedProfile = {}, overrideProfile = {}) {
  const base = Object.keys(importedProfile || {}).length > 0 ? importedProfile : fallbackProfileRow(user)
  const merged = {
    ...base,
    ...overrideProfile,
  }
  return {
    ...merged,
    id: textOf(merged, ['id'], user.supabase_user_id || user.id),
    email: textOf(merged, ['email'], user.email),
    display_name: profileName(merged, user.display_name || user.email),
    full_name: textOf(merged, ['full_name'], profileName(merged, user.display_name || user.email)),
    avatar_url: profileAvatar(merged),
    created_at: textOf(merged, ['created_at'], user.created_at),
    updated_at: textOf(merged, ['updated_at'], new Date().toISOString()),
  }
}

function profilePatchFromBody(body) {
  const patch = {}

  const stringFields = [
    ['display_name', ['display_name', 'displayName', 'full_name', 'fullName']],
    ['bio', ['bio']],
    ['country', ['country']],
    ['city', ['city']],
    ['status', ['status']],
    ['avatar_url', ['avatar_url', 'avatarUrl']],
    ['cover_url', ['cover_url', 'coverUrl']],
    ['profile_visibility', ['profile_visibility', 'profileVisibility']],
    ['last_seen_visibility', ['last_seen_visibility', 'lastSeenVisibility']],
    ['online_status_visibility', ['online_status_visibility', 'onlineStatusVisibility']],
  ]

  for (const [targetKey, sourceKeys] of stringFields) {
    for (const sourceKey of sourceKeys) {
      if (Object.prototype.hasOwnProperty.call(body || {}, sourceKey)) {
        const value = cleanText(body[sourceKey], targetKey.endsWith('_url') ? 700 : 280)
        if (value) patch[targetKey] = value
        break
      }
    }
  }

  if (Object.prototype.hasOwnProperty.call(body || {}, 'is_private')) {
    patch.is_private = Boolean(body.is_private)
  } else if (Object.prototype.hasOwnProperty.call(body || {}, 'isPrivate')) {
    patch.is_private = Boolean(body.isPrivate)
  }

  if (patch.display_name && !patch.full_name) {
    patch.full_name = patch.display_name
  }

  return patch
}

function isOwnedBy(row, identity) {
  return rowMatchesAny(row, ['user_id', 'owner_id', 'author_id', 'creator_id', 'profile_id', 'id'], identity.ids)
}

function countRows(rows, predicate) {
  return rows.reduce((total, row) => total + (predicate(row) ? 1 : 0), 0)
}

function enrichPost(row, profilesById, likeCounts, commentCounts, likedPostIds) {
  const authorId = normalizeId(textOf(row, ['user_id', 'owner_id', 'author_id', 'creator_id'], ''))
  const profile = authorId ? profilesById.get(authorId) : null
  const postId = textOf(row, ['id'], '')

  return {
    ...row,
    user_id: authorId || textOf(row, ['user_id'], ''),
    user_name: textOf(row, ['user_name', 'display_name'], '') || profileName(profile, 'Usuario'),
    user_profile_image: textOf(row, ['user_profile_image', 'avatar_url'], '') || profileAvatar(profile),
    like_count: numberOf(row, ['like_count', 'likes_count'], likeCounts.get(postId) || 0),
    comment_count: numberOf(row, ['comment_count', 'comments_count'], commentCounts.get(postId) || 0),
    liked_by_me: likedPostIds.has(postId),
  }
}

function postIdOf(row) {
  return normalizeId(textOf(row, ['post_id', 'id'], ''))
}

function postOwnerId(row) {
  return normalizeId(textOf(row, ['user_id', 'owner_id', 'author_id', 'creator_id'], ''))
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

function enrichPostComment(row, profilesById) {
  const userId = normalizeId(textOf(row, ['user_id', 'profile_id', 'author_id'], ''))
  const profile = userId ? profilesById.get(userId) : null
  const createdAt = textOf(row, ['created_at', 'updated_at'], new Date().toISOString())

  return {
    ...row,
    id: textOf(row, ['id'], `${textOf(row, ['post_id'], 'post')}-${createdAt}-${userId}`),
    post_id: normalizeId(textOf(row, ['post_id'], '')),
    user_id: userId,
    user_name: textOf(row, ['user_name', 'display_name', 'author_name'], '') || profileName(profile, userId || 'Usuario'),
    user_avatar_url: textOf(row, ['user_avatar_url', 'avatar_url', 'user_profile_image'], '') || profileAvatar(profile),
    content: textOf(row, ['content', 'text', 'body'], ''),
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
  const relevantShares = [
    ...importedShares.filter((row) => postIdOf(row) === postId),
    ...localShares,
  ]

  const { counts: likeCounts, mine: likedPostIds } = countPostInteractions(relevantLikes, identity)
  const commentCounts = new Map([[postId, relevantComments.length]])
  const { counts: shareCounts, mine: sharedPostIds } = countPostInteractions(relevantShares, identity)
  const enrichedPost = enrichPost(post, profilesById, likeCounts, commentCounts, likedPostIds)

  return {
    post: {
      ...enrichedPost,
      share_count: numberOf(enrichedPost, ['share_count', 'shares_count'], shareCounts.get(postId) || 0),
      shared_by_me: sharedPostIds.has(postId),
    },
    comments: relevantComments
      .map((row) => enrichPostComment(row, profilesById))
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

function memberUserId(row) {
  return normalizeId(textOf(row, ['user_id', 'profile_id', 'member_id'], ''))
}

function roomIdOf(row, key) {
  return normalizeId(textOf(row, [key, 'room_id'], ''))
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

async function loadCollectionRoomPayload(pool, user, kind, roomId) {
  const config = collectionConfig(kind)
  const cleanRoomId = normalizeId(roomId)
  if (!config || !cleanRoomId) return null

  const identity = userIdentity(user)
  const [importedProfiles, localProfiles, importedRooms, importedMembers, importedMessages, localRooms, localMembers] = await Promise.all([
    importedRows(pool, 'profiles', 5000),
    fallbackUserProfileRows(pool, 5000),
    importedRows(pool, config.roomTable, 5000),
    importedRows(pool, config.memberTable, 10_000),
    importedRows(pool, config.messageTable, 10_000),
    localCollectionRooms(pool, config.kind, 5000),
    localCollectionMembers(pool, config.kind, 10_000),
  ])
  const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
  const rooms = [...importedRooms, ...localRooms]
  const members = [...importedMembers, ...localMembers]
  const room = rooms.find((row) => normalizeId(textOf(row, ['id'], '')) === cleanRoomId)
  if (!room) return null

  const roomMembers = members.filter((row) => roomIdOf(row, config.roomKey) === cleanRoomId)
  const isCreator = rowMatchesAny(room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
  const isMember = roomMembers.some((row) => identity.ids.has(memberUserId(row)))
  const isPublicCommunity = config.kind === 'community' && boolOf(room, ['is_public', 'public'], true)
  const hasAccess = isCreator || isMember || isPublicCommunity || roomMembers.length === 0
  if (!hasAccess) return null

  const importedRoomMessages = importedMessages.filter((row) => roomIdOf(row, config.roomKey) === cleanRoomId)
  const messages = [
    ...importedRoomMessages,
    ...(await localCollectionMessages(pool, config.kind, cleanRoomId)),
  ]
    .map((row) => enrichCollectionMessage(row, config, profilesById))
    .sort((a, b) => toMillis(textOf(a, ['created_at'], '')) - toMillis(textOf(b, ['created_at'], '')))

  return {
    chat: room,
    room,
    title: collectionRoomTitle(room, config.titleFallback),
    otherParticipant: null,
    participants: roomMembers.map((row) => {
      const id = memberUserId(row)
      const profile = id ? profilesById.get(id) : null
      return {
        id,
        name: textOf(row, ['user_name', 'display_name', 'name'], '') || profileName(profile, id || 'Usuario'),
        avatarUrl: textOf(row, ['user_avatar_url', 'avatar_url'], '') || profileAvatar(profile),
        role: textOf(row, ['role'], 'member'),
        joinedAt: textOf(row, ['joined_at', 'created_at'], ''),
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

function voiceParticipantRoomId(row) {
  return normalizeId(textOf(row, ['room_id', 'voice_room_id'], ''))
}

async function localVoiceParticipants(pool, roomId = '') {
  const cleanRoomId = normalizeId(roomId)
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
        joined_at,
        updated_at
     FROM fallback_voice_room_participants
     WHERE ($1::text = '' OR lower(room_id) = $1)
     ORDER BY joined_at ASC`,
    [cleanRoomId],
  )
  return rows.map(normalizeImportedRow)
}

async function localVoiceRooms(pool, limit = 1000) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 1000, 10_000))
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
        created_at,
        updated_at
     FROM fallback_voice_rooms
     WHERE is_active = TRUE
     ORDER BY updated_at DESC, created_at DESC
     LIMIT $1`,
    [safeLimit],
  )
  return rows.map(normalizeImportedRow)
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
    joined_at: textOf(row, ['joined_at', 'created_at'], ''),
  }
}

function mapVoiceRoom(row, profilesById, participantCount) {
  const creatorId = normalizeId(textOf(row, ['creator_id', 'owner_id', 'user_id'], ''))
  const creatorProfile = creatorId ? profilesById.get(creatorId) : null
  const id = voiceRoomId(row)
  const count = Math.max(
    0,
    participantCount,
    numberOf(row, ['current_participants', 'participant_count', 'active_participants'], 0),
  )
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
    updated_at: textOf(row, ['updated_at', 'created_at'], ''),
    created_at: textOf(row, ['created_at'], textOf(row, ['updated_at'], '')),
  }
}

async function ensureLocalDataTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_messages (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      chat_id TEXT NOT NULL,
      sender_id TEXT NOT NULL,
      content TEXT NOT NULL DEFAULT '',
      content_type TEXT NOT NULL DEFAULT 'text',
      media_url TEXT,
      media_size BIGINT NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'sent',
      reply_to_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(
    `ALTER TABLE fallback_messages
     ADD COLUMN IF NOT EXISTS media_size BIGINT NOT NULL DEFAULT 0`,
  )
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
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_posts_created
     ON fallback_posts(created_at DESC)`,
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
    CREATE TABLE IF NOT EXISTS fallback_room_messages (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      room_kind TEXT NOT NULL CHECK (room_kind IN ('group', 'community')),
      room_id TEXT NOT NULL,
      sender_id TEXT NOT NULL,
      content TEXT NOT NULL DEFAULT '',
      content_type TEXT NOT NULL DEFAULT 'text',
      media_url TEXT,
      media_size BIGINT NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'sent',
      reply_to_id TEXT,
      poll_id TEXT,
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
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS sender_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS content TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS content_type TEXT NOT NULL DEFAULT 'text'`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS media_url TEXT`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS media_size BIGINT NOT NULL DEFAULT 0`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'sent'`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS reply_to_id TEXT`,
    `ALTER TABLE fallback_room_messages ADD COLUMN IF NOT EXISTS poll_id TEXT`,
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
      PRIMARY KEY (room_kind, room_id, user_id)
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS room_kind TEXT NOT NULL DEFAULT 'group'`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS room_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'member'`,
    `ALTER TABLE fallback_collection_members ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_collection_members_user
     ON fallback_collection_members(room_kind, lower(user_id), lower(room_id))`,
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
}

function registerDataRoutes(app, { pool, authRequired }) {
  app.get('/v1/data/profile/me', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const [profiles, importedPosts, fallbackPosts, videos, chats, follows, reports] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        importedRows(pool, 'posts', 5000),
        localPosts(pool, 5000),
        importedRows(pool, 'videos', 5000),
        importedRows(pool, 'chats', 5000),
        importedRows(pool, 'follows', 5000),
        importedRows(pool, 'reports', 5000),
      ])
      const posts = [...importedPosts, ...fallbackPosts]

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

      const patch = profilePatchFromBody(req.body || {})
      if (Object.keys(patch).length === 0) {
        const current = buildProfileForUser(user, fallbackProfileRow(user), await localProfileOverride(pool, user.id))
        return res.json({ profile: current, stats: {} })
      }

      patch.updated_at = new Date().toISOString()

      await pool.query(
        `INSERT INTO fallback_profile_overrides(user_id, profile, updated_at)
         VALUES ($1, $2::jsonb, NOW())
         ON CONFLICT (user_id)
         DO UPDATE SET
           profile = fallback_profile_overrides.profile || EXCLUDED.profile,
           updated_at = NOW()`,
        [user.id, JSON.stringify(patch)],
      )

      const nextDisplayName = patch.display_name || patch.full_name || null
      const nextAvatarUrl = patch.avatar_url || null
      await pool.query(
        `UPDATE fallback_users
         SET display_name = COALESCE($2, display_name),
             avatar_url = COALESCE($3, avatar_url)
         WHERE id = $1`,
        [user.id, nextDisplayName, nextAvatarUrl],
      )

      const refreshedUser = { ...user, display_name: nextDisplayName || user.display_name, avatar_url: nextAvatarUrl || user.avatar_url }
      const current = buildProfileForUser(refreshedUser, fallbackProfileRow(refreshedUser), await localProfileOverride(pool, user.id))
      res.json({ profile: current, stats: {} })
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
      const [profiles, importedPosts, fallbackPosts, likes, reactions, comments] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        importedRows(pool, 'posts', 5000),
        localPosts(pool, 5000),
        importedRows(pool, 'post_likes', 10_000),
        importedRows(pool, 'post_reactions', 10_000),
        importedRows(pool, 'post_comments', 10_000),
      ])
      const posts = [...importedPosts, ...fallbackPosts]
      const postIds = posts.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const [fallbackLikes, fallbackComments] = await Promise.all([
        localPostReactions(pool, postIds, 10_000),
        localPostComments(pool, postIds, 10_000),
      ])

      const profilesById = indexProfiles(profiles)
      const likeCounts = new Map()
      const commentCounts = new Map()
      const likedPostIds = new Set()

      for (const row of [...likes, ...reactions, ...fallbackLikes]) {
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

      const items = sortByDateDesc(posts, ['created_at', 'updated_at'])
        .slice(0, limit)
        .map((row) => enrichPost(row, profilesById, likeCounts, commentCounts, likedPostIds))

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
      const mediaUrl = String(req.body?.mediaUrl || '').trim()
      const mediaType = String(req.body?.mediaType || '').trim() || null
      if (!content && !mediaUrl) {
        return res.status(400).json({ error: 'POST_CONTENT_OR_MEDIA_REQUIRED' })
      }

      const { rows } = await pool.query(
        `INSERT INTO fallback_posts(
            user_id,
            user_name,
            user_profile_image,
            content,
            media_url,
            media_urls,
            media_type
         )
         VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7)
         RETURNING
            id::text,
            user_id,
            user_name,
            user_profile_image,
            content,
            media_url,
            media_urls,
            media_type,
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
          JSON.stringify(mediaUrl ? [{ url: mediaUrl, media_type: mediaType || 'file' }] : []),
          mediaType,
        ],
      )

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
      const { rows } = await pool.query(
        `INSERT INTO fallback_post_comments(post_id, user_id, content, parent_id, media_url, media_type)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id::text, post_id, user_id, content, parent_id, media_url, media_type, created_at, updated_at`,
        [postId, identity.displayUserId, content, parentId, mediaUrl, mediaType],
      )
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      res.status(201).json({ comment: enrichPostComment(normalizeImportedRow(rows[0]), profilesById) })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/feed/comments/:commentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const commentId = validUuid(req.params.commentId)
      if (!commentId) return res.status(400).json({ error: 'COMMENT_ID_REQUIRED' })

      const { rowCount } = await pool.query(
        `UPDATE fallback_post_comments
         SET is_deleted = TRUE, updated_at = NOW()
         WHERE id = $1 AND lower(user_id) = $2`,
        [commentId, identity.displayUserId],
      )
      if (!rowCount) return res.status(404).json({ error: 'COMMENT_NOT_FOUND' })
      res.json({ ok: true })
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
      await pool.query(
        `INSERT INTO fallback_post_reactions(post_id, user_id, reaction_type, created_at, updated_at)
         VALUES ($1, $2, $3, NOW(), NOW())
         ON CONFLICT (post_id, user_id)
         DO UPDATE SET reaction_type = EXCLUDED.reaction_type, updated_at = NOW()`,
        [postId, identity.displayUserId, reactionType],
      )

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
        const [importedMemberships, importedRooms, importedMessages, localRooms, localMemberships] = await Promise.all([
          importedRows(pool, config.memberTable, 5000),
          importedRows(pool, config.roomTable, 2000),
          importedRows(pool, config.messageTable, 10_000),
          localCollectionRooms(pool, config.kind, 2000),
          localCollectionMembers(pool, config.kind, 10_000),
        ])
        const memberships = [...importedMemberships, ...localMemberships]
        const rooms = [...importedRooms, ...localRooms]
        const roomIds = cleanSet(
          memberships
            .filter((row) => rowMatchesAny(row, ['user_id', 'profile_id', 'member_id'], identity.ids))
            .map((row) => textOf(row, [config.roomKey, 'room_id'], '')),
        )
        const importedLatestByRoom = new Map()
        for (const message of sortByDateDesc(importedMessages, ['created_at', 'updated_at'])) {
          const roomId = roomIdOf(message, config.roomKey)
          if (roomId && !importedLatestByRoom.has(roomId)) importedLatestByRoom.set(roomId, message)
        }
        const localLatestRows = []
        for (const id of Array.from(roomIds)) {
          const localRows = await localCollectionMessages(pool, config.kind, id)
          if (localRows.length > 0) localLatestRows.push(localRows[localRows.length - 1])
        }
        const localLatestByRoom = new Map(localLatestRows.map((row) => [roomIdOf(row, config.roomKey), row]))
        const items = sortByDateDesc(
          rooms.filter((row) => roomIds.size === 0 || roomIds.has(normalizeId(textOf(row, ['id'], '')))),
          ['updated_at', 'created_at'],
        ).slice(0, 60).map((row) => {
          const id = textOf(row, ['id'], '')
          const cleanId = normalizeId(id)
          const importedLatest = importedLatestByRoom.get(cleanId)
          const localLatest = localLatestByRoom.get(cleanId)
          const latest = toMillis(textOf(localLatest || {}, ['created_at'], '')) > toMillis(textOf(importedLatest || {}, ['created_at'], ''))
            ? localLatest
            : importedLatest
          return {
            id,
            title: collectionRoomTitle(row, config.titleFallback),
            subtitle: previewFromMessage(latest || {}) || textOf(row, ['last_message', 'description', 'about'], 'Sin actividad reciente'),
            updatedAt: textOf(latest || {}, ['created_at', 'updated_at'], '') || textOf(row, ['last_message_at', 'updated_at', 'created_at'], ''),
            kind: config.kind,
            avatarUrl: collectionRoomAvatar(row),
            profileId: null,
            unreadCount: 0,
            isPinned: false,
            isMuted: false,
            isArchived: false,
            isFavorite: false,
            folders: [],
            openPath: `${config.openPathPrefix}${id}`,
          }
        })
        return res.json({ items })
      }

      const [profiles, chats, participants, importedMessages] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        importedRows(pool, 'chats', 5000),
        importedRows(pool, 'chat_participants', 10_000),
        importedRows(pool, 'messages', 10_000),
      ])

      const participantChatIds = cleanSet(
        participants
          .filter((row) => rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids))
          .map((row) => textOf(row, ['chat_id'], '')),
      )

      const chatRows = chats.filter((row) => {
        const chatId = normalizeId(textOf(row, ['id'], ''))
        const isGroup = boolOf(row, ['is_group'], false)
        return !isGroup && (rowParticipantsContain(row, identity.ids) || participantChatIds.has(chatId))
      })
      const chatIds = chatRows.map((row) => textOf(row, ['id'], '')).filter(Boolean)
      const messages = [
        ...importedMessages.filter((row) => cleanSet(chatIds).has(normalizeId(textOf(row, ['chat_id'], '')))),
        ...(await localMessages(pool, chatIds)),
      ]
      const profilesById = indexProfiles(profiles)

      const participantByChat = new Map()
      const otherByChat = new Map()
      for (const row of participants) {
        const chatId = normalizeId(textOf(row, ['chat_id'], ''))
        const userId = normalizeId(textOf(row, ['user_id', 'profile_id'], ''))
        if (!chatId || !userId) continue
        if (identity.ids.has(userId)) {
          participantByChat.set(chatId, row)
        } else if (!otherByChat.has(chatId)) {
          otherByChat.set(chatId, userId)
        }
      }

      const latestByChat = new Map()
      const unreadByChat = new Map()
      for (const message of sortByDateDesc(messages, ['created_at', 'updated_at'])) {
        const chatId = normalizeId(textOf(message, ['chat_id'], ''))
        if (!chatId) continue
        if (!latestByChat.has(chatId)) latestByChat.set(chatId, message)
        const senderId = normalizeId(textOf(message, ['sender_id'], ''))
        const readAt = textOf(message, ['read_at'], '')
        const status = textOf(message, ['status'], '').toLowerCase()
        if (senderId && !identity.ids.has(senderId) && !readAt && status !== 'read' && status !== 'seen') {
          unreadByChat.set(chatId, (unreadByChat.get(chatId) || 0) + 1)
        }
      }

      const items = sortByDateDesc(chatRows, ['last_message_at', 'updated_at', 'created_at'])
        .slice(0, 80)
        .map((row) => {
          const id = textOf(row, ['id'], '')
          const cleanChatId = normalizeId(id)
          let otherId = otherByChat.get(cleanChatId)
          if (!otherId) {
            otherId = arrayOf(row, ['participants']).map(normalizeId).find((entry) => !identity.ids.has(entry)) || ''
          }
          const otherProfile = otherId ? profilesById.get(otherId) : null
          const participant = participantByChat.get(cleanChatId) || {}
          const latest = latestByChat.get(cleanChatId) || {}
          const fallbackName = textOf(row, ['other_user_name', 'display_name', 'user_name', 'name'], 'Chat privado')
          return {
            id,
            title: otherProfile ? profileName(otherProfile, fallbackName) : fallbackName,
            subtitle: previewFromMessage(latest) || textOf(row, ['last_message'], 'Sin mensajes'),
            updatedAt:
              textOf(latest, ['created_at', 'updated_at'], '') ||
              textOf(row, ['last_message_at', 'updated_at', 'created_at'], ''),
            kind: 'chat',
            avatarUrl: otherProfile ? profileAvatar(otherProfile) : textOf(row, ['other_user_image', 'avatar_url'], ''),
            profileId: otherId || null,
            unreadCount: unreadByChat.get(cleanChatId) || 0,
            isPinned: boolOf(participant, ['is_pinned'], false),
            isMuted: boolOf(participant, ['is_muted'], false),
            isArchived: boolOf(participant, ['is_archived'], false),
            isFavorite: boolOf(participant, ['is_favorite'], false),
            folders: [],
            openPath: `/app/messages/${id}`,
          }
        })

      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/:collection(groups|communities)', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(req.params.collection)
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
        : true
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
            created_at,
            updated_at
         )
         VALUES (COALESCE($1::uuid, gen_random_uuid()), $2, $3, $4, $5, $6, $7, $8, FALSE, FALSE, NOW(), NOW())
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
          [config.kind, roomId, memberId, memberId === identity.displayUserId ? 'admin' : 'member'],
        )
      }

      res.status(201).json({ room: normalizeImportedRow(rows[0]), id: roomId })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/messages/:collection(groups|communities)/:roomId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const payload = await loadCollectionRoomPayload(pool, user, req.params.collection, req.params.roomId)
      if (!payload) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })
      res.json(payload)
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/:collection(groups|communities)/:roomId/join', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(req.params.collection)
      const roomId = normalizeId(req.params.roomId)
      if (!config || !roomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const identity = userIdentity(user)
      const targetUserId = normalizeId(req.body?.userId) || identity.displayUserId
      const role = cleanText(req.body?.role, 40) || 'member'
      const room = await loadCollectionRoomPayload(pool, user, config.kind, roomId)
      if (!room && config.kind === 'group') return res.status(404).json({ error: 'ROOM_NOT_FOUND' })

      await pool.query(
        `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at)
         VALUES ($1, $2, $3, $4, NOW())
         ON CONFLICT (room_kind, room_id, user_id)
         DO UPDATE SET role = COALESCE(NULLIF(fallback_collection_members.role, ''), EXCLUDED.role)`,
        [config.kind, roomId, targetUserId, role],
      )

      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/:collection(groups|communities)/:roomId/leave', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(req.params.collection)
      const roomId = normalizeId(req.params.roomId)
      if (!config || !roomId) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })

      const identity = userIdentity(user)
      await pool.query(
        `DELETE FROM fallback_collection_members
         WHERE room_kind = $1 AND lower(room_id) = $2 AND lower(user_id) = $3`,
        [config.kind, roomId, identity.displayUserId],
      )

      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/messages/:collection(groups|communities)/:roomId/messages', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const config = collectionConfig(req.params.collection)
      const roomId = normalizeId(req.params.roomId)
      const content = String(req.body?.content || '').trim()
      const contentType = String(req.body?.contentType || 'text').trim() || 'text'
      const mediaUrl = String(req.body?.mediaUrl || '').trim()
      const mediaSize = Math.max(0, Number(req.body?.mediaSize || 0))
      const replyToId = String(req.body?.replyToId || '').trim() || null
      const pollId = String(req.body?.pollId || '').trim() || null
      const isForwarded = Boolean(req.body?.isForwarded || req.body?.is_forwarded)
      const forwardedFromMessageId = String(req.body?.forwardedFromMessageId || req.body?.forwarded_from_message_id || '').trim() || null
      const forwardedFromUserId = String(req.body?.forwardedFromUserId || req.body?.forwarded_from_user_id || '').trim() || null
      const requestedId = validUuid(req.body?.id)
      if (!config || !roomId || (!content && !mediaUrl)) {
        return res.status(400).json({ error: 'ROOM_ID_AND_CONTENT_OR_MEDIA_REQUIRED' })
      }

      const existingRoom = await loadCollectionRoomPayload(pool, user, config.kind, roomId)
      if (!existingRoom) return res.status(404).json({ error: 'ROOM_NOT_FOUND' })

      const identity = userIdentity(user)
      const senderId = identity.displayUserId
      const { rows } = await pool.query(
        `INSERT INTO fallback_room_messages(
            id,
            room_kind,
            room_id,
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
         VALUES (COALESCE($1::uuid, gen_random_uuid()), $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
         ON CONFLICT (id) DO UPDATE SET
            room_kind = EXCLUDED.room_kind,
            room_id = EXCLUDED.room_id,
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
      res.status(201).json({ message: enrichCollectionMessage(normalizeImportedRow(rows[0]), config, profilesById) })
    } catch (error) {
      next(error)
    }
  })

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
            NOW(),
            NOW()
         )
         ON CONFLICT (id) DO UPDATE SET
            name = EXCLUDED.name,
            description = EXCLUDED.description,
            creator_id = EXCLUDED.creator_id,
            max_participants = EXCLUDED.max_participants,
            is_public = EXCLUDED.is_public,
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
        ],
      )

      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      res.status(201).json({ room: mapVoiceRoom(normalizeImportedRow(rows[0]), profilesById, 0) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/voice/rooms', authRequired, async (_req, res, next) => {
    try {
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
      const items = sortByDateDesc(
        rooms.map((row) => {
          const id = voiceRoomId(row)
          return mapVoiceRoom(row, profilesById, countByRoom.get(id) || 0)
        }),
        ['updated_at', 'created_at'],
      )
      res.json({ rooms: items, items })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/voice/rooms/:roomId', authRequired, async (req, res, next) => {
    try {
      const cleanRoomId = normalizeId(req.params.roomId)
      const [importedProfiles, localProfiles, importedRooms, importedParticipants, fallbackParticipants, fallbackRooms] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        fallbackUserProfileRows(pool, 5000),
        importedRows(pool, 'voice_rooms', 1000),
        importedRows(pool, 'voice_room_participants', 10_000),
        localVoiceParticipants(pool, cleanRoomId),
        localVoiceRooms(pool, 1000),
      ])
      const rooms = [...importedRooms, ...fallbackRooms]
      const profilesById = indexProfiles([...importedProfiles, ...localProfiles])
      const room = rooms.find((row) => voiceRoomId(row) === cleanRoomId)
      if (!room) return res.status(404).json({ error: 'VOICE_ROOM_NOT_FOUND' })
      const participants = [...importedParticipants, ...fallbackParticipants]
        .filter((row) => voiceParticipantRoomId(row) === cleanRoomId)
        .map((row) => mapVoiceParticipant(row, profilesById))
      res.json({
        room: mapVoiceRoom(room, profilesById, participants.length),
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
      const room = rooms.find((row) => voiceRoomId(row) === cleanRoomId)
      if (!room) return res.status(404).json({ error: 'VOICE_ROOM_NOT_FOUND' })

      const identity = userIdentity(user)
      await pool.query(
        `INSERT INTO fallback_voice_room_participants(
            room_id,
            user_id,
            user_name,
            user_avatar_url,
            socket_id,
            is_muted,
            is_speaking,
            joined_at,
            updated_at
         )
         VALUES ($1, $2, $3, $4, $5, $6, FALSE, NOW(), NOW())
         ON CONFLICT (room_id, user_id)
         DO UPDATE SET
            user_name = EXCLUDED.user_name,
            user_avatar_url = EXCLUDED.user_avatar_url,
            socket_id = EXCLUDED.socket_id,
            is_muted = EXCLUDED.is_muted,
            updated_at = NOW()`,
        [
          cleanRoomId,
          identity.displayUserId,
          String(req.body?.userName || user.display_name || user.email || 'Usuario').trim(),
          String(req.body?.userAvatarUrl || user.avatar_url || '').trim(),
          String(req.body?.socketId || '').trim(),
          Boolean(req.body?.isMuted),
        ],
      )
      const payload = await localVoiceParticipants(pool, cleanRoomId)
      const profilesById = indexProfiles([...(await importedRows(pool, 'profiles', 5000)), ...(await fallbackUserProfileRows(pool, 5000))])
      res.json({ participants: payload.map((row) => mapVoiceParticipant(row, profilesById)) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/voice/rooms/:roomId/leave', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      await pool.query(
        `DELETE FROM fallback_voice_room_participants
         WHERE lower(room_id) = $1 AND lower(user_id) = $2`,
        [normalizeId(req.params.roomId), identity.displayUserId],
      )
      res.json({ ok: true })
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
      const [profiles, chats, participants, importedMessages] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        importedRows(pool, 'chats', 5000),
        importedRows(pool, 'chat_participants', 10_000),
        importedRows(pool, 'messages', 10_000),
      ])

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

      const profilesById = indexProfiles(profiles)
      const otherProfile = otherId ? profilesById.get(otherId) : null
      const messages = [
        ...importedMessages.filter((row) => normalizeId(textOf(row, ['chat_id'], '')) === chatId),
        ...(await localMessages(pool, [chatId])),
      ].sort((a, b) => toMillis(textOf(a, ['created_at'], '')) - toMillis(textOf(b, ['created_at'], '')))

      const otherParticipantRow = memberRows.find((row) => {
        const id = normalizeId(textOf(row, ['user_id', 'profile_id'], ''))
        return id && !identity.ids.has(id)
      })
      if (messages.length === 0 && textOf(chat, ['last_message'], '')) {
        messages.push({
          id: textOf(chat, ['last_message_id'], '') || `summary-${chatId}`,
          chat_id: textOf(chat, ['id'], chatId),
          sender_id: textOf(chat, ['last_message_sender_id'], '') || otherId || '',
          content: textOf(chat, ['last_message'], ''),
          content_type: 'text',
          media_url: '',
          status: 'sent',
          reply_to_id: '',
          created_at: textOf(chat, ['last_message_at', 'updated_at', 'created_at'], ''),
          updated_at: textOf(chat, ['updated_at', 'last_message_at', 'created_at'], ''),
          reactions: {},
          deleted_by: [],
          deleted_for_all: false,
          is_edited: false,
          is_forwarded: false,
          read_at: '',
          delivered_at: '',
        })
      }

      res.json({
        chat,
        title: otherProfile ? profileName(otherProfile, 'Chat privado') : textOf(chat, ['name', 'group_name'], 'Chat privado'),
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
      const mediaSize = Math.max(0, Number(req.body?.mediaSize || 0))
      const replyToId = String(req.body?.replyToId || '').trim() || null
      const requestedId = validUuid(req.body?.id)
      if (!chatId || (!content && !mediaUrl)) {
        return res.status(400).json({ error: 'CHAT_ID_AND_CONTENT_OR_MEDIA_REQUIRED' })
      }

      const identity = userIdentity(user)
      const senderId = identity.displayUserId
      const { rows } = await pool.query(
        `INSERT INTO fallback_messages(id, chat_id, sender_id, content, content_type, media_url, media_size, reply_to_id)
         VALUES (COALESCE($1::uuid, gen_random_uuid()), $2, $3, $4, $5, $6, $7, $8)
         ON CONFLICT (id) DO UPDATE SET
            chat_id = EXCLUDED.chat_id,
            sender_id = EXCLUDED.sender_id,
            content = EXCLUDED.content,
            content_type = EXCLUDED.content_type,
            media_url = EXCLUDED.media_url,
            media_size = EXCLUDED.media_size,
            reply_to_id = EXCLUDED.reply_to_id,
            status = 'sent',
            updated_at = NOW()
         RETURNING id::text, chat_id, sender_id, content, content_type, media_url, media_size, status, reply_to_id, created_at, updated_at`,
        [requestedId, chatId, senderId, content, contentType, mediaUrl || null, mediaSize, replyToId],
      )
      res.status(201).json({ message: normalizeImportedRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  ensureLocalDataTables,
  registerDataRoutes,
}
