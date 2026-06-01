const crypto = require('crypto')

const MAX_READ_ROWS = Number(process.env.POSTGREST_COMPAT_MAX_READ_ROWS || 10000)
const RESERVED_QUERY_KEYS = new Set([
  'select',
  'order',
  'limit',
  'offset',
  'or',
  'and',
  'apikey',
])

function safeIdentifier(value) {
  const name = String(value || '').trim()
  return /^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(name) ? name : ''
}

function quoteIdentifier(value) {
  const safe = safeIdentifier(value)
  if (!safe) throw new Error('INVALID_IDENTIFIER')
  return `"${safe.replace(/"/g, '""')}"`
}

function normalizeTableName(value) {
  return safeIdentifier(String(value || '').trim().toLowerCase())
}

function normalizeId(value) {
  const id = String(value || '').trim()
  return id || crypto.randomUUID()
}

function nowIso() {
  return new Date().toISOString()
}

function asArrayBody(body) {
  if (Array.isArray(body)) return body
  if (body && typeof body === 'object') return [body]
  return [{}]
}

function rowId(row) {
  return String(
    row?.id ||
      row?.uuid ||
      row?.user_id ||
      row?.profile_id ||
      row?.chat_id ||
      row?.room_id ||
      row?.slug ||
      '',
  )
}

function compactRow(row) {
  if (!row || typeof row !== 'object') return {}
  return Object.fromEntries(Object.entries(row).filter(([, value]) => value !== undefined))
}

function mapUserRow(row) {
  return compactRow({
    id: row.supabase_user_id || row.id,
    local_id: row.id,
    email: row.email,
    display_name: row.display_name || row.email,
    username: row.display_name || row.email,
    full_name: row.display_name || row.email,
    avatar_url: row.avatar_url,
    created_at: row.created_at,
    updated_at: row.password_changed_by_user_at || row.local_password_set_at || row.created_at,
  })
}

async function ensurePostgrestCompatTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_compat_rows (
      table_name TEXT NOT NULL,
      row_id TEXT NOT NULL,
      row JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY(table_name, row_id)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_compat_rows_updated
    ON fallback_compat_rows(table_name, updated_at DESC)
  `)
}

async function tableExists(pool, schemaName, tableName) {
  const schema = safeIdentifier(schemaName)
  const table = safeIdentifier(tableName)
  if (!schema || !table) return false
  const { rows } = await pool.query('SELECT to_regclass($1) AS oid', [`${schema}.${table}`])
  return Boolean(rows[0]?.oid)
}

async function readImportedRows(pool, tableName) {
  if (!(await tableExists(pool, 'supabase_import', tableName))) return []
  const sql = `SELECT to_jsonb(t) AS row FROM ${quoteIdentifier('supabase_import')}.${quoteIdentifier(tableName)} t LIMIT $1`
  const { rows } = await pool.query(sql, [MAX_READ_ROWS])
  return rows.map((entry) => entry.row || {})
}

async function readCompatRows(pool, tableName) {
  const { rows } = await pool.query(
    `SELECT row
     FROM fallback_compat_rows
     WHERE table_name = $1
     ORDER BY updated_at DESC
     LIMIT $2`,
    [tableName, MAX_READ_ROWS],
  )
  return rows.map((entry) => entry.row || {})
}

async function readSpecificRows(pool, tableName) {
  switch (tableName) {
    case 'profiles':
    case 'users': {
      const { rows } = await pool.query(`
        SELECT id, email, display_name, avatar_url, supabase_user_id, created_at,
               password_changed_by_user_at, local_password_set_at
        FROM fallback_users
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows.map(mapUserRow)
    }
    case 'posts': {
      const { rows } = await pool.query(`
        SELECT id::text, user_id, user_name, user_profile_image, content, media_url,
               media_urls, media_type, visibility, created_at, updated_at
        FROM fallback_posts
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'post_comments': {
      const { rows } = await pool.query(`
        SELECT id::text, post_id, user_id, content, parent_id, media_url, media_type,
               is_deleted, created_at, updated_at
        FROM fallback_post_comments
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'post_reactions':
    case 'post_likes': {
      const { rows } = await pool.query(`
        SELECT (post_id::text || ':' || user_id::text) AS id,
               post_id::text, user_id::text, reaction_type, created_at
        FROM fallback_post_reactions
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'shares': {
      const { rows } = await pool.query(`
        SELECT id::text, user_id, post_id, post_id AS content_id, content_type, created_at
        FROM fallback_shares
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'follows': {
      const { rows } = await pool.query(`
        SELECT (follower_id::text || ':' || following_id::text) AS id,
               follower_id::text, following_id::text, created_at
        FROM fallback_follows
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'reports': {
      const { rows } = await pool.query(`
        SELECT id::text, reporter_id, reporter_name, reported_user_id, reported_user_name,
               reason, description, description AS details, evidence_url, status, created_at
        FROM fallback_reports
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'notifications': {
      const { rows } = await pool.query(`
        SELECT id::text, recipient_id AS user_id, actor_id, actor_name, actor_avatar_url,
               type, target_id, target_type, content AS title, content AS body,
               extra_data AS data, is_read,
               CASE WHEN is_read THEN updated_at ELSE NULL END AS read_at,
               created_at, updated_at
        FROM fallback_notifications
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'messages': {
      const { rows } = await pool.query(`
        SELECT id::text, chat_id, sender_id, content, content_type AS message_type,
               content_type, media_url, media_size, status, reply_to_id,
               '{}'::jsonb AS metadata, created_at, updated_at
        FROM fallback_messages
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'group_messages':
    case 'community_messages': {
      const collectionType = tableName === 'group_messages' ? 'group' : 'community'
      const idColumn = collectionType === 'group' ? 'group_id' : 'community_id'
      const { rows } = await pool.query(`
        SELECT id::text, room_id AS ${idColumn}, sender_id, content,
               content_type AS message_type, content_type, media_url, media_size,
               status, reply_to_id, poll_id, is_forwarded,
               forwarded_from_message_id, forwarded_from_user_id,
               '{}'::jsonb AS metadata, created_at, updated_at
        FROM fallback_room_messages
        WHERE room_kind = $1
        ORDER BY created_at DESC
        LIMIT $2
      `, [collectionType, MAX_READ_ROWS])
      return rows
    }
    case 'groups':
    case 'communities': {
      const collectionType = tableName === 'groups' ? 'group' : 'community'
      const { rows } = await pool.query(`
        SELECT id::text, creator_id AS owner_id, creator_id, name, description,
               image AS avatar_url, image, category, is_public, only_admins_can_talk,
               '{}'::jsonb AS metadata, created_at, updated_at
        FROM fallback_collection_rooms
        WHERE room_kind = $1 AND is_deleted = FALSE
        ORDER BY updated_at DESC
        LIMIT $2
      `, [collectionType, MAX_READ_ROWS])
      return rows
    }
    case 'group_members':
    case 'community_members': {
      const collectionType = tableName === 'group_members' ? 'group' : 'community'
      const idColumn = collectionType === 'group' ? 'group_id' : 'community_id'
      const { rows } = await pool.query(`
        SELECT (room_id || ':' || user_id) AS id,
               room_id AS ${idColumn}, user_id, role, joined_at AS created_at
        FROM fallback_collection_members
        WHERE room_kind = $1
        ORDER BY joined_at DESC
        LIMIT $2
      `, [collectionType, MAX_READ_ROWS])
      return rows
    }
    case 'voice_rooms': {
      const { rows } = await pool.query(`
        SELECT id::text, creator_id AS owner_id, creator_id, name AS title, name,
               description AS topic, description, max_participants, is_public,
               is_active, agora_channel_name, readable_id,
               '{}'::jsonb AS metadata, created_at, updated_at
        FROM fallback_voice_rooms
        ORDER BY updated_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'voice_room_participants': {
      const { rows } = await pool.query(`
        SELECT (room_id || ':' || user_id) AS id,
               room_id, user_id, user_name, user_avatar_url, socket_id,
               is_muted, is_speaking, joined_at, updated_at,
               NULL::timestamptz AS left_at
        FROM fallback_voice_room_participants
        ORDER BY joined_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    default:
      return []
  }
}

function mergeRows(groups) {
  const byId = new Map()
  const noId = []
  for (const row of groups.flat()) {
    const normalized = compactRow(row)
    const id = rowId(normalized)
    if (!id) {
      noId.push(normalized)
      continue
    }
    byId.set(id, { ...(byId.get(id) || {}), ...normalized })
  }
  return [...byId.values(), ...noId]
}

function parseFilterValue(raw) {
  const value = String(raw || '')
  const firstDot = value.indexOf('.')
  if (firstDot === -1) return { op: 'eq', value }
  return {
    op: value.slice(0, firstDot),
    value: value.slice(firstDot + 1),
  }
}

function coerceComparable(value) {
  if (value === null || value === undefined) return null
  return String(value)
}

function valueMatches(actual, op, expected) {
  const actualValue = coerceComparable(actual)
  const expectedValue = String(expected || '')
  switch (op) {
    case 'eq':
      return actualValue === expectedValue
    case 'neq':
      return actualValue !== expectedValue
    case 'is':
      return expectedValue === 'null' ? actual === null || actual === undefined : actualValue === expectedValue
    case 'not':
      return expectedValue === 'is.null' ? actual !== null && actual !== undefined : actualValue !== expectedValue
    case 'in': {
      const list = expectedValue.replace(/^\(/, '').replace(/\)$/, '').split(',').map((item) => item.trim())
      return list.includes(actualValue)
    }
    case 'like':
    case 'ilike': {
      const haystack = op === 'ilike' ? String(actualValue || '').toLowerCase() : String(actualValue || '')
      const needle = op === 'ilike' ? expectedValue.toLowerCase() : expectedValue
      const pattern = needle.replace(/%|\*/g, '')
      return haystack.includes(pattern)
    }
    case 'gt':
      return Number(actualValue) > Number(expectedValue)
    case 'gte':
      return Number(actualValue) >= Number(expectedValue)
    case 'lt':
      return Number(actualValue) < Number(expectedValue)
    case 'lte':
      return Number(actualValue) <= Number(expectedValue)
    default:
      return actualValue === expectedValue
  }
}

function matchesOrClause(row, value) {
  const raw = String(value || '').trim().replace(/^\(/, '').replace(/\)$/, '')
  if (!raw) return true
  return raw.split(',').some((part) => {
    const pieces = part.split('.')
    if (pieces.length < 3) return true
    const [field, op, ...rest] = pieces
    return valueMatches(row[field], op, rest.join('.'))
  })
}

function applyFilters(rows, query) {
  return rows.filter((row) => {
    for (const [key, rawValue] of Object.entries(query || {})) {
      if (RESERVED_QUERY_KEYS.has(key)) continue
      const values = Array.isArray(rawValue) ? rawValue : [rawValue]
      const ok = values.every((entry) => {
        const { op, value } = parseFilterValue(entry)
        return valueMatches(row[key], op, value)
      })
      if (!ok) return false
    }
    if (query?.or && !matchesOrClause(row, query.or)) return false
    return true
  })
}

function applyOrder(rows, order) {
  const raw = String(order || '').trim()
  if (!raw) return rows
  const [field, direction] = raw.split('.')
  if (!field) return rows
  const descending = String(direction || '').toLowerCase().startsWith('desc')
  return [...rows].sort((a, b) => {
    const av = a[field]
    const bv = b[field]
    if (av === bv) return 0
    if (av === null || av === undefined) return 1
    if (bv === null || bv === undefined) return -1
    return (String(av) > String(bv) ? 1 : -1) * (descending ? -1 : 1)
  })
}

function applyRange(rows, query) {
  const offset = Math.max(0, Number(query?.offset || 0) || 0)
  const limit = Math.min(MAX_READ_ROWS, Math.max(0, Number(query?.limit || rows.length) || rows.length))
  return rows.slice(offset, offset + limit)
}

function wantsSingleObject(req) {
  return String(req.headers.accept || '').includes('vnd.pgrst.object+json')
}

function sendPostgrestRows(req, res, rows, status = 200, headOnly = false) {
  const total = rows.length
  const offset = Math.max(0, Number(req.query?.offset || 0) || 0)
  const ranged = applyRange(rows, req.query)
  const end = ranged.length > 0 ? offset + ranged.length - 1 : 0
  res.setHeader('Range-Unit', 'items')
  res.setHeader('Content-Range', `${ranged.length > 0 ? offset : 0}-${end}/${total}`)
  res.status(status)
  if (headOnly) return res.end()
  if (wantsSingleObject(req)) {
    return res.json(ranged[0] || null)
  }
  return res.json(ranged)
}

async function readTableRows(pool, tableName, query) {
  const [importedRows, specificRows, compatRows] = await Promise.all([
    readImportedRows(pool, tableName),
    readSpecificRows(pool, tableName),
    readCompatRows(pool, tableName),
  ])
  const merged = mergeRows([importedRows, specificRows, compatRows])
  const filtered = applyFilters(merged, query)
  return applyOrder(filtered, query?.order)
}

async function storeCompatRow(pool, tableName, row) {
  const cleaned = compactRow(row)
  const id = normalizeId(rowId(cleaned))
  const stored = {
    ...cleaned,
    id: cleaned.id || id,
    created_at: cleaned.created_at || nowIso(),
    updated_at: nowIso(),
  }
  await pool.query(
    `INSERT INTO fallback_compat_rows(table_name, row_id, row)
     VALUES ($1, $2, $3)
     ON CONFLICT (table_name, row_id)
     DO UPDATE SET row = fallback_compat_rows.row || EXCLUDED.row,
                   updated_at = NOW()`,
    [tableName, id, stored],
  )
  return stored
}

async function upsertProfile(pool, auth, row) {
  const id = row.id || auth?.sub
  const displayName = row.display_name || row.username || row.full_name || row.name || row.email
  const avatarUrl = row.avatar_url || row.avatarUrl
  if (auth?.sub) {
    await pool.query(
      `UPDATE fallback_users
       SET display_name = COALESCE(NULLIF($2, ''), display_name),
           avatar_url = COALESCE(NULLIF($3, ''), avatar_url)
       WHERE id = $1`,
      [auth.sub, displayName || '', avatarUrl || ''],
    )
  }
  return storeCompatRow(pool, 'profiles', {
    ...row,
    id: id || row.id,
    display_name: displayName || row.display_name,
    avatar_url: avatarUrl || row.avatar_url,
  })
}

async function handleInsert(pool, tableName, auth, body) {
  const rows = []
  for (const entry of asArrayBody(body)) {
    const base = compactRow(entry)
    if (tableName === 'profiles' || tableName === 'users') {
      rows.push(await upsertProfile(pool, auth, base))
    } else {
      rows.push(await storeCompatRow(pool, tableName, base))
    }
  }
  return rows
}

function extractIdFilter(query) {
  for (const field of ['id', 'user_id', 'profile_id', 'chat_id', 'post_id']) {
    const raw = query?.[field]
    if (!raw) continue
    const { op, value } = parseFilterValue(Array.isArray(raw) ? raw[0] : raw)
    if (op === 'eq' && value) return { field, value }
  }
  return null
}

async function handlePatch(pool, tableName, auth, query, body) {
  const currentRows = await readTableRows(pool, tableName, query)
  const patch = compactRow(body || {})
  const targets = currentRows.length > 0 ? currentRows : []
  const idFilter = extractIdFilter(query)
  if (targets.length === 0 && idFilter) {
    targets.push({ id: idFilter.value, [idFilter.field]: idFilter.value })
  }

  const rows = []
  for (const current of targets) {
    const next = { ...current, ...patch, updated_at: nowIso() }
    if (tableName === 'profiles' || tableName === 'users') {
      rows.push(await upsertProfile(pool, auth, next))
    } else {
      rows.push(await storeCompatRow(pool, tableName, next))
    }
  }
  return rows
}

async function handleDelete(pool, tableName, query) {
  const rows = await readTableRows(pool, tableName, query)
  for (const row of rows) {
    const id = rowId(row)
    if (!id) continue
    await pool.query(
      `DELETE FROM fallback_compat_rows
       WHERE table_name = $1 AND row_id = $2`,
      [tableName, id],
    )
  }
  return rows
}

async function handleRpc(pool, req, rpcName) {
  const body = req.body || {}
  const auth = req.auth || null

  switch (rpcName) {
    case 'resolve_login_email': {
      const identifier = String(body.identifier || body.email || body.username || '').trim().toLowerCase()
      const { rows } = await pool.query(
        `SELECT email FROM fallback_users
         WHERE lower(email) = $1 OR lower(display_name) = $1
         LIMIT 1`,
        [identifier],
      )
      return rows[0]?.email || identifier
    }
    case 'secure_get_my_suspension_state':
      return { is_suspended: false, suspended: false, reason: null, until: null }
    case 'secure_send_message': {
      const chatId = body.chat_id || body.chatId || crypto.randomUUID()
      const content = String(body.content || body.message || body.text || '').trim()
      const mediaUrl = body.media_url || body.mediaUrl || null
      const messageType = body.message_type || body.messageType || (mediaUrl ? 'media' : 'text')
      const { rows } = await pool.query(
        `INSERT INTO fallback_messages(chat_id, sender_id, content, content_type, media_url, media_size, reply_to_id)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         RETURNING id::text, chat_id, sender_id, content, content_type AS message_type, media_url, media_size, reply_to_id, created_at, updated_at`,
        [chatId, auth?.sub || '', content, messageType, mediaUrl, Number(body.media_size || body.mediaSize || 0) || 0, body.reply_to_id || body.replyToId || null],
      )
      return rows[0]
    }
    case 'secure_mark_messages_read':
    case 'secure_mark_messages_delivered':
      return { ok: true }
    case 'secure_submit_report':
    case 'secure_submit_video_report': {
      const tableName = rpcName === 'secure_submit_video_report' ? 'video_reports' : 'reports'
      return storeCompatRow(pool, tableName, {
        ...body,
        id: crypto.randomUUID(),
        reporter_id: body.reporter_id || auth?.sub || null,
        status: 'pending',
      })
    }
    case 'wallet_get_or_create': {
      const userId = body.user_id || auth?.sub || crypto.randomUUID()
      const wallet = { id: userId, user_id: userId, balance: 0, created_at: nowIso(), updated_at: nowIso() }
      await storeCompatRow(pool, 'wallets', wallet)
      return wallet
    }
    case 'secure_get_poll_state':
      return { poll: null, options: [], votes: [] }
    case 'secure_vote_poll_option':
    case 'secure_toggle_message_reaction':
    case 'secure_set_chat_preferences':
    case 'secure_set_chat_translation_settings':
    case 'secure_schedule_message_job':
    case 'send_virtual_gift':
    case 'subscribe_to_plan':
    case 'increment_post_count':
    case 'increment_video_views':
    case 'increment_like_count':
    case 'increment_share_count':
    case 'register_media_object':
      return { ok: true }
    case 'secure_search_stickers':
      return []
    default:
      return { ok: true, rpc: rpcName }
  }
}

function requireWriteAuth(req, res) {
  if (req.auth?.sub) return true
  res.status(401).json({ error: 'AUTH_REQUIRED' })
  return false
}

function registerPostgrestCompatRoutes(app, { pool, authOptional }) {
  const tableRoute = /^\/rest\/v1\/([A-Za-z_][A-Za-z0-9_]*)$/
  const rpcRoute = /^\/rest\/v1\/rpc\/([A-Za-z_][A-Za-z0-9_]*)$/

  app.post(rpcRoute, authOptional, async (req, res, next) => {
    try {
      const rpcName = safeIdentifier(req.params[0])
      if (!rpcName) return res.status(404).json({ error: 'NOT_FOUND' })
      const result = await handleRpc(pool, req, rpcName)
      res.json(result)
    } catch (error) {
      next(error)
    }
  })

  app.get(tableRoute, authOptional, async (req, res, next) => {
    try {
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).json({ error: 'NOT_FOUND' })
      const rows = await readTableRows(pool, tableName, req.query)
      sendPostgrestRows(req, res, rows)
    } catch (error) {
      next(error)
    }
  })

  app.head(tableRoute, authOptional, async (req, res, next) => {
    try {
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).end()
      const rows = await readTableRows(pool, tableName, req.query)
      sendPostgrestRows(req, res, rows, 200, true)
    } catch (error) {
      next(error)
    }
  })

  app.post(tableRoute, authOptional, async (req, res, next) => {
    try {
      if (!requireWriteAuth(req, res)) return
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).json({ error: 'NOT_FOUND' })
      const rows = await handleInsert(pool, tableName, req.auth, req.body)
      sendPostgrestRows(req, res, rows, 201)
    } catch (error) {
      next(error)
    }
  })

  app.patch(tableRoute, authOptional, async (req, res, next) => {
    try {
      if (!requireWriteAuth(req, res)) return
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).json({ error: 'NOT_FOUND' })
      const rows = await handlePatch(pool, tableName, req.auth, req.query, req.body)
      sendPostgrestRows(req, res, rows)
    } catch (error) {
      next(error)
    }
  })

  app.delete(tableRoute, authOptional, async (req, res, next) => {
    try {
      if (!requireWriteAuth(req, res)) return
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).json({ error: 'NOT_FOUND' })
      const rows = await handleDelete(pool, tableName, req.query)
      sendPostgrestRows(req, res, rows)
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  ensurePostgrestCompatTables,
  registerPostgrestCompatRoutes,
}
