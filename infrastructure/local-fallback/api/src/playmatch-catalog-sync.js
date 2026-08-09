const crypto = require('crypto')
const { gameCatalogProvider, slugify } = require('./game-catalog-providers')

function text(value, max = 500) {
  return String(value || '').trim().slice(0, max)
}

function safeJsonArray(value, max = 50) {
  return [...new Set((Array.isArray(value) ? value : []).map((entry) => text(entry, 100)).filter(Boolean))].slice(0, max)
}

function stableGameId(provider, externalProviderId, slug) {
  const prefix = text(provider, 24).toLowerCase().replace(/[^a-z0-9]+/g, '-') || 'catalog'
  const external = text(externalProviderId, 80).toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const readable = slugify(slug).slice(0, 72)
  return `${prefix}-${external || readable || crypto.randomUUID()}`.slice(0, 120)
}

async function findExistingGame(client, item) {
  const { rows } = await client.query(
    `SELECT *
       FROM fallback_game_catalog
      WHERE (lower(provider) = lower($1) AND lower(COALESCE(external_provider_id, '')) = lower($2))
         OR (provider = 'manual' AND lower(name) = lower($3))
      ORDER BY CASE WHEN lower(provider) = lower($1) THEN 0 ELSE 1 END
      LIMIT 1
      FOR UPDATE`,
    [item.provider, item.externalProviderId, item.name],
  )
  return rows[0] || null
}

async function upsertCatalogItem(client, raw) {
  const item = {
    provider: text(raw.provider, 40).toLowerCase(),
    externalProviderId: text(raw.externalProviderId, 100),
    name: text(raw.name, 160),
    slug: slugify(raw.slug || raw.name),
    shortName: text(raw.shortName || raw.name, 100),
    description: text(raw.description, 500),
    coverUrl: /^https:\/\//i.test(raw.coverUrl || '') ? raw.coverUrl : null,
    logoUrl: /^https:\/\//i.test(raw.logoUrl || '') ? raw.logoUrl : null,
    iconUrl: /^https:\/\//i.test(raw.iconUrl || '') ? raw.iconUrl : null,
    platforms: safeJsonArray(raw.platforms, 30),
    genres: safeJsonArray(raw.genres, 30),
    onlineSupported: raw.onlineSupported === true,
    multiplayerSupported: raw.multiplayerSupported === true,
    competitive: raw.competitive === true,
    rankedSupported: raw.rankedSupported === true,
    rolesSupported: raw.rolesSupported === true,
    crossPlay: raw.crossPlay === true,
    active: raw.active !== false,
    releaseDate: /^\d{4}-\d{2}-\d{2}$/.test(raw.releaseDate || '') ? raw.releaseDate : null,
    sourceUrl: /^https:\/\//i.test(raw.sourceUrl || '') ? raw.sourceUrl : null,
    coverLicense: text(raw.coverLicense, 120) || null,
    coverAttribution: text(raw.coverAttribution, 500) || null,
    coverSourceUrl: /^https:\/\//i.test(raw.coverSourceUrl || '') ? raw.coverSourceUrl : null,
    supportLevel: ['FULLY_SUPPORTED', 'CATALOG_SUPPORTED', 'UNSUPPORTED'].includes(raw.supportLevel)
      ? raw.supportLevel
      : 'CATALOG_SUPPORTED',
  }
  if (!item.provider || !item.externalProviderId || !item.name) return { status: 'skipped' }
  const existing = await findExistingGame(client, item)
  if (existing) {
    await client.query(
      `UPDATE fallback_game_catalog
          SET external_provider_id = CASE WHEN manual_override THEN external_provider_id ELSE $2 END,
              provider = CASE WHEN manual_override THEN provider ELSE $3 END,
              name = CASE WHEN manual_override THEN name ELSE $4 END,
              slug = CASE WHEN manual_override THEN slug ELSE $5 END,
              short_name = CASE WHEN manual_override THEN short_name ELSE $6 END,
              description = CASE WHEN manual_override THEN description ELSE $7 END,
              cover_url = CASE WHEN manual_override THEN cover_url ELSE COALESCE($8, cover_url) END,
              logo_url = CASE WHEN manual_override THEN logo_url ELSE COALESCE($9, logo_url) END,
              icon_url = CASE WHEN manual_override THEN icon_url ELSE COALESCE($10, icon_url) END,
              image_url = CASE WHEN manual_override THEN image_url ELSE COALESCE($9, $8, image_url) END,
              platforms = CASE WHEN manual_override THEN platforms ELSE $11::jsonb END,
              genres = CASE WHEN manual_override THEN genres ELSE $12::jsonb END,
              online_supported = CASE WHEN manual_override THEN online_supported ELSE $13 END,
              multiplayer_supported = CASE WHEN manual_override THEN multiplayer_supported ELSE $14 END,
              competitive = CASE WHEN support_level = 'FULLY_SUPPORTED' THEN competitive ELSE $15 END,
              ranked_supported = CASE WHEN support_level = 'FULLY_SUPPORTED' THEN ranked_supported ELSE $16 END,
              roles_supported = CASE WHEN support_level = 'FULLY_SUPPORTED' THEN roles_supported ELSE $17 END,
              cross_play = CASE WHEN manual_override THEN cross_play ELSE $18 END,
              active = CASE WHEN manual_override THEN active ELSE $19 END,
              release_date = CASE WHEN manual_override THEN release_date ELSE $20::date END,
              source_url = CASE WHEN manual_override THEN source_url ELSE $21 END,
              cover_license = CASE WHEN manual_override THEN cover_license ELSE $22 END,
              cover_attribution = CASE WHEN manual_override THEN cover_attribution ELSE $23 END,
              cover_source_url = CASE WHEN manual_override THEN cover_source_url ELSE $24 END,
              support_level = CASE WHEN support_level = 'FULLY_SUPPORTED' THEN support_level ELSE $25 END,
              provider_updated_at = NOW(),
              catalog_updated_at = NOW(),
              updated_at = NOW()
        WHERE id = $1`,
      [
        existing.id, item.externalProviderId, item.provider, item.name, item.slug, item.shortName,
        item.description, item.coverUrl, item.logoUrl, item.iconUrl, JSON.stringify(item.platforms),
        JSON.stringify(item.genres), item.onlineSupported, item.multiplayerSupported, item.competitive,
        item.rankedSupported, item.rolesSupported, item.crossPlay, item.active, item.releaseDate,
        item.sourceUrl, item.coverLicense, item.coverAttribution, item.coverSourceUrl, item.supportLevel,
      ],
    )
    return { status: 'updated', id: existing.id }
  }
  const id = stableGameId(item.provider, item.externalProviderId, item.slug)
  await client.query(
    `INSERT INTO fallback_game_catalog(
       id, external_provider_id, provider, name, slug, short_name, description,
       image_url, cover_url, logo_url, icon_url, platforms, genres,
       online_supported, multiplayer_supported, competitive, ranked_supported,
       roles_supported, cross_play, active, release_date, source_url,
       cover_license, cover_attribution, cover_source_url, support_level,
       provider_updated_at, catalog_updated_at
     ) VALUES (
       $1,$2,$3,$4,$5,$6,$7,$8,$8,$9,$10,$11::jsonb,$12::jsonb,
       $13,$14,$15,$16,$17,$18,$19,$20::date,$21,$22,$23,$24,$25,NOW(),NOW()
     )`,
    [
      id, item.externalProviderId, item.provider, item.name, item.slug, item.shortName,
      item.description, item.coverUrl || item.logoUrl, item.logoUrl, item.iconUrl,
      JSON.stringify(item.platforms), JSON.stringify(item.genres), item.onlineSupported,
      item.multiplayerSupported, item.competitive, item.rankedSupported, item.rolesSupported,
      item.crossPlay, item.active, item.releaseDate, item.sourceUrl, item.coverLicense,
      item.coverAttribution, item.coverSourceUrl, item.supportLevel,
    ],
  )
  return { status: 'inserted', id }
}

async function synchronizeGameCatalog(pool, options = {}) {
  const provider = options.providerInstance || gameCatalogProvider(options.provider || 'wikidata', options)
  const maxPages = Math.max(1, Math.min(Number(options.maxPages) || 1, 100))
  const pageSize = Math.max(1, Math.min(Number(options.pageSize) || 100, 250))
  const client = await pool.connect()
  let runId = null
  let cursor = options.cursor == null ? null : String(options.cursor)
  let fetched = 0
  let inserted = 0
  let updated = 0
  let skipped = 0
  try {
    const run = await client.query(
      `INSERT INTO fallback_playmatch_catalog_sync_runs(provider, requested_by, cursor_before)
       VALUES ($1,$2,$3) RETURNING id`,
      [provider.name, text(options.requestedBy, 240) || null, cursor],
    )
    runId = run.rows[0].id
    for (let pageNumber = 0; pageNumber < maxPages; pageNumber += 1) {
      const page = await provider.fetchPage(cursor, pageSize)
      fetched += page.items.length
      await client.query('BEGIN')
      try {
        for (const item of page.items) {
          const result = await upsertCatalogItem(client, item)
          if (result.status === 'inserted') inserted += 1
          else if (result.status === 'updated') updated += 1
          else skipped += 1
        }
        cursor = page.nextCursor
        await client.query(
          `INSERT INTO fallback_playmatch_catalog_provider_state(provider, next_cursor, last_success_at, last_error_code, updated_at)
           VALUES ($1,$2,NOW(),NULL,NOW())
           ON CONFLICT (provider) DO UPDATE SET next_cursor=EXCLUDED.next_cursor,last_success_at=NOW(),last_error_code=NULL,updated_at=NOW()`,
          [provider.name, cursor],
        )
        await client.query('COMMIT')
      } catch (error) {
        await client.query('ROLLBACK')
        throw error
      }
      if (page.exhausted || !cursor) break
    }
    await client.query(
      `UPDATE fallback_playmatch_catalog_sync_runs
          SET status='COMPLETED',cursor_after=$2,fetched_count=$3,inserted_count=$4,
              updated_count=$5,skipped_count=$6,completed_at=NOW()
        WHERE id=$1`,
      [runId, cursor, fetched, inserted, updated, skipped],
    )
    return { runId, provider: provider.name, cursor, fetched, inserted, updated, skipped }
  } catch (error) {
    if (runId) {
      await client.query(
        `UPDATE fallback_playmatch_catalog_sync_runs
            SET status='FAILED',error_code=$2,completed_at=NOW(),fetched_count=$3,
                inserted_count=$4,updated_count=$5,skipped_count=$6
          WHERE id=$1`,
        [runId, text(error?.message || 'CATALOG_SYNC_FAILED', 120), fetched, inserted, updated, skipped],
      ).catch(() => {})
    }
    throw error
  } finally {
    client.release()
  }
}

module.exports = { stableGameId, synchronizeGameCatalog, upsertCatalogItem }
