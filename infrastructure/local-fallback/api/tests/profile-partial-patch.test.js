const test = require('node:test')
const assert = require('node:assert/strict')
const { _test: { profilePatchRow } } = require('../src/postgrest-compat-routes')

test('music-only patches do not resubmit a projected legacy username', () => {
  const current = { id: 'own-profile', username: 'Nombre visible distinto', favorite_book: 'Libro' }
  const next = profilePatchRow(current, { favorite_music_url: 'https://example.invalid/owned.wav' })
  assert.equal(Object.hasOwn(next, 'username'), false)
  assert.equal(next.favorite_book, 'Libro')
  assert.equal(next.favorite_music_url, 'https://example.invalid/owned.wav')
  assert.equal(current.username, 'Nombre visible distinto')
})

test('explicit username changes still reach the immutable username guard', () => {
  assert.equal(profilePatchRow({ username: 'permanent' }, { username: 'other' }).username, 'other')
  assert.equal(profilePatchRow({ username: 'permanent' }, { username: '' }).username, '')
})

test('clearing music preserves unrelated profile fields', () => {
  const next = profilePatchRow({ username: 'permanent', favorite_music_url: 'old', biography: 'Bio' },
    { favorite_music_url: '' })
  assert.equal(next.favorite_music_url, '')
  assert.equal(next.biography, 'Bio')
})
