function quoteIdentifier(value) {
  return `"${String(value).replace(/"/g, '""')}"`
}

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
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

      const myPosts = sortByDateDesc(
        posts.filter((row) => isOwnedBy(row, identity)),
        ['created_at', 'updated_at'],
      ).slice(0, 30)

      const myReports = sortByDateDesc(
        reports.filter((row) => rowMatchesAny(row, ['reporter_id', 'user_id'], identity.ids)),
        ['created_at', 'updated_at'],
      ).slice(0, 12)

      res.json({
        profile,
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

  app.get('/v1/data/feed', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 60, 200))
      const [profiles, importedPosts, fallbackPosts, likes, comments] = await Promise.all([
        importedRows(pool, 'profiles', 5000),
        importedRows(pool, 'posts', 5000),
        localPosts(pool, 5000),
        importedRows(pool, 'post_likes', 10_000),
        importedRows(pool, 'post_comments', 10_000),
      ])
      const posts = [...importedPosts, ...fallbackPosts]

      const profilesById = indexProfiles(profiles)
      const likeCounts = new Map()
      const commentCounts = new Map()
      const likedPostIds = new Set()

      for (const row of likes) {
        const postId = textOf(row, ['post_id'], '')
        if (!postId) continue
        likeCounts.set(postId, (likeCounts.get(postId) || 0) + 1)
        if (rowMatchesAny(row, ['user_id', 'profile_id'], identity.ids)) {
          likedPostIds.add(postId)
        }
      }

      for (const row of comments) {
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

  app.get('/v1/data/messages/inbox', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })

      const identity = userIdentity(user)
      const tab = String(req.query.tab || 'chats')

      if (tab === 'voice') {
        const rooms = sortByDateDesc(await importedRows(pool, 'voice_rooms', 1000), ['updated_at', 'created_at'])
        return res.json({
          items: rooms.slice(0, 40).map((row) => {
            const id = textOf(row, ['id'], '')
            return {
              id,
              title: textOf(row, ['name', 'title', 'readable_id'], 'Sala de voz'),
              subtitle: textOf(row, ['topic', 'description'], 'Sala disponible'),
              updatedAt: textOf(row, ['updated_at', 'created_at'], ''),
              kind: 'voice',
              avatarUrl: textOf(row, ['avatar_url', 'cover_url', 'image_url'], ''),
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
        const membershipTable = tab === 'groups' ? 'group_members' : 'community_members'
        const roomTable = tab === 'groups' ? 'groups' : 'communities'
        const roomKey = tab === 'groups' ? 'group_id' : 'community_id'
        const kind = tab === 'groups' ? 'group' : 'community'
        const prefix = tab === 'groups' ? '/app/messages/group/' : '/app/messages/community/'
        const [memberships, rooms] = await Promise.all([
          importedRows(pool, membershipTable, 5000),
          importedRows(pool, roomTable, 2000),
        ])
        const roomIds = cleanSet(
          memberships
            .filter((row) => rowMatchesAny(row, ['user_id', 'profile_id', 'member_id'], identity.ids))
            .map((row) => textOf(row, [roomKey, 'room_id'], '')),
        )
        const items = sortByDateDesc(
          rooms.filter((row) => roomIds.size === 0 || roomIds.has(normalizeId(textOf(row, ['id'], '')))),
          ['updated_at', 'created_at'],
        ).slice(0, 60).map((row) => {
          const id = textOf(row, ['id'], '')
          return {
            id,
            title: textOf(row, ['name', 'title', 'group_name', 'community_name'], tab === 'groups' ? 'Grupo' : 'Comunidad'),
            subtitle: textOf(row, ['description', 'about'], 'Sin actividad reciente'),
            updatedAt: textOf(row, ['updated_at', 'created_at'], ''),
            kind,
            avatarUrl: textOf(row, ['avatar_url', 'cover_url', 'image_url', 'group_image', 'community_image'], ''),
            profileId: null,
            unreadCount: 0,
            isPinned: false,
            isMuted: false,
            isArchived: false,
            isFavorite: false,
            folders: [],
            openPath: `${prefix}${id}`,
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
      if (!chatId || (!content && !mediaUrl)) {
        return res.status(400).json({ error: 'CHAT_ID_AND_CONTENT_OR_MEDIA_REQUIRED' })
      }

      const identity = userIdentity(user)
      const senderId = identity.displayUserId
      const { rows } = await pool.query(
        `INSERT INTO fallback_messages(chat_id, sender_id, content, content_type, media_url, media_size, reply_to_id)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         RETURNING id::text, chat_id, sender_id, content, content_type, media_url, media_size, status, reply_to_id, created_at, updated_at`,
        [chatId, senderId, content, contentType, mediaUrl || null, mediaSize, replyToId],
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
