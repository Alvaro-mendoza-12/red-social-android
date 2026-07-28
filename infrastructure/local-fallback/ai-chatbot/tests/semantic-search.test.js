const assert = require('assert')
const {
  SEMANTIC_SEARCH_MODEL,
  rankSemanticDocuments,
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

console.log('semantic-search tests passed')
