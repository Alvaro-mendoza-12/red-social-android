'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { requireCommunityMusicDuration, MAX_COMMUNITY_MUSIC_DURATION_MS } = require('../src/community-music-policy')
const { mapTrack } = require('../src/music-routes')

test('uploads accept a complete four-minute track and exactly four thirty', () => {
  assert.equal(MAX_COMMUNITY_MUSIC_DURATION_MS, 270000)
  for (const duration of [5000, 15000, 240000, 270000]) assert.equal(requireCommunityMusicDuration(duration), duration)
})

test('excess duration is rejected for user cropping, never silently clamped', () => {
  for (const duration of [270001, 300000, 900000]) {
    assert.throws(() => requireCommunityMusicDuration(duration), { message: 'MUSIC_TRIM_TO_4_30_REQUIRED', status: 400 })
  }
})

test('invalid or short metadata cannot authorize an upload', () => {
  for (const duration of [null, undefined, true, [], '', NaN, Infinity, -1, 4999]) {
    assert.throws(() => requireCommunityMusicDuration(duration), { message: 'VALID_AUDIO_DURATION_REQUIRED', status: 400 })
  }
})

test('catalog retains original duration, complete URL and cover separately from short preview', () => {
  const mapped = mapTrack({ id: 'track', owner_id: 'owner', duration_ms: 240000,
    audio_url: 'https://example.invalid/full.mp3', preview_url: 'https://example.invalid/preview-15s.mp3',
    cover_url: 'https://example.invalid/cover.jpg', tags: [], collaborators: [] })
  assert.equal(mapped.durationMs, 240000)
  assert.equal(mapped.audioUrl, 'https://example.invalid/full.mp3')
  assert.equal(mapped.previewUrl, 'https://example.invalid/preview-15s.mp3')
  assert.equal(mapped.artworkUrl, 'https://example.invalid/cover.jpg')
})
