const crypto = require('crypto')

const CREATOR_ANALYTICS_FLAG = 'CREATOR_ANALYTICS_ENABLED'
const CREATOR_ANALYTICS_MIN_COHORT = 'CREATOR_ANALYTICS_MIN_COHORT'
const CREATOR_ANALYTICS_HASH_SECRET = 'CREATOR_ANALYTICS_HASH_SECRET'
const DEFAULT_MIN_COHORT = 10
const MIN_ALLOWED_COHORT = 5
const MAX_ALLOWED_COHORT = 100
const MAX_RANGE_DAYS = 366
const MAX_EVENT_AGE_DAYS = 366 * 5

const EVENT_TYPES = new Map([
  ['video_view', 'video'],
  ['video_watch', 'video'],
  ['video_like', 'video'],
  ['video_comment', 'video'],
  ['video_share', 'video'],
  ['video_save', 'video'],
  ['video_follow', 'video'],
  ['live_join', 'live'],
  ['live_leave', 'live'],
  ['live_presence', 'live'],
  ['live_follow', 'live'],
  ['live_comment', 'live'],
  ['live_like', 'live'],
  ['live_gift', 'live'],
])

function envFlag(value) {
  return /^(1|true|yes|on)$/i.test(String(value || '').trim())
}

function boundedInteger(value, minimum, maximum, fallback) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isSafeInteger(parsed)) return fallback
  return Math.min(maximum, Math.max(minimum, parsed))
}

function creatorAnalyticsConfig(env = process.env) {
  return {
    enabled: envFlag(env[CREATOR_ANALYTICS_FLAG]),
    minimumCohort: boundedInteger(
      env[CREATOR_ANALYTICS_MIN_COHORT],
      MIN_ALLOWED_COHORT,
      MAX_ALLOWED_COHORT,
      DEFAULT_MIN_COHORT,
    ),
    hashSecret: String(env[CREATOR_ANALYTICS_HASH_SECRET] || '').trim(),
  }
}

function normalizeId(value, maxLength = 200) {
  const text = String(value || '').trim().toLowerCase()
  if (!text || text.length > maxLength || /[\u0000-\u001f\u007f]/.test(text)) return null
  return text
}

function validUuid(value) {
  const text = normalizeId(value, 36)
  return text && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function normalizeCountry(value) {
  if (value === undefined || value === null || value === '') return null
  const country = String(value).trim().toUpperCase()
  return /^[A-Z]{2}$/.test(country) ? country : null
}

function optionalInteger(value, minimum, maximum) {
  if (value === undefined || value === null || value === '') return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= minimum && parsed <= maximum ? parsed : null
}

function hmac(secret, scope, value) {
  return crypto.createHmac('sha256', secret).update(`${scope}\u0000${String(value)}`).digest('hex')
}

function normalizeOccurredAt(value, now = Date.now()) {
  const timestamp = value === undefined || value === null ? now : Date.parse(value)
  const oldest = now - MAX_EVENT_AGE_DAYS * 24 * 60 * 60 * 1000
  const newest = now + 5 * 60 * 1000
  if (!Number.isFinite(timestamp) || timestamp < oldest || timestamp > newest) return null
  return new Date(timestamp).toISOString()
}

function normalizeServerEvent(event, { hashSecret, now = Date.now() } = {}) {
  if (!event || typeof event !== 'object' || Array.isArray(event)) {
    return { error: 'CREATOR_ANALYTICS_EVENT_INVALID' }
  }
  if (typeof hashSecret !== 'string' || hashSecret.length < 32) {
    return { error: 'CREATOR_ANALYTICS_HASH_SECRET_MISSING' }
  }

  const eventType = String(event.eventType || '').trim().toLowerCase()
  const contentType = EVENT_TYPES.get(eventType)
  const contentId = validUuid(event.contentId)
  const idempotencyKey = normalizeId(event.idempotencyKey, 512)
  const occurredAt = normalizeOccurredAt(event.occurredAt, now)
  if (!contentType || !contentId || !idempotencyKey || !occurredAt) {
    return { error: 'CREATOR_ANALYTICS_EVENT_INVALID' }
  }

  const actorId = normalizeId(event.actorId, 512)
  const sessionId = normalizeId(event.sessionId, 512)
  const countryCode = normalizeCountry(event.countryCode)
  const watchMs = optionalInteger(event.watchMs, 0, 86_400_000)
  const durationMs = optionalInteger(event.durationMs, 1, 86_400_000)
  const concurrentCount = optionalInteger(event.concurrentCount, 0, 10_000_000)
  const valueCoins = optionalInteger(event.valueCoins, 0, 1_000_000_000_000)

  if (event.countryCode && !countryCode) return { error: 'CREATOR_ANALYTICS_COUNTRY_INVALID' }
  if (event.watchMs !== undefined && event.watchMs !== null && watchMs === null) {
    return { error: 'CREATOR_ANALYTICS_WATCH_INVALID' }
  }
  if (event.durationMs !== undefined && event.durationMs !== null && durationMs === null) {
    return { error: 'CREATOR_ANALYTICS_WATCH_INVALID' }
  }
  if (event.concurrentCount !== undefined && event.concurrentCount !== null && concurrentCount === null) {
    return { error: 'CREATOR_ANALYTICS_PRESENCE_INVALID' }
  }
  if (event.valueCoins !== undefined && event.valueCoins !== null && valueCoins === null) {
    return { error: 'CREATOR_ANALYTICS_GIFT_VALUE_INVALID' }
  }
  if (eventType !== 'live_presence' && !actorId) return { error: 'CREATOR_ANALYTICS_ACTOR_REQUIRED' }
  if (eventType === 'video_watch' && (watchMs === null || durationMs === null)) {
    return { error: 'CREATOR_ANALYTICS_WATCH_INVALID' }
  }
  if (eventType === 'live_presence' && concurrentCount === null) {
    return { error: 'CREATOR_ANALYTICS_PRESENCE_INVALID' }
  }
  if (eventType === 'live_gift' && (valueCoins === null || valueCoins <= 0)) {
    return { error: 'CREATOR_ANALYTICS_GIFT_VALUE_INVALID' }
  }

  const contentScope = `${contentType}:${contentId}`
  return {
    value: {
      idempotencyHash: hmac(hashSecret, `${contentScope}:idempotency:${eventType}`, idempotencyKey),
      contentType,
      contentId,
      eventType,
      actorHash: actorId ? hmac(hashSecret, `${contentScope}:actor`, actorId) : null,
      sessionHash: sessionId ? hmac(hashSecret, `${contentScope}:session`, sessionId) : null,
      countryCode,
      watchMs,
      durationMs,
      concurrentCount,
      valueCoins,
      occurredAt,
    },
  }
}

async function resolveContentOwner(pool, contentType, contentId) {
  const statement = contentType === 'video'
    ? `SELECT user_id AS owner_id
       FROM fallback_videos
       WHERE lower(id::text) = lower($1)
       LIMIT 1`
    : `SELECT host_user_id AS owner_id
       FROM fallback_live_streams
       WHERE lower(id::text) = lower($1)
       LIMIT 1`
  const { rows } = await pool.query(statement, [contentId])
  return normalizeId(rows[0]?.owner_id)
}

// This is intentionally an internal API, not an HTTP endpoint. Call it only
// after the canonical server operation (view, follow, gift, etc.) succeeds.
// The owner is resolved from canonical tables and actor/session identifiers are
// HMAC-pseudonymized before the INSERT parameters are constructed.
async function recordCreatorAnalyticsEvent(pool, event, options = {}) {
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')
  const config = options.config || creatorAnalyticsConfig()
  if (!config.enabled) return { accepted: false, duplicate: false, reason: 'FEATURE_DISABLED' }

  const normalized = normalizeServerEvent(event, {
    hashSecret: options.hashSecret || config.hashSecret,
    now: options.now?.() ?? Date.now(),
  })
  if (normalized.error) return { accepted: false, duplicate: false, reason: normalized.error }

  const value = normalized.value
  const ownerId = await (options.resolveOwner || resolveContentOwner)(pool, value.contentType, value.contentId)
  if (!ownerId) return { accepted: false, duplicate: false, reason: 'CONTENT_NOT_FOUND' }

  const { rows } = await pool.query(
    `INSERT INTO fallback_creator_analytics_events(
       idempotency_hash, content_type, content_id, event_type,
       actor_hash, session_hash, country_code, watch_ms, duration_ms,
       concurrent_count, value_coins, occurred_at
     ) VALUES (
       $1, $2, $3::uuid, $4, $5, $6, $7, $8, $9, $10, $11, $12::timestamptz
     )
     ON CONFLICT (idempotency_hash) DO NOTHING
     RETURNING id::text`,
    [
      value.idempotencyHash,
      value.contentType,
      value.contentId,
      value.eventType,
      value.actorHash,
      value.sessionHash,
      value.countryCode,
      value.watchMs,
      value.durationMs,
      value.concurrentCount,
      value.valueCoins,
      value.occurredAt,
    ],
  )
  const accepted = Boolean(rows[0]?.id)
  return { accepted, duplicate: !accepted, reason: accepted ? null : 'DUPLICATE' }
}

function normalizeReportRange(query = {}, now = Date.now()) {
  const toTimestamp = query.to ? Date.parse(query.to) : now
  const fromTimestamp = query.from ? Date.parse(query.from) : toTimestamp - 30 * 24 * 60 * 60 * 1000
  if (!Number.isFinite(fromTimestamp) || !Number.isFinite(toTimestamp)) return null
  if (fromTimestamp >= toTimestamp || toTimestamp - fromTimestamp > MAX_RANGE_DAYS * 24 * 60 * 60 * 1000) return null
  if (toTimestamp > now + 5 * 60 * 1000) return null
  return { from: new Date(fromTimestamp).toISOString(), to: new Date(toTimestamp).toISOString() }
}

function numberOf(value) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

function nullableNumber(value) {
  if (value === null || value === undefined) return null
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

function dividedNullable(value, divisor) {
  const numeric = nullableNumber(value)
  return numeric === null ? null : numeric / divisor
}

function isoValue(value) {
  if (!value) return null
  const timestamp = value instanceof Date ? value.getTime() : Date.parse(value)
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : null
}

function maxMetricRows(primary = {}, canonical = {}, keys = []) {
  const merged = { ...primary }
  for (const key of keys) merged[key] = Math.max(numberOf(primary[key]), numberOf(canonical[key]))
  return merged
}

function videoReportBody({ contentId, range, minimumCohort, summary = {}, countries = [], timeline = [] }) {
  const uniqueViewers = numberOf(summary.unique_viewers)
  const cohortEligible = uniqueViewers >= minimumCohort
  return {
    version: 1,
    contentType: 'video',
    contentId,
    range,
    totals: {
      views: numberOf(summary.views),
      uniqueViewers: cohortEligible ? uniqueViewers : null,
      watchMinutes: cohortEligible ? numberOf(summary.watch_ms) / 60_000 : null,
      averageWatchSeconds: cohortEligible ? dividedNullable(summary.average_watch_ms, 1000) : null,
      retention: {
        percent25: cohortEligible ? nullableNumber(summary.retention_25) : null,
        percent50: cohortEligible ? nullableNumber(summary.retention_50) : null,
        percent75: cohortEligible ? nullableNumber(summary.retention_75) : null,
        percent100: cohortEligible ? nullableNumber(summary.retention_100) : null,
      },
      interactions: {
        likes: numberOf(summary.likes),
        comments: numberOf(summary.comments),
        shares: numberOf(summary.shares),
        saves: numberOf(summary.saves),
        follows: numberOf(summary.follows),
      },
    },
    countries: countries.map((row) => ({
      countryCode: row.country_code,
      uniqueViewers: numberOf(row.unique_viewers),
      views: numberOf(row.views),
    })),
    timeline: timeline.map((row) => ({
      bucket: isoValue(row.bucket),
      views: numberOf(row.views),
      uniqueViewers: numberOf(row.unique_viewers),
      interactions: numberOf(row.interactions),
    })).filter((row) => row.bucket),
    privacy: {
      minimumCohort,
      detailedMetricsAvailable: cohortEligible,
      rawViewerIdsReturned: false,
      smallCountriesSuppressed: true,
      smallTimelineBucketsSuppressed: true,
    },
  }
}

function liveReportBody({ contentId, range, minimumCohort, summary = {}, donors = [], curve = [] }) {
  const uniqueViewers = numberOf(summary.unique_viewers)
  const viewerCohortEligible = uniqueViewers >= minimumCohort
  const donorCohort = donors.length > 0 ? numberOf(donors[0].donor_cohort) : 0
  const donorCohortEligible = donorCohort >= minimumCohort
  return {
    version: 1,
    contentType: 'live',
    contentId,
    range,
    totals: {
      peakConcurrent: numberOf(summary.peak_concurrent),
      uniqueViewers: viewerCohortEligible ? uniqueViewers : null,
      averageConcurrent: viewerCohortEligible ? nullableNumber(summary.average_concurrent) : null,
      viewerMinutes: viewerCohortEligible ? numberOf(summary.viewer_watch_ms) / 60_000 : null,
      follows: numberOf(summary.follows),
      comments: numberOf(summary.comments),
      likes: numberOf(summary.likes),
      gifts: numberOf(summary.gifts),
      giftValueCoins: numberOf(summary.gift_value_coins),
    },
    topDonors: donorCohortEligible
      ? donors.map((row, index) => ({
        rank: index + 1,
        gifts: numberOf(row.gifts),
        valueCoins: numberOf(row.value_coins),
      }))
      : [],
    viewerCurve: viewerCohortEligible
      ? curve.map((row) => ({
        bucket: isoValue(row.bucket),
        concurrent: numberOf(row.concurrent),
      })).filter((row) => row.bucket)
      : [],
    privacy: {
      minimumCohort,
      detailedMetricsAvailable: viewerCohortEligible,
      topDonorsAvailable: donorCohortEligible,
      rawViewerIdsReturned: false,
      rawDonorIdsReturned: false,
      smallViewerBucketsSuppressed: true,
    },
  }
}

async function loadVideoAnalytics(pool, { contentId, range, minimumCohort }) {
  const parameters = [contentId, range.from, range.to, minimumCohort]
  const [summaryResult, canonicalResult, countriesResult, timelineResult] = await Promise.all([
    pool.query(
      `/* creator_analytics:video_summary */
       SELECT
         COUNT(*) FILTER (WHERE event_type = 'video_view')::bigint AS views,
         COUNT(DISTINCT actor_hash) FILTER (WHERE event_type IN ('video_view', 'video_watch'))::bigint AS unique_viewers,
         COALESCE(SUM(watch_ms) FILTER (WHERE event_type = 'video_watch'), 0)::bigint AS watch_ms,
         AVG(watch_ms) FILTER (WHERE event_type = 'video_watch') AS average_watch_ms,
         ROUND(100.0 * COUNT(*) FILTER (WHERE event_type = 'video_watch' AND watch_ms * 100 >= duration_ms * 25)
           / NULLIF(COUNT(*) FILTER (WHERE event_type = 'video_watch'), 0), 2) AS retention_25,
         ROUND(100.0 * COUNT(*) FILTER (WHERE event_type = 'video_watch' AND watch_ms * 100 >= duration_ms * 50)
           / NULLIF(COUNT(*) FILTER (WHERE event_type = 'video_watch'), 0), 2) AS retention_50,
         ROUND(100.0 * COUNT(*) FILTER (WHERE event_type = 'video_watch' AND watch_ms * 100 >= duration_ms * 75)
           / NULLIF(COUNT(*) FILTER (WHERE event_type = 'video_watch'), 0), 2) AS retention_75,
         ROUND(100.0 * COUNT(*) FILTER (WHERE event_type = 'video_watch' AND watch_ms >= duration_ms)
           / NULLIF(COUNT(*) FILTER (WHERE event_type = 'video_watch'), 0), 2) AS retention_100,
         COUNT(*) FILTER (WHERE event_type = 'video_like')::bigint AS likes,
         COUNT(*) FILTER (WHERE event_type = 'video_comment')::bigint AS comments,
         COUNT(*) FILTER (WHERE event_type = 'video_share')::bigint AS shares,
         COUNT(*) FILTER (WHERE event_type = 'video_save')::bigint AS saves,
         COUNT(*) FILTER (WHERE event_type = 'video_follow')::bigint AS follows
       FROM fallback_creator_analytics_events
       WHERE content_type = 'video' AND content_id = $1::uuid
         AND occurred_at >= $2::timestamptz AND occurred_at < $3::timestamptz`,
      parameters.slice(0, 3),
    ),
    pool.query(
      `/* creator_analytics:video_canonical_summary */
       SELECT
         (SELECT COUNT(*) FROM fallback_video_views
           WHERE lower(video_id) = lower($1)
             AND viewed_at >= $2::timestamptz AND viewed_at < $3::timestamptz)::bigint AS views,
         (SELECT COUNT(DISTINCT lower(user_id)) FROM fallback_video_views
           WHERE lower(video_id) = lower($1)
             AND viewed_at >= $2::timestamptz AND viewed_at < $3::timestamptz)::bigint AS unique_viewers,
         (SELECT COUNT(*) FROM fallback_video_likes
           WHERE lower(video_id) = lower($1)
             AND created_at >= $2::timestamptz AND created_at < $3::timestamptz)::bigint AS likes,
         (SELECT COUNT(*) FROM fallback_video_comments
           WHERE lower(video_id) = lower($1) AND is_deleted = FALSE
             AND created_at >= $2::timestamptz AND created_at < $3::timestamptz)::bigint AS comments,
         (SELECT COUNT(*) FROM fallback_shares
           WHERE content_type = 'video' AND lower(post_id) = lower($1)
             AND created_at >= $2::timestamptz AND created_at < $3::timestamptz)::bigint AS shares,
         (SELECT COUNT(*) FROM fallback_video_favorites
           WHERE lower(video_id) = lower($1)
             AND created_at >= $2::timestamptz AND created_at < $3::timestamptz)::bigint AS saves`,
      parameters.slice(0, 3),
    ),
    pool.query(
      `/* creator_analytics:video_countries */
       SELECT country_code,
              COUNT(DISTINCT actor_hash)::bigint AS unique_viewers,
              COUNT(*) FILTER (WHERE event_type = 'video_view')::bigint AS views
       FROM fallback_creator_analytics_events
       WHERE content_type = 'video' AND content_id = $1::uuid
         AND occurred_at >= $2::timestamptz AND occurred_at < $3::timestamptz
         AND country_code IS NOT NULL AND actor_hash IS NOT NULL
       GROUP BY country_code
       HAVING COUNT(DISTINCT actor_hash) >= $4
       ORDER BY unique_viewers DESC, country_code ASC
       LIMIT 100`,
      parameters,
    ),
    pool.query(
      `/* creator_analytics:video_timeline */
       SELECT date_trunc('hour', occurred_at) AS bucket,
              COUNT(*) FILTER (WHERE event_type = 'video_view')::bigint AS views,
              COUNT(DISTINCT actor_hash)::bigint AS unique_viewers,
              COUNT(*) FILTER (WHERE event_type IN ('video_like', 'video_comment', 'video_share', 'video_save', 'video_follow'))::bigint AS interactions
       FROM fallback_creator_analytics_events
       WHERE content_type = 'video' AND content_id = $1::uuid
         AND occurred_at >= $2::timestamptz AND occurred_at < $3::timestamptz
         AND actor_hash IS NOT NULL
       GROUP BY date_trunc('hour', occurred_at)
       HAVING COUNT(DISTINCT actor_hash) >= $4
       ORDER BY bucket ASC
       LIMIT 8784`,
      parameters,
    ),
  ])
  const mergedSummary = maxMetricRows(
    summaryResult.rows[0] || {},
    canonicalResult.rows[0] || {},
    ['views', 'unique_viewers', 'likes', 'comments', 'shares', 'saves'],
  )
  return videoReportBody({
    contentId,
    range,
    minimumCohort,
    summary: mergedSummary,
    countries: countriesResult.rows,
    timeline: timelineResult.rows,
  })
}

async function loadLiveAnalytics(pool, { contentId, range, minimumCohort }) {
  const parameters = [contentId, range.from, range.to, minimumCohort]
  const [summaryResult, canonicalResult, donorsResult, canonicalDonorsResult, curveResult] = await Promise.all([
    pool.query(
      `/* creator_analytics:live_summary */
       SELECT
         COALESCE(MAX(concurrent_count) FILTER (WHERE event_type = 'live_presence'), 0)::bigint AS peak_concurrent,
         COUNT(DISTINCT actor_hash) FILTER (WHERE event_type = 'live_join')::bigint AS unique_viewers,
         AVG(concurrent_count) FILTER (WHERE event_type = 'live_presence') AS average_concurrent,
         COALESCE(SUM(watch_ms) FILTER (WHERE event_type = 'live_leave'), 0)::bigint AS viewer_watch_ms,
         COUNT(*) FILTER (WHERE event_type = 'live_follow')::bigint AS follows,
         COUNT(*) FILTER (WHERE event_type = 'live_comment')::bigint AS comments,
         COUNT(*) FILTER (WHERE event_type = 'live_like')::bigint AS likes,
         COUNT(*) FILTER (WHERE event_type = 'live_gift')::bigint AS gifts,
         COALESCE(SUM(value_coins) FILTER (WHERE event_type = 'live_gift'), 0)::bigint AS gift_value_coins
       FROM fallback_creator_analytics_events
       WHERE content_type = 'live' AND content_id = $1::uuid
         AND occurred_at >= $2::timestamptz AND occurred_at < $3::timestamptz`,
      parameters.slice(0, 3),
    ),
    pool.query(
      `/* creator_analytics:live_canonical_summary */
       SELECT
         COALESCE(stream.peak_viewer_count, 0)::bigint AS peak_concurrent,
         COALESCE(stream.total_comments, 0)::bigint AS comments,
         COALESCE(stream.total_reactions, 0)::bigint AS likes,
         COALESCE(participants.unique_viewers, 0)::bigint AS unique_viewers,
         COALESCE(participants.viewer_watch_ms, 0)::bigint AS viewer_watch_ms,
         COALESCE(gifts.gifts, 0)::bigint AS gifts,
         COALESCE(gifts.gift_value_coins, 0)::bigint AS gift_value_coins
       FROM fallback_live_streams stream
       LEFT JOIN LATERAL (
         SELECT COUNT(DISTINCT lower(user_id)) FILTER (WHERE role <> 'host')::bigint AS unique_viewers,
                COALESCE(SUM(
                  EXTRACT(EPOCH FROM (COALESCE(left_at, updated_at, NOW()) - COALESCE(joined_at, created_at))) * 1000
                ) FILTER (WHERE role <> 'host' AND joined_at IS NOT NULL), 0)::bigint AS viewer_watch_ms
           FROM fallback_live_participants
          WHERE live_id = stream.id
            AND COALESCE(joined_at, created_at) >= $2::timestamptz
            AND COALESCE(joined_at, created_at) < $3::timestamptz
       ) participants ON TRUE
       LEFT JOIN LATERAL (
         SELECT COUNT(*)::bigint AS gifts,
                COALESCE(SUM(
                  CASE WHEN COALESCE(row->>'amount_diamonds', '') ~ '^[0-9]+$'
                    THEN (row->>'amount_diamonds')::bigint ELSE 0 END
                ), 0)::bigint AS gift_value_coins
           FROM fallback_compat_rows
          WHERE table_name = 'gift_transactions'
            AND lower(COALESCE(row->>'live_stream_id', '')) = lower($1)
            AND lower(COALESCE(row->>'status', '')) IN ('committed', 'completed', 'success')
            AND created_at >= $2::timestamptz
            AND created_at < $3::timestamptz
       ) gifts ON TRUE
       WHERE lower(stream.id::text) = lower($1)
       LIMIT 1`,
      parameters.slice(0, 3),
    ),
    pool.query(
      `/* creator_analytics:live_top_donors */
       WITH donors AS (
         SELECT actor_hash,
                COUNT(*)::bigint AS gifts,
                COALESCE(SUM(value_coins), 0)::bigint AS value_coins
         FROM fallback_creator_analytics_events
         WHERE content_type = 'live' AND content_id = $1::uuid
           AND occurred_at >= $2::timestamptz AND occurred_at < $3::timestamptz
           AND event_type = 'live_gift' AND actor_hash IS NOT NULL
         GROUP BY actor_hash
       )
       SELECT actor_hash, gifts, value_coins, COUNT(*) OVER ()::bigint AS donor_cohort
       FROM donors
       ORDER BY value_coins DESC, gifts DESC, actor_hash ASC
       LIMIT 10`,
      parameters.slice(0, 3),
    ),
    pool.query(
      `/* creator_analytics:live_canonical_top_donors */
       WITH donors AS (
         SELECT lower(row->>'sender_id') AS actor_hash,
                COUNT(*)::bigint AS gifts,
                COALESCE(SUM(
                  CASE WHEN COALESCE(row->>'amount_diamonds', '') ~ '^[0-9]+$'
                    THEN (row->>'amount_diamonds')::bigint ELSE 0 END
                ), 0)::bigint AS value_coins
           FROM fallback_compat_rows
          WHERE table_name = 'gift_transactions'
            AND lower(COALESCE(row->>'live_stream_id', '')) = lower($1)
            AND lower(COALESCE(row->>'status', '')) IN ('committed', 'completed', 'success')
            AND COALESCE(row->>'sender_id', '') <> ''
            AND created_at >= $2::timestamptz
            AND created_at < $3::timestamptz
          GROUP BY lower(row->>'sender_id')
       )
       SELECT actor_hash, gifts, value_coins, COUNT(*) OVER ()::bigint AS donor_cohort
         FROM donors
        ORDER BY value_coins DESC, gifts DESC, actor_hash ASC
        LIMIT 10`,
      parameters.slice(0, 3),
    ),
    pool.query(
      `/* creator_analytics:live_viewer_curve */
       SELECT date_trunc('minute', occurred_at) AS bucket,
              MAX(concurrent_count)::bigint AS concurrent
       FROM fallback_creator_analytics_events
       WHERE content_type = 'live' AND content_id = $1::uuid
         AND occurred_at >= $2::timestamptz AND occurred_at < $3::timestamptz
         AND event_type = 'live_presence'
       GROUP BY date_trunc('minute', occurred_at)
       HAVING MAX(concurrent_count) >= $4
       ORDER BY bucket ASC
       LIMIT 10080`,
      parameters,
    ),
  ])
  const mergedSummary = maxMetricRows(
    summaryResult.rows[0] || {},
    canonicalResult.rows[0] || {},
    ['peak_concurrent', 'unique_viewers', 'viewer_watch_ms', 'comments', 'likes', 'gifts', 'gift_value_coins'],
  )
  const eventDonors = donorsResult.rows || []
  const canonicalDonors = canonicalDonorsResult.rows || []
  const donors = eventDonors.length >= canonicalDonors.length ? eventDonors : canonicalDonors
  return liveReportBody({
    contentId,
    range,
    minimumCohort,
    summary: mergedSummary,
    donors,
    curve: curveResult.rows,
  })
}

function identityOwns(identity, ownerId) {
  const normalizedOwner = normalizeId(ownerId)
  if (!normalizedOwner) return false
  const values = identity?.ids instanceof Set
    ? Array.from(identity.ids)
    : Array.isArray(identity?.ids)
      ? identity.ids
      : []
  if (identity?.displayUserId) values.push(identity.displayUserId)
  return values.some((value) => normalizeId(value) === normalizedOwner)
}

function identityValues(identity) {
  const values = identity?.ids instanceof Set
    ? Array.from(identity.ids)
    : Array.isArray(identity?.ids)
      ? [...identity.ids]
      : []
  if (identity?.displayUserId) values.push(identity.displayUserId)
  return Array.from(new Set(values.map((value) => normalizeId(value)).filter(Boolean)))
}

async function loadCreatorAnalyticsContent(pool, identity, { limit = 100 } = {}) {
  const ownerIds = identityValues(identity)
  if (ownerIds.length === 0) return { version: 1, items: [] }
  const safeLimit = boundedInteger(limit, 1, 200, 100)
  const [videosResult, livesResult] = await Promise.all([
    pool.query(
      `/* creator_analytics:owned_videos */
       SELECT id::text AS content_id, title, duration_seconds,
              created_at, updated_at, is_public
       FROM fallback_videos
       WHERE lower(user_id) = ANY($1::text[])
       ORDER BY created_at DESC, id DESC
       LIMIT $2`,
      [ownerIds, safeLimit],
    ),
    pool.query(
      `/* creator_analytics:owned_lives */
       SELECT id::text AS content_id, title, source, is_active,
              started_at, ended_at, created_at, updated_at
       FROM fallback_live_streams
       WHERE lower(host_user_id) = ANY($1::text[])
       ORDER BY COALESCE(started_at, created_at) DESC, id DESC
       LIMIT $2`,
      [ownerIds, safeLimit],
    ),
  ])
  const videos = videosResult.rows.map((row) => ({
    contentType: 'video',
    contentId: row.content_id,
    title: String(row.title || '').trim(),
    status: row.is_public ? 'published' : 'private',
    occurredAt: isoValue(row.created_at),
    durationSeconds: Math.max(0, numberOf(row.duration_seconds)),
  }))
  const lives = livesResult.rows.map((row) => ({
    contentType: 'live',
    contentId: row.content_id,
    title: String(row.title || '').trim(),
    status: row.is_active ? 'live' : row.ended_at ? 'ended' : 'draft',
    occurredAt: isoValue(row.started_at || row.created_at),
    source: String(row.source || '').trim().toLowerCase(),
  }))
  return {
    version: 1,
    items: [...videos, ...lives]
      .filter((item) => item.contentId && item.occurredAt)
      .sort((left, right) => String(right.occurredAt).localeCompare(String(left.occurredAt)))
      .slice(0, safeLimit),
  }
}

function registerCreatorAnalyticsRoutes(app, {
  pool,
  authRequired,
  getCurrentUser,
  userIdentity,
  config = creatorAnalyticsConfig,
  resolveOwner = resolveContentOwner,
  loadVideo = loadVideoAnalytics,
  loadLive = loadLiveAnalytics,
  loadContent = loadCreatorAnalyticsContent,
  now = () => Date.now(),
} = {}) {
  if (!app || typeof app.get !== 'function') throw new TypeError('app.get is required')
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')
  if (typeof authRequired !== 'function') throw new TypeError('authRequired is required')
  if (typeof getCurrentUser !== 'function') throw new TypeError('getCurrentUser is required')
  if (typeof userIdentity !== 'function') throw new TypeError('userIdentity is required')

  const handlerFor = (contentType, loader) => async (req, res, next) => {
    try {
      const settings = config()
      if (!settings.enabled) return res.status(404).json({ error: 'CREATOR_ANALYTICS_DISABLED' })
      const contentId = validUuid(req.params?.contentId)
      const range = normalizeReportRange(req.query, now())
      if (!contentId || !range) return res.status(400).json({ error: 'CREATOR_ANALYTICS_REQUEST_INVALID' })

      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const ownerId = await resolveOwner(pool, contentType, contentId)
      if (!ownerId) return res.status(404).json({ error: 'CONTENT_NOT_FOUND' })
      if (!identityOwns(identity, ownerId)) return res.status(403).json({ error: 'CREATOR_ANALYTICS_OWNER_REQUIRED' })

      res.setHeader?.('Cache-Control', 'private, no-store')
      return res.json(await loader(pool, {
        contentId,
        range,
        minimumCohort: boundedInteger(
          settings.minimumCohort,
          MIN_ALLOWED_COHORT,
          MAX_ALLOWED_COHORT,
          DEFAULT_MIN_COHORT,
        ),
      }))
    } catch (error) {
      next(error)
    }
  }

  app.get('/v1/data/creator/analytics/video/:contentId', authRequired, handlerFor('video', loadVideo))
  app.get('/v1/data/creator/analytics/live/:contentId', authRequired, handlerFor('live', loadLive))
  app.get('/v1/data/creator/analytics/content', authRequired, async (req, res, next) => {
    try {
      const settings = config()
      if (!settings.enabled) return res.status(404).json({ error: 'CREATOR_ANALYTICS_DISABLED' })
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      res.setHeader?.('Cache-Control', 'private, no-store')
      return res.json(await loadContent(pool, userIdentity(user), { limit: req.query?.limit }))
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  CREATOR_ANALYTICS_FLAG,
  CREATOR_ANALYTICS_HASH_SECRET,
  CREATOR_ANALYTICS_MIN_COHORT,
  DEFAULT_MIN_COHORT,
  EVENT_TYPES,
  creatorAnalyticsConfig,
  identityOwns,
  identityValues,
  liveReportBody,
  loadCreatorAnalyticsContent,
  loadLiveAnalytics,
  loadVideoAnalytics,
  normalizeReportRange,
  normalizeServerEvent,
  maxMetricRows,
  recordCreatorAnalyticsEvent,
  registerCreatorAnalyticsRoutes,
  resolveContentOwner,
  videoReportBody,
}
