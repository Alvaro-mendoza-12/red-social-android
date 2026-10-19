'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { postsWithCurrentProfileAvatar } = require('../src/data-routes')

test('own profile posts use the current avatar while preserving content and counts', () => {
  const posts = [{ id: 'post', user_profile_image: 'old-avatar', content: 'Texto', like_count: 7 }]
  const result = postsWithCurrentProfileAvatar(posts, { avatar_url: 'current-avatar' })
  assert.deepEqual(result, [{ ...posts[0], user_profile_image: 'current-avatar' }])
  assert.equal(posts[0].user_profile_image, 'old-avatar')
  assert.notEqual(result[0], posts[0])
})

test('removing the avatar does not revive an obsolete post snapshot', () => {
  assert.equal(postsWithCurrentProfileAvatar([{ user_profile_image: 'old-avatar' }], { avatar_url: '' })[0].user_profile_image, '')
})

test('an account without posts keeps an empty list', () => {
  assert.deepEqual(postsWithCurrentProfileAvatar([], { avatar_url: 'current-avatar' }), [])
})
