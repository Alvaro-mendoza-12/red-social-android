const crypto = require('crypto')
const {
  ensureDynamicPlayMatchCatalogTables,
  loadGameConfiguration,
  mapCatalogRow,
  registerPlayMatchCatalogRoutes,
  searchCatalog,
} = require('./playmatch-catalog')

const PLAY_STYLES = new Set(['casual', 'competitive', 'mixed'])
const VISIBILITIES = new Set(['private', 'connections', 'public'])
const SESSION_STATES = new Set([
  'SEARCHING',
  'MATCH_FOUND',
  'ACCEPTED',
  'SKIPPED',
  'TIMEOUT',
  'CANCELLED',
  'ERROR',
])

function cleanText(value, maxLength = 160) {
  const text = String(value || '').trim()
  return text ? text.slice(0, maxLength) : ''
}

function normalizeId(value) {
  return cleanText(value, 240).toLowerCase()
}

function booleanEnv(name, fallback = false) {
  const raw = String(process.env[name] ?? '').trim().toLowerCase()
  if (!raw) return fallback
  return ['1', 'true', 'yes', 'on'].includes(raw)
}

function cleanArray(value, maxItems = 20, maxLength = 80) {
  if (!Array.isArray(value)) return []
  return [...new Set(value.map((entry) => cleanText(entry, maxLength)).filter(Boolean))].slice(0, maxItems)
}

function validTimezone(value) {
  const timezone = cleanText(value, 80) || 'UTC'
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: timezone }).format(new Date())
    return timezone
  } catch {
    return null
  }
}

function safePhotos(value) {
  return cleanArray(value, 4, 1000).filter((url) => /^https:\/\//i.test(url))
}

function testerIds() {
  return new Set(
    String(process.env.PLAYMATCH_TESTER_IDS || '')
      .split(',')
      .map(normalizeId)
      .filter(Boolean),
  )
}

function playMatchEnabledFor(identity) {
  if (booleanEnv('PLAYMATCH_GAMES_ENABLED', false)) return true
  const allowed = testerIds()
  return (identity?.values || []).some((id) => allowed.has(normalizeId(id)))
}

function cursorSecret() {
  return cleanText(process.env.PLAYMATCH_CURSOR_SECRET, 512)
}

function encodeCursor(offset) {
  const secret = cursorSecret()
  if (!secret) return null
  const payload = Buffer.from(JSON.stringify({ offset: Math.max(0, Number(offset) || 0) })).toString('base64url')
  const signature = crypto.createHmac('sha256', secret).update(payload).digest('base64url')
  return `${payload}.${signature}`
}

function decodeCursor(value) {
  if (!value) return 0
  const secret = cursorSecret()
  if (!secret) throw Object.assign(new Error('PLAYMATCH_CURSOR_NOT_READY'), { statusCode: 503 })
  const [payload, signature] = String(value).split('.')
  if (!payload || !signature) throw Object.assign(new Error('INVALID_CURSOR'), { statusCode: 400 })
  const expected = crypto.createHmac('sha256', secret).update(payload).digest('base64url')
  const a = Buffer.from(signature)
  const b = Buffer.from(expected)
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    throw Object.assign(new Error('INVALID_CURSOR'), { statusCode: 400 })
  }
  const parsed = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'))
  const offset = Number(parsed?.offset)
  if (!Number.isInteger(offset) || offset < 0 || offset > 10000) {
    throw Object.assign(new Error('INVALID_CURSOR'), { statusCode: 400 })
  }
  return offset
}

function publicUserId(user) {
  return cleanText(user?.supabase_user_id || user?.user_id || user?.id, 240)
}

function localUserId(user) {
  return cleanText(user?.id, 240)
}

async function ensurePlayMatchTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_game_catalog (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      image_url TEXT,
      platforms JSONB NOT NULL DEFAULT '[]'::jsonb,
      ranks JSONB NOT NULL DEFAULT '[]'::jsonb,
      roles JSONB NOT NULL DEFAULT '[]'::jsonb,
      cross_play BOOLEAN NOT NULL DEFAULT FALSE,
      active BOOLEAN NOT NULL DEFAULT TRUE,
      sort_order INTEGER NOT NULL DEFAULT 0,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_gaming_profiles (
      user_id UUID PRIMARY KEY REFERENCES fallback_users(id) ON DELETE CASCADE,
      gamer_bio TEXT NOT NULL DEFAULT '',
      region_code TEXT NOT NULL DEFAULT '',
      languages TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      play_style TEXT NOT NULL DEFAULT 'casual',
      voice_preference BOOLEAN NOT NULL DEFAULT FALSE,
      discovery_level SMALLINT NOT NULL DEFAULT 50,
      discoverable BOOLEAN NOT NULL DEFAULT FALSE,
      adult_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
      game_id_visibility TEXT NOT NULL DEFAULT 'connections',
      photos_visibility TEXT NOT NULL DEFAULT 'connections',
      availability_visibility TEXT NOT NULL DEFAULT 'connections',
      photo_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
      timezone TEXT NOT NULL DEFAULT 'UTC',
      version BIGINT NOT NULL DEFAULT 1,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_user_games (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      game_id TEXT NOT NULL REFERENCES fallback_game_catalog(id) ON DELETE RESTRICT,
      game_user_id TEXT NOT NULL DEFAULT '',
      platform TEXT NOT NULL,
      rank_key TEXT NOT NULL DEFAULT '',
      level_key TEXT NOT NULL DEFAULT '',
      role_key TEXT NOT NULL DEFAULT '',
      visibility TEXT NOT NULL DEFAULT 'connections',
      is_primary BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (user_id, game_id, platform)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_gaming_availability (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      day_of_week SMALLINT NOT NULL,
      start_minute SMALLINT NOT NULL,
      end_minute SMALLINT NOT NULL,
      timezone TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (user_id, day_of_week, start_minute, end_minute, timezone)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_gaming_preferences (
      user_id UUID PRIMARY KEY REFERENCES fallback_users(id) ON DELETE CASCADE,
      game_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      platforms TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      rank_tolerance SMALLINT NOT NULL DEFAULT 2,
      roles TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      styles TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      languages TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      regions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
      voice_required BOOLEAN NOT NULL DEFAULT FALSE,
      discovery_level SMALLINT NOT NULL DEFAULT 50,
      version BIGINT NOT NULL DEFAULT 1,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_gaming_connection_requests (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      requester_user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      recipient_user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      status TEXT NOT NULL DEFAULT 'PENDING',
      message TEXT NOT NULL DEFAULT '',
      idempotency_key TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      responded_at TIMESTAMPTZ,
      UNIQUE (requester_user_id, idempotency_key)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_matchmaking_sessions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      status TEXT NOT NULL DEFAULT 'SEARCHING',
      criteria JSONB NOT NULL DEFAULT '{}'::jsonb,
      current_match_id UUID,
      idempotency_key TEXT NOT NULL,
      expires_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (user_id, idempotency_key)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_gaming_matches (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      session_id UUID NOT NULL REFERENCES fallback_matchmaking_sessions(id) ON DELETE CASCADE,
      user_a_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      user_b_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      score SMALLINT NOT NULL,
      reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
      status TEXT NOT NULL DEFAULT 'PENDING',
      expires_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (session_id, user_b_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_gaming_match_decisions (
      match_id UUID NOT NULL REFERENCES fallback_gaming_matches(id) ON DELETE CASCADE,
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      decision TEXT NOT NULL,
      reason TEXT NOT NULL DEFAULT '',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (match_id, user_id)
    )
  `)
  await ensureDynamicPlayMatchCatalogTables(pool)
}

async function findUserByAlias(db, rawId) {
  const id = normalizeId(rawId)
  if (!id) return null
  const { rows } = await db.query(
    `SELECT id, supabase_user_id, display_name, avatar_url, email
       FROM fallback_users
      WHERE lower(id::text) = $1
         OR lower(COALESCE(supabase_user_id::text, '')) = $1
      LIMIT 1`,
    [id],
  )
  return rows[0] || null
}

async function blockedPair(db, viewer, candidate) {
  const viewerIds = [normalizeId(viewer.id), normalizeId(viewer.supabase_user_id)].filter(Boolean)
  const candidateIds = [normalizeId(candidate.id), normalizeId(candidate.supabase_user_id)].filter(Boolean)
  const { rows } = await db.query(
    `SELECT 1
       FROM fallback_user_blocks
      WHERE (lower(blocker_id) = ANY($1::text[]) AND lower(blocked_id) = ANY($2::text[]))
         OR (lower(blocker_id) = ANY($2::text[]) AND lower(blocked_id) = ANY($1::text[]))
      LIMIT 1`,
    [viewerIds, candidateIds],
  )
  return rows.length > 0
}

async function acceptedConnection(db, a, b) {
  const { rows } = await db.query(
    `SELECT id
       FROM fallback_gaming_connection_requests
      WHERE status = 'ACCEPTED'
        AND ((requester_user_id = $1 AND recipient_user_id = $2)
          OR (requester_user_id = $2 AND recipient_user_id = $1))
      LIMIT 1`,
    [a, b],
  )
  return rows.length > 0
}

async function acceptedConnectionSet(db, viewerUserId, candidateUserIds) {
  const ids = [...new Set((candidateUserIds || []).map(localUserId).filter(Boolean))]
  if (!ids.length) return new Set()
  const { rows } = await db.query(
    `SELECT CASE WHEN requester_user_id = $1 THEN recipient_user_id ELSE requester_user_id END AS other_user_id
       FROM fallback_gaming_connection_requests
      WHERE status = 'ACCEPTED'
        AND ((requester_user_id = $1 AND recipient_user_id = ANY($2::uuid[]))
          OR (recipient_user_id = $1 AND requester_user_id = ANY($2::uuid[])))`,
    [viewerUserId, ids],
  )
  return new Set(rows.map((row) => row.other_user_id))
}

async function ensureGamingProfile(db, userId) {
  await db.query(
    `INSERT INTO fallback_gaming_profiles(user_id)
     VALUES ($1)
     ON CONFLICT (user_id) DO NOTHING`,
    [userId],
  )
}

function mapCatalog(row) {
  return mapCatalogRow(row)
}

function mapGame(row, includePrivate = false) {
  return {
    id: row.id,
    gameId: row.game_id,
    gameName: row.game_name || row.game_id,
    imageUrl: row.game_image_url || null,
    platform: row.platform,
    rank: row.rank_key || '',
    level: row.level_key || '',
    role: row.role_key || '',
    gameUserId: includePrivate ? row.game_user_id || '' : '',
    gameUserIdVisible: includePrivate,
    visibility: row.visibility || 'connections',
    isPrimary: Boolean(row.is_primary),
  }
}

function mapAvailability(row) {
  return {
    id: row.id,
    dayOfWeek: Number(row.day_of_week),
    startMinute: Number(row.start_minute),
    endMinute: Number(row.end_minute),
    timezone: row.timezone || 'UTC',
    crossesMidnight: Number(row.end_minute) <= Number(row.start_minute),
  }
}

async function loadBundle(db, userId) {
  const { rows: profileRows } = await db.query(
    `SELECT gp.*, u.supabase_user_id, u.display_name, u.avatar_url
       FROM fallback_gaming_profiles gp
       JOIN fallback_users u ON u.id = gp.user_id
      WHERE gp.user_id = $1
      LIMIT 1`,
    [userId],
  )
  const profile = profileRows[0]
  if (!profile) return null
  const [{ rows: games }, { rows: availability }, { rows: preferences }] = await Promise.all([
    db.query(
      `SELECT ug.*, gc.name AS game_name, COALESCE(gc.icon_url,gc.logo_url,gc.cover_url,gc.image_url) AS game_image_url,
              gc.cross_play, gc.ranks, gc.roles, gc.platforms,
              COALESCE((SELECT r.order_index FROM fallback_playmatch_game_ranks r
                JOIN fallback_playmatch_game_rank_schemas s ON s.id=r.schema_id
                WHERE s.game_id=ug.game_id AND s.active=TRUE AND r.active=TRUE
                  AND lower(r.rank_key)=lower(ug.rank_key) ORDER BY s.updated_at DESC LIMIT 1),-1) AS rank_order
         FROM fallback_user_games ug
         JOIN fallback_game_catalog gc ON gc.id = ug.game_id
        WHERE ug.user_id = $1 AND gc.active = TRUE
        ORDER BY ug.is_primary DESC, gc.sort_order, gc.name`,
      [userId],
    ),
    db.query(
      `SELECT * FROM fallback_gaming_availability
        WHERE user_id = $1 ORDER BY day_of_week, start_minute`,
      [userId],
    ),
    db.query(`SELECT * FROM fallback_gaming_preferences WHERE user_id = $1 LIMIT 1`, [userId]),
  ])
  return { profile, games, availability, preferences: preferences[0] || null }
}

async function loadBundles(db, userIds) {
  const ids = [...new Set((userIds || []).map((value) => cleanText(value, 240)).filter(Boolean))]
  if (!ids.length) return new Map()
  const [{ rows: profiles }, { rows: games }, { rows: availability }, { rows: preferences }] = await Promise.all([
    db.query(
      `SELECT gp.*, u.supabase_user_id, u.display_name, u.avatar_url
         FROM fallback_gaming_profiles gp
         JOIN fallback_users u ON u.id = gp.user_id
        WHERE gp.user_id = ANY($1::uuid[])`,
      [ids],
    ),
    db.query(
      `SELECT ug.*, gc.name AS game_name, COALESCE(gc.icon_url,gc.logo_url,gc.cover_url,gc.image_url) AS game_image_url,
              gc.cross_play, gc.ranks, gc.roles, gc.platforms, gc.support_level,
              gc.ranked_supported, gc.roles_supported,
              COALESCE((SELECT r.order_index FROM fallback_playmatch_game_ranks r
                JOIN fallback_playmatch_game_rank_schemas s ON s.id=r.schema_id
                WHERE s.game_id=ug.game_id AND s.active=TRUE AND r.active=TRUE
                  AND lower(r.rank_key)=lower(ug.rank_key) ORDER BY s.updated_at DESC LIMIT 1),-1) AS rank_order
         FROM fallback_user_games ug
         JOIN fallback_game_catalog gc ON gc.id = ug.game_id
        WHERE ug.user_id = ANY($1::uuid[]) AND gc.active = TRUE
        ORDER BY ug.is_primary DESC, gc.sort_order, gc.name`,
      [ids],
    ),
    db.query(`SELECT * FROM fallback_gaming_availability WHERE user_id = ANY($1::uuid[]) ORDER BY day_of_week,start_minute`, [ids]),
    db.query(`SELECT * FROM fallback_gaming_preferences WHERE user_id = ANY($1::uuid[])`, [ids]),
  ])
  return new Map(profiles.map((profile) => [profile.user_id, {
    profile,
    games: games.filter((row) => row.user_id === profile.user_id),
    availability: availability.filter((row) => row.user_id === profile.user_id),
    preferences: preferences.find((row) => row.user_id === profile.user_id) || null,
  }]))
}

function mapProfileBundle(bundle, { own = false, connected = false, score = null, reasons = [] } = {}) {
  const profile = bundle.profile
  const canSeeIds = own || profile.game_id_visibility === 'public' || (connected && profile.game_id_visibility === 'connections')
  const canSeePhotos = own || profile.photos_visibility === 'public' || (connected && profile.photos_visibility === 'connections')
  const canSeeAvailability = own || profile.availability_visibility === 'public' || (connected && profile.availability_visibility === 'connections')
  return {
    userId: publicUserId(profile),
    displayName: profile.display_name || 'Jugador',
    avatarUrl: profile.avatar_url || null,
    gamerBio: profile.gamer_bio || '',
    regionCode: profile.region_code || '',
    languages: profile.languages || [],
    playStyle: profile.play_style || 'casual',
    voicePreference: Boolean(profile.voice_preference),
    discoveryLevel: Number(profile.discovery_level || 50),
    discoverable: Boolean(profile.discoverable),
    adultConfirmed: own ? Boolean(profile.adult_confirmed) : undefined,
    gameIdVisibility: own ? profile.game_id_visibility : undefined,
    photosVisibility: own ? profile.photos_visibility : undefined,
    availabilityVisibility: own ? profile.availability_visibility : undefined,
    timezone: profile.timezone || 'UTC',
    version: Number(profile.version || 1),
    games: bundle.games.map((game) => mapGame(game, canSeeIds && game.visibility !== 'private')),
    photos: canSeePhotos && Array.isArray(profile.photo_urls) ? profile.photo_urls : [],
    availability: canSeeAvailability ? bundle.availability.map(mapAvailability) : [],
    compatibilityScore: score,
    compatibilityReasons: reasons,
    connected,
  }
}

function timezoneOffsetMinutes(timezone, instantMs) {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  })
  const parts = Object.fromEntries(formatter.formatToParts(new Date(instantMs)).map((part) => [part.type, part.value]))
  const represented = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  )
  return Math.round((represented - instantMs) / 60000)
}

function localWeeklyMinuteToUtc(dayOfWeek, minute, timezone) {
  const anchor = Date.UTC(2026, 7, 2, 0, 0, 0)
  const localTarget = anchor + (Number(dayOfWeek) * 1440 + Number(minute)) * 60000
  let instant = localTarget
  for (let index = 0; index < 2; index += 1) {
    instant = localTarget - timezoneOffsetMinutes(timezone, instant) * 60000
  }
  return Math.round((instant - anchor) / 60000)
}

function expandWeeklyInterval(entry) {
  const timezone = validTimezone(entry.timezone) || 'UTC'
  const rawStart = localWeeklyMinuteToUtc(entry.day_of_week, entry.start_minute, timezone)
  let endDay = Number(entry.day_of_week)
  if (Number(entry.end_minute) <= Number(entry.start_minute)) endDay += 1
  let rawEnd = localWeeklyMinuteToUtc(endDay, entry.end_minute, timezone)
  while (rawEnd <= rawStart) rawEnd += 10080
  const duration = Math.min(rawEnd - rawStart, 1440)
  const start = ((rawStart % 10080) + 10080) % 10080
  const end = start + duration
  if (end <= 10080) return [[start, end]]
  return [[start, 10080], [0, end - 10080]]
}

function availabilityOverlapMinutes(left = [], right = []) {
  const a = left.flatMap(expandWeeklyInterval)
  const b = right.flatMap(expandWeeklyInterval)
  let overlap = 0
  for (const first of a) {
    for (const second of b) {
      overlap += Math.max(0, Math.min(first[1], second[1]) - Math.max(first[0], second[0]))
    }
  }
  return overlap
}

function intersection(left = [], right = [], selector = (value) => value) {
  const rightSet = new Set(right.map((value) => normalizeId(selector(value))))
  return left.filter((value) => rightSet.has(normalizeId(selector(value))))
}

function computeCompatibility(viewer, candidate, criteria = {}) {
  const reasons = []
  let score = 0
  const viewerGames = viewer.games || []
  const candidateGames = candidate.games || []
  const commonGames = intersection(viewerGames, candidateGames, (game) => game.game_id || game.gameId)
  if (commonGames.length > 0) {
    score += 30
    reasons.push({ code: 'COMMON_GAME', points: 30, label: `Juego en comun: ${commonGames[0].game_name || commonGames[0].gameName || commonGames[0].game_id}` })
  }

  const commonGamePairs = commonGames.map((game) => ({
    viewer: game,
    candidate: candidateGames.find((entry) => normalizeId(entry.game_id || entry.gameId) === normalizeId(game.game_id || game.gameId)),
  })).filter((pair) => pair.candidate)
  const samePlatform = commonGamePairs.some((pair) => normalizeId(pair.viewer.platform) === normalizeId(pair.candidate.platform))
  const crossPlay = commonGamePairs.some((pair) => Boolean(pair.viewer.cross_play || pair.viewer.crossPlay || pair.candidate.cross_play || pair.candidate.crossPlay))
  if (samePlatform || crossPlay) {
    score += 20
    reasons.push({ code: crossPlay && !samePlatform ? 'CROSS_PLAY' : 'PLATFORM', points: 20, label: crossPlay && !samePlatform ? 'Plataformas compatibles por cross-play' : 'Plataforma compatible' })
  }

  const rankTolerance = Math.max(0, Math.min(Number(criteria.rankTolerance ?? criteria.rank_tolerance ?? viewer.preferences?.rank_tolerance ?? 2) || 0, 10))
  const rankCompatible = commonGames.some((game) => {
    const other = candidateGames.find((entry) => normalizeId(entry.game_id || entry.gameId) === normalizeId(game.game_id || game.gameId))
    if (!game.rank_key || !other?.rank_key) return false
    const ranks = Array.isArray(game.ranks) ? game.ranks.map(normalizeId) : []
    const left = Number(game.rank_order) >= 0 ? Number(game.rank_order) : ranks.indexOf(normalizeId(game.rank_key))
    const right = Number(other.rank_order) >= 0 ? Number(other.rank_order) : ranks.indexOf(normalizeId(other.rank_key))
    return left >= 0 && right >= 0
      ? Math.abs(left - right) <= rankTolerance
      : normalizeId(game.rank_key) === normalizeId(other.rank_key)
  })
  if (rankCompatible) {
    score += 10
    reasons.push({ code: 'RANK', points: 10, label: 'Rango compatible' })
  }

  const roleCompatible = commonGames.some((game) => {
    const other = candidateGames.find((entry) => normalizeId(entry.game_id || entry.gameId) === normalizeId(game.game_id || game.gameId))
    return game.role_key && other?.role_key && normalizeId(game.role_key) !== normalizeId(other.role_key)
  })
  if (roleCompatible) {
    score += 5
    reasons.push({ code: 'ROLE', points: 5, label: 'Roles complementarios' })
  }

  const styles = cleanArray(criteria.styles || viewer.preferences?.styles || [viewer.profile?.play_style])
  if (styles.map(normalizeId).includes(normalizeId(candidate.profile?.play_style))) {
    score += 10
    reasons.push({ code: 'STYLE', points: 10, label: 'Estilo de juego compatible' })
  }

  const overlapMinutes = availabilityOverlapMinutes(viewer.availability, candidate.availability)
  if (overlapMinutes > 0) {
    score += 15
    reasons.push({ code: 'AVAILABILITY', points: 15, label: `${Math.max(1, Math.round(overlapMinutes / 60))} h de horario compatible` })
  }

  const wantedLanguages = cleanArray(criteria.languages || viewer.preferences?.languages || viewer.profile?.languages)
  if (intersection(wantedLanguages, candidate.profile?.languages || []).length > 0) {
    score += 5
    reasons.push({ code: 'LANGUAGE', points: 5, label: 'Idioma compartido' })
  }

  const wantedRegions = cleanArray(criteria.regions || viewer.preferences?.regions || [viewer.profile?.region_code])
  if (wantedRegions.map(normalizeId).includes(normalizeId(candidate.profile?.region_code))) {
    score += 5
    reasons.push({ code: 'REGION', points: 5, label: 'Region amplia compatible' })
  }

  return { score: Math.min(100, score), reasons }
}

async function candidateBundles(db, viewer, filters = {}) {
  const viewerAliases = [normalizeId(viewer.id), normalizeId(viewer.supabase_user_id)].filter(Boolean)
  const query = cleanText(filters.query, 120).toLowerCase()
  const gameId = normalizeId(filters.gameId)
  const platform = normalizeId(filters.platform)
  const region = normalizeId(filters.region)
  const { rows } = await db.query(
    `SELECT gp.*, u.supabase_user_id, u.display_name, u.avatar_url
       FROM fallback_gaming_profiles gp
       JOIN fallback_users u ON u.id = gp.user_id
      WHERE gp.discoverable = TRUE
        AND gp.adult_confirmed = TRUE
        AND gp.user_id <> $1
        AND ($2 = '' OR lower(u.display_name) LIKE '%' || $2 || '%' OR lower(gp.gamer_bio) LIKE '%' || $2 || '%')
        AND ($3 = '' OR lower(gp.region_code) = $3)
        AND NOT EXISTS (
          SELECT 1 FROM fallback_user_blocks ub
           WHERE (lower(ub.blocker_id) = ANY($4::text[]) AND lower(ub.blocked_id) IN (lower(u.id::text), lower(COALESCE(u.supabase_user_id::text, ''))))
              OR (lower(ub.blocked_id) = ANY($4::text[]) AND lower(ub.blocker_id) IN (lower(u.id::text), lower(COALESCE(u.supabase_user_id::text, ''))))
        )
        AND NOT EXISTS (
          SELECT 1 FROM fallback_profile_moderation pm
           WHERE lower(pm.user_id) IN (lower(u.id::text), lower(COALESCE(u.supabase_user_id::text, '')))
             AND pm.suspension_status = 'active'
             AND (pm.suspension_end_date IS NULL OR pm.suspension_end_date > NOW())
        )
        AND (NOT $7::boolean OR NOT EXISTS (
          SELECT 1 FROM fallback_gaming_matches gm
           WHERE gm.created_at > NOW() - INTERVAL '24 hours'
             AND ((gm.user_a_id = $1 AND gm.user_b_id = gp.user_id)
               OR (gm.user_b_id = $1 AND gm.user_a_id = gp.user_id))
        ))
        AND ($5 = '' OR EXISTS (SELECT 1 FROM fallback_user_games ug WHERE ug.user_id = gp.user_id AND lower(ug.game_id) = $5))
        AND ($6 = '' OR EXISTS (SELECT 1 FROM fallback_user_games ug WHERE ug.user_id = gp.user_id AND lower(ug.platform) = $6))
      ORDER BY gp.updated_at DESC
      LIMIT 250`,
    [localUserId(viewer), query, region, viewerAliases, gameId, platform, Boolean(filters.excludeRecentMatches)],
  )
  if (!rows.length) return []
  const ids = rows.map((row) => row.user_id)
  const [{ rows: games }, { rows: availability }, { rows: preferences }] = await Promise.all([
    db.query(
      `SELECT ug.*, gc.name AS game_name, COALESCE(gc.icon_url,gc.logo_url,gc.cover_url,gc.image_url) AS game_image_url, gc.cross_play,
              gc.ranks, gc.roles, gc.platforms,
              COALESCE((SELECT r.order_index FROM fallback_playmatch_game_ranks r
                JOIN fallback_playmatch_game_rank_schemas s ON s.id=r.schema_id
                WHERE s.game_id=ug.game_id AND s.active=TRUE AND r.active=TRUE
                  AND lower(r.rank_key)=lower(ug.rank_key) ORDER BY s.updated_at DESC LIMIT 1),-1) AS rank_order
         FROM fallback_user_games ug JOIN fallback_game_catalog gc ON gc.id = ug.game_id
        WHERE ug.user_id = ANY($1::uuid[]) AND gc.active = TRUE`,
      [ids],
    ),
    db.query(`SELECT * FROM fallback_gaming_availability WHERE user_id = ANY($1::uuid[])`, [ids]),
    db.query(`SELECT * FROM fallback_gaming_preferences WHERE user_id = ANY($1::uuid[])`, [ids]),
  ])
  return rows.map((profile) => ({
    profile,
    games: games.filter((row) => row.user_id === profile.user_id),
    availability: availability.filter((row) => row.user_id === profile.user_id),
    preferences: preferences.find((row) => row.user_id === profile.user_id) || null,
  }))
}

function cleanCriteria(body = {}) {
  return {
    gameIds: cleanArray(body.gameIds || body.game_ids, 12),
    platforms: cleanArray(body.platforms, 8),
    styles: cleanArray(body.styles, 4),
    languages: cleanArray(body.languages, 8),
    regions: cleanArray(body.regions, 8),
    roles: cleanArray(body.roles, 12),
    rankTolerance: Math.max(0, Math.min(Number(body.rankTolerance ?? body.rank_tolerance ?? 2) || 0, 10)),
    voiceRequired: Boolean(body.voiceRequired ?? body.voice_required),
  }
}

function candidateFitsCriteria(candidate, criteria = {}) {
  const games = candidate.games || []
  const gameIds = cleanArray(criteria.gameIds || criteria.game_ids, 12).map(normalizeId)
  const platforms = cleanArray(criteria.platforms, 8).map(normalizeId)
  const roles = cleanArray(criteria.roles, 12).map(normalizeId)
  const languages = cleanArray(criteria.languages, 8).map(normalizeId)
  const regions = cleanArray(criteria.regions, 8).map(normalizeId)
  if (gameIds.length && !games.some((game) => gameIds.includes(normalizeId(game.game_id || game.gameId)))) return false
  if (platforms.length && !games.some((game) => platforms.includes(normalizeId(game.platform)))) return false
  if (roles.length && !games.some((game) => roles.includes(normalizeId(game.role_key || game.role)))) return false
  if (languages.length && !candidate.profile?.languages?.some((value) => languages.includes(normalizeId(value)))) return false
  if (regions.length && !regions.includes(normalizeId(candidate.profile?.region_code))) return false
  if (criteria.voiceRequired && !candidate.profile?.voice_preference) return false
  return true
}

async function sessionResponse(db, row, viewerUser) {
  if (!row) return null
  let match = null
  if (row.current_match_id) {
    const { rows } = await db.query(`SELECT * FROM fallback_gaming_matches WHERE id = $1 LIMIT 1`, [row.current_match_id])
    const matchRow = rows[0]
    if (matchRow) {
      const otherId = matchRow.user_a_id === localUserId(viewerUser) ? matchRow.user_b_id : matchRow.user_a_id
      const bundle = await loadBundle(db, otherId)
      match = {
        id: matchRow.id,
        status: matchRow.status,
        score: Number(matchRow.score),
        reasons: matchRow.reasons || [],
        expiresAt: matchRow.expires_at,
        player: bundle ? mapProfileBundle(bundle, { score: Number(matchRow.score), reasons: matchRow.reasons || [] }) : null,
      }
    }
  }
  return {
    id: row.id,
    status: SESSION_STATES.has(row.status) ? row.status : 'ERROR',
    criteria: row.criteria || {},
    expiresAt: row.expires_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    match,
  }
}

async function selectMatch(db, viewer, session) {
  const viewerBundle = await loadBundle(db, localUserId(viewer))
  if (!viewerBundle) return null
  const candidates = await candidateBundles(db, viewer, { ...(session.criteria || {}), excludeRecentMatches: true })
  const scored = candidates
    .filter((candidate) => candidateFitsCriteria(candidate, session.criteria || {}))
    .map((candidate) => ({ candidate, ...computeCompatibility(viewerBundle, candidate, session.criteria || {}) }))
    .filter((entry) => entry.score > 0)
    .sort((a, b) => b.score - a.score || String(a.candidate.profile.user_id).localeCompare(String(b.candidate.profile.user_id)))
  for (const entry of scored) {
    const { rows } = await db.query(
      `INSERT INTO fallback_gaming_matches(session_id, user_a_id, user_b_id, score, reasons, expires_at)
       VALUES ($1, $2, $3, $4, $5::jsonb, LEAST($6::timestamptz, NOW() + INTERVAL '10 minutes'))
       ON CONFLICT (session_id, user_b_id) DO NOTHING
       RETURNING *`,
      [session.id, localUserId(viewer), entry.candidate.profile.user_id, entry.score, JSON.stringify(entry.reasons), session.expires_at],
    )
    if (!rows[0]) continue
    await db.query(
      `UPDATE fallback_matchmaking_sessions
          SET status = 'MATCH_FOUND', current_match_id = $2, updated_at = NOW()
        WHERE id = $1`,
      [session.id, rows[0].id],
    )
    return rows[0]
  }
  return null
}

function registerPlayMatchRoutes(app, {
  pool,
  authRequired,
  getCurrentUser,
  userIdentity,
  publishRealtimeEvent = async () => false,
}) {
  async function context(req, res) {
    const user = await getCurrentUser(pool, req)
    if (!user) {
      res.status(404).json({ error: 'USER_NOT_FOUND' })
      return null
    }
    const identity = userIdentity(user)
    if (!playMatchEnabledFor(identity)) {
      res.status(404).json({ error: 'PLAYMATCH_DISABLED' })
      return null
    }
    if (!cursorSecret()) {
      res.status(503).json({ error: 'PLAYMATCH_NOT_READY' })
      return null
    }
    return { user, identity }
  }

  app.get('/v1/data/playmatch/capabilities', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const enabled = playMatchEnabledFor(identity) && Boolean(cursorSecret())
      res.json({
        capabilities: {
          enabled,
          testerAccess: !booleanEnv('PLAYMATCH_GAMES_ENABLED', false) && enabled,
          adultConfirmationRequired: booleanEnv('PLAYMATCH_REQUIRE_ADULT_CONFIRMATION', true),
          preciseLocationUsed: false,
          privateMessagesUsedForMatching: false,
          matchmakingRealtime: true,
          photosEnabled: booleanEnv('PLAYMATCH_PHOTOS_ENABLED', false),
        },
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/playmatch/catalog', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const query = cleanText(req.query.q, 100).toLowerCase()
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 30, 50))
      const page = await searchCatalog(pool, { query, limit, offset: 0 })
      res.json({ items: page.items })
    } catch (error) {
      next(error)
    }
  })

  registerPlayMatchCatalogRoutes(app, {
    pool,
    authRequired,
    getCurrentUser,
    userIdentity,
    publicContext: context,
    encodeCursor,
    decodeCursor,
  })

  app.get('/v1/data/playmatch/profile', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      await ensureGamingProfile(pool, localUserId(ctx.user))
      const bundle = await loadBundle(pool, localUserId(ctx.user))
      res.json({ profile: mapProfileBundle(bundle, { own: true }), preferences: bundle.preferences })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/playmatch/profile', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const userId = localUserId(ctx.user)
      await ensureGamingProfile(pool, userId)
      const expectedVersion = Number(req.body?.expectedVersion)
      if (!Number.isInteger(expectedVersion) || expectedVersion < 1) {
        return res.status(400).json({ error: 'EXPECTED_VERSION_REQUIRED' })
      }
      const timezone = validTimezone(req.body?.timezone)
      if (!timezone) return res.status(400).json({ error: 'INVALID_TIMEZONE' })
      const style = normalizeId(req.body?.playStyle || req.body?.play_style || 'casual')
      if (!PLAY_STYLES.has(style)) return res.status(400).json({ error: 'INVALID_PLAY_STYLE' })
      const gameIdVisibility = normalizeId(req.body?.gameIdVisibility || 'connections')
      const photosVisibility = normalizeId(req.body?.photosVisibility || 'connections')
      const availabilityVisibility = normalizeId(req.body?.availabilityVisibility || 'connections')
      if (![gameIdVisibility, photosVisibility, availabilityVisibility].every((value) => VISIBILITIES.has(value))) {
        return res.status(400).json({ error: 'INVALID_VISIBILITY' })
      }
      const adultConfirmed = Boolean(req.body?.adultConfirmed)
      const discoverable = Boolean(req.body?.discoverable) && (!booleanEnv('PLAYMATCH_REQUIRE_ADULT_CONFIRMATION', true) || adultConfirmed)
      const photos = booleanEnv('PLAYMATCH_PHOTOS_ENABLED', false) ? safePhotos(req.body?.photos) : []
      const { rows } = await pool.query(
        `UPDATE fallback_gaming_profiles
            SET gamer_bio = $3,
                region_code = $4,
                languages = $5::text[],
                play_style = $6,
                voice_preference = $7,
                discovery_level = $8,
                discoverable = $9,
                adult_confirmed = $10,
                game_id_visibility = $11,
                photos_visibility = $12,
                availability_visibility = $13,
                photo_urls = CASE WHEN $16 THEN $14::jsonb ELSE photo_urls END,
                timezone = $15,
                version = version + 1,
                updated_at = NOW()
          WHERE user_id = $1 AND version = $2
          RETURNING version`,
        [
          userId,
          expectedVersion,
          cleanText(req.body?.gamerBio, 500),
          cleanText(req.body?.regionCode, 40).toUpperCase(),
          cleanArray(req.body?.languages, 8, 20),
          style,
          Boolean(req.body?.voicePreference),
          Math.max(0, Math.min(Number(req.body?.discoveryLevel) || 50, 100)),
          discoverable,
          adultConfirmed,
          gameIdVisibility,
          photosVisibility,
          availabilityVisibility,
          JSON.stringify(photos),
          timezone,
          booleanEnv('PLAYMATCH_PHOTOS_ENABLED', false),
        ],
      )
      if (!rows[0]) {
        const current = await pool.query(`SELECT version FROM fallback_gaming_profiles WHERE user_id = $1`, [userId])
        return res.status(409).json({ error: 'VERSION_CONFLICT', currentVersion: Number(current.rows[0]?.version || 1) })
      }
      const bundle = await loadBundle(pool, userId)
      res.json({ profile: mapProfileBundle(bundle, { own: true }), preferences: bundle.preferences })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/playmatch/games', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const userId = localUserId(ctx.user)
      await ensureGamingProfile(pool, userId)
      const gameId = normalizeId(req.body?.gameId)
      const platform = cleanText(req.body?.platform, 40)
      const visibility = normalizeId(req.body?.visibility || 'connections')
      if (!gameId || !platform || !VISIBILITIES.has(visibility)) return res.status(400).json({ error: 'INVALID_GAME' })
      const gameConfiguration = await loadGameConfiguration(pool, gameId)
      if (!gameConfiguration || !gameConfiguration.active || gameConfiguration.supportLevel === 'UNSUPPORTED') {
        return res.status(404).json({ error: 'GAME_NOT_FOUND' })
      }
      const catalog = await pool.query(`SELECT * FROM fallback_game_catalog WHERE id = $1 LIMIT 1`, [gameId])
      const game = catalog.rows[0]
      if (!(game.platforms || []).map(normalizeId).includes(normalizeId(platform))) {
        return res.status(400).json({ error: 'PLATFORM_NOT_SUPPORTED' })
      }
      const gameUserId = cleanText(req.body?.gameUserId, 80)
      if (gameUserId && !/^[\p{L}\p{N}_.#\- ]{2,80}$/u.test(gameUserId)) {
        return res.status(400).json({ error: 'INVALID_GAME_USER_ID' })
      }
      const rank = cleanText(req.body?.rank, 60)
      const role = cleanText(req.body?.role, 60)
      const allowedRanks = gameConfiguration.rankSchemas.flatMap((schema) => schema.ranks.map((item) => normalizeId(item.key)))
      const legacyRanks = (game.ranks || []).map(normalizeId)
      if (rank && !allowedRanks.includes(normalizeId(rank)) && !legacyRanks.includes(normalizeId(rank))) {
        return res.status(400).json({ error: 'RANK_NOT_SUPPORTED' })
      }
      const allowedRoles = gameConfiguration.roleDefinitions.map((item) => normalizeId(item.key))
      const legacyRoles = (game.roles || []).map(normalizeId)
      if (role && !allowedRoles.includes(normalizeId(role)) && !legacyRoles.includes(normalizeId(role))) {
        return res.status(400).json({ error: 'ROLE_NOT_SUPPORTED' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_user_games(
           user_id, game_id, game_user_id, platform, rank_key, level_key, role_key, visibility, is_primary
         ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
         ON CONFLICT (user_id, game_id, platform)
         DO UPDATE SET game_user_id = EXCLUDED.game_user_id,
                       rank_key = EXCLUDED.rank_key,
                       level_key = EXCLUDED.level_key,
                       role_key = EXCLUDED.role_key,
                       visibility = EXCLUDED.visibility,
                       is_primary = EXCLUDED.is_primary,
                       updated_at = NOW()
         RETURNING *`,
        [
          userId,
          gameId,
          gameUserId,
          platform,
          rank,
          cleanText(req.body?.level, 60),
          role,
          visibility,
          Boolean(req.body?.isPrimary),
        ],
      )
      const saved = { ...rows[0], game_name: game.name, game_image_url: game.icon_url || game.logo_url || game.cover_url || game.image_url }
      res.status(201).json({ game: mapGame(saved, true) })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'GAME_ALREADY_EXISTS' })
      next(error)
    }
  })

  app.delete('/v1/data/playmatch/games/:userGameId', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const { rowCount } = await pool.query(
        `DELETE FROM fallback_user_games WHERE id::text = $1 AND user_id = $2`,
        [cleanText(req.params.userGameId, 80), localUserId(ctx.user)],
      )
      if (!rowCount) return res.status(404).json({ error: 'USER_GAME_NOT_FOUND' })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/playmatch/availability', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const userId = localUserId(ctx.user)
      const entries = Array.isArray(req.body?.entries) ? req.body.entries.slice(0, 28) : []
      const normalized = []
      for (const entry of entries) {
        const day = Number(entry?.dayOfWeek)
        const start = Number(entry?.startMinute)
        const end = Number(entry?.endMinute)
        const timezone = validTimezone(entry?.timezone)
        if (!Number.isInteger(day) || day < 0 || day > 6 || !Number.isInteger(start) || start < 0 || start > 1439 || !Number.isInteger(end) || end < 0 || end > 1439 || !timezone) {
          return res.status(400).json({ error: 'INVALID_AVAILABILITY' })
        }
        normalized.push({ day, start, end, timezone })
      }
      await client.query('BEGIN')
      await client.query(`DELETE FROM fallback_gaming_availability WHERE user_id = $1`, [userId])
      for (const entry of normalized) {
        await client.query(
          `INSERT INTO fallback_gaming_availability(user_id, day_of_week, start_minute, end_minute, timezone)
           VALUES ($1,$2,$3,$4,$5)`,
          [userId, entry.day, entry.start, entry.end, entry.timezone],
        )
      }
      await client.query('COMMIT')
      const { rows } = await pool.query(`SELECT * FROM fallback_gaming_availability WHERE user_id = $1 ORDER BY day_of_week, start_minute`, [userId])
      res.json({ items: rows.map(mapAvailability) })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.put('/v1/data/playmatch/preferences', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const userId = localUserId(ctx.user)
      const expectedVersion = Number(req.body?.expectedVersion || 0)
      const criteria = cleanCriteria(req.body)
      const { rows } = await pool.query(
        `INSERT INTO fallback_gaming_preferences(
           user_id, game_ids, platforms, rank_tolerance, roles, styles, languages, regions, voice_required, discovery_level
         ) VALUES ($1,$2::text[],$3::text[],$4,$5::text[],$6::text[],$7::text[],$8::text[],$9,$10)
         ON CONFLICT (user_id) DO UPDATE SET
           game_ids = EXCLUDED.game_ids,
           platforms = EXCLUDED.platforms,
           rank_tolerance = EXCLUDED.rank_tolerance,
           roles = EXCLUDED.roles,
           styles = EXCLUDED.styles,
           languages = EXCLUDED.languages,
           regions = EXCLUDED.regions,
           voice_required = EXCLUDED.voice_required,
           discovery_level = EXCLUDED.discovery_level,
           version = fallback_gaming_preferences.version + 1,
           updated_at = NOW()
         WHERE $11 = 0 OR fallback_gaming_preferences.version = $11
         RETURNING *`,
        [
          userId,
          criteria.gameIds,
          criteria.platforms,
          Math.max(0, Math.min(Number(req.body?.rankTolerance) || 2, 10)),
          criteria.roles,
          criteria.styles,
          criteria.languages,
          criteria.regions,
          criteria.voiceRequired,
          Math.max(0, Math.min(Number(req.body?.discoveryLevel) || 50, 100)),
          expectedVersion,
        ],
      )
      if (!rows[0]) return res.status(409).json({ error: 'VERSION_CONFLICT' })
      res.json({ preferences: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/playmatch/players', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      await ensureGamingProfile(pool, localUserId(ctx.user))
      const viewer = await loadBundle(pool, localUserId(ctx.user))
      const offset = decodeCursor(req.query.cursor)
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 20, 50))
      const filters = {
        query: req.query.q,
        gameId: req.query.gameId,
        platform: req.query.platform,
        region: req.query.region,
      }
      const candidates = await candidateBundles(pool, ctx.user, filters)
      const ranked = candidates
        .map((candidate) => ({ candidate, ...computeCompatibility(viewer, candidate, filters) }))
        .sort((a, b) => b.score - a.score || String(a.candidate.profile.user_id).localeCompare(String(b.candidate.profile.user_id)))
      const page = ranked.slice(offset, offset + limit)
      const connections = await acceptedConnectionSet(pool, localUserId(ctx.user), page.map((entry) => entry.candidate.profile.user_id))
      const items = page.map((entry) => mapProfileBundle(entry.candidate, {
        connected: connections.has(entry.candidate.profile.user_id),
        score: entry.score,
        reasons: entry.reasons,
      }))
      res.set('Cache-Control', 'private, no-store')
      res.json({
        items,
        nextCursor: offset + limit < ranked.length ? encodeCursor(offset + limit) : null,
        safeguards: { preciseLocationUsed: false, privateMessagesUsed: false, ageOrGenderUsed: false },
      })
    } catch (error) {
      if (error?.statusCode) return res.status(error.statusCode).json({ error: error.message })
      next(error)
    }
  })

  app.get('/v1/data/playmatch/players/:playerId', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const target = await findUserByAlias(pool, req.params.playerId)
      if (!target) return res.status(404).json({ error: 'PLAYER_NOT_FOUND' })
      if (await blockedPair(pool, ctx.user, target)) return res.status(404).json({ error: 'PLAYER_NOT_FOUND' })
      const bundle = await loadBundle(pool, localUserId(target))
      if (!bundle || (!bundle.profile.discoverable && localUserId(target) !== localUserId(ctx.user))) {
        return res.status(404).json({ error: 'PLAYER_NOT_FOUND' })
      }
      const viewerBundle = await loadBundle(pool, localUserId(ctx.user))
      const compatibility = computeCompatibility(viewerBundle, bundle)
      const connected = await acceptedConnection(pool, localUserId(ctx.user), localUserId(target))
      res.json({ player: mapProfileBundle(bundle, { own: localUserId(target) === localUserId(ctx.user), connected, ...compatibility }) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/playmatch/connections', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const target = await findUserByAlias(client, req.body?.playerId)
      if (!target || localUserId(target) === localUserId(ctx.user)) return res.status(400).json({ error: 'INVALID_PLAYER' })
      if (await blockedPair(client, ctx.user, target)) return res.status(403).json({ error: 'PLAYER_BLOCKED' })
      const key = cleanText(req.body?.idempotencyKey, 100)
      if (!key) return res.status(400).json({ error: 'IDEMPOTENCY_KEY_REQUIRED' })
      const replay = await client.query(
        `SELECT * FROM fallback_gaming_connection_requests WHERE requester_user_id=$1 AND idempotency_key=$2 LIMIT 1`,
        [localUserId(ctx.user), key],
      )
      if (replay.rows[0]) return res.json({ connection: replay.rows[0] })
      const recent = await client.query(
        `SELECT COUNT(*)::int AS count FROM fallback_gaming_connection_requests
          WHERE requester_user_id = $1 AND created_at > NOW() - INTERVAL '24 hours'`,
        [localUserId(ctx.user)],
      )
      if (Number(recent.rows[0]?.count || 0) >= 20) return res.status(429).json({ error: 'RATE_LIMITED' })
      await client.query('BEGIN')
      await client.query(`SELECT pg_advisory_xact_lock(hashtext(LEAST($1,$2) || ':' || GREATEST($1,$2)))`, [localUserId(ctx.user), localUserId(target)])
      const existing = await client.query(
        `SELECT * FROM fallback_gaming_connection_requests
          WHERE ((requester_user_id=$1 AND recipient_user_id=$2) OR (requester_user_id=$2 AND recipient_user_id=$1))
            AND status IN ('PENDING','ACCEPTED') LIMIT 1`,
        [localUserId(ctx.user), localUserId(target)],
      )
      let row = existing.rows[0]
      if (!row) {
        const inserted = await client.query(
          `INSERT INTO fallback_gaming_connection_requests(
             requester_user_id, recipient_user_id, message, idempotency_key
           ) VALUES ($1,$2,$3,$4) RETURNING *`,
          [localUserId(ctx.user), localUserId(target), cleanText(req.body?.message, 240), key],
        )
        row = inserted.rows[0]
      }
      await client.query('COMMIT')
      void publishRealtimeEvent(`user:${publicUserId(target)}`, { event: 'playmatch.connection', action: 'created', connectionId: row.id })
      res.status(existing.rows[0] ? 200 : 201).json({ connection: row })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.get('/v1/data/playmatch/connections', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const userId = localUserId(ctx.user)
      const { rows } = await pool.query(
        `SELECT cr.*,
                ru.supabase_user_id AS requester_public_id, ru.display_name AS requester_name, ru.avatar_url AS requester_avatar,
                tu.supabase_user_id AS recipient_public_id, tu.display_name AS recipient_name, tu.avatar_url AS recipient_avatar
           FROM fallback_gaming_connection_requests cr
           JOIN fallback_users ru ON ru.id = cr.requester_user_id
           JOIN fallback_users tu ON tu.id = cr.recipient_user_id
          WHERE cr.requester_user_id = $1 OR cr.recipient_user_id = $1
          ORDER BY cr.updated_at DESC LIMIT 100`,
        [userId],
      )
      res.json({ items: rows.map((row) => ({
        id: row.id,
        status: row.status,
        direction: row.requester_user_id === userId ? 'OUTGOING' : 'INCOMING',
        otherUserId: row.requester_user_id === userId ? row.recipient_public_id || row.recipient_user_id : row.requester_public_id || row.requester_user_id,
        otherName: row.requester_user_id === userId ? row.recipient_name : row.requester_name,
        otherAvatarUrl: row.requester_user_id === userId ? row.recipient_avatar : row.requester_avatar,
        message: row.message,
        updatedAt: row.updated_at,
      })) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/playmatch/connections/:connectionId/decision', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const decision = normalizeId(req.body?.decision).toUpperCase()
      if (!['ACCEPT', 'REJECT', 'CANCEL'].includes(decision)) return res.status(400).json({ error: 'INVALID_DECISION' })
      await client.query('BEGIN')
      const locked = await client.query(`SELECT * FROM fallback_gaming_connection_requests WHERE id::text=$1 FOR UPDATE`, [cleanText(req.params.connectionId, 80)])
      const row = locked.rows[0]
      if (!row) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'CONNECTION_NOT_FOUND' })
      }
      const userId = localUserId(ctx.user)
      if (row.status !== 'PENDING') {
        const expectedStatus = decision === 'ACCEPT' ? 'ACCEPTED' : decision === 'REJECT' ? 'REJECTED' : 'CANCELLED'
        if (row.status === expectedStatus) {
          await client.query('ROLLBACK')
          return res.json({ connection: row })
        }
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'CONNECTION_NOT_PENDING' })
      }
      if (decision === 'CANCEL' && row.requester_user_id !== userId) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'NOT_ALLOWED' })
      }
      if (decision !== 'CANCEL' && row.recipient_user_id !== userId) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'NOT_ALLOWED' })
      }
      const status = decision === 'ACCEPT' ? 'ACCEPTED' : decision === 'REJECT' ? 'REJECTED' : 'CANCELLED'
      const updated = await client.query(
        `UPDATE fallback_gaming_connection_requests
            SET status=$2, updated_at=NOW(), responded_at=NOW()
          WHERE id=$1 RETURNING *`,
        [row.id, status],
      )
      await client.query('COMMIT')
      const otherId = row.requester_user_id === userId ? row.recipient_user_id : row.requester_user_id
      const other = await findUserByAlias(pool, otherId)
      void publishRealtimeEvent(`user:${publicUserId(other)}`, { event: 'playmatch.connection', action: status.toLowerCase(), connectionId: row.id })
      res.json({ connection: updated.rows[0] })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.post('/v1/data/playmatch/matchmaking/sessions', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const userId = localUserId(ctx.user)
      await ensureGamingProfile(client, userId)
      const bundle = await loadBundle(client, userId)
      if (booleanEnv('PLAYMATCH_REQUIRE_ADULT_CONFIRMATION', true) && !bundle.profile.adult_confirmed) {
        return res.status(403).json({ error: 'ADULT_CONFIRMATION_REQUIRED' })
      }
      if (!bundle.profile.discoverable) return res.status(403).json({ error: 'PROFILE_NOT_DISCOVERABLE' })
      if (!bundle.games.length) return res.status(409).json({ error: 'ADD_GAME_REQUIRED' })
      const key = cleanText(req.body?.idempotencyKey, 100)
      if (!key) return res.status(400).json({ error: 'IDEMPOTENCY_KEY_REQUIRED' })
      const replay = await client.query(
        `SELECT * FROM fallback_matchmaking_sessions WHERE user_id=$1 AND idempotency_key=$2 LIMIT 1`,
        [userId, key],
      )
      if (replay.rows[0]) return res.json({ session: await sessionResponse(client, replay.rows[0], ctx.user) })
      const recent = await client.query(
        `SELECT COUNT(*)::int AS count FROM fallback_matchmaking_sessions
          WHERE user_id=$1 AND created_at > NOW() - INTERVAL '10 minutes'`,
        [userId],
      )
      if (Number(recent.rows[0]?.count || 0) >= 10) return res.status(429).json({ error: 'RATE_LIMITED' })
      await client.query('BEGIN')
      await client.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [`playmatch:${userId}`])
      await client.query(
        `UPDATE fallback_matchmaking_sessions SET status='TIMEOUT', updated_at=NOW()
          WHERE user_id=$1 AND status IN ('SEARCHING','MATCH_FOUND') AND expires_at <= NOW()`,
        [userId],
      )
      await client.query(
        `UPDATE fallback_gaming_matches SET status='TIMEOUT',updated_at=NOW()
          WHERE session_id IN (SELECT id FROM fallback_matchmaking_sessions WHERE user_id=$1 AND status='TIMEOUT')
            AND status='PENDING' AND expires_at <= NOW()`,
        [userId],
      )
      const existing = await client.query(
        `SELECT * FROM fallback_matchmaking_sessions
          WHERE user_id=$1 AND status IN ('SEARCHING','MATCH_FOUND') LIMIT 1`,
        [userId],
      )
      let session = existing.rows[0]
      if (!session) {
        const criteria = cleanCriteria(req.body)
        const inserted = await client.query(
          `INSERT INTO fallback_matchmaking_sessions(user_id, criteria, idempotency_key, expires_at)
           VALUES ($1,$2::jsonb,$3,NOW()+INTERVAL '5 minutes') RETURNING *`,
          [userId, JSON.stringify(criteria), key],
        )
        session = inserted.rows[0]
        const match = await selectMatch(client, ctx.user, session)
        if (match) {
          session.status = 'MATCH_FOUND'
          session.current_match_id = match.id
        }
      }
      await client.query('COMMIT')
      const response = await sessionResponse(pool, session, ctx.user)
      void publishRealtimeEvent(`user:${publicUserId(ctx.user)}`, { event: 'playmatch.matchmaking', action: response.status.toLowerCase(), session: response })
      if (response.match?.player?.userId) {
        void publishRealtimeEvent(`user:${response.match.player.userId}`, { event: 'playmatch.matchmaking', action: 'candidate_found', sessionId: response.id, matchId: response.match.id })
      }
      res.status(existing.rows[0] ? 200 : 201).json({ session: response })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.get('/v1/data/playmatch/matchmaking/sessions/:sessionId', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      await client.query('BEGIN')
      const { rows } = await client.query(
        `SELECT * FROM fallback_matchmaking_sessions WHERE id::text=$1 AND user_id=$2 FOR UPDATE`,
        [cleanText(req.params.sessionId, 80), localUserId(ctx.user)],
      )
      const session = rows[0]
      if (!session) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'SESSION_NOT_FOUND' })
      }
      if (new Date(session.expires_at).getTime() <= Date.now() && ['SEARCHING', 'MATCH_FOUND'].includes(session.status)) {
        session.status = 'TIMEOUT'
        await client.query(`UPDATE fallback_matchmaking_sessions SET status='TIMEOUT',updated_at=NOW() WHERE id=$1`, [session.id])
        await client.query(`UPDATE fallback_gaming_matches SET status='TIMEOUT',updated_at=NOW() WHERE session_id=$1 AND status='PENDING'`, [session.id])
      } else if (session.status === 'SEARCHING') {
        const match = await selectMatch(client, ctx.user, session)
        if (match) {
          session.status = 'MATCH_FOUND'
          session.current_match_id = match.id
        }
      }
      await client.query('COMMIT')
      res.json({ session: await sessionResponse(pool, session, ctx.user) })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.post('/v1/data/playmatch/matchmaking/sessions/:sessionId/cancel', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const { rows } = await pool.query(
        `UPDATE fallback_matchmaking_sessions
            SET status='CANCELLED', updated_at=NOW()
          WHERE id::text=$1 AND user_id=$2 AND status IN ('SEARCHING','MATCH_FOUND')
          RETURNING *`,
        [cleanText(req.params.sessionId, 80), localUserId(ctx.user)],
      )
      if (!rows[0]) return res.status(409).json({ error: 'SESSION_NOT_CANCELLABLE' })
      await pool.query(`UPDATE fallback_gaming_matches SET status='CANCELLED',updated_at=NOW() WHERE session_id=$1 AND status='PENDING'`, [rows[0].id])
      void publishRealtimeEvent(`user:${publicUserId(ctx.user)}`, { event: 'playmatch.matchmaking', action: 'cancelled', sessionId: rows[0].id })
      res.json({ session: await sessionResponse(pool, rows[0], ctx.user) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/playmatch/matches', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const userId = localUserId(ctx.user)
      const { rows } = await pool.query(
        `SELECT * FROM fallback_gaming_matches
          WHERE user_a_id=$1 OR user_b_id=$1
          ORDER BY created_at DESC LIMIT 50`,
        [userId],
      )
      const otherIds = rows.map((row) => row.user_a_id === userId ? row.user_b_id : row.user_a_id)
      const bundles = await loadBundles(pool, otherIds)
      const items = rows.flatMap((row) => {
        const otherId = row.user_a_id === userId ? row.user_b_id : row.user_a_id
        const bundle = bundles.get(otherId)
        if (!bundle) return []
        return [{
          id: row.id,
          status: row.status,
          score: Number(row.score),
          reasons: row.reasons || [],
          expiresAt: row.expires_at,
          player: mapProfileBundle(bundle, { score: Number(row.score), reasons: row.reasons || [] }),
        }]
      })
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/playmatch/matches/:matchId/decision', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      const decision = normalizeId(req.body?.decision).toUpperCase()
      if (!['ACCEPT', 'SKIP'].includes(decision)) return res.status(400).json({ error: 'INVALID_DECISION' })
      await client.query('BEGIN')
      const locked = await client.query(`SELECT * FROM fallback_gaming_matches WHERE id::text=$1 FOR UPDATE`, [cleanText(req.params.matchId, 80)])
      const match = locked.rows[0]
      const userId = localUserId(ctx.user)
      if (!match || ![match.user_a_id, match.user_b_id].includes(userId)) {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'MATCH_NOT_FOUND' })
      }
      const existingDecision = await client.query(
        `SELECT decision FROM fallback_gaming_match_decisions WHERE match_id=$1 AND user_id=$2 LIMIT 1`,
        [match.id, userId],
      )
      if (existingDecision.rows[0]?.decision === decision) {
        await client.query('ROLLBACK')
        return res.json({ ok: true, status: match.status })
      }
      if (match.status !== 'PENDING' || new Date(match.expires_at).getTime() <= Date.now()) {
        if (match.status === 'PENDING') {
          await client.query(`UPDATE fallback_gaming_matches SET status='TIMEOUT',updated_at=NOW() WHERE id=$1`, [match.id])
          await client.query(`UPDATE fallback_matchmaking_sessions SET status='TIMEOUT',updated_at=NOW() WHERE id=$1`, [match.session_id])
          await client.query('COMMIT')
        } else {
          await client.query('ROLLBACK')
        }
        return res.status(409).json({ error: 'MATCH_NOT_PENDING' })
      }
      if (await blockedPair(client, ctx.user, await findUserByAlias(client, match.user_a_id === userId ? match.user_b_id : match.user_a_id))) {
        await client.query(`UPDATE fallback_gaming_matches SET status='BLOCKED',updated_at=NOW() WHERE id=$1`, [match.id])
        await client.query('COMMIT')
        return res.status(403).json({ error: 'PLAYER_BLOCKED' })
      }
      await client.query(
        `INSERT INTO fallback_gaming_match_decisions(match_id,user_id,decision,reason)
         VALUES ($1,$2,$3,$4)
         ON CONFLICT (match_id,user_id) DO UPDATE SET decision=EXCLUDED.decision,reason=EXCLUDED.reason,created_at=NOW()`,
        [match.id, userId, decision, cleanText(req.body?.reason, 80)],
      )
      if (decision === 'SKIP') {
        await client.query(`UPDATE fallback_gaming_matches SET status='SKIPPED',updated_at=NOW() WHERE id=$1`, [match.id])
        // The matchmaking session belongs to user_a, but either participant may
        // skip. Re-open the owner's search atomically so a candidate decision
        // can never leave the session stuck on a rejected match.
        await client.query(`UPDATE fallback_matchmaking_sessions SET status='SEARCHING',current_match_id=NULL,updated_at=NOW() WHERE id=$1`, [match.session_id])
      } else {
        const decisions = await client.query(`SELECT user_id,decision FROM fallback_gaming_match_decisions WHERE match_id=$1`, [match.id])
        const accepted = new Set(decisions.rows.filter((row) => row.decision === 'ACCEPT').map((row) => row.user_id))
        if (accepted.has(match.user_a_id) && accepted.has(match.user_b_id)) {
          await client.query(`UPDATE fallback_gaming_matches SET status='ACCEPTED',updated_at=NOW() WHERE id=$1`, [match.id])
          await client.query(`UPDATE fallback_matchmaking_sessions SET status='ACCEPTED',updated_at=NOW() WHERE id=$1`, [match.session_id])
          await client.query(
            `INSERT INTO fallback_gaming_connection_requests(requester_user_id,recipient_user_id,status,message,idempotency_key,responded_at)
             VALUES ($1,$2,'ACCEPTED','Match de juego aceptado',$3,NOW())
             ON CONFLICT DO NOTHING`,
            [match.user_a_id, match.user_b_id, `match:${match.id}`],
          )
          match.status = 'ACCEPTED'
        }
      }
      await client.query('COMMIT')
      for (const participantId of [match.user_a_id, match.user_b_id]) {
        const target = await findUserByAlias(pool, participantId)
        void publishRealtimeEvent(`user:${publicUserId(target)}`, { event: 'playmatch.match', action: decision.toLowerCase(), matchId: match.id, status: match.status })
      }
      res.json({ ok: true, status: match.status })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })
}

module.exports = {
  availabilityOverlapMinutes,
  computeCompatibility,
  candidateFitsCriteria,
  decodeCursor,
  encodeCursor,
  ensurePlayMatchTables,
  playMatchEnabledFor,
  registerPlayMatchRoutes,
}
