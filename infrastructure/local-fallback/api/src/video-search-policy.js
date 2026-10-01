function normalizeVideoSearch(value, limit = 120) {
  return String(value || '').trim().replace(/^#/, '').normalize('NFD')
    .replace(/\p{M}+/gu, '').toLowerCase().slice(0, limit)
}

function videoTextMatchesQuery(row, query) {
  const needle = normalizeVideoSearch(query)
  if (needle.length < 2) return false
  return normalizeVideoSearch([
    row.title || row.caption || '', row.description || row.content || '',
    ...(Array.isArray(row.tags) ? row.tags : []),
  ].join(' '), Infinity).includes(needle)
}

function matchingVisibleCommentVideoIds(rows, query, videoIds, reportedIds = new Set()) {
  const needle = normalizeVideoSearch(query)
  const candidates = new Set(Array.from(videoIds, (id) => String(id).toLowerCase()))
  const result = new Set()
  if (needle.length < 2) return result
  for (const row of rows) {
    const id = String(row.id || '').toLowerCase()
    const videoId = String(row.video_id || row.videoId || row.content_id || row.post_id || '').toLowerCase()
    if (!candidates.has(videoId) || reportedIds.has(id)) continue
    if ([true, 'true', 1, '1'].includes(row.is_deleted) || [true, 'true', 1, '1'].includes(row.deleted)) continue
    if (normalizeVideoSearch(row.content || row.text || row.message || '', Infinity).includes(needle)) result.add(videoId)
  }
  return result
}

function escapedSearchLike(query) {
  return `%${normalizeVideoSearch(query).replace(/[\\%_]/g, '\\$&')}%`
}

module.exports = { normalizeVideoSearch, videoTextMatchesQuery, matchingVisibleCommentVideoIds, escapedSearchLike }
