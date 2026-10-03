'use strict'

// Read larger candidate windows when privacy filtering leaves a short page.
// Bounds match the existing feed readers; no unbounded collection scan.
async function publicFeedPage({ read, visible, order, offset, limit, maxCandidates = 10000 }) {
  let window = Math.min(maxCandidates, Math.max(240, offset + (limit * 4)))
  for (;;) {
    const groups = await read(window)
    const byId = new Map()
    for (const row of groups.flat()) {
      const id = String(row.id || '')
      if (id) byId.set(id, row)
    }
    // Local rows supersede imported snapshots before applying visibility.
    const rows = order([...byId.values()].filter(visible)
      .sort((a, b) => String(a.id).localeCompare(String(b.id))))
    const exhausted = groups.every(group => group.length < window)
    if (rows.length > offset + limit || exhausted || window === maxCandidates) {
      const items = rows.slice(offset, offset + limit)
      const hasMore = rows.length > offset + limit
      return { items, hasMore, nextOffset: hasMore ? offset + items.length : null,
        candidateLimitReached: !exhausted && window === maxCandidates }
    }
    window = Math.min(maxCandidates, window * 2)
  }
}

module.exports = { publicFeedPage }
