'use strict'

const MAX_SEARCH_DOCUMENTS = 800
const normalize = value => String(value || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
const words = value => normalize(value).match(/[\p{L}\p{N}_]+/gu) || []

function searchPageOptions(raw = {}) {
  const limit = Number(raw.limit), offset = Number(raw.offset)
  return {
    limit: Number.isFinite(limit) && limit > 0 ? Math.min(Math.floor(limit), 50) : 24,
    offset: Number.isFinite(offset) && offset > 0 ? Math.min(Math.floor(offset), MAX_SEARCH_DOCUMENTS) : 0,
  }
}

/** Bounded lexical fallback; it never invents trends, returns unrelated items or widens the authorized corpus. */
function lexicalSearchPage(query, documents, options) {
  const { limit, offset } = searchPageOptions(options)
  const terms = [...new Set(words(query))]
  const ranked = terms.length ? documents.slice(0, MAX_SEARCH_DOCUMENTS).map(document => {
    const title = new Set(words(document.title))
    const tags = new Set(words(Array.isArray(document.tags) ? document.tags.join(' ') : document.tags))
    const body = new Set(words(document.text))
    const matched = terms.filter(term => title.has(term) || tags.has(term) || body.has(term))
    const score = matched.reduce((sum, term) => sum + (title.has(term) ? 3 : tags.has(term) ? 2 : 1), 0)
    return { id: document.id, score: score / Math.max(1, terms.length * 3), matched: matched.length }
  }).filter(result => result.matched > 0)
    .sort((a, b) => b.matched - a.matched || b.score - a.score || a.id.localeCompare(b.id)) : []
  const results = ranked.slice(offset, offset + limit).map(({ matched, ...result }) => ({ ...result, reason: 'lexical_match' }))
  const hasMore = offset + results.length < ranked.length
  return { model: 'novvin-lexical-fallback-v1', results, hasMore, nextOffset: hasMore ? offset + results.length : null }
}

async function publicSearchPage({ query, documents, limit, offset, rank }) {
  const options = searchPageOptions({ limit, offset })
  const bounded = documents.slice(0, MAX_SEARCH_DOCUMENTS)
  let page
  try {
    page = await rank({ query, documents: bounded, ...options })
  } catch (error) {
    if (!['AI_SERVICE_TIMEOUT', 'AI_SERVICE_UNAVAILABLE'].includes(error?.code)) throw error
    return lexicalSearchPage(query, bounded, options)
  }
  // An older AI service has no pagination contract. Use the same complete bounded
  // corpus locally instead of silently repeating its first 50 results on every page.
  if (!Array.isArray(page?.results) || typeof page.hasMore !== 'boolean') {
    return lexicalSearchPage(query, bounded, options)
  }
  const allowed = new Set(bounded.map(document => document.id))
  const seen = new Set()
  const results = page.results.filter(result => {
    if (!allowed.has(result?.id) || seen.has(result.id)) return false
    seen.add(result.id)
    return true
  }).slice(0, options.limit)
  const nextOffset = Number(page.nextOffset)
  const hasMore = page.hasMore && Number.isInteger(nextOffset) && nextOffset > options.offset && nextOffset <= bounded.length
  return { ...page, results, hasMore, nextOffset: hasMore ? nextOffset : null }
}

module.exports = { MAX_SEARCH_DOCUMENTS, searchPageOptions, lexicalSearchPage, publicSearchPage }
