'use strict'
const test = require('node:test'), assert = require('node:assert/strict')
const { publicCreatorVisibility, deniedPublicCreatorIds } = require('../src/public-creator-visibility')
const { publicFeedPage } = require('../src/public-feed-page')
const profile = extra => ({ id: 'creator-local', supabase_user_id: 'creator-public', ...extra })

test('public creators resolve aliases, not display names; unknown authors fail closed', () => {
  const visible = publicCreatorVisibility({ profiles: [profile({ display_name: 'Namesake' })] })
  assert(visible('creator-local')); assert(visible('CREATOR-PUBLIC'))
  assert.equal(visible('Namesake'), false); assert.equal(visible('missing'), false)
})
test('hidden mode denies public access, including to followers, but retains own profile access', () => {
  const profiles = [profile({ break_mode: 'hidden' })]
  assert.equal(publicCreatorVisibility({ profiles, followingIds: ['creator-public'] })('creator-local'), false)
  assert(publicCreatorVisibility({ profiles, viewerIds: ['creator-public'] })('creator-local'))
})
test('private and follower profiles require a followed alias; only-me is always denied to others', () => {
  for (const changes of [{ is_private: true }, { isPrivate: 'true' }, { profile_visibility: 'followers' }, { profileVisibility: 'connections' }]) {
    const profiles = [profile(changes)]
    assert.equal(publicCreatorVisibility({ profiles })('creator-local'), false)
    assert(publicCreatorVisibility({ profiles, followingIds: ['creator-public'] })('creator-local'))
  }
  for (const value of ['only_me', 'nobody', 'invalid']) {
    const profiles = [profile({ profile_visibility: value })]
    assert.equal(publicCreatorVisibility({ profiles, followingIds: ['creator-public'] })('creator-local'), false)
  }
})
test('a denied canonical alias overrides public, followed and own-profile access', () => {
  const visible = publicCreatorVisibility({ profiles: [profile({})], viewerIds: ['creator-public'], deniedIds: ['creator-local'] })
  assert.equal(visible('creator-public'), false)
})
test('safety SQL tests both block directions, active suspension and deletion, using bound identifiers', async () => {
  let inspected = false
  const result = await deniedPublicCreatorIds({ async query(sql, params) {
    inspected = true
    assert(sql.includes('lower(edge.blocker_id)=ANY($1::text[])'))
    assert(sql.includes('lower(edge.blocked_id)=ANY($1::text[])'))
    assert(sql.includes('suspension_end_date)>NOW()')); assert(sql.includes('fallback_deleted_identities'))
    assert.deepEqual(params[0], ['viewer']); assert(params[1].includes('creator-public'))
    return { rows: [{ id: 'CREATOR-LOCAL' }] }
  } }, { profiles: [profile({})], viewerIds: ['VIEWER'] })
  assert(inspected); assert(result.has('creator-local'))
  await assert.rejects(deniedPublicCreatorIds({ query: async () => { throw new Error('DB unavailable') } }, { ownerIds: ['creator'] }), /DB unavailable/)
})
test('privacy filtering preserves raw exhaustion counts and continues beyond 270 hidden posts', async () => {
  const profiles = [profile({ break_mode: 'hidden' }), { id: 'public' }]
  const visible = publicCreatorVisibility({ profiles })
  const rows = Array.from({ length: 650 }, (_, i) => ({ id: 'post-'+i, owner: i<270?'creator-public':'public', rank: i }))
  const windows = [], collected = []
  let offset = 0, hasMore
  do {
    const page = await publicFeedPage({ read: async n => { windows.push(n); return [rows.slice(0,n)] },
      visible: row => visible(row.owner), order: values => values.sort((a,b)=>a.rank-b.rank), offset, limit: 60 })
    collected.push(...page.items.map(row=>row.id));hasMore=page.hasMore;offset=page.nextOffset
  } while(hasMore)
  assert.equal(collected.length,380); assert.equal(new Set(collected).size,380)
  assert.equal(collected[0],'post-270');assert(windows.includes(480))
})

test('real public-profile normalization preserves canonical and local safety identities', () => {
  const { normalizeProfileForPublic } = require('../src/data-routes')
  const normalized=normalizeProfileForPublic({id:'creator-local',supabase_user_id:'creator-public'})
  assert.equal(normalized.id,'creator-public');assert.equal(normalized.user_id,'creator-local')
  const publicVisible=publicCreatorVisibility({profiles:[normalized]})
  assert(publicVisible('creator-local'));assert(publicVisible('creator-public'))
  const denied=publicCreatorVisibility({profiles:[normalized],deniedIds:['creator-local']})
  assert.equal(denied('creator-public'),false)
  const followed=publicCreatorVisibility({profiles:[{...normalized,profile_visibility:'followers'}],followingIds:['creator-public']})
  assert(followed('creator-local'))
})
