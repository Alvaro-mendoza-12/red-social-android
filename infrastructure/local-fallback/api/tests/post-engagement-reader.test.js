'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { createPostEngagementReader } = require('../src/post-engagement-reader')
const { mergePostReactionRows, buildPostReactionState, enrichPost } = require('../src/data-routes')
const identity = { ids: new Set(['viewer', 'viewer-alias']) }
function fixture(overrides = {}) {
  const calls = []
  const options = {
    readImported: async (table, ids) => { calls.push([table, ids]); return [] },
    readReactions: async ids => { calls.push(['localReactions', ids]); return [] },
    readComments: async ids => { calls.push(['localComments', ids]); return [] },
    readShares: async ids => { calls.push(['localShares', ids]); return [] },
    mergeReactions: mergePostReactionRows, projectReactions: buildPostReactionState, ...overrides,
  }
  return { read: createPostEngagementReader(options), calls }
}
test('a selected page has four reactions, two comments and the current viewer state', async () => {
  const f = fixture({
    readReactions: async () => ['viewer', 'b', 'c', 'd'].map((user_id, i) => ({ post_id:'post', user_id, reaction_type:['LIKE','LIKE','FIRE','HAHA'][i] })),
    readComments: async () => [{ id:'one', post_id:'post' }, { id:'two', post_id:'post' }],
  })
  const state = await f.read(['post'], identity)
  const projected = enrichPost({ id:'post', user_id:'creator' }, new Map(), state.likeCounts, state.commentCounts, state.likedPostIds, state.reactionCounts, state.myReactions)
  assert.equal(projected.like_count, 4); assert.equal(projected.comment_count, 2)
  assert.deepEqual(projected.reaction_counts, { LIKE:2,FIRE:1,HAHA:1 })
  assert.equal(projected.my_reaction, 'LIKE'); assert.equal(projected.liked_by_me, true)
})
test('one batch per source handles an entire page and an empty page performs no reads', async () => {
  const f = fixture()
  await f.read([], identity); assert.equal(f.calls.length, 0)
  await f.read(Array.from({length:60},(_,i)=>`POST-${i}`), identity)
  assert.equal(f.calls.length, 7)
  assert.ok(f.calls.every(([, ids]) => ids.length === 60 && ids[0] === 'post-0'))
})
test('canonical aliases, camel-case references and unlike tombstones override imported counts', async () => {
  const f = fixture({
    readImported: async table => table === 'post_likes' ? [{ postId:'POST', user_id:'viewer-alias' }] : [],
    readReactions: async () => [{ post_id:'post', user_id:'viewer', profile_id:'viewer-alias', is_deleted:true }],
  })
  const state = await f.read(['POST'], identity)
  assert.equal(state.likeCounts.get('post'), 0); assert.equal(state.likedPostIds.size, 0)
  assert.equal(enrichPost({id:'POST',like_count:9}, new Map(),state.likeCounts,state.commentCounts,state.likedPostIds).like_count, 0)
})
test('deleted or superseded comments and rows from another page do not inflate counts', async () => {
  const f = fixture({
    readImported: async table => table === 'post_comments' ? [{id:'one',postId:'POST'},{id:'removed',post_id:'post',is_deleted:true}] : [],
    readComments: async () => [{id:'one',post_id:'post'}, {id:'two',post_id:'post'}, {id:'other',post_id:'other'}],
  })
  assert.equal((await f.read(['post'], identity)).commentCounts.get('post'), 2)
})
test('a failed or incomplete read stays an error instead of creating a zero-count snapshot', async () => {
  await assert.rejects(fixture({readComments: async()=>{throw Error('db unavailable')}}).read(['post'],identity), /unavailable/)
  await assert.rejects(fixture({readComments: async()=>null}).read(['post'],identity), /Incomplete/)
  await assert.rejects(fixture().read(Array(201).fill('post'),identity), /Invalid/)
})
