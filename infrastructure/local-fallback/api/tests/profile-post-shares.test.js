const assert = require('node:assert/strict')
const { profilePostShareCandidates, postShareProjection } = require('../src/profile-post-shares')
const { publicFeedPage } = require('../src/public-feed-page')

async function run() {
  const state = postShareProjection([
    { post_id: 'p', user_id: 'old-a' }, { post_id: 'p', user_id: 'new-a', content_type: 'post' },
    { post_id: 'p', user_id: 'b' }, { post_id: 'p', user_id: 'b' },
    { post_id: 'p', user_id: 'c', content_type: 'video' },
  ], new Set(['old-a', 'new-a']))
  assert.equal(state.counts.get('p'), 2)
  assert(state.mine.has('p'))
  assert.equal(postShareProjection([], new Set(['a'])).mine.size, 0)
  const rows = [{ post_id: 'old-post', user_id: 'profile-b', content_type: 'post', created_at: '2026-10-30' }]
  let query
  const pool = { query: async (sql, params) => { query = { sql, params }; return { rows } } }
  const reads = { importedShares: async () => [], importedPosts: async () => [],
    localPosts: async ids => ids.map(id => ({ id, user_id: 'author-c', content: 'Original', visibility: 'public' })) }
  const groups = await profilePostShareCandidates(pool, ['Profile-B'], 40, reads)
  assert.deepEqual(query.params, [['profile-b'], 40])
  assert.match(query.sql, /WHERE lower\(user_id\) = ANY/)
  assert.match(query.sql, /content_type = 'post'/)
  assert.equal(groups[1][0].id, 'old-post')
  assert.equal(groups[1][0].user_id, 'author-c')

  const mixed = { ...reads, importedShares: async () => [
    { post_id: 'visitor-post', user_id: 'visitor-a' },
    { post_id: 'video-1', user_id: 'profile-b', content_type: 'video' },
    { post_id: 'deleted', user_id: 'profile-b' },
    { post_id: 'old-post', user_id: 'profile-b' },
  ] }
  const filtered = await profilePostShareCandidates(pool, ['profile-b'], 40, mixed)
  assert.equal(filtered[0].length, 4)
  assert.deepEqual(filtered[0].map(row => !row.missing_share), [false, false, true, true])
  const gone = await profilePostShareCandidates(pool, ['profile-b'], 40, { ...mixed, localPosts: async () => [] })
  assert(gone.flat().every(row => row.missing_share))

  let calls = 0
  const page = await publicFeedPage({ offset: 0, limit: 2,
    read: async limit => {
      calls++
      const shares = Array.from({ length: 400 }, (_, i) => ({ post_id: `p-${i}`, user_id: 'profile-b' })).slice(0, limit)
      return profilePostShareCandidates({ query: async () => ({ rows: shares }) }, ['profile-b'], limit,
        { ...reads, localPosts: async ids => ids.map(id => ({ id, visibility: Number(id.slice(2)) < 300 ? 'private' : 'public' })) })
    },
    visible: row => !row.missing_share && row.visibility === 'public', order: rows => rows,
  })
  assert.equal(page.items.length, 2)
  assert(page.items.every(row => row.visibility === 'public'))
  assert(page.hasMore)
  assert(calls > 1, 'Hidden originals must not falsely exhaust the profile')
  console.log('profile post shares: scoped owner, original identity, media type, missing originals and paginated visibility passed')
}
run().catch(error => { console.error(error); process.exitCode = 1 })
