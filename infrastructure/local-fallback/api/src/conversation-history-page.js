'use strict'

const { mergeMessageRows } = require('./message-deletion-policy')

const PAGE_SIZE = 80
const MAX_PAGE_SIZE = 200
const clean = value => String(value || '').trim().toLowerCase()

// Keep PostgreSQL's fractional timestamp in the cursor; Date.toISOString loses microseconds.
function historyPosition(row) {
  const at = String(row.created_at || row.createdAt || '').trim()
  const id = clean(row.id)
  if (!id || !Number.isFinite(Date.parse(at))) return null
  return { at, id }
}

function timestampMicros(value) {
  const millis = Date.parse(value)
  const fraction = /\.(\d+)(?:Z|[+-]\d\d(?::?\d\d)?)$/i.exec(value)?.[1] || ''
  return BigInt(millis) * 1000n + BigInt(fraction.padEnd(6, '0').slice(3, 6) || '0')
}

function compareHistory(a, b) {
  const left = historyPosition(a), right = historyPosition(b)
  if (!left || !right) return Number(Boolean(left)) - Number(Boolean(right))
  const difference = timestampMicros(left.at) - timestampMicros(right.at)
  return difference < 0n ? -1 : difference > 0n ? 1 : left.id < right.id ? -1 : left.id > right.id ? 1 : 0
}

function encodeHistoryCursor(row) {
  const position = historyPosition(row)
  return position ? Buffer.from(JSON.stringify(position)).toString('base64url') : null
}

function historyPageOptions(query = {}) {
  // Existing Android clients retain their current full-history contract.
  if (query.limit === undefined && query.before === undefined) return null
  const limit = Number(query.limit ?? PAGE_SIZE)
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_PAGE_SIZE) {
    throw Object.assign(new Error('INVALID_HISTORY_LIMIT'), { status: 400 })
  }
  let before = null
  if (query.before !== undefined) {
    const token = typeof query.before === 'string' ? query.before : ''
    try {
      if (!token || token.length > 1024 || !/^[\w-]+$/.test(token)) throw new Error()
      const parsed = JSON.parse(Buffer.from(token, 'base64url').toString('utf8'))
      if (typeof parsed.at !== 'string' || parsed.at.length > 64 || typeof parsed.id !== 'string' ||
          !/^[a-zA-Z0-9_-]{1,128}$/.test(parsed.id) || !Number.isFinite(Date.parse(parsed.at))) throw new Error()
      before = { at: parsed.at, id: clean(parsed.id) }
    } catch { throw Object.assign(new Error('INVALID_HISTORY_CURSOR'), { status: 400 }) }
  }
  return { limit, before }
}

/** Scan bounded candidate pages, applying the existing per-account visibility before counting rows. */
async function conversationHistoryPage({ read, visible, options }) {
  const items = []
  let before = options.before
  const batchSize = options.limit + 1
  for (;;) {
    const sources = await read(before, batchSize)
    const candidates = mergeMessageRows(sources.flat()).sort((a, b) => compareHistory(b, a))
    // A sparse source can reach much further back than a busy one. Only consume
    // the interval fully read by every source, otherwise hidden rows can skip history.
    const frontiers = sources.filter(source => source.length === batchSize)
      .map(source => source.at(-1)).filter(row => historyPosition(row))
    const frontier = frontiers.sort((a, b) => compareHistory(b, a))[0]
    const valid = candidates.filter(row => historyPosition(row) && (!frontier || compareHistory(row, frontier) >= 0))
    for (const row of valid) {
      if (visible(row)) items.push(row)
      if (items.length > options.limit) break
    }
    const exhausted = sources.every(source => source.length < batchSize)
    if (items.length > options.limit || exhausted || valid.length === 0) break
    const next = historyPosition(valid.at(-1))
    // Fail closed on a broken reader instead of looping forever at the same cursor.
    if (before && compareHistory({ created_at: next.at, id: next.id }, { created_at: before.at, id: before.id }) >= 0) break
    before = next
  }
  const hasMore = items.length > options.limit
  const page = items.slice(0, options.limit)
  return {
    messages: page.reverse(),
    historyPage: { hasMore, nextCursor: hasMore ? encodeHistoryCursor(page[0]) : null },
  }
}

module.exports = { historyPageOptions, conversationHistoryPage, compareHistory }
