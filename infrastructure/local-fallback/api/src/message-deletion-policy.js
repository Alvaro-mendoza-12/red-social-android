'use strict'

const clean = value => String(value ?? '').trim().toLowerCase()
const enabled = value => value === true || clean(value) === 'true'
const messageTypes = new Set(['message', 'new_message', 'direct_message', 'group_message', 'community_message', 'message_deleted'])
const messageTables = new Set(['messages', 'group_messages', 'community_messages'])

function isDeletedForAll(row) {
  return enabled(row?.deleted_for_all) || enabled(row?.deletedForAll) || ['deleted', 'recalled'].includes(clean(row?.status))
}

// Preserve only identifiers and state. Legacy aliases, metadata and embedded
// quote/poll/contact objects must never retain a deleted message's payload.
function redactDeletedMessage(row) {
  if (!row || !isDeletedForAll(row)) return row
  const result = {}
  for (const key of ['id', 'chat_id', 'room_id', 'room_kind', 'group_id', 'community_id', 'channel_id',
    'sender_id', 'sender_name', 'sender_image', 'created_at', 'updated_at', 'deleted_by', 'delivered_at', 'read_at']) {
    if (Object.hasOwn(row, key)) result[key] = row[key]
  }
  return { ...result, deleted_for_all: true, status: 'deleted', content: '', content_type: 'text',
    message_type: 'text', media_url: null, thumbnail_url: null, media_size: 0,
    reply_to_id: null, poll_id: null, reactions: {}, is_edited: false, is_forwarded: false,
    forwarded_from_message_id: null, forwarded_from_user_id: null }
}

function mergeMessageRows(rows) {
  const map = new Map(), withoutId = []
  for (const row of rows) {
    const id = clean(row?.id)
    if (!id) { withoutId.push(redactDeletedMessage(row)); continue }
    const previous = map.get(id)
    const deleted = isDeletedForAll(previous) || isDeletedForAll(row)
    map.set(id, redactDeletedMessage({ ...previous, ...row, ...(deleted ? { deleted_for_all: true } : {}) }))
  }
  return [...map.values(), ...withoutId]
}

function messageScope({ chatId, message } = {}) {
  const room = /^room:(group|community):(.+)$/.exec(clean(chatId))
  const kind = room?.[1] || (message?.community_id ? 'community' : message?.group_id ? 'group' : 'chat')
  const scopeId = room?.[2] || clean(chatId || message?.[`${kind}_id`] || message?.room_id)
  const messageId = clean(message?.id)
  return scopeId && messageId ? { kind, scopeId, messageId } : null
}

function notificationMessageScope(notification, extraData = {}, { strict = true } = {}) {
  const type = clean(notification?.type)
  const requestedKind = clean(notification?.target_type || extraData.target_type)
  if (!messageTypes.has(type) && !messageTypes.has(clean(extraData.notification_type || extraData.type)) &&
      !(extraData.message_id && ['chat','dm','direct','group','community'].includes(requestedKind) && !type.startsWith('call_'))) return undefined
  const kind = ['group', 'community'].includes(requestedKind) ? requestedKind : 'chat'
  const scopeId = clean(notification?.target_id || extraData[`${kind}_id`] || extraData.chat_id)
  const messageId = clean(extraData.message_id)
  const suppliedScope = clean(extraData[`${kind}_id`])
  if (!scopeId || !messageId || (strict && suppliedScope && suppliedScope !== scopeId)) return null
  return { kind, scopeId, messageId }
}

function extraOf(row) {
  const value = row?.extra_data || row?.extraData || row?.data || {}
  if (typeof value !== 'string') return value && typeof value === 'object' ? value : {}
  try { const parsed=JSON.parse(value); return parsed && typeof parsed === 'object' ? parsed : {} } catch { return {} }
}

function scopeKey(scope) { return JSON.stringify([scope.kind,scope.scopeId,scope.messageId]) }

// Read only scopes/IDs and deletion state in a bounded batch. Live notifications
// and unrelated types remain intact, including when no matching message exists.
async function filterDeletedMessageNotifications(db, rows) {
  const scopes=new Map(), rowScopes=new Map()
  for (const row of rows) {
    const extra=extraOf(row)
    const scope=notificationMessageScope({ ...row, type:row.type || row.notification_type,
      target_id:row.target_id || row.targetId, target_type:row.target_type || row.targetType },
    { ...extra, message_id:extra.message_id || extra.messageId || row.message_id }, { strict:false })
    if (!scope) continue
    const key=scopeKey(scope); scopes.set(key,scope); rowScopes.set(row,key)
  }
  if (!scopes.size) return rows
  const requested=[...scopes.values()].map(scope=>({ kind:scope.kind,scope_id:scope.scopeId,message_id:scope.messageId }))
  const imported=await db.query(`SELECT table_name FROM information_schema.tables
    WHERE table_schema='supabase_import' AND table_name=ANY($1::text[])`,
  [[...new Set(requested.map(scope=>scope.kind==='chat'?'messages':`${scope.kind}_messages`))]])
  const queries=[
    `SELECT q.kind,q.scope_id,q.message_id FROM requested q JOIN fallback_messages m
      ON q.kind='chat' AND lower(m.chat_id)=q.scope_id AND lower(m.id::text)=q.message_id
      WHERE m.deleted_for_all=TRUE OR lower(m.status) IN ('deleted','recalled')`,
    `SELECT q.kind,q.scope_id,q.message_id FROM requested q JOIN fallback_room_messages m
      ON q.kind=m.room_kind AND lower(m.room_id)=q.scope_id AND lower(m.id::text)=q.message_id
      WHERE m.deleted_for_all=TRUE OR lower(m.status) IN ('deleted','recalled')`,
    `SELECT q.kind,q.scope_id,q.message_id FROM requested q JOIN fallback_compat_rows m
      ON m.table_name=CASE WHEN q.kind='chat' THEN 'messages' ELSE q.kind||'_messages' END
      AND lower(m.row_id)=q.message_id AND lower(COALESCE(m.row->>(q.kind||'_id'),m.row->>'room_id'))=q.scope_id
      WHERE lower(COALESCE(m.row->>'deleted_for_all',m.row->>'deletedForAll','false'))='true'
        OR lower(m.row->>'status') IN ('deleted','recalled')`,
  ]
  for (const { table_name:table } of imported.rows) {
    if (!messageTables.has(table)) continue
    const kind=table==='messages'?'chat':table==='group_messages'?'group':'community'
    queries.push(`SELECT q.kind,q.scope_id,q.message_id FROM requested q JOIN supabase_import.${table} m
      ON q.kind='${kind}' AND lower(to_jsonb(m)->>'id')=q.message_id
      AND lower(COALESCE(to_jsonb(m)->>'${kind}_id',to_jsonb(m)->>'room_id'))=q.scope_id
      WHERE lower(COALESCE(to_jsonb(m)->>'deleted_for_all',to_jsonb(m)->>'deletedForAll','false'))='true'
        OR lower(to_jsonb(m)->>'status') IN ('deleted','recalled')`)
  }
  const deleted=await db.query(`WITH requested AS (SELECT * FROM jsonb_to_recordset($1::jsonb)
    AS q(kind TEXT,scope_id TEXT,message_id TEXT)) ${queries.join(' UNION ')}`, [JSON.stringify(requested)])
  const deletedKeys=new Set(deleted.rows.map(row=>scopeKey({kind:row.kind,scopeId:row.scope_id,messageId:row.message_id})))
  return rows.filter(row=>!deletedKeys.has(rowScopes.get(row)))
}

function hiddenForIdentity(row, identity) {
  const hidden=Array.isArray(row?.deleted_by)?row.deleted_by:Array.isArray(row?.deletedBy)?row.deletedBy:[]
  return hidden.some(value=>identity.ids.has(clean(value)))
}

function isRecallPatch(patch) {
  return Object.keys(patch||{}).every(key=>['deleted_for_all','deletedForAll','updated_at'].includes(key)) &&
    (enabled(patch?.deleted_for_all) || enabled(patch?.deletedForAll))
}

function isHidePatch(row,patch,identity) {
  if (!Object.keys(patch||{}).every(key=>['deleted_by','deletedBy','updated_at'].includes(key))) return false
  const requested=patch?.deleted_by || patch?.deletedBy
  if (!Array.isArray(requested) || !requested.some(value=>identity.ids.has(clean(value)))) return false
  const previous=new Set((Array.isArray(row.deleted_by)?row.deleted_by:[]).map(clean))
  return requested.every(value=>identity.ids.has(clean(value)) || previous.has(clean(value)))
}

async function hideDeletedMessageForActor(pool,{kind='chat',scopeId,messageId,actorId,seedMessage}) {
  const direct=kind==='chat',client=await pool.connect()
  try {
    await client.query('BEGIN')
    const table=direct?'fallback_messages':'fallback_room_messages'
    if (seedMessage && isDeletedForAll(seedMessage)) {
      await client.query(`INSERT INTO ${table}(id,${direct?'chat_id':'room_kind,room_id,channel_id'},sender_id,content,deleted_for_all,created_at)
        VALUES ($1::uuid,${direct?'$2':'$2,$3,$4'},${direct?'$3':'$5'},'',TRUE,${direct?'$4':'$6'}::timestamptz) ON CONFLICT DO NOTHING`,
      direct?[messageId,scopeId,seedMessage.sender_id,seedMessage.created_at||new Date()]
        :[messageId,kind,scopeId,seedMessage.channel_id||null,seedMessage.sender_id,seedMessage.created_at||new Date()])
    }
    const updated=await client.query(`UPDATE ${table} SET deleted_by=(
      SELECT COALESCE(jsonb_agg(DISTINCT value),'[]'::jsonb) FROM jsonb_array_elements_text(deleted_by||$3::jsonb) AS hidden(value)
    ),updated_at=NOW() WHERE lower(${direct?'chat_id':'room_id'})=$1 AND lower(id::text)=$2 AND deleted_for_all=TRUE
      ${direct?'':'AND room_kind=$4'} RETURNING *`,direct?[clean(scopeId),clean(messageId),JSON.stringify([clean(actorId)])]
      :[clean(scopeId),clean(messageId),JSON.stringify([clean(actorId)]),kind])
    if (!updated.rows[0]) {const error=Error('MESSAGE_NOT_FOUND');error.status=404;throw error}
    const message=redactDeletedMessage(updated.rows[0])
    await client.query(`UPDATE fallback_compat_rows SET row=$3::jsonb,updated_at=NOW() WHERE table_name=$1 AND lower(row_id)=$2`,
      [direct?'messages':`${kind}_messages`,clean(messageId),message])
    await client.query('COMMIT');return message
  } catch(error) {await client.query('ROLLBACK').catch(()=>{});throw error}
  finally{client.release()}
}

function rowMatchesScope(row, scope) {
  return clean(row?.id) === scope.messageId && clean(row?.[`${scope.kind}_id`] || row?.room_id) === scope.scopeId
}

// FOR SHARE serializes publication with DELETE/UPDATE. A push already accepted
// before deletion is recalled by message_deleted; a queued push after it is
// suppressed. The client tombstone handles FCM delivery order and offline devices.
async function withCurrentMessage(pool, scope, callback, { allowDeleted = false, actorId } = {}) {
  if (!scope || !pool?.connect) return { sent: 0, failed: 0, suppressed: true }
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const direct = scope.kind === 'chat'
    const local = await client.query(direct
      ? `SELECT * FROM fallback_messages WHERE lower(chat_id)=$1 AND lower(id::text)=$2 FOR SHARE`
      : `SELECT * FROM fallback_room_messages WHERE room_kind=$3 AND lower(room_id)=$1 AND lower(id::text)=$2 FOR SHARE`,
    direct ? [scope.scopeId, scope.messageId] : [scope.scopeId, scope.messageId, scope.kind])
    const tableName = direct ? 'messages' : `${scope.kind}_messages`
    const compat = await client.query(`SELECT row FROM fallback_compat_rows
      WHERE table_name=$1 AND lower(row_id)=$2 FOR SHARE`, [tableName, scope.messageId])
    const overlays = compat.rows.map(value => value.row).filter(row => rowMatchesScope(row, scope))
    const current = mergeMessageRows([...local.rows, ...overlays])[0]
    if (!current || (allowDeleted ? !isDeletedForAll(current) : isDeletedForAll(current))) {
      await client.query('COMMIT')
      return { sent: 0, failed: 0, suppressed: true }
    }
    if (actorId) {
      const actor = await client.query(`SELECT lower($1::text) AS id UNION
        SELECT lower(id::text) FROM fallback_users WHERE lower(id::text)=$1 OR lower(supabase_user_id::text)=$1 UNION
        SELECT lower(supabase_user_id::text) FROM fallback_users WHERE lower(id::text)=$1 OR lower(supabase_user_id::text)=$1`, [clean(actorId)])
      if (!actor.rows.some(row => clean(row.id) === clean(current.sender_id))) {
        await client.query('COMMIT')
        return { sent: 0, failed: 0, suppressed: true }
      }
    }
    const result = await callback(current, client)
    await client.query('COMMIT')
    return result
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally { client.release() }
}

function deletionData(scope, row, recipientId) {
  const timestamp = value => { const date = new Date(value); return value && Number.isFinite(date.getTime()) ? date.toISOString() : '' }
  const createdAt = timestamp(row.created_at), deletedAt = timestamp(row.updated_at)
  return { type: 'message_deleted', deleted_for_all: 'true', recipient_id: String(recipientId || ''),
    target_type: scope.kind, target_id: scope.scopeId, chat_id: scope.scopeId, message_id: scope.messageId,
    ...(scope.kind !== 'chat' ? { [`${scope.kind}_id`]: scope.scopeId } : {}),
    ...(row.channel_id ? { channel_id: String(row.channel_id) } : {}),
    ...(createdAt ? { message_created_at: createdAt } : {}),
    ...(deletedAt ? { deleted_at: deletedAt } : {}) }
}

async function sendMessageDeletedPush(pool, { kind = 'chat', scopeId, message, recipientIds = [] }, send) {
  const dispatch = send || require('./push-notifications').sendPushForNotification
  const scope = { kind, scopeId: clean(scopeId), messageId: clean(message?.id) }
  if (!scope.scopeId || !scope.messageId || !isDeletedForAll(message)) return []
  const recipients = [...new Set(recipientIds.map(clean).filter(Boolean))]
  return Promise.all(recipients.map(recipientId => dispatch(pool, {
    type: 'message_deleted', recipient_id: recipientId, target_type: kind, target_id: scope.scopeId,
  }, deletionData(scope, message, recipientId))))
}

async function ensureMessageDeletionTables(db) {
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_message_deletion_outbox (
    scope_type TEXT NOT NULL, scope_id TEXT NOT NULL, message_id TEXT NOT NULL, recipient_id TEXT NOT NULL,
    data JSONB NOT NULL, status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 6,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), locked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (scope_type, scope_id, message_id, recipient_id)
  )`)
  await db.query(`CREATE INDEX IF NOT EXISTS idx_fallback_message_deletion_outbox_pending
    ON fallback_message_deletion_outbox(next_attempt_at) WHERE status <> 'sent'`)
  await db.query(`CREATE INDEX IF NOT EXISTS idx_fallback_message_deletion_outbox_sent
    ON fallback_message_deletion_outbox(updated_at) WHERE status = 'sent'`)
}

function messagePreview(row) {
  if (!row) return null
  if (isDeletedForAll(row)) return 'Mensaje eliminado'
  if (row.content_type === 'birthday_card') return '🎂 Tarjeta de cumpleaños'
  if (row.content_type === 'contact') return 'Contacto'
  if (String(row.content || '').trim()) return String(row.content).trim()
  return ({ image: 'Imagen', video: 'Video', audio: 'Audio', sticker: 'Sticker', poll: 'Encuesta' })[row.content_type] ||
    (String(row.content_type || '').startsWith('view_once') ? 'View once' : 'Sin actividad reciente')
}

async function refreshChatSummary(db, chatId) {
  if (db.connect && !db.release) {
    const client = await db.connect()
    try { await client.query('BEGIN'); const latest = await refreshChatSummary(client, chatId); await client.query('COMMIT'); return latest }
    catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error }
    finally { client.release() }
  }
  const result = await db.query(`SELECT * FROM fallback_messages WHERE lower(chat_id)=$1
    ORDER BY created_at DESC, id DESC LIMIT 1 FOR SHARE`, [clean(chatId)])
  const latest = redactDeletedMessage(result.rows[0])
  await db.query(`UPDATE fallback_chats SET last_message=$2, last_message_at=$3,
    last_message_sender_id=$4, last_message_id=$5, updated_at=NOW() WHERE lower(id::text)=$1
    AND (last_message_at IS NULL OR $3::timestamptz IS NULL OR last_message_at <= $3::timestamptz)`,
  [clean(chatId), messagePreview(latest), latest?.created_at || null, latest?.sender_id || null, latest?.id || null])
  return latest || null
}

// Atomic payload removal + durable recall. Call after route access checks;
// sender ownership is rechecked in the UPDATE against the authenticated aliases.
async function deleteMessageForAll(pool, { kind = 'chat', scopeId, messageId, actorIds, recipientIds, seedMessage }) {
  const scope = { kind, scopeId: clean(scopeId), messageId: clean(messageId) }
  const direct = kind === 'chat', client = await pool.connect()
  try {
    await client.query('BEGIN')
    if (seedMessage && actorIds.map(clean).includes(clean(seedMessage.sender_id))) {
      // Imported messages need a canonical local tombstone in the same commit.
      const table = direct ? 'fallback_messages' : 'fallback_room_messages'
      await client.query(`INSERT INTO ${table}(id,${direct ? 'chat_id' : 'room_kind,room_id,channel_id'},sender_id,content,deleted_for_all,created_at)
        VALUES ($1::uuid,${direct ? '$2' : '$2,$3,$4'},${direct ? '$3' : '$5'},'',TRUE,${direct ? '$4' : '$6'}::timestamptz)
        ON CONFLICT (id) DO NOTHING`, direct ? [scope.messageId,scope.scopeId,seedMessage.sender_id,seedMessage.created_at || new Date()]
        : [scope.messageId,kind,scope.scopeId,seedMessage.channel_id || null,seedMessage.sender_id,seedMessage.created_at || new Date()])
    }
    const result = await client.query(`UPDATE ${direct ? 'fallback_messages' : 'fallback_room_messages'}
      SET deleted_for_all=TRUE, content='', content_type='text', media_url=NULL, ${direct ? 'thumbnail_url=NULL,' : ''}
      media_size=0, reply_to_id=NULL, poll_id=NULL, reactions='{}'::jsonb, is_edited=FALSE, is_forwarded=FALSE,
      forwarded_from_message_id=NULL, forwarded_from_user_id=NULL, updated_at=NOW()
      WHERE lower(${direct ? 'chat_id' : 'room_id'})=$1 AND lower(id::text)=$2
        AND lower(sender_id)=ANY($3::text[])${direct ? '' : ' AND room_kind=$4'} RETURNING *`,
    direct ? [scope.scopeId, scope.messageId, actorIds.map(clean)] : [scope.scopeId, scope.messageId, actorIds.map(clean), kind])
    if (!result.rows[0]) { const error = Error('MESSAGE_DELETE_FOR_ALL_DENIED'); error.status = 403; throw error }
    const message = redactDeletedMessage(result.rows[0])
    // A compatibility overlay cannot revive the message through a legacy read.
    await client.query(`UPDATE fallback_compat_rows SET row=$3::jsonb, updated_at=NOW()
      WHERE table_name=$1 AND lower(row_id)=$2`, [direct ? 'messages' : `${kind}_messages`, scope.messageId, message])
    await client.query(`DELETE FROM fallback_notifications WHERE lower(extra_data->>'message_id')=$1 AND lower(target_id)=$2`,
      [scope.messageId, scope.scopeId])
    await client.query(`DELETE FROM fallback_compat_rows WHERE table_name='notifications'
      AND lower(COALESCE(row->'extra_data'->>'message_id', row->'data'->>'message_id'))=$1
      AND lower(row->>'target_id')=$2`, [scope.messageId, scope.scopeId])
    if (direct) await refreshChatSummary(client, scope.scopeId)
    for (const recipientId of [...new Set(recipientIds.map(clean).filter(Boolean))]) {
      await client.query(`INSERT INTO fallback_message_deletion_outbox(scope_type,scope_id,message_id,recipient_id,data)
        VALUES ($1,$2,$3,$4,$5::jsonb) ON CONFLICT DO NOTHING`,
      [kind, scope.scopeId, scope.messageId, recipientId, deletionData(scope, message, recipientId)])
    }
    await client.query('COMMIT')
    return message
  } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error }
  finally { client.release() }
}

let processing = false
async function processMessageDeletionOutbox(pool, { send, limit = 50 } = {}) {
  if (processing) return { busy: true }
  processing = true
  let sent = 0, retried = 0
  try {
    await pool.query(`WITH expired AS (
      SELECT ctid FROM fallback_message_deletion_outbox WHERE status='sent' AND updated_at < NOW()-INTERVAL '7 days'
      ORDER BY updated_at LIMIT 100 FOR UPDATE SKIP LOCKED
    ) DELETE FROM fallback_message_deletion_outbox WHERE ctid IN (SELECT ctid FROM expired)`)
    await pool.query(`UPDATE fallback_message_deletion_outbox SET status='failed',locked_at=NULL,updated_at=NOW()
      WHERE status='processing' AND locked_at < NOW()-INTERVAL '2 minutes' AND attempts >= max_attempts`)
    const claimed = await pool.query(`WITH due AS (
      SELECT scope_type,scope_id,message_id,recipient_id FROM fallback_message_deletion_outbox
      WHERE attempts < max_attempts AND ((status='pending' AND next_attempt_at <= NOW()) OR (status='processing' AND locked_at < NOW()-INTERVAL '2 minutes'))
      ORDER BY next_attempt_at LIMIT $1 FOR UPDATE SKIP LOCKED
    ) UPDATE fallback_message_deletion_outbox o SET status='processing',locked_at=NOW(),attempts=o.attempts+1,updated_at=NOW()
      FROM due WHERE (o.scope_type,o.scope_id,o.message_id,o.recipient_id)=(due.scope_type,due.scope_id,due.message_id,due.recipient_id)
      RETURNING o.*`, [Math.max(1, Math.min(Number(limit) || 50, 100))])
    const dispatch = send || require('./push-notifications').sendPushForNotification
    for (const row of claimed.rows) {
      let success = false
      try {
        const result = await dispatch(pool, { type: 'message_deleted', recipient_id: row.recipient_id,
          target_id: row.scope_id, target_type: row.scope_type }, row.data)
        success = !result.disabled && !result.suppressed && Number(result.failed || 0) === 0
      } catch { /* Durable retry retains identifiers only. */ }
      await pool.query(`UPDATE fallback_message_deletion_outbox SET status=$5,locked_at=NULL,
        next_attempt_at=NOW()+($6::integer * INTERVAL '1 second'),updated_at=NOW()
        WHERE scope_type=$1 AND scope_id=$2 AND message_id=$3 AND recipient_id=$4`,
      [row.scope_type,row.scope_id,row.message_id,row.recipient_id,success ? 'sent' : Number(row.attempts) >= Number(row.max_attempts) ? 'failed' : 'pending',
        Math.min(900, 5 * 2 ** Math.min(8, Math.max(0, Number(row.attempts) - 1)))])
      if (success) sent++; else retried++
    }
    return { sent, retried }
  } finally { processing = false }
}

module.exports = { messageTables, isDeletedForAll, redactDeletedMessage, mergeMessageRows, messageScope,
  notificationMessageScope, withCurrentMessage, deletionData, sendMessageDeletedPush,
  ensureMessageDeletionTables, refreshChatSummary, deleteMessageForAll, processMessageDeletionOutbox,
  filterDeletedMessageNotifications, hiddenForIdentity, isRecallPatch, isHidePatch, hideDeletedMessageForActor }
