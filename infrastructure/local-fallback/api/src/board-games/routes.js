'use strict'

const { randomInt } = require('node:crypto')
const engine = () => import('./engine/board-match.js')
const kinds = new Set(['chess', 'checkers', 'ludo'])
const emotes = new Set(['👏', '😄', '🔥', '🤝', '🎉', '😮'])
const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const root = '/v1/data/playmatch/board-games'
const invalid = (status, error) => Object.assign(new Error(error), { status, code: error })
const square = value => typeof value === 'string' && /^[a-h][1-8]$/.test(value)

// Blocks and accepted friendships can still use legacy account IDs.
const accountAliases = `SELECT id, ARRAY[id::text, lower(supabase_user_id::text)] AS aliases
  FROM fallback_users`
const roomAccounts = `SELECT u.id, ARRAY[u.id::text, lower(u.supabase_user_id::text)] AS aliases
  FROM jsonb_array_elements_text(r.seats) seat(id) JOIN fallback_users u ON u.id=seat.id::uuid`
const blocked = `EXISTS (
  SELECT 1 FROM fallback_user_blocks b
  WHERE (lower(b.blocker_id) = ANY(me.aliases) AND lower(b.blocked_id) = ANY(other.aliases))
     OR (lower(b.blocked_id) = ANY(me.aliases) AND lower(b.blocker_id) = ANY(other.aliases))
)`
const friends = `EXISTS (
  SELECT 1 FROM fallback_friends f
  WHERE lower(f.status) = 'accepted' AND (
    (lower(f.sender_id) = ANY(me.aliases) AND lower(f.receiver_id) = ANY(other.aliases))
    OR (lower(f.receiver_id) = ANY(me.aliases) AND lower(f.sender_id) = ANY(other.aliases))
  )
)`

async function ensureBoardGameTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_board_rooms (
      id UUID PRIMARY KEY,
      kind TEXT NOT NULL CHECK (kind IN ('chess', 'checkers', 'ludo')),
      capacity INT NOT NULL CHECK (capacity BETWEEN 2 AND 4),
      host_id TEXT NOT NULL,
      visibility TEXT NOT NULL CHECK (visibility IN ('public', 'private')),
      seats JSONB NOT NULL,
      events JSONB NOT NULL DEFAULT '[]'::jsonb,
      reactions JSONB NOT NULL DEFAULT '[]'::jsonb,
      revision INT NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT 'waiting' CHECK (status IN ('waiting', 'playing', 'finished', 'closed')),
      winner_id TEXT,
      ranked BOOLEAN NOT NULL DEFAULT false,
      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
      finished_at TIMESTAMPTZ,
      CHECK (kind = 'ludo' OR capacity = 2)
    )`)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS fallback_board_public_waiting
    ON fallback_board_rooms (kind, created_at DESC)
    WHERE status = 'waiting' AND visibility = 'public'`)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS fallback_board_host_waiting
    ON fallback_board_rooms (host_id, created_at DESC) WHERE status = 'waiting'`)
  await pool.query('CREATE INDEX IF NOT EXISTS fallback_board_member ON fallback_board_rooms USING gin (seats)')
  await pool.query(`
    CREATE INDEX IF NOT EXISTS fallback_board_rank_period
    ON fallback_board_rooms (finished_at, winner_id) WHERE winner_id IS NOT NULL AND visibility = 'public'`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_board_invites (
      room_id UUID NOT NULL REFERENCES fallback_board_rooms(id),
      recipient_id TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (room_id, recipient_id)
    )`)
  await pool.query('CREATE INDEX IF NOT EXISTS fallback_board_invite_recipient ON fallback_board_invites (recipient_id, created_at DESC)')
}

function publicRoom(row, actor) {
  return {
    id: row.id, game: row.kind, capacity: row.capacity, status: row.status,
    visibility: row.visibility, count: row.seats.length,
    seat: row.seats.indexOf(actor), host: row.host_id === actor, createdAt: row.created_at,
    invited: Boolean(row.invited),
  }
}
function snapshot(row, actor) {
  return { ...publicRoom(row, actor), revision: row.revision, actions: row.events, reactions: row.reactions }
}
function requireMember(row, actor) {
  const seat = row.seats.indexOf(actor)
  if (seat === -1) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
  return seat
}
async function transaction(pool, fn) {
  const connection = await pool.connect()
  try {
    await connection.query('BEGIN')
    const result = await fn(connection)
    await connection.query('COMMIT')
    return result
  } catch (error) {
    await connection.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    connection.release()
  }
}
function inRoom(pool, id, fn) {
  if (!uuid.test(id)) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
  return transaction(pool, async connection => {
    const { rows: [row] } = await connection.query('SELECT * FROM fallback_board_rooms WHERE id=$1 FOR UPDATE', [id])
    if (!row) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    return fn(connection, row)
  })
}
async function seatPlayer(connection, row, actor) {
  const seats = [...row.seats, actor]
  const next = seats.length === row.capacity ? 'playing' : 'waiting'
  const { rows: [saved] } = await connection.query(`
    UPDATE fallback_board_rooms SET seats=$2::jsonb,status=$3,revision=revision+1,updated_at=now()
    WHERE id=$1 RETURNING *`, [row.id, JSON.stringify(seats), next])
  return { room: snapshot(saved, actor) }
}
function playerAction(kind, body, rollDie) {
  if (kind === 'ludo' && body.type === 'roll') return { type: 'roll', roll: rollDie() }
  if (kind === 'chess' && body.type === 'claim') return { type: 'claim' }
  if (kind !== 'ludo' && body.type === 'resign') return { type: 'resign' }
  if (body.type !== 'move') return null
  if (kind === 'chess' && square(body.from) && square(body.to) &&
      (body.promotion === undefined || (typeof body.promotion === 'string' && /^[qrbn]$/.test(body.promotion)))) {
    return { type: 'move', from: body.from, to: body.to, promotion: body.promotion || undefined }
  }
  if (kind === 'checkers' && [body.from, body.to].every(value => Number.isInteger(value) && value >= 0 && value < 64)) {
    return { type: 'move', from: body.from, to: body.to }
  }
  if (kind === 'ludo' && Number.isInteger(body.token) && body.token >= 0 && body.token < 4) {
    return { type: 'move', token: body.token }
  }
  return null
}

function registerBoardGameRoutes(app, { pool, authRequired, getIdentity, rollDie = () => randomInt(1, 7) }) {
  const route = (verb, suffix, handle) => app[verb](root + suffix, authRequired, async (req, res, next) => {
    try {
      const actor = await getIdentity(req, res)
      if (!actor) return
      res.set('Cache-Control', 'no-store').json(await handle(req, actor))
    } catch (error) {
      if (Number.isInteger(error.status) && /^BOARD_/.test(error.code || '')) {
        return res.status(error.status).json({ error: error.code })
      }
      next(error)
    }
  })
  route('get', '/rooms', async (req, actor) => {
    const { rows } = await pool.query(`
      SELECT r.id,r.kind,r.capacity,r.status,r.visibility,r.seats,r.host_id,r.created_at,
             (i.room_id IS NOT NULL) AS invited
        FROM fallback_board_rooms r
        JOIN (${accountAliases}) me ON me.id=$1::text::uuid
        LEFT JOIN fallback_board_invites i ON i.room_id=r.id AND i.recipient_id=$1
       WHERE r.status<>'closed' AND ((r.seats ? $1 AND r.updated_at > now()-interval '7 days')
          OR (r.status='waiting' AND r.created_at > now()-interval '1 day'
              AND (r.visibility='public' OR i.room_id IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM LATERAL (${roomAccounts}) other WHERE ${blocked})))
       ORDER BY (r.seats ? $1) DESC, r.updated_at DESC LIMIT 60`, [actor])
    return { rooms: rows.map(row => publicRoom(row, actor)) }
  })
  function startRoom(automatic) {
    return (req, actor) => transaction(pool, async connection => {
      const { game, capacity, visibility, requestId: id } = req.body || {}
      if (!kinds.has(game) || ![2, 3, 4].includes(capacity) || (game !== 'ludo' && capacity !== 2) ||
          !['public', 'private'].includes(visibility) || (automatic && visibility !== 'public') ||
          typeof id !== 'string' || !uuid.test(id)) throw invalid(400, 'BOARD_ROOM_INVALID')
      await connection.query('SELECT pg_advisory_xact_lock(hashtext($1))', ['novvin-board-host:' + actor])
      const duplicate = await connection.query('SELECT * FROM fallback_board_rooms WHERE id=$1 AND host_id=$2', [id, actor])
      if (duplicate.rowCount) return { room: snapshot(duplicate.rows[0], actor) }
      if (automatic) {
        await connection.query('SELECT pg_advisory_xact_lock(hashtext($1))', ['novvin-board-match:' + game + ':' + capacity])
        const { rows: [candidate] } = await connection.query(`
          SELECT r.* FROM fallback_board_rooms r JOIN (${accountAliases}) me ON me.id=$1::text::uuid
           WHERE r.kind=$2 AND r.capacity=$3 AND r.visibility='public'
             AND ((r.status='waiting' AND r.created_at > now()-interval '1 day')
                  OR (r.status='playing' AND r.seats ? $1 AND r.updated_at > now()-interval '1 day'))
             AND NOT EXISTS(SELECT 1 FROM LATERAL (${roomAccounts}) other WHERE ${blocked})
           ORDER BY (r.seats ? $1) DESC, r.created_at
           LIMIT 1 FOR UPDATE OF r`, [actor, game, capacity])
        if (candidate) return candidate.seats.includes(actor) ? { room: snapshot(candidate, actor) } : seatPlayer(connection, candidate, actor)
      }
      const { rows: [room] } = await connection.query(`
        INSERT INTO fallback_board_rooms(id,kind,capacity,visibility,host_id,seats)
        SELECT $1,$2,$3,$4,$5,$6::jsonb WHERE 5>(
          SELECT count(*) FROM fallback_board_rooms
          WHERE host_id=$5 AND status='waiting' AND created_at > now()-interval '1 day')
        ON CONFLICT(id) DO NOTHING RETURNING *`, [id, game, capacity, visibility, actor, JSON.stringify([actor])])
      if (!room) throw invalid(429, 'BOARD_ROOM_LIMIT')
      return { room: snapshot(room, actor) }
    })
  }
  route('post', '/rooms', startRoom(false))
  route('post', '/match', startRoom(true))
  route('post', '/rooms/:id/join', (req, actor) => inRoom(pool, req.params.id, async (connection, row) => {
    if (row.seats.includes(actor)) return { room: snapshot(row, actor) }
    if (row.visibility === 'private') {
      const invited = await connection.query('SELECT 1 FROM fallback_board_invites WHERE room_id=$1 AND recipient_id=$2', [row.id, actor])
      if (!invited.rowCount) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    }
    if (row.status !== 'waiting' || row.seats.length >= row.capacity || Date.now() - Date.parse(row.created_at) >= 86400000) {
      throw invalid(409, 'BOARD_ROOM_FULL')
    }
    const prevented = await connection.query(`
      SELECT 1 FROM (${accountAliases}) me, (${accountAliases}) other
      WHERE me.id=$1 AND other.id=ANY($2::uuid[]) AND ${blocked} LIMIT 1`, [actor, row.seats])
    if (prevented.rowCount) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    return seatPlayer(connection, row, actor)
  }))
  route('post', '/rooms/:id/invite', (req, actor) => inRoom(pool, req.params.id, async (connection, row) => {
    if (row.host_id !== actor) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    const recipient = req.body?.recipientId
    if (typeof recipient !== 'string' || !uuid.test(recipient) || row.seats.includes(recipient) || row.status !== 'waiting') {
      throw invalid(400, 'BOARD_INVITE_INVALID')
    }
    const count = await connection.query('SELECT COUNT(*) AS count FROM fallback_board_invites WHERE room_id=$1', [row.id])
    if (Number(count.rows[0].count) >= 20) throw invalid(429, 'BOARD_INVITES_LIMIT')
    const matched = await connection.query(`
      SELECT other.id FROM (${accountAliases}) me, (${accountAliases}) other
       WHERE me.id=$1 AND other.id=$2 AND ${friends} AND NOT ${blocked}`, [actor, recipient])
    if (matched.rowCount !== 1) throw invalid(404, 'BOARD_PLAYER_NOT_FOUND')
    await connection.query(`
      INSERT INTO fallback_board_invites(room_id,recipient_id) VALUES($1,$2)
      ON CONFLICT (room_id,recipient_id) DO NOTHING`, [row.id, matched.rows[0].id])
    return { invited: true }
  }))
  route('get', '/friends', async (req, actor) => {
    const query = typeof req.query.q === 'string' ? req.query.q.trim().toLowerCase().slice(0, 80) : ''
    const { rows } = await pool.query(`
      SELECT other.id, COALESCE(NULLIF(u.display_name,''),'Jugador de Novvin') AS name
        FROM (${accountAliases}) me, (${accountAliases}) other
        JOIN fallback_users u ON u.id=other.id
       WHERE me.id=$1 AND other.id<>$1 AND ${friends} AND NOT ${blocked}
         AND strpos(lower(COALESCE(u.display_name,'')),$2)>0
       ORDER BY u.display_name NULLS LAST, other.id LIMIT 30`, [actor, query])
    return { friends: rows }
  })
  route('post', '/rooms/:id/close', (req, actor) => inRoom(pool, req.params.id, async (connection, row) => {
    const seat = requireMember(row, actor)
    if (row.status === 'waiting' && row.host_id !== actor) {
      const { rows: [saved] } = await connection.query(`
        UPDATE fallback_board_rooms SET seats=$2::jsonb,revision=revision+1,updated_at=now()
        WHERE id=$1 RETURNING *`, [row.id, JSON.stringify(row.seats.filter((_, index) => index !== seat))])
      return { room: snapshot(saved, actor) }
    }
    if (row.status === 'waiting' || row.status === 'playing') {
      const { rows: [saved] } = await connection.query(`
        UPDATE fallback_board_rooms SET status='closed',revision=revision+1,updated_at=now()
        WHERE id=$1 RETURNING *`, [row.id])
      return { room: snapshot(saved, actor) }
    }
    return { room: snapshot(row, actor) }
  }))
  route('get', '/rooms/:id', async (req, actor) => {
    if (!uuid.test(req.params.id)) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    const { rows: [room] } = await pool.query('SELECT * FROM fallback_board_rooms WHERE id=$1 AND seats ? $2', [req.params.id, actor])
    if (!room) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    return { room: snapshot(room, actor) }
  })
  route('post', '/rooms/:id/actions', (req, actor) => inRoom(pool, req.params.id, async (connection, row) => {
    const seat = requireMember(row, actor)
    const body = req.body || {}
    if (row.status !== 'playing') throw invalid(409, 'BOARD_ROOM_NOT_PLAYING')
    if (body.revision !== row.revision) throw invalid(409, 'BOARD_REVISION_CHANGED')
    const { boardMatch, boardTurn, applyBoardEvent } = await engine()
    if (row.events.length >= 2400) throw invalid(409, 'BOARD_MOVE_LIMIT')
    const game = boardMatch(row.kind, row.capacity, row.events)
    if (boardTurn(row.kind, game) !== seat) throw invalid(409, 'BOARD_OUT_OF_TURN')
    const action = playerAction(row.kind, body, rollDie)
    if (!action || !applyBoardEvent(row.kind, game, action)) throw invalid(422, 'BOARD_ILLEGAL_ACTION')
    const status = game.outcome ? 'finished' : 'playing'
    const winner = game.outcome?.winner === null || !game.outcome ? null : row.seats[game.outcome.winner]
    const { rows: [saved] } = await connection.query(`
      UPDATE fallback_board_rooms SET events=$2::jsonb,revision=revision+1,status=$3,winner_id=$4,
          ranked=$3='finished' AND $5,
          updated_at=now(), finished_at=CASE WHEN $3='finished' THEN now() ELSE NULL END
      WHERE id=$1 RETURNING *`, [row.id, JSON.stringify([...row.events, action]), status, winner,
      action.type !== 'resign' || row.events.length >= 14])
    return { room: snapshot(saved, actor) }
  }))
  route('post', '/rooms/:id/reactions', (req, actor) => inRoom(pool, req.params.id, async (connection, row) => {
    const seat = requireMember(row, actor)
    const emoji = req.body?.emoji
    if (!emotes.has(emoji) || row.status !== 'playing') throw invalid(400, 'BOARD_REACTION_INVALID')
    const now = Date.now()
    if (row.reactions.some(item => item.seat === seat && now - item.at < 1200)) throw invalid(429, 'BOARD_REACTION_LIMIT')
    const reactions = [...row.reactions.filter(item => now - item.at < 30000), { seat, emoji, at: now }].slice(-20)
    await connection.query('UPDATE fallback_board_rooms SET reactions=$2::jsonb WHERE id=$1', [row.id, JSON.stringify(reactions)])
    return { reactions }
  }))
  route('get', '/ranking', async () => {
    const { rows: [period] } = await pool.query(`
      SELECT timezone('America/Bogota', start) AS "startsAt",
             timezone('America/Bogota', start + interval '7 days') AS "endsAt",
             timezone('America/Bogota', start - interval '7 days') AS "lastStart",
             now() AS "serverNow"
        FROM (SELECT date_trunc('week', timezone('America/Bogota', now())) AS start) p`)
    const load = async (from, through, limit) => {
      const { rows } = await pool.query(`
        SELECT COALESCE(NULLIF(u.display_name,''),'Jugador de Novvin') AS name, count(*)::int AS wins
          FROM fallback_board_rooms r JOIN fallback_users u ON u.id::text=r.winner_id
         WHERE r.ranked AND r.visibility='public' AND r.finished_at>=$1 AND r.finished_at<$2
         GROUP BY u.id,u.display_name ORDER BY count(*) DESC, min(r.finished_at), u.id LIMIT $3`, [from, through, limit])
      return rows
    }
    const [top, podium] = await Promise.all([load(period.startsAt, period.endsAt, 100), load(period.lastStart, period.startsAt, 3)])
    return { startsAt: period.startsAt, endsAt: period.endsAt, serverNow: period.serverNow, top, podium }
  })
}
module.exports = { ensureBoardGameTables, registerBoardGameRoutes }
