const assert = require('assert')
const fs = require('fs')
const path = require('path')

const {
  RawgGameCatalogProvider,
  WikidataGameCatalogProvider,
  acceptedCommonsLicense,
  normalizePlatform,
  slugify,
} = require('../src/game-catalog-providers')
const { mapCatalogRow, registerPlayMatchCatalogRoutes } = require('../src/playmatch-catalog')
const { stableGameId, upsertCatalogItem } = require('../src/playmatch-catalog-sync')

async function main() {
  assert.equal(normalizePlatform('Microsoft Windows'), 'PC')
  assert.equal(normalizePlatform('PlayStation 5'), 'PlayStation 5')
  assert.equal(normalizePlatform('Xbox Series X/S'), 'Xbox Series X|S')
  assert.equal(normalizePlatform('Android'), 'Android')
  assert.equal(normalizePlatform('Q123456'), null)
  assert.equal(slugify('Pokémon UNITE'), 'pokemon-unite')
  assert.equal(stableGameId('wikidata', 'Q123', 'Juego'), 'wikidata-q123')

  assert.equal(acceptedCommonsLicense({ LicenseShortName: { value: 'CC BY-SA 4.0' } }), true)
  assert.equal(acceptedCommonsLicense({ UsageTerms: { value: 'Public domain' } }), true)
  assert.equal(acceptedCommonsLicense({ LicenseShortName: { value: 'All rights reserved' } }), false)

  await assert.rejects(
    () => new RawgGameCatalogProvider({ apiKey: 'test', commercialApproved: false }).fetchPage(),
    /BLOCKED_EXTERNAL_DEPENDENCY/,
  )

  const entityValue = (id) => ({ mainsnak: { datavalue: { value: { id } } } })
  const stringValue = (value) => ({ mainsnak: { datavalue: { value } } })
  const fakeWikidataFetch = async (input, options = {}) => {
    const url = new URL(String(input))
    if (options.method === 'HEAD') return { ok: true }
    if (url.hostname === 'query.wikidata.org') {
      return {
        ok: true,
        json: async () => ({ results: { bindings: [{
          game: { value: 'http://www.wikidata.org/entity/Q42' },
          releaseDate: { value: '2024-01-02T00:00:00Z' },
        }] } }),
      }
    }
    const props = url.searchParams.get('props')
    if (props === 'labels') {
      return {
        ok: true,
        json: async () => ({ entities: {
          Q1406: { labels: { es: { value: 'Microsoft Windows' } } },
          Q744038: { labels: { es: { value: 'juego de disparos' } } },
        } }),
      }
    }
    return {
      ok: true,
      json: async () => ({ entities: {
        Q42: {
          labels: { es: { value: 'Juego de prueba' } },
          descriptions: { es: { value: 'Videojuego multijugador de prueba' } },
          claims: {
            P400: [entityValue('Q1406')],
            P136: [entityValue('Q744038')],
            P404: [entityValue('Q3297989')],
            P1733: [stringValue('123456')],
          },
        },
      } }),
    }
  }
  const wikidataPage = await new WikidataGameCatalogProvider({ fetchImpl: fakeWikidataFetch, steamStoreAssetsApproved: true }).fetchPage(null, 25)
  assert.equal(wikidataPage.items.length, 1)
  assert.equal(wikidataPage.items[0].externalProviderId, 'Q42')
  assert.deepEqual(wikidataPage.items[0].platforms, ['PC'])
  assert.deepEqual(wikidataPage.items[0].genres, ['juego de disparos'])
  assert.equal(wikidataPage.items[0].onlineSupported, true)
  assert.equal(wikidataPage.items[0].supportLevel, 'CATALOG_SUPPORTED')
  assert.equal(wikidataPage.items[0].coverUrl, 'https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/123456/library_600x900.jpg')
  assert.equal(wikidataPage.items[0].coverSourceUrl, 'https://store.steampowered.com/app/123456')

  const unavailableSteamFetch = async (input, options = {}) => {
    if (options.method === 'HEAD') return { ok: false, status: 404 }
    return fakeWikidataFetch(input, options)
  }
  const pageWithoutBrokenSteamArt = await new WikidataGameCatalogProvider({
    fetchImpl: unavailableSteamFetch,
    steamStoreAssetsApproved: true,
  }).fetchPage(null, 25)
  assert.equal(pageWithoutBrokenSteamArt.items[0].coverUrl, null, 'una cápsula Steam 404 no se publica como arte')

  const mapped = mapCatalogRow({
    id: 'game-1', name: 'Game', cover_url: 'https://cdn.example/cover.jpg', logo_url: null,
    platforms: ['PC'], genres: ['MOBA'], multiplayer_supported: true, support_level: 'CATALOG_SUPPORTED',
  })
  assert.equal(mapped.iconUrl, 'https://cdn.example/cover.jpg', 'logo faltante cae a portada')
  assert.equal(mapped.supportLevel, 'CATALOG_SUPPORTED')
  assert.deepEqual(mapped.platforms, ['PC'])

  const catalogSource = fs.readFileSync(path.resolve(__dirname, '../src/playmatch-catalog.js'), 'utf8')
  const artworkFilters = catalogSource.match(/AND COALESCE\(NULLIF\(BTRIM\(gc\.cover_url\)/g) || []
  assert(artworkFilters.length >= 2, 'búsqueda y carrusel deben excluir entradas sin arte verificado')
  assert(/CASE WHEN gc\.support_level='FULLY_SUPPORTED' THEN 0 ELSE 1 END/.test(catalogSource), 'los juegos principales deben encabezar el carrusel')

  const insertedQueries = []
  const insertClient = {
    query: async (sql) => {
      insertedQueries.push(sql)
      if (/SELECT \*/.test(sql)) return { rows: [] }
      return { rows: [] }
    },
  }
  const inserted = await upsertCatalogItem(insertClient, {
    provider: 'wikidata', externalProviderId: 'Q42', name: 'Online Test', platforms: ['PC'], genres: ['MMO'],
    multiplayerSupported: true, onlineSupported: true, active: true, supportLevel: 'CATALOG_SUPPORTED',
  })
  assert.equal(inserted.status, 'inserted')
  assert(insertedQueries.some((sql) => /INSERT INTO fallback_game_catalog/.test(sql)))

  const updateClient = {
    query: async (sql) => {
      if (/SELECT \*/.test(sql)) return { rows: [{ id: 'valorant', support_level: 'FULLY_SUPPORTED' }] }
      return { rows: [] }
    },
  }
  const updated = await upsertCatalogItem(updateClient, {
    provider: 'wikidata', externalProviderId: 'Q1', name: 'Valorant', platforms: ['PC'],
    multiplayerSupported: true, active: true, supportLevel: 'CATALOG_SUPPORTED',
  })
  assert.equal(updated.status, 'updated')
  assert.equal(updated.id, 'valorant', 'el seed conserva su ID estable')

  const registered = []
  const app = {}
  for (const verb of ['get', 'post', 'put', 'patch']) app[verb] = (route) => registered.push(`${verb.toUpperCase()} ${route}`)
  registerPlayMatchCatalogRoutes(app, {
    pool: {}, authRequired: () => {}, getCurrentUser: async () => null, userIdentity: () => ({}),
    publicContext: async () => null, encodeCursor: String, decodeCursor: Number,
  })
  for (const route of [
    'GET /v1/data/playmatch/games/search',
    'GET /v1/data/playmatch/games/popular',
    'GET /v1/data/playmatch/games/:gameId/config',
    'POST /v1/data/playmatch/admin/catalog/sync',
    'POST /v1/data/playmatch/admin/games',
    'PUT /v1/data/playmatch/admin/games/:gameId/rank-schema',
    'PUT /v1/data/playmatch/admin/games/:gameId/roles',
  ]) assert(registered.includes(route), `falta registrar ${route}`)

  const migration = fs.readFileSync(path.resolve(__dirname, '../../db/migrations/013_playmatch_dynamic_catalog_v2.sql'), 'utf8')
  for (const required of [
    'external_provider_id', 'cover_url', 'logo_url', 'support_level',
    'fallback_playmatch_game_rank_schemas', 'fallback_playmatch_game_ranks',
    'fallback_playmatch_game_roles', 'fallback_playmatch_catalog_sync_runs',
  ]) assert(migration.includes(required), `migración dinámica omite ${required}`)
  assert(/ON DELETE RESTRICT/i.test(fs.readFileSync(path.resolve(__dirname, '../../db/migrations/012_playmatch_games_v1.sql'), 'utf8')), 'los juegos usados no deben borrarse en cascada')
  const metricsMigration = fs.readFileSync(path.resolve(__dirname, '../../db/migrations/014_playmatch_catalog_sync_metrics.sql'), 'utf8')
  assert(metricsMigration.includes('pages_count'), 'las métricas deben registrar páginas procesadas')
  const artworkMigration = fs.readFileSync(path.resolve(__dirname, '../../db/migrations/015_playmatch_official_game_logos.sql'), 'utf8')
  for (const curatedGame of ['valorant', 'league-of-legends', 'fortnite', 'minecraft', 'warzone', 'mobile-legends', 'free-fire', 'rocket-league']) {
    assert(artworkMigration.includes(`'${curatedGame}'`), `falta arte oficial para ${curatedGame}`)
  }
  assert(/cover_url\s*=\s*NULL/i.test(artworkMigration), 'los logos no deben fingirse como portadas recortables')
  assert(artworkMigration.includes('cover_source_url'), 'cada asset debe conservar procedencia auditable')

  console.log('playmatch catalog tests passed')
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
