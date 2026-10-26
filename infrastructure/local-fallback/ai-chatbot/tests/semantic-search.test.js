const assert = require('assert')
const {
  SEMANTIC_SEARCH_MODEL,
  rankSemanticDocuments,
  rankSemanticPage,
} = require('../src/semantic-search')

assert.strictEqual(SEMANTIC_SEARCH_MODEL, 'novvin-semantic-local-v1')

const results = rankSemanticDocuments(
  'alimentación sana',
  [
    {
      id: 'healthy-recipe',
      title: 'Recetas nutritivas',
      text: 'Ideas de cocina para cuidar tu bienestar',
      tags: ['comida'],
    },
    {
      id: 'software',
      title: 'Arquitectura de aplicaciones',
      text: 'Programación y desarrollo de software',
      tags: ['codigo'],
    },
  ],
)

assert.strictEqual(results[0].id, 'healthy-recipe')
assert.strictEqual(results[0].reason, 'semantic_concept')
assert.ok(results[0].score > 0.1)
assert.ok(!results.some((result) => result.id === 'software'))

const typoResults = rankSemanticDocuments(
  'programasion',
  [{ id: 'coding', title: 'Programación para principiantes', text: 'Aprende código', tags: [] }],
)
assert.strictEqual(typoResults[0].id, 'coding')

assert.deepStrictEqual(rankSemanticDocuments('a', [{ id: 'x', title: 'Algo' }]), [])

const many = Array.from({ length: 127 }, (_, index) => ({ id: `video-${String(index).padStart(3, '0')}`, title: 'Cocina saludable' }))
const firstPage = rankSemanticPage('cocina', many, { limit: 50 })
const secondPage = rankSemanticPage('cocina', many, { limit: 50, offset: firstPage.nextOffset })
const lastPage = rankSemanticPage('cocina', many, { limit: 50, offset: secondPage.nextOffset })
assert.strictEqual(firstPage.results.length, 50)
assert.strictEqual(secondPage.results.length, 50)
assert.strictEqual(lastPage.results.length, 27)
assert.strictEqual(new Set([...firstPage.results, ...secondPage.results, ...lastPage.results].map(row => row.id)).size, 127)
assert.strictEqual(lastPage.hasMore, false)
assert.strictEqual(lastPage.nextOffset, null)
assert.deepStrictEqual(rankSemanticPage('cocina', many, { offset: 800 }).results, [])
assert.deepStrictEqual(rankSemanticPage('cocina', many.slice().reverse(), { limit: 50 }), firstPage)
assert.strictEqual(rankSemanticPage('cocina', many, { limit: 1000, offset: -10 }).results.length, 50)

console.log('semantic-search tests passed')
