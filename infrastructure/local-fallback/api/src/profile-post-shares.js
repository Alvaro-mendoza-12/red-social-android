const key = value => String(value || '').trim().toLowerCase()

/** Read the selected profile's shares, never the visitor's recommendation feed. */
async function profilePostShareCandidates(pool, profileIds, limit, { importedShares, importedPosts, localPosts }) {
  const aliases = [...new Set([...profileIds].map(key).filter(Boolean))]
  if (!aliases.length) return [[], []]
  const bound = Math.max(1, Math.min(Math.floor(Number(limit) || 1), 20_000))
  const [legacy, local] = await Promise.all([
    importedShares(aliases, bound),
    pool.query(`SELECT post_id, user_id, content_type, created_at
      FROM fallback_shares
      WHERE lower(user_id) = ANY($1::text[]) AND content_type = 'post'
      ORDER BY created_at DESC, post_id ASC LIMIT $2`, [aliases, bound]).then(result => result.rows),
  ])
  const allowed = new Set(aliases)
  const isPost = row => !row.video_id && (!row.content_type || key(row.content_type) === 'post')
  const target = row => key(row.post_id || row.postId || row.content_id)
  const eligible = row => allowed.has(key(row.user_id || row.userId || row.profile_id)) && isPost(row)
  const ids = [...new Set([...legacy, ...local].filter(eligible).map(target).filter(Boolean))]
  const [oldPosts, newPosts] = ids.length ? await Promise.all([importedPosts(ids), localPosts(ids)]) : [[], []]
  const posts = new Map([...oldPosts, ...newPosts].map(post => [key(post.id), post]))
  const map = row => {
    const id = target(row)
    const post = eligible(row) && posts.get(id)
    // Retain source lengths so missing/private originals cannot hide later pages.
    return post ? { ...post, profile_shared_at: row.created_at } : { id: `missing-share:${id}`, missing_share: true }
  }
  return [legacy.map(map), local.map(map)]
}

function postShareProjection(rows, viewerIds) {
  const mine = new Set(), counts = new Map(), seen = new Set()
  const viewers = new Set([...viewerIds].map(key))
  for (const row of rows) {
    if (row.video_id || (row.content_type && key(row.content_type) !== 'post')) continue
    const post = key(row.post_id || row.postId || row.content_id)
    const actor = key(row.user_id || row.userId || row.profile_id)
    if (!post || !actor) continue
    const own = viewers.has(actor)
    const identity = `${post}:${own ? 'current-viewer' : actor}`
    if (seen.has(identity)) continue
    seen.add(identity)
    counts.set(post, (counts.get(post) || 0) + 1)
    if (own) mine.add(post)
  }
  return { counts, mine }
}

module.exports = { profilePostShareCandidates, postShareProjection }
