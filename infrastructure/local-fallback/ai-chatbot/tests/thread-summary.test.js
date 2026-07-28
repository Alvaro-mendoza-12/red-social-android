const assert = require('assert')
const {
  THREAD_SUMMARY_MODEL,
  THREAD_SUMMARY_NOTICE,
  summarizeThread,
} = require('../src/thread-summary')

const comments = [
  { id: 'c1', authorName: 'Ana', content: 'La actividad debería comenzar a las ocho para que todos lleguen.' },
  { id: 'c2', authorName: 'Luis', content: 'Prefiero iniciar a las nueve porque el transporte llega tarde.' },
  { id: 'c3', authorName: 'Mara', content: 'Podemos grabar la sesión y compartir el material con quienes falten.' },
  { id: 'c4', authorName: 'Joel', content: 'También propongo compartir el material y una lista de acuerdos.' },
]

const first = summarizeThread(comments, { maxBullets: 3 })
const second = summarizeThread(comments, { maxBullets: 3 })

assert.strictEqual(first.kind, 'extractive')
assert.strictEqual(first.model, THREAD_SUMMARY_MODEL)
assert.strictEqual(first.notice, THREAD_SUMMARY_NOTICE)
assert.strictEqual(first.commentCount, 4)
assert.deepStrictEqual(first, second)
assert.ok(first.bullets.length >= 2)
assert.ok(first.bullets.length <= 3)
for (const bullet of first.bullets) {
  assert.strictEqual(bullet.sourceIds.length, 1)
  const source = comments.find((comment) => comment.id === bullet.sourceIds[0])
  assert.ok(source)
  assert.ok(source.content.startsWith(bullet.text) || bullet.text.startsWith(source.content))
}

assert.throws(
  () => summarizeThread(comments.slice(0, 2)),
  (error) => error.code === 'THREAD_SUMMARY_NOT_ENOUGH_CONTENT' && error.status === 422,
)

console.log('thread-summary tests passed')
