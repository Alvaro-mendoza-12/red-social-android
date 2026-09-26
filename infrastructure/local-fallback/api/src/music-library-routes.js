'use strict'

function libraryCursor(value) {
  if (!value) return null
  try {
    if (typeof value !== 'string' || value.length > 320) throw new Error()
    const cursor = JSON.parse(Buffer.from(value, 'base64url').toString('utf8'))
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(cursor.id) ||
        !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(cursor.at) || !Number.isFinite(Date.parse(cursor.at)) ||
        new Date(cursor.at).toISOString().slice(0, 23) !== cursor.at.slice(0, 23)) throw new Error()
    return cursor
  } catch {
    throw Object.assign(new Error('INVALID_MUSIC_CURSOR'), { status: 400 })
  }
}

async function listOwnMusic(pool, userId, query, mapTrack) {
  const cursor = libraryCursor(query?.cursor)
  const requestedLimit = Number(query?.limit)
  const limit = Number.isFinite(requestedLimit) && requestedLimit > 0 ? Math.min(100, Math.floor(requestedLimit)) || 1 : 20
  const { rows } = await pool.query(
    `SELECT t.*, to_char(t.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS history_cursor_at
       FROM fallback_music_tracks t
      WHERE t.owner_id=$1::uuid AND t.removed_at IS NULL
        AND ($2::timestamptz IS NULL OR (t.created_at,t.id)<($2::timestamptz,$3::uuid))
      ORDER BY t.created_at DESC,t.id DESC LIMIT $4`,
    [userId, cursor?.at || null, cursor?.id || null, limit + 1],
  )
  const page = rows.slice(0, limit)
  const last = page.at(-1)
  return {
    items: page.map((row) => mapTrack(row)),
    // Keep PostgreSQL microseconds: converting to a JS Date can skip uploads sharing a millisecond.
    nextCursor: rows.length > limit && last
      ? Buffer.from(JSON.stringify({ at: last.history_cursor_at, id: last.id })).toString('base64url') : null,
  }
}

async function removeOwnMusic(pool, userId, trackId) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const { rows } = await client.query(
      `SELECT id,moderation_state,removed_at FROM fallback_music_tracks
        WHERE id=$1::uuid AND owner_id=$2::uuid FOR UPDATE`, [trackId, userId],
    )
    const track = rows[0]
    if (!track) {
      await client.query('ROLLBACK')
      return null
    }
    if (!track.removed_at) {
      // Moments store their own audio metadata. Retain the row and all stored media;
      // deleting shared audio/cover objects here would break previously published moments.
      await client.query(
        `UPDATE fallback_music_tracks SET moderation_state='REMOVED',visibility='PRIVATE',
           requested_visibility='PRIVATE',allow_reuse=FALSE,allow_remix=FALSE,allow_use_in_moments=FALSE,
           removed_at=NOW(),moderation_reason='Eliminada por su autor',updated_at=NOW()
         WHERE id=$1::uuid AND owner_id=$2::uuid`, [trackId, userId],
      )
      await client.query(
        `INSERT INTO fallback_music_moderation_events(track_id,actor_id,from_state,to_state,reason,evidence)
         VALUES($1::uuid,$2::uuid,$3,'REMOVED','Eliminada por su autor','{"ownerRequested":true,"mediaRetained":true}'::jsonb)`,
        [trackId, userId, track.moderation_state],
      )
    }
    await client.query('COMMIT')
    return { id: `community:${trackId}`, deleted: true, alreadyDeleted: !!track.removed_at, existingMomentsPreserved: true }
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }
}

function registerMusicLibraryRoutes(app, { pool, authRequired, identity, rawTrackId, mapTrack }) {
  app.get('/v1/data/music/my', authRequired, async (req, res, next) => {
    try {
      const current = await identity(req)
      if (!current) return res.status(401).json({ error: 'AUTH_REQUIRED' })
      res.json(await listOwnMusic(pool, current.userId, req.query, mapTrack))
    } catch (error) { next(error) }
  })
  app.delete('/v1/data/music/:trackId', authRequired, async (req, res, next) => {
    try {
      const current = await identity(req)
      if (!current) return res.status(401).json({ error: 'AUTH_REQUIRED' })
      const trackId = rawTrackId(req.params.trackId)
      if (!trackId) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const result = await removeOwnMusic(pool, current.userId, trackId)
      if (!result) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      res.json(result)
    } catch (error) { next(error) }
  })
}

module.exports = { libraryCursor, listOwnMusic, removeOwnMusic, registerMusicLibraryRoutes }
