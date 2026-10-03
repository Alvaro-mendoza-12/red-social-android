const crypto = require('crypto')
const { requireCommunityMusicDuration } = require('./community-music-policy')
const { registerMusicLibraryRoutes } = require('./music-library-routes')
const fs = require('fs')
const path = require('path')
const { execFile } = require('child_process')
const { promisify } = require('util')
const { appAdminHasCapability } = require('./admin-moderation-routes')
const { ADMIN_CAPABILITY } = require('./admin-capabilities')
const {
  getExternalTrack,
  searchJamendo,
  searchOpenverse,
} = require('./music-provider-adapters')

const RIGHTS_TERMS_VERSION = 'community-music-2026-08-14-draft'
const MODERATION_STATES = new Set([
  'UPLOADING', 'PROCESSING', 'PENDING_REVIEW', 'APPROVED', 'REJECTED',
  'CHANGES_REQUIRED', 'DISABLED', 'TAKEDOWN_PENDING', 'REMOVED',
])
const PUBLIC_VISIBILITIES = new Set(['PUBLIC_LISTEN_ONLY', 'PUBLIC_REUSABLE', 'PUBLIC_REMIXABLE'])
const ALL_VISIBILITIES = new Set(['PRIVATE', 'UNLISTED', ...PUBLIC_VISIBILITIES])
const RIGHTS_TYPES = new Set(['ORIGINAL_OWNER', 'AUTHORIZED_LICENSEE', 'PUBLIC_DOMAIN', 'CC0'])
const AUDIO_TYPES = new Set([
  'audio/mpeg', 'audio/mp4', 'audio/wav', 'audio/x-wav', 'audio/ogg',
  'audio/webm', 'audio/flac', 'audio/aac', 'audio/opus',
])
const execFileAsync = promisify(execFile)
const CATALOG_SECTIONS = new Set(['search', 'recommended', 'trending', 'genres', 'community', 'mine'])

function cleanText(value, max = 240) {
  return String(value || '').trim().replace(/\s+/g, ' ').slice(0, max)
}

function cleanMultiline(value, max = 8000) {
  return String(value || '').trim().replace(/\r\n/g, '\n').slice(0, max)
}

function cleanList(value, limit = 20, itemMax = 60) {
  const source = Array.isArray(value) ? value : String(value || '').split(',')
  return Array.from(new Set(source.map((item) => cleanText(item, itemMax)).filter(Boolean))).slice(0, limit)
}

function cleanEnum(value, allowed, fallback) {
  const normalized = cleanText(value, 60).toUpperCase()
  return allowed.has(normalized) ? normalized : fallback
}

function numberIn(value, min, max, fallback) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(min, Math.min(parsed, max)) : fallback
}

function bool(value) {
  return value === true || value === 1 || String(value || '').toLowerCase() === 'true'
}

function cursorOffset(value) {
  const decoded = runSafe(() => Buffer.from(String(value || ''), 'base64url').toString('utf8'))
  const parsed = Number(decoded)
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0
}

function nextCursor(offset, count, limit) {
  return count === limit ? Buffer.from(String(offset + count)).toString('base64url') : null
}

function runSafe(block, fallback = null) {
  try { return block() } catch (_) { return fallback }
}

async function ensureMusicTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_tracks (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      owner_id UUID NOT NULL,
      title TEXT NOT NULL,
      artist_name TEXT NOT NULL,
      collaborators JSONB NOT NULL DEFAULT '[]'::jsonb,
      cover_url TEXT,
      audio_url TEXT NOT NULL,
      storage_object_name TEXT NOT NULL,
      content_type TEXT NOT NULL,
      size_bytes BIGINT NOT NULL,
      duration_ms BIGINT NOT NULL,
      genre TEXT,
      subgenre TEXT,
      mood TEXT,
      tags JSONB NOT NULL DEFAULT '[]'::jsonb,
      language TEXT,
      instrumental BOOLEAN NOT NULL DEFAULT FALSE,
      explicit_content BOOLEAN NOT NULL DEFAULT FALSE,
      release_year INT,
      description TEXT,
      lyrics TEXT,
      rights_type TEXT NOT NULL,
      license_name TEXT,
      license_url TEXT,
      original_source_url TEXT,
      territories JSONB NOT NULL DEFAULT '[]'::jsonb,
      allow_reuse BOOLEAN NOT NULL DEFAULT FALSE,
      allow_remix BOOLEAN NOT NULL DEFAULT FALSE,
      requested_visibility TEXT NOT NULL DEFAULT 'PRIVATE',
      visibility TEXT NOT NULL DEFAULT 'PRIVATE',
      moderation_state TEXT NOT NULL DEFAULT 'PROCESSING',
      moderation_reason TEXT,
      technical_validation TEXT NOT NULL DEFAULT 'SIGNATURE_VALIDATED',
      terms_version TEXT NOT NULL,
      published_at TIMESTAMPTZ,
      reviewed_at TIMESTAMPTZ,
      reviewed_by UUID,
      disabled_at TIMESTAMPTZ,
      removed_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`ALTER TABLE fallback_music_tracks ADD COLUMN IF NOT EXISTS album_name TEXT`)
  await pool.query(`ALTER TABLE fallback_music_tracks ADD COLUMN IF NOT EXISTS attribution TEXT`)
  await pool.query(`ALTER TABLE fallback_music_tracks ADD COLUMN IF NOT EXISTS preview_url TEXT`)
  await pool.query(`ALTER TABLE fallback_music_tracks ADD COLUMN IF NOT EXISTS allow_use_in_moments BOOLEAN NOT NULL DEFAULT FALSE`)
  await pool.query(`ALTER TABLE fallback_music_tracks ADD COLUMN IF NOT EXISTS waveform_url TEXT`)
  await pool.query(`ALTER TABLE fallback_music_tracks ADD COLUMN IF NOT EXISTS source_sha256 TEXT`)
  await pool.query(`UPDATE fallback_music_tracks SET allow_use_in_moments=TRUE WHERE allow_reuse=TRUE AND allow_use_in_moments=FALSE`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_rights_acceptances (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      track_id UUID NOT NULL REFERENCES fallback_music_tracks(id) ON DELETE CASCADE,
      user_id UUID NOT NULL,
      terms_version TEXT NOT NULL,
      rights_type TEXT NOT NULL,
      owns_or_controls_rights BOOLEAN NOT NULL,
      accepts_publication BOOLEAN NOT NULL,
      accepts_moderation BOOLEAN NOT NULL,
      accepts_takedown BOOLEAN NOT NULL,
      evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
      request_ip_hash TEXT,
      user_agent TEXT,
      accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_moderation_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      track_id UUID NOT NULL REFERENCES fallback_music_tracks(id) ON DELETE CASCADE,
      actor_id UUID NOT NULL,
      from_state TEXT,
      to_state TEXT NOT NULL,
      reason TEXT,
      evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_favorites (
      track_id UUID NOT NULL REFERENCES fallback_music_tracks(id) ON DELETE CASCADE,
      user_id UUID NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY(track_id, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_artist_follows (
      artist_id UUID NOT NULL,
      follower_id UUID NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY(artist_id, follower_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_listens (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      track_id UUID NOT NULL REFERENCES fallback_music_tracks(id) ON DELETE CASCADE,
      user_id UUID,
      session_id UUID NOT NULL,
      context TEXT NOT NULL DEFAULT 'catalog',
      started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      completed_at TIMESTAMPTZ,
      listened_ms BIGINT NOT NULL DEFAULT 0,
      counted BOOLEAN NOT NULL DEFAULT FALSE,
      UNIQUE(track_id, session_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_play_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      track_key TEXT NOT NULL,
      user_id UUID,
      event_type TEXT NOT NULL,
      listened_ms BIGINT NOT NULL DEFAULT 0,
      context TEXT NOT NULL DEFAULT 'catalog',
      region_code TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_recommendations (
      user_id UUID NOT NULL,
      track_key TEXT NOT NULL,
      score REAL NOT NULL,
      reason JSONB NOT NULL DEFAULT '{}'::jsonb,
      generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '6 hours'),
      PRIMARY KEY(user_id, track_key)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_reports (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      track_id UUID NOT NULL REFERENCES fallback_music_tracks(id) ON DELETE CASCADE,
      reporter_id UUID NOT NULL,
      reason TEXT NOT NULL,
      details TEXT,
      status TEXT NOT NULL DEFAULT 'OPEN',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      resolved_at TIMESTAMPTZ,
      UNIQUE(track_id, reporter_id, reason)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_external_favorites (
      track_key TEXT NOT NULL,
      user_id UUID NOT NULL,
      metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY(track_key, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_not_interested (
      track_key TEXT NOT NULL,
      user_id UUID NOT NULL,
      metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY(track_key, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_catalog_reports (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      track_key TEXT NOT NULL,
      reporter_id UUID NOT NULL,
      provider TEXT NOT NULL,
      reason TEXT NOT NULL,
      details TEXT,
      metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
      status TEXT NOT NULL DEFAULT 'OPEN',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      resolved_at TIMESTAMPTZ,
      UNIQUE(track_key, reporter_id, reason)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_provider_cache (
      track_key TEXT PRIMARY KEY,
      provider TEXT NOT NULL,
      payload JSONB NOT NULL,
      verified_license TEXT NOT NULL,
      verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      expires_at TIMESTAMPTZ NOT NULL
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_music_retired_hashes (
      source_sha256 TEXT PRIMARY KEY,
      reason TEXT NOT NULL,
      retired_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      retired_by UUID
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moment_music (
      moment_id UUID PRIMARY KEY REFERENCES fallback_moments(id) ON DELETE CASCADE,
      track_key TEXT NOT NULL,
      provider TEXT NOT NULL,
      title TEXT NOT NULL,
      artist TEXT NOT NULL,
      track_owner_id TEXT,
      attribution TEXT,
      source_url TEXT NOT NULL,
      license_name TEXT NOT NULL,
      license_url TEXT NOT NULL,
      start_ms BIGINT NOT NULL,
      end_ms BIGINT NOT NULL,
      used_duration_ms BIGINT NOT NULL DEFAULT 0,
      music_volume REAL NOT NULL DEFAULT 1,
      original_audio_volume REAL NOT NULL DEFAULT 1,
      fade_in_ms BIGINT NOT NULL DEFAULT 0,
      fade_out_ms BIGINT NOT NULL DEFAULT 0,
      duck_original BOOLEAN NOT NULL DEFAULT FALSE,
      embedded_in_media BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  // Device-owned tracks do not have a public source page or a license URL.
  // Those fields are deliberately nullable for local uploads; ownership is
  // verified before a moment is written (see verifyOwnedLocalMomentMusic).
  // Keep this migration idempotent so existing databases created with the
  // earlier NOT NULL definition are repaired on the next API startup.
  await pool.query(`ALTER TABLE fallback_moment_music ALTER COLUMN source_url DROP NOT NULL`)
  await pool.query(`ALTER TABLE fallback_moment_music ALTER COLUMN license_url DROP NOT NULL`)
  await pool.query(`ALTER TABLE fallback_moment_music ADD COLUMN IF NOT EXISTS track_owner_id TEXT`)
  await pool.query(`ALTER TABLE fallback_moment_music ADD COLUMN IF NOT EXISTS attribution TEXT`)
  await pool.query(`ALTER TABLE fallback_moment_music ADD COLUMN IF NOT EXISTS used_duration_ms BIGINT NOT NULL DEFAULT 0`)
  await pool.query(`ALTER TABLE fallback_moment_music ADD COLUMN IF NOT EXISTS embedded_in_media BOOLEAN NOT NULL DEFAULT FALSE`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_public_search ON fallback_music_tracks(moderation_state, visibility, published_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_owner ON fallback_music_tracks(owner_id, updated_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_owner_history ON fallback_music_tracks(owner_id, created_at DESC, id DESC) WHERE removed_at IS NULL`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_listens_track ON fallback_music_listens(track_id, counted, completed_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_play_events_track_time ON fallback_music_play_events(track_key, created_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_play_events_user_time ON fallback_music_play_events(user_id, created_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_recommendations_user_score ON fallback_music_recommendations(user_id, score DESC, generated_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_reports_open ON fallback_music_reports(track_id, status, created_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_catalog_reports_open ON fallback_music_catalog_reports(track_key, status, created_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_music_not_interested_user ON fallback_music_not_interested(user_id, created_at DESC)`)
}

async function ownedMusicStorageObject(pool, ownerId, rawUrl) {
  const url = cleanText(rawUrl, 2000)
  if (!url) return null
  const { rows } = await pool.query(
    `SELECT name, content_type, size_bytes, public_url, local_path
       FROM fallback_storage_objects
      WHERE owner_id = $1::uuid
        AND bucket_id = 'community-music'
        AND (
          public_url = $2 OR
          $2 LIKE ('%/storage/v1/object/public/community-music/' || name) OR
          $2 LIKE ('%/storage/v1/object/community-music/' || name)
        )
      ORDER BY updated_at DESC LIMIT 1`,
    [ownerId, url],
  )
  const row = rows[0]
  if (!row || !AUDIO_TYPES.has(String(row.content_type || '').toLowerCase())) return null
  if (Number(row.size_bytes || 0) <= 0 || Number(row.size_bytes) > 80 * 1024 * 1024) return null
  return row
}

function safeMusicLocalPath(storage) {
  const root = path.resolve(process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'))
  const expected = path.resolve(root, 'community-music', String(storage?.name || ''))
  const actual = path.resolve(storage?.local_path || expected)
  if (!actual.startsWith(`${root}${path.sep}`) || actual !== expected) {
    throw Object.assign(new Error('COMMUNITY_MUSIC_STORAGE_PATH_INVALID'), { status: 400 })
  }
  return actual
}

function musicPreviewLocation(userId, previewId = crypto.randomUUID()) {
  const musicRoot = path.resolve(process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'), 'community-music')
  const previewName = `${userId}/previews/${previewId}.mp3`
  const previewPath = path.resolve(musicRoot, previewName)
  if (!previewPath.startsWith(`${musicRoot}${path.sep}`)) {
    throw Object.assign(new Error('COMMUNITY_MUSIC_PREVIEW_PATH_INVALID'), { status: 400 })
  }
  return { musicRoot, previewName, previewPath }
}

function musicPublicBase(req) {
  const configured = String(process.env.PUBLIC_BASE_URL || '').trim().replace(/\/+$/, '')
  if (configured) return configured
  const proto = String(req.headers['x-forwarded-proto'] || req.protocol || 'http').split(',')[0].trim()
  const host = String(req.headers['x-forwarded-host'] || req.get('host') || '').split(',')[0].trim()
  return `${proto}://${host}`
}

// clamscan loads approximately 1 GiB of signatures per process. This API has a
// bounded container heap: reject overlapping scans instead of exhausting it.
let musicAntivirusBusy = false
async function scanMusicFile(inputPath, { runScan = execFileAsync } = {}) {
  const command = cleanText(process.env.MUSIC_ANTIVIRUS_COMMAND, 300)
  const required = bool(process.env.MUSIC_ANTIVIRUS_REQUIRED)
  if (!command) {
    if (required) {
      throw Object.assign(new Error('MUSIC_ANTIVIRUS_UNAVAILABLE'), { status: 503 })
    }
    return 'ANTIVIRUS_SKIPPED_DEVELOPMENT'
  }
  if (musicAntivirusBusy) throw Object.assign(new Error('MUSIC_ANTIVIRUS_BUSY'), { status: 503 })
  musicAntivirusBusy = true
  try {
    await runScan(command, ['--no-summary', inputPath], {
      timeout: Math.round(numberIn(process.env.MUSIC_ANTIVIRUS_TIMEOUT_MS, 5_000, 180_000, 90_000)),
      killSignal: 'SIGKILL',
      maxBuffer: 2 * 1024 * 1024,
    })
    return `ANTIVIRUS_CLEAN:${path.basename(command)}`
  } catch (error) {
    if (Number(error?.code) === 1) {
      throw Object.assign(new Error('MUSIC_MALWARE_DETECTED'), { status: 422 })
    }
    throw Object.assign(new Error('MUSIC_ANTIVIRUS_UNAVAILABLE'), { status: 503 })
  } finally { musicAntivirusBusy = false }
}

async function inspectAndCreateMusicPreview(pool, req, userId, storage, declaredDurationMs) {
  const inputPath = safeMusicLocalPath(storage)
  const antivirusValidation = await scanMusicFile(inputPath)
  const input = await fs.promises.readFile(inputPath)
  const sourceSha256 = crypto.createHash('sha256').update(input).digest('hex')
  const retired = await pool.query(
    `SELECT 1 FROM fallback_music_retired_hashes WHERE source_sha256=$1 LIMIT 1`,
    [sourceSha256],
  )
  if (retired.rowCount > 0) throw Object.assign(new Error('MUSIC_PREVIOUSLY_REMOVED'), { status: 409 })

  let probe
  try {
    const result = await execFileAsync(
      'ffprobe',
      ['-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=codec_type:format=duration', '-of', 'json', inputPath],
      { timeout: 20_000, maxBuffer: 1024 * 1024 },
    )
    probe = JSON.parse(result.stdout)
  } catch (_) {
    throw Object.assign(new Error('MUSIC_AUDIO_DECODE_FAILED'), { status: 400 })
  }
  if (!probe?.streams?.some((stream) => stream.codec_type === 'audio')) {
    throw Object.assign(new Error('MUSIC_AUDIO_STREAM_REQUIRED'), { status: 400 })
  }
  const probedDurationMs = requireCommunityMusicDuration(Math.round(Number(probe?.format?.duration || 0) * 1000))
  if (Math.abs(probedDurationMs - declaredDurationMs) > Math.max(2_000, probedDurationMs * 0.05)) {
    throw Object.assign(new Error('MUSIC_DURATION_MISMATCH'), { status: 400 })
  }

  const { previewName, previewPath } = musicPreviewLocation(userId)
  // Source objects can contain both the authenticated owner prefix and a
  // client-side profile prefix. Previews, however, must always be resolved
  // from the bucket root so their DB name, public URL and disk path agree.
  await fs.promises.mkdir(path.dirname(previewPath), { recursive: true })
  try {
    await execFileAsync(
      'ffmpeg',
      ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-i', inputPath, '-map', '0:a:0', '-t', '30', '-vn', '-ac', '2', '-ar', '44100', '-b:a', '128k', previewPath],
      { timeout: 60_000, maxBuffer: 2 * 1024 * 1024 },
    )
    const stat = await fs.promises.stat(previewPath)
    const encodedName = previewName.split('/').map(encodeURIComponent).join('/')
    const previewUrl = `${musicPublicBase(req)}/storage/v1/object/public/community-music/${encodedName}`
    await pool.query(
      `INSERT INTO fallback_storage_objects(bucket_id,name,owner_id,content_type,size_bytes,public_url,local_path,metadata)
       VALUES('community-music',$1,$2::uuid,'audio/mpeg',$3,$4,$5,$6::jsonb)
       ON CONFLICT(bucket_id,name) DO UPDATE SET size_bytes=EXCLUDED.size_bytes,public_url=EXCLUDED.public_url,
         local_path=EXCLUDED.local_path,metadata=EXCLUDED.metadata,updated_at=NOW()`,
      [previewName, userId, stat.size, previewUrl, previewPath, JSON.stringify({ role: 'safe-preview', sourceSha256 })],
    )
    return { previewUrl, sourceSha256, durationMs: probedDurationMs, antivirusValidation }
  } catch (error) {
    await fs.promises.unlink(previewPath).catch(() => {})
    if (error?.status) throw error
    throw Object.assign(new Error('MUSIC_PREVIEW_GENERATION_FAILED'), { status: 422 })
  }
}

async function ownedMusicCoverStorageObject(pool, ownerId, rawUrl) {
  const url = cleanText(rawUrl, 2000)
  if (!url) return null
  const { rows } = await pool.query(
    `SELECT name, content_type, size_bytes, public_url
       FROM fallback_storage_objects
      WHERE owner_id=$1::uuid AND bucket_id='thumbnails'
        AND (public_url=$2 OR $2 LIKE ('%/storage/v1/object/public/thumbnails/' || name)
          OR $2 LIKE ('%/storage/v1/object/thumbnails/' || name))
      ORDER BY updated_at DESC LIMIT 1`,
    [ownerId, url],
  )
  const row = rows[0]
  if (!row || !new Set(['image/jpeg', 'image/png', 'image/webp']).has(String(row.content_type || '').toLowerCase())) return null
  if (Number(row.size_bytes || 0) <= 0 || Number(row.size_bytes) > 10 * 1024 * 1024) return null
  return row
}

function mapTrack(row, extras = {}) {
  return {
    id: `community:${row.id}`,
    rawId: String(row.id),
    ownerId: String(row.owner_id),
    title: row.title,
    artist: row.artist_name,
    album: row.album_name || null,
    collaborators: row.collaborators || [],
    coverUrl: row.cover_url || null,
    artworkUrl: row.cover_url || null,
    audioUrl: row.audio_url,
    previewUrl: row.preview_url || row.audio_url,
    durationMs: Number(row.duration_ms || 0),
    genre: row.genre || null,
    subgenre: row.subgenre || null,
    mood: row.mood || null,
    tags: row.tags || [],
    categories: Array.from(new Set([row.genre, row.subgenre, row.mood, ...(row.tags || [])].filter(Boolean))),
    language: row.language || null,
    instrumental: row.instrumental === true,
    explicit: row.explicit_content === true,
    year: row.release_year || null,
    description: row.description || null,
    lyrics: row.lyrics || null,
    source: 'novvin_community',
    provider: 'Novvin Community',
    sourcePageUrl: extras.sourcePageUrl || `/v1/data/music/tracks/community:${row.id}`,
    license: row.license_name || row.rights_type,
    licenseUrl: row.license_url || null,
    attribution: row.attribution || `${row.title} — ${row.artist_name}`,
    waveformUrl: row.waveform_url || null,
    visibility: row.visibility,
    requestedVisibility: row.requested_visibility,
    allowReuse: row.allow_reuse === true,
    allowRemix: row.allow_remix === true,
    allowUseInMoments: row.allow_use_in_moments === true && row.allow_reuse === true,
    moderationState: row.moderation_state,
    moderationReason: row.moderation_reason || null,
    favoriteCount: Number(extras.favorite_count || row.favorite_count || 0),
    listenCount: Number(extras.listen_count || row.listen_count || 0),
    useCount: Number(extras.use_count || row.use_count || 0),
    isFavorite: extras.is_favorite === true || row.is_favorite === true,
    publishedAt: row.published_at || null,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  }
}

function publicTrackWhere(alias = 't') {
  return `${alias}.moderation_state = 'APPROVED' AND ${alias}.visibility IN ('PUBLIC_LISTEN_ONLY','PUBLIC_REUSABLE','PUBLIC_REMIXABLE') AND ${alias}.removed_at IS NULL AND ${alias}.disabled_at IS NULL`
}

async function trackWithCounts(pool, trackId, userId = null, requirePublic = false) {
  const { rows } = await pool.query(
    `SELECT t.*,
       (SELECT COUNT(*)::int FROM fallback_music_favorites f WHERE f.track_id = t.id) AS favorite_count,
       (SELECT COUNT(*)::int FROM fallback_music_listens l WHERE l.track_id = t.id AND l.counted = TRUE) AS listen_count,
       EXISTS(SELECT 1 FROM fallback_music_favorites f WHERE f.track_id = t.id AND f.user_id = $2::uuid) AS is_favorite
     FROM fallback_music_tracks t
     WHERE t.id = $1::uuid ${requirePublic ? `AND ${publicTrackWhere('t')}` : ''}
     LIMIT 1`,
    [trackId, userId],
  )
  return rows[0] || null
}

function rawTrackId(value) {
  const clean = cleanText(value, 100).replace(/^community:/, '')
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(clean) ? clean : null
}

async function musicIdentity(pool, req, getCurrentUser, userIdentity) {
  const user = await getCurrentUser(pool, req)
  if (!user) return null
  return { user, identity: userIdentity(user), userId: String(user.id) }
}

async function recordModeration(pool, trackId, actorId, fromState, toState, reason, evidence = {}) {
  if (!MODERATION_STATES.has(toState)) throw Object.assign(new Error('INVALID_MODERATION_STATE'), { status: 400 })
  await pool.query(
    `INSERT INTO fallback_music_moderation_events(track_id, actor_id, from_state, to_state, reason, evidence)
     VALUES($1::uuid, $2::uuid, $3, $4, $5, $6::jsonb)`,
    [trackId, actorId, fromState || null, toState, reason || null, JSON.stringify(evidence || {})],
  )
}

function catalogCursor(value) {
  const parsed = runSafe(() => JSON.parse(Buffer.from(String(value || ''), 'base64url').toString('utf8')), {}) || {}
  return {
    providerPage: Math.max(1, Math.round(Number(parsed.providerPage) || 1)),
    communityOffset: Math.max(0, Math.round(Number(parsed.communityOffset) || 0)),
  }
}

function encodeCatalogCursor(value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url')
}

async function communityCatalogPage(pool, userId, { query = '', category = '', section = 'search', offset = 0, limit = 20 } = {}) {
  const q = cleanText(query, 100).toLowerCase()
  const genre = cleanText(category, 60).toLowerCase()
  const mine = section === 'mine'
  const { rows } = await pool.query(
    `SELECT t.*,
       (SELECT COUNT(*)::int FROM fallback_music_favorites f WHERE f.track_id=t.id) favorite_count,
       (SELECT COUNT(*)::int FROM fallback_music_listens l WHERE l.track_id=t.id AND l.counted) listen_count,
       EXISTS(SELECT 1 FROM fallback_music_favorites f WHERE f.track_id=t.id AND f.user_id=$1::uuid) is_favorite
     FROM fallback_music_tracks t
     WHERE (${mine ? 't.owner_id=$1::uuid AND t.removed_at IS NULL' : `${publicTrackWhere('t')} AND t.allow_reuse=TRUE AND t.allow_use_in_moments=TRUE`})
       AND ($2='' OR lower(t.title) LIKE '%'||$2||'%' OR lower(t.artist_name) LIKE '%'||$2||'%' OR lower(COALESCE(t.album_name,'')) LIKE '%'||$2||'%')
       AND ($3='' OR lower(COALESCE(t.genre,''))=$3 OR lower(COALESCE(t.subgenre,''))=$3 OR lower(COALESCE(t.mood,''))=$3)
     ORDER BY
       ${section === 'trending' ? `(SELECT COUNT(*) FROM fallback_music_listens l WHERE l.track_id=t.id AND l.counted AND l.completed_at > NOW()-INTERVAL '7 days') DESC,` : ''}
       ${section === 'recommended' ? `EXISTS(SELECT 1 FROM fallback_music_artist_follows af WHERE af.artist_id=t.owner_id AND af.follower_id=$1::uuid) DESC,` : ''}
       t.published_at DESC NULLS LAST, t.updated_at DESC
     LIMIT $4 OFFSET $5`,
    [userId, q, genre, limit, offset],
  )
  return rows.map((row) => mapTrack(row))
}

async function cacheProviderTracks(pool, tracks) {
  for (const track of tracks) {
    await pool.query(
      `INSERT INTO fallback_music_provider_cache(track_key,provider,payload,verified_license,verified_at,expires_at)
       VALUES($1,$2,$3::jsonb,$4,NOW(),NOW()+INTERVAL '6 hours')
       ON CONFLICT(track_key) DO UPDATE SET provider=EXCLUDED.provider,payload=EXCLUDED.payload,
         verified_license=EXCLUDED.verified_license,verified_at=NOW(),expires_at=EXCLUDED.expires_at`,
      [track.id, track.source, JSON.stringify(track), track.license],
    )
  }
}

async function cachedProviderTrack(pool, trackKey) {
  const { rows } = await pool.query(
    `SELECT payload FROM fallback_music_provider_cache WHERE track_key=$1 AND expires_at>NOW() LIMIT 1`,
    [trackKey],
  )
  return rows[0]?.payload || null
}

async function resolveCatalogTrack(pool, trackKey, { refreshExternal = false } = {}) {
  const key = cleanText(trackKey, 180)
  if (key.startsWith('community:')) {
    const rawId = rawTrackId(key)
    const row = rawId ? await trackWithCounts(pool, rawId, null, true) : null
    return row ? mapTrack(row) : null
  }
  if (!refreshExternal) {
    const cached = await cachedProviderTrack(pool, key)
    if (cached) return cached
  }
  const external = await getExternalTrack(key)
  if (external) await cacheProviderTracks(pool, [external])
  return external
}

function registerMusicRoutes(app, {
  pool,
  authRequired,
  getCurrentUser,
  userIdentity,
  createLocalNotification,
}) {
  registerMusicLibraryRoutes(app, {
    pool, authRequired, rawTrackId, mapTrack,
    identity: (req) => musicIdentity(pool, req, getCurrentUser, userIdentity),
  })
  app.get('/v1/data/music/terms', authRequired, (_req, res) => {
    res.json({
      version: RIGHTS_TERMS_VERSION,
      draft: true,
      legalReviewRequired: true,
      summary: 'Solo puedes publicar audio original, autorizado, CC0 o de dominio público. Novvin puede moderar, deshabilitar o retirar contenido ante reclamos.',
    })
  })

  app.get('/v1/data/music/catalog/categories', authRequired, (_req, res) => {
    res.json({
      items: [
        { id: 'instrumental', label: 'Instrumental' },
        { id: 'ambient', label: 'Ambiental' },
        { id: 'classical', label: 'Clásica' },
        { id: 'electronic', label: 'Electrónica' },
        { id: 'jazz', label: 'Jazz' },
        { id: 'nature', label: 'Naturaleza' },
        { id: 'piano', label: 'Piano' },
        { id: 'soundtrack', label: 'Soundtrack' },
      ],
      externalLicensePolicy: ['CC0 1.0', 'Public Domain'],
    })
  })

  app.get('/v1/data/music/catalog/search', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      if (!current) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const section = cleanEnum(req.query?.section, new Set(Array.from(CATALOG_SECTIONS).map((value) => value.toUpperCase())), 'SEARCH').toLowerCase()
      const query = cleanText(req.query?.q, 100)
      const category = cleanText(req.query?.category || req.query?.genre, 60)
      const limit = Math.round(numberIn(req.query?.limit, 1, 40, 20))
      const cursor = catalogCursor(req.query?.cursor)
      const includeCommunity = !['genres'].includes(section) || Boolean(category)
      const includeExternal = !['community', 'mine'].includes(section)

      const communityPromise = includeCommunity
        ? communityCatalogPage(pool, current.userId, {
          query,
          category,
          section,
          offset: cursor.communityOffset,
          limit,
        })
        : Promise.resolve([])
      const providerMode = section === 'trending' ? 'trending' : 'relevance'
      const openversePromise = includeExternal
        ? searchOpenverse({ query, category, page: cursor.providerPage, limit, order: providerMode }).catch(() => ({ items: [], nextPage: null }))
        : Promise.resolve({ items: [], nextPage: null })
      const jamendoPromise = includeExternal
        ? searchJamendo({ query, category, page: cursor.providerPage, limit }).catch(() => ({ items: [], nextPage: null }))
        : Promise.resolve({ items: [], nextPage: null })
      const [community, openverse, jamendo] = await Promise.all([communityPromise, openversePromise, jamendoPromise])
      const external = [...openverse.items, ...jamendo.items]
      await cacheProviderTracks(pool, external)
      const hiddenRows = await pool.query(
        `SELECT track_key FROM fallback_music_not_interested WHERE user_id=$1::uuid`,
        [current.userId],
      )
      const hiddenTrackKeys = new Set(hiddenRows.rows.map((row) => row.track_key))
      const items = [...community, ...external]
        .filter((track) => section === 'mine' || track.allowUseInMoments === true || track.allowReuse === true)
        .filter((track) => section === 'mine' || !hiddenTrackKeys.has(track.id))
        .filter((track, index, all) => all.findIndex((candidate) => candidate.id === track.id) === index)
        .slice(0, limit * 2)
      const hasMoreCommunity = community.length === limit
      const hasMoreProvider = Boolean(openverse.nextPage || jamendo.nextPage)
      res.json({
        items,
        nextCursor: (hasMoreCommunity || hasMoreProvider)
          ? encodeCatalogCursor({
            communityOffset: cursor.communityOffset + community.length,
            providerPage: hasMoreProvider ? cursor.providerPage + 1 : cursor.providerPage,
          })
          : null,
        section,
        providers: {
          community: includeCommunity,
          openverse: includeExternal,
          jamendo: includeExternal && Boolean(process.env.JAMENDO_CLIENT_ID),
        },
        legalPolicy: 'EXTERNAL_CC0_OR_PUBLIC_DOMAIN_ONLY',
      })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/music/catalog/tracks/:trackKey', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const track = current ? await resolveCatalogTrack(pool, req.params.trackKey, { refreshExternal: true }) : null
      if (!track || (track.allowUseInMoments !== true && track.allowReuse !== true)) {
        return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      }
      let isFavorite = track.isFavorite === true
      if (!track.id.startsWith('community:')) {
        const favorite = await pool.query(
          `SELECT 1 FROM fallback_music_external_favorites WHERE track_key=$1 AND user_id=$2::uuid`,
          [track.id, current.userId],
        )
        isFavorite = favorite.rowCount > 0
      }
      res.json({ track: { ...track, isFavorite }, attributionRequired: true })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/music/catalog/tracks/:trackKey/moments', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const track = current ? await resolveCatalogTrack(pool, req.params.trackKey) : null
      if (!track) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const limit = Math.round(numberIn(req.query?.limit, 1, 50, 20))
      const offset = cursorOffset(req.query?.cursor)
      const { rows } = await pool.query(
        `SELECT m.id::text,m.user_id,m.media_url,m.media_type,m.thumbnail_url,m.caption,
                m.created_at,m.expires_at
           FROM fallback_moment_music mm
           JOIN fallback_moments m ON m.id=mm.moment_id
          WHERE mm.track_key=$1 AND m.expires_at>NOW()
            AND m.visibility_type NOT IN ('private','silent')
            AND m.visibility IN ('all','public')
          ORDER BY m.created_at DESC LIMIT $2 OFFSET $3`,
        [track.id, limit, offset],
      )
      res.json({ items: rows, nextCursor: nextCursor(offset, rows.length, limit) })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/catalog/:trackKey/not-interested', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const track = current ? await resolveCatalogTrack(pool, req.params.trackKey) : null
      if (!track) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      await pool.query(
        `INSERT INTO fallback_music_not_interested(track_key,user_id,metadata)
         VALUES($1,$2::uuid,$3::jsonb)
         ON CONFLICT(track_key,user_id) DO UPDATE SET metadata=EXCLUDED.metadata,created_at=NOW()`,
        [track.id, current.userId, JSON.stringify({ source: track.source, reason: cleanText(req.body?.reason, 80) || 'not_interested' })],
      )
      res.status(201).json({ hidden: true })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/catalog/:trackKey/play-events', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const track = current ? await resolveCatalogTrack(pool, req.params.trackKey) : null
      if (!track) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const eventType = cleanText(req.body?.eventType, 40)
      if (!new Set(['preview_started', 'preview_completed']).has(eventType)) {
        return res.status(400).json({ error: 'MUSIC_PLAY_EVENT_INVALID' })
      }
      const regionCode = /^[A-Z]{2}$/.test(String(req.headers['cf-ipcountry'] || '').toUpperCase())
        ? String(req.headers['cf-ipcountry']).toUpperCase() : null
      await pool.query(
        `INSERT INTO fallback_music_play_events(track_key,user_id,event_type,listened_ms,context,region_code)
         VALUES($1,$2::uuid,$3,$4,$5,$6)`,
        [
          track.id,
          current.userId,
          eventType,
          Math.round(numberIn(req.body?.listenedMs, 0, 15 * 60_000, 0)),
          cleanText(req.body?.context, 40) || 'moment_picker',
          regionCode,
        ],
      )
      res.status(201).json({ recorded: true })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/catalog/:trackKey/favorite', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const track = current ? await resolveCatalogTrack(pool, req.params.trackKey) : null
      if (!track) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      if (track.id.startsWith('community:')) {
        const rawId = rawTrackId(track.id)
        await pool.query(`INSERT INTO fallback_music_favorites(track_id,user_id) VALUES($1::uuid,$2::uuid) ON CONFLICT DO NOTHING`, [rawId, current.userId])
      } else {
        await pool.query(
          `INSERT INTO fallback_music_external_favorites(track_key,user_id,metadata) VALUES($1,$2::uuid,$3::jsonb)
           ON CONFLICT(track_key,user_id) DO UPDATE SET metadata=EXCLUDED.metadata`,
          [track.id, current.userId, JSON.stringify(track)],
        )
      }
      res.status(201).json({ favorite: true })
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/music/catalog/:trackKey/favorite', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      if (!current) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const trackKey = cleanText(req.params.trackKey, 180)
      if (trackKey.startsWith('community:')) {
        const rawId = rawTrackId(trackKey)
        if (rawId) await pool.query(`DELETE FROM fallback_music_favorites WHERE track_id=$1::uuid AND user_id=$2::uuid`, [rawId, current.userId])
      } else {
        await pool.query(`DELETE FROM fallback_music_external_favorites WHERE track_key=$1 AND user_id=$2::uuid`, [trackKey, current.userId])
      }
      res.json({ favorite: false })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/catalog/:trackKey/report', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const track = current ? await resolveCatalogTrack(pool, req.params.trackKey) : null
      const reason = cleanText(req.body?.reason, 80)
      if (!track || !reason) return res.status(400).json({ error: 'TRACK_AND_REASON_REQUIRED' })
      if (track.id.startsWith('community:')) {
        const rawId = rawTrackId(track.id)
        await pool.query(
          `INSERT INTO fallback_music_reports(track_id,reporter_id,reason,details) VALUES($1::uuid,$2::uuid,$3,$4)
           ON CONFLICT(track_id,reporter_id,reason) DO UPDATE SET details=EXCLUDED.details,status='OPEN',created_at=NOW()`,
          [rawId, current.userId, reason, cleanMultiline(req.body?.details, 2000) || null],
        )
      } else {
        await pool.query(
          `INSERT INTO fallback_music_catalog_reports(track_key,reporter_id,provider,reason,details,metadata)
           VALUES($1,$2::uuid,$3,$4,$5,$6::jsonb)
           ON CONFLICT(track_key,reporter_id,reason) DO UPDATE SET details=EXCLUDED.details,status='OPEN',created_at=NOW()`,
          [track.id, current.userId, track.source, reason, cleanMultiline(req.body?.details, 2000) || null, JSON.stringify(track)],
        )
      }
      res.status(201).json({ reported: true })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/upload', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      if (!current) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const title = cleanText(req.body?.title, 140)
      const artist = cleanText(req.body?.artist, 120) || cleanText(current.user.display_name || current.user.name, 120)
      const audioUrl = cleanText(req.body?.audioUrl, 2000)
      const storage = await ownedMusicStorageObject(pool, current.userId, audioUrl)
      const requestedCoverUrl = cleanText(req.body?.coverUrl, 2000)
      const coverStorage = requestedCoverUrl
        ? await ownedMusicCoverStorageObject(pool, current.userId, requestedCoverUrl)
        : null
      if (!title || !artist) return res.status(400).json({ error: 'TITLE_AND_ARTIST_REQUIRED' })
      if (!storage) return res.status(403).json({ error: 'COMMUNITY_MUSIC_STORAGE_OWNERSHIP_REQUIRED' })
      if (requestedCoverUrl && !coverStorage) return res.status(403).json({ error: 'COMMUNITY_MUSIC_COVER_OWNERSHIP_REQUIRED' })

      const rightsType = cleanEnum(req.body?.rightsType, RIGHTS_TYPES, '')
      if (!rightsType) return res.status(400).json({ error: 'RIGHTS_TYPE_REQUIRED' })
      const termsVersion = cleanText(req.body?.termsVersion, 100)
      if (termsVersion !== RIGHTS_TERMS_VERSION) return res.status(409).json({ error: 'MUSIC_TERMS_VERSION_REQUIRED', currentVersion: RIGHTS_TERMS_VERSION })
      const declarations = req.body?.declarations || {}
      if (![declarations.ownsOrControlsRights, declarations.acceptsPublication, declarations.acceptsModeration, declarations.acceptsTakedown].every(bool)) {
        return res.status(400).json({ error: 'RIGHTS_DECLARATIONS_REQUIRED' })
      }
      const requestedVisibility = cleanEnum(req.body?.visibility, ALL_VISIBILITIES, 'PRIVATE')
      const allowReuse = bool(req.body?.allowReuse) && requestedVisibility !== 'PUBLIC_LISTEN_ONLY'
      const allowRemix = bool(req.body?.allowRemix) && requestedVisibility === 'PUBLIC_REMIXABLE'
      const durationMs = requireCommunityMusicDuration(req.body?.durationMs)
      const processing = await inspectAndCreateMusicPreview(pool, req, current.userId, storage, durationMs)

      const client = await pool.connect()
      try {
        await client.query('BEGIN')
        const inserted = await client.query(
          `INSERT INTO fallback_music_tracks(
             owner_id, title, artist_name, collaborators, cover_url, audio_url, storage_object_name,
             content_type, size_bytes, duration_ms, genre, subgenre, mood, tags, language, instrumental,
             explicit_content, release_year, description, lyrics, rights_type, license_name, license_url,
             original_source_url, territories, allow_reuse, allow_remix, requested_visibility, visibility,
             moderation_state, terms_version
           ) VALUES(
             $1::uuid,$2,$3,$4::jsonb,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14::jsonb,$15,$16,$17,$18,$19,$20,
             $21,$22,$23,$24,$25::jsonb,$26,$27,$28,'PRIVATE','PROCESSING',$29
           ) RETURNING *`,
          [
            current.userId, title, artist, JSON.stringify(cleanList(req.body?.collaborators, 12, 100)),
            requestedCoverUrl || null, audioUrl, storage.name, storage.content_type,
            Number(storage.size_bytes), durationMs, cleanText(req.body?.genre, 60) || null,
            cleanText(req.body?.subgenre, 60) || null, cleanText(req.body?.mood, 60) || null,
            JSON.stringify(cleanList(req.body?.tags)), cleanText(req.body?.language, 40) || null,
            bool(req.body?.instrumental), bool(req.body?.explicit),
            Math.round(numberIn(req.body?.year, 1900, new Date().getUTCFullYear() + 1, 0)) || null,
            cleanMultiline(req.body?.description, 3000) || null, cleanMultiline(req.body?.lyrics, 12000) || null,
            rightsType, cleanText(req.body?.licenseName, 120) || rightsType,
            cleanText(req.body?.licenseUrl, 2000) || null, cleanText(req.body?.originalSourceUrl, 2000) || null,
            JSON.stringify(cleanList(req.body?.territories, 80, 3)), allowReuse, allowRemix,
            requestedVisibility, RIGHTS_TERMS_VERSION,
          ],
        )
        let track = inserted.rows[0]
        const enriched = await client.query(
          `UPDATE fallback_music_tracks SET
             album_name=$2, attribution=$3, preview_url=$5, source_sha256=$6,
             allow_use_in_moments=$4, waveform_url=$7
           WHERE id=$1::uuid RETURNING *`,
          [
            track.id,
            cleanText(req.body?.album, 140) || null,
            cleanText(req.body?.attribution, 500) || `${title} — ${artist}`,
            allowReuse,
            processing.previewUrl,
            processing.sourceSha256,
            cleanText(req.body?.waveformUrl, 2000) || null,
          ],
        )
        track = enriched.rows[0] || track
        const ipHash = crypto.createHash('sha256').update(`${req.ip || ''}:${process.env.MUSIC_AUDIT_SALT || 'novvin'}`).digest('hex')
        await client.query(
          `INSERT INTO fallback_music_rights_acceptances(
             track_id,user_id,terms_version,rights_type,owns_or_controls_rights,accepts_publication,
             accepts_moderation,accepts_takedown,evidence,request_ip_hash,user_agent
           ) VALUES($1::uuid,$2::uuid,$3,$4,TRUE,TRUE,TRUE,TRUE,$5::jsonb,$6,$7)`,
          [track.id, current.userId, RIGHTS_TERMS_VERSION, rightsType, JSON.stringify(req.body?.evidence || {}), ipHash, cleanText(req.headers['user-agent'], 300)],
        )
        const validatedTrack = await client.query(
          `UPDATE fallback_music_tracks SET duration_ms=$2,technical_validation=$3 WHERE id=$1::uuid RETURNING *`,
          [track.id, processing.durationMs, `FFPROBE_VALIDATED_PREVIEW_GENERATED|${processing.antivirusValidation}`],
        )
        track = validatedTrack.rows[0] || track
        await recordModeration(client, track.id, current.userId, null, 'PROCESSING', 'Firma, decodificación y previsualización segura validadas por servidor')
        await client.query('COMMIT')
        res.status(201).json({ track: mapTrack(track), termsVersion: RIGHTS_TERMS_VERSION })
      } catch (error) {
        await client.query('ROLLBACK')
        throw error
      } finally {
        client.release()
      }
    } catch (error) { next(error) }
  })

  app.patch('/v1/data/music/:trackId', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      if (!current || !trackId) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const currentTrack = await trackWithCounts(pool, trackId, current.userId)
      if (!currentTrack || String(currentTrack.owner_id) !== current.userId) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      if (!['PROCESSING', 'REJECTED', 'CHANGES_REQUIRED'].includes(currentTrack.moderation_state)) {
        return res.status(409).json({ error: 'TRACK_LOCKED_DURING_MODERATION' })
      }
      const visibility = cleanEnum(req.body?.visibility, ALL_VISIBILITIES, currentTrack.requested_visibility)
      const { rows } = await pool.query(
        `UPDATE fallback_music_tracks SET
           title=$3, artist_name=$4, genre=$5, subgenre=$6, mood=$7, tags=$8::jsonb,
           description=$9, lyrics=$10, explicit_content=$11, instrumental=$12,
           requested_visibility=$13, allow_reuse=$14, allow_remix=$15, updated_at=NOW()
         WHERE id=$1::uuid AND owner_id=$2::uuid RETURNING *`,
        [trackId, current.userId, cleanText(req.body?.title, 140) || currentTrack.title,
          cleanText(req.body?.artist, 120) || currentTrack.artist_name,
          cleanText(req.body?.genre, 60) || null, cleanText(req.body?.subgenre, 60) || null,
          cleanText(req.body?.mood, 60) || null, JSON.stringify(cleanList(req.body?.tags)),
          cleanMultiline(req.body?.description, 3000) || null, cleanMultiline(req.body?.lyrics, 12000) || null,
          bool(req.body?.explicit), bool(req.body?.instrumental), visibility,
          bool(req.body?.allowReuse) && visibility !== 'PUBLIC_LISTEN_ONLY',
          bool(req.body?.allowRemix) && visibility === 'PUBLIC_REMIXABLE'],
      )
      res.json({ track: mapTrack(rows[0]) })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/:trackId/publish', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      if (!current || !trackId) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const { rows } = await pool.query(
        `UPDATE fallback_music_tracks t SET
            moderation_state='APPROVED',
            moderation_reason='Aprobada automáticamente después de validación técnica y declaración de derechos',
            visibility=CASE
              WHEN t.requested_visibility IN ('PUBLIC_LISTEN_ONLY','PUBLIC_REUSABLE','PUBLIC_REMIXABLE')
                THEN t.requested_visibility
              ELSE 'PUBLIC_REUSABLE'
            END,
            published_at=COALESCE(t.published_at,NOW()),
            reviewed_at=NOW(),
            updated_at=NOW()
          WHERE t.id=$1::uuid AND t.owner_id=$2::uuid
            AND t.removed_at IS NULL AND t.disabled_at IS NULL
            AND t.moderation_state IN ('PROCESSING','REJECTED','CHANGES_REQUIRED')
            AND t.technical_validation LIKE 'FFPROBE_VALIDATED_PREVIEW_GENERATED|%'
            AND t.preview_url IS NOT NULL
            AND t.duration_ms BETWEEN 5000 AND 270000
            AND EXISTS(
              SELECT 1 FROM fallback_music_rights_acceptances a
               WHERE a.track_id=t.id AND a.user_id=t.owner_id AND a.terms_version=$3
                 AND a.owns_or_controls_rights AND a.accepts_publication AND a.accepts_moderation AND a.accepts_takedown
            ) RETURNING *`,
        [trackId, current.userId, RIGHTS_TERMS_VERSION],
      )
      if (!rows[0]) return res.status(409).json({ error: 'TRACK_NOT_READY_FOR_AUTOMATIC_APPROVAL' })
      await recordModeration(
        pool,
        trackId,
        current.userId,
        'PROCESSING',
        'APPROVED',
        'Validación automática completada: firma, decodificación, previsualización segura y declaración de derechos',
        { termsVersion: RIGHTS_TERMS_VERSION, automatic: true },
      )
      if (typeof createLocalNotification === 'function') {
        await createLocalNotification(pool, {
          recipientId: current.userId,
          type: 'community_music_published',
          targetId: `community:${trackId}`,
          targetType: 'music',
          content: `“${cleanText(rows[0].title, 140)}” ya está disponible para la Comunidad.`,
          extraData: {
            track_id: `community:${trackId}`,
            moderation_state: 'APPROVED',
            automatic_approval: true,
          },
        })
      }
      res.json({ track: mapTrack(rows[0]) })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/music/:trackId/status', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      const row = current && trackId ? await trackWithCounts(pool, trackId, current.userId) : null
      if (!row || String(row.owner_id) !== current.userId) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      res.json({ id: `community:${row.id}`, moderationState: row.moderation_state, reason: row.moderation_reason, technicalValidation: row.technical_validation })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/music/dashboard', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      if (!current) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const { rows } = await pool.query(
        `SELECT t.*,
          (SELECT COUNT(*)::int FROM fallback_music_favorites f WHERE f.track_id=t.id) favorite_count,
          (SELECT COUNT(*)::int FROM fallback_music_listens l WHERE l.track_id=t.id AND l.counted) listen_count,
          (SELECT COUNT(*)::int FROM fallback_music_reports r WHERE r.track_id=t.id AND r.status='OPEN') report_count
         FROM fallback_music_tracks t WHERE owner_id=$1::uuid ORDER BY updated_at DESC`,
        [current.userId],
      )
      res.json({ tracks: rows.map((row) => mapTrack(row)), totals: rows.reduce((acc, row) => ({ tracks: acc.tracks + 1, listens: acc.listens + Number(row.listen_count || 0), favorites: acc.favorites + Number(row.favorite_count || 0), reports: acc.reports + Number(row.report_count || 0) }), { tracks: 0, listens: 0, favorites: 0, reports: 0 }) })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/music/search', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      if (!current) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const q = cleanText(req.query?.q, 100).toLowerCase()
      const genre = cleanText(req.query?.genre, 60).toLowerCase()
      const mood = cleanText(req.query?.mood, 60).toLowerCase()
      const durationMax = Math.round(numberIn(req.query?.durationMaxMs, 5_000, 15 * 60_000, 15 * 60_000))
      const limit = Math.round(numberIn(req.query?.limit, 1, 50, 20))
      const offset = cursorOffset(req.query?.cursor)
      const { rows } = await pool.query(
        `SELECT t.*,
          (SELECT COUNT(*)::int FROM fallback_music_favorites f WHERE f.track_id=t.id) favorite_count,
          (SELECT COUNT(*)::int FROM fallback_music_listens l WHERE l.track_id=t.id AND l.counted) listen_count,
          EXISTS(SELECT 1 FROM fallback_music_favorites f WHERE f.track_id=t.id AND f.user_id=$1::uuid) is_favorite
         FROM fallback_music_tracks t
         WHERE ${publicTrackWhere('t')}
           AND ($2='' OR lower(t.title) LIKE '%'||$2||'%' OR lower(t.artist_name) LIKE '%'||$2||'%' OR lower(t.description) LIKE '%'||$2||'%')
           AND ($3='' OR lower(t.genre)=$3 OR lower(t.subgenre)=$3)
           AND ($4='' OR lower(t.mood)=$4)
           AND t.duration_ms <= $5
         ORDER BY (SELECT COUNT(*) FROM fallback_music_listens l WHERE l.track_id=t.id AND l.counted AND l.completed_at > NOW()-INTERVAL '7 days') DESC,
                  t.published_at DESC NULLS LAST
         LIMIT $6 OFFSET $7`,
        [current.userId, q, genre, mood, durationMax, limit, offset],
      )
      res.json({ items: rows.map((row) => mapTrack(row)), nextCursor: nextCursor(offset, rows.length, limit) })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/music/recommendations', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      if (!current) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const limit = Math.round(numberIn(req.query?.limit, 1, 40, 20))
      const regionCode = /^[A-Z]{2}$/.test(String(req.headers['cf-ipcountry'] || '').toUpperCase())
        ? String(req.headers['cf-ipcountry']).toUpperCase()
        : ''
      const { rows } = await pool.query(
        `SELECT t.*,
          (SELECT COUNT(*)::int FROM fallback_music_favorites f WHERE f.track_id=t.id) favorite_count,
          (SELECT COUNT(*)::int FROM fallback_music_listens l WHERE l.track_id=t.id AND l.counted) listen_count,
          EXISTS(SELECT 1 FROM fallback_music_favorites f WHERE f.track_id=t.id AND f.user_id=$1::uuid) is_favorite,
          EXISTS(SELECT 1 FROM fallback_music_artist_follows af WHERE af.artist_id=t.owner_id AND af.follower_id=$1::uuid) artist_followed,
          EXISTS(
            SELECT 1 FROM fallback_music_favorites uf
            JOIN fallback_music_tracks ut ON ut.id=uf.track_id
            WHERE uf.user_id=$1::uuid AND t.genre IS NOT NULL AND lower(ut.genre)=lower(t.genre)
          ) genre_affinity,
          (SELECT COUNT(*)::int FROM fallback_moment_music mm WHERE mm.track_key=('community:'||t.id::text)) moment_use_count,
          (SELECT COUNT(*)::int FROM fallback_music_play_events pe
            WHERE pe.track_key=('community:'||t.id::text) AND $2<>'' AND pe.region_code=$2
              AND pe.created_at>NOW()-INTERVAL '7 days') regional_play_count
         FROM fallback_music_tracks t
         WHERE ${publicTrackWhere('t')}
         ORDER BY
           artist_followed DESC,
           genre_affinity DESC,
           regional_play_count DESC,
           moment_use_count DESC,
           (SELECT COUNT(*) FROM fallback_music_listens l WHERE l.track_id=t.id AND l.counted AND l.completed_at > NOW()-INTERVAL '7 days') DESC,
           (SELECT COUNT(*) FROM fallback_music_favorites f WHERE f.track_id=t.id) DESC,
           t.published_at DESC NULLS LAST
         LIMIT $3`,
        [current.userId, regionCode, limit],
      )
      const recommendations = rows.map((row) => {
        const score = (row.artist_followed ? 30 : 0) + (row.genre_affinity ? 20 : 0) +
          Number(row.regional_play_count || 0) * 3 + Number(row.moment_use_count || 0) * 4 +
          Number(row.listen_count || 0) + Number(row.favorite_count || 0) * 2
        return {
          row,
          score,
          reason: {
            artistFollowed: row.artist_followed === true,
            genreAffinity: row.genre_affinity === true,
            regionalTrend: Number(row.regional_play_count || 0),
            momentUses: Number(row.moment_use_count || 0),
            validListens: Number(row.listen_count || 0),
            favorites: Number(row.favorite_count || 0),
          },
        }
      })
      await Promise.all(recommendations.map(({ row, score, reason }) => pool.query(
        `INSERT INTO fallback_music_recommendations(user_id,track_key,score,reason,generated_at,expires_at)
         VALUES($1::uuid,$2,$3,$4::jsonb,NOW(),NOW()+INTERVAL '6 hours')
         ON CONFLICT(user_id,track_key) DO UPDATE SET score=EXCLUDED.score,reason=EXCLUDED.reason,
           generated_at=EXCLUDED.generated_at,expires_at=EXCLUDED.expires_at`,
        [current.userId, `community:${row.id}`, score, JSON.stringify(reason)],
      )))
      res.json({
        items: recommendations.map(({ row }) => mapTrack(row)),
        basis: 'artist_follows+genre_affinity+regional_7d_plays+moment_uses+valid_listens+favorites+recency',
      })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/music/tracks/:trackId', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      const row = current && trackId ? await trackWithCounts(pool, trackId, current.userId, true) : null
      if (!row) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      res.json({ track: mapTrack(row), attributionRequired: true })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/:trackId/favorite', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      const row = current && trackId ? await trackWithCounts(pool, trackId, current.userId, true) : null
      if (!row) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      await pool.query(`INSERT INTO fallback_music_favorites(track_id,user_id) VALUES($1::uuid,$2::uuid) ON CONFLICT DO NOTHING`, [trackId, current.userId])
      res.status(201).json({ favorite: true })
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/music/:trackId/favorite', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      if (!current || !trackId) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      await pool.query(`DELETE FROM fallback_music_favorites WHERE track_id=$1::uuid AND user_id=$2::uuid`, [trackId, current.userId])
      res.json({ favorite: false })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/artists/:artistId/follow', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const artistId = rawTrackId(req.params.artistId)
      if (!current || !artistId) return res.status(404).json({ error: 'ARTIST_NOT_FOUND' })
      await pool.query(`INSERT INTO fallback_music_artist_follows(artist_id,follower_id) VALUES($1::uuid,$2::uuid) ON CONFLICT DO NOTHING`, [artistId, current.userId])
      res.status(201).json({ following: true })
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/music/artists/:artistId/follow', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const artistId = rawTrackId(req.params.artistId)
      if (!current || !artistId) return res.status(404).json({ error: 'ARTIST_NOT_FOUND' })
      await pool.query(`DELETE FROM fallback_music_artist_follows WHERE artist_id=$1::uuid AND follower_id=$2::uuid`, [artistId, current.userId])
      res.json({ following: false })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/:trackId/listens', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      const row = current && trackId ? await trackWithCounts(pool, trackId, current.userId, true) : null
      if (!row) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const sessionId = crypto.randomUUID()
      await pool.query(`INSERT INTO fallback_music_listens(track_id,user_id,session_id,context) VALUES($1::uuid,$2::uuid,$3::uuid,$4)`, [trackId, current.userId, sessionId, cleanText(req.body?.context, 40) || 'catalog'])
      const regionCode = /^[A-Z]{2}$/.test(String(req.headers['cf-ipcountry'] || '').toUpperCase())
        ? String(req.headers['cf-ipcountry']).toUpperCase() : null
      await pool.query(
        `INSERT INTO fallback_music_play_events(track_key,user_id,event_type,context,region_code)
         VALUES($1,$2::uuid,'preview_started',$3,$4)`,
        [`community:${trackId}`, current.userId, cleanText(req.body?.context, 40) || 'catalog', regionCode],
      )
      res.status(201).json({ sessionId, minimumCountedMs: Math.min(30_000, Math.max(3_000, Math.round(Number(row.duration_ms) * 0.3))) })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/listens/:sessionId/complete', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      if (!current || !rawTrackId(req.params.sessionId)) return res.status(404).json({ error: 'LISTEN_SESSION_NOT_FOUND' })
      const listenedMs = Math.round(numberIn(req.body?.listenedMs, 0, 15 * 60_000, 0))
      const { rows } = await pool.query(
        `UPDATE fallback_music_listens l SET completed_at=NOW(), listened_ms=$3,
           counted=(NOW()-l.started_at >= INTERVAL '3 seconds' AND $3 >= LEAST(30000, GREATEST(3000, (SELECT duration_ms*0.3 FROM fallback_music_tracks WHERE id=l.track_id))))
         WHERE l.session_id=$1::uuid AND l.user_id=$2::uuid RETURNING counted,track_id::text,context`,
        [req.params.sessionId, current.userId, listenedMs],
      )
      if (!rows[0]) return res.status(404).json({ error: 'LISTEN_SESSION_NOT_FOUND' })
      if (rows[0].counted === true) {
        await pool.query(
          `INSERT INTO fallback_music_play_events(track_key,user_id,event_type,listened_ms,context)
           VALUES($1,$2::uuid,'preview_completed',$3,$4)`,
          [`community:${rows[0].track_id}`, current.userId, listenedMs, rows[0].context || 'catalog'],
        )
      }
      res.json({ counted: rows[0].counted === true })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/:trackId/report', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      const reason = cleanText(req.body?.reason, 80)
      if (!current || !trackId || !reason) return res.status(400).json({ error: 'TRACK_AND_REASON_REQUIRED' })
      await pool.query(
        `INSERT INTO fallback_music_reports(track_id,reporter_id,reason,details) VALUES($1::uuid,$2::uuid,$3,$4)
         ON CONFLICT(track_id,reporter_id,reason) DO UPDATE SET details=EXCLUDED.details,status='OPEN',created_at=NOW()`,
        [trackId, current.userId, reason, cleanMultiline(req.body?.details, 2000) || null],
      )
      const openCount = Number((await pool.query(`SELECT COUNT(*)::int count FROM fallback_music_reports WHERE track_id=$1::uuid AND status='OPEN'`, [trackId])).rows[0]?.count || 0)
      if (openCount >= 3) {
        await pool.query(`UPDATE fallback_music_tracks SET moderation_state='TAKEDOWN_PENDING', visibility='PRIVATE', moderation_reason='Revisión automática por reportes', updated_at=NOW() WHERE id=$1::uuid AND moderation_state='APPROVED'`, [trackId])
      }
      res.status(201).json({ reported: true, underReview: openCount >= 3 })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/:trackId/takedown', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      if (!current || !trackId) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const before = await trackWithCounts(pool, trackId, current.userId, false)
      if (!before || String(before.owner_id) !== String(current.userId)) {
        return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      }
      const { rows } = await pool.query(`UPDATE fallback_music_tracks SET moderation_state='DISABLED',visibility='PRIVATE',disabled_at=NOW(),moderation_reason=$3,updated_at=NOW() WHERE id=$1::uuid AND owner_id=$2::uuid RETURNING *`, [trackId, current.userId, cleanText(req.body?.reason, 300) || 'Retirado por el autor'])
      if (!rows[0]) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      await recordModeration(pool, trackId, current.userId, before.moderation_state, 'DISABLED', rows[0].moderation_reason)
      res.json({ track: mapTrack(rows[0]) })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/music/admin/moderation-queue', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      if (!current || !(await appAdminHasCapability(pool, current.identity, ADMIN_CAPABILITY.READ))) {
        return res.status(403).json({ error: 'ADMIN_CAPABILITY_REQUIRED', capability: ADMIN_CAPABILITY.READ })
      }
      const { rows } = await pool.query(`SELECT * FROM fallback_music_tracks WHERE moderation_state IN ('PENDING_REVIEW','TAKEDOWN_PENDING') ORDER BY updated_at ASC LIMIT 200`)
      res.json({ items: rows.map((row) => mapTrack(row)) })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/music/admin/:trackId/moderate', authRequired, async (req, res, next) => {
    try {
      const current = await musicIdentity(pool, req, getCurrentUser, userIdentity)
      const trackId = rawTrackId(req.params.trackId)
      if (!current || !(await appAdminHasCapability(pool, current.identity, ADMIN_CAPABILITY.MODERATION_MANAGE))) {
        return res.status(403).json({
          error: 'ADMIN_CAPABILITY_REQUIRED',
          capability: ADMIN_CAPABILITY.MODERATION_MANAGE,
        })
      }
      if (!trackId) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const decision = cleanEnum(req.body?.decision, new Set(['APPROVED','REJECTED','CHANGES_REQUIRED','DISABLED','REMOVED']), '')
      const reason = cleanText(req.body?.reason, 600)
      if (!decision || (decision !== 'APPROVED' && !reason)) return res.status(400).json({ error: 'DECISION_AND_REASON_REQUIRED' })
      if (decision === 'APPROVED' && !bool(req.body?.technicalValidationConfirmed)) {
        return res.status(400).json({ error: 'TECHNICAL_VALIDATION_CONFIRMATION_REQUIRED' })
      }
      const before = await trackWithCounts(pool, trackId, current.userId)
      if (!before) return res.status(404).json({ error: 'TRACK_NOT_FOUND' })
      const publicVisibility = decision === 'APPROVED' && PUBLIC_VISIBILITIES.has(before.requested_visibility)
        ? before.requested_visibility : 'PRIVATE'
      const { rows } = await pool.query(
        `UPDATE fallback_music_tracks SET moderation_state=$2,moderation_reason=$3,visibility=$4,
           technical_validation=CASE WHEN $2='APPROVED' THEN 'MANUALLY_VALIDATED' ELSE technical_validation END,
           published_at=CASE WHEN $2='APPROVED' THEN COALESCE(published_at,NOW()) ELSE published_at END,
           reviewed_at=NOW(),reviewed_by=$5::uuid,
           disabled_at=CASE WHEN $2='DISABLED' THEN NOW() ELSE disabled_at END,
           removed_at=CASE WHEN $2='REMOVED' THEN NOW() ELSE removed_at END,updated_at=NOW()
         WHERE id=$1::uuid RETURNING *`,
        [trackId, decision, reason || null, publicVisibility, current.userId],
      )
      if (decision === 'REMOVED' && before.source_sha256) {
        await pool.query(
          `INSERT INTO fallback_music_retired_hashes(source_sha256,reason,retired_by)
           VALUES($1,$2,$3::uuid) ON CONFLICT(source_sha256) DO UPDATE SET reason=EXCLUDED.reason,retired_at=NOW(),retired_by=EXCLUDED.retired_by`,
          [before.source_sha256, reason || 'Retirada por moderación', current.userId],
        )
      }
      await recordModeration(pool, trackId, current.userId, before.moderation_state, decision, reason, req.body?.evidence || {})
      res.json({ track: mapTrack(rows[0]) })
    } catch (error) { next(error) }
  })
}

module.exports = {
  RIGHTS_TERMS_VERSION,
  ensureMusicTables,
  inspectAndCreateMusicPreview,
  mapTrack,
  musicPreviewLocation,
  registerMusicRoutes,
  rawTrackId,
  scanMusicFile,
}
