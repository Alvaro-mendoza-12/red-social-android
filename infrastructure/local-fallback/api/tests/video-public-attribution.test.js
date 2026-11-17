const assert = require('node:assert/strict')
const { test } = require('node:test')
const { enrichVideo } = require('../src/data-routes')
const { publicVideoSound } = require('../src/video-public-attribution')

test('legacy video author, artist and generated title do not reveal the login address', () => {
  const email = 'private@example.test'
  const video = enrichVideo({
    id: 'video',
    user_id: 'creator',
    userName: email,
    soundArtist: email,
    soundTitle: `Sonido original — ${email}`,
  }, new Map(), new Map(), new Set())
  assert.equal(video.user_name, 'Usuario')
  assert.equal(video.sound_artist, 'Usuario')
  assert.equal(video.sound_title, 'Sonido original — Usuario')
  assert.doesNotMatch(JSON.stringify(video), /private@example\.test/)
})

test('normal titles, artist credits and public handles are preserved', () => {
  assert.deepEqual(publicVideoSound('Café con lluvia', '@artista', 'Novvin'), {
    soundArtist: '@artista',
    soundTitle: 'Café con lluvia',
  })
})
