const assert = require('node:assert/strict')
const { test } = require('node:test')
const { normalizeVideoChapters } = require('../src/video-chapters')

test('video chapters keep ordered millisecond positions inside duration', () => {
  assert.deepEqual(normalizeVideoChapters([
    { title: 'Inicio', start_ms: 0 }, { title: 'Ejemplo', startMs: 12_500 },
  ], 20).chapters, [
    { title: 'Inicio', start_ms: 0 }, { title: 'Ejemplo', start_ms: 12_500 },
  ])
  assert.deepEqual(normalizeVideoChapters(null, 20).chapters, [])
})

test('video chapters reject duplicates, reordering, invalid titles, excess and out-of-range time', () => {
  for (const value of [
    [{ title: 'Inicio', start_ms: 20_000 }],
    [{ title: 'Inicio', start_ms: -1 }],
    [{ title: 'Inicio', start_ms: 1.5 }],
    [{ title: 'Inicio', start_ms: '1000' }],
    [{ title: 'Inicio', start_ms: 1 }, { title: 'Otro', start_ms: 1 }],
    [{ title: 'Otro', start_ms: 2 }, { title: 'Inicio', start_ms: 1 }],
    [{ title: '', start_ms: 0 }],
    [{ title: 'Línea\nnueva', start_ms: 0 }],
    Array.from({ length: 51 }, (_, i) => ({ title: String(i), start_ms: i })),
  ]) {
    assert.equal(normalizeVideoChapters(value, 20).error, 'INVALID_VIDEO_CHAPTERS')
  }
})
