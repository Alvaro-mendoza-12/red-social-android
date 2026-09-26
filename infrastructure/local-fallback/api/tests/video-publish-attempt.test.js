const assert = require('node:assert/strict')
const { test } = require('node:test')
const { validVideoPublishAttemptId, sameVideoPublishPayload } = require('../src/video-publish-attempt')

const attemptId = '11111111-1111-4111-8111-111111111111'
const stored = {
  video_url: 'https://example.test/video.mp4', title: 'Mi video', description: 'Descripción',
  cover_url: 'https://example.test/cover.jpg', duration_seconds: 12,
  trim_start_seconds: 0, trim_end_seconds: 12,
  is_public: true, is_sensitive: false, tags: ['viaje'],
  allow_clip_sharing: true,
  sound_id: 'canonical-sound', client_requested_sound_id: 'sound-1',
  chapters: [{ start_ms: 0, title: 'Inicio' }],
}
const requested = {
  videoUrl: stored.video_url, title: stored.title, description: stored.description,
  coverUrl: stored.cover_url, durationSeconds: 12, trimStart: 0, trimEnd: 12,
  isPublic: true, isSensitive: false, tags: ['viaje'], soundId: 'sound-1',
  allowClipSharing: true,
  chapters: [{ title: 'Inicio', start_ms: 0 }],
}

test('publish attempt IDs require UUIDs and reject path-like values', () => {
  assert.equal(validVideoPublishAttemptId(attemptId), true)
  assert.equal(validVideoPublishAttemptId('not-a-uuid'), false)
  assert.equal(validVideoPublishAttemptId('../other-account'), false)
})

test('an exact retry returns the publication; changed content conflicts', () => {
  assert.equal(sameVideoPublishPayload(stored, requested), true)
  assert.equal(sameVideoPublishPayload(
    { ...stored, allow_clip_sharing: false },
    { ...requested, allowClipSharing: false },
  ), true)
  assert.equal(sameVideoPublishPayload(null, requested), false)
  for (const changed of [
    { title: 'Otro título' }, { videoUrl: 'https://example.test/other.mp4' },
    { coverUrl: 'https://example.test/other.jpg' }, { soundId: 'sound-2' },
    { isPublic: false }, { tags: ['otro'] }, { durationSeconds: 13 }, { trimStart: 2 },
    { allowClipSharing: false },
    { chapters: [{ title: 'Otro', start_ms: 0 }] },
  ]) {
    assert.equal(sameVideoPublishPayload(stored, { ...requested, ...changed }), false)
  }
})
