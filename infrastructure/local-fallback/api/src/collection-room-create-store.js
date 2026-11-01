function conflict() {
  return Object.assign(new Error('ROOM_ID_CONFLICT'), { status: 409 })
}

/** The conflict predicate is checked by PostgreSQL, including concurrent creation attempts. */
async function upsertOwnedCollectionRoom(pool, room, actorIds, importedIdExists) {
  const ids = [...actorIds].map((id) => String(id).trim().toLowerCase()).filter(Boolean)
  if (!['group', 'community'].includes(room.kind) || !ids.includes(String(room.creatorId).trim().toLowerCase())) {
    throw conflict()
  }
  if (room.id && importedIdExists) {
    const imported = await Promise.all(['group', 'community'].map((kind) => importedIdExists(kind, room.id)))
    if (imported.some(Boolean)) throw conflict()
  }
  const { rows } = await pool.query(
    `INSERT INTO fallback_collection_rooms(
       id, room_kind, name, image, description, category, is_public, creator_id,
       is_deleted, only_admins_can_talk, daily_message_limit, weekly_message_limit, created_at, updated_at
     ) VALUES (COALESCE($1::uuid, gen_random_uuid()), $2, $3, $4, $5, $6, $7, $8,
       FALSE, ($2 = 'community'), 0, 0, NOW(), NOW())
     ON CONFLICT (id) DO UPDATE SET
       name = EXCLUDED.name, image = EXCLUDED.image, description = EXCLUDED.description,
       category = EXCLUDED.category, is_public = EXCLUDED.is_public, updated_at = NOW()
     WHERE fallback_collection_rooms.room_kind = EXCLUDED.room_kind
       AND lower(fallback_collection_rooms.creator_id) = ANY($9::text[])
       AND fallback_collection_rooms.is_deleted = FALSE
     RETURNING id::text, room_kind, name, image, description, category, is_public,
       creator_id, is_deleted, only_admins_can_talk, daily_message_limit, weekly_message_limit, created_at, updated_at`,
    [room.id || null, room.kind, room.name, room.image, room.description, room.category,
      room.isPublic, room.creatorId, ids],
  )
  if (!rows[0]) throw conflict()
  return rows[0]
}

async function withCollectionTransaction(pool, operation) {
  const client = await pool.connect()
  let begun = false
  try {
    await client.query('BEGIN')
    begun = true
    await client.query("SET LOCAL lock_timeout = '5s'")
    const result = await operation(client)
    await client.query('COMMIT')
    begun = false
    return result
  } catch (error) {
    if (begun) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally { client.release() }
}

async function insertInitialMembers(client, row, room, actorIds, memberIds) {
  const creator = String(row.creator_id || room.creatorId).trim().toLowerCase()
  const actors = new Set([...actorIds].map(id => String(id).trim().toLowerCase()))
  const members = new Set([creator, ...memberIds.map(id => String(id).trim().toLowerCase())
    .filter(Boolean).map(id => actors.has(id) ? creator : id)])
  for (const member of members) {
    await client.query(
      `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at)
       VALUES ($1, $2, $3, $4, NOW())
       ON CONFLICT (room_kind, room_id, user_id) DO NOTHING`,
      [room.kind, row.id, member, member === creator ? 'owner' : 'member'],
    )
  }
}

async function lockCollectionMembership(client, room) {
  if (room.id) await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',
    [`membership/${room.kind}/${String(room.id).trim().toLowerCase()}`])
}

/** A room and all initial memberships become visible together; preserves the installed client contract. */
async function createOwnedCollectionRoom(pool, room, actorIds, memberIds, importedIdExists) {
  return withCollectionTransaction(pool, async client => {
    await lockCollectionMembership(client, room)
    const row = await upsertOwnedCollectionRoom(client, room, actorIds,
      importedIdExists ? (kind, id) => importedIdExists(client, kind, id) : null)
    await insertInitialMembers(client, row, room, actorIds, memberIds)
    return row
  })
}

/** Opt-in idempotent creation: an old retry cannot undo edits or re-invite removed members. */
async function createOwnedCollectionRoomOnce(pool, room, actorIds, memberIds, importedIdExists) {
  const actors = [...actorIds].map(id => String(id).trim().toLowerCase()).filter(Boolean)
  if (!['group', 'community'].includes(room.kind) || !actors.includes(String(room.creatorId).trim().toLowerCase()) || !room.id) throw conflict()
  return withCollectionTransaction(pool, async client => {
    await lockCollectionMembership(client, room)
    if (importedIdExists) {
      const imported = await Promise.all(['group', 'community'].map(kind => importedIdExists(client, kind, room.id)))
      if (imported.some(Boolean)) throw conflict()
    }
    const inserted = await client.query(
      `INSERT INTO fallback_collection_rooms(
         id, room_kind, name, image, description, category, is_public, creator_id,
         is_deleted, only_admins_can_talk, daily_message_limit, weekly_message_limit, created_at, updated_at
       ) VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8,
         FALSE, ($2 = 'community'), 0, 0, NOW(), NOW())
       ON CONFLICT (id) DO NOTHING RETURNING *`,
      [room.id, room.kind, room.name, room.image, room.description, room.category, room.isPublic, room.creatorId],
    )
    const created = inserted.rows.length > 0
    // A conflicting insertion waits for its transaction before this SELECT sees the committed row.
    const row = inserted.rows[0] || (await client.query(
      'SELECT * FROM fallback_collection_rooms WHERE id=$1::uuid FOR UPDATE', [room.id],
    )).rows[0]
    if (!row || row.room_kind !== room.kind || row.is_deleted || !actors.includes(String(row.creator_id).trim().toLowerCase())) throw conflict()
    if (!created && (await client.query(
      `SELECT 1 AS departed FROM fallback_collection_members WHERE room_kind=$1 AND room_id=$2
       AND lower(trim(user_id))=ANY($3::text[]) AND is_deleted=TRUE LIMIT 1`, [room.kind, row.id, actors],
    )).rows.length) throw conflict()
    if (created) await insertInitialMembers(client, row, room, actorIds, memberIds)
    const members = (await client.query(
      `SELECT user_id, role, joined_at FROM fallback_collection_members
       WHERE room_kind=$1 AND room_id=$2 AND is_deleted IS NOT TRUE ORDER BY (user_id=$3) DESC, user_id`,
      [room.kind, row.id, row.creator_id],
    )).rows
    return { room: row, created, initialMembers: members }
  })
}

module.exports = { upsertOwnedCollectionRoom, createOwnedCollectionRoom, createOwnedCollectionRoomOnce }
