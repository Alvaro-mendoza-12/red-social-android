'use strict'

const {
  algorithmFeatureFlags,
  upsertTrustedContentClassification,
} = require('./algorithm-routes')

const CLASSIFIER_VERSION = 'novvin-server-rules-v1'
const BACKFILL_FLAG = 'USER_ALGORITHM_CLASSIFICATION_BACKFILL_ENABLED'
const MAX_BACKFILL_ROWS = 10_000

// The classifier deliberately consumes only public content fields. It never
// reads private messages, precise location, age, gender, contacts or client
// supplied classification/safety values.
const TOPIC_RULES = Object.freeze([
  ['technology', 'programming', /\b(android|app|codigo|code|coding|java|javascript|kotlin|programacion|software|tecnologia|typescript)\b/i],
  ['gaming', '', /\b(gamer|gaming|juego|juegos|minecraft|playstation|xbox|nintendo|videojuego)\b/i],
  ['music', '', /\b(cancion|cantar|concierto|musica|music|piano|rap|rock|salsa)\b/i],
  ['sports', '', /\b(basket|deporte|futbol|football|gym|running|tenis|voleibol)\b/i],
  ['food', '', /\b(cocina|comida|food|receta|restaurant|restaurante)\b/i],
  ['travel', '', /\b(paisaje|turismo|travel|viaje|viajes)\b/i],
  ['education', '', /\b(aprende|aprender|curso|educacion|explicacion|tutorial)\b/i],
  ['art', '', /\b(arte|dibujo|ilustracion|pintura)\b/i],
  ['delfines', 'dolphins', /\b(delfin|delfines|dolphin|dolphins)\b/i],
  ['animals', '', /\b(animal|animales|delfin|delfines|dolphin|dolphins|gato|mascota|perro)\b/i],
  ['comedy', '', /\b(comedia|humor|meme|risa)\b/i],
])

function envFlag(name) {
  return /^(1|true|yes|on)$/i.test(String(process.env[name] || '').trim())
}

function cleanText(value, maxLength = 5_000) {
  return typeof value === 'string' ? value.trim().slice(0, maxLength) : ''
}

function normalizedId(value) {
  const text = cleanText(value, 120).toLowerCase()
  return /^[a-z0-9][a-z0-9:_-]{0,119}$/.test(text) ||
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : ''
}

function normalizedSearchText(value) {
  return cleanText(value)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
}

function detectLanguage(text) {
  const value = ` ${normalizedSearchText(text)} `
  if (/\b(el|ella|para|pero|que|una|video|con|como|este|esta)\b/.test(value)) return 'es'
  if (/\b(the|this|that|with|from|your|video|and|for)\b/.test(value)) return 'en'
  if (/\b(uma|voce|com|para|video|esta|esse|isso)\b/.test(value)) return 'pt'
  return null
}

function contentText(kind, row) {
  if (kind === 'post') return cleanText(row?.content)
  return [cleanText(row?.title, 500), cleanText(row?.description)].filter(Boolean).join(' ')
}

function isPublicContent(kind, row) {
  if (kind === 'post') return cleanText(row?.visibility || 'public', 20).toLowerCase() === 'public'
  return row?.is_public !== false && row?.isPublic !== false && String(row?.is_public ?? row?.isPublic ?? 'true').toLowerCase() !== 'false'
}

function inferFormat(kind, row) {
  if (kind === 'video') {
    const duration = Number(row?.duration_seconds ?? row?.durationSeconds ?? row?.duration ?? 0)
    return Number.isFinite(duration) && duration > 0 && duration <= 60 ? 'short-video' : 'video'
  }
  const mediaType = cleanText(row?.media_type ?? row?.mediaType, 80).toLowerCase()
  if (mediaType.includes('video')) return 'video-post'
  if (mediaType.includes('image') || mediaType.includes('photo')) return 'image-post'
  return 'text-post'
}

function deriveTrustedContentClassification(kind, row) {
  if (!['post', 'video'].includes(kind) || !row || typeof row !== 'object' || Array.isArray(row)) {
    return { error: 'TRUSTED_CONTENT_INVALID' }
  }
  const contentId = normalizedId(row.id)
  const creatorId = normalizedId(row.user_id ?? row.userId)
  if (!contentId || !creatorId) return { error: 'TRUSTED_CONTENT_IDENTITY_INVALID' }
  if (!isPublicContent(kind, row)) {
    return { excluded: 'CONTENT_NOT_PUBLIC', contentKind: kind, contentId }
  }

  const text = contentText(kind, row)
  const searchable = normalizedSearchText(text)
  const topics = []
  for (const [topicKey, subtopicKey, matcher] of TOPIC_RULES) {
    if (matcher.test(searchable)) {
      topics.push({ topicKey, subtopicKey: subtopicKey || null, confidence: 0.86 })
    }
    if (topics.length >= 5) break
  }
  if (topics.length === 0) {
    topics.push({ topicKey: kind === 'video' ? 'video-general' : 'social-general', confidence: 0.55 })
  }

  return {
    value: {
      contentKind: kind,
      contentId,
      creatorId,
      languageCode: detectLanguage(text),
      formatKey: inferFormat(kind, row),
      // sound_id is assigned and canonicalized by the trusted video route;
      // unlike creator tags, it is safe to use as an audio affinity key.
      audioKey: kind === 'video' ? normalizedId(row?.sound_id ?? row?.soundId) || null : null,
      communityId: null,
      safetyState: 'allowed',
      classifierVersion: CLASSIFIER_VERSION,
      topics,
    },
  }
}

async function deleteTrustedContentClassification(client, kind, contentId) {
  const cleanId = normalizedId(contentId)
  if (!['post', 'video'].includes(kind) || !cleanId) return { error: 'TRUSTED_CONTENT_IDENTITY_INVALID' }
  await client.query(
    `DELETE FROM fallback_algorithm_content_classifications
      WHERE content_kind = $1 AND lower(content_id) = $2`,
    [kind, cleanId],
  )
  return { deleted: true }
}

async function syncTrustedContentClassification(client, kind, row) {
  const flags = algorithmFeatureFlags()
  if (!flags.core || !flags.classification) return { skipped: 'USER_ALGORITHM_CLASSIFICATION_DISABLED' }

  const derived = deriveTrustedContentClassification(kind, row)
  const contentId = normalizedId(row?.id)
  if (derived.excluded) {
    if (contentId) await deleteTrustedContentClassification(client, kind, contentId)
    return derived
  }
  if (derived.error) {
    if (contentId) await deleteTrustedContentClassification(client, kind, contentId)
    return derived
  }

  const result = await upsertTrustedContentClassification(client, derived.value)
  if (result.error && contentId) {
    await deleteTrustedContentClassification(client, kind, contentId)
  }
  return result
}

async function ingestTrustedContentClassification(client, kind, row) {
  try {
    return await syncTrustedContentClassification(client, kind, row)
  } catch {
    const contentId = normalizedId(row?.id)
    if (contentId) {
      try {
        await deleteTrustedContentClassification(client, kind, contentId)
      } catch {
        // A database outage must not be turned into a data leak through logs.
      }
    }
    return { error: 'TRUSTED_CLASSIFICATION_INGESTION_FAILED' }
  }
}

function existingColumnSet(rows) {
  return new Set((rows || []).map((row) => String(row.column_name || '').trim()).filter(Boolean))
}

function pickSqlColumn(columns, candidates, fallbackSql) {
  const found = candidates.find((name) => columns.has(name))
  return found ? `source.${found}` : fallbackSql
}

async function importedContentRows(pool, kind, limit) {
  const tableName = kind === 'post' ? 'posts' : 'videos'
  const columnsResult = await pool.query(
    `SELECT column_name
       FROM information_schema.columns
      WHERE table_schema = 'supabase_import' AND table_name = $1`,
    [tableName],
  )
  const columns = existingColumnSet(columnsResult.rows)
  if (!columns.has('id')) return []

  const userId = pickSqlColumn(columns, ['user_id', 'creator_id', 'owner_id'], `''::text`)
  if (kind === 'post') {
    const content = pickSqlColumn(columns, ['content', 'caption', 'description'], `''::text`)
    const mediaType = pickSqlColumn(columns, ['media_type', 'content_type'], `NULL::text`)
    const visibility = pickSqlColumn(columns, ['visibility'], `'public'::text`)
    const result = await pool.query(
      `SELECT source.id::text AS id, ${userId}::text AS user_id,
              ${content}::text AS content, ${mediaType}::text AS media_type,
              ${visibility}::text AS visibility
         FROM supabase_import.posts source
        ORDER BY source.id::text
        LIMIT $1`,
      [limit],
    )
    return result.rows
  }

  const title = pickSqlColumn(columns, ['title', 'name'], `''::text`)
  const description = pickSqlColumn(columns, ['description', 'caption'], `''::text`)
  const duration = pickSqlColumn(columns, ['duration_seconds', 'duration'], `'0'::text`)
  const isPublic = pickSqlColumn(columns, ['is_public'], `'true'::text`)
  const result = await pool.query(
    `SELECT source.id::text AS id, ${userId}::text AS user_id,
            ${title}::text AS title, ${description}::text AS description,
            ${duration}::text AS duration_seconds,
            CASE WHEN lower(COALESCE(${isPublic}::text, 'true')) IN ('true', '1', 'yes') THEN TRUE ELSE FALSE END AS is_public
       FROM supabase_import.videos source
      ORDER BY source.id::text
      LIMIT $1`,
    [limit],
  )
  return result.rows
}

async function backfillTrustedPublicContentClassifications(pool, options = {}) {
  const flags = algorithmFeatureFlags()
  if (!flags.core || !flags.classification || !envFlag(BACKFILL_FLAG)) {
    return { skipped: 'USER_ALGORITHM_CLASSIFICATION_BACKFILL_DISABLED', classified: 0, excluded: 0, failed: 0 }
  }
  const limit = Math.max(1, Math.min(Number(options.limit) || 2_000, MAX_BACKFILL_ROWS))
  const sources = [
    ['post', await pool.query(
      `SELECT id::text, user_id, content, media_type, visibility
         FROM fallback_posts ORDER BY id::text LIMIT $1`,
      [limit],
    ).then((result) => result.rows)],
    ['video', await pool.query(
      `SELECT id::text, user_id, title, description, duration_seconds, is_public
         FROM fallback_videos ORDER BY id::text LIMIT $1`,
      [limit],
    ).then((result) => result.rows)],
    ['post', await importedContentRows(pool, 'post', limit)],
    ['video', await importedContentRows(pool, 'video', limit)],
  ]

  const seen = new Set()
  const summary = { classified: 0, excluded: 0, failed: 0 }
  for (const [kind, rows] of sources) {
    for (const row of rows) {
      const key = `${kind}:${normalizedId(row.id)}`
      if (!row.id || seen.has(key)) continue
      seen.add(key)
      try {
        const result = await syncTrustedContentClassification(pool, kind, row)
        if (result.id) summary.classified += 1
        else if (result.excluded) summary.excluded += 1
        else summary.failed += 1
      } catch {
        summary.failed += 1
        try {
          await deleteTrustedContentClassification(pool, kind, row.id)
        } catch {
          // Both operations failed. No content/classification data is logged.
        }
      }
    }
  }
  return summary
}

module.exports = {
  BACKFILL_FLAG,
  CLASSIFIER_VERSION,
  backfillTrustedPublicContentClassifications,
  deleteTrustedContentClassification,
  deriveTrustedContentClassification,
  ingestTrustedContentClassification,
  importedContentRows,
  syncTrustedContentClassification,
}
