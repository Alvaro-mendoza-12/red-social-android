'use strict'

const crypto = require('node:crypto')
const { loadCollectionMembershipSnapshot, collectionMembershipStateFromSnapshot, lockRoom } = require('./collection-membership-policy')
const { selectVoiceInvitees, groupVoiceRequestHash } = require('./group-voice-policy')
const TYPE = 'group_voice_room_invite'
const id = value => String(value || '').trim().toLowerCase()
const identities = user => [...new Set([id(user?.id), id(user?.supabase_user_id)].filter(Boolean))]
const preferred = user => id(user.supabase_user_id || user.id)
const problem = (code, status = 400) => Object.assign(new Error(code), { code, status })
const cleanName = (value, fallback) => {
  const name = String(value || fallback).replace(/[\r\n\u0000-\u001f]/g, ' ').trim().slice(0, 120)
  return name && !name.includes('@') ? name : fallback
}

async function ensureGroupVoiceTables(db) {
  await db.query('ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS group_id TEXT')
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_group_voice_requests (
    request_id UUID PRIMARY KEY, room_id UUID NOT NULL UNIQUE REFERENCES fallback_voice_rooms(id) ON DELETE CASCADE,
    group_id TEXT NOT NULL, creator_id TEXT NOT NULL, request_hash TEXT NOT NULL,
    invited_count INTEGER NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`)
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_group_voice_invites (
    room_id UUID NOT NULL REFERENCES fallback_voice_rooms(id) ON DELETE CASCADE, user_id TEXT NOT NULL,
    notification_id UUID NOT NULL UNIQUE REFERENCES fallback_notifications(id) ON DELETE CASCADE,
    delivered_at TIMESTAMPTZ, retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_until TIMESTAMPTZ, attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(room_id,user_id))`)
  await db.query(`CREATE INDEX IF NOT EXISTS idx_group_voice_pending
    ON fallback_group_voice_invites(retry_at) WHERE delivered_at IS NULL`)
  await db.query(`CREATE INDEX IF NOT EXISTS idx_group_voice_active
    ON fallback_voice_rooms(updated_at) WHERE group_id IS NOT NULL AND is_active=TRUE`)
}

/** One complete membership snapshot. Historical aliases cannot resurrect a removed member. */
async function groupVoiceRoster(db, user, rawGroupId) {
  const groupId = id(rawGroupId)
  const snapshot = await loadCollectionMembershipSnapshot(db, 'group', groupId)
  const actor = collectionMembershipStateFromSnapshot(snapshot, identities(user))
  if (!actor.member) throw problem('GROUP_VOICE_MEMBERSHIP_REQUIRED', 403)
  const candidates = [...new Set([
    actor.room.creator_id || actor.room.owner_id || actor.room.user_id,
    ...actor.members.map(row => row.user_id || row.profile_id || row.member_id),
  ].map(id).filter(Boolean))]
  const users = await db.query(`SELECT u.id::text,u.supabase_user_id::text,
    COALESCE(NULLIF(p.profile->>'display_name',''),u.display_name) AS display_name,
    COALESCE(NULLIF(p.profile->>'avatar_url',''),u.avatar_url) AS avatar_url
    FROM fallback_users u LEFT JOIN fallback_profile_overrides p ON p.user_id=u.id
    WHERE lower(u.id::text)=ANY($1::text[]) OR lower(COALESCE(u.supabase_user_id::text,''))=ANY($1::text[])`, [candidates])
  const own = identities(user)
  const covered = new Set(users.rows.flatMap(identities))
  const records = [...users.rows]
  // Imported membership remains authoritative even before that person signs in
  // to Ubuntu. Persist its activity invitation instead of silently dropping it.
  for (const candidate of candidates) {
    if (!covered.has(candidate)) records.push({ id: candidate })
  }
  const profiles = await require('./data-routes').publicProfilesByIds(db, candidates)
  const compatProfiles = await db.query(`SELECT row FROM fallback_compat_rows WHERE table_name='profiles'
    AND lower(COALESCE(row->>'id',row->>'user_id',row_id))=ANY($1::text[]) ORDER BY updated_at`, [candidates])
  profiles.push(...compatProfiles.rows.map(entry => entry.row))
  const byId = new Map(profiles.flatMap(profile =>
    ['id','user_id','profile_id','supabase_user_id','local_id'].map(key => id(profile[key])).filter(Boolean).map(value => [value,profile])))
  const members = records.filter(member =>
    !identities(member).some(value => own.includes(value)) &&
    collectionMembershipStateFromSnapshot(snapshot, identities(member)).member)
    .map(member => {
      const profile = identities(member).map(value => byId.get(value)).find(Boolean) || {}
      return { id: preferred(member), name: cleanName(member.display_name || profile.display_name || profile.username, 'Integrante'),
        avatarUrl: member.avatar_url || profile.avatar_url || profile.photo_url || profile.profile_image || '' }
    })
    .sort((a, b) => a.id.localeCompare(b.id))
  return { groupId, groupName: cleanName(actor.room.name || actor.room.title, 'Grupo'), members }
}

async function canAccessGroupVoiceRoom(db, user, room) {
  const groupId = id(room.group_id || room.groupId)
  if (!groupId) return true
  // Re-read active state: cached directory/alias rows are never an admission ticket.
  const current = await db.query('SELECT is_active FROM fallback_voice_rooms WHERE id=$1::uuid AND group_id=$2', [room.id, groupId])
  if (current.rows[0]?.is_active !== true) return false
  const snapshot = await loadCollectionMembershipSnapshot(db, 'group', groupId)
  const ids = identities(user)
  if (!collectionMembershipStateFromSnapshot(snapshot, ids).member) return false
  if (ids.includes(id(room.creator_id))) return true
  const invitation = await db.query(`SELECT 1 FROM fallback_group_voice_invites
    WHERE room_id=$1::uuid AND user_id=ANY($2::text[])`, [room.id, ids])
  return invitation.rows.length > 0
}

async function lockGroupVoiceRoom(db, roomId) {
  await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`group-voice/${id(roomId)}`])
}

async function createGroupVoiceRoom(db, user, groupId, input) {
  const requestId = id(input?.requestId)
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(requestId)) throw problem('GROUP_VOICE_REQUEST_ID_INVALID')
  if (!Array.isArray(input.recipientIds) || input.recipientIds.length > 10000) throw problem('GROUP_VOICE_RECIPIENTS_REQUIRED')
  if (!['all', 'selected'].includes(input.mode)) throw problem('GROUP_VOICE_MODE_INVALID')
  const own = identities(user)
  const recipientIds = input.recipientIds.map(id).filter(value => !own.includes(value))
  const requestHash = groupVoiceRequestHash({ groupId, mode: input.mode, recipientIds })
  await lockGroupVoiceRoom(db, requestId)
  await lockRoom(db, 'group', id(groupId))
  const prior = await db.query(`SELECT r.*,v.is_active FROM fallback_group_voice_requests r
    JOIN fallback_voice_rooms v ON v.id=r.room_id WHERE r.request_id=$1::uuid`, [requestId])
  if (prior.rows[0]) {
    const record = prior.rows[0]
    if (!own.includes(record.creator_id) || record.group_id !== id(groupId) || record.request_hash !== requestHash) throw problem('GROUP_VOICE_REQUEST_CONFLICT', 409)
    if (!record.is_active) throw problem('GROUP_VOICE_ROOM_ENDED', 410)
    const state = await loadCollectionMembershipSnapshot(db, 'group', groupId)
    if (!collectionMembershipStateFromSnapshot(state, own).member) throw problem('GROUP_VOICE_MEMBERSHIP_REQUIRED', 403)
    return { roomId: record.room_id, invitedCount: record.invited_count, reused: true }
  }
  const roster = await groupVoiceRoster(db, user, groupId)
  const recipients = selectVoiceInvitees({ members: roster.members, creatorIds: own, mode: input.mode, recipientIds })
  const recent = await db.query(`SELECT COUNT(*)::int AS count FROM fallback_group_voice_requests
    WHERE creator_id=$1 AND created_at>NOW()-INTERVAL '10 minutes'`, [preferred(user)])
  if (recent.rows[0].count >= 10) throw problem('GROUP_VOICE_CREATE_LIMIT', 429)
  const creatorName = cleanName(user.display_name, 'Un integrante')
  await db.query(`INSERT INTO fallback_voice_rooms(id,name,description,creator_id,is_public,is_active,
    agora_channel_name,room_mode,max_participants,group_id)
    VALUES($1::uuid,$2,$3,$4,FALSE,TRUE,$1,'casual',50,$5)`,
  [requestId, `Voz · ${roster.groupName}`.slice(0, 120), roster.groupName, preferred(user), roster.groupId])
  await db.query(`INSERT INTO fallback_group_voice_requests(request_id,room_id,group_id,creator_id,request_hash,invited_count)
    VALUES($1::uuid,$1::uuid,$2,$3,$4,$5)`, [requestId, roster.groupId, preferred(user), requestHash, recipients.length])
  const content = `${creatorName} ha iniciado una sala de voz en ${roster.groupName}`
  const notices = recipients.map(member => ({ notification_id: crypto.randomUUID(), user_id: member.id }))
  const extra = { notification_type: TYPE, room_id: requestId, group_id: roster.groupId, group_name: roster.groupName }
  await db.query(`INSERT INTO fallback_notifications(id,recipient_id,actor_id,actor_name,actor_avatar_url,type,target_id,target_type,content,extra_data)
    SELECT n.notification_id::uuid,n.user_id,$2,$3,$4,$5,$6,'voice_room',$7,$8::jsonb
    FROM jsonb_to_recordset($1::jsonb) AS n(notification_id text,user_id text)`,
  [JSON.stringify(notices), preferred(user), creatorName, user.avatar_url || '', TYPE, requestId, content, JSON.stringify(extra)])
  await db.query(`INSERT INTO fallback_group_voice_invites(room_id,user_id,notification_id)
    SELECT $1::uuid,n.user_id,n.notification_id::uuid FROM jsonb_to_recordset($2::jsonb) AS n(notification_id text,user_id text)`,
  [requestId, JSON.stringify(notices)])
  return { roomId: requestId, invitedCount: recipients.length, reused: false }
}

/** Claim a durable outbox item. Retries keep the same notification ID on every transport. */
async function deliverGroupVoiceInvitations(pool, deliver) {
  const claimed = await pool.query(`WITH pending AS (
    SELECT room_id,user_id FROM fallback_group_voice_invites WHERE delivered_at IS NULL AND retry_at<=NOW()
      AND (claimed_until IS NULL OR claimed_until<NOW()) ORDER BY retry_at LIMIT 40 FOR UPDATE SKIP LOCKED)
    UPDATE fallback_group_voice_invites i SET claimed_until=NOW()+INTERVAL '90 seconds',attempts=i.attempts+1
    FROM pending p WHERE i.room_id=p.room_id AND i.user_id=p.user_id RETURNING i.*`)
  for (const invite of claimed.rows) {
    try {
      const rooms = await pool.query('SELECT * FROM fallback_voice_rooms WHERE id=$1', [invite.room_id])
      const user = await pool.query(`SELECT id::text,supabase_user_id::text FROM fallback_users
        WHERE lower(id::text)=$1 OR lower(COALESCE(supabase_user_id::text,''))=$1 LIMIT 1`, [invite.user_id])
      let delivered = true
      if (user.rows[0] && rooms.rows[0] && await canAccessGroupVoiceRoom(pool, user.rows[0], rooms.rows[0])) {
        const notice = await pool.query('SELECT * FROM fallback_notifications WHERE id=$1', [invite.notification_id])
        if (notice.rows[0]) delivered = await deliver(notice.rows[0])
      }
      await pool.query(`UPDATE fallback_group_voice_invites SET delivered_at=CASE WHEN $3 THEN NOW() ELSE NULL END,
        retry_at=NOW()+INTERVAL '60 seconds',claimed_until=NULL WHERE room_id=$1 AND user_id=$2`, [invite.room_id, invite.user_id, delivered])
    } catch {
      await pool.query(`UPDATE fallback_group_voice_invites SET retry_at=NOW()+INTERVAL '60 seconds',claimed_until=NULL
        WHERE room_id=$1 AND user_id=$2`, [invite.room_id, invite.user_id])
    }
  }
}

/** No creator dependency: only an empty room with expired presence is retired. */
async function retireEmptyGroupVoiceRooms(pool, onClosed = async () => {}) {
  const candidates = await pool.query(`SELECT id FROM fallback_voice_rooms r WHERE group_id IS NOT NULL AND is_active=TRUE
    AND created_at<NOW()-INTERVAL '5 minutes' AND updated_at<NOW()-INTERVAL '2 minutes'
    AND NOT EXISTS(SELECT 1 FROM fallback_voice_room_participants p WHERE p.room_id=r.id::text AND p.updated_at>=NOW()-INTERVAL '2 minutes') LIMIT 100`)
  for (const candidate of candidates.rows) {
    const db = await pool.connect()
    let closed = false
    try {
      await db.query('BEGIN'); await lockGroupVoiceRoom(db, candidate.id)
      const result = await db.query(`UPDATE fallback_voice_rooms r SET is_active=FALSE,updated_at=NOW()
        WHERE id=$1 AND is_active=TRUE AND group_id IS NOT NULL
        AND created_at<NOW()-INTERVAL '5 minutes' AND updated_at<NOW()-INTERVAL '2 minutes'
        AND NOT EXISTS(SELECT 1 FROM fallback_voice_room_participants p WHERE p.room_id=r.id::text AND p.updated_at>=NOW()-INTERVAL '2 minutes') RETURNING id`, [candidate.id])
      closed = result.rows.length > 0
      if (closed) await db.query('DELETE FROM fallback_voice_room_participants WHERE room_id=$1', [String(candidate.id)])
      await db.query('COMMIT')
    } catch (error) { await db.query('ROLLBACK'); throw error } finally { db.release() }
    if (closed) await onClosed(String(candidate.id))
  }
}

function registerGroupVoiceRoutes(app, { pool, authRequired, getCurrentUser, onChanged = () => {}, deliver, maintenance = true }) {
  const base = '/v1/data/messages/groups/:groupId'
  app.get(`${base}/voice-room-invitees`, authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(401).json({ error: 'USER_NOT_FOUND' })
      res.set('Cache-Control', 'no-store').json(await groupVoiceRoster(pool, user, req.params.groupId))
    } catch (error) { if (error.status) res.status(error.status).json({ error: error.code }); else next(error) }
  })
  app.post(`${base}/voice-rooms`, authRequired, async (req, res, next) => {
    let db
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(401).json({ error: 'USER_NOT_FOUND' })
      db = await pool.connect(); await db.query('BEGIN')
      const result = await createGroupVoiceRoom(db, user, id(req.params.groupId), req.body || {})
      await db.query('COMMIT'); onChanged()
      res.set('Cache-Control', 'no-store').json(result)
      if (deliver) void deliverGroupVoiceInvitations(pool, deliver).catch(() => {})
    } catch (error) {
      if (db) await db.query('ROLLBACK').catch(() => {})
      if (error.status) res.status(error.status).json({ error: error.code }); else next(error)
    } finally { db?.release() }
  })
  let busy = false
  const tick = async () => {
    if (busy) return
    busy = true
    try {
      await retireEmptyGroupVoiceRooms(pool, async () => onChanged())
      if (deliver) await deliverGroupVoiceInvitations(pool, deliver)
    } catch { console.error('[group-voice] maintenance deferred') } finally { busy = false }
  }
  const timer = maintenance ? setInterval(() => void tick(), 15000) : null
  timer?.unref()
  return () => clearInterval(timer)
}

module.exports = { TYPE, ensureGroupVoiceTables, groupVoiceRoster, canAccessGroupVoiceRoom, lockGroupVoiceRoom,
  createGroupVoiceRoom, deliverGroupVoiceInvitations, retireEmptyGroupVoiceRooms, registerGroupVoiceRoutes }
