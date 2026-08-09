const { isAppAdmin } = require('./admin-moderation-routes')
const { synchronizeGameCatalog } = require('./playmatch-catalog-sync')
const { slugify } = require('./game-catalog-providers')

const SUPPORT_LEVELS = new Set(['FULLY_SUPPORTED', 'CATALOG_SUPPORTED', 'UNSUPPORTED'])

function booleanEnv(name, fallback = false) {
  const raw = String(process.env[name] ?? '').trim().toLowerCase()
  return raw ? ['1', 'true', 'yes', 'on'].includes(raw) : fallback
}

function text(value, max = 500) {
  return String(value || '').trim().slice(0, max)
}

function normalizeId(value) {
  return text(value, 240).toLowerCase()
}

function cleanArray(value, maxItems = 30, maxLength = 100) {
  if (!Array.isArray(value)) return []
  return [...new Set(value.map((entry) => text(entry, maxLength)).filter(Boolean))].slice(0, maxItems)
}

function safeUrl(value) {
  const candidate = text(value, 1500)
  return /^https:\/\//i.test(candidate) ? candidate : null
}

async function ensureDynamicPlayMatchCatalogTables(pool) {
  const columns = [
    `ADD COLUMN IF NOT EXISTS external_provider_id TEXT`,
    `ADD COLUMN IF NOT EXISTS slug TEXT`,
    `ADD COLUMN IF NOT EXISTS short_name TEXT`,
    `ADD COLUMN IF NOT EXISTS cover_url TEXT`,
    `ADD COLUMN IF NOT EXISTS logo_url TEXT`,
    `ADD COLUMN IF NOT EXISTS icon_url TEXT`,
    `ADD COLUMN IF NOT EXISTS genres JSONB NOT NULL DEFAULT '[]'::jsonb`,
    `ADD COLUMN IF NOT EXISTS online_supported BOOLEAN NOT NULL DEFAULT FALSE`,
    `ADD COLUMN IF NOT EXISTS multiplayer_supported BOOLEAN NOT NULL DEFAULT TRUE`,
    `ADD COLUMN IF NOT EXISTS competitive BOOLEAN NOT NULL DEFAULT FALSE`,
    `ADD COLUMN IF NOT EXISTS ranked_supported BOOLEAN NOT NULL DEFAULT FALSE`,
    `ADD COLUMN IF NOT EXISTS roles_supported BOOLEAN NOT NULL DEFAULT FALSE`,
    `ADD COLUMN IF NOT EXISTS release_date DATE`,
    `ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'manual'`,
    `ADD COLUMN IF NOT EXISTS provider_updated_at TIMESTAMPTZ`,
    `ADD COLUMN IF NOT EXISTS catalog_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ADD COLUMN IF NOT EXISTS support_level TEXT NOT NULL DEFAULT 'CATALOG_SUPPORTED'`,
    `ADD COLUMN IF NOT EXISTS game_user_id_label TEXT NOT NULL DEFAULT 'ID del juego'`,
    `ADD COLUMN IF NOT EXISTS source_url TEXT`,
    `ADD COLUMN IF NOT EXISTS cover_license TEXT`,
    `ADD COLUMN IF NOT EXISTS cover_attribution TEXT`,
    `ADD COLUMN IF NOT EXISTS cover_source_url TEXT`,
    `ADD COLUMN IF NOT EXISTS manual_override BOOLEAN NOT NULL DEFAULT FALSE`,
  ]
  for (const column of columns) {
    await pool.query(`ALTER TABLE fallback_game_catalog ${column}`)
  }
  await pool.query(`UPDATE fallback_game_catalog SET slug=COALESCE(NULLIF(slug,''),id),short_name=COALESCE(NULLIF(short_name,''),name),cover_url=COALESCE(cover_url,image_url) WHERE slug IS NULL OR short_name IS NULL OR cover_url IS NULL`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_game_catalog_active_support ON fallback_game_catalog(active,support_level,catalog_updated_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_game_catalog_provider_external ON fallback_game_catalog(lower(provider),lower(external_provider_id)) WHERE external_provider_id IS NOT NULL`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_playmatch_game_rank_schemas (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(), game_id TEXT NOT NULL REFERENCES fallback_game_catalog(id) ON DELETE CASCADE,
      schema_key TEXT NOT NULL, display_name TEXT NOT NULL, schema_version TEXT NOT NULL DEFAULT '1', season TEXT NOT NULL DEFAULT '',
      region_code TEXT NOT NULL DEFAULT '', platform TEXT NOT NULL DEFAULT '', points_label TEXT NOT NULL DEFAULT '', official_source_url TEXT,
      active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(game_id,schema_key,schema_version,season,region_code,platform)
    )`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_playmatch_game_ranks (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(), schema_id UUID NOT NULL REFERENCES fallback_playmatch_game_rank_schemas(id) ON DELETE CASCADE,
      rank_key TEXT NOT NULL, tier_key TEXT NOT NULL DEFAULT '', division_key TEXT NOT NULL DEFAULT '', display_name TEXT NOT NULL,
      order_index INTEGER NOT NULL, icon_url TEXT, min_rating INTEGER, max_rating INTEGER, active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(schema_id,rank_key)
    )`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_playmatch_game_roles (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(), game_id TEXT NOT NULL REFERENCES fallback_game_catalog(id) ON DELETE CASCADE,
      role_key TEXT NOT NULL, display_name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', icon_url TEXT, order_index INTEGER NOT NULL DEFAULT 0,
      official_source_url TEXT, active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(game_id,role_key)
    )`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_playmatch_catalog_sync_runs (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(), provider TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'RUNNING', requested_by TEXT,
      cursor_before TEXT, cursor_after TEXT, fetched_count INTEGER NOT NULL DEFAULT 0, inserted_count INTEGER NOT NULL DEFAULT 0,
      updated_count INTEGER NOT NULL DEFAULT 0, skipped_count INTEGER NOT NULL DEFAULT 0, pages_count INTEGER NOT NULL DEFAULT 0, error_code TEXT,
      started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), completed_at TIMESTAMPTZ
    )`)
  await pool.query(`ALTER TABLE fallback_playmatch_catalog_sync_runs ADD COLUMN IF NOT EXISTS pages_count INTEGER NOT NULL DEFAULT 0`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_playmatch_catalog_provider_state (
      provider TEXT PRIMARY KEY, next_cursor TEXT, last_success_at TIMESTAMPTZ, last_error_code TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )`)
}

function mapCatalogRow(row, configuration = null) {
  const coverUrl = safeUrl(row.cover_url || row.image_url)
  const logoUrl = safeUrl(row.logo_url)
  const iconUrl = safeUrl(row.icon_url || row.logo_url || row.cover_url || row.image_url)
  return {
    id: row.id,
    externalProviderId: row.external_provider_id || null,
    name: row.name,
    slug: row.slug || row.id,
    shortName: row.short_name || row.name,
    description: row.description || '',
    imageUrl: iconUrl || coverUrl,
    coverUrl,
    logoUrl,
    iconUrl,
    platforms: Array.isArray(row.platforms) ? row.platforms : [],
    genres: Array.isArray(row.genres) ? row.genres : [],
    ranks: Array.isArray(row.ranks) ? row.ranks : [],
    roles: Array.isArray(row.roles) ? row.roles : [],
    onlineSupported: Boolean(row.online_supported),
    multiplayerSupported: row.multiplayer_supported !== false,
    competitive: Boolean(row.competitive),
    rankedSupported: Boolean(row.ranked_supported),
    rolesSupported: Boolean(row.roles_supported),
    crossPlay: Boolean(row.cross_play),
    active: row.active !== false,
    releaseDate: row.release_date || null,
    provider: row.provider || 'manual',
    supportLevel: row.support_level || 'CATALOG_SUPPORTED',
    gameUserIdLabel: row.game_user_id_label || 'ID del juego',
    sourceUrl: safeUrl(row.source_url),
    coverLicense: row.cover_license || null,
    coverAttribution: row.cover_attribution || null,
    coverSourceUrl: safeUrl(row.cover_source_url),
    userCount: Number(row.user_count || 0),
    activeSearchCount: Number(row.active_search_count || 0),
    rankSchemas: configuration?.rankSchemas || [],
    roleDefinitions: configuration?.roleDefinitions || [],
  }
}

async function loadGameConfiguration(pool, gameId) {
  const [gameResult, schemasResult, rolesResult] = await Promise.all([
    pool.query(`SELECT * FROM fallback_game_catalog WHERE id=$1 LIMIT 1`, [gameId]),
    pool.query(
      `SELECT s.*,
              COALESCE(jsonb_agg(jsonb_build_object(
                'id',r.id,'key',r.rank_key,'tier',r.tier_key,'division',r.division_key,
                'displayName',r.display_name,'order',r.order_index,'iconUrl',r.icon_url,
                'minRating',r.min_rating,'maxRating',r.max_rating
              ) ORDER BY r.order_index) FILTER (WHERE r.id IS NOT NULL),'[]'::jsonb) AS rank_items
         FROM fallback_playmatch_game_rank_schemas s
         LEFT JOIN fallback_playmatch_game_ranks r ON r.schema_id=s.id AND r.active=TRUE
        WHERE s.game_id=$1 AND s.active=TRUE
        GROUP BY s.id ORDER BY s.updated_at DESC`,
      [gameId],
    ),
    pool.query(`SELECT * FROM fallback_playmatch_game_roles WHERE game_id=$1 AND active=TRUE ORDER BY order_index,display_name`, [gameId]),
  ])
  const game = gameResult.rows[0]
  if (!game) return null
  const rankSchemas = schemasResult.rows.map((schema) => ({
    id: schema.id,
    key: schema.schema_key,
    displayName: schema.display_name,
    version: schema.schema_version,
    season: schema.season || null,
    regionCode: schema.region_code || null,
    platform: schema.platform || null,
    pointsLabel: schema.points_label || null,
    officialSourceUrl: safeUrl(schema.official_source_url),
    ranks: schema.rank_items || [],
  }))
  const roleDefinitions = rolesResult.rows.map((role) => ({
    id: role.id,
    key: role.role_key,
    displayName: role.display_name,
    description: role.description || '',
    iconUrl: safeUrl(role.icon_url),
    officialSourceUrl: safeUrl(role.official_source_url),
  }))
  return mapCatalogRow(game, { rankSchemas, roleDefinitions })
}

async function searchCatalog(pool, options = {}) {
  const query = text(options.query, 100).toLowerCase()
  const limit = Math.max(1, Math.min(Number(options.limit) || 30, 50))
  const offset = Math.max(0, Math.min(Number(options.offset) || 0, 100_000))
  const includeUnsupported = options.includeUnsupported === true
  const { rows } = await pool.query(
    `SELECT gc.*,
            (SELECT COUNT(*)::int FROM fallback_user_games ug WHERE ug.game_id=gc.id) AS user_count,
            (SELECT COUNT(*)::int FROM fallback_matchmaking_sessions ms
              WHERE ms.created_at > NOW()-INTERVAL '30 days'
                AND (COALESCE(ms.criteria->'gameIds',ms.criteria->'game_ids','[]'::jsonb) ? gc.id)) AS active_search_count
       FROM fallback_game_catalog gc
      WHERE gc.active=TRUE
        AND ($1 OR gc.support_level <> 'UNSUPPORTED')
        AND ($2='' OR lower(gc.name) LIKE '%'||$2||'%' OR lower(COALESCE(gc.slug,'')) LIKE '%'||$2||'%')
      ORDER BY
        CASE WHEN $2<>'' AND lower(gc.name)= $2 THEN 0 WHEN $2<>'' AND lower(gc.name) LIKE $2||'%' THEN 1 ELSE 2 END,
        CASE WHEN $2='' THEN gc.sort_order ELSE 0 END,
        (SELECT COUNT(*) FROM fallback_user_games ug WHERE ug.game_id=gc.id) DESC,
        gc.name
      LIMIT $3 OFFSET $4`,
    [includeUnsupported, query, limit + 1, offset],
  )
  return {
    items: rows.slice(0, limit).map((row) => mapCatalogRow(row)),
    hasMore: rows.length > limit,
    nextOffset: rows.length > limit ? offset + limit : null,
  }
}

async function popularCatalog(pool, limit = 12) {
  const safeLimit = Math.max(1, Math.min(Number(limit) || 12, 20))
  const { rows } = await pool.query(
    `SELECT gc.*,
            COUNT(DISTINCT ug.user_id)::int AS user_count,
            COUNT(DISTINCT ms.id)::int AS active_search_count
       FROM fallback_game_catalog gc
       LEFT JOIN fallback_user_games ug ON ug.game_id=gc.id
       LEFT JOIN fallback_matchmaking_sessions ms
         ON ms.created_at > NOW()-INTERVAL '30 days'
        AND (COALESCE(ms.criteria->'gameIds',ms.criteria->'game_ids','[]'::jsonb) ? gc.id)
      WHERE gc.active=TRUE AND gc.support_level <> 'UNSUPPORTED'
      GROUP BY gc.id
      ORDER BY (COUNT(DISTINCT ug.user_id)*10 + COUNT(DISTINCT ms.id)*3) DESC,
               gc.sort_order, gc.name
      LIMIT $1`,
    [safeLimit],
  )
  return rows.map(mapCatalogRow)
}

async function requireCatalogAdmin(pool, req, res, getCurrentUser, userIdentity) {
  const user = await getCurrentUser(pool, req)
  if (!user) {
    res.status(404).json({ error: 'USER_NOT_FOUND' })
    return null
  }
  const identity = userIdentity(user)
  if (!(await isAppAdmin(pool, identity))) {
    res.status(403).json({ error: 'ADMIN_REQUIRED' })
    return null
  }
  return { user, identity }
}

function registerPlayMatchCatalogRoutes(app, dependencies) {
  const { pool, authRequired, getCurrentUser, userIdentity, publicContext, encodeCursor, decodeCursor } = dependencies

  app.get('/v1/data/playmatch/games/search', authRequired, async (req, res, next) => {
    try {
      if (!(await publicContext(req, res))) return
      const query = text(req.query.q, 100)
      const offset = decodeCursor(req.query.cursor)
      const limit = Math.max(1, Math.min(Number(req.query.limit) || 30, 50))
      const page = await searchCatalog(pool, { query, offset, limit })
      res.set('Cache-Control', 'private, max-age=60')
      res.json({
        items: page.items,
        nextCursor: page.nextOffset == null ? null : encodeCursor(page.nextOffset),
        query,
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/playmatch/games/popular', authRequired, async (req, res, next) => {
    try {
      if (!(await publicContext(req, res))) return
      const items = await popularCatalog(pool, req.query.limit)
      res.set('Cache-Control', 'private, max-age=120')
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/playmatch/games/:gameId/config', authRequired, async (req, res, next) => {
    try {
      if (!(await publicContext(req, res))) return
      const game = await loadGameConfiguration(pool, normalizeId(req.params.gameId))
      if (!game || !game.active || game.supportLevel === 'UNSUPPORTED') return res.status(404).json({ error: 'GAME_NOT_FOUND' })
      res.set('Cache-Control', 'private, max-age=300')
      res.json({ game })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/playmatch/admin/catalog/stats', authRequired, async (req, res, next) => {
    try {
      if (!(await requireCatalogAdmin(pool, req, res, getCurrentUser, userIdentity))) return
      const { rows } = await pool.query(
        `SELECT COUNT(*)::int AS total,
                COUNT(*) FILTER (WHERE active)::int AS active,
                COUNT(*) FILTER (WHERE multiplayer_supported)::int AS multiplayer,
                COUNT(*) FILTER (WHERE online_supported)::int AS online,
                COUNT(*) FILTER (WHERE cover_url IS NOT NULL)::int AS with_cover,
                COUNT(*) FILTER (WHERE logo_url IS NOT NULL)::int AS with_logo,
                COUNT(*) FILTER (WHERE support_level='FULLY_SUPPORTED')::int AS fully_supported,
                COUNT(*) FILTER (WHERE support_level='CATALOG_SUPPORTED')::int AS catalog_supported,
                COUNT(*) FILTER (WHERE ranked_supported)::int AS ranked_supported,
                COUNT(*) FILTER (WHERE roles_supported)::int AS roles_supported
           FROM fallback_game_catalog`,
      )
      res.json({ catalog: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/playmatch/admin/catalog/sync', authRequired, async (req, res, next) => {
    try {
      const admin = await requireCatalogAdmin(pool, req, res, getCurrentUser, userIdentity)
      if (!admin) return
      if (!booleanEnv('PLAYMATCH_CATALOG_ADMIN_SYNC_ENABLED', false)) {
        return res.status(404).json({ error: 'PLAYMATCH_CATALOG_SYNC_DISABLED' })
      }
      const provider = text(req.body?.provider || 'wikidata', 40).toLowerCase()
      const maxPages = Math.max(1, Math.min(Number(req.body?.maxPages) || 1, 2))
      const pageSize = Math.max(1, Math.min(Number(req.body?.pageSize) || 100, 250))
      const state = await pool.query(`SELECT next_cursor FROM fallback_playmatch_catalog_provider_state WHERE provider=$1`, [provider])
      const result = await synchronizeGameCatalog(pool, {
        provider,
        cursor: req.body?.restart === true ? null : state.rows[0]?.next_cursor,
        maxPages,
        pageSize,
        requestedBy: admin.identity?.preferredUserId || admin.user?.id,
      })
      res.json({ result })
    } catch (error) {
      if (error?.message === 'BLOCKED_EXTERNAL_DEPENDENCY') return res.status(409).json({ error: 'BLOCKED_EXTERNAL_DEPENDENCY' })
      next(error)
    }
  })

  app.post('/v1/data/playmatch/admin/games', authRequired, async (req, res, next) => {
    try {
      if (!(await requireCatalogAdmin(pool, req, res, getCurrentUser, userIdentity))) return
      const name = text(req.body?.name, 160)
      const id = normalizeId(req.body?.id || slugify(name))
      const platforms = cleanArray(req.body?.platforms)
      if (!name || !id || !platforms.length) return res.status(400).json({ error: 'INVALID_GAME' })
      const supportLevel = SUPPORT_LEVELS.has(req.body?.supportLevel) ? req.body.supportLevel : 'CATALOG_SUPPORTED'
      const { rows } = await pool.query(
        `INSERT INTO fallback_game_catalog(
           id,name,slug,short_name,description,image_url,cover_url,logo_url,icon_url,platforms,genres,
           online_supported,multiplayer_supported,competitive,ranked_supported,roles_supported,cross_play,
           active,release_date,provider,support_level,game_user_id_label,source_url,manual_override,catalog_updated_at
         ) VALUES ($1,$2,$3,$4,$5,$6,$6,$7,$8,$9::jsonb,$10::jsonb,$11,$12,$13,$14,$15,$16,$17,$18::date,'manual',$19,$20,$21,TRUE,NOW())
         RETURNING *`,
        [
          id, name, slugify(req.body?.slug || name), text(req.body?.shortName || name, 100), text(req.body?.description, 500),
          safeUrl(req.body?.coverUrl), safeUrl(req.body?.logoUrl), safeUrl(req.body?.iconUrl), JSON.stringify(platforms),
          JSON.stringify(cleanArray(req.body?.genres)), req.body?.onlineSupported === true, req.body?.multiplayerSupported !== false,
          req.body?.competitive === true, req.body?.rankedSupported === true, req.body?.rolesSupported === true,
          req.body?.crossPlay === true, req.body?.active !== false, /^\d{4}-\d{2}-\d{2}$/.test(req.body?.releaseDate || '') ? req.body.releaseDate : null,
          supportLevel, text(req.body?.gameUserIdLabel || 'ID del juego', 80), safeUrl(req.body?.sourceUrl),
        ],
      )
      res.status(201).json({ game: mapCatalogRow(rows[0]) })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'GAME_ALREADY_EXISTS' })
      next(error)
    }
  })

  app.patch('/v1/data/playmatch/admin/games/:gameId', authRequired, async (req, res, next) => {
    try {
      if (!(await requireCatalogAdmin(pool, req, res, getCurrentUser, userIdentity))) return
      const supportLevel = SUPPORT_LEVELS.has(req.body?.supportLevel) ? req.body.supportLevel : null
      const { rows } = await pool.query(
        `UPDATE fallback_game_catalog SET
           cover_url=CASE WHEN $2 THEN $3 ELSE cover_url END,
           logo_url=CASE WHEN $4 THEN $5 ELSE logo_url END,
           icon_url=CASE WHEN $6 THEN $7 ELSE icon_url END,
           platforms=CASE WHEN $8 THEN $9::jsonb ELSE platforms END,
           active=COALESCE($10,active), support_level=COALESCE($11,support_level),
           manual_override=TRUE,catalog_updated_at=NOW(),updated_at=NOW()
         WHERE id=$1 RETURNING *`,
        [
          normalizeId(req.params.gameId), Object.hasOwn(req.body || {}, 'coverUrl'), safeUrl(req.body?.coverUrl),
          Object.hasOwn(req.body || {}, 'logoUrl'), safeUrl(req.body?.logoUrl), Object.hasOwn(req.body || {}, 'iconUrl'), safeUrl(req.body?.iconUrl),
          Array.isArray(req.body?.platforms), JSON.stringify(cleanArray(req.body?.platforms)), typeof req.body?.active === 'boolean' ? req.body.active : null,
          supportLevel,
        ],
      )
      if (!rows[0]) return res.status(404).json({ error: 'GAME_NOT_FOUND' })
      res.json({ game: mapCatalogRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/playmatch/admin/games/:gameId/rank-schema', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      if (!(await requireCatalogAdmin(pool, req, res, getCurrentUser, userIdentity))) return
      const gameId = normalizeId(req.params.gameId)
      const key = normalizeId(req.body?.key || 'official')
      const version = text(req.body?.version || '1', 80)
      const ranks = Array.isArray(req.body?.ranks) ? req.body.ranks.slice(0, 100) : []
      if (!gameId || !key || !ranks.length) return res.status(400).json({ error: 'INVALID_RANK_SCHEMA' })
      await client.query('BEGIN')
      const schemaResult = await client.query(
        `INSERT INTO fallback_playmatch_game_rank_schemas(game_id,schema_key,display_name,schema_version,season,region_code,platform,points_label,official_source_url)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
         ON CONFLICT(game_id,schema_key,schema_version,season,region_code,platform)
         DO UPDATE SET display_name=EXCLUDED.display_name,points_label=EXCLUDED.points_label,official_source_url=EXCLUDED.official_source_url,active=TRUE,updated_at=NOW()
         RETURNING id`,
        [gameId, key, text(req.body?.displayName || 'Clasificación oficial', 120), version, text(req.body?.season, 80), text(req.body?.regionCode, 40), text(req.body?.platform, 80), text(req.body?.pointsLabel, 80), safeUrl(req.body?.officialSourceUrl)],
      )
      const schemaId = schemaResult.rows[0].id
      await client.query(`UPDATE fallback_playmatch_game_ranks SET active=FALSE,updated_at=NOW() WHERE schema_id=$1`, [schemaId])
      for (let index = 0; index < ranks.length; index += 1) {
        const rank = ranks[index]
        const rankKey = normalizeId(rank?.key || rank?.displayName)
        if (!rankKey || !text(rank?.displayName, 120)) throw Object.assign(new Error('INVALID_RANK'), { statusCode: 400 })
        await client.query(
          `INSERT INTO fallback_playmatch_game_ranks(schema_id,rank_key,tier_key,division_key,display_name,order_index,icon_url,min_rating,max_rating,active)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,TRUE)
           ON CONFLICT(schema_id,rank_key) DO UPDATE SET tier_key=EXCLUDED.tier_key,division_key=EXCLUDED.division_key,display_name=EXCLUDED.display_name,
             order_index=EXCLUDED.order_index,icon_url=EXCLUDED.icon_url,min_rating=EXCLUDED.min_rating,max_rating=EXCLUDED.max_rating,active=TRUE,updated_at=NOW()`,
          [schemaId, rankKey, normalizeId(rank?.tier), normalizeId(rank?.division), text(rank.displayName, 120), index, safeUrl(rank?.iconUrl), Number.isFinite(rank?.minRating) ? rank.minRating : null, Number.isFinite(rank?.maxRating) ? rank.maxRating : null],
        )
      }
      await client.query(`UPDATE fallback_game_catalog SET ranked_supported=TRUE,competitive=TRUE,support_level='FULLY_SUPPORTED',manual_override=TRUE,ranks=$2::jsonb,catalog_updated_at=NOW(),updated_at=NOW() WHERE id=$1`, [gameId, JSON.stringify(ranks.map((rank) => text(rank.displayName, 120)))])
      await client.query('COMMIT')
      res.json({ game: await loadGameConfiguration(pool, gameId) })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.put('/v1/data/playmatch/admin/games/:gameId/roles', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      if (!(await requireCatalogAdmin(pool, req, res, getCurrentUser, userIdentity))) return
      const gameId = normalizeId(req.params.gameId)
      const roles = Array.isArray(req.body?.roles) ? req.body.roles.slice(0, 50) : []
      await client.query('BEGIN')
      await client.query(`UPDATE fallback_playmatch_game_roles SET active=FALSE,updated_at=NOW() WHERE game_id=$1`, [gameId])
      for (let index = 0; index < roles.length; index += 1) {
        const role = roles[index]
        const key = normalizeId(role?.key || role?.displayName)
        if (!key || !text(role?.displayName, 120)) throw Object.assign(new Error('INVALID_ROLE'), { statusCode: 400 })
        await client.query(
          `INSERT INTO fallback_playmatch_game_roles(game_id,role_key,display_name,description,icon_url,order_index,official_source_url,active)
           VALUES ($1,$2,$3,$4,$5,$6,$7,TRUE)
           ON CONFLICT(game_id,role_key) DO UPDATE SET display_name=EXCLUDED.display_name,description=EXCLUDED.description,icon_url=EXCLUDED.icon_url,
             order_index=EXCLUDED.order_index,official_source_url=EXCLUDED.official_source_url,active=TRUE,updated_at=NOW()`,
          [gameId, key, text(role.displayName, 120), text(role?.description, 300), safeUrl(role?.iconUrl), index, safeUrl(role?.officialSourceUrl)],
        )
      }
      await client.query(`UPDATE fallback_game_catalog SET roles_supported=$2,support_level=CASE WHEN $2 THEN 'FULLY_SUPPORTED' ELSE support_level END,manual_override=TRUE,roles=$3::jsonb,catalog_updated_at=NOW(),updated_at=NOW() WHERE id=$1`, [gameId, roles.length > 0, JSON.stringify(roles.map((role) => text(role.displayName, 120)))])
      await client.query('COMMIT')
      res.json({ game: await loadGameConfiguration(pool, gameId) })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })
}

module.exports = {
  ensureDynamicPlayMatchCatalogTables,
  loadGameConfiguration,
  mapCatalogRow,
  popularCatalog,
  registerPlayMatchCatalogRoutes,
  searchCatalog,
}
