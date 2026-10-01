const { test } = require('node:test')
const assert = require('node:assert/strict')
const { normalizeVideoSearch, videoTextMatchesQuery, matchingVisibleCommentVideoIds, escapedSearchLike } = require('../src/video-search-policy')

test('search normalizes hashtags and accents, bounds only the query', () => {
  assert.equal(normalizeVideoSearch(' #MÚSICA '), 'musica')
  assert.equal(normalizeVideoSearch('x'.repeat(150)).length, 120)
  assert.equal(videoTextMatchesQuery({ title: 'Cocina', tags: ['Música'] }, 'musica'), true)
  assert.equal(videoTextMatchesQuery({ description: 'x'.repeat(150) + ' música' }, 'musica'), true)
})

test('comment search includes visible comments and excludes deleted, reported and unrelated videos', () => {
  const rows = [
    { id: 'ok', video_id: 'A', content: '¿Cómo cocinas este plato?' },
    { id: 'reported', video_id: 'b', content: 'cocinas este plato' },
    { id: 'deleted', video_id: 'c', content: 'cocinas este plato', is_deleted: true },
    { id: 'external', video_id: 'd', content: 'cocinas este plato' },
  ]
  assert.deepEqual([...matchingVisibleCommentVideoIds(rows, 'como cocinas', ['a', 'b', 'c'], new Set(['reported']))], ['a'])
  assert.deepEqual([...matchingVisibleCommentVideoIds(rows, 'cocinas', ['a', 'b', 'c'], new Set(['reported']))], ['a'])
  assert.deepEqual([...matchingVisibleCommentVideoIds(rows, '', ['a'])], [])
})

test('SQL wildcard characters are literal search text', () => {
  assert.equal(escapedSearchLike('100%_\\'), '%100\\%\\_\\\\%')
})
