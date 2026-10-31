const assert = require('node:assert/strict')
const { test } = require('node:test')
const { Pool } = require('pg')
const { profilePostShareCandidates } = require('../src/profile-post-shares')
const { publicFeedPage } = require('../src/public-feed-page')
const url = process.env.PROFILE_SHARE_TEST_DATABASE_URL
if (url && !new URL(url).pathname.startsWith('/novvin_share100_')) throw Error('An isolated share test database is required')

test('PostgreSQL scopes shares to the displayed profile and preserves original authors', { skip: !url }, async () => {
  const db = new Pool({ connectionString: url })
  try {
    await db.query('CREATE TABLE fallback_shares(user_id text, post_id text, content_type text, created_at timestamptz)')
    await db.query("INSERT INTO fallback_shares VALUES ('visitor-a','a-post','post',now()),('profile-b','b-post','post',now()),('profile-b','video','video',now())")
    const groups = await profilePostShareCandidates(db, new Set(['profile-b']), 40, {
      importedShares: async () => [], importedPosts: async () => [],
      localPosts: async ids => ids.map(id => ({ id, user_id: 'original-c' })),
    })
    assert.deepEqual(groups[1].map(row => row.id), ['b-post'])
    assert.equal(groups[1][0].user_id, 'original-c')
  } finally { await db.end() }
})

test('later readable originals remain reachable after hundreds of restricted shares', { skip: !url }, async () => {
  const db = new Pool({ connectionString: url })
  try {
    await db.query("INSERT INTO fallback_shares SELECT 'profile-many','p-'||n,'post',now()-n*interval '1 second' FROM generate_series(0,449) n")
    const read = async count => profilePostShareCandidates(db, new Set(['profile-many']), count, {
      importedShares: async () => [], importedPosts: async () => [],
      localPosts: async ids => ids.filter(id => id !== 'p-301').map(id => ({ id, visibility: Number(id.slice(2)) >= 300 ? 'public' : 'private' })),
    })
    const visible = row => !row.missing_share && row.visibility === 'public'
    const order = rows => rows.sort((a, b) => Number(a.id.slice(2)) - Number(b.id.slice(2)))
    const first = await publicFeedPage({ read, visible, order, offset: 0, limit: 40 })
    const second = await publicFeedPage({ read, visible, order, offset: first.nextOffset, limit: 40 })
    assert.equal(first.items[0].id, 'p-300')
    assert.equal(first.items.length, 40)
    assert.equal(second.items.length, 40)
    assert(first.items.every(row => row.visibility === 'public'))
    assert(!first.items.some(row => second.items.some(other => other.id === row.id)))
  } finally { await db.end() }
})
