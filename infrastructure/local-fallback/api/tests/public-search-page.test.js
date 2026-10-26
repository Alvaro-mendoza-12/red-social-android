const test = require('node:test')
const assert = require('node:assert/strict')
const { searchPageOptions, lexicalSearchPage, publicSearchPage } = require('../src/public-search-page')

const documents = Array.from({ length: 61 }, (_, index) => ({
  id: `video:${String(index).padStart(3, '0')}`, title: 'Cocina saludable', text: 'Recetas de verano', tags: ['nutrición'],
}))

test('bounded paging remains deterministic beyond the first 50 matches', () => {
  const first = lexicalSearchPage('NUTRICION', documents, { limit: 50 })
  const second = lexicalSearchPage('nutrición', documents.slice().reverse(), { limit: 50, offset: first.nextOffset })
  assert.equal(first.results.length, 50)
  assert.equal(second.results.length, 11)
  assert.equal(new Set([...first.results, ...second.results].map(row => row.id)).size, 61)
  assert.equal(second.hasMore, false)
  assert.equal(second.nextOffset, null)
  assert.deepEqual(lexicalSearchPage('inexistente', documents, {}).results, [])
  assert.deepEqual(searchPageOptions({ limit: Infinity, offset: Infinity }), { limit: 24, offset: 0 })
  assert.deepEqual(searchPageOptions({ limit: 100, offset: 900 }), { limit: 50, offset: 800 })
})

test('AI timeout and older service use authorized corpus without broken pagination', async () => {
  for (const rank of [
    async () => { throw Object.assign(new Error('timeout'), { code: 'AI_SERVICE_TIMEOUT' }) },
    async () => ({ results: documents.slice(0, 50) }),
  ]) {
    const page = await publicSearchPage({ query: 'cocina', documents, offset: 50, limit: 50, rank })
    assert.equal(page.results.length, 11)
    assert.equal(page.results[0].id, 'video:050')
    assert.equal(page.model, 'novvin-lexical-fallback-v1')
    assert.equal(page.nextOffset, null)
  }
})

test('never introduces private/unknown IDs, duplicates, or nonprogressing cursors from AI', async () => {
  const page = await publicSearchPage({ query: 'cocina', documents, limit: 20, offset: 20,
    rank: async payload => {
      assert.equal(payload.offset, 20)
      assert.equal(payload.limit, 20)
      return { results: [{ id: 'private' }, { id: 'video:020' }, { id: 'video:020' }], hasMore: true, nextOffset: 20 }
    },
  })
  assert.deepEqual(page.results, [{ id: 'video:020' }])
  assert.equal(page.hasMore, false)
  assert.equal(page.nextOffset, null)
})

test('unexpected failures propagate; corpus sent to AI is bounded at 800', async () => {
  await assert.rejects(publicSearchPage({ query: 'cocina', documents,
    rank: async () => { throw new Error('unexpected database failure') },
  }), /unexpected database failure/)
  await publicSearchPage({ query: 'cocina', documents: Array(1000).fill(documents[0]), rank: async payload => {
    assert.equal(payload.documents.length, 800)
    return { results: [], hasMore: false, nextOffset: null }
  } })
})
