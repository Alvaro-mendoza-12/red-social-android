const crypto = require('crypto')
const { publishRealtimeEvent } = require('./realtime-publish')
const { mergeProfileRows } = require('./profile-merge')
const { getExternalTrack } = require('./music-provider-adapters')
const { renderCompatibleMomentMusic, isPublicHttpsUrl } = require('./moment-music-renderer')
const { parseStorageObjectUrl, removeStorageObjectFiles } = require('./storage-routes')
const { sameCommunityMusicReference } = require('./moment-music-reference')
const { momentTimestamp } = require('./moment-timestamps')
const { approvedSyncedLyrics } = require('./moment-music-lyrics')

const IMAGE_MOMENT_DISPLAY_DURATION_MS = 6000
const MIN_IMAGE_MOMENT_DISPLAY_DURATION_MS = 6000
const MAX_VIDEO_MOMENT_DURATION_MS = 25000
const MAX_MUSIC_DURATION_MS = 60000

async function removeRenderedMomentMusic(pool, audioUrl) {
  const location = parseStorageObjectUrl(audioUrl)
  if (
    location?.bucket !== 'community-music' ||
    !/^[^/]+\/moments\/rendered\/[0-9a-f-]{36}\.m4a$/i.test(location.objectPath || '')
  ) return false
  const { rows } = await pool.query(
    `DELETE FROM fallback_storage_objects
      WHERE bucket_id=$1 AND name=$2
      RETURNING bucket_id,name,local_path`,
    [location.bucket, location.objectPath],
  )
  const removed = await removeStorageObjectFiles(rows)
  if (removed.failures.length > 0) {
    console.warn('[moment-music-cleanup]', { count: removed.failures.length })
  }
  return rows.length > 0
}

function normalizedApprovedMusicLicense(value) {
  const clean = String(value || '').trim().toLowerCase()
  if (clean === 'cc0' || clean.startsWith('cc0 ') || clean.includes('creative commons zero')) return 'CC0 1.0'
  if (clean === 'public domain' || clean === 'dominio público' || clean.startsWith('public domain ')) return 'Public Domain'
  return null
}

function isPlayableMomentMusicMime(value) {
  return new Set([
    'audio/mpeg',
    'audio/mp4',
    'audio/aac',
    'audio/ogg',
    'application/ogg',
    'audio/opus',
    'audio/flac',
    'audio/x-flac',
    'audio/wav',
    'audio/x-wav',
    'audio/webm',
  ]).has(String(value || '').trim().toLowerCase())
}

function sameHttpsAsset(left, right, expectedHost) {
  try {
    const a = new URL(String(left || '').trim())
    const b = new URL(String(right || '').trim())
    return a.protocol === 'https:' && b.protocol === 'https:' &&
      a.hostname === expectedHost && b.hostname === expectedHost &&
      decodeURIComponent(a.pathname) === decodeURIComponent(b.pathname)
  } catch (_) {
    return false
  }
}

function plainMetadataText(value) {
  return String(value || '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ')
    .trim()
}

async function verifyApprovedCommonsMusic(body) {
  const requestedAudioUrl = textOf(body, ['musicAudioUrl', 'music_audio_url', 'musicUrl', 'music_url'], '')
  if (!requestedAudioUrl) return null
  const musicId = textOf(body, ['musicId', 'music_id'], '')
  const match = /^commons:(\d+)$/.exec(musicId)
  if (!match || textOf(body, ['musicSource', 'music_source'], '') !== 'wikimedia_commons') {
    throw Object.assign(new Error('MUSIC_SOURCE_NOT_APPROVED'), { statusCode: 400 })
  }

  const params = new URLSearchParams({
    action: 'query',
    pageids: match[1],
    prop: 'info|imageinfo',
    inprop: 'url',
    iiprop: 'url|mime|size|extmetadata',
    iiextmetadatafilter: 'Artist|Credit|LicenseShortName|LicenseUrl|UsageTerms',
    format: 'json',
    formatversion: '2',
  })
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 8000)
  let response
  try {
    response = await fetch(`https://commons.wikimedia.org/w/api.php?${params}`, {
      headers: { 'User-Agent': 'Novvin-Server/3.3.0 (Moment music verification; support@novvin.app)' },
      signal: controller.signal,
    })
  } catch (_) {
    throw Object.assign(new Error('MUSIC_LICENSE_VERIFICATION_UNAVAILABLE'), { statusCode: 503 })
  } finally {
    clearTimeout(timer)
  }
  if (!response?.ok) throw Object.assign(new Error('MUSIC_LICENSE_VERIFICATION_UNAVAILABLE'), { statusCode: 503 })
  const payload = await response.json()
  const page = payload?.query?.pages?.[0]
  const info = page?.imageinfo?.[0]
  const metadata = info?.extmetadata || {}
  const license = normalizedApprovedMusicLicense(
    metadata?.LicenseShortName?.value || metadata?.UsageTerms?.value,
  )
  const mime = String(info?.mime || '').toLowerCase()
  const sourcePageUrl = String(info?.descriptionurl || page?.canonicalurl || '')
  if (
    !license ||
    !isPlayableMomentMusicMime(mime) ||
    !sameHttpsAsset(requestedAudioUrl, info?.url, 'upload.wikimedia.org') ||
    !sameHttpsAsset(
      textOf(body, ['musicSourceUrl', 'music_source_url'], ''),
      sourcePageUrl,
      'commons.wikimedia.org',
    )
  ) {
    throw Object.assign(new Error('MUSIC_LICENSE_NOT_APPROVED'), { statusCode: 400 })
  }
  const durationMs = Math.max(0, Math.round(Number(info?.duration || 0) * 1000))
  if (durationMs < 5000) throw Object.assign(new Error('MUSIC_TRACK_TOO_SHORT'), { statusCode: 400 })
  const startMs = Math.max(0, Math.round(numberOf(body, ['musicStartMs', 'music_start_ms'], 0)))
  const requestedEnd = Math.round(numberOf(body, ['musicEndMs', 'music_end_ms'], startMs + 6000))
  const endMs = Math.min(durationMs, Math.max(startMs + 5000, requestedEnd), startMs + MAX_MUSIC_DURATION_MS)
  if (endMs <= startMs) throw Object.assign(new Error('MUSIC_CLIP_INVALID'), { statusCode: 400 })

  return {
    id: musicId,
    title: String(page?.title || '').replace(/^File:/, '').replace(/\.[^.]+$/, '').slice(0, 140) || 'Audio de Commons',
    artist: plainMetadataText(metadata?.Artist?.value || metadata?.Credit?.value || 'Autor no indicado').slice(0, 120),
    ownerId: null,
    attribution: plainMetadataText(metadata?.Credit?.value || metadata?.Artist?.value || '').slice(0, 500),
    source: 'wikimedia_commons',
    sourceUrl: sourcePageUrl,
    license,
    licenseUrl: String(metadata?.LicenseUrl?.value || '').startsWith('https://') ? String(metadata.LicenseUrl.value) : null,
    audioUrl: String(info.url),
    startMs,
    endMs,
    durationMs: endMs - startMs,
    musicVolume: Math.max(0, Math.min(numberOf(body, ['musicVolume', 'music_volume'], 1), 1)),
    originalAudioVolume: Math.max(0, Math.min(numberOf(body, ['originalAudioVolume', 'original_audio_volume'], 1), 1)),
  }
}

function sameCanonicalHttpsUrl(left, right) {
  try {
    const a = new URL(String(left || '').trim())
    const b = new URL(String(right || '').trim())
    a.hash = ''
    b.hash = ''
    return a.protocol === 'https:' && b.protocol === 'https:' && a.toString() === b.toString()
  } catch (_) {
    return false
  }
}

async function verifyApprovedExternalMusic(body) {
  const musicId = textOf(body, ['musicId', 'music_id'], '')
  const source = textOf(body, ['musicSource', 'music_source'], '')
  if (!musicId.startsWith(`${source}:`) || !['openverse', 'jamendo'].includes(source)) {
    throw Object.assign(new Error('MUSIC_SOURCE_NOT_APPROVED'), { statusCode: 400 })
  }
  let track
  try {
    track = await getExternalTrack(musicId)
  } catch (_) {
    throw Object.assign(new Error('MUSIC_LICENSE_VERIFICATION_UNAVAILABLE'), { statusCode: 503 })
  }
  if (
    !track ||
    track.allowUseInMoments !== true ||
    !normalizedApprovedMusicLicense(track.license) ||
    !sameCanonicalHttpsUrl(textOf(body, ['musicAudioUrl', 'music_audio_url', 'musicUrl', 'music_url'], ''), track.audioUrl) ||
    !sameCanonicalHttpsUrl(textOf(body, ['musicSourceUrl', 'music_source_url'], ''), track.sourcePageUrl)
  ) {
    throw Object.assign(new Error('MUSIC_LICENSE_NOT_APPROVED'), { statusCode: 400 })
  }
  const durationMs = Math.max(5_000, Math.min(Number(track.durationMs || 0), 15 * 60_000))
  const startMs = Math.max(0, Math.round(numberOf(body, ['musicStartMs', 'music_start_ms'], 0)))
  const requestedEnd = Math.round(numberOf(body, ['musicEndMs', 'music_end_ms'], startMs + 6000))
  const endMs = Math.min(durationMs, Math.max(startMs + 5000, requestedEnd), startMs + MAX_MUSIC_DURATION_MS)
  if (endMs <= startMs || startMs >= durationMs) {
    throw Object.assign(new Error('MUSIC_CLIP_INVALID'), { statusCode: 400 })
  }
  return {
    id: track.id,
    title: track.title,
    artist: track.artist,
    ownerId: track.ownerId || null,
    attribution: textOf(track, ['attribution'], '').slice(0, 500),
    source: track.source,
    sourceUrl: track.sourcePageUrl,
    license: track.license,
    licenseUrl: track.licenseUrl,
    audioUrl: track.audioUrl,
    startMs,
    endMs,
    durationMs: endMs - startMs,
    musicVolume: Math.max(0, Math.min(numberOf(body, ['musicVolume', 'music_volume'], 1), 1)),
    originalAudioVolume: Math.max(0, Math.min(numberOf(body, ['originalAudioVolume', 'original_audio_volume'], 1), 1)),
  }
}

async function verifyOwnedLocalMomentMusic(
  pool,
  body,
  { user, currentUserOwnsStorageObject } = {},
) {
  const source = textOf(body, ['musicSource', 'music_source'], '')
  const musicId = textOf(body, ['musicId', 'music_id'], '')
  // Keep compatibility with the first Android local-music payloads, which
  // identified a device track but did not include music_source. Ownership is
  // still mandatory below, so this does not allow arbitrary remote audio.
  // A local track is identified by the server-created `device:` id. Older
  // Android builds sent a provider label (for example "Mi dispositivo") or
  // omitted music_source entirely, so the label must never make an owned
  // device upload look like an unapproved remote catalog track. Ownership of
  // the uploaded object below remains mandatory.
  if (!musicId.startsWith('device:')) return null
  const audioUrl = textOf(body, ['musicAudioUrl', 'music_audio_url', 'musicUrl', 'music_url'], '')
  if (
    !/^device:[a-z0-9-]{1,80}$/i.test(musicId) ||
    !audioUrl ||
    !user?.id ||
    typeof currentUserOwnsStorageObject !== 'function'
  ) {
    throw Object.assign(new Error('MUSIC_SOURCE_NOT_APPROVED'), { statusCode: 400 })
  }
  const ownsAudio = await currentUserOwnsStorageObject(pool, user.id, audioUrl, ['community-music'])
  if (!ownsAudio) {
    throw Object.assign(new Error('MOMENT_MUSIC_STORAGE_OWNERSHIP_REQUIRED'), { statusCode: 403 })
  }
  const startMs = Math.max(0, Math.round(numberOf(body, ['musicStartMs', 'music_start_ms'], 0)))
  const requestedDuration = Math.round(numberOf(body, ['musicDurationMs', 'music_duration_ms'], 6000))
  const durationMs = Math.max(1000, Math.min(requestedDuration, MAX_MUSIC_DURATION_MS))
  const title = textOf(body, ['musicTitle', 'music_title'], 'Audio del dispositivo').slice(0, 140)
  const artist = textOf(body, ['musicArtist', 'music_artist'], 'Audio del dispositivo').slice(0, 120)
  return {
    id: musicId,
    title,
    artist,
    ownerId: String(user.id),
    attribution: `${title} — ${artist}`.slice(0, 500),
    source: 'novvin_moment_local',
    lyrics: approvedSyncedLyrics(body.musicLyrics ?? body.music_lyrics),
    sourceUrl: null,
    license: 'Uso aportado por el usuario',
    licenseUrl: null,
    audioUrl,
    startMs,
    endMs: startMs + durationMs,
    durationMs,
    musicVolume: Math.max(0, Math.min(numberOf(body, ['musicVolume', 'music_volume'], 1), 1)),
    originalAudioVolume: Math.max(0, Math.min(numberOf(body, ['originalAudioVolume', 'original_audio_volume'], 1), 1)),
  }
}

async function verifyApprovedMomentMusic(pool, body, context = {}) {
  const source = textOf(body, ['musicSource', 'music_source'], '')
  const musicId = textOf(body, ['musicId', 'music_id'], '')
  // Older Android clients did not serialize music_source for a track picked
  // from the device.  Do not reject those requests as an unapproved remote
  // source: a device track is still accepted only after the same storage
  // ownership check used by the explicit local source.
  if (musicId.startsWith('device:')) {
    return verifyOwnedLocalMomentMusic(
      pool,
      source ? body : { ...body, musicSource: 'novvin_moment_local' },
      context,
    )
  }
  if (source === 'openverse' || source === 'jamendo') {
    return verifyApprovedExternalMusic(body)
  }
  if (source !== 'novvin_community' && !musicId.startsWith('community:')) {
    return verifyApprovedCommonsMusic(body)
  }
  const rawId = musicId.replace(/^community:/, '')
  if (!/^[0-9a-f-]{36}$/i.test(rawId)) {
    throw Object.assign(new Error('MUSIC_SOURCE_NOT_APPROVED'), { statusCode: 400 })
  }
  const { rows } = await pool.query(
    `SELECT id,owner_id,title,artist_name,attribution,audio_url,cover_url,duration_ms,rights_type,license_name,license_url,
            visibility,allow_reuse,moderation_state,lyrics
       FROM fallback_music_tracks
      WHERE id=$1::uuid
        AND moderation_state='APPROVED'
        AND visibility IN ('PUBLIC_REUSABLE','PUBLIC_REMIXABLE')
        AND allow_reuse=TRUE
        AND removed_at IS NULL AND disabled_at IS NULL
      LIMIT 1`,
    [rawId],
  )
  const track = rows[0]
  if (!track) throw Object.assign(new Error('MUSIC_LICENSE_NOT_APPROVED'), { statusCode: 400 })
  const requestedAudioUrl = textOf(body, ['musicAudioUrl', 'music_audio_url', 'musicUrl', 'music_url'], '')
  if (requestedAudioUrl && !sameCommunityMusicReference(requestedAudioUrl, track.audio_url)) {
    throw Object.assign(new Error('MUSIC_AUDIO_URL_MISMATCH'), { statusCode: 400 })
  }
  const durationMs = Math.max(5_000, Math.min(Number(track.duration_ms || 0), 15 * 60_000))
  const startMs = Math.max(0, Math.round(numberOf(body, ['musicStartMs', 'music_start_ms'], 0)))
  const requestedEnd = Math.round(numberOf(body, ['musicEndMs', 'music_end_ms'], startMs + 6000))
  const endMs = Math.min(durationMs, Math.max(startMs + 5000, requestedEnd), startMs + MAX_MUSIC_DURATION_MS)
  if (endMs <= startMs || startMs >= durationMs) {
    throw Object.assign(new Error('MUSIC_CLIP_INVALID'), { statusCode: 400 })
  }
  return {
    id: `community:${track.id}`,
    title: track.title,
    artist: track.artist_name,
    lyrics: approvedSyncedLyrics(track.lyrics),
    ownerId: String(track.owner_id || ''),
    attribution: String(track.attribution || `${track.title} — ${track.artist_name}`).slice(0, 500),
    source: 'novvin_community',
    sourceUrl: `/v1/data/music/tracks/community:${track.id}`,
    license: track.license_name || track.rights_type,
    licenseUrl: track.license_url || null,
    audioUrl: track.audio_url,
    artworkUrl: track.cover_url || null,
    startMs,
    endMs,
    durationMs: endMs - startMs,
    musicVolume: Math.max(0, Math.min(numberOf(body, ['musicVolume', 'music_volume'], 1), 1)),
    originalAudioVolume: Math.max(0, Math.min(numberOf(body, ['originalAudioVolume', 'original_audio_volume'], 1), 1)),
  }
}

function textOf(row, keys, fallback = '') {
  for (const key of keys) {
    const value = row?.[key]
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      return String(value).trim()
    }
  }
  return fallback
}

function numberOf(row, keys, fallback = 0) {
  for (const key of keys) {
    const value = row?.[key]
    if (value === undefined || value === null || value === '') continue
    const number = Number(value)
    if (Number.isFinite(number)) return number
  }
  return fallback
}

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function boolOf(row, keys, fallback = false) {
  for (const key of keys) {
    const value = row?.[key]
    if (value === undefined || value === null) continue
    if (typeof value === 'boolean') return value
    const text = String(value).toLowerCase()
    if (text === 'true' || text === '1') return true
    if (text === 'false' || text === '0') return false
  }
  return fallback
}

function idListOf(row, keys) {
  for (const key of keys) {
    const value = row?.[key]
    if (Array.isArray(value)) return value.map(normalizeId).filter(Boolean)
    if (typeof value === 'string' && value.trim()) {
      try {
        const parsed = JSON.parse(value)
        if (Array.isArray(parsed)) return parsed.map(normalizeId).filter(Boolean)
      } catch (_) {
        return value.replace(/[{}]/g, '').split(',').map(normalizeId).filter(Boolean)
      }
    }
  }
  return []
}

function momentAudienceAllows({ row, identityIds, followRows = [], chatRows = [] }) {
  const ownerId = normalizeId(textOf(row, ['user_id'], ''))
  const viewerIds = new Set(Array.from(identityIds || []).map(normalizeId).filter(Boolean))
  if (viewerIds.has(ownerId)) return true

  const legacyType = textOf(row, ['visibility_type'], 'public').toLowerCase()
  if (legacyType === 'private' || legacyType === 'silent') return false

  const audience = textOf(row, ['visibility'], 'all').toLowerCase()
  const selectedIds = new Set(idListOf(row, ['audience_ids', 'audienceIds']))
  const excludedIds = new Set(idListOf(row, ['excluded_ids', 'excludedIds']))
  const viewerIsSelected = Array.from(viewerIds).some((id) => selectedIds.has(id))
  const viewerIsExcluded = Array.from(viewerIds).some((id) => excludedIds.has(id))
  const viewerFollowsOwner = followRows.some((follow) =>
    viewerIds.has(normalizeId(textOf(follow, ['follower_id', 'user_id'], ''))) &&
      normalizeId(textOf(follow, ['following_id', 'target_user_id'], '')) === ownerId,
  )
  const ownerFollowsViewer = followRows.some((follow) =>
    normalizeId(textOf(follow, ['follower_id', 'user_id'], '')) === ownerId &&
      viewerIds.has(normalizeId(textOf(follow, ['following_id', 'target_user_id'], ''))),
  )
  const hasDirectChat = chatRows.some((chat) => {
    if (boolOf(chat, ['is_group', 'isGroup'], false)) return false
    const participants = new Set(idListOf(chat, ['participants', 'participant_ids', 'user_ids']))
    return participants.has(ownerId) && Array.from(viewerIds).some((id) => participants.has(id))
  })

  switch (audience) {
    case 'followers': return viewerFollowsOwner
    case 'following': return ownerFollowsViewer
    case 'chats': return hasDirectChat
    case 'only_selected': return viewerIsSelected
    case 'except_selected': return !viewerIsExcluded
    case 'only_me':
    case 'private': return false
    case 'all':
    case 'public': return true
    default: return false
  }
}

function toMillis(value) {
  const text = String(value || '').trim()
  if (!text) return 0
  const millis = Date.parse(text)
  return Number.isFinite(millis) ? millis : 0
}

function versionedAssetUrl(url, versionSource) {
  const cleanUrl = String(url || '').trim()
  if (!cleanUrl || cleanUrl.startsWith('data:')) return cleanUrl
  const versionMs = toMillis(versionSource)
  const version = versionMs > 0 ? String(versionMs) : String(versionSource || '').trim()
  if (!version) return cleanUrl
  const [base, hash = ''] = cleanUrl.split('#', 2)
  const stripped = base
    .replace(/([?&])v=[^&#]*&?/g, '$1')
    .replace(/[?&]$/, '')
  const separator = stripped.includes('?') ? '&' : '?'
  return `${stripped}${separator}v=${encodeURIComponent(version)}${hash ? `#${hash}` : ''}`
}

function normalizeMomentMediaType(rawType, mediaUrl = '') {
  const type = String(rawType || '').trim().toLowerCase()
  const url = String(mediaUrl || '').split('?')[0].toLowerCase()
  if (
    type === 'video' ||
    type.startsWith('video/') ||
    url.endsWith('.mp4') ||
    url.endsWith('.mov') ||
    url.endsWith('.m4v') ||
    url.endsWith('.webm')
  ) {
    return 'video'
  }
  return 'image'
}

function normalizedMomentDisplayDurationMs(row, mediaType) {
  // Still images are deterministic on every Android decoder/device. Their
  // lifetime is independent from the selected audio fragment metadata.
  if (mediaType !== 'video') return IMAGE_MOMENT_DISPLAY_DURATION_MS

  const direct = numberOf(row, [
    'displayDurationMs',
    'display_duration_ms',
    'durationMs',
    'duration_ms',
    'duration_milliseconds',
  ])
  if (direct > 0) {
    return Math.max(
      mediaType === 'video' ? 1000 : MIN_IMAGE_MOMENT_DISPLAY_DURATION_MS,
      Math.min(Math.round(direct), mediaType === 'video' ? MAX_VIDEO_MOMENT_DURATION_MS : 60000),
    )
  }

  const seconds = numberOf(row, ['durationSeconds', 'duration_seconds'])
  if (seconds > 0) {
    return Math.max(
      mediaType === 'video' ? 1000 : MIN_IMAGE_MOMENT_DISPLAY_DURATION_MS,
      Math.min(Math.round(seconds * 1000), mediaType === 'video' ? MAX_VIDEO_MOMENT_DURATION_MS : 60000),
    )
  }

  return mediaType === 'video' ? MAX_VIDEO_MOMENT_DURATION_MS : IMAGE_MOMENT_DISPLAY_DURATION_MS
}

async function ensureMomentsTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moments (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      media_url TEXT NOT NULL DEFAULT '',
      media_type TEXT NOT NULL DEFAULT 'image',
      thumbnail_url TEXT,
      display_duration_ms INT,
      caption TEXT,
      overlay_text TEXT,
      overlay_color TEXT NOT NULL DEFAULT '#FFFFFFFF',
      overlay_font TEXT NOT NULL DEFAULT 'sans',
      overlay_scale REAL NOT NULL DEFAULT 1,
      overlay_x REAL NOT NULL DEFAULT 0.5,
      overlay_y REAL NOT NULL DEFAULT 0.38,
      music_url TEXT,
      music_audio_url TEXT,
      music_id TEXT,
      music_title TEXT,
      music_artist TEXT,
      music_source TEXT,
      music_source_url TEXT,
      music_license TEXT,
      music_license_url TEXT,
      music_start_ms INT NOT NULL DEFAULT 0,
      music_duration_ms INT NOT NULL DEFAULT 6000,
      music_end_ms INT,
      music_volume REAL NOT NULL DEFAULT 1,
      original_audio_volume REAL NOT NULL DEFAULT 1,
      music_fade_in_ms INT NOT NULL DEFAULT 0,
      music_fade_out_ms INT NOT NULL DEFAULT 0,
      duck_original_audio BOOLEAN NOT NULL DEFAULT FALSE,
      music_embedded BOOLEAN NOT NULL DEFAULT FALSE,
      visibility TEXT NOT NULL DEFAULT 'all',
      visibility_type TEXT NOT NULL DEFAULT 'public',
      allow_replies BOOLEAN NOT NULL DEFAULT TRUE,
      duration_hours INT NOT NULL DEFAULT 24,
      duration_type TEXT NOT NULL DEFAULT '24h',
      excluded_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
      audience_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
      expires_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moment_views (
      moment_id UUID NOT NULL,
      user_id TEXT NOT NULL,
      viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (moment_id, user_id)
    )
  `)
  await pool.query(`
    ALTER TABLE fallback_moments
    ADD COLUMN IF NOT EXISTS display_duration_ms INT
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moment_notification_preferences (
      user_id TEXT PRIMARY KEY,
      enabled BOOLEAN NOT NULL DEFAULT FALSE,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS audience_ids JSONB NOT NULL DEFAULT '[]'::jsonb`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS overlay_text TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS overlay_color TEXT NOT NULL DEFAULT '#FFFFFFFF'`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS overlay_font TEXT NOT NULL DEFAULT 'sans'`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS overlay_scale REAL NOT NULL DEFAULT 1`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS overlay_x REAL NOT NULL DEFAULT 0.5`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS overlay_y REAL NOT NULL DEFAULT 0.38`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_url TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_audio_url TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_id TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_title TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_artist TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_presentation TEXT NOT NULL DEFAULT 'card'`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_artwork_url TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_overlay_x REAL NOT NULL DEFAULT 0.5`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_overlay_y REAL NOT NULL DEFAULT 0.5`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_overlay_scale REAL NOT NULL DEFAULT 1`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_overlay_rotation REAL NOT NULL DEFAULT 0`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_lyrics TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_source TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_source_url TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_license TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_license_url TEXT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_start_ms INT NOT NULL DEFAULT 0`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_duration_ms INT NOT NULL DEFAULT 6000`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_end_ms INT`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_volume REAL NOT NULL DEFAULT 1`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS original_audio_volume REAL NOT NULL DEFAULT 1`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_fade_in_ms INT NOT NULL DEFAULT 0`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_fade_out_ms INT NOT NULL DEFAULT 0`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS duck_original_audio BOOLEAN NOT NULL DEFAULT FALSE`)
  await pool.query(`ALTER TABLE fallback_moments ADD COLUMN IF NOT EXISTS music_embedded BOOLEAN NOT NULL DEFAULT FALSE`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moment_reactions (
      moment_id UUID NOT NULL REFERENCES fallback_moments(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      reaction TEXT NOT NULL DEFAULT 'like',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (moment_id, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moment_deletions (
      moment_id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL DEFAULT '',
      deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
}

async function importedRows(pool, tableName, limit = 2000) {
  const { rows } = await pool.query(
    `SELECT to_regclass($1) AS oid`,
    [`supabase_import.${tableName}`],
  )
  if (!rows[0]?.oid) return []
  const { rows: data } = await pool.query(
    `SELECT to_jsonb(t) AS row
     FROM supabase_import."${tableName.replace(/"/g, '""')}" t
     LIMIT $1`,
    [limit],
  )
  return data.map((entry) => entry.row || {})
}

function profileAliases(row) {
  return new Set([
    textOf(row, ['id'], ''),
    textOf(row, ['user_id', 'userId'], ''),
    textOf(row, ['profile_id', 'profileId'], ''),
    textOf(row, ['supabase_user_id', 'supabaseUserId'], ''),
    textOf(row, ['email'], ''),
    textOf(row, ['username', 'handle'], ''),
  ].map(normalizeId).filter(Boolean))
}

function momentMusicPresentationFields(body = {}, approvedMusic = null) {
  const requested = textOf(body, ['musicPresentation', 'music_presentation'], 'card')
  const lyrics = approvedSyncedLyrics(approvedMusic?.lyrics)
  const presentation = ['card', 'label', 'audio', ...(lyrics ? ['lyrics'] : [])].includes(requested) ? requested : 'card'
  // Community artwork is authoritative catalog data, like title and artist.
  const artwork = approvedMusic?.source === 'novvin_community'
    ? String(approvedMusic.artworkUrl || '')
    : textOf(body, ['musicArtworkUrl', 'music_artwork_url'], '')
  const coordinate = (keys, fallback, min, max) => Math.max(min, Math.min(numberOf(body, keys, fallback), max))
  const rotation = numberOf(body, ['musicOverlayRotation', 'music_overlay_rotation'], 0)
  return { presentation, lyrics,
    overlayX: approvedMusic ? coordinate(['musicOverlayX', 'music_overlay_x'], .5, 0, 1) : .5,
    overlayY: approvedMusic ? coordinate(['musicOverlayY', 'music_overlay_y'], .5, 0, 1) : .5,
    overlayScale: approvedMusic ? coordinate(['musicOverlayScale', 'music_overlay_scale'], 1, .5, 2) : 1,
    overlayRotation: approvedMusic ? ((rotation + 180) % 360 + 360) % 360 - 180 : 0,
    artworkUrl: approvedMusic && artwork.length <= 2048 && isPublicHttpsUrl(artwork) ? artwork : null,
  }
}

function normalizedMomentMusicForMedia(approvedMusic, mediaType, body = {}) {
  if (!approvedMusic) return null
  const selected = { ...approvedMusic, ...momentMusicPresentationFields(body, approvedMusic) }
  if (mediaType === 'video') return selected
  return {
    ...selected,
    durationMs: IMAGE_MOMENT_DISPLAY_DURATION_MS,
    endMs: approvedMusic.startMs + IMAGE_MOMENT_DISPLAY_DURATION_MS,
  }
}

function mergeMomentOwnerProfile(importedProfile = {}, localUser = null) {
  if (!localUser) return { ...importedProfile }
  const localProfile = {
    ...mergeProfileRows(localUser, localUser.profile_override),
    updated_at: localUser.profile_override_updated_at || localUser.updated_at,
  }
  return mergeProfileRows(importedProfile, localProfile)
}

async function profileFor(pool, userId, profilesById) {
  const cleanUserId = normalizeId(userId)
  if (profilesById?.has(cleanUserId)) return profilesById.get(cleanUserId)
  const [profiles, localUsers] = await Promise.all([
    importedRows(pool, 'profiles', 5000),
    pool.query(
      `SELECT u.id, u.email, u.display_name, u.avatar_url, u.supabase_user_id,
              u.created_at, u.updated_at, u.last_login_at,
              COALESCE(o.profile, '{}'::jsonb) AS profile_override,
              o.updated_at AS profile_override_updated_at
       FROM fallback_users u
       LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
       LIMIT 10000`,
    ).then((result) => result.rows),
  ])
  const importedProfile = profiles.find((row) => profileAliases(row).has(cleanUserId)) || {}
  const localUser = localUsers.find((row) => profileAliases(row).has(cleanUserId))
  // Local profile edits are authoritative. Imported rows are only a fallback
  // for fields that the user has not set in Novvin yet.
  const profile = mergeMomentOwnerProfile(importedProfile, localUser)
  const normalized = {
    name: textOf(profile, ['display_name', 'displayName', 'full_name', 'username', 'email'], 'Usuario'),
    avatarUrl: versionedAssetUrl(
      textOf(profile, ['avatar_url', 'avatarUrl', 'photo_url', 'profile_image', 'picture', 'image'], ''),
      textOf(profile, ['updated_at', 'updatedAt', 'created_at', 'createdAt'], ''),
    ),
  }
  profilesById?.set(cleanUserId, normalized)
  return normalized
}

async function visibleMomentForInteraction(pool, identity, requestedId, now = Date.now()) {
  const id = normalizeId(requestedId)
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(id)) return null
  const [local, deleted] = await Promise.all([
    pool.query(`SELECT id::text, user_id, visibility, visibility_type, audience_ids, excluded_ids, expires_at
                FROM fallback_moments WHERE lower(id::text) = $1 LIMIT 1`, [id]),
    pool.query(`SELECT 1 FROM fallback_moment_deletions WHERE lower(moment_id) = $1 LIMIT 1`, [id]),
  ])
  if (deleted.rows.length) return null
  const row = local.rows[0] || (await importedRows(pool, 'moments', 5000)).find(item => normalizeId(item.id) === id)
  if (!row || !normalizeId(textOf(row, ['user_id'], ''))) return null
  const expiresAt = momentTimestamp(row.expires_at)
  if ((textOf(row, ['expires_at'], '') && !expiresAt) || (expiresAt && Date.parse(expiresAt) <= now)) return null
  const ownerId = normalizeId(row.user_id)
  const audience = textOf(row, ['visibility'], 'all').toLowerCase()
  const legacyType = textOf(row, ['visibility_type'], 'public').toLowerCase()
  if (identity.ids.has(ownerId) || legacyType === 'private' || legacyType === 'silent' ||
      !['followers', 'following', 'chats'].includes(audience)) {
    return momentAudienceAllows({ row, identityIds: identity.ids }) ? row : null
  }
  let followRows = []
  let chatRows = []
  if (audience === 'chats') {
    const chats = await pool.query(
      `SELECT participants, is_group FROM fallback_chats
       WHERE $1 = ANY(participants) AND participants && $2::text[] AND is_group = FALSE LIMIT 1`,
      [ownerId, Array.from(identity.ids)],
    )
    chatRows = chats.rows
  } else {
    const [localFollows, importedFollows] = await Promise.all([
      pool.query(`SELECT follower_id, following_id FROM fallback_follows
                  WHERE (lower(follower_id) = $1 AND lower(following_id) = ANY($2::text[]))
                     OR (lower(following_id) = $1 AND lower(follower_id) = ANY($2::text[]))`,
        [ownerId, Array.from(identity.ids)]),
      importedRows(pool, 'follows', 20_000),
    ])
    followRows = [...localFollows.rows, ...importedFollows]
  }
  return momentAudienceAllows({ row, identityIds: identity.ids, followRows, chatRows }) ? row : null
}

function requestedMomentOwners(value) {
  if (value == null || value === '') return []
  if (typeof value !== 'string' || value.length > 3700) return null
  const owners = value.split(',').map(value => value.trim().toLowerCase())
  if (owners.length > 100 || owners.some(value => !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value))) return null
  return [...new Set(owners)]
}

async function importedMomentsForOwners(pool, ownerIds) {
  if (!ownerIds.length) return []
  const { rows } = await pool.query('SELECT to_regclass($1) AS oid', ['supabase_import.moments'])
  if (!rows[0]?.oid) return []
  const result = await pool.query(`SELECT to_jsonb(t) AS row FROM supabase_import.moments t
    WHERE lower(to_jsonb(t)->>'user_id') = ANY($1::text[])
    ORDER BY to_jsonb(t)->>'created_at' DESC LIMIT 2000`, [ownerIds])
  return result.rows.map(entry => entry.row || {})
}

async function buildMomentsFeed(pool, identity, ownerIds = []) {
  const now = Date.now()
  const { rows: localRows } = await pool.query(
    `SELECT id::text, user_id, media_url, media_type, thumbnail_url, caption,
            overlay_text, overlay_color, overlay_font, overlay_scale, overlay_x, overlay_y,
            music_url, music_audio_url, music_id, music_title, music_artist, music_presentation, music_artwork_url, music_overlay_x, music_overlay_y, music_overlay_scale, music_overlay_rotation, music_lyrics, music_source,
            music_source_url, music_license, music_license_url, music_start_ms, music_duration_ms,
            music_end_ms, music_volume, original_audio_volume, music_fade_in_ms, music_fade_out_ms,
            duck_original_audio, music_embedded, visibility, visibility_type,
            allow_replies, display_duration_ms, duration_hours, duration_type, excluded_ids, audience_ids, expires_at, created_at, updated_at
     FROM fallback_moments
     ORDER BY created_at DESC
     LIMIT 300`,
  )
  const imported = await importedRows(pool, 'moments', 500)
  // Supplement the discovery window before applying the same audience and expiry rules.
  // Active publications are limited to ten per owner; requests contain at most 100 owners.
  const [authorLocal, authorImported] = ownerIds.length ? await Promise.all([
    pool.query(`SELECT * FROM fallback_moments WHERE lower(user_id) = ANY($1::text[])
      ORDER BY created_at DESC LIMIT 2000`, [ownerIds]).then(result => result.rows),
    importedMomentsForOwners(pool, ownerIds),
  ]) : [[], []]
  const { rows: deletedRows } = await pool.query(
    `SELECT lower(moment_id) AS moment_id FROM fallback_moment_deletions`,
  )
  const deletedIds = new Set(deletedRows.map((row) => row.moment_id).filter(Boolean))
  const merged = [...imported, ...authorImported, ...localRows, ...authorLocal].map(row => ({ ...row, id: textOf(row, ['id'], '') }))
  const byId = new Map()
  merged.forEach((row) => {
    const id = textOf(row, ['id'], crypto.randomUUID())
    if (!id) return
    byId.set(id, { ...row, id })
  })
  const moments = Array.from(byId.values())
    .filter((row) => !deletedIds.has(normalizeId(textOf(row, ['id'], ''))))

  const momentIds = moments.map((row) => textOf(row, ['id'], '')).filter(Boolean)
  const viewsByMoment = new Map()
  const viewedByMe = new Set()
  const viewersByMoment = new Map()
  const reactionsByMoment = new Map()
  const reactedByMe = new Set()
  const [localFollows, importedFollows, localChats] = await Promise.all([
    pool.query(`SELECT follower_id, following_id FROM fallback_follows LIMIT 20000`).then((result) => result.rows),
    importedRows(pool, 'follows', 20_000),
    pool.query(`SELECT participants, is_group FROM fallback_chats LIMIT 10000`).then((result) => result.rows),
  ])
  const followRows = [...localFollows, ...importedFollows]

  if (momentIds.length > 0) {
    const { rows: viewRows } = await pool.query(
      `SELECT moment_id::text, user_id, viewed_at
       FROM fallback_moment_views
       WHERE moment_id::text = ANY($1::text[])`,
      [momentIds],
    )
    const importedViews = await importedRows(pool, 'moment_views', 5000)
    const allViews = [
      ...viewRows.map((row) => ({
        moment_id: textOf(row, ['moment_id'], ''),
        user_id: textOf(row, ['user_id'], ''),
        viewed_at: textOf(row, ['viewed_at'], ''),
      })),
      ...importedViews,
    ]
    allViews.forEach((view) => {
      const momentId = textOf(view, ['moment_id'], '')
      const viewerId = textOf(view, ['user_id'], '')
      if (!momentId) return
      viewsByMoment.set(momentId, (viewsByMoment.get(momentId) || 0) + 1)
      if (identity.ids.has(viewerId)) viewedByMe.add(momentId)
      if (viewerId) {
        const list = viewersByMoment.get(momentId) || []
        list.push({ userId: viewerId, viewedAt: textOf(view, ['viewed_at'], '') })
        viewersByMoment.set(momentId, list)
      }
    })
    const { rows: reactionRows } = await pool.query(
      `SELECT moment_id::text, user_id FROM fallback_moment_reactions WHERE moment_id::text = ANY($1::text[])`,
      [momentIds],
    )
    reactionRows.forEach((reaction) => {
      const reactionMomentId = textOf(reaction, ['moment_id'], '')
      const reactionUserId = normalizeId(textOf(reaction, ['user_id'], ''))
      reactionsByMoment.set(reactionMomentId, (reactionsByMoment.get(reactionMomentId) || 0) + 1)
      if (identity.ids.has(reactionUserId)) reactedByMe.add(reactionMomentId)
    })
  }

  const profilesById = new Map()
  const items = []
  for (const row of moments) {
    const id = textOf(row, ['id'], '')
    const ownerId = normalizeId(textOf(row, ['user_id'], ''))
    const mediaType = normalizeMomentMediaType(textOf(row, ['media_type'], 'image'), textOf(row, ['media_url'], ''))
    if (!momentAudienceAllows({ row, identityIds: identity.ids, followRows, chatRows: localChats })) continue
    const expiresAt = momentTimestamp(row.expires_at)
    // A malformed expiry must not become an empty, indefinitely visible expiry.
    if (textOf(row, ['expires_at'], '') && !expiresAt) continue
    if (expiresAt) {
      const expiresMs = Date.parse(expiresAt)
      if (!Number.isNaN(expiresMs) && expiresMs <= now) continue
    }
    const ownerProfile = await profileFor(pool, ownerId, profilesById)
    const viewerPreview = Array.from(
      new Set(
        (viewersByMoment.get(id) || [])
          .filter((entry) => entry.userId !== ownerId)
          .slice(0, 5)
          .map((entry) => profilesById.get(entry.userId)?.name || entry.userId),
      ),
    )
    items.push({
      id,
      user_id: ownerId,
      owner_name: ownerProfile.name,
      owner_avatar_url: ownerProfile.avatarUrl,
      media_url: textOf(row, ['media_url'], ''),
      media_type: mediaType,
      display_duration_ms: normalizedMomentDisplayDurationMs(row, mediaType),
      caption: textOf(row, ['caption'], ''),
      overlay_text: textOf(row, ['overlay_text', 'overlayText'], ''),
      overlay_color: textOf(row, ['overlay_color', 'overlayColor'], '#FFFFFFFF'),
      overlay_font: textOf(row, ['overlay_font', 'overlayFont'], 'sans'),
      overlay_scale: Math.max(0.7, Math.min(numberOf(row, ['overlay_scale', 'overlayScale'], 1), 2.1)),
      overlay_x: Math.max(0, Math.min(numberOf(row, ['overlay_x', 'overlayX'], 0.5), 1)),
      overlay_y: Math.max(0, Math.min(numberOf(row, ['overlay_y', 'overlayY'], 0.38), 1)),
      music_url: textOf(row, ['music_url', 'musicUrl'], ''),
      music_audio_url: textOf(row, ['music_audio_url', 'musicAudioUrl', 'music_url', 'musicUrl'], ''),
      music_id: textOf(row, ['music_id', 'musicId'], ''),
      music_title: textOf(row, ['music_title', 'musicTitle'], ''),
      music_artist: textOf(row, ['music_artist', 'musicArtist'], ''),
      music_presentation: ['card', 'label', 'audio', ...(approvedSyncedLyrics(row.music_lyrics) ? ['lyrics'] : [])].includes(row.music_presentation) ? row.music_presentation : 'card',
      music_lyrics: approvedSyncedLyrics(row.music_lyrics),
      music_overlay_x: Math.max(0, Math.min(numberOf(row, ['music_overlay_x', 'musicOverlayX'], .5), 1)),
      music_overlay_y: Math.max(0, Math.min(numberOf(row, ['music_overlay_y', 'musicOverlayY'], .5), 1)),
      music_overlay_scale: Math.max(.5, Math.min(numberOf(row, ['music_overlay_scale', 'musicOverlayScale'], 1), 2)),
      music_overlay_rotation: ((numberOf(row, ['music_overlay_rotation', 'musicOverlayRotation'], 0) + 180) % 360 + 360) % 360 - 180,
      music_artwork_url: isPublicHttpsUrl(row.music_artwork_url) ? row.music_artwork_url : null,
      music_source: textOf(row, ['music_source', 'musicSource'], ''),
      music_source_url: textOf(row, ['music_source_url', 'musicSourceUrl'], ''),
      music_license: textOf(row, ['music_license', 'musicLicense'], ''),
      music_license_url: textOf(row, ['music_license_url', 'musicLicenseUrl'], ''),
      music_start_ms: Math.max(0, numberOf(row, ['music_start_ms', 'musicStartMs'], 0)),
      music_duration_ms: Math.max(1000, Math.min(numberOf(row, ['music_duration_ms', 'musicDurationMs'], 6000), MAX_MUSIC_DURATION_MS)),
      music_end_ms: Math.max(0, numberOf(row, ['music_end_ms', 'musicEndMs'], 0)) || null,
      music_volume: Math.max(0, Math.min(numberOf(row, ['music_volume', 'musicVolume'], 1), 1)),
      original_audio_volume: Math.max(0, Math.min(numberOf(row, ['original_audio_volume', 'originalAudioVolume'], 1), 1)),
      music_fade_in_ms: Math.max(0, Math.min(numberOf(row, ['music_fade_in_ms', 'musicFadeInMs'], 0), 5000)),
      music_fade_out_ms: Math.max(0, Math.min(numberOf(row, ['music_fade_out_ms', 'musicFadeOutMs'], 0), 5000)),
      duck_original_audio: boolOf(row, ['duck_original_audio', 'duckOriginalAudio'], false),
      music_embedded: boolOf(row, ['music_embedded', 'musicEmbedded'], false),
      visibility: textOf(row, ['visibility'], 'all'),
      visibility_type: textOf(row, ['visibility_type'], 'public'),
      excluded_ids: idListOf(row, ['excluded_ids', 'excludedIds']),
      audience_ids: idListOf(row, ['audience_ids', 'audienceIds']),
      allow_replies: boolOf(row, ['allow_replies'], true),
      created_at: momentTimestamp(row.created_at),
      expires_at: expiresAt,
      views_count: viewsByMoment.get(id) || 0,
      viewed_by_me: viewedByMe.has(id),
      reaction_count: reactionsByMoment.get(id) || 0,
      reacted_by_me: reactedByMe.has(id),
      viewer_preview: viewerPreview,
    })
  }

  items.sort((a, b) => Date.parse(b.created_at || 0) - Date.parse(a.created_at || 0))
  return items
}

async function momentFollowerRecipientIds(pool, ownerId) {
  const cleanOwnerId = normalizeId(ownerId)
  if (!cleanOwnerId) return []
  const { rows: localRows } = await pool.query(
    `SELECT follower_id
     FROM fallback_follows
     WHERE lower(following_id) = $1
     LIMIT 2000`,
    [cleanOwnerId],
  )
  const importedRowsForFollows = await importedRows(pool, 'follows', 20_000)
  const recipients = new Set(
    localRows
      .map((row) => normalizeId(textOf(row, ['follower_id'], '')))
      .filter(Boolean),
  )
  importedRowsForFollows.forEach((row) => {
    const following = normalizeId(textOf(row, ['following_id', 'target_user_id'], ''))
    const follower = normalizeId(textOf(row, ['follower_id', 'user_id'], ''))
    if (following === cleanOwnerId && follower) recipients.add(follower)
  })
  recipients.delete(cleanOwnerId)
  return Array.from(recipients)
}

async function momentNotificationRecipientIds(pool, ownerId, momentRow) {
  const owner = normalizeId(ownerId)
  const audience = textOf(momentRow, ['visibility'], 'all').toLowerCase()
  if (audience === 'only_me' || audience === 'private') return []

  const excludedIds = new Set(idListOf(momentRow, ['excluded_ids', 'excludedIds']))
  let recipients = []
  if (audience === 'only_selected') {
    recipients = idListOf(momentRow, ['audience_ids', 'audienceIds'])
  } else if (audience === 'following') {
    const { rows } = await pool.query(
      `SELECT following_id FROM fallback_follows WHERE lower(follower_id) = $1 LIMIT 2000`,
      [owner],
    )
    recipients = rows.map((row) => normalizeId(textOf(row, ['following_id'], '')))
  } else if (audience === 'chats') {
    const { rows } = await pool.query(
      `SELECT participants FROM fallback_chats WHERE is_group = FALSE LIMIT 2000`,
    )
    recipients = rows
      .filter((row) => idListOf(row, ['participants']).includes(owner))
      .flatMap((row) => idListOf(row, ['participants']))
  } else {
    recipients = await momentFollowerRecipientIds(pool, owner)
  }
  const eligible = Array.from(new Set(recipients.map(normalizeId)))
    .filter((id) => id && id !== owner && !excludedIds.has(id))
  if (eligible.length === 0) return []
  const { rows } = await pool.query(
    `SELECT lower(user_id) AS user_id FROM fallback_moment_notification_preferences
     WHERE lower(user_id) = ANY($1::text[]) AND enabled = TRUE`,
    [eligible],
  )
  const optedIn = new Set(rows.map((row) => normalizeId(row.user_id)))
  return eligible.filter((id) => optedIn.has(id))
}

function momentNotificationExtraData(momentRow, fallbackExpiresAt) {
  const rawExpiry = momentRow?.expires_at
  return {
    moment_id: momentRow?.id,
    expires_at: rawExpiry instanceof Date
      ? rawExpiry.toISOString()
      : String(rawExpiry || fallbackExpiresAt),
  }
}

function registerMomentsRoutes(
  app,
  { pool, authRequired, getCurrentUser, userIdentity, createLocalNotification, currentUserOwnsStorageObject },
) {
  app.get('/v1/data/moments/notification-preferences', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const { rows } = await pool.query(
        `SELECT enabled FROM fallback_moment_notification_preferences
         WHERE lower(user_id) = ANY($1::text[]) LIMIT 1`,
        [identity.values],
      )
      res.json({ enabled: rows[0]?.enabled === true })
    } catch (error) { next(error) }
  })

  app.put('/v1/data/moments/notification-preferences', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      if (typeof req.body?.enabled !== 'boolean') {
        return res.status(400).json({ error: 'MOMENT_NOTIFICATION_PREFERENCE_INVALID' })
      }
      const identity = userIdentity(user)
      await pool.query(
        `INSERT INTO fallback_moment_notification_preferences(user_id, enabled)
         VALUES ($1, $2)
         ON CONFLICT (user_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = NOW()`,
        [identity.displayUserId, req.body.enabled],
      )
      res.json({ enabled: req.body.enabled })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/moments/feed', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const owners = requestedMomentOwners(req.query?.owner_ids)
      if (owners === null) return res.status(400).json({ error: 'MOMENT_OWNERS_INVALID' })
      const items = await buildMomentsFeed(pool, identity, owners)
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/moments', authRequired, async (req, res, next) => {
    let renderedAudioUrl = null
    let momentPersisted = false
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const mediaUrl = textOf(req.body, ['mediaUrl', 'media_url'], '')
      if (!mediaUrl) return res.status(400).json({ error: 'MEDIA_URL_REQUIRED' })
      if (!(await currentUserOwnsStorageObject(pool, user.id, mediaUrl, ['moment-media']))) {
        return res.status(403).json({ error: 'MOMENT_MEDIA_STORAGE_OWNERSHIP_REQUIRED' })
      }
      const thumbnailUrl = textOf(req.body, ['thumbnailUrl', 'thumbnail_url'], '') || null
      if (
        thumbnailUrl &&
        !(await currentUserOwnsStorageObject(pool, user.id, thumbnailUrl, ['moment-media']))
      ) {
        return res.status(403).json({ error: 'MOMENT_THUMBNAIL_STORAGE_OWNERSHIP_REQUIRED' })
      }
      const mediaType = normalizeMomentMediaType(textOf(req.body, ['mediaType', 'media_type'], 'image'), mediaUrl)
      const approvedMusic = await verifyApprovedMomentMusic(pool, req.body, {
        user,
        currentUserOwnsStorageObject,
      })
      const displayDurationMs = normalizedMomentDisplayDurationMs(req.body, mediaType)
      const selectedMusic = normalizedMomentMusicForMedia(approvedMusic, mediaType, req.body)
      // Android flattens a device soundtrack into the MP4 before uploading it.
      // Rendering the same local source a second time on the API caused an
      // avoidable failure when the temporary storage URL was not reachable
      // from the container. Keep the owned track metadata for attribution and
      // let the already mixed MP4 provide playback in this case.
      const clientEmbeddedMusic = mediaType === 'video' && Boolean(selectedMusic) &&
        boolOf(req.body, ['musicEmbedded', 'music_embedded'], false)
      const localMusicAlreadyEmbedded = clientEmbeddedMusic &&
        String(selectedMusic?.id || '').startsWith('device:')
      // Server-render external and image soundtracks into a bounded AAC-LC/M4A
      // clip. Device video soundtracks are already flattened by Android above,
      // so retaining their owned metadata avoids a redundant transcode.
      const effectiveMusic = localMusicAlreadyEmbedded
        ? selectedMusic
        : await renderCompatibleMomentMusic({
          pool,
          req,
          user,
          approvedMusic: selectedMusic,
        })
      renderedAudioUrl = effectiveMusic?.audioUrl || null
      const overlayText = textOf(req.body, ['overlayText', 'overlay_text'], '').slice(0, 120) || null
      const overlayColor = /^#[0-9a-f]{8}$/i.test(textOf(req.body, ['overlayColor', 'overlay_color'], ''))
        ? textOf(req.body, ['overlayColor', 'overlay_color'], '#FFFFFFFF').toUpperCase()
        : '#FFFFFFFF'
      const overlayFont = ['sans', 'serif', 'mono'].includes(textOf(req.body, ['overlayFont', 'overlay_font'], 'sans'))
        ? textOf(req.body, ['overlayFont', 'overlay_font'], 'sans')
        : 'sans'
      const overlayScale = Math.max(0.7, Math.min(numberOf(req.body, ['overlayScale', 'overlay_scale'], 1), 2.1))
      const overlayX = Math.max(0, Math.min(numberOf(req.body, ['overlayX', 'overlay_x'], 0.5), 1))
      const overlayY = Math.max(0, Math.min(numberOf(req.body, ['overlayY', 'overlay_y'], 0.38), 1))
      const durationHours = Math.max(1, Math.min(Number(req.body?.durationHours || req.body?.duration_hours || 24), 168))
      const expiresAt = new Date(Date.now() + durationHours * 60 * 60 * 1000).toISOString()
      const visibility = textOf(req.body, ['visibility'], 'all')
      const requestedVisibilityType = textOf(req.body, ['visibilityType', 'visibility_type'], '')
      const visibilityType = ['private', 'silent', 'public'].includes(requestedVisibilityType)
        ? requestedVisibilityType
        : visibility === 'private'
          ? 'private'
          : visibility === 'silent'
            ? 'silent'
            : 'public'
      const fadeLimitMs = Math.min(5000, Math.floor(Number(effectiveMusic?.durationMs || 0) / 2))
      const musicFadeInMs = effectiveMusic
        ? Math.max(0, Math.min(Math.round(numberOf(req.body, ['musicFadeInMs', 'music_fade_in_ms'], 0)), fadeLimitMs))
        : 0
      const musicFadeOutMs = effectiveMusic
        ? Math.max(0, Math.min(Math.round(numberOf(req.body, ['musicFadeOutMs', 'music_fade_out_ms'], 0)), fadeLimitMs))
        : 0
      const duckOriginalAudio = Boolean(effectiveMusic) && boolOf(req.body, ['duckOriginalAudio', 'duck_original_audio'], false)
      const musicEmbedded = mediaType === 'video' && Boolean(effectiveMusic) &&
        boolOf(req.body, ['musicEmbedded', 'music_embedded'], false)

      const { rows } = await pool.query(
        `INSERT INTO fallback_moments(
            user_id, media_url, media_type, thumbnail_url, caption, visibility, visibility_type,
            allow_replies, display_duration_ms, duration_hours, duration_type, excluded_ids, audience_ids, expires_at,
            overlay_text, overlay_color, overlay_font, overlay_scale, overlay_x, overlay_y,
            music_url, music_audio_url, music_id, music_title, music_artist, music_source,
            music_source_url, music_license, music_license_url, music_start_ms, music_duration_ms,
            music_end_ms, music_volume, original_audio_volume, music_fade_in_ms, music_fade_out_ms,
            duck_original_audio, music_embedded, music_presentation, music_artwork_url,
            music_overlay_x, music_overlay_y, music_overlay_scale, music_overlay_rotation, music_lyrics
         )
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::jsonb, $13::jsonb, $14,
                 $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25, $26, $27, $28,
                 $29, $30, $31, $32, $33, $34, $35, $36, $37, $38, $39, $40, $41, $42, $43, $44, $45)
         RETURNING id::text, user_id, media_url, media_type, thumbnail_url, caption, visibility, visibility_type,
                   allow_replies, display_duration_ms, duration_hours, duration_type, excluded_ids, audience_ids, expires_at,
                   overlay_text, overlay_color, overlay_font, overlay_scale, overlay_x, overlay_y,
                   music_url, music_audio_url, music_id, music_title, music_artist, music_presentation, music_artwork_url, music_overlay_x, music_overlay_y, music_overlay_scale, music_overlay_rotation, music_lyrics, music_source,
                   music_source_url, music_license, music_license_url, music_start_ms, music_duration_ms,
                   music_end_ms, music_volume, original_audio_volume, music_fade_in_ms, music_fade_out_ms,
                   duck_original_audio, music_embedded, created_at, updated_at`,
        [
          identity.displayUserId,
          mediaUrl,
          mediaType,
          thumbnailUrl,
          textOf(req.body, ['caption'], '') || null,
          visibility,
          visibilityType,
          req.body?.allowReplies !== false && req.body?.allow_replies !== false,
          displayDurationMs,
          durationHours,
          textOf(req.body, ['durationType', 'duration_type'], '24h'),
          JSON.stringify(idListOf(req.body, ['excludedIds', 'excluded_ids'])),
          JSON.stringify(idListOf(req.body, ['audienceIds', 'audience_ids'])),
          expiresAt,
          overlayText,
          overlayColor,
          overlayFont,
          overlayScale,
          overlayX,
          overlayY,
          effectiveMusic?.audioUrl || null,
          effectiveMusic?.audioUrl || null,
          effectiveMusic?.id || null,
          effectiveMusic?.title || null,
          effectiveMusic?.artist || null,
          effectiveMusic?.source || null,
          effectiveMusic?.sourceUrl || null,
          effectiveMusic?.license || null,
          effectiveMusic?.licenseUrl || null,
          effectiveMusic?.startMs || 0,
          effectiveMusic?.durationMs || 6000,
          effectiveMusic?.endMs || null,
          effectiveMusic?.musicVolume ?? 1,
          effectiveMusic?.originalAudioVolume ?? 1,
          musicFadeInMs,
          musicFadeOutMs,
          duckOriginalAudio,
          musicEmbedded,
          effectiveMusic?.presentation || 'card',
          effectiveMusic?.artworkUrl || null,
          effectiveMusic?.overlayX ?? .5, effectiveMusic?.overlayY ?? .5,
          effectiveMusic?.overlayScale ?? 1, effectiveMusic?.overlayRotation ?? 0,
          effectiveMusic?.lyrics || null,
        ],
      )
      momentPersisted = true

      if (effectiveMusic) {
        await pool.query(
          `INSERT INTO fallback_moment_music(
             moment_id,track_key,provider,title,artist,track_owner_id,attribution,source_url,license_name,license_url,
             start_ms,end_ms,used_duration_ms,music_volume,original_audio_volume,fade_in_ms,fade_out_ms,duck_original,embedded_in_media
           ) VALUES($1::uuid,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19)
           ON CONFLICT(moment_id) DO UPDATE SET
             track_key=EXCLUDED.track_key,provider=EXCLUDED.provider,title=EXCLUDED.title,
             artist=EXCLUDED.artist,track_owner_id=EXCLUDED.track_owner_id,attribution=EXCLUDED.attribution,
             source_url=EXCLUDED.source_url,license_name=EXCLUDED.license_name,
             license_url=EXCLUDED.license_url,start_ms=EXCLUDED.start_ms,end_ms=EXCLUDED.end_ms,
             used_duration_ms=EXCLUDED.used_duration_ms,music_volume=EXCLUDED.music_volume,original_audio_volume=EXCLUDED.original_audio_volume,
             fade_in_ms=EXCLUDED.fade_in_ms,fade_out_ms=EXCLUDED.fade_out_ms,duck_original=EXCLUDED.duck_original,
             embedded_in_media=EXCLUDED.embedded_in_media`,
          [
            rows[0].id, effectiveMusic.id, effectiveMusic.source, effectiveMusic.title, effectiveMusic.artist,
            effectiveMusic.ownerId, effectiveMusic.attribution || `${effectiveMusic.title} — ${effectiveMusic.artist}`,
            effectiveMusic.sourceUrl, effectiveMusic.license, effectiveMusic.licenseUrl || effectiveMusic.sourceUrl,
            effectiveMusic.startMs, effectiveMusic.endMs, effectiveMusic.durationMs,
            effectiveMusic.musicVolume, effectiveMusic.originalAudioVolume,
            musicFadeInMs, musicFadeOutMs, duckOriginalAudio, musicEmbedded,
          ],
        )
        await pool.query(
          `INSERT INTO fallback_music_play_events(track_key,user_id,event_type,listened_ms,context)
           VALUES($1,$2::uuid,'moment_used',$3,'moment_editor')`,
          [effectiveMusic.id, user.id, effectiveMusic.durationMs],
        )
      }

      if (typeof createLocalNotification === 'function') {
        const recipients = await momentNotificationRecipientIds(pool, identity.displayUserId, rows[0])
        await Promise.all(
          recipients.map((recipientId) =>
            createLocalNotification(pool, {
              recipientId,
              actorId: identity.displayUserId,
              actorName: user.display_name || user.email || 'Usuario',
              actorAvatarUrl: user.avatar_url || '',
              type: 'new_moment',
              targetId: rows[0]?.id,
              targetType: 'moment',
              content: 'Subió un nuevo momento.',
              extraData: momentNotificationExtraData(rows[0], expiresAt),
            }),
          ),
        )
      }

      void publishRealtimeEvent('moments:live', { event: 'moment', action: 'created', momentId: rows[0]?.id })
      res.status(201).json({ moment: rows[0] })
    } catch (error) {
      if (renderedAudioUrl && !momentPersisted) {
        await removeRenderedMomentMusic(pool, renderedAudioUrl).catch(() => {})
      }
      next(error)
    }
  })

  app.post('/v1/data/moments/:momentId/music', authRequired, async (req, res, next) => {
    let renderedAudioUrl = null
    let previousAudioUrl = null
    let musicUpdated = false
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = normalizeId(req.params.momentId)
      const ownerIds = Array.from(identity.ids || []).map(normalizeId).filter(Boolean)
      const owned = await pool.query(
        `SELECT id::text,media_type,music_audio_url FROM fallback_moments
         WHERE lower(id::text)=$1 AND lower(user_id)=ANY($2::text[]) LIMIT 1`,
        [momentId, ownerIds],
      )
      if (!owned.rows[0]) return res.status(404).json({ error: 'MOMENT_NOT_FOUND' })
      previousAudioUrl = owned.rows[0].music_audio_url || null
      const approvedMusic = await verifyApprovedMomentMusic(pool, req.body, {
        user,
        currentUserOwnsStorageObject,
      })
      if (!approvedMusic) return res.status(400).json({ error: 'MUSIC_SELECTION_REQUIRED' })
      const selectedMusic = normalizedMomentMusicForMedia(
        approvedMusic,
        normalizeMomentMediaType(owned.rows[0].media_type, ''),
        req.body,
      )
      const effectiveMusic = await renderCompatibleMomentMusic({
        pool,
        req,
        user,
        approvedMusic: selectedMusic,
      })
      renderedAudioUrl = effectiveMusic.audioUrl
      const fadeLimitMs = Math.min(5000, Math.floor(effectiveMusic.durationMs / 2))
      const fadeInMs = Math.max(0, Math.min(Math.round(numberOf(req.body, ['musicFadeInMs', 'music_fade_in_ms'], 0)), fadeLimitMs))
      const fadeOutMs = Math.max(0, Math.min(Math.round(numberOf(req.body, ['musicFadeOutMs', 'music_fade_out_ms'], 0)), fadeLimitMs))
      const duckOriginal = boolOf(req.body, ['duckOriginalAudio', 'duck_original_audio'], false)
      const { rows } = await pool.query(
        `UPDATE fallback_moments SET
           music_url=$2,music_audio_url=$2,music_id=$3,music_title=$4,music_artist=$5,
           music_source=$6,music_source_url=$7,music_license=$8,music_license_url=$9,
           music_start_ms=$10,music_duration_ms=$11,music_end_ms=$12,music_volume=$13,
           original_audio_volume=$14,music_fade_in_ms=$15,music_fade_out_ms=$16,
           duck_original_audio=$17,music_embedded=FALSE,music_presentation=$18,music_artwork_url=$19,
           music_overlay_x=$20,music_overlay_y=$21,music_overlay_scale=$22,music_overlay_rotation=$23,music_lyrics=$24,updated_at=NOW()
         WHERE id=$1::uuid RETURNING *`,
        [
          owned.rows[0].id, effectiveMusic.audioUrl, effectiveMusic.id, effectiveMusic.title,
          effectiveMusic.artist, effectiveMusic.source, effectiveMusic.sourceUrl, effectiveMusic.license,
          effectiveMusic.licenseUrl || effectiveMusic.sourceUrl, effectiveMusic.startMs,
          effectiveMusic.durationMs, effectiveMusic.endMs, effectiveMusic.musicVolume,
          effectiveMusic.originalAudioVolume, fadeInMs, fadeOutMs, duckOriginal,
          effectiveMusic.presentation, effectiveMusic.artworkUrl,
          effectiveMusic.overlayX, effectiveMusic.overlayY, effectiveMusic.overlayScale, effectiveMusic.overlayRotation, effectiveMusic.lyrics,
        ],
      )
      musicUpdated = true
      await pool.query(
        `INSERT INTO fallback_moment_music(
           moment_id,track_key,provider,title,artist,track_owner_id,attribution,source_url,license_name,license_url,
           start_ms,end_ms,used_duration_ms,music_volume,original_audio_volume,fade_in_ms,fade_out_ms,duck_original,embedded_in_media
         ) VALUES($1::uuid,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,FALSE)
         ON CONFLICT(moment_id) DO UPDATE SET
           track_key=EXCLUDED.track_key,provider=EXCLUDED.provider,title=EXCLUDED.title,
           artist=EXCLUDED.artist,track_owner_id=EXCLUDED.track_owner_id,attribution=EXCLUDED.attribution,
           source_url=EXCLUDED.source_url,license_name=EXCLUDED.license_name,
           license_url=EXCLUDED.license_url,start_ms=EXCLUDED.start_ms,end_ms=EXCLUDED.end_ms,
           used_duration_ms=EXCLUDED.used_duration_ms,music_volume=EXCLUDED.music_volume,original_audio_volume=EXCLUDED.original_audio_volume,
           fade_in_ms=EXCLUDED.fade_in_ms,fade_out_ms=EXCLUDED.fade_out_ms,
           duck_original=EXCLUDED.duck_original,embedded_in_media=FALSE`,
        [
          owned.rows[0].id, effectiveMusic.id, effectiveMusic.source, effectiveMusic.title,
          effectiveMusic.artist, effectiveMusic.ownerId,
          effectiveMusic.attribution || `${effectiveMusic.title} — ${effectiveMusic.artist}`,
          effectiveMusic.sourceUrl, effectiveMusic.license,
          effectiveMusic.licenseUrl || effectiveMusic.sourceUrl, effectiveMusic.startMs,
          effectiveMusic.endMs, effectiveMusic.durationMs, effectiveMusic.musicVolume,
          effectiveMusic.originalAudioVolume, fadeInMs, fadeOutMs, duckOriginal,
        ],
      )
      await pool.query(
        `INSERT INTO fallback_music_play_events(track_key,user_id,event_type,listened_ms,context)
         VALUES($1,$2::uuid,'moment_used',$3,'moment_editor')`,
        [effectiveMusic.id, user.id, effectiveMusic.durationMs],
      )
      if (previousAudioUrl && previousAudioUrl !== renderedAudioUrl) {
        await removeRenderedMomentMusic(pool, previousAudioUrl)
      }
      res.json({ moment: rows[0] })
    } catch (error) {
      if (renderedAudioUrl && !musicUpdated) {
        await removeRenderedMomentMusic(pool, renderedAudioUrl).catch(() => {})
      }
      next(error)
    }
  })

  app.delete('/v1/data/moments/:momentId/music', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = normalizeId(req.params.momentId)
      const ownerIds = Array.from(identity.ids || []).map(normalizeId).filter(Boolean)
      const { rows } = await pool.query(
        `WITH target AS (
           SELECT id,music_audio_url
             FROM fallback_moments
            WHERE lower(id::text)=$1 AND lower(user_id)=ANY($2::text[])
            FOR UPDATE
         ), updated AS (
           UPDATE fallback_moments AS moment SET
             music_url=NULL,music_audio_url=NULL,music_id=NULL,music_title=NULL,music_artist=NULL,
             music_source=NULL,music_source_url=NULL,music_license=NULL,music_license_url=NULL,
             music_presentation='card',music_artwork_url=NULL,
             music_overlay_x=.5,music_overlay_y=.5,music_overlay_scale=1,music_overlay_rotation=0,music_lyrics=NULL,
             music_start_ms=0,music_duration_ms=6000,music_end_ms=NULL,music_volume=1,
             original_audio_volume=1,music_fade_in_ms=0,music_fade_out_ms=0,
             duck_original_audio=FALSE,music_embedded=FALSE,updated_at=NOW()
            FROM target WHERE moment.id=target.id RETURNING moment.id
         )
         SELECT updated.id::text,target.music_audio_url
           FROM updated JOIN target ON target.id=updated.id`,
        [momentId, ownerIds],
      )
      if (!rows[0]) return res.status(404).json({ error: 'MOMENT_NOT_FOUND' })
      await pool.query(`DELETE FROM fallback_moment_music WHERE moment_id=$1::uuid`, [rows[0].id])
      await removeRenderedMomentMusic(pool, rows[0].music_audio_url)
      res.json({ removed: true })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/moments/:momentId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = normalizeId(req.params.momentId)
      const moment = await visibleMomentForInteraction(pool, identity, momentId)
      if (!moment) {
        return res.status(404).json({ error: 'MOMENT_NOT_FOUND' })
      }
      const { rows: added } = await pool.query(
        `INSERT INTO fallback_moment_reactions(moment_id, user_id, reaction)
         VALUES ($1::uuid, $2, 'like')
         ON CONFLICT (moment_id, user_id) DO NOTHING RETURNING moment_id`,
        [momentId, identity.displayUserId],
      )
      const ownerId = normalizeId(moment.user_id)
      if (added.length && ownerId && !identity.ids.has(ownerId) && typeof createLocalNotification === 'function') {
        try {
          await createLocalNotification(pool, {
            recipientId: ownerId, actorId: identity.displayUserId,
            actorName: user.display_name || 'Usuario', actorAvatarUrl: user.avatar_url || '',
            type: 'like_moment', targetId: momentId, targetType: 'moment',
            content: 'Le gustó tu momento.', extraData: momentNotificationExtraData(moment),
          })
        } catch (error) {
          console.warn('[moment-like] notification failed', String(error?.code || 'DELIVERY_FAILED'))
        }
      }
      res.json({ ok: true, liked: true })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/moments/:momentId/reaction', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = normalizeId(req.params.momentId)
      await pool.query(
        `DELETE FROM fallback_moment_reactions WHERE lower(moment_id::text) = $1 AND lower(user_id) = ANY($2::text[])`,
        [momentId, Array.from(identity.ids)],
      )
      res.json({ ok: true, liked: false })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/moments/:momentId/view', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = textOf(req.params, ['momentId'], '')
      if (!momentId) return res.status(400).json({ error: 'MOMENT_ID_REQUIRED' })

      const moment = await visibleMomentForInteraction(pool, identity, momentId)
      if (!moment) return res.status(404).json({ error: 'MOMENT_NOT_FOUND' })
      const ownerId = normalizeId(moment.user_id)
      if (ownerId && identity.ids.has(ownerId)) {
        await pool.query(
          `DELETE FROM fallback_moment_views
           WHERE lower(moment_id::text) = $1 AND lower(user_id) = ANY($2::text[])`,
          [normalizeId(momentId), Array.from(identity.ids)],
        )
        return res.json({ ok: true, self_view_ignored: true })
      }
      await pool.query(
        `INSERT INTO fallback_moment_views(moment_id, user_id, viewed_at)
         VALUES ($1::uuid, $2, NOW())
         ON CONFLICT (moment_id, user_id)
         DO UPDATE SET viewed_at = NOW()`,
        [momentId, identity.displayUserId],
      )
      void publishRealtimeEvent('moments:live', { event: 'moment', action: 'view', momentId })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/moments/:momentId/viewers', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = normalizeId(req.params.momentId)
      if (!momentId) return res.status(400).json({ error: 'MOMENT_ID_REQUIRED' })

      const { rows: localRows } = await pool.query(
        `SELECT id::text, user_id
         FROM fallback_moments
         WHERE lower(id::text) = $1
         LIMIT 1`,
        [momentId],
      )
      let ownerId = normalizeId(textOf(localRows[0] || {}, ['user_id'], ''))
      if (!ownerId) {
        const imported = await importedRows(pool, 'moments', 5000)
        const importedRow = imported.find((row) => normalizeId(textOf(row, ['id'], '')) === momentId)
        ownerId = normalizeId(textOf(importedRow || {}, ['user_id', 'userId', 'owner_id', 'profile_id', 'author_id'], ''))
      }
      if (!ownerId) return res.status(404).json({ error: 'MOMENT_NOT_FOUND' })
      if (!identity.ids.has(ownerId) && ownerId !== identity.email) return res.status(403).json({ error: 'MOMENT_NOT_OWNED' })

      const { rows: viewRows } = await pool.query(
        `SELECT moment_id::text, user_id, viewed_at
         FROM fallback_moment_views
         WHERE lower(moment_id::text) = $1
         ORDER BY viewed_at DESC
         LIMIT 500`,
        [momentId],
      )
      const importedViews = (await importedRows(pool, 'moment_views', 5000))
        .filter((row) => normalizeId(textOf(row, ['moment_id'], '')) === momentId)
        .map((row) => ({
          moment_id: textOf(row, ['moment_id'], ''),
          user_id: textOf(row, ['user_id'], ''),
          viewed_at: textOf(row, ['viewed_at'], ''),
        }))
      const seen = new Set()
      const profilesById = new Map()
      const items = []

      for (const row of [...viewRows, ...importedViews]) {
        const viewerId = normalizeId(textOf(row, ['user_id'], ''))
        if (!viewerId || identity.ids.has(viewerId) || seen.has(viewerId)) continue
        seen.add(viewerId)
        const profile = await profileFor(pool, viewerId, profilesById)
        items.push({
          id: `${momentId}:${viewerId}`,
          moment_id: momentId,
          user_id: viewerId,
          viewed_at: textOf(row, ['viewed_at'], ''),
          username: profile.name,
          display_name: profile.name,
          user_display_name: profile.name,
          avatar_url: profile.avatarUrl,
          user_avatar_url: profile.avatarUrl,
        })
      }

      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/moments/:momentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = normalizeId(req.params.momentId)
      if (!momentId) return res.status(400).json({ error: 'MOMENT_ID_REQUIRED' })

      const { rows: localRows } = await pool.query(
        `SELECT id::text, user_id, music_audio_url
         FROM fallback_moments
         WHERE lower(id::text) = $1
         LIMIT 1`,
        [momentId],
      )
      let ownerId = normalizeId(textOf(localRows[0] || {}, ['user_id'], ''))
      if (!ownerId) {
        const imported = await importedRows(pool, 'moments', 5000)
        const importedRow = imported.find((row) => normalizeId(textOf(row, ['id'], '')) === momentId)
        ownerId = normalizeId(textOf(importedRow || {}, ['user_id', 'userId', 'owner_id', 'profile_id', 'author_id'], ''))
      }
      if (!ownerId) return res.status(404).json({ error: 'MOMENT_NOT_FOUND' })
      if (!identity.ids.has(ownerId) && ownerId !== identity.email) return res.status(403).json({ error: 'MOMENT_NOT_OWNED' })

      await pool.query(
        `DELETE FROM fallback_moments
         WHERE lower(id::text) = $1`,
        [momentId],
      )
      await pool.query(
        `INSERT INTO fallback_moment_deletions(moment_id, user_id, deleted_at)
         VALUES ($1, $2, NOW())
         ON CONFLICT (moment_id)
         DO UPDATE SET user_id = EXCLUDED.user_id, deleted_at = NOW()`,
        [momentId, identity.displayUserId],
      )
      await pool.query(`DELETE FROM fallback_moment_views WHERE lower(moment_id::text) = $1`, [momentId])
      await removeRenderedMomentMusic(pool, localRows[0]?.music_audio_url)

      void publishRealtimeEvent('moments:live', { event: 'moment', action: 'deleted', momentId })
      res.json({ ok: true, deleted: true })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  ensureMomentsTables,
  registerMomentsRoutes,
  buildMomentsFeed,
  requestedMomentOwners,
  visibleMomentForInteraction,
  momentAudienceAllows,
  mergeMomentOwnerProfile,
  normalizedApprovedMusicLicense,
  isPlayableMomentMusicMime,
  normalizedMomentDisplayDurationMs,
  normalizedMomentMusicForMedia,
  momentMusicPresentationFields,
  verifyApprovedCommonsMusic,
  verifyApprovedMomentMusic,
  verifyOwnedLocalMomentMusic,
  momentNotificationExtraData,
  momentNotificationRecipientIds,
}
