const assert = require('assert/strict')
const {
  cleanBaseUrl,
  collectHttpUrls,
  mediaRef,
  parseContentRange,
  positiveInteger,
  responseItems,
  selectImageUrls,
  selectVideoUrl,
  truthy,
} = require('../scripts/verify-social-read-paths')

assert.equal(cleanBaseUrl('https://api.example.test///'), 'https://api.example.test')
assert.throws(() => cleanBaseUrl('file:///tmp/api'), /HTTP or HTTPS/)
assert.equal(positiveInteger('2500', 10), 2500)
assert.equal(positiveInteger('-1', 10), 10)
assert.equal(truthy('yes'), true)
assert.equal(truthy('false', true), false)
assert.deepEqual(responseItems({ items: [{ id: 1 }] }), [{ id: 1 }])
assert.deepEqual(responseItems({ videos: [{ id: 2 }] }), [{ id: 2 }])
assert.deepEqual(parseContentRange('bytes 1048576-2097151/2650851'), {
  start: 1048576,
  end: 2097151,
  total: 2650851,
})
assert.equal(parseContentRange('invalid'), null)

const payload = {
  items: [
    {
      avatar_url: 'https://api.example.test/storage/v1/object/public/avatars/user/profile.jpg',
      video_url: 'https://api.example.test/storage/v1/object/public/videos/user/clip.mp4?v=2',
      thumbnail_url: 'https://api.example.test/storage/v1/object/public/thumbnails/user/clip.jpg',
      unrelated: 'not-a-url',
    },
  ],
}
assert.equal(collectHttpUrls(payload).length, 3)
assert.match(selectVideoUrl(payload), /clip\.mp4/)
assert.deepEqual(selectImageUrls(payload, 3), [
  'https://api.example.test/storage/v1/object/public/avatars/user/profile.jpg',
  'https://api.example.test/storage/v1/object/public/thumbnails/user/clip.jpg',
])
assert.equal(mediaRef(payload.items[0].avatar_url).length, 16)
assert.equal(mediaRef('not-a-url'), 'invalid')

console.log('verify-social-read-paths tests passed')
