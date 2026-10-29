'use strict'
const bcrypt = require('bcryptjs')
function problem(code, status = 400) { return Object.assign(new Error(code), { code, status }) }

/** Only the existing owner may change settings; empty password preserves a private room's hash. */
async function updateVoiceRoomSettings(pool, room, actorIds, input, hashPassword = value => bcrypt.hash(value, 12), { allowLegacyInsert = false } = {}) {
  const ids = [...actorIds].map(value => String(value).trim().toLowerCase()).filter(Boolean)
  const owner = String(room.creator_id || room.owner_id || '').trim().toLowerCase()
  if (!owner || !ids.includes(owner)) throw problem('VOICE_ROOM_UPDATE_DENIED', 403)
  if (room.community_id || room.communityId || room.community_channel_id || room.communityChannelId) throw problem('VOICE_ROOM_COMMUNITY_MANAGED', 409)
  const name = typeof input.name === 'string' ? input.name.trim() : ''
  if (!name || name.length > 120) throw problem('VOICE_ROOM_NAME_INVALID')
  if (typeof input.isPublic !== 'boolean') throw problem('VOICE_ROOM_VISIBILITY_INVALID')
  const password = input.password == null ? '' : String(input.password)
  let passwordHash = input.isPublic ? null : room.password_hash || null
  if (!input.isPublic && !password && !passwordHash && room.password) passwordHash = await hashPassword(String(room.password))
  if (!input.isPublic && password) {
    if (password.length < 4 || password.length > 128 || Buffer.byteLength(password, 'utf8') > 72) throw problem('VOICE_ROOM_PASSWORD_INVALID')
    passwordHash = await hashPassword(password)
  }
  if (!input.isPublic && !passwordHash) throw problem('VOICE_ROOM_PASSWORD_REQUIRED')
  const { rows } = await pool.query(`UPDATE fallback_voice_rooms
    SET name=$2, is_public=$3, password_hash=$4, updated_at=NOW()
    WHERE lower(id::text)=$1 AND lower(creator_id)=ANY($5::text[]) AND is_active=TRUE
    RETURNING *`, [String(room.id).toLowerCase(), name, input.isPublic, passwordHash, ids])
  if (rows[0]) return rows[0]
  // Imported rooms are immutable snapshots. Materialize an authorized edit once,
  // retaining the UUID/channel and old short-code alias. An existing tombstone or
  // concurrent ownership change wins the conflict and can never be resurrected.
  if (allowLegacyInsert) {
    const inserted = await pool.query(`INSERT INTO fallback_voice_rooms
      (id,name,creator_id,is_public,password_hash,description,max_participants,agora_channel_name,legacy_readable_id,room_mode)
      VALUES ($1::uuid,$2,$3,$4,$5,$6,$7,$8,$9,$10)
      ON CONFLICT (id) DO NOTHING RETURNING *`,
    [room.id,name,owner,input.isPublic,passwordHash,String(room.description || room.topic || ''),
      Math.max(2,Math.min(Number(room.max_participants) || 50,200)),String(room.agora_channel_name || room.agoraChannelName || ''),
      room.readable_id || room.readableId || null,room.room_mode === 'stage' ? 'stage' : 'casual'])
    if (inserted.rows[0]) return inserted.rows[0]
  }
  throw problem('VOICE_ROOM_NOT_FOUND', 404)
}

async function deleteVoiceRoomRecord(pool, room, actorIds) {
  const ids = [...actorIds].map(id => String(id).trim().toLowerCase()).filter(Boolean)
  const owner = String(room.creator_id || room.owner_id || '').trim().toLowerCase()
  if (!owner || !ids.includes(owner)) throw problem('VOICE_ROOM_DELETE_DENIED', 403)
  if (room.community_id || room.communityId || room.community_channel_id || room.communityChannelId) throw problem('VOICE_ROOM_COMMUNITY_MANAGED', 409)
  const result = await pool.query(`INSERT INTO fallback_voice_rooms(id,name,creator_id,is_active,legacy_readable_id)
    VALUES ($1::uuid,$2,$3,FALSE,$4)
    ON CONFLICT (id) DO UPDATE SET is_active=FALSE,updated_at=NOW()
      WHERE lower(fallback_voice_rooms.creator_id)=ANY($5::text[])
    RETURNING id`,[room.id,String(room.name || room.title || 'Sala de voz'),owner,room.readable_id || room.readableId || null,ids])
  if (!result.rows[0]) throw problem('VOICE_ROOM_DELETE_DENIED', 403)
}

async function visibleVoiceDirectoryRows(pool, rows) {
  const ids = [...new Set(rows.map(row => String(row.id || '').trim().toLowerCase()).filter(Boolean))]
  if (!ids.length) return []
  const result = await pool.query(`SELECT id::text FROM fallback_voice_rooms
    WHERE is_active=FALSE AND lower(id::text)=ANY($1::text[])`,[ids])
  const hidden = new Set(result.rows.map(row => String(row.id).toLowerCase()))
  return rows.filter(row => !hidden.has(String(row.id).toLowerCase()) && ![false,'false',0,'0'].includes(row.is_active))
}
module.exports = { updateVoiceRoomSettings, deleteVoiceRoomRecord, visibleVoiceDirectoryRows }
