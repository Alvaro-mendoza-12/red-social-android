'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { buildPostReactionState, enrichPost, enrichVideo, mergePostReactionRows, userIdentity } = require('../src/data-routes')

const viewer = userIdentity({ id: 'local-viewer', supabase_user_id: 'imported-viewer' })
function project(post, imported, local = []) {
  const state = buildPostReactionState(mergePostReactionRows(imported, local), viewer)
  return enrichPost(post, new Map(), state.totals, new Map(), new Set(), state.counts, state.mine)
}

test('a stored reaction remains visible when the post keeps its original mixed-case identifier', () => {
  const post = { id: 'MiXeD-Post', user_id: 'author', like_count: 0 }
  const saved = [{ post_id: 'mixed-post', user_id: 'imported-viewer', reaction_type: 'LOVE' }]
  const result = project(post, [], saved)
  assert.equal(result.id, 'MiXeD-Post', 'Do not change the public identifier contract')
  assert.equal(result.my_reaction, 'LOVE')
  assert.equal(result.liked_by_me, true)
  assert.equal(result.like_count, 1)
  assert.deepEqual(result.reaction_counts, { LOVE: 1 })
})

test('camel-case imported post references resolve to the post, not to the reaction record id', () => {
  const result = project({ id: 'post-a', user_id: 'author', like_count: 0 }, [
    { id: 'reaction-record', postId: 'POST-A', userId: 'IMPORTED-VIEWER', reactionType: 'FIRE' },
  ])
  assert.equal(result.my_reaction, 'FIRE')
  assert.equal(result.liked_by_me, true)
  assert.equal(result.like_count, 1)
})

test('unlike tombstones suppress a camel-case imported reaction on every subsequent read', () => {
  const result = project({ id: 'post-a', user_id: 'author', like_count: 0 }, [
    { id: 'reaction-record', postId: 'POST-A', userId: 'LOCAL-VIEWER', reactionType: 'LIKE' },
  ], [{ post_id: 'post-a', user_id: 'local-viewer', is_deleted: true }])
  assert.equal(result.liked_by_me, false)
  assert.equal(result.my_reaction, null)
  assert.equal(result.like_count, 0)
})

test('canonical reactions and alias tombstones are idempotent across clients and rereads', () => {
  const imported = [{ id: 'old-like', postId: 'PoSt-A', userId: 'LOCAL-VIEWER' }]
  const local = [
    { post_id: 'post-a', user_id: 'local-viewer', is_deleted: true },
    { post_id: 'post-a', user_id: 'imported-viewer', reaction_type: 'LIKE', is_deleted: false },
  ]
  for (let read = 0; read < 3; read++) {
    const result = project({ id: 'PoSt-A', user_id: 'author', like_count: 0 }, imported, local)
    assert.equal(result.my_reaction, 'LIKE')
    assert.equal(result.like_count, 1)
    assert.equal(result.liked_by_me, true)
  }
})

test('video likes also use normalized keys while preserving the public mixed-case identifier', () => {
  const result = enrichVideo({ id: 'ViDeO-A', user_id: 'author', like_count: 0 },
    new Map(), new Map([['video-a', 2]]), new Set(['video-a']))
  assert.equal(result.id, 'ViDeO-A')
  assert.equal(result.liked_by_me, true)
  assert.equal(result.like_count, 2)
})
