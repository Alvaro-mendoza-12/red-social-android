'use strict'

const { randomInt, randomUUID } = require('node:crypto')
const engine = () => import('./engine/board-match.js')
const gameNames = { chess: 'ajedrez', checkers: 'damas', ludo: 'Ludo', tic_tac_toe: 'tres en raya',
  connect_four: 'cuatro en línea', gomoku: 'Gomoku', reversi: 'Reversi' }
const kinds = new Set(Object.keys(gameNames))
const positionLimits = { tic_tac_toe: 9, connect_four: 42, gomoku: 225, reversi: 64 }
const supportsAll = req => req.get('X-Novvin-Board-Protocol') === '2'
const supported = (req, kind) => supportsAll(req) || kind !== 'gomoku' && kind !== 'reversi'
const supportsRoom = (req, row) => supported(req, row.kind) && (supportsAll(req) || !row.chess_clock)
const emotes = new Set(['👏', '😄', '🔥', '🤝', '🎉', '😮'])
const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i
const root = '/v1/data/playmatch/board-games'
const invalid = (status, error) => Object.assign(new Error(error), { status, code: error })
const square = value => typeof value === 'string' && /^[a-h][1-8]$/.test(value)

function publishBoardInvite(notification, publish, sendPush) {
  return Promise.allSettled([
    Promise.resolve().then(() => publish(`user:${notification.recipient_id}`, { event: 'notification', notification })),
    Promise.resolve().then(() => sendPush(notification, {
      ...notification.extra_data,
      body: notification.content.charAt(0).toUpperCase() + notification.content.slice(1),
    })),
  ])
}

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
      kind TEXT NOT NULL CHECK (kind IN ('chess', 'checkers', 'ludo', 'tic_tac_toe', 'connect_four', 'gomoku', 'reversi')),
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
  await pool.query(`DO $$
    BEGIN
      PERFORM pg_advisory_xact_lock(19110, 103);
      IF EXISTS (SELECT 1 FROM pg_constraint
          WHERE conrelid = 'fallback_board_rooms'::regclass AND conname = 'fallback_board_rooms_kind_check'
            AND strpos(pg_get_constraintdef(oid), 'reversi') = 0) THEN
        ALTER TABLE fallback_board_rooms DROP CONSTRAINT fallback_board_rooms_kind_check;
        ALTER TABLE fallback_board_rooms ADD CONSTRAINT fallback_board_rooms_kind_check
          CHECK (kind IN ('chess', 'checkers', 'ludo', 'tic_tac_toe', 'connect_four', 'gomoku', 'reversi'));
      END IF;
    END $$`)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS fallback_board_public_waiting
    ON fallback_board_rooms (kind, created_at DESC)
    WHERE status = 'waiting' AND visibility = 'public'`)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS fallback_board_host_waiting
    ON fallback_board_rooms (host_id, created_at DESC) WHERE status = 'waiting'`)
  await pool.query('CREATE INDEX IF NOT EXISTS fallback_board_member ON fallback_board_rooms USING gin (seats)')
  await pool.query(`ALTER TABLE fallback_board_rooms
      ADD COLUMN IF NOT EXISTS room_chat JSONB NOT NULL DEFAULT '[]'::jsonb,
      ADD COLUMN IF NOT EXISTS rematch_seats JSONB NOT NULL DEFAULT '[]'::jsonb,
      ADD COLUMN IF NOT EXISTS rematch_id UUID,
      ADD COLUMN IF NOT EXISTS chess_clock JSONB`)
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
    invited: Boolean(row.invited), clockMinutes: row.chess_clock?.minutes ?? 0,
    ...(row.status === 'finished' && row.seats.includes(actor) ? { result: row.winner_id === null ? 'draw' : row.winner_id === actor ? 'win' : 'loss' } : {}),
  }
}
function snapshot(row, actor) {
  const mine = row.seats.includes(actor)
  return { ...publicRoom(row, actor), revision: row.revision, actions: row.events, reactions: row.reactions,
    players: mine ? row.seats.map((id, seat) => ({ id, seat })) : [],
    ...(mine ? { chat: row.room_chat, rematch: { seats: row.rematch_seats, roomId: row.rematch_id }, clock: row.chess_clock } : {}),
  }
}

async function resolvePlayerNames(pool, players) {
  if (!players.length) return players
  const { rows } = await pool.query(`SELECT id::text, COALESCE(supabase_user_id,id)::text AS profile_id,
    COALESCE(NULLIF(display_name,''),'Jugador de Novvin') AS name
    FROM fallback_users WHERE id=ANY($1::uuid[])`, [players.map(player => player.id)])
  const profiles = new Map(rows.map(row => [row.id, row]))
  return players.map(({ id, seat }) => ({
    id: profiles.get(id)?.profile_id || id, seat, name: profiles.get(id)?.name || 'Jugador de Novvin',
  }))
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
function inRoom(pool, req, fn) {
  const id = req.params.id
  if (!uuid.test(id)) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
  return transaction(pool, async connection => {
    const { rows: [row] } = await connection.query('SELECT * FROM fallback_board_rooms WHERE id=$1 FOR UPDATE', [id])
    if (!row || !supportsRoom(req, row)) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    return fn(connection, row)
  })
}
const createClock = (minutes, start = null) => minutes ? { minutes, remaining: [minutes * 60000, minutes * 60000], seat: 0, startedAt: start } : null
const remainingTime = (clock, at) => Math.max(0, clock.remaining[clock.seat] - Math.max(0, at - clock.startedAt))
const clockExpired = (row, at) => row.status === 'playing' && row.chess_clock?.startedAt != null && remainingTime(row.chess_clock, at) === 0

async function expireClock(connection, row) {
  const clock = row.chess_clock
  const events = [...row.events, { type: 'timeout', seat: clock.seat }]
  clock.remaining[clock.seat] = 0
  clock.startedAt = null
  const { rows: [saved] } = await connection.query(`UPDATE fallback_board_rooms
    SET events=$2::jsonb,chess_clock=$3::jsonb,revision=revision+1,status='finished',winner_id=$4,
        ranked=$5,finished_at=now(),updated_at=now() WHERE id=$1 RETURNING *`,
  [row.id, JSON.stringify(events), JSON.stringify(clock), row.seats[1 - clock.seat], row.events.length >= 2])
  return saved
}

async function seatPlayer(connection, row, actor, at) {
  const seats = [...row.seats, actor]
  const next = seats.length === row.capacity ? 'playing' : 'waiting'
  const clock = next === 'playing' ? createClock(row.chess_clock?.minutes, at) : row.chess_clock
  const { rows: [saved] } = await connection.query(`
    UPDATE fallback_board_rooms SET seats=$2::jsonb,status=$3,chess_clock=$4::jsonb,revision=revision+1,updated_at=now()
    WHERE id=$1 RETURNING *`, [row.id, JSON.stringify(seats), next, clock && JSON.stringify(clock)])
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
  if (Number.isInteger(body.position) && body.position >= 0 && body.position < positionLimits[kind]) {
    return { type: 'move', position: body.position }
  }
  return null
}

function registerBoardGameRoutes(app, { pool, authRequired, getIdentity, publishInvite, now = Date.now, rollDie = () => randomInt(1, 7) }) {
  const route = (verb, suffix, handle) => app[verb](root + suffix, authRequired, async (req, res, next) => {
    try {
      const actor = await getIdentity(req, res)
      if (!actor) return
      const result = await handle(req, actor)
      if (result.room?.players) result.room.players = await resolvePlayerNames(pool, result.room.players)
      if (result.room?.clock) result.room.clock.serverNow = now()
      if (result.notification) {
        const notification = result.notification
        delete result.notification
        void Promise.resolve().then(() => publishInvite(notification)).catch(() => {})
      }
      res.set('Cache-Control', 'no-store').json(result)
    } catch (error) {
      if (Number.isInteger(error.status) && /^BOARD_/.test(error.code || '')) {
        return res.status(error.status).json({ error: error.code })
      }
      next(error)
    }
  })
  route('get', '/rooms', async (req, actor) => {
    const { rows } = await pool.query(`
      SELECT r.id,r.kind,r.capacity,r.status,r.visibility,r.seats,r.host_id,r.created_at,r.chess_clock,r.winner_id,
             (i.room_id IS NOT NULL) AS invited
        FROM fallback_board_rooms r
        JOIN (${accountAliases}) me ON me.id=$1::text::uuid
        LEFT JOIN fallback_board_invites i ON i.room_id=r.id AND i.recipient_id=$1
       WHERE r.status<>'closed' AND ($2 OR (r.kind NOT IN ('gomoku','reversi') AND r.chess_clock IS NULL))
          AND ((r.seats ? $1 AND r.updated_at > now()-interval '7 days')
          OR (r.status='waiting' AND r.created_at > now()-interval '1 day'
              AND (r.visibility='public' OR i.room_id IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM LATERAL (${roomAccounts}) other WHERE ${blocked})))
       ORDER BY (r.seats ? $1) DESC, r.updated_at DESC LIMIT 60`, [actor, supportsAll(req)])
    return { rooms: rows.map(row => publicRoom(row, actor)) }
  })
  function startRoom(automatic) {
    return (req, actor) => transaction(pool, async connection => {
      const { game, capacity, visibility, requestId: id } = req.body || {}
      const minutes = req.body?.clockMinutes ?? 0
      if (!kinds.has(game) || !supported(req, game) || ![2, 3, 4].includes(capacity) || (game !== 'ludo' && capacity !== 2) ||
          ![0, 3, 5, 10].includes(minutes) || (minutes && (game !== 'chess' || !supportsAll(req))) ||
          !['public', 'private'].includes(visibility) || (automatic && visibility !== 'public') ||
          typeof id !== 'string' || !uuid.test(id)) throw invalid(400, 'BOARD_ROOM_INVALID')
      await connection.query('SELECT pg_advisory_xact_lock(hashtext($1))', ['novvin-board-host:' + actor])
      const duplicate = await connection.query('SELECT * FROM fallback_board_rooms WHERE id=$1 AND host_id=$2', [id, actor])
      if (duplicate.rowCount) return { room: snapshot(duplicate.rows[0], actor) }
      if (automatic) {
        await connection.query('SELECT pg_advisory_xact_lock(hashtext($1))', ['novvin-board-match:' + game + ':' + capacity])
        const { rows: [candidate] } = await connection.query(`
          SELECT r.* FROM fallback_board_rooms r JOIN (${accountAliases}) me ON me.id=$1::text::uuid
           WHERE r.kind=$2 AND r.capacity=$3 AND r.visibility='public' AND COALESCE((r.chess_clock->>'minutes')::int,0)=$4
             AND ((r.status='waiting' AND r.created_at > now()-interval '1 day')
                  OR (r.status='playing' AND r.seats ? $1 AND r.updated_at > now()-interval '1 day'))
             AND NOT EXISTS(SELECT 1 FROM LATERAL (${roomAccounts}) other WHERE ${blocked})
           ORDER BY (r.seats ? $1) DESC, r.created_at
           LIMIT 1 FOR UPDATE OF r`, [actor, game, capacity, minutes])
        if (candidate) return candidate.seats.includes(actor) ? { room: snapshot(candidate, actor) } : seatPlayer(connection, candidate, actor, now())
      }
      const { rows: [room] } = await connection.query(`
        INSERT INTO fallback_board_rooms(id,kind,capacity,visibility,host_id,seats,chess_clock)
        SELECT $1,$2,$3,$4,$5,$6::jsonb,$7::jsonb WHERE 5>(
          SELECT count(*) FROM fallback_board_rooms
          WHERE host_id=$5 AND status='waiting' AND created_at > now()-interval '1 day')
        ON CONFLICT(id) DO NOTHING RETURNING *`, [id, game, capacity, visibility, actor, JSON.stringify([actor]), minutes ? JSON.stringify(createClock(minutes)) : null])
      if (!room) throw invalid(429, 'BOARD_ROOM_LIMIT')
      return { room: snapshot(room, actor) }
    })
  }
  route('post', '/rooms', startRoom(false))
  route('post', '/match', startRoom(true))
  route('post', '/rooms/:id/join', (req, actor) => inRoom(pool, req, async (connection, row) => {
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
    return seatPlayer(connection, row, actor, now())
  }))
  route('post', '/rooms/:id/invite', (req, actor) => inRoom(pool, req, async (connection, row) => {
    if (row.host_id !== actor) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    const recipient = req.body?.recipientId
    if (typeof recipient !== 'string' || !uuid.test(recipient) || row.seats.includes(recipient) || row.status !== 'waiting' || row.visibility !== 'private') {
      throw invalid(400, 'BOARD_INVITE_INVALID')
    }
    const matched = await connection.query(`
      SELECT other.id FROM (${accountAliases}) me, (${accountAliases}) other
       WHERE me.id=$1 AND other.id=$2 AND ($3 OR ${friends}) AND NOT ${blocked}`, [actor, recipient, supportsAll(req)])
    if (matched.rowCount !== 1) throw invalid(404, 'BOARD_PLAYER_NOT_FOUND')
    const count = await connection.query(`SELECT count(*) AS count, bool_or(recipient_id=$2) AS already
      FROM fallback_board_invites WHERE room_id=$1`, [row.id, recipient])
    if (count.rows[0].already) return { invited: true }
    if (Number(count.rows[0].count) >= 20) throw invalid(429, 'BOARD_INVITES_LIMIT')
    await connection.query('SELECT pg_advisory_xact_lock(hashtext($1))', ['novvin-board-inviter:' + actor])
    const { rows: [limits] } = await connection.query(`SELECT count(*) AS sent,
        count(*) FILTER (WHERE i.recipient_id=$2) AS sent_to_recipient
      FROM fallback_board_invites i JOIN fallback_board_rooms r ON r.id=i.room_id
      WHERE r.host_id=$1 AND i.created_at > now()-interval '1 hour'`, [actor, recipient])
    if (Number(limits.sent) >= 30 || Number(limits.sent_to_recipient) >= 6) throw invalid(429, 'BOARD_INVITES_LIMIT')
    await connection.query(`
      INSERT INTO fallback_board_invites(room_id,recipient_id) VALUES($1,$2)
      ON CONFLICT (room_id,recipient_id) DO NOTHING`, [row.id, matched.rows[0].id])
    if (!publishInvite) return { invited: true }
    const { rows: [notification] } = await connection.query(`INSERT INTO fallback_notifications
      (recipient_id,actor_id,actor_name,type,target_id,target_type,content,extra_data)
      SELECT COALESCE(recipient.supabase_user_id,recipient.id)::text, COALESCE(actor.supabase_user_id,actor.id)::text,
        COALESCE(NULLIF(actor.display_name,''),'Una amistad de Novvin'), 'board_invite', $3, 'board_room',
        $5, jsonb_build_object('game',$4::text)
      FROM fallback_users recipient, fallback_users actor WHERE recipient.id=$1 AND actor.id=$2
      RETURNING *`, [recipient, actor, row.id, row.kind, `te invitó a jugar ${gameNames[row.kind]}.`])
    return { invited: true, notification }
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
  route('post', '/rooms/:id/players', (req, actor) => inRoom(pool, req, async (connection, row) => {
    if (row.host_id !== actor || row.visibility !== 'private' || row.status !== 'waiting') throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    const query = typeof req.body?.query === 'string' ? req.body.query.trim().slice(0, 80).toLowerCase() : ''
    if (query.length < 2) return { friends: [] }
    const { rows } = await connection.query(`SELECT other.id, u.display_name AS name,
      EXISTS(SELECT 1 FROM fallback_board_invites i WHERE i.room_id=$2 AND i.recipient_id=other.id::text) AS invited
      FROM (${accountAliases}) me, (${accountAliases}) other JOIN fallback_users u ON u.id=other.id
      WHERE me.id=$1 AND NOT (other.id=ANY($3::uuid[])) AND NOT ${blocked}
        AND strpos(lower(COALESCE(u.display_name,'')),$4)>0
      ORDER BY lower(u.display_name), other.id LIMIT 20`, [actor, row.id, row.seats, query])
    return { friends: rows }
  }))
  route('post', '/rooms/:id/close', (req, actor) => inRoom(pool, req, async (connection, row) => {
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
    if (!room || !supportsRoom(req, room)) throw invalid(404, 'BOARD_ROOM_NOT_FOUND')
    if (clockExpired(room, now())) return inRoom(pool, req, async (connection, latest) => {
      requireMember(latest, actor)
      return { room: snapshot(clockExpired(latest, now()) ? await expireClock(connection, latest) : latest, actor) }
    })
    return { room: snapshot(room, actor) }
  })
  async function requireUnblockedTable(connection, row) {
    const denied = await connection.query(`SELECT 1 FROM (${accountAliases}) me, (${accountAliases}) other
      WHERE me.id=ANY($1::uuid[]) AND other.id=ANY($1::uuid[]) AND me.id<>other.id AND ${blocked} LIMIT 1`, [row.seats])
    if (denied.rowCount) throw invalid(409, 'BOARD_SOCIAL_UNAVAILABLE')
  }
  route('post', '/rooms/:id/chat', (req, actor) => inRoom(pool, req, async (connection, row) => {
    const seat = requireMember(row, actor)
    if (row.status !== 'playing') throw invalid(409, 'BOARD_ROOM_NOT_PLAYING')
    const text = typeof req.body?.text === 'string' ? req.body.text.trim() : ''
    const id = req.body?.messageId
    if (!text || text.length > 240 || typeof id !== 'string' || !uuid.test(id)) throw invalid(422, 'BOARD_CHAT_INVALID')
    await requireUnblockedTable(connection, row)
    const recent = row.room_chat
    if (recent.some(message => message.id === id && message.seat === seat)) return { room: snapshot(row, actor) }
    const at = Date.now()
    if (recent.some(message => message.seat === seat && at - message.at < 1200)) throw invalid(429, 'BOARD_CHAT_LIMIT')
    const { rows: [saved] } = await connection.query(`UPDATE fallback_board_rooms SET room_chat=$2::jsonb
      WHERE id=$1 RETURNING *`, [row.id, JSON.stringify([...recent, { id, text, seat, at }].slice(-24))])
    return { room: snapshot(saved, actor) }
  }))
  route('post', '/rooms/:id/rematch', (req, actor) => inRoom(pool, req, async (connection, row) => {
    const seat = requireMember(row, actor)
    if (row.status !== 'finished') throw invalid(409, 'BOARD_REMATCH_UNAVAILABLE')
    await requireUnblockedTable(connection, row)
    if (row.rematch_id) return { room: snapshot(row, actor) }
    const votes = [...new Set([...row.rematch_seats, seat])]
    let id = null
    if (votes.length === row.seats.length) {
      id = randomUUID()
      const seats = [...row.seats.slice(1), row.seats[0]]
      const clock = createClock(row.chess_clock?.minutes, now())
      await connection.query(`INSERT INTO fallback_board_rooms (id,kind,capacity,visibility,host_id,seats,status,chess_clock)
        VALUES ($1,$2,$3,'private',$4,$5::jsonb,'playing',$6::jsonb)`, [id, row.kind, row.capacity, seats[0], JSON.stringify(seats), clock && JSON.stringify(clock)])
    }
    const { rows: [saved] } = await connection.query(`UPDATE fallback_board_rooms SET rematch_seats=$2::jsonb,rematch_id=$3
      WHERE id=$1 RETURNING *`, [row.id, JSON.stringify(votes), id])
    return { room: snapshot(saved, actor) }
  }))
  route('post', '/rooms/:id/actions', (req, actor) => inRoom(pool, req, async (connection, row) => {
    const seat = requireMember(row, actor)
    const body = req.body || {}
    const at = now()
    if (clockExpired(row, at)) return { room: snapshot(await expireClock(connection, row), actor) }
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
    const clock = row.chess_clock
    if (clock) {
      clock.remaining[seat] = remainingTime(clock, at)
      clock.seat = boardTurn(row.kind, game)
      clock.startedAt = game.outcome ? null : at
    }
    const { rows: [saved] } = await connection.query(`
      UPDATE fallback_board_rooms SET events=$2::jsonb,revision=revision+1,status=$3,winner_id=$4,chess_clock=$6::jsonb,
          ranked=$3='finished' AND $5,
          updated_at=now(), finished_at=CASE WHEN $3='finished' THEN now() ELSE NULL END
      WHERE id=$1 RETURNING *`, [row.id, JSON.stringify([...row.events, action]), status, winner,
      action.type !== 'resign' || row.events.length >= 14, clock && JSON.stringify(clock)])
    return { room: snapshot(saved, actor) }
  }))
  route('post', '/rooms/:id/reactions', (req, actor) => inRoom(pool, req, async (connection, row) => {
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
module.exports = { ensureBoardGameTables, registerBoardGameRoutes, publishBoardInvite }
