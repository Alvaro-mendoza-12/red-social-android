'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { singleJob, assertExportBudget } = require('../src/surveys/resources')

test('rechaza trabajos simultáneos y libera el recurso después de éxito o error', async () => {
  const slot = singleJob('BUSY', 'Ocupado')
  let release
  const first = slot(() => new Promise(resolve => { release = resolve }))
  await assert.rejects(slot(async () => {}), error => error.status === 503 && error.code === 'BUSY')
  release('listo'); assert.equal(await first, 'listo')
  await assert.rejects(slot(async () => { throw new Error('Fallo') }), /Fallo/)
  assert.equal(await slot(async () => 5), 5)
})
test('limita celdas y bytes antes de generar documentos', () => {
  const c = { schema: { questions: Array(100).fill({}) } }
  assert.throws(() => assertExportBudget(c, Array(2501).fill({})), error => error.status === 413)
  assert.throws(() => assertExportBudget(c, [{ answers: 'x'.repeat(17*1024*1024) }]), error => error.status === 413)
  assert.doesNotThrow(() => assertExportBudget(c, [{ answers: {} }]))
})
