'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const D = require('../src/surveys/domain')

function schema() {
  return D.normalizeSchema({
    title: 'Encuesta de campus', purpose: 'Estudio académico', contact: 'investigador@example.org',
    retention: 'Se conservan durante un año.', consent: 'Acepto participar.',
    sections: [{ id: 'inicio', title: 'Inicio' }, { id: 'detalle', title: 'Detalle' }],
    questions: [
      { id: 'participa', section: 'inicio', type: 'single', title: '¿Participas?', required: true,
        options: [{ id: 'si', label: 'Sí', next: 'detalle' }, { id: 'no', label: 'No', next: 'end' }] },
      { id: 'edad', section: 'detalle', type: 'number', title: 'Edad', required: true, min: 18, max: 99 }
    ]
  })
}

test('los saltos excluyen preguntas omitidas y validan las activas', () => {
  const form = schema()
  assert.deepEqual({ ...D.validateAnswers(form, { participa: 'no', edad: 45 }) }, { participa: 'no' })
  assert.throws(() => D.validateAnswers(form, { participa: 'si' }), /Completa: Edad/)
  assert.deepEqual({ ...D.validateAnswers(form, { participa: 'si', edad: 25 }) }, { participa: 'si', edad: 25 })
})

test('rechaza saltos hacia secciones anteriores', () => {
  const form = schema()
  form.questions[1].type = 'single'
  form.questions[1].options = [{ id: 'volver', label: 'Volver', next: 'inicio' }, { id: 'seguir', label: 'Seguir' }]
  assert.throws(() => D.normalizeSchema(form), /saltos deben ir a una sección posterior/)
})

test('la edición vence a los 30 minutos o al cierre, lo primero que ocurra', () => {
  const start = new Date('2026-10-03T10:00:00.000Z')
  const response = { submitted_at: start }
  assert.equal(D.deadline({ closes_at: null }, response), start.getTime() + 30 * 60 * 1000)
  assert.equal(D.deadline({ closes_at: '2026-10-03T10:12:00.000Z' }, response), start.getTime() + 12 * 60 * 1000)
})

test('las relaciones sin pares completos no rompen el resumen', () => {
  const result = D.relations({ questions: [{ id: 'x', type: 'number' }, { id: 'y', type: 'number' }] }, [], 'x', 'y')
  assert.equal(result.kind, 'scatter')
  assert.equal(result.n, 0)
  assert.equal(result.pearson, null)
})

test('los textos abiertos no se publican como categorías del resumen', () => {
  const form = schema()
  form.questions.push({ id: 'comentario', section: 'detalle', type: 'paragraph', title: 'Comentario', options: [], rows: [], measure: 'nominal', role: 'descriptive' })
  const result = D.analytics(form, [{ submitted_at: '2026-10-03T10:00:00.000Z', answers: { participa: 'si', edad: 25, comentario: 'dato privado' } }])
  const commentary = result.questions.find(question => question.id === 'comentario')
  assert.equal(commentary.answered, 1)
  assert.deepEqual(commentary.frequencies, [])
})

test('rechaza validaciones contradictorias antes de guardar una planilla', () => {
  const single = question => ({ ...schema(), sections: [{ id: 'inicio', title: 'Inicio' }], questions: [{ id: 'pregunta', section: 'inicio', title: 'Pregunta', ...question }] })
  assert.throws(() => D.normalizeSchema(single({ type: 'text', minLength: 20, maxLength: 10 })), /límites de caracteres/)
  assert.throws(() => D.normalizeSchema(single({ type: 'multiple', min: 3, options: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }] })), /límites de selección/)
  assert.throws(() => D.normalizeSchema(single({ type: 'date', minDate: '2026-02-31' })), /rango de fechas/)
  assert.throws(() => D.normalizeSchema(single({ type: 'date', minDate: '2026-05-01', maxDate: '2026-04-01' })), /rango de fechas/)
})

test('las matrices omiten filas vacías y presentan etiquetas legibles en los resúmenes', () => {
  const form = D.normalizeSchema({ ...schema(), sections: [{ id: 'inicio', title: 'Inicio' }], questions: [
    { id: 'matriz', section: 'inicio', title: 'Satisfacción', type: 'matrix', rows: [{ id: 'curso', label: 'Curso' }], options: [{ id: 'si', label: 'Sí' }, { id: 'no', label: 'No' }] }
  ] })
  assert.equal(D.validateAnswers(form, { matriz: { curso: null } }).matriz, null)
  const result = D.analytics(form, [{ submitted_at: '2026-10-03T10:00:00Z', answers: { matriz: { curso: 'si' } } }])
  assert.equal(result.questions[0].frequencies[0].label, 'Curso: Sí')
})

test('las escalas ordinales usan Spearman con rangos medios para empates', () => {
  const form = D.normalizeSchema({ ...schema(), sections: [{ id: 'inicio', title: 'Inicio' }], questions: [
    { id: 'nivel', section: 'inicio', title: 'Nivel', type: 'scale', min: 1, max: 5 },
    { id: 'tiempo', section: 'inicio', title: 'Tiempo', type: 'number', measure: 'continuous' }
  ] })
  const responses = [[1, 30], [1, 30], [3, 10]].map(([nivel, tiempo]) => ({ submitted_at: '2026-10-03T10:00:00Z', answers: { nivel, tiempo } }))
  assert.equal(D.analytics(form, responses).questions[0].descriptive.mean, null)
  const relation = D.relations(form, responses, 'nivel', 'tiempo')
  assert.equal(relation.method, 'spearman'); assert.equal(relation.coefficient, -1)
  assert.equal(relation.pearson, null)
  form.questions[0].treatAsInterval = true
  assert.ok(D.analytics(form, responses).questions[0].descriptive.mean > 1)
  assert.equal(D.relations(form, responses, 'nivel', 'tiempo').method, 'pearson')
  form.questions[1].measure = 'nominal'
  assert.equal(D.analytics(form, responses).questions[1].descriptive, null)
  assert.equal(D.relations(form, responses, 'nivel', 'tiempo').kind, 'crosstab')
})

test('rechaza objetos incompletos e identificadores reservados con un error controlado', () => {
  for (const field of ['sections', 'questions']) {
    assert.throws(() => D.normalizeSchema({ ...schema(), [field]: [null] }), error => error.status === 400)
  }
  for (const reserved of ['__proto__', 'constructor', 'prototype']) {
    const form = schema(); form.questions[0].id = reserved
    assert.throws(() => D.normalizeSchema(form), /Identificador inválido/)
  }
  const form = schema(); form.questions[0].options = [null, null]
  assert.throws(() => D.normalizeSchema(form), error => error.status === 400)
})
