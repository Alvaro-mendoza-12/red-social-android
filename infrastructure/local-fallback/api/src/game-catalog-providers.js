const WIKIDATA_ENDPOINT = 'https://query.wikidata.org/sparql'
const WIKIDATA_API_ENDPOINT = 'https://www.wikidata.org/w/api.php'
const COMMONS_ENDPOINT = 'https://commons.wikimedia.org/w/api.php'
const RAWG_ENDPOINT = 'https://api.rawg.io/api/games'

function text(value, max = 500) {
  const cleaned = String(value || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim()
  return cleaned.slice(0, max)
}

function truthy(value) {
  return /^(1|true|yes|on)$/i.test(String(value || '').trim())
}

function httpsUrl(value) {
  const candidate = text(value, 1500)
  return /^https:\/\//i.test(candidate) ? candidate : null
}

function unique(values, max = 50) {
  return [...new Set((values || []).map((entry) => text(entry, 100)).filter(Boolean))].slice(0, max)
}

function slugify(value) {
  return text(value, 160)
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 120)
}

function normalizePlatform(label) {
  const value = text(label, 100)
  const lower = value.toLowerCase()
  if (!value || /^q\d+$/i.test(value)) return null
  if (/windows|linux|macos|mac os|personal computer|pc-98|dos/.test(lower)) return 'PC'
  if (/playstation 5|ps5/.test(lower)) return 'PlayStation 5'
  if (/playstation 4|ps4/.test(lower)) return 'PlayStation 4'
  if (/playstation 3|ps3/.test(lower)) return 'PlayStation 3'
  if (/playstation vita|ps vita/.test(lower)) return 'PlayStation Vita'
  if (/playstation/.test(lower)) return value
  if (/xbox series/.test(lower)) return 'Xbox Series X|S'
  if (/xbox one/.test(lower)) return 'Xbox One'
  if (/xbox 360/.test(lower)) return 'Xbox 360'
  if (/xbox/.test(lower)) return value
  if (/nintendo switch 2/.test(lower)) return 'Nintendo Switch 2'
  if (/nintendo switch/.test(lower)) return 'Nintendo Switch'
  if (/wii u/.test(lower)) return 'Wii U'
  if (/nintendo 3ds/.test(lower)) return 'Nintendo 3DS'
  if (/android/.test(lower)) return 'Android'
  if (/iphone|ipad|ios/.test(lower)) return 'iOS'
  if (/web browser|browser/.test(lower)) return 'Web'
  return value
}

function splitBinding(binding, field) {
  return unique(String(binding?.[field]?.value || '').split('|'))
}

function commonsFileTitle(url) {
  const value = text(url, 1500)
  const marker = '/Special:FilePath/'
  const index = value.indexOf(marker)
  if (index < 0) return null
  try {
    return `File:${decodeURIComponent(value.slice(index + marker.length)).replace(/_/g, ' ')}`
  } catch {
    return null
  }
}

function acceptedCommonsLicense(metadata) {
  const shortName = text(metadata?.LicenseShortName?.value, 120).toLowerCase()
  const usage = text(metadata?.UsageTerms?.value, 160).toLowerCase()
  const combined = `${shortName} ${usage}`
  return /public domain|cc0|creative commons.*attribution|cc[- ]by(?:[- ]sa)?\b/.test(combined)
}

async function fetchJson(url, { timeoutMs = 55_000, userAgent, fetchImpl = fetch, attempts = 2 } = {}) {
  let lastError = null
  for (let attempt = 1; attempt <= Math.max(1, attempts); attempt += 1) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    try {
      const response = await fetchImpl(url, {
        signal: controller.signal,
        headers: {
          Accept: 'application/json',
          'User-Agent': userAgent || 'NovvinPlayMatch/1.0 (catalog synchronization)',
        },
      })
      if (!response.ok) {
        const error = new Error(`CATALOG_PROVIDER_HTTP_${response.status}`)
        error.statusCode = response.status
        throw error
      }
      return await response.json()
    } catch (error) {
      lastError = error
      const retriable = error?.name === 'AbortError' || error?.statusCode === 429 || error?.statusCode >= 500
      if (!retriable || attempt >= attempts) throw error
      await new Promise((resolve) => setTimeout(resolve, 350 * attempt))
    } finally {
      clearTimeout(timer)
    }
  }
  throw lastError || new Error('CATALOG_PROVIDER_UNAVAILABLE')
}

async function resolveCommonsAssets(urls, options = {}) {
  const titles = unique(urls.map(commonsFileTitle).filter(Boolean), 50)
  if (!titles.length) return new Map()
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    origin: '*',
    prop: 'imageinfo',
    iiprop: 'url|extmetadata',
    iiurlwidth: String(Math.max(320, Math.min(Number(options.width) || 720, 1280))),
    titles: titles.join('|'),
  })
  const payload = await fetchJson(`${COMMONS_ENDPOINT}?${params}`, options)
  const result = new Map()
  for (const page of Object.values(payload?.query?.pages || {})) {
    const info = page?.imageinfo?.[0]
    const metadata = info?.extmetadata || {}
    if (!acceptedCommonsLicense(metadata)) continue
    const sourceUrl = httpsUrl(info?.descriptionurl)
    const assetUrl = httpsUrl(info?.thumburl || info?.url)
    if (!assetUrl || !sourceUrl) continue
    result.set(text(page.title, 500), {
      url: assetUrl,
      sourceUrl,
      license: text(metadata?.LicenseShortName?.value || metadata?.UsageTerms?.value, 120),
      attribution: text(metadata?.Credit?.value || metadata?.Artist?.value, 500),
    })
  }
  return result
}

function entityIdFromUri(value) {
  const match = String(value || '').match(/\/entity\/(Q\d+)$/i)
  return match?.[1]?.toUpperCase() || null
}

function claimValues(entity, property) {
  return (entity?.claims?.[property] || [])
    .map((claim) => claim?.mainsnak?.datavalue?.value)
    .filter((value) => value != null)
}

function entityIdsFromClaims(entity, property) {
  return unique(claimValues(entity, property).map((value) => value?.id).filter(Boolean), 100)
}

function commonsFileUrl(entity, property) {
  const filename = text(claimValues(entity, property)[0], 500)
  return filename ? `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(filename)}` : null
}

function entityLabel(entity) {
  return text(entity?.labels?.es?.value || entity?.labels?.en?.value, 160)
}

function entityDescription(entity) {
  return text(entity?.descriptions?.es?.value || entity?.descriptions?.en?.value, 500)
}

async function fetchWikidataEntities(ids, options = {}, props = 'labels|descriptions|claims') {
  const entities = new Map()
  for (let start = 0; start < ids.length; start += 50) {
    const batch = unique(ids.slice(start, start + 50), 50)
    if (!batch.length) continue
    const params = new URLSearchParams({
      action: 'wbgetentities',
      format: 'json',
      origin: '*',
      ids: batch.join('|'),
      props,
      languages: 'es|en',
      languagefallback: '1',
    })
    const payload = await fetchJson(`${WIKIDATA_API_ENDPOINT}?${params}`, options)
    for (const [id, entity] of Object.entries(payload?.entities || {})) entities.set(id.toUpperCase(), entity)
  }
  return entities
}

class GameCatalogProvider {
  constructor(name) {
    if (new.target === GameCatalogProvider) throw new Error('GAME_CATALOG_PROVIDER_ABSTRACT')
    this.name = name
  }

  async fetchPage() {
    throw new Error('GAME_CATALOG_PROVIDER_FETCH_NOT_IMPLEMENTED')
  }
}

class WikidataGameCatalogProvider extends GameCatalogProvider {
  constructor(options = {}) {
    super('wikidata')
    this.userAgent = text(options.userAgent || process.env.PLAYMATCH_CATALOG_USER_AGENT, 240)
      || 'NovvinPlayMatch/1.0 (catalog-sync; support@conectate.com)'
    this.minimumYear = Math.max(1970, Math.min(Number(options.minimumYear || process.env.PLAYMATCH_CATALOG_MIN_RELEASE_YEAR) || 2000, 2100))
    this.fetchImpl = options.fetchImpl || fetch
  }

  async fetchPage(cursor = null, limit = 100) {
    const offset = Math.max(0, Number(cursor) || 0)
    const pageSize = Math.max(1, Math.min(Number(limit) || 100, 250))
    const query = `
SELECT DISTINCT ?game ?releaseDate
WHERE {
  ?game wdt:P404 ?mode;
        wdt:P577 ?releaseDate.
  VALUES ?mode { wd:Q6895044 wd:Q3297989 wd:Q116634 }
  FILTER(YEAR(?releaseDate) >= ${this.minimumYear})
  FILTER(?releaseDate <= NOW())
}
ORDER BY DESC(?releaseDate) ?game
LIMIT ${pageSize}
OFFSET ${offset}`
    const params = new URLSearchParams({ query, format: 'json' })
    const fetchOptions = { userAgent: this.userAgent, fetchImpl: this.fetchImpl, attempts: 2 }
    const payload = await fetchJson(`${WIKIDATA_ENDPOINT}?${params}`, fetchOptions)
    const rows = payload?.results?.bindings || []
    const uniqueRowsById = new Map()
    for (const row of rows) {
      const id = entityIdFromUri(row?.game?.value)
      if (id && !uniqueRowsById.has(id)) uniqueRowsById.set(id, row)
    }
    const uniqueRows = [...uniqueRowsById.values()]
    const ids = [...uniqueRowsById.keys()]
    const entities = await fetchWikidataEntities(ids, fetchOptions)
    const relatedIds = unique([...entities.values()].flatMap((entity) => [
      ...entityIdsFromClaims(entity, 'P400'),
      ...entityIdsFromClaims(entity, 'P136'),
    ]), 500)
    const relatedEntities = await fetchWikidataEntities(relatedIds, fetchOptions, 'labels')
    const assetUrls = [...entities.values()].flatMap((entity) => [commonsFileUrl(entity, 'P18'), commonsFileUrl(entity, 'P154')]).filter(Boolean)
    let assets = new Map()
    for (let start = 0; start < assetUrls.length; start += 50) {
      const page = await resolveCommonsAssets(assetUrls.slice(start, start + 50), fetchOptions)
      assets = new Map([...assets, ...page])
    }
    const items = uniqueRows.map((row) => {
      const externalProviderId = entityIdFromUri(row?.game?.value)
      const entity = entities.get(externalProviderId)
      const entityUrl = externalProviderId ? `https://www.wikidata.org/wiki/${externalProviderId}` : null
      const name = entityLabel(entity)
      const coverSource = commonsFileUrl(entity, 'P18')
      const logoSource = commonsFileUrl(entity, 'P154')
      const cover = assets.get(commonsFileTitle(coverSource)) || null
      const logo = assets.get(commonsFileTitle(logoSource)) || null
      const platforms = unique(entityIdsFromClaims(entity, 'P400').map((id) => normalizePlatform(entityLabel(relatedEntities.get(id)))).filter(Boolean), 30)
      const genres = unique(entityIdsFromClaims(entity, 'P136').map((id) => entityLabel(relatedEntities.get(id))).filter(Boolean), 30)
      const modes = entityIdsFromClaims(entity, 'P404')
      return {
        externalProviderId,
        name,
        slug: slugify(name),
        shortName: name,
        description: entityDescription(entity),
        coverUrl: cover?.url || null,
        logoUrl: logo?.url || null,
        iconUrl: logo?.url || cover?.url || null,
        platforms,
        genres,
        onlineSupported: modes.includes('Q3297989') || modes.includes('Q116634'),
        multiplayerSupported: true,
        competitive: false,
        rankedSupported: false,
        rolesSupported: false,
        crossPlay: false,
        active: platforms.length > 0,
        releaseDate: text(row?.releaseDate?.value, 10) || null,
        provider: this.name,
        sourceUrl: entityUrl,
        coverLicense: cover?.license || logo?.license || null,
        coverAttribution: cover?.attribution || logo?.attribution || null,
        coverSourceUrl: cover?.sourceUrl || logo?.sourceUrl || null,
        supportLevel: platforms.length > 0 ? 'CATALOG_SUPPORTED' : 'UNSUPPORTED',
      }
    }).filter((item) => item.externalProviderId && item.name)
    return {
      items,
      nextCursor: rows.length === pageSize ? String(offset + pageSize) : null,
      exhausted: rows.length < pageSize,
    }
  }
}

class RawgGameCatalogProvider extends GameCatalogProvider {
  constructor(options = {}) {
    super('rawg')
    this.apiKey = text(options.apiKey || process.env.RAWG_API_KEY, 500)
    this.commercialApproved = truthy(options.commercialApproved ?? process.env.PLAYMATCH_RAWG_COMMERCIAL_USE_APPROVED)
  }

  async fetchPage(cursor = null, limit = 100) {
    if (!this.apiKey || !this.commercialApproved) {
      const error = new Error('BLOCKED_EXTERNAL_DEPENDENCY')
      error.provider = this.name
      throw error
    }
    const page = Math.max(1, Number(cursor) || 1)
    const pageSize = Math.max(1, Math.min(Number(limit) || 100, 40))
    const params = new URLSearchParams({
      key: this.apiKey,
      page: String(page),
      page_size: String(pageSize),
      tags: 'multiplayer,online-multiplayer,online-co-op',
      ordering: '-updated',
    })
    const payload = await fetchJson(`${RAWG_ENDPOINT}?${params}`)
    const items = (payload?.results || []).map((game) => {
      const name = text(game?.name, 160)
      return {
        externalProviderId: text(game?.id, 80),
        name,
        slug: slugify(game?.slug || name),
        shortName: name,
        description: '',
        coverUrl: httpsUrl(game?.background_image),
        logoUrl: null,
        iconUrl: null,
        platforms: unique((game?.platforms || []).map((entry) => normalizePlatform(entry?.platform?.name)).filter(Boolean), 30),
        genres: unique((game?.genres || []).map((entry) => entry?.name), 30),
        onlineSupported: true,
        multiplayerSupported: true,
        competitive: false,
        rankedSupported: false,
        rolesSupported: false,
        crossPlay: false,
        active: true,
        releaseDate: text(game?.released, 10) || null,
        provider: this.name,
        sourceUrl: game?.slug ? `https://rawg.io/games/${encodeURIComponent(game.slug)}` : 'https://rawg.io',
        coverLicense: 'RAWG commercial plan',
        coverAttribution: 'RAWG',
        coverSourceUrl: game?.slug ? `https://rawg.io/games/${encodeURIComponent(game.slug)}` : 'https://rawg.io',
        supportLevel: 'CATALOG_SUPPORTED',
      }
    }).filter((item) => item.externalProviderId && item.name)
    return { items, nextCursor: payload?.next ? String(page + 1) : null, exhausted: !payload?.next }
  }
}

function gameCatalogProvider(name, options = {}) {
  const provider = text(name, 40).toLowerCase()
  if (provider === 'wikidata') return new WikidataGameCatalogProvider(options)
  if (provider === 'rawg') return new RawgGameCatalogProvider(options)
  throw new Error('GAME_CATALOG_PROVIDER_UNSUPPORTED')
}

module.exports = {
  GameCatalogProvider,
  RawgGameCatalogProvider,
  WikidataGameCatalogProvider,
  acceptedCommonsLicense,
  commonsFileTitle,
  gameCatalogProvider,
  normalizePlatform,
  resolveCommonsAssets,
  fetchWikidataEntities,
  slugify,
}
