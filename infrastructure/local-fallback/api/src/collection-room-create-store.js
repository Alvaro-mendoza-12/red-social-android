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

module.exports = { upsertOwnedCollectionRoom }
