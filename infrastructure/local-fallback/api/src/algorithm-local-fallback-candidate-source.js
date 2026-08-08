'use strict'

/**
 * Read-only, fail-closed candidate source for the isolated user-algorithm
 * recommendation route.
 *
 * This is intentionally separate from data-routes.js.  It reads only the
 * local-fallback tables and returns the narrow raw-candidate contract consumed
 * by algorithm-candidate-provider.js; it neither registers an endpoint nor
 * changes any legacy feed.  A host must still compose this source with the
 * candidate provider before registering the isolated recommendation route.
 *
 * Privacy/safety boundary:
 * - queries project an explicit public-field allow-list (never SELECT *);
 * - profile visibility, hidden mode, deletion and active suspension are
 *   evaluated by PostgreSQL before a candidate leaves this module;
 * - fallback_user_blocks is read only through the bidirectional edge reader;
 * - no direct messages, precise location, raw age, gender, contact details or
 *   credentials are queried or returned.
 *
 * Missing safety tables and database failures return no candidates.  A block
 * reader failure throws, so algorithm-candidate-provider fails the whole
 * request rather than accidentally showing a blocked creator.
 */

const crypto = require('crypto')

const SUPPORTED_SURFACES = new Set(['social', 'video'])
const MAX_CANDIDATES = 200
const MAX_ALIASES = 64
const MAX_TAGS = 16
const MAX_MEDIA = 10

const REQUIRED_TABLES = Object.freeze({
  social: Object.freeze([
    'fallback_posts',
    'fallback_users',
    'fallback_profile_overrides',
    'fallback_profile_moderation',
    'fallback_deleted_identities',
    'fallback_user_blocks',
    'fallback_follows',
    'fallback_algorithm_content_classifications',
    'fallback_algorithm_content_topics',
  ]),
  video: Object.freeze([
    'fallback_videos',
    'fallback_video_deletions',
    'fallback_users',
    'fallback_profile_overrides',
    'fallback_profile_moderation',
    'fallback_deleted_identities',
    'fallback_user_blocks',
    'fallback_follows',
    'fallback_algorithm_content_classifications',
    'fallback_algorithm_content_topics',
  ]),
})

// These two selectors intentionally contain only the fields that the current
// public Social/Video response contracts need.  New fields must be reviewed
// before they are added here; never use SELECT * in this module.
const SOCIAL_CANDIDATE_SQL = `
  SELECT
    post.id::text AS id,
    post.user_id AS source_creator_id,
    account.id::text AS local_creator_id,
    account.supabase_user_id::text AS external_creator_id,
    COALESCE(NULLIF(account.supabase_user_id::text, ''), account.id::text) AS public_creator_id,
    COALESCE(
      NULLIF(profile.profile->>'display_name', ''),
      NULLIF(profile.profile->>'displayName', ''),
      NULLIF(account.display_name, ''),
      'Usuario'
    ) AS display_name,
    COALESCE(
      NULLIF(profile.profile->>'avatar_url', ''),
      NULLIF(profile.profile->>'avatarUrl', ''),
      NULLIF(account.avatar_url, '')
    ) AS avatar_url,
    post.content,
    post.media_url,
    CASE
      WHEN jsonb_typeof(post.media_urls) = 'array' THEN post.media_urls
      ELSE '[]'::jsonb
    END AS media_urls,
    post.media_type,
    post.created_at,
    post.updated_at,
    profile.updated_at AS profile_updated_at,
    classification.language_code,
    classification.format_key,
    classification.audio_key,
    classification.community_id,
    classification.updated_at AS classification_updated_at,
    classification_topics.entries AS classification_topics,
    EXISTS (
      SELECT 1
      FROM fallback_follows follow_edge
      WHERE lower(follow_edge.follower_id) = ANY($1::text[])
        AND lower(follow_edge.following_id) = ANY(
          ARRAY[
            lower(post.user_id),
            lower(account.id::text),
            lower(COALESCE(account.supabase_user_id::text, ''))
          ]::text[]
        )
    ) AS is_followed_creator
  FROM fallback_posts post
  JOIN LATERAL (
    SELECT candidate_account.id, candidate_account.supabase_user_id,
           candidate_account.display_name, candidate_account.avatar_url
    FROM fallback_users candidate_account
    WHERE lower(candidate_account.id::text) = lower(post.user_id)
       OR lower(COALESCE(candidate_account.supabase_user_id::text, '')) = lower(post.user_id)
    ORDER BY candidate_account.updated_at DESC NULLS LAST, candidate_account.created_at DESC
    LIMIT 1
  ) account ON TRUE
  LEFT JOIN fallback_profile_overrides profile ON profile.user_id = account.id
  JOIN fallback_algorithm_content_classifications classification
    ON classification.content_kind = 'post'
   AND lower(classification.content_id) = lower(post.id::text)
   AND classification.safety_state = 'allowed'
  JOIN LATERAL (
    SELECT jsonb_agg(
      jsonb_build_object(
        'topic_key', classified_topic.topic_key,
        'subtopic_key', classified_topic.subtopic_key,
        'confidence', classified_topic.confidence
      )
      ORDER BY classified_topic.confidence DESC, classified_topic.topic_key, classified_topic.subtopic_key
    ) AS entries
    FROM fallback_algorithm_content_topics classified_topic
    WHERE classified_topic.classification_id = classification.id
  ) classification_topics ON classification_topics.entries IS NOT NULL
  WHERE lower(COALESCE(NULLIF(post.visibility, ''), 'public')) = 'public'
    AND lower(COALESCE(
      NULLIF(profile.profile->>'profile_visibility', ''),
      NULLIF(profile.profile->>'profileVisibility', ''),
      'everyone'
    )) IN ('everyone', 'public')
    AND lower(COALESCE(
      NULLIF(profile.profile->>'is_private', ''),
      NULLIF(profile.profile->>'isPrivate', ''),
      'false'
    )) NOT IN ('true', '1', 'yes')
    AND lower(COALESCE(
      NULLIF(profile.profile->>'break_mode', ''),
      NULLIF(profile.profile->>'breakMode', ''),
      'off'
    )) IN ('off', 'mute')
    AND NOT (
      lower(post.user_id) = ANY($1::text[])
      OR lower(account.id::text) = ANY($1::text[])
      OR lower(COALESCE(account.supabase_user_id::text, '')) = ANY($1::text[])
    )
    AND NOT EXISTS (
      SELECT 1
      FROM fallback_profile_moderation moderation
      WHERE lower(moderation.user_id) = ANY(
        ARRAY[
          lower(post.user_id),
          lower(account.id::text),
          lower(COALESCE(account.supabase_user_id::text, ''))
        ]::text[]
      )
        AND moderation.is_suspended = TRUE
        AND (
          COALESCE(moderation.suspended_until, moderation.suspension_end_date) IS NULL
          OR COALESCE(moderation.suspended_until, moderation.suspension_end_date) > NOW()
        )
    )
    AND NOT EXISTS (
      SELECT 1
      FROM fallback_deleted_identities deleted_identity
      WHERE lower(deleted_identity.user_id) = ANY(
        ARRAY[
          lower(post.user_id),
          lower(account.id::text),
          lower(COALESCE(account.supabase_user_id::text, ''))
        ]::text[]
      )
    )
  ORDER BY post.updated_at DESC, post.id DESC
  LIMIT $2
`

const VIDEO_CANDIDATE_SQL = `
  SELECT
    video.id::text AS id,
    video.user_id AS source_creator_id,
    account.id::text AS local_creator_id,
    account.supabase_user_id::text AS external_creator_id,
    COALESCE(NULLIF(account.supabase_user_id::text, ''), account.id::text) AS public_creator_id,
    COALESCE(
      NULLIF(profile.profile->>'display_name', ''),
      NULLIF(profile.profile->>'displayName', ''),
      NULLIF(account.display_name, ''),
      'Usuario'
    ) AS display_name,
    COALESCE(
      NULLIF(profile.profile->>'avatar_url', ''),
      NULLIF(profile.profile->>'avatarUrl', ''),
      NULLIF(account.avatar_url, '')
    ) AS avatar_url,
    video.title,
    video.description,
    video.video_url,
    video.cover_url,
    video.thumbnail_url,
    video.duration_seconds,
    video.views,
    video.like_count,
    video.comment_count,
    video.share_count,
    video.created_at,
    video.updated_at,
    profile.updated_at AS profile_updated_at,
    classification.language_code,
    classification.format_key,
    classification.audio_key,
    classification.community_id,
    classification.updated_at AS classification_updated_at,
    classification_topics.entries AS classification_topics,
    EXISTS (
      SELECT 1
      FROM fallback_follows follow_edge
      WHERE lower(follow_edge.follower_id) = ANY($1::text[])
        AND lower(follow_edge.following_id) = ANY(
          ARRAY[
            lower(video.user_id),
            lower(account.id::text),
            lower(COALESCE(account.supabase_user_id::text, ''))
          ]::text[]
        )
    ) AS is_followed_creator
  FROM fallback_videos video
  JOIN LATERAL (
    SELECT candidate_account.id, candidate_account.supabase_user_id,
           candidate_account.display_name, candidate_account.avatar_url
    FROM fallback_users candidate_account
    WHERE lower(candidate_account.id::text) = lower(video.user_id)
       OR lower(COALESCE(candidate_account.supabase_user_id::text, '')) = lower(video.user_id)
    ORDER BY candidate_account.updated_at DESC NULLS LAST, candidate_account.created_at DESC
    LIMIT 1
  ) account ON TRUE
  LEFT JOIN fallback_profile_overrides profile ON profile.user_id = account.id
  JOIN fallback_algorithm_content_classifications classification
    ON classification.content_kind = 'video'
   AND lower(classification.content_id) = lower(video.id::text)
   AND classification.safety_state = 'allowed'
  JOIN LATERAL (
    SELECT jsonb_agg(
      jsonb_build_object(
        'topic_key', classified_topic.topic_key,
        'subtopic_key', classified_topic.subtopic_key,
        'confidence', classified_topic.confidence
      )
      ORDER BY classified_topic.confidence DESC, classified_topic.topic_key, classified_topic.subtopic_key
    ) AS entries
    FROM fallback_algorithm_content_topics classified_topic
    WHERE classified_topic.classification_id = classification.id
  ) classification_topics ON classification_topics.entries IS NOT NULL
  WHERE video.is_public = TRUE
    AND NOT EXISTS (
      SELECT 1
      FROM fallback_video_deletions deleted_video
      WHERE lower(deleted_video.video_id) = lower(video.id::text)
    )
    AND lower(COALESCE(
      NULLIF(profile.profile->>'profile_visibility', ''),
      NULLIF(profile.profile->>'profileVisibility', ''),
      'everyone'
    )) IN ('everyone', 'public')
    AND lower(COALESCE(
      NULLIF(profile.profile->>'is_private', ''),
      NULLIF(profile.profile->>'isPrivate', ''),
      'false'
    )) NOT IN ('true', '1', 'yes')
    AND lower(COALESCE(
      NULLIF(profile.profile->>'break_mode', ''),
      NULLIF(profile.profile->>'breakMode', ''),
      'off'
    )) IN ('off', 'mute')
    AND NOT (
      lower(video.user_id) = ANY($1::text[])
      OR lower(account.id::text) = ANY($1::text[])
      OR lower(COALESCE(account.supabase_user_id::text, '')) = ANY($1::text[])
    )
    AND NOT EXISTS (
      SELECT 1
      FROM fallback_profile_moderation moderation
      WHERE lower(moderation.user_id) = ANY(
        ARRAY[
          lower(video.user_id),
          lower(account.id::text),
          lower(COALESCE(account.supabase_user_id::text, ''))
        ]::text[]
      )
        AND moderation.is_suspended = TRUE
        AND (
          COALESCE(moderation.suspended_until, moderation.suspension_end_date) IS NULL
          OR COALESCE(moderation.suspended_until, moderation.suspension_end_date) > NOW()
        )
    )
    AND NOT EXISTS (
      SELECT 1
      FROM fallback_deleted_identities deleted_identity
      WHERE lower(deleted_identity.user_id) = ANY(
        ARRAY[
          lower(video.user_id),
          lower(account.id::text),
          lower(COALESCE(account.supabase_user_id::text, ''))
        ]::text[]
      )
    )
  ORDER BY video.updated_at DESC, video.id DESC
  LIMIT $2
`

const BLOCK_EDGE_SQL = `
  SELECT lower(blocker_id) AS blocker_id, lower(blocked_id) AS blocked_id
  FROM fallback_user_blocks
  WHERE (
    lower(blocker_id) = ANY($1::text[])
    AND lower(blocked_id) = ANY($2::text[])
  ) OR (
    lower(blocker_id) = ANY($2::text[])
    AND lower(blocked_id) = ANY($1::text[])
  )
  ORDER BY created_at DESC
  LIMIT $3
`

const TABLE_AVAILABILITY_SQL = `
  SELECT required.table_name
  FROM unnest($1::text[]) AS required(table_name)
  WHERE to_regclass(required.table_name) IS NULL
`

class LocalFallbackCandidateSourceError extends Error {
  constructor(code) {
    super(code)
    this.name = 'LocalFallbackCandidateSourceError'
    this.code = code
  }
}

function normalizeId(value, maxLength = 180) {
  const text = String(value == null ? '' : value).trim().toLowerCase()
  return text && text.length <= maxLength ? text : null
}

function normalizeSurface(value) {
  const surface = normalizeId(value, 32)
  return surface && SUPPORTED_SURFACES.has(surface) ? surface : null
}

function uniqueIds(values, maxItems = MAX_ALIASES) {
  const result = []
  const seen = new Set()
  const source = Array.isArray(values) ? values : [values]
  for (const value of source) {
    const normalized = normalizeId(value)
    if (!normalized || seen.has(normalized)) continue
    seen.add(normalized)
    result.push(normalized)
    if (result.length >= maxItems) break
  }
  return result
}

function ownerAliases(owner) {
  const identity = owner?.identity || {}
  const ids = identity.ids instanceof Set
    ? Array.from(identity.ids)
    : (Array.isArray(identity.ids) ? identity.ids : [])
  return uniqueIds([
    owner?.userId,
    identity.displayUserId,
    identity.localUserId,
    identity.preferredUserId,
    ...ids,
  ])
}

function boundedInteger(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isInteger(number)) return fallback
  return Math.max(min, Math.min(max, number))
}

function cleanPublicText(value, maxLength = 8_000) {
  const text = String(value == null ? '' : value)
    .replace(/[\u0000-\u001F\u007F]/g, ' ')
    .trim()
  return text ? text.slice(0, maxLength) : ''
}

function publicDisplayName(value) {
  const name = cleanPublicText(value, 120)
  // Legacy writes occasionally used a login identifier as a display fallback.
  // Do not turn that implementation detail into recommendation output.
  return name && !name.includes('@') ? name : 'Usuario'
}

function safePublicUrl(value) {
  const raw = String(value == null ? '' : value).trim()
  if (!raw || raw.length > 4_096) return null
  try {
    const parsed = new URL(raw)
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password) return null
    for (const key of parsed.searchParams.keys()) {
      if (/(?:token|secret|signature|sig|credential|authorization|session|key)/i.test(key)) return null
    }
    return parsed.toString()
  } catch {
    return null
  }
}

function asArray(value) {
  if (Array.isArray(value)) return value
  if (typeof value !== 'string') return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function safeMediaUrls(value) {
  const result = []
  const seen = new Set()
  for (const entry of asArray(value)) {
    const url = safePublicUrl(typeof entry === 'string' ? entry : entry?.url)
    if (!url || seen.has(url)) continue
    seen.add(url)
    const mediaType = cleanPublicText(entry?.media_type ?? entry?.mediaType ?? '', 48).toLowerCase()
    result.push(mediaType ? { url, media_type: mediaType } : { url })
    if (result.length >= MAX_MEDIA) break
  }
  return result
}

function safeClassificationKey(value, maxLength = 80) {
  const normalized = cleanPublicText(value, maxLength).toLowerCase()
  return /^[a-z0-9][a-z0-9:_-]*$/.test(normalized) ? normalized : null
}

/**
 * Only classifications written by a trusted server process can reach the
 * ranking inputs.  In particular, never use creator-provided video tags,
 * post text, or media type as an inferred topic.
 */
function classificationMetadata(row) {
  const topics = []
  const subtopics = []
  const seenTopics = new Set()
  const seenSubtopics = new Set()
  for (const entry of asArray(row?.classification_topics)) {
    if (!entry || typeof entry !== 'object') continue
    const topic = safeClassificationKey(entry.topic_key ?? entry.topicKey)
    const subtopic = safeClassificationKey(entry.subtopic_key ?? entry.subtopicKey)
    if (topic && !seenTopics.has(topic)) {
      seenTopics.add(topic)
      topics.push(topic)
    }
    if (subtopic && !seenSubtopics.has(subtopic)) {
      seenSubtopics.add(subtopic)
      subtopics.push(subtopic)
    }
    if (topics.length >= MAX_TAGS && subtopics.length >= MAX_TAGS) break
  }
  return {
    topics: topics.slice(0, MAX_TAGS),
    subtopics: subtopics.slice(0, MAX_TAGS),
    formatKey: safeClassificationKey(row?.format_key ?? row?.formatKey),
    audioKey: safeClassificationKey(row?.audio_key ?? row?.audioKey),
    languageCode: safeClassificationKey(row?.language_code ?? row?.languageCode),
    communityId: safeClassificationKey(row?.community_id ?? row?.communityId),
  }
}

function safeTimestamp(value) {
  const time = new Date(value).getTime()
  return Number.isFinite(time) ? new Date(time).toISOString() : null
}

function safeNumber(value, max = Number.MAX_SAFE_INTEGER) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.max(0, Math.min(Math.round(number), max)) : 0
}

function freshnessSignal(value, now) {
  const time = new Date(value).getTime()
  if (!Number.isFinite(time)) return 0
  const ageDays = Math.max(0, Number(now) - time) / 86_400_000
  return Math.max(0, Math.min(1, 1 - (ageDays / 30)))
}

function creatorAliases(row) {
  return uniqueIds([
    row?.public_creator_id,
    row?.external_creator_id,
    row?.local_creator_id,
    row?.source_creator_id,
  ])
}

function baseSafety() {
  return {
    contentPublic: true,
    contentEligible: true,
    creatorVisible: true,
    creatorHidden: false,
    creatorSuspended: false,
  }
}

function socialCandidate(row, now) {
  const id = normalizeId(row?.id)
  const aliases = creatorAliases(row)
  const creatorId = normalizeId(row?.public_creator_id) || aliases[0]
  const classification = classificationMetadata(row)
  if (!id || !creatorId || aliases.length === 0 || classification.topics.length === 0) return null
  const mediaUrls = safeMediaUrls(row.media_urls)
  const mediaUrl = safePublicUrl(row.media_url) || mediaUrls[0]?.url || null
  const mediaType = cleanPublicText(row.media_type, 48).toLowerCase()
  const createdAt = safeTimestamp(row.created_at)
  const updatedAt = safeTimestamp(row.updated_at) || createdAt
  return {
    id,
    surface: 'social',
    creatorId,
    creatorAliases: aliases,
    safety: baseSafety(),
    item: {
      id,
      user_id: creatorId,
      user_name: publicDisplayName(row.display_name),
      user_profile_image: safePublicUrl(row.avatar_url),
      content: cleanPublicText(row.content),
      media_url: mediaUrl,
      media_urls: mediaUrls,
      media_type: mediaType || null,
      visibility: 'public',
      created_at: createdAt,
      updated_at: updatedAt,
    },
    contentKey: `post:${id}`,
    topics: classification.topics,
    subtopics: classification.subtopics,
    formats: ['post', ...(classification.formatKey ? [classification.formatKey] : [])],
    audioKeys: classification.audioKey ? [classification.audioKey] : [],
    languages: classification.languageCode ? [classification.languageCode] : [],
    communityKeys: classification.communityId ? [classification.communityId] : [],
    isFollowedCreator: row.is_followed_creator === true,
    feedSignals: {
      following: row.is_followed_creator === true ? 1 : 0,
      new_content: freshnessSignal(updatedAt || createdAt, now),
    },
    explorationScore: 0.1,
    snapshotUpdatedAt: safeTimestamp(row.classification_updated_at) || updatedAt,
    snapshotProfileUpdatedAt: safeTimestamp(row.profile_updated_at),
  }
}

function videoCandidate(row, now) {
  const id = normalizeId(row?.id)
  const aliases = creatorAliases(row)
  const creatorId = normalizeId(row?.public_creator_id) || aliases[0]
  const classification = classificationMetadata(row)
  if (!id || !creatorId || aliases.length === 0 || classification.topics.length === 0) return null
  const createdAt = safeTimestamp(row.created_at)
  const updatedAt = safeTimestamp(row.updated_at) || createdAt
  const tags = classification.topics
  return {
    id,
    surface: 'video',
    creatorId,
    creatorAliases: aliases,
    safety: baseSafety(),
    item: {
      id,
      user_id: creatorId,
      user_name: publicDisplayName(row.display_name),
      user_profile_image: safePublicUrl(row.avatar_url),
      title: cleanPublicText(row.title, 240),
      description: cleanPublicText(row.description),
      video_url: safePublicUrl(row.video_url),
      cover_url: safePublicUrl(row.cover_url),
      thumbnail_url: safePublicUrl(row.thumbnail_url),
      duration_seconds: safeNumber(row.duration_seconds, 86_400),
      views: safeNumber(row.views),
      like_count: safeNumber(row.like_count),
      comment_count: safeNumber(row.comment_count),
      share_count: safeNumber(row.share_count),
      is_public: true,
      tags,
      created_at: createdAt,
      updated_at: updatedAt,
    },
    contentKey: `video:${id}`,
    topics: tags,
    subtopics: classification.subtopics,
    formats: ['video', ...(classification.formatKey ? [classification.formatKey] : [])],
    audioKeys: classification.audioKey ? [classification.audioKey] : [],
    languages: classification.languageCode ? [classification.languageCode] : [],
    communityKeys: classification.communityId ? [classification.communityId] : [],
    isFollowedCreator: row.is_followed_creator === true,
    feedSignals: {
      following: row.is_followed_creator === true ? 1 : 0,
      new_content: freshnessSignal(updatedAt || createdAt, now),
    },
    explorationScore: 0.1,
    snapshotUpdatedAt: safeTimestamp(row.classification_updated_at) || updatedAt,
    snapshotProfileUpdatedAt: safeTimestamp(row.profile_updated_at),
  }
}

function snapshotId(surface, candidates, unavailable = false) {
  const shape = {
    source: 'local-fallback-candidates-v1',
    surface,
    unavailable,
    candidates: candidates.map((candidate) => ({
      id: candidate.id,
      creatorId: candidate.creatorId,
      following: candidate.isFollowedCreator,
      topics: candidate.topics,
      subtopics: candidate.subtopics,
      formats: candidate.formats,
      audioKeys: candidate.audioKeys,
      languages: candidate.languages,
      communityKeys: candidate.communityKeys,
      updatedAt: candidate.snapshotUpdatedAt,
      profileUpdatedAt: candidate.snapshotProfileUpdatedAt,
      item: candidate.item,
    })),
  }
  const digest = crypto.createHash('sha256').update(JSON.stringify(shape), 'utf8').digest('base64url')
  return `lfc1-${surface}-${digest.slice(0, 48)}`
}

function emptyBatch(surface) {
  return {
    snapshotId: snapshotId(surface || 'unknown', [], true),
    providerState: null,
    inferredSignals: { sources: {} },
    candidates: [],
  }
}

async function tablesAvailable(pool, tableNames) {
  try {
    const result = await pool.query(TABLE_AVAILABILITY_SQL, [tableNames])
    return Array.isArray(result?.rows) && result.rows.length === 0
  } catch {
    return false
  }
}

function rawQueryFor(surface) {
  return surface === 'social' ? SOCIAL_CANDIDATE_SQL : VIDEO_CANDIDATE_SQL
}

function rawMapperFor(surface) {
  return surface === 'social' ? socialCandidate : videoCandidate
}

/**
 * Produces the two reader functions expected by createAlgorithmCandidateProvider.
 * The object is deliberately not coupled to Express or to data-routes.js.
 */
function createAlgorithmLocalFallbackCandidateSource({ pool, now = () => Date.now() } = {}) {
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')

  return {
    async loadRawCandidates(input = {}) {
      const surface = normalizeSurface(input.surface)
      const viewerIds = ownerAliases(input.owner)
      if (!surface || viewerIds.length === 0) return emptyBatch(surface)
      if (!(await tablesAvailable(pool, REQUIRED_TABLES[surface]))) return emptyBatch(surface)

      const limit = boundedInteger(input.maxCandidates ?? input.limit, 1, MAX_CANDIDATES, MAX_CANDIDATES)
      const timestamp = Number.isFinite(Number(input.now)) ? Number(input.now) : Number(now())
      try {
        const result = await pool.query(rawQueryFor(surface), [viewerIds, limit])
        if (!Array.isArray(result?.rows)) return emptyBatch(surface)
        const candidates = result.rows
          .map((row) => rawMapperFor(surface)(row, timestamp))
          .filter(Boolean)
          .slice(0, MAX_CANDIDATES)
        return {
          snapshotId: snapshotId(surface, candidates),
          providerState: null,
          inferredSignals: { sources: {} },
          candidates,
        }
      } catch {
        return emptyBatch(surface)
      }
    },

    async loadBlockEdges(input = {}) {
      const viewerIds = uniqueIds(input.viewerIds)
      const creatorIds = uniqueIds(input.creatorIds, MAX_CANDIDATES)
      if (viewerIds.length === 0 || creatorIds.length === 0) return []
      if (!(await tablesAvailable(pool, ['fallback_user_blocks']))) {
        throw new LocalFallbackCandidateSourceError('RECOMMENDATION_PROVIDER_BLOCKS_UNAVAILABLE')
      }
      try {
        const result = await pool.query(BLOCK_EDGE_SQL, [viewerIds, creatorIds, MAX_CANDIDATES * 2])
        if (!Array.isArray(result?.rows)) {
          throw new LocalFallbackCandidateSourceError('RECOMMENDATION_PROVIDER_BLOCKS_UNAVAILABLE')
        }
        return result.rows
          .map((row) => {
            const blockerId = normalizeId(row?.blocker_id)
            const blockedId = normalizeId(row?.blocked_id)
            return blockerId && blockedId ? { blockerId, blockedId } : null
          })
          .filter(Boolean)
      } catch (error) {
        if (error instanceof LocalFallbackCandidateSourceError) throw error
        throw new LocalFallbackCandidateSourceError('RECOMMENDATION_PROVIDER_BLOCKS_UNAVAILABLE')
      }
    },
  }
}

module.exports = {
  BLOCK_EDGE_SQL,
  LocalFallbackCandidateSourceError,
  REQUIRED_TABLES,
  SOCIAL_CANDIDATE_SQL,
  TABLE_AVAILABILITY_SQL,
  VIDEO_CANDIDATE_SQL,
  createAlgorithmLocalFallbackCandidateSource,
  emptyBatch,
  ownerAliases,
  safeMediaUrls,
  safePublicUrl,
  snapshotId,
}
