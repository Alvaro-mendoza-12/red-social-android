const crypto = require('crypto')
const { publishRealtimeEvent } = require('./realtime-publish')
const { createFixedWindowRateLimiter } = require('./request-security')

const LIVE_DRAW_GUESS_FLAG = 'LIVE_DRAW_GUESS_ENABLED'
const DRAW_CHANNEL_PREFIX = 'live-game:'
const MAX_GUESSES_PER_PLAYER_PER_ROUND = 24
const DRAWER_ASSIST_POINTS = 20

// The client never supplies prompts. This versioned, reviewed allowlist is the
// sole source of words for v1 games.
const SAFE_PROMPTS = Object.freeze([
  { key: 'arcoiris', label: 'Arcoíris', answers: ['arcoiris'] },
  { key: 'ballena', label: 'Ballena', answers: ['ballena'] },
  { key: 'barco', label: 'Barco', answers: ['barco'] },
  { key: 'bicicleta', label: 'Bicicleta', answers: ['bicicleta', 'bici'] },
  { key: 'camara', label: 'Cámara', answers: ['camara'] },
  { key: 'casa', label: 'Casa', answers: ['casa'] },
  { key: 'castillo', label: 'Castillo', answers: ['castillo'] },
  { key: 'cohete', label: 'Cohete', answers: ['cohete'] },
  { key: 'colibri', label: 'Colibrí', answers: ['colibri'] },
  { key: 'estrella', label: 'Estrella', answers: ['estrella'] },
  { key: 'flor', label: 'Flor', answers: ['flor'] },
  { key: 'guitarra', label: 'Guitarra', answers: ['guitarra'] },
  { key: 'helado', label: 'Helado', answers: ['helado'] },
  { key: 'libro', label: 'Libro', answers: ['libro'] },
  { key: 'luna', label: 'Luna', answers: ['luna'] },
  { key: 'manzana', label: 'Manzana', answers: ['manzana'] },
  { key: 'mariposa', label: 'Mariposa', answers: ['mariposa'] },
  { key: 'montana', label: 'Montaña', answers: ['montana'] },
  { key: 'nube', label: 'Nube', answers: ['nube'] },
  { key: 'pez', label: 'Pez', answers: ['pez', 'pescado'] },
  { key: 'reloj', label: 'Reloj', answers: ['reloj'] },
  { key: 'sombrero', label: 'Sombrero', answers: ['sombrero'] },
  { key: 'tren', label: 'Tren', answers: ['tren'] },
  { key: 'venado', label: 'Venado', answers: ['venado', 'ciervo'] },
])

const PROMPT_BY_KEY = new Map(SAFE_PROMPTS.map((prompt) => [prompt.key, prompt]))
const PROHIBITED_TERMS = new Set([
  'idiota', 'imbecil', 'estupido', 'estupida', 'maldito', 'maldita',
  'racista', 'terrorista', 'pornografia', 'suicidio',
])
const DRAW_TOOLS = new Set(['pen', 'eraser'])
const DRAW_COLORS = new Set([
  '#111111', '#ffffff', '#e53935', '#fb8c00', '#fdd835',
  '#43a047', '#1e88e5', '#5e35b1', '#8e24aa', '#6d4c41',
])

const LIVE_DRAW_GUESS_SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS fallback_live_draw_games (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
  host_user_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'lobby' CHECK (status IN ('lobby', 'active', 'completed', 'canceled')),
  max_players SMALLINT NOT NULL DEFAULT 8 CHECK (max_players BETWEEN 2 AND 8),
  total_rounds SMALLINT NOT NULL DEFAULT 3 CHECK (total_rounds BETWEEN 1 AND 12),
  round_seconds SMALLINT NOT NULL DEFAULT 75 CHECK (round_seconds BETWEEN 30 AND 180),
  current_round_number SMALLINT NOT NULL DEFAULT 0 CHECK (current_round_number BETWEEN 0 AND 12),
  drawer_offset SMALLINT NOT NULL DEFAULT 0 CHECK (drawer_offset BETWEEN 0 AND 7),
  request_nonce TEXT NOT NULL CHECK (char_length(request_nonce) BETWEEN 16 AND 160),
  start_nonce TEXT CHECK (start_nonce IS NULL OR char_length(start_nonce) BETWEEN 16 AND 160),
  cancel_nonce TEXT CHECK (cancel_nonce IS NULL OR char_length(cancel_nonce) BETWEEN 16 AND 160),
  started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (live_id, request_nonce)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_draw_one_open_game
  ON fallback_live_draw_games(live_id) WHERE status IN ('lobby', 'active');
CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_games_due
  ON fallback_live_draw_games(status, updated_at) WHERE status = 'active';
CREATE TABLE IF NOT EXISTS fallback_live_draw_players (
  game_id UUID NOT NULL REFERENCES fallback_live_draw_games(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL,
  seat_index SMALLINT NOT NULL CHECK (seat_index BETWEEN 0 AND 7),
  status TEXT NOT NULL DEFAULT 'invited' CHECK (status IN ('invited', 'accepted', 'declined', 'left')),
  score BIGINT NOT NULL DEFAULT 0 CHECK (score >= 0),
  acceptance_nonce TEXT CHECK (acceptance_nonce IS NULL OR char_length(acceptance_nonce) BETWEEN 16 AND 160),
  accepted_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (game_id, user_id), UNIQUE (game_id, seat_index)
);
CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_players_score
  ON fallback_live_draw_players(game_id, score DESC, accepted_at ASC) WHERE status = 'accepted';
CREATE TABLE IF NOT EXISTS fallback_live_draw_rounds (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id UUID NOT NULL REFERENCES fallback_live_draw_games(id) ON DELETE CASCADE,
  round_number SMALLINT NOT NULL CHECK (round_number BETWEEN 1 AND 12),
  drawer_user_id TEXT NOT NULL,
  prompt_key TEXT NOT NULL CHECK (char_length(prompt_key) BETWEEN 2 AND 64),
  prompt_version SMALLINT NOT NULL DEFAULT 1 CHECK (prompt_version > 0),
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'expired', 'canceled')),
  last_draw_sequence BIGINT NOT NULL DEFAULT -1 CHECK (last_draw_sequence >= -1),
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), ends_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE (game_id, round_number),
  UNIQUE (game_id, id),
  FOREIGN KEY (game_id, drawer_user_id)
    REFERENCES fallback_live_draw_players(game_id, user_id) ON DELETE CASCADE,
  CHECK (ends_at > started_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_draw_one_active_round
  ON fallback_live_draw_rounds(game_id) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_rounds_due
  ON fallback_live_draw_rounds(status, ends_at) WHERE status = 'active';
CREATE TABLE IF NOT EXISTS fallback_live_draw_guesses (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id UUID NOT NULL REFERENCES fallback_live_draw_games(id) ON DELETE CASCADE,
  round_id UUID NOT NULL REFERENCES fallback_live_draw_rounds(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL CHECK (char_length(idempotency_key) BETWEEN 16 AND 160),
  answer_length SMALLINT NOT NULL CHECK (answer_length BETWEEN 1 AND 72),
  correct BOOLEAN NOT NULL, awarded_points INTEGER NOT NULL DEFAULT 0 CHECK (awarded_points BETWEEN 0 AND 1000),
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE (game_id, user_id, idempotency_key),
  FOREIGN KEY (game_id, round_id)
    REFERENCES fallback_live_draw_rounds(game_id, id) ON DELETE CASCADE,
  FOREIGN KEY (game_id, user_id)
    REFERENCES fallback_live_draw_players(game_id, user_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_draw_one_correct_guess
  ON fallback_live_draw_guesses(round_id, lower(user_id)) WHERE correct = TRUE;
CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_guesses_round
  ON fallback_live_draw_guesses(round_id, submitted_at ASC);
CREATE TABLE IF NOT EXISTS fallback_live_draw_audit (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id UUID NOT NULL REFERENCES fallback_live_draw_games(id) ON DELETE CASCADE,
  round_id UUID REFERENCES fallback_live_draw_rounds(id) ON DELETE SET NULL,
  actor_user_id TEXT,
  event_type TEXT NOT NULL CHECK (event_type IN (
    'game_created', 'player_accepted', 'game_started', 'round_started',
    'guess_correct', 'round_completed', 'game_completed', 'game_canceled'
  )),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (NOT (metadata ?| ARRAY['prompt', 'prompt_key', 'guess', 'answer']))
);
CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_audit_game
  ON fallback_live_draw_audit(game_id, created_at ASC);
`

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function cleanText(value, maxLength) {
  return String(value || '').trim().slice(0, maxLength)
}

function validUuid(value) {
  const text = normalizeId(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function validIdempotencyKey(value) {
  const text = cleanText(value, 161)
  return text.length >= 16 && text.length <= 160 && /^[A-Za-z0-9._:-]+$/.test(text) ? text : null
}

function intBetween(value, minimum, maximum, fallback) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(maximum, Math.max(minimum, parsed))
}

function drawGuessEnabled() {
  return /^(1|true|yes|on)$/i.test(String(process.env[LIVE_DRAW_GUESS_FLAG] || '').trim())
}

function requireDrawGuessFeature(res) {
  if (drawGuessEnabled()) return true
  res.status(404).json({ error: 'LIVE_DRAW_GUESS_DISABLED' })
  return false
}

function normalizeGuess(value) {
  return String(value || '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase('es')
    .replace(/[^\p{L}\p{N}\s-]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function containsPii(value) {
  const text = String(value || '')
  const compactDigits = text.replace(/\D/g, '')
  return /(?:https?:\/\/|www\.)/i.test(text) ||
    /\b[^\s@]+@[^\s@]+\.[^\s@]+\b/i.test(text) ||
    /(^|\s)@[\p{L}\p{N}_.-]{2,}/u.test(text) ||
    compactDigits.length >= 7
}

function validateGuessText(value, blockedWords = []) {
  const raw = String(value || '').trim()
  if (!raw || Array.from(raw).length > 72) return { ok: false, error: 'LIVE_DRAW_GUESS_TEXT_INVALID' }
  if (containsPii(raw)) return { ok: false, error: 'LIVE_DRAW_GUESS_PII_BLOCKED' }
  const normalized = normalizeGuess(raw)
  if (!normalized) return { ok: false, error: 'LIVE_DRAW_GUESS_TEXT_INVALID' }
  const words = normalized.split(' ')
  const configuredBlockedWords = (Array.isArray(blockedWords) ? blockedWords : [])
    .map(normalizeGuess)
    .filter(Boolean)
  if (
    words.some((word) => PROHIBITED_TERMS.has(word)) ||
    configuredBlockedWords.some((word) =>
      normalized === word ||
      normalized.startsWith(`${word} `) ||
      normalized.endsWith(` ${word}`) ||
      normalized.includes(` ${word} `),
    )
  ) {
    return { ok: false, error: 'LIVE_DRAW_GUESS_MODERATION_BLOCKED' }
  }
  return { ok: true, normalized, answerLength: Array.from(raw).length }
}

function evaluateGuess(promptKey, value) {
  const validation = validateGuessText(value)
  if (!validation.ok) return validation
  const prompt = PROMPT_BY_KEY.get(normalizeId(promptKey))
  if (!prompt) return { ok: false, error: 'LIVE_DRAW_PROMPT_INVALID' }
  return {
    ...validation,
    correct: prompt.answers.some((answer) => normalizeGuess(answer) === validation.normalized),
  }
}

function numericInRange(value, minimum, maximum) {
  return typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum
}

function validateDrawPacket(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: 'LIVE_DRAW_PACKET_INVALID' }
  }
  let encodedBytes = Number.POSITIVE_INFINITY
  try {
    encodedBytes = Buffer.byteLength(JSON.stringify(value), 'utf8')
  } catch (_error) {
    return { ok: false, error: 'LIVE_DRAW_PACKET_INVALID' }
  }
  if (encodedBytes > 16 * 1024) return { ok: false, error: 'LIVE_DRAW_PACKET_TOO_LARGE' }
  const sequence = Number(value.sequence)
  const points = Array.isArray(value.points) ? value.points : []
  const tool = normalizeId(value.tool || 'pen')
  const color = cleanText(value.color || '#111111', 7).toLowerCase()
  const width = Number(value.width ?? 0.012)
  if (!Number.isSafeInteger(sequence) || sequence < 0 || sequence > Number.MAX_SAFE_INTEGER) {
    return { ok: false, error: 'LIVE_DRAW_SEQUENCE_INVALID' }
  }
  if (!DRAW_TOOLS.has(tool) || !DRAW_COLORS.has(color) || !numericInRange(width, 0.002, 0.08)) {
    return { ok: false, error: 'LIVE_DRAW_STYLE_INVALID' }
  }
  if (points.length < 1 || points.length > 64) return { ok: false, error: 'LIVE_DRAW_POINTS_INVALID' }
  const sanitizedPoints = []
  for (const point of points) {
    if (!point || typeof point !== 'object' ||
        !numericInRange(point.x, 0, 1) || !numericInRange(point.y, 0, 1)) {
      return { ok: false, error: 'LIVE_DRAW_POINT_INVALID' }
    }
    const pressure = point.pressure === undefined ? 1 : Number(point.pressure)
    const deltaMs = point.deltaMs === undefined ? 0 : Number(point.deltaMs)
    if (!numericInRange(pressure, 0, 1) || !Number.isInteger(deltaMs) || deltaMs < 0 || deltaMs > 2_000) {
      return { ok: false, error: 'LIVE_DRAW_POINT_INVALID' }
    }
    sanitizedPoints.push({
      x: Number(point.x.toFixed(4)),
      y: Number(point.y.toFixed(4)),
      pressure: Number(pressure.toFixed(3)),
      deltaMs,
    })
  }
  return {
    ok: true,
    packet: { sequence, tool, color, width: Number(width.toFixed(4)), points: sanitizedPoints },
  }
}

function calculateGuessScore({ nowMs, endsAtMs, roundSeconds }) {
  const duration = intBetween(roundSeconds, 30, 180, 75)
  const remainingSeconds = Math.max(0, Math.min(duration, Math.ceil((endsAtMs - nowMs) / 1000)))
  return Math.min(1000, 100 + (remainingSeconds * 5))
}

function choosePrompt(excludedKeys = [], randomInt = crypto.randomInt) {
  const excluded = new Set((excludedKeys || []).map(normalizeId))
  const candidates = SAFE_PROMPTS.filter((prompt) => !excluded.has(prompt.key))
  const source = candidates.length > 0 ? candidates : SAFE_PROMPTS
  return source[randomInt(source.length)]
}

function acceptedPlayers(players) {
  return (players || [])
    .filter((player) => player.status === 'accepted')
    .sort((left, right) => Number(left.seat_index) - Number(right.seat_index))
}

function chooseDrawer(players, roundNumber, drawerOffset = 0) {
  const eligible = acceptedPlayers(players)
  if (eligible.length < 2) return null
  const index = (Math.max(0, Number(drawerOffset)) + Math.max(0, Number(roundNumber) - 1)) % eligible.length
  return eligible[index]
}

function publicRound(round) {
  if (!round) return null
  return {
    id: round.id,
    game_id: round.game_id,
    round_number: Number(round.round_number),
    drawer_user_id: round.drawer_user_id,
    status: round.status,
    last_draw_sequence: Number(round.last_draw_sequence ?? -1),
    started_at: round.started_at,
    ends_at: round.ends_at,
    completed_at: round.completed_at,
  }
}

function serializeGameState({ game, players, round, viewerUserId }) {
  const safeRound = publicRound(round)
  const isDrawer = safeRound && normalizeId(safeRound.drawer_user_id) === normalizeId(viewerUserId)
  const prompt = isDrawer ? PROMPT_BY_KEY.get(normalizeId(round.prompt_key)) : null
  const orderedPlayers = (players || [])
    .map((player) => ({
      user_id: player.user_id,
      seat_index: Number(player.seat_index),
      score: Number(player.score || 0),
      status: player.status,
    }))
    .sort((left, right) => {
      if (left.status === 'accepted' && right.status !== 'accepted') return -1
      if (left.status !== 'accepted' && right.status === 'accepted') return 1
      return right.score - left.score || left.seat_index - right.seat_index
    })
  let acceptedRank = 0
  return {
    game: {
      id: game.id,
      live_id: game.live_id,
      host_user_id: game.host_user_id,
      status: game.status,
      max_players: Number(game.max_players),
      total_rounds: Number(game.total_rounds),
      round_seconds: Number(game.round_seconds),
      current_round_number: Number(game.current_round_number),
      started_at: game.started_at,
      completed_at: game.completed_at,
    },
    players: orderedPlayers.map((player) => ({
      ...player,
      rank: player.status === 'accepted' ? ++acceptedRank : null,
    })),
    round: safeRound,
    private_prompt: prompt ? { key: prompt.key, label: prompt.label, version: 1 } : null,
  }
}

function identityValues(identity) {
  if (!identity) return []
  const source = identity.ids instanceof Set ? [...identity.ids] : Array.isArray(identity.ids) ? identity.ids : []
  return [...new Set([
    ...source,
    ...(Array.isArray(identity.values) ? identity.values : []),
    identity.displayUserId,
    identity.preferredUserId,
    identity.localUserId,
  ].map(normalizeId).filter(Boolean))]
}

function identityOwns(userId, identity) {
  return identityValues(identity).includes(normalizeId(userId))
}

function displayUserId(identity) {
  return normalizeId(identity?.displayUserId || identity?.preferredUserId || identityValues(identity)[0])
}

function createDrawGuessRateLimiters(now) {
  return {
    control: createFixedWindowRateLimiter({ limit: 30, windowMs: 60_000, now }),
    guess: createFixedWindowRateLimiter({ limit: 12, windowMs: 10_000, now }),
    draw: createFixedWindowRateLimiter({ limit: 240, windowMs: 10_000, now }),
  }
}

function applyRateLimit(res, consume, key) {
  const result = consume(key)
  res.set('x-ratelimit-limit', String(result.limit))
  res.set('x-ratelimit-remaining', String(result.remaining))
  if (result.allowed) return true
  res.set('retry-after', String(result.retryAfterSeconds))
  res.status(429).json({ error: 'LIVE_DRAW_RATE_LIMITED' })
  return false
}

async function ensureLiveDrawGuessTables(pool) {
  await pool.query(LIVE_DRAW_GUESS_SCHEMA_SQL)
}

async function liveRow(client, liveId, lock = false) {
  const { rows } = await client.query(
    `SELECT id::text, host_user_id, is_active, blocked_words
     FROM fallback_live_streams
     WHERE id = $1::uuid
     LIMIT 1${lock ? ' FOR UPDATE' : ''}`,
    [liveId],
  )
  return rows[0] || null
}

async function gameRow(client, liveId, gameId, lock = false) {
  const { rows } = await client.query(
    `SELECT id::text, live_id::text, host_user_id, status, max_players, total_rounds,
            round_seconds, current_round_number, drawer_offset, request_nonce,
            start_nonce, cancel_nonce, started_at, completed_at, created_at, updated_at
     FROM fallback_live_draw_games
     WHERE id = $1::uuid AND live_id = $2::uuid
     LIMIT 1${lock ? ' FOR UPDATE' : ''}`,
    [gameId, liveId],
  )
  return rows[0] || null
}

async function playerRows(client, gameId) {
  const { rows } = await client.query(
    `SELECT game_id::text, user_id, seat_index, status, score, acceptance_nonce,
            accepted_at, created_at, updated_at
     FROM fallback_live_draw_players
     WHERE game_id = $1::uuid
     ORDER BY seat_index ASC`,
    [gameId],
  )
  return rows
}

async function currentRoundRow(client, gameId, lock = false) {
  const { rows } = await client.query(
    `SELECT id::text, game_id::text, round_number, drawer_user_id, prompt_key,
            prompt_version, status, last_draw_sequence, started_at, ends_at,
            completed_at, created_at, updated_at
     FROM fallback_live_draw_rounds
     WHERE game_id = $1::uuid AND status = 'active'
     ORDER BY round_number DESC
     LIMIT 1${lock ? ' FOR UPDATE' : ''}`,
    [gameId],
  )
  return rows[0] || null
}

async function stateForUser(client, game, viewerUserId) {
  // Keep transactional reads ordered on a single pg client. node-postgres can
  // queue concurrent calls, but explicit ordering is safer across adapters.
  const players = await playerRows(client, game.id)
  const round = await currentRoundRow(client, game.id)
  return serializeGameState({ game, players, round, viewerUserId })
}

async function acceptedGamePlayer(client, gameId, userId, lock = false) {
  const { rows } = await client.query(
    `SELECT game_id::text, user_id, seat_index, status, score, acceptance_nonce,
            accepted_at, created_at, updated_at
     FROM fallback_live_draw_players
     WHERE game_id = $1::uuid AND lower(user_id) = lower($2)
     LIMIT 1${lock ? ' FOR UPDATE' : ''}`,
    [gameId, userId],
  )
  return rows[0] || null
}

async function isConnectedParticipant(client, liveId, userId, isHost = false) {
  if (isHost) return true
  const { rows } = await client.query(
    `SELECT 1
     FROM fallback_live_participants
     WHERE live_id = $1::uuid AND lower(user_id) = lower($2) AND state = 'connected'
     LIMIT 1`,
    [liveId, userId],
  )
  return Boolean(rows[0])
}

async function appendAudit(client, { gameId, roundId = null, actorUserId = null, eventType, metadata = {} }) {
  const safeMetadata = { ...metadata }
  for (const forbidden of ['prompt', 'prompt_key', 'guess', 'answer']) delete safeMetadata[forbidden]
  await client.query(
    `INSERT INTO fallback_live_draw_audit(game_id, round_id, actor_user_id, event_type, metadata)
     VALUES ($1::uuid, $2::uuid, $3, $4, $5::jsonb)`,
    [gameId, roundId, actorUserId, eventType, JSON.stringify(safeMetadata)],
  )
}

async function createNextRound(client, game, players) {
  const roundNumber = Number(game.current_round_number) + 1
  const drawer = chooseDrawer(players, roundNumber, game.drawer_offset)
  if (!drawer) throw Object.assign(new Error('LIVE_DRAW_NOT_ENOUGH_PLAYERS'), { code: 'LIVE_DRAW_NOT_ENOUGH_PLAYERS' })
  const prior = await client.query(
    `SELECT prompt_key FROM fallback_live_draw_rounds
     WHERE game_id = $1::uuid ORDER BY round_number DESC LIMIT 3`,
    [game.id],
  )
  const prompt = choosePrompt(prior.rows.map((row) => row.prompt_key))
  const { rows } = await client.query(
    `INSERT INTO fallback_live_draw_rounds(
       game_id, round_number, drawer_user_id, prompt_key, ends_at
     ) VALUES ($1::uuid, $2, $3, $4, NOW() + ($5::int * INTERVAL '1 second'))
     RETURNING id::text, game_id::text, round_number, drawer_user_id, prompt_key,
               prompt_version, status, last_draw_sequence, started_at, ends_at,
               completed_at, created_at, updated_at`,
    [game.id, roundNumber, drawer.user_id, prompt.key, Number(game.round_seconds)],
  )
  await client.query(
    `UPDATE fallback_live_draw_games
     SET current_round_number = $2, updated_at = NOW()
     WHERE id = $1::uuid`,
    [game.id, roundNumber],
  )
  game.current_round_number = roundNumber
  await appendAudit(client, {
    gameId: game.id,
    roundId: rows[0].id,
    eventType: 'round_started',
    metadata: { round_number: roundNumber, drawer_user_id: drawer.user_id },
  })
  return rows[0]
}

async function finishOrAdvanceRound(client, game, { force = false, completionReason = 'timer' } = {}) {
  const round = await currentRoundRow(client, game.id, true)
  if (!round) return { changed: false, gameCompleted: game.status === 'completed', round: null }
  const timing = await client.query(
    `SELECT ($1::timestamptz <= NOW()) AS expired`,
    [round.ends_at],
  )
  const expired = Boolean(timing.rows[0]?.expired)
  if (!force && !expired) return { changed: false, gameCompleted: false, round }
  const roundStatus = expired && completionReason === 'timer' ? 'expired' : 'completed'
  await client.query(
    `UPDATE fallback_live_draw_rounds
     SET status = $2, completed_at = NOW(), updated_at = NOW()
     WHERE id = $1::uuid AND status = 'active'`,
    [round.id, roundStatus],
  )
  await appendAudit(client, {
    gameId: game.id,
    roundId: round.id,
    eventType: 'round_completed',
    metadata: { round_number: Number(round.round_number), reason: completionReason },
  })
  if (Number(round.round_number) >= Number(game.total_rounds)) {
    const updated = await client.query(
      `UPDATE fallback_live_draw_games
       SET status = 'completed', completed_at = NOW(), updated_at = NOW()
       WHERE id = $1::uuid AND status = 'active'
       RETURNING id::text, live_id::text, host_user_id, status, max_players, total_rounds,
                 round_seconds, current_round_number, drawer_offset, request_nonce,
                 start_nonce, cancel_nonce, started_at, completed_at, created_at, updated_at`,
      [game.id],
    )
    Object.assign(game, updated.rows[0] || { status: 'completed' })
    await appendAudit(client, {
      gameId: game.id,
      eventType: 'game_completed',
      metadata: { rounds: Number(game.total_rounds) },
    })
    return { changed: true, gameCompleted: true, round: null, completedRound: publicRound(round) }
  }
  const players = acceptedPlayers(await playerRows(client, game.id))
  const nextRound = await createNextRound(client, game, players)
  return { changed: true, gameCompleted: false, round: nextRound, completedRound: publicRound(round) }
}

function sanitizePublicEvent(value) {
  if (Array.isArray(value)) return value.map(sanitizePublicEvent)
  if (!value || typeof value !== 'object') return value
  const forbidden = new Set(['prompt', 'prompt_key', 'private_prompt', 'guess', 'answer'])
  return Object.fromEntries(Object.entries(value)
    .filter(([key]) => !forbidden.has(normalizeId(key)))
    .map(([key, nested]) => [key, sanitizePublicEvent(nested)]))
}

async function safePublish(publishEvent, liveId, payload) {
  // Defense in depth: public events must never carry a private prompt or guess,
  // even if a future caller accidentally nests it.
  const event = sanitizePublicEvent({ event: 'live_draw_guess', ...payload })
  try {
    await publishEvent(`${DRAW_CHANNEL_PREFIX}${liveId}`, event)
  } catch (_error) {
    // Durable game state remains authoritative. Realtime delivery is best-effort.
  }
}

function registerLiveDrawGuessRoutes(app, {
  pool,
  authRequired,
  getCurrentUser,
  userIdentity,
  publishEvent = publishRealtimeEvent,
  now,
}) {
  const limits = createDrawGuessRateLimiters(now)

  app.post('/v1/data/live/:liveId/games/draw-guess', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      const requestNonce = validIdempotencyKey(req.body?.idempotencyKey || req.body?.idempotency_key)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      if (!requestNonce) return res.status(400).json({ error: 'LIVE_DRAW_IDEMPOTENCY_KEY_REQUIRED' })
      if (!applyRateLimit(res, limits.control, `create:${liveId}:${actorId}`)) return
      const targets = [...new Set((Array.isArray(req.body?.participantIds) ? req.body.participantIds : [])
        .map(normalizeId).filter((id) => id && !identityOwns(id, identity)))].slice(0, 7)
      if (targets.length < 1) return res.status(400).json({ error: 'LIVE_DRAW_PARTICIPANTS_REQUIRED' })
      const totalRounds = intBetween(req.body?.totalRounds, 1, 12, 3)
      const roundSeconds = intBetween(req.body?.roundSeconds, 30, 180, 75)
      const maxPlayers = intBetween(req.body?.maxPlayers, 2, 8, Math.min(8, targets.length + 1))
      if (targets.length + 1 > maxPlayers) return res.status(400).json({ error: 'LIVE_DRAW_TOO_MANY_PARTICIPANTS' })

      client = await pool.connect()
      await client.query('BEGIN')
      const live = await liveRow(client, liveId, true)
      if (!live) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      if (!live.is_active) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_NOT_ACTIVE' })
      }
      if (!identityOwns(live.host_user_id, identity)) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      }
      const existing = await client.query(
        `SELECT id::text, live_id::text, host_user_id, status, max_players, total_rounds,
                round_seconds, current_round_number, drawer_offset, request_nonce,
                start_nonce, cancel_nonce, started_at, completed_at, created_at, updated_at
         FROM fallback_live_draw_games
         WHERE live_id = $1::uuid AND request_nonce = $2 LIMIT 1`,
        [liveId, requestNonce],
      )
      if (existing.rows[0]) {
        const game = existing.rows[0]
        if (!identityOwns(game.host_user_id, identity)) {
          await client.query('ROLLBACK')
          return res.status(409).json({ error: 'LIVE_DRAW_IDEMPOTENCY_CONFLICT' })
        }
        const state = await stateForUser(client, game, actorId)
        await client.query('COMMIT')
        res.setHeader('Cache-Control', 'no-store')
        return res.json({ ...state, idempotent_replay: true })
      }
      const connected = await client.query(
        `SELECT lower(user_id) AS user_id
         FROM fallback_live_participants
         WHERE live_id = $1::uuid AND lower(user_id) = ANY($2::text[]) AND state = 'connected'`,
        [liveId, targets],
      )
      const connectedIds = new Set(connected.rows.map((row) => normalizeId(row.user_id)))
      if (targets.some((target) => !connectedIds.has(target))) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_PARTICIPANT_NOT_CONNECTED' })
      }
      const inserted = await client.query(
        `INSERT INTO fallback_live_draw_games(
           live_id, host_user_id, max_players, total_rounds, round_seconds, request_nonce
         ) VALUES ($1::uuid, $2, $3, $4, $5, $6)
         RETURNING id::text, live_id::text, host_user_id, status, max_players, total_rounds,
                   round_seconds, current_round_number, drawer_offset, request_nonce,
                   start_nonce, cancel_nonce, started_at, completed_at, created_at, updated_at`,
        [liveId, actorId, maxPlayers, totalRounds, roundSeconds, requestNonce],
      )
      const game = inserted.rows[0]
      await client.query(
        `INSERT INTO fallback_live_draw_players(
           game_id, user_id, seat_index, status, acceptance_nonce, accepted_at
         ) VALUES ($1::uuid, $2, 0, 'accepted', $3, NOW())`,
        [game.id, actorId, requestNonce],
      )
      for (let index = 0; index < targets.length; index += 1) {
        await client.query(
          `INSERT INTO fallback_live_draw_players(game_id, user_id, seat_index, status)
           VALUES ($1::uuid, $2, $3, 'invited')`,
          [game.id, targets[index], index + 1],
        )
      }
      await appendAudit(client, {
        gameId: game.id,
        actorUserId: actorId,
        eventType: 'game_created',
        metadata: { invited_count: targets.length, total_rounds: totalRounds, round_seconds: roundSeconds },
      })
      const state = await stateForUser(client, game, actorId)
      await client.query('COMMIT')
      await safePublish(publishEvent, liveId, { type: 'game_created', game_id: game.id })
      res.setHeader('Cache-Control', 'no-store')
      res.status(201).json({ ...state, idempotent_replay: false })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_DRAW_GAME_ALREADY_OPEN' })
      next(error)
    } finally {
      client?.release()
    }
  })

  app.post('/v1/data/live/:liveId/games/draw-guess/:gameId/accept', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      const gameId = validUuid(req.params.gameId)
      const nonce = validIdempotencyKey(req.body?.idempotencyKey || req.body?.idempotency_key)
      if (!liveId || !gameId) return res.status(400).json({ error: 'LIVE_DRAW_GAME_ID_INVALID' })
      if (!nonce) return res.status(400).json({ error: 'LIVE_DRAW_IDEMPOTENCY_KEY_REQUIRED' })
      if (!applyRateLimit(res, limits.control, `accept:${gameId}:${actorId}`)) return
      client = await pool.connect()
      await client.query('BEGIN')
      const live = await liveRow(client, liveId, true)
      const game = await gameRow(client, liveId, gameId, true)
      if (!live || !game) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'LIVE_DRAW_GAME_NOT_FOUND' })
      }
      if (!live.is_active || game.status !== 'lobby') {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_GAME_NOT_ACCEPTING' })
      }
      const player = await acceptedGamePlayer(client, gameId, actorId, true)
      if (!player || !['invited', 'accepted'].includes(player.status)) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_DRAW_INVITATION_REQUIRED' })
      }
      if (!await isConnectedParticipant(client, liveId, actorId, identityOwns(live.host_user_id, identity))) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_PARTICIPANT_NOT_CONNECTED' })
      }
      if (player.status === 'accepted') {
        if (player.acceptance_nonce && player.acceptance_nonce !== nonce) {
          await client.query('ROLLBACK')
          return res.status(409).json({ error: 'LIVE_DRAW_ALREADY_ACCEPTED' })
        }
        const state = await stateForUser(client, game, actorId)
        await client.query('COMMIT')
        return res.json({ ...state, idempotent_replay: true })
      }
      await client.query(
        `UPDATE fallback_live_draw_players
         SET status = 'accepted', acceptance_nonce = $3, accepted_at = NOW(), updated_at = NOW()
         WHERE game_id = $1::uuid AND lower(user_id) = lower($2) AND status = 'invited'`,
        [gameId, actorId, nonce],
      )
      await appendAudit(client, {
        gameId,
        actorUserId: actorId,
        eventType: 'player_accepted',
        metadata: { user_id: actorId },
      })
      const state = await stateForUser(client, game, actorId)
      await client.query('COMMIT')
      await safePublish(publishEvent, liveId, { type: 'player_accepted', game_id: gameId, user_id: actorId })
      res.json({ ...state, idempotent_replay: false })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.post('/v1/data/live/:liveId/games/draw-guess/:gameId/start', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      const gameId = validUuid(req.params.gameId)
      const nonce = validIdempotencyKey(req.body?.idempotencyKey || req.body?.idempotency_key)
      if (!liveId || !gameId) return res.status(400).json({ error: 'LIVE_DRAW_GAME_ID_INVALID' })
      if (!nonce) return res.status(400).json({ error: 'LIVE_DRAW_IDEMPOTENCY_KEY_REQUIRED' })
      if (!applyRateLimit(res, limits.control, `start:${gameId}:${actorId}`)) return
      client = await pool.connect()
      await client.query('BEGIN')
      const live = await liveRow(client, liveId, true)
      const game = await gameRow(client, liveId, gameId, true)
      if (!live || !game) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'LIVE_DRAW_GAME_NOT_FOUND' })
      }
      if (!identityOwns(live.host_user_id, identity) || !identityOwns(game.host_user_id, identity)) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      }
      if (!live.is_active) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_NOT_ACTIVE' })
      }
      if (game.status === 'active') {
        if (game.start_nonce !== nonce) {
          await client.query('ROLLBACK')
          return res.status(409).json({ error: 'LIVE_DRAW_GAME_ALREADY_STARTED' })
        }
        const state = await stateForUser(client, game, actorId)
        await client.query('COMMIT')
        return res.json({ ...state, idempotent_replay: true })
      }
      if (game.status !== 'lobby') {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_GAME_NOT_STARTABLE' })
      }
      const players = acceptedPlayers(await playerRows(client, gameId))
      if (players.length < 2) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_NOT_ENOUGH_PLAYERS' })
      }
      const nonHostIds = players.map((player) => normalizeId(player.user_id)).filter((id) => !identityOwns(id, identity))
      if (nonHostIds.length > 0) {
        const connected = await client.query(
          `SELECT lower(user_id) AS user_id FROM fallback_live_participants
           WHERE live_id = $1::uuid AND lower(user_id) = ANY($2::text[]) AND state = 'connected'`,
          [liveId, nonHostIds],
        )
        const connectedIds = new Set(connected.rows.map((row) => normalizeId(row.user_id)))
        if (nonHostIds.some((id) => !connectedIds.has(id))) {
          await client.query('ROLLBACK')
          return res.status(409).json({ error: 'LIVE_DRAW_PARTICIPANT_NOT_CONNECTED' })
        }
      }
      game.drawer_offset = crypto.randomInt(players.length)
      const updated = await client.query(
        `UPDATE fallback_live_draw_games
         SET status = 'active', start_nonce = $2, drawer_offset = $3,
             started_at = NOW(), updated_at = NOW()
         WHERE id = $1::uuid AND status = 'lobby'
         RETURNING id::text, live_id::text, host_user_id, status, max_players, total_rounds,
                   round_seconds, current_round_number, drawer_offset, request_nonce,
                   start_nonce, cancel_nonce, started_at, completed_at, created_at, updated_at`,
        [gameId, nonce, game.drawer_offset],
      )
      Object.assign(game, updated.rows[0])
      const round = await createNextRound(client, game, players)
      await appendAudit(client, {
        gameId,
        roundId: round.id,
        actorUserId: actorId,
        eventType: 'game_started',
        metadata: { accepted_players: players.length, total_rounds: Number(game.total_rounds) },
      })
      const state = serializeGameState({ game, players, round, viewerUserId: actorId })
      await client.query('COMMIT')
      await safePublish(publishEvent, liveId, {
        type: 'round_started', game_id: gameId, round: publicRound(round),
      })
      res.setHeader('Cache-Control', 'no-store')
      res.status(201).json({ ...state, idempotent_replay: false })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.get('/v1/data/live/:liveId/games/draw-guess/current', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      client = await pool.connect()
      await client.query('BEGIN')
      const current = await client.query(
        `SELECT g.id::text, g.live_id::text, g.host_user_id, g.status, g.max_players,
                g.total_rounds, g.round_seconds, g.current_round_number, g.drawer_offset,
                g.request_nonce, g.start_nonce, g.cancel_nonce, g.started_at,
                g.completed_at, g.created_at, g.updated_at
         FROM fallback_live_draw_games g
         WHERE g.live_id = $1::uuid
           AND (
             g.status IN ('lobby', 'active')
             OR (
               g.status IN ('completed', 'canceled')
               AND g.completed_at >= NOW() - INTERVAL '10 minutes'
             )
           )
           AND (
             lower(g.host_user_id) = ANY($2::text[])
             OR EXISTS (
               SELECT 1 FROM fallback_live_draw_players p
               WHERE p.game_id = g.id AND lower(p.user_id) = ANY($2::text[])
             )
           )
         ORDER BY
           CASE WHEN g.status IN ('lobby', 'active') THEN 0 ELSE 1 END,
           g.created_at DESC
         LIMIT 1
         FOR UPDATE`,
        [liveId, identityValues(identity)],
      )
      const game = current.rows[0]
      if (!game) {
        await client.query('COMMIT')
        res.setHeader('Cache-Control', 'private, no-store')
        return res.json({ state: null })
      }
      const transition = game.status === 'active'
        ? await finishOrAdvanceRound(client, game)
        : { changed: false }
      const refreshed = transition.changed ? await gameRow(client, liveId, game.id) : game
      const state = await stateForUser(client, refreshed || game, actorId)
      await client.query('COMMIT')
      if (transition.changed) await safePublish(publishEvent, liveId, {
        type: transition.gameCompleted ? 'game_completed' : 'round_started',
        game_id: game.id, round: publicRound(transition.round),
      })
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ state })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.get('/v1/data/live/:liveId/games/draw-guess/:gameId', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      const gameId = validUuid(req.params.gameId)
      if (!liveId || !gameId) return res.status(400).json({ error: 'LIVE_DRAW_GAME_ID_INVALID' })
      client = await pool.connect()
      await client.query('BEGIN')
      const game = await gameRow(client, liveId, gameId, true)
      if (!game) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'LIVE_DRAW_GAME_NOT_FOUND' })
      }
      const member = await acceptedGamePlayer(client, gameId, actorId)
      if (!identityOwns(game.host_user_id, identity) && !member) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_DRAW_PARTICIPANT_REQUIRED' })
      }
      const transition = game.status === 'active'
        ? await finishOrAdvanceRound(client, game)
        : { changed: false }
      const refreshed = transition.changed ? await gameRow(client, liveId, gameId) : game
      const state = await stateForUser(client, refreshed || game, actorId)
      await client.query('COMMIT')
      if (transition.changed) await safePublish(publishEvent, liveId, {
        type: transition.gameCompleted ? 'game_completed' : 'round_started',
        game_id: gameId, round: publicRound(transition.round),
      })
      res.setHeader('Cache-Control', 'private, no-store')
      res.json(state)
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.post('/v1/data/live/:liveId/games/draw-guess/:gameId/draw', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      const gameId = validUuid(req.params.gameId)
      if (!liveId || !gameId) return res.status(400).json({ error: 'LIVE_DRAW_GAME_ID_INVALID' })
      const validation = validateDrawPacket(req.body)
      if (!validation.ok) return res.status(400).json({ error: validation.error })
      if (!applyRateLimit(res, limits.draw, `draw:${gameId}:${actorId}`)) return
      client = await pool.connect()
      await client.query('BEGIN')
      const live = await liveRow(client, liveId, true)
      const game = await gameRow(client, liveId, gameId, true)
      if (!live?.is_active || !game || game.status !== 'active') {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_GAME_NOT_ACTIVE' })
      }
      const round = await currentRoundRow(client, gameId, true)
      if (!round) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_ROUND_NOT_ACTIVE' })
      }
      if (!identityOwns(round.drawer_user_id, identity)) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_DRAW_DRAWER_REQUIRED' })
      }
      if (!await isConnectedParticipant(client, liveId, actorId, identityOwns(live.host_user_id, identity))) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_PARTICIPANT_NOT_CONNECTED' })
      }
      const timing = await client.query(`SELECT ($1::timestamptz <= NOW()) AS expired`, [round.ends_at])
      if (timing.rows[0]?.expired) {
        const transition = await finishOrAdvanceRound(client, game)
        await client.query('COMMIT')
        if (transition.changed) await safePublish(publishEvent, liveId, {
          type: transition.gameCompleted ? 'game_completed' : 'round_started',
          game_id: gameId,
          round: publicRound(transition.round),
        })
        return res.status(409).json({ error: 'LIVE_DRAW_ROUND_EXPIRED' })
      }
      const updated = await client.query(
        `UPDATE fallback_live_draw_rounds
         SET last_draw_sequence = $2, updated_at = NOW()
         WHERE id = $1::uuid AND status = 'active' AND last_draw_sequence < $2
         RETURNING last_draw_sequence`,
        [round.id, validation.packet.sequence],
      )
      await client.query('COMMIT')
      if (!updated.rows[0]) return res.json({ accepted: false, reason: 'OUT_OF_ORDER' })
      await safePublish(publishEvent, liveId, {
        type: 'draw_packet', game_id: gameId, round_id: round.id,
        drawer_user_id: actorId, packet: validation.packet,
      })
      res.status(202).json({ accepted: true, sequence: validation.packet.sequence })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.post('/v1/data/live/:liveId/games/draw-guess/:gameId/guesses', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      const gameId = validUuid(req.params.gameId)
      const nonce = validIdempotencyKey(req.body?.idempotencyKey || req.body?.idempotency_key)
      const guessValidation = validateGuessText(req.body?.guess)
      if (!liveId || !gameId) return res.status(400).json({ error: 'LIVE_DRAW_GAME_ID_INVALID' })
      if (!nonce) return res.status(400).json({ error: 'LIVE_DRAW_IDEMPOTENCY_KEY_REQUIRED' })
      if (!guessValidation.ok) return res.status(422).json({ error: guessValidation.error })
      if (!applyRateLimit(res, limits.guess, `guess:${gameId}:${actorId}`)) return
      client = await pool.connect()
      await client.query('BEGIN')
      const live = await liveRow(client, liveId, true)
      const game = await gameRow(client, liveId, gameId, true)
      if (!live?.is_active || !game || game.status !== 'active') {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_GAME_NOT_ACTIVE' })
      }
      const moderatedGuess = validateGuessText(req.body?.guess, live.blocked_words)
      if (!moderatedGuess.ok) {
        await client.query('ROLLBACK')
        return res.status(422).json({ error: moderatedGuess.error })
      }
      const player = await acceptedGamePlayer(client, gameId, actorId, true)
      if (!player || player.status !== 'accepted') {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_DRAW_PARTICIPANT_REQUIRED' })
      }
      if (!await isConnectedParticipant(client, liveId, actorId, identityOwns(live.host_user_id, identity))) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_PARTICIPANT_NOT_CONNECTED' })
      }
      const round = await currentRoundRow(client, gameId, true)
      if (!round) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_ROUND_NOT_ACTIVE' })
      }
      if (identityOwns(round.drawer_user_id, identity)) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_DRAW_DRAWER_CANNOT_GUESS' })
      }
      const timing = await client.query(`SELECT ($1::timestamptz <= NOW()) AS expired`, [round.ends_at])
      if (timing.rows[0]?.expired) {
        const transition = await finishOrAdvanceRound(client, game)
        await client.query('COMMIT')
        if (transition.changed) await safePublish(publishEvent, liveId, {
          type: transition.gameCompleted ? 'game_completed' : 'round_started',
          game_id: gameId, round: publicRound(transition.round),
        })
        return res.status(409).json({ error: 'LIVE_DRAW_ROUND_EXPIRED' })
      }
      const replay = await client.query(
        `SELECT id::text, round_id::text, correct, awarded_points, submitted_at
         FROM fallback_live_draw_guesses
         WHERE game_id = $1::uuid AND lower(user_id) = lower($2) AND idempotency_key = $3
         LIMIT 1`,
        [gameId, actorId, nonce],
      )
      if (replay.rows[0]) {
        const result = replay.rows[0]
        const state = await stateForUser(client, game, actorId)
        await client.query('COMMIT')
        return res.json({
          correct: Boolean(result.correct), awarded_points: Number(result.awarded_points),
          idempotent_replay: true, state,
        })
      }
      const existingCorrect = await client.query(
        `SELECT 1 FROM fallback_live_draw_guesses
         WHERE round_id = $1::uuid AND lower(user_id) = lower($2) AND correct = TRUE LIMIT 1`,
        [round.id, actorId],
      )
      if (existingCorrect.rows[0]) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_ALREADY_GUESSED_CORRECTLY' })
      }
      const guessCount = await client.query(
        `SELECT COUNT(*)::int AS count FROM fallback_live_draw_guesses
         WHERE round_id = $1::uuid AND lower(user_id) = lower($2)`,
        [round.id, actorId],
      )
      if (Number(guessCount.rows[0]?.count || 0) >= MAX_GUESSES_PER_PLAYER_PER_ROUND) {
        await client.query('ROLLBACK')
        return res.status(429).json({ error: 'LIVE_DRAW_GUESS_LIMIT_REACHED' })
      }
      const evaluated = evaluateGuess(round.prompt_key, req.body?.guess)
      if (!evaluated.ok) {
        await client.query('ROLLBACK')
        return res.status(422).json({ error: evaluated.error })
      }
      const awardedPoints = evaluated.correct
        ? calculateGuessScore({
            nowMs: Date.now(), endsAtMs: new Date(round.ends_at).getTime(), roundSeconds: game.round_seconds,
          })
        : 0
      await client.query(
        `INSERT INTO fallback_live_draw_guesses(
           game_id, round_id, user_id, idempotency_key, answer_length, correct, awarded_points
         ) VALUES ($1::uuid, $2::uuid, $3, $4, $5, $6, $7)`,
        [gameId, round.id, actorId, nonce, evaluated.answerLength, evaluated.correct, awardedPoints],
      )
      if (evaluated.correct) {
        await client.query(
          `UPDATE fallback_live_draw_players
           SET score = score + $3, updated_at = NOW()
           WHERE game_id = $1::uuid AND lower(user_id) = lower($2) AND status = 'accepted'`,
          [gameId, actorId, awardedPoints],
        )
        await client.query(
          `UPDATE fallback_live_draw_players
           SET score = score + $3, updated_at = NOW()
           WHERE game_id = $1::uuid AND lower(user_id) = lower($2) AND status = 'accepted'`,
          [gameId, round.drawer_user_id, DRAWER_ASSIST_POINTS],
        )
        await appendAudit(client, {
          gameId,
          roundId: round.id,
          actorUserId: actorId,
          eventType: 'guess_correct',
          metadata: { awarded_points: awardedPoints, drawer_assist_points: DRAWER_ASSIST_POINTS },
        })
      }
      let transition = { changed: false }
      if (evaluated.correct) {
        const counts = await client.query(
          `SELECT
             (SELECT COUNT(*)::int FROM fallback_live_draw_players
              WHERE game_id = $1::uuid AND status = 'accepted' AND lower(user_id) <> lower($2)) AS eligible,
             (SELECT COUNT(*)::int FROM fallback_live_draw_guesses
              WHERE round_id = $3::uuid AND correct = TRUE) AS correct`,
          [gameId, round.drawer_user_id, round.id],
        )
        if (Number(counts.rows[0]?.eligible || 0) > 0 &&
            Number(counts.rows[0]?.correct || 0) >= Number(counts.rows[0]?.eligible || 0)) {
          transition = await finishOrAdvanceRound(client, game, { force: true, completionReason: 'all_guessed' })
        }
      }
      const refreshed = await gameRow(client, liveId, gameId)
      const state = await stateForUser(client, refreshed || game, actorId)
      await client.query('COMMIT')
      if (evaluated.correct) {
        await safePublish(publishEvent, liveId, {
          type: 'guess_correct', game_id: gameId, round_id: round.id,
          user_id: actorId, awarded_points: awardedPoints,
        })
      }
      if (transition.changed) {
        await safePublish(publishEvent, liveId, {
          type: transition.gameCompleted ? 'game_completed' : 'round_started',
          game_id: gameId, round: publicRound(transition.round),
        })
      }
      res.status(201).json({
        correct: evaluated.correct, awarded_points: awardedPoints,
        idempotent_replay: false, state,
      })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_DRAW_GUESS_CONFLICT' })
      next(error)
    } finally {
      client?.release()
    }
  })

  app.post('/v1/data/live/:liveId/games/draw-guess/:gameId/advance', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      const gameId = validUuid(req.params.gameId)
      if (!liveId || !gameId) return res.status(400).json({ error: 'LIVE_DRAW_GAME_ID_INVALID' })
      if (!applyRateLimit(res, limits.control, `advance:${gameId}:${actorId}`)) return
      client = await pool.connect()
      await client.query('BEGIN')
      const game = await gameRow(client, liveId, gameId, true)
      if (!game) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'LIVE_DRAW_GAME_NOT_FOUND' })
      }
      if (!identityOwns(game.host_user_id, identity)) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      }
      const transition = await finishOrAdvanceRound(client, game)
      const refreshed = await gameRow(client, liveId, gameId)
      const state = await stateForUser(client, refreshed || game, actorId)
      await client.query('COMMIT')
      if (transition.changed) await safePublish(publishEvent, liveId, {
        type: transition.gameCompleted ? 'game_completed' : 'round_started',
        game_id: gameId, round: publicRound(transition.round),
      })
      res.json({ advanced: transition.changed, state })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.post('/v1/data/live/:liveId/games/draw-guess/:gameId/cancel', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireDrawGuessFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const actorId = displayUserId(identity)
      const liveId = validUuid(req.params.liveId)
      const gameId = validUuid(req.params.gameId)
      const nonce = validIdempotencyKey(req.body?.idempotencyKey || req.body?.idempotency_key)
      if (!liveId || !gameId) return res.status(400).json({ error: 'LIVE_DRAW_GAME_ID_INVALID' })
      if (!nonce) return res.status(400).json({ error: 'LIVE_DRAW_IDEMPOTENCY_KEY_REQUIRED' })
      if (!applyRateLimit(res, limits.control, `cancel:${gameId}:${actorId}`)) return
      client = await pool.connect()
      await client.query('BEGIN')
      const game = await gameRow(client, liveId, gameId, true)
      if (!game) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'LIVE_DRAW_GAME_NOT_FOUND' })
      }
      if (!identityOwns(game.host_user_id, identity)) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      }
      if (game.status === 'canceled') {
        if (game.cancel_nonce !== nonce) {
          await client.query('ROLLBACK')
          return res.status(409).json({ error: 'LIVE_DRAW_GAME_ALREADY_CANCELED' })
        }
        const state = await stateForUser(client, game, actorId)
        await client.query('COMMIT')
        return res.json({ ...state, idempotent_replay: true })
      }
      if (!['lobby', 'active'].includes(game.status)) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_DRAW_GAME_NOT_CANCELABLE' })
      }
      const updated = await client.query(
        `UPDATE fallback_live_draw_games
         SET status = 'canceled', cancel_nonce = $2, completed_at = NOW(), updated_at = NOW()
         WHERE id = $1::uuid
         RETURNING id::text, live_id::text, host_user_id, status, max_players, total_rounds,
                   round_seconds, current_round_number, drawer_offset, request_nonce,
                   start_nonce, cancel_nonce, started_at, completed_at, created_at, updated_at`,
        [gameId, nonce],
      )
      await client.query(
        `UPDATE fallback_live_draw_rounds
         SET status = 'canceled', completed_at = NOW(), updated_at = NOW()
         WHERE game_id = $1::uuid AND status = 'active'`,
        [gameId],
      )
      await appendAudit(client, {
        gameId,
        actorUserId: actorId,
        eventType: 'game_canceled',
        metadata: { reason: 'host_request' },
      })
      const state = await stateForUser(client, updated.rows[0], actorId)
      await client.query('COMMIT')
      await safePublish(publishEvent, liveId, { type: 'game_canceled', game_id: gameId })
      res.json({ ...state, idempotent_replay: false })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })
}

async function reconcileExpiredLiveDrawGuessGames({ pool, publishEvent = publishRealtimeEvent, limit = 25 }) {
  if (!drawGuessEnabled()) return { processed: 0, advanced: 0, disabled: true }
  const due = await pool.query(
    `SELECT DISTINCT g.id::text, g.live_id::text
     FROM fallback_live_draw_games g
     JOIN fallback_live_draw_rounds r ON r.game_id = g.id AND r.status = 'active'
     WHERE g.status = 'active' AND r.ends_at <= NOW()
     ORDER BY g.id
     LIMIT $1`,
    [intBetween(limit, 1, 100, 25)],
  )
  let advanced = 0
  let failed = 0
  for (const item of due.rows) {
    let client
    try {
      client = await pool.connect()
      await client.query('BEGIN')
      const game = await gameRow(client, item.live_id, item.id, true)
      if (!game || game.status !== 'active') {
        await client.query('ROLLBACK')
        continue
      }
      const transition = await finishOrAdvanceRound(client, game)
      await client.query('COMMIT')
      if (transition.changed) {
        advanced += 1
        await safePublish(publishEvent, item.live_id, {
          type: transition.gameCompleted ? 'game_completed' : 'round_started',
          game_id: item.id, round: publicRound(transition.round),
        })
      }
    } catch (_error) {
      failed += 1
      if (client) await client.query('ROLLBACK').catch(() => {})
    } finally {
      client?.release()
    }
  }
  return { processed: due.rows.length, advanced, failed, disabled: false }
}

function startLiveDrawGuessReconciler({
  pool,
  publishEvent = publishRealtimeEvent,
  intervalMs = 1_000,
}) {
  if (!drawGuessEnabled()) return { started: false, stop() {} }
  let running = false
  const timer = setInterval(async () => {
    if (running) return
    running = true
    try {
      const result = await reconcileExpiredLiveDrawGuessGames({ pool, publishEvent })
      if (result.failed > 0) {
        console.warn('[live-draw-guess] reconciliation had failed games', {
          processed: result.processed,
          failed: result.failed,
        })
      }
    } catch (error) {
      console.warn('[live-draw-guess] reconciliation failed', {
        code: String(error?.code || 'RECONCILIATION_FAILED').slice(0, 80),
      })
    } finally {
      running = false
    }
  }, intBetween(intervalMs, 500, 10_000, 1_000))
  timer.unref?.()
  return {
    started: true,
    stop() { clearInterval(timer) },
  }
}

module.exports = {
  LIVE_DRAW_GUESS_FLAG,
  LIVE_DRAW_GUESS_SCHEMA_SQL,
  SAFE_PROMPTS,
  calculateGuessScore,
  chooseDrawer,
  choosePrompt,
  createDrawGuessRateLimiters,
  drawGuessEnabled,
  ensureLiveDrawGuessTables,
  evaluateGuess,
  identityOwns,
  normalizeGuess,
  publicRound,
  reconcileExpiredLiveDrawGuessGames,
  registerLiveDrawGuessRoutes,
  requireDrawGuessFeature,
  sanitizePublicEvent,
  serializeGameState,
  startLiveDrawGuessReconciler,
  validateDrawPacket,
  validateGuessText,
  validIdempotencyKey,
}
