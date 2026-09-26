const APPROVED_EXTERNAL_LICENSES = new Map([
  ['cc0', { name: 'CC0 1.0', canonicalUrl: 'https://creativecommons.org/publicdomain/zero/1.0/' }],
  ['pdm', { name: 'Public Domain', canonicalUrl: 'https://creativecommons.org/publicdomain/mark/1.0/' }],
])

const USER_AGENT = 'Novvin-Server/3.3.0 (licensed music catalog; support@novvin.app)'

function cleanText(value, max = 500) {
  return String(value || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max)
}

function httpsUrl(value) {
  try {
    const url = new URL(String(value || '').trim())
    return url.protocol === 'https:' ? url.toString() : null
  } catch (_) {
    return null
  }
}

function normalizedExternalLicense(code, url) {
  const normalizedCode = String(code || '').trim().toLowerCase()
  const definition = APPROVED_EXTERNAL_LICENSES.get(normalizedCode)
  if (!definition) return null
  const licenseUrl = httpsUrl(url)
  if (!licenseUrl) return null
  const parsed = new URL(licenseUrl)
  const expected = new URL(definition.canonicalUrl)
  const path = parsed.pathname.toLowerCase().replace(/\/$/, '')
  const expectedPath = expected.pathname.toLowerCase().replace(/\/$/, '')
  if (parsed.hostname !== expected.hostname || path !== expectedPath) return null
  return { code: normalizedCode, name: definition.name, url: definition.canonicalUrl }
}

function durationMs(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0) return 0
  // Openverse currently returns milliseconds. Jamendo returns seconds.
  return Math.round(parsed)
}

async function fetchJson(url, { headers = {}, timeoutMs = 8000 } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(url, {
      headers: { Accept: 'application/json', 'User-Agent': USER_AGENT, ...headers },
      signal: controller.signal,
    })
    if (!response.ok) {
      const error = new Error(`MUSIC_PROVIDER_HTTP_${response.status}`)
      error.status = response.status
      throw error
    }
    return response.json()
  } finally {
    clearTimeout(timer)
  }
}

function openverseTrack(item) {
  const license = normalizedExternalLicense(item?.license, item?.license_url)
  const audioUrl = httpsUrl(item?.url)
  const sourcePageUrl = httpsUrl(item?.foreign_landing_url)
  const id = cleanText(item?.id, 100)
  const length = durationMs(item?.duration)
  if (!license || !audioUrl || !sourcePageUrl || !id || length < 5000) return null
  const genres = Array.isArray(item?.genres) ? item.genres.map((entry) => cleanText(entry, 60)).filter(Boolean) : []
  const tags = Array.isArray(item?.tags)
    ? item.tags.map((entry) => cleanText(entry?.name || entry, 60)).filter(Boolean)
    : []
  return {
    id: `openverse:${id}`,
    title: cleanText(item?.title, 140) || 'Audio sin título',
    artist: cleanText(item?.creator, 120) || 'Autor no indicado',
    album: cleanText(item?.audio_set?.title, 140) || null,
    durationMs: length,
    audioUrl,
    previewUrl: audioUrl,
    source: 'openverse',
    provider: cleanText(item?.provider, 80) || 'Openverse',
    sourcePageUrl,
    license: license.name,
    licenseCode: license.code,
    licenseUrl: license.url,
    attribution: cleanText(item?.attribution, 500) || `${cleanText(item?.title, 140)} — ${cleanText(item?.creator, 120)}`,
    artworkUrl: httpsUrl(item?.thumbnail),
    categories: Array.from(new Set([cleanText(item?.category, 60), ...genres, ...tags].filter(Boolean))).slice(0, 20),
    waveformUrl: httpsUrl(item?.waveform),
    allowUseInMoments: true,
    allowRemix: true,
    explicit: item?.mature === true,
  }
}

function jamendoTrack(item) {
  const licenseUrl = httpsUrl(item?.license_ccurl)
  let licenseCode = ''
  if (licenseUrl?.includes('/publicdomain/zero/')) licenseCode = 'cc0'
  if (licenseUrl?.includes('/publicdomain/mark/')) licenseCode = 'pdm'
  const license = normalizedExternalLicense(licenseCode, licenseUrl)
  const audioUrl = httpsUrl(item?.audio)
  const sourcePageUrl = httpsUrl(item?.shareurl || item?.shorturl)
  const id = cleanText(item?.id, 100)
  const length = Math.round(Number(item?.duration || 0) * 1000)
  // The current Novvin policy admits only verified CC0/Public Domain tracks.
  if (!license || !audioUrl || !sourcePageUrl || !id || length < 5000) return null
  const musicInfo = item?.musicinfo || {}
  const tags = musicInfo?.tags || {}
  const categories = [...(tags.genres || []), ...(tags.instruments || []), ...(tags.vartags || [])]
    .map((entry) => cleanText(entry, 60)).filter(Boolean)
  return {
    id: `jamendo:${id}`,
    title: cleanText(item?.name, 140) || 'Audio sin título',
    artist: cleanText(item?.artist_name, 120) || 'Autor no indicado',
    album: cleanText(item?.album_name, 140) || null,
    durationMs: length,
    audioUrl,
    previewUrl: audioUrl,
    source: 'jamendo',
    provider: 'Jamendo',
    sourcePageUrl,
    license: license.name,
    licenseCode: license.code,
    licenseUrl: license.url,
    attribution: `${cleanText(item?.name, 140)} — ${cleanText(item?.artist_name, 120)}`,
    artworkUrl: httpsUrl(item?.image || item?.album_image),
    categories: Array.from(new Set(categories)).slice(0, 20),
    waveformUrl: null,
    allowUseInMoments: true,
    allowRemix: true,
    explicit: false,
  }
}

function openverseHeaders() {
  const token = String(process.env.OPENVERSE_API_TOKEN || '').trim()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function searchOpenverse({ query = '', category = '', page = 1, limit = 20, order = 'relevance' } = {}) {
  const safeQuery = cleanText(query, 100)
  const safeCategory = cleanText(category, 60)
  const params = new URLSearchParams({
    q: [safeQuery, safeCategory].filter(Boolean).join(' ') || 'music',
    license: 'cc0,pdm',
    page: String(Math.max(1, Number(page) || 1)),
    page_size: String(Math.max(1, Math.min(50, Number(limit) || 20))),
    mature: 'false',
  })
  if (order === 'trending') params.set('q', [safeQuery, safeCategory, 'music'].filter(Boolean).join(' '))
  const base = String(process.env.OPENVERSE_API_BASE || 'https://api.openverse.org/v1/audio/').replace(/\/$/, '')
  const payload = await fetchJson(`${base}/?${params}`, { headers: openverseHeaders() })
  return {
    items: (payload?.results || []).map(openverseTrack).filter(Boolean),
    nextPage: payload?.next ? Math.max(1, Number(page) || 1) + 1 : null,
    provider: 'openverse',
  }
}

async function getOpenverseTrack(id) {
  const cleanId = cleanText(id, 100).replace(/^openverse:/, '')
  if (!/^[0-9a-f-]{20,}$/i.test(cleanId)) return null
  const base = String(process.env.OPENVERSE_API_BASE || 'https://api.openverse.org/v1/audio/').replace(/\/$/, '')
  const payload = await fetchJson(`${base}/${encodeURIComponent(cleanId)}/`, { headers: openverseHeaders() })
  return openverseTrack(payload)
}

async function searchJamendo({ query = '', category = '', page = 1, limit = 20 } = {}) {
  const clientId = String(process.env.JAMENDO_CLIENT_ID || '').trim()
  if (!clientId) return { items: [], nextPage: null, provider: 'jamendo', disabledReason: 'JAMENDO_CLIENT_ID_MISSING' }
  const params = new URLSearchParams({
    client_id: clientId,
    format: 'json',
    limit: String(Math.max(1, Math.min(50, Number(limit) || 20))),
    offset: String((Math.max(1, Number(page) || 1) - 1) * Math.max(1, Math.min(50, Number(limit) || 20))),
    include: 'licenses+musicinfo+stats',
    audioformat: 'mp31',
    audiodlformat: 'mp32',
  })
  if (query) params.set('search', cleanText(query, 100))
  if (category) params.set('fuzzytags', cleanText(category, 60).toLowerCase())
  const payload = await fetchJson(`https://api.jamendo.com/v3.0/tracks/?${params}`)
  const raw = payload?.results || []
  return {
    items: raw.map(jamendoTrack).filter(Boolean),
    nextPage: raw.length === Number(params.get('limit')) ? Math.max(1, Number(page) || 1) + 1 : null,
    provider: 'jamendo',
  }
}

async function getJamendoTrack(id) {
  const clientId = String(process.env.JAMENDO_CLIENT_ID || '').trim()
  const cleanId = cleanText(id, 100).replace(/^jamendo:/, '')
  if (!clientId || !/^\d{1,20}$/.test(cleanId)) return null
  const params = new URLSearchParams({
    client_id: clientId,
    format: 'json',
    id: cleanId,
    limit: '1',
    include: 'licenses+musicinfo+stats',
    audioformat: 'mp31',
    audiodlformat: 'mp32',
  })
  const payload = await fetchJson(`https://api.jamendo.com/v3.0/tracks/?${params}`)
  return jamendoTrack(payload?.results?.[0])
}

async function getExternalTrack(trackId) {
  const value = cleanText(trackId, 150)
  if (value.startsWith('openverse:')) return getOpenverseTrack(value)
  if (value.startsWith('jamendo:')) return getJamendoTrack(value)
  return null
}

module.exports = {
  APPROVED_EXTERNAL_LICENSES,
  getExternalTrack,
  getJamendoTrack,
  jamendoTrack,
  normalizedExternalLicense,
  openverseTrack,
  searchJamendo,
  searchOpenverse,
}
