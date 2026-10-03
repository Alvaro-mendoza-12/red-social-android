'use strict'

const { randomUUID } = require('node:crypto')
const TYPES = new Set(['text', 'paragraph', 'single', 'multiple', 'dropdown', 'scale', 'number', 'date', 'matrix', 'file'])
const MEASURES = new Set(['nominal', 'ordinal', 'discrete', 'continuous'])
const ROLES = new Set(['descriptive', 'independent', 'dependent', 'control'])
const EDIT_MS = 30 * 60 * 1000
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
function fail(message, status = 400, code = 'SURVEY_INVALID') {
  throw Object.assign(new Error(message), { status, code, expose: true })
}
function text(value, max, required = false) {
  const result = typeof value === 'string' ? value.trim() : ''
  if (result.length > max || (required && !result)) fail(`Texto obligatorio o demasiado largo (máximo ${max}).`)
  return result
}
function list(value, max) {
  if (!Array.isArray(value) || value.length > max) fail(`Lista inválida (máximo ${max}).`)
  return value
}
function number(value, fallback) {
  if (value == null || value === '') return fallback
  const n = Number(value)
  if (!Number.isFinite(n)) fail('Número inválido.')
  return n
}
function identifier(value) {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9_-]{1,80}$/.test(value) || ['__proto__', 'constructor', 'prototype'].includes(value)) fail('Identificador inválido.')
  return value
}
function record(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail('Definición de pregunta, sección u opción inválida.')
  return value
}
function normalizeSchema(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) fail('Falta el formulario.')
  const schema = {
    title: text(input.title, 180, true), description: text(input.description, 4000),
    purpose: text(input.purpose, 3000, true), contact: text(input.contact, 300, true),
    retention: text(input.retention, 800, true), consent: text(input.consent, 3000, true),
    retentionDays: input.retentionDays == null ? null : number(input.retentionDays, null),
    theme: ['violet', 'blue', 'green'].includes(input.theme) ? input.theme : 'violet',
    cover: input.cover ? text(input.cover, 80) : null, coverAlt: text(input.coverAlt, 300),
    confirmation: text(input.confirmation, 1500), estimatedMinutes: Math.max(1, Math.min(120, number(input.estimatedMinutes, 5))),
    sensitive: input.sensitive === true,
    sections: list(input.sections, 30).map(record).map(s => ({ id: identifier(s.id), title: text(s.title, 180, true), description: text(s.description, 2000), image: s.image ? text(s.image, 80) : null, imageAlt: text(s.imageAlt, 300) })),
    questions: [],
  }
  if (schema.retentionDays != null && (!Number.isInteger(schema.retentionDays) || schema.retentionDays < 1 || schema.retentionDays > 3650)) fail('El plazo de conservación debe ser de 1 a 3650 días completos.')
  if (!schema.sections.length || new Set(schema.sections.map(s => s.id)).size !== schema.sections.length) fail('Secciones vacías o repetidas.')
  if ((schema.cover && !uuid(schema.cover)) || schema.sections.some(s => s.image && !uuid(s.image))) fail('Imagen inválida.')
  const sections = schema.sections.map(s => s.id)
  schema.questions = list(input.questions, 100).map(q => {
    record(q)
    if (!TYPES.has(q.type) || !sections.includes(q.section)) fail('Tipo o sección de pregunta inválido.')
    const result = {
      id: identifier(q.id), section: q.section, type: q.type, title: text(q.title, 500, true),
      description: text(q.description, 2000), required: q.required === true,
      measure: MEASURES.has(q.measure) ? q.measure : (['number', 'scale'].includes(q.type) ? 'continuous' : 'nominal'),
      role: ROLES.has(q.role) ? q.role : 'descriptive', image: q.image ? text(q.image, 80) : null,
      treatAsInterval: q.treatAsInterval === true,
      imageAlt: text(q.imageAlt, 300), unit: text(q.unit, 60), precision: Math.max(0, Math.min(8, number(q.precision, 2))),
      minLength: Math.max(0, Math.min(10000, number(q.minLength, 0))),
      minLabel: text(q.minLabel, 120), maxLabel: text(q.maxLabel, 120), hypothesis: text(q.hypothesis, 1500),
      randomize: q.randomize === true, min: number(q.min, null), max: number(q.max, null),
      maxLength: Math.max(1, Math.min(10000, number(q.maxLength, q.type === 'text' ? 500 : 5000))),
      options: [], rows: [],
    }
    if (result.image && !uuid(result.image)) fail('Imagen inválida.')
    if (result.min != null && result.max != null && result.min > result.max) fail('El mínimo supera al máximo.')
    if (['text', 'paragraph'].includes(q.type) && (result.minLength > result.maxLength || !Number.isInteger(result.minLength) || !Number.isInteger(result.maxLength))) fail('Los límites de caracteres deben ser enteros y el mínimo no puede superar al máximo.')
    if (['single', 'multiple', 'dropdown', 'matrix'].includes(q.type)) {
      result.options = list(q.options, 40).map(record).map(o => ({ id: identifier(o.id), label: text(o.label, 300, true), image: o.image ? text(o.image, 80) : null, imageAlt: text(o.imageAlt, 300), next: o.next ? identifier(o.next) : null }))
      if (result.options.length < 2 || new Set(result.options.map(o => o.id)).size !== result.options.length) fail('Se requieren al menos dos opciones diferentes.')
      for (const o of result.options) {
        if (o.image && !uuid(o.image)) fail('Imagen de opción inválida.')
        if (o.next && (!['single', 'dropdown'].includes(q.type) || (o.next !== 'end' && sections.indexOf(o.next) <= sections.indexOf(q.section)))) fail('Los saltos deben ir a una sección posterior o al final.')
      }
      if (q.type === 'multiple' && [result.min, result.max].some(n => n != null && (!Number.isInteger(n) || n < 0 || n > result.options.length))) fail('Los límites de selección deben estar entre cero y el número de opciones.')
    }
    if (q.type === 'matrix') {
      result.rows = list(q.rows, 30).map(record).map(r => ({ id: identifier(r.id), label: text(r.label, 300, true) }))
      if (!result.rows.length || new Set(result.rows.map(r => r.id)).size !== result.rows.length) fail('Filas de matriz inválidas.')
    }
    if (q.type === 'scale') {
      result.min = number(q.min, 1); result.max = number(q.max, 5)
      if (!Number.isInteger(result.min) || !Number.isInteger(result.max) || result.min >= result.max || result.max - result.min > 10) fail('Escala inválida (hasta 11 niveles).')
      result.measure = 'ordinal'
    }
    if (q.type === 'date') {
      result.minDate = text(q.minDate, 10); result.maxDate = text(q.maxDate, 10)
      const validDate = v => !v || (/^\d{4}-\d{2}-\d{2}$/.test(v) && Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 10) === v)
      if (!validDate(result.minDate) || !validDate(result.maxDate) || (result.minDate && result.maxDate && result.minDate > result.maxDate)) fail('El rango de fechas de la pregunta es inválido.')
    }
    return result
  })
  if (!schema.questions.length || new Set(schema.questions.map(q => q.id)).size !== schema.questions.length) fail('Preguntas vacías o repetidas.')
  // Branching is only defined on the last question in a section: no contradictory jumps.
  for (const s of schema.sections) {
    const qs = schema.questions.filter(q => q.section === s.id)
    if (!qs.length) fail('Cada sección necesita una pregunta.')
    if (qs.slice(0, -1).some(q => q.options.some(o => o.next))) fail('Coloca la pregunta con salto al final de su sección.')
  }
  return schema
}
function activeQuestions(schema, answers) {
  const active = []; let index = 0
  while (index < schema.sections.length) {
    const qs = schema.questions.filter(q => q.section === schema.sections[index].id)
    active.push(...qs)
    const last = qs[qs.length - 1]
    const next = last?.options.find(o => o.id === answers[last.id])?.next
    if (next === 'end') break
    index = next ? schema.sections.findIndex(s => s.id === next) : index + 1
  }
  return active
}
function validateAnswers(schema, input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) fail('Respuestas inválidas.')
  if (Object.keys(input).some(id => !schema.questions.some(q => q.id === id))) fail('Pregunta desconocida.')
  const answers = Object.create(null)
  for (const q of activeQuestions(schema, input)) {
    const v = input[q.id]
    const empty = v == null || v === '' || (Array.isArray(v) && !v.length)
    if (empty) {
      if (q.required) fail(`Completa: ${q.title}`, 400, 'SURVEY_REQUIRED')
      answers[q.id] = null; continue
    }
    if (['text', 'paragraph'].includes(q.type)) {
      answers[q.id] = text(v, q.maxLength, true)
      if (answers[q.id].length < q.minLength) fail(`Respuesta demasiado corta: ${q.title}`)
    }
    else if (q.type === 'number' || q.type === 'scale') {
      if (typeof v !== 'number') fail(`Número inválido: ${q.title}`)
      const n = number(v, null)
      if ((q.min != null && n < q.min) || (q.max != null && n > q.max) || ((q.type === 'scale' || q.measure === 'discrete') && !Number.isInteger(n))) fail(`Valor fuera de rango: ${q.title}`)
      answers[q.id] = n
    } else if (['single', 'dropdown'].includes(q.type)) {
      if (!q.options.some(o => o.id === v)) fail(`Opción inválida: ${q.title}`)
      answers[q.id] = v
    } else if (q.type === 'multiple') {
      const selected = list(v, q.options.length)
      if (new Set(selected).size !== selected.length || selected.some(id => !q.options.some(o => o.id === id)) || (q.min != null && selected.length < q.min) || (q.max != null && selected.length > q.max)) fail(`Selección inválida: ${q.title}`)
      answers[q.id] = selected
    } else if (q.type === 'matrix') {
      if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).some(id => !q.rows.some(r => r.id === id))) fail('Matriz inválida.')
      const matrix = Object.create(null)
      for (const r of q.rows) {
        const value = v[r.id] || null
        if ((q.required && !value) || (value && !q.options.some(o => o.id === value))) fail(`Completa ${q.title}: ${r.label}`)
        matrix[r.id] = value
      }
      answers[q.id] = Object.values(matrix).some(value => value != null) ? matrix : null
    } else if (q.type === 'date') {
      if (typeof v !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(v) || !Number.isFinite(Date.parse(v)) || new Date(v).toISOString().slice(0, 10) !== v || (q.minDate && v < q.minDate) || (q.maxDate && v > q.maxDate)) fail(`Fecha inválida: ${q.title}`)
      answers[q.id] = v
    } else if (q.type === 'file') {
      const files = list(v, 3)
      if (files.some(f => !uuid(f))) fail('Archivo inválido.')
      answers[q.id] = [...new Set(files)]
    }
  }
  return answers
}
function deadline(campaign, response) {
  const editEnd = new Date(response.submitted_at).getTime() + EDIT_MS
  return Math.min(editEnd, campaign.closes_at ? new Date(campaign.closes_at).getTime() : Infinity, response.edit_locked_at ? new Date(response.edit_locked_at).getTime() : Infinity)
}
function isOpen(c, now = Date.now()) { return c.status === 'open' && (!c.opens_at || new Date(c.opens_at).getTime() <= now) && (!c.closes_at || new Date(c.closes_at).getTime() > now) }
function quantile(sorted, p) {
  if (!sorted.length) return null
  const i = (sorted.length - 1) * p, lo = Math.floor(i), hi = Math.ceil(i)
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo)
}
function descriptive(values) {
  const x = values.filter(v => typeof v === 'number' && Number.isFinite(v)).sort((a, b) => a - b), n = x.length
  if (!n) return null
  const mean = x.reduce((a, b) => a + b, 0) / n
  const frequencies = new Map(); x.forEach(v => frequencies.set(v, (frequencies.get(v) || 0) + 1))
  const highest = Math.max(...frequencies.values()), modes = [...frequencies].filter(([, f]) => f === highest).map(([v]) => v)
  const q1 = quantile(x, .25), q3 = quantile(x, .75), iqr = q3 - q1
  const outliers = x.filter(v => v < q1 - 1.5 * iqr || v > q3 + 1.5 * iqr), inside = x.filter(v => v >= q1 - 1.5 * iqr && v <= q3 + 1.5 * iqr)
  return { n, mean, median: quantile(x, .5), modes, min: x[0], max: x[n - 1], q1, q3, iqr,
    variance: n > 1 ? x.reduce((a, b) => a + (b - mean) ** 2, 0) / (n - 1) : null,
    sd: n > 1 ? Math.sqrt(x.reduce((a, b) => a + (b - mean) ** 2, 0) / (n - 1)) : null,
    whiskerMin: inside[0], whiskerMax: inside[inside.length - 1], outliers,
  }
}
function analytics(schema, responses) {
  const questions = schema.questions.map(q => {
    const applicable = responses.filter(r => Object.hasOwn(r.answers, q.id))
    const valid = applicable.filter(r => r.answers[q.id] != null)
    const values = valid.map(r => r.answers[q.id]), counts = new Map()
    const add = v => counts.set(String(v), (counts.get(String(v)) || 0) + 1)
    if (!['text', 'paragraph', 'file'].includes(q.type)) {
      values.forEach(v => Array.isArray(v) ? v.forEach(add) : q.type === 'matrix' ? Object.entries(v).filter(([, a]) => a != null).forEach(([row, a]) => add(`${row}:${a}`)) : add(v))
    }
    let keys = [...counts.keys()]
    if (q.options.length && q.type !== 'matrix') keys = q.options.map(o => o.id)
    else if (['ordinal', 'discrete', 'continuous'].includes(q.measure) && ['number', 'scale'].includes(q.type)) keys.sort((a, b) => Number(a) - Number(b))
    let cumulative = 0
    const frequencies = keys.map(key => {
      const f = counts.get(key) || 0; cumulative += f
      const [rowId, optionId] = key.split(':')
      const label = q.type === 'matrix' ? `${q.rows.find(r => r.id === rowId)?.label || rowId}: ${q.options.find(o => o.id === optionId)?.label || optionId}` : q.options.find(o => o.id === key)?.label || key
      return { value: key, label, absolute: f, relative: valid.length ? f / valid.length : 0,
        cumulative: q.measure !== 'nominal' && q.type !== 'multiple' && q.type !== 'matrix' ? cumulative : null }
    })
    const numeric = ['number', 'scale'].includes(q.type) && q.measure !== 'nominal' ? descriptive(values) : null
    if (numeric && q.measure === 'ordinal' && !q.treatAsInterval) {
      numeric.mean = null; numeric.variance = null; numeric.sd = null
    }
    let histogram = []
    if (numeric) {
      const bins = Math.max(1, Math.min(30, Math.ceil(Math.log2(numeric.n) + 1))), width = (numeric.max - numeric.min || 1) / bins
      histogram = Array.from({ length: bins }, (_, i) => ({ low: numeric.min + i * width, high: numeric.min + (i + 1) * width, absolute: 0 }))
      values.forEach(v => histogram[Math.min(bins - 1, Math.floor((v - numeric.min) / width))].absolute++)
      if (numeric.min === numeric.max) histogram = [{ low: numeric.min-.5, high: numeric.max+.5, absolute: numeric.n }]
    }
    return { id: q.id, title: q.title, type: q.type, measure: q.measure, role: q.role, treatAsInterval: q.treatAsInterval, answered: valid.length, missing: applicable.length - valid.length, notApplicable: responses.length - applicable.length, frequencies, descriptive: numeric, histogram }
  })
  const timeline = new Map()
  responses.forEach(r => { const date = new Date(r.submitted_at).toISOString().slice(0, 10); timeline.set(date, (timeline.get(date) || 0) + 1) })
  return { total: responses.length, questions, timeline: [...timeline].sort().map(([date, count]) => ({ date, count })), caveat: 'Muestra voluntaria. Los resultados describen las cuentas participantes; no demuestran causalidad ni representatividad.' }
}
function averageRanks(values) {
  const sorted = values.map((value, index) => ({ value, index })).sort((a, b) => a.value - b.value), ranks = Array(values.length)
  for (let i = 0; i < sorted.length;) {
    let end = i + 1
    while (end < sorted.length && sorted[end].value === sorted[i].value) end++
    const rank = (i + 1 + end) / 2
    for (let j = i; j < end; j++) ranks[sorted[j].index] = rank
    i = end
  }
  return ranks
}
function relations(schema, responses, xId, yId) {
  const x = schema.questions.find(q => q.id === xId), y = schema.questions.find(q => q.id === yId)
  if (!x || !y || xId === yId) fail('Elige dos preguntas diferentes.')
  const pairs = responses.map(r => [r.answers[xId], r.answers[yId]]).filter(p => p.every(v => v != null && (typeof v === 'string' || typeof v === 'number')))
  if (['number', 'scale'].includes(x.type) && ['number', 'scale'].includes(y.type) && x.measure !== 'nominal' && y.measure !== 'nominal') {
    const n = pairs.length, ordinal = [x, y].some(q => (q.measure === 'ordinal' || q.type === 'scale') && !q.treatAsInterval)
    const method = ordinal ? 'spearman' : 'pearson'
    if (!n) return { kind: 'scatter', n: 0, pairs: [], method, coefficient: null, pearson: null, spearman: null, note: 'No hay pares completos para estas preguntas.' }
    const xs = ordinal ? averageRanks(pairs.map(p => p[0])) : pairs.map(p => p[0]), ys = ordinal ? averageRanks(pairs.map(p => p[1])) : pairs.map(p => p[1])
    const dx = descriptive(xs), dy = descriptive(ys)
    const ssx = xs.reduce((s, v) => s + (v - dx.mean) ** 2, 0), ssy = ys.reduce((s, v) => s + (v - dy.mean) ** 2, 0)
    const coefficient = n > 2 && ssx && ssy ? Math.max(-1, Math.min(1, xs.reduce((s, v, i) => s + (v - dx.mean) * (ys[i] - dy.mean), 0) / Math.sqrt(ssx * ssy))) : null
    return { kind: 'scatter', n, pairs, method, coefficient, pearson: method === 'pearson' ? coefficient : null, spearman: method === 'spearman' ? coefficient : null,
      note: ordinal ? 'Spearman sobre rangos medios para empates. Asociación exploratoria sin intervalo de confianza; no demuestra causa y efecto.' : 'Pearson descriptivo para variables tratadas como intervalares. Revisa los supuestos; no demuestra causa y efecto.' }
  }
  const rows = [...new Set(pairs.map(p => String(p[0])))], columns = [...new Set(pairs.map(p => String(p[1])))], n = pairs.length
  if (rows.length > 20 || columns.length > 20) fail('La tabla cruzada admite hasta 20 categorías por variable. Agrupa categorías o exporta los datos para analizarlos.')
  const cells = rows.map(row => columns.map(col => pairs.filter(p => String(p[0]) === row && String(p[1]) === col).length))
  const rowTotals = cells.map(r => r.reduce((a, b) => a + b, 0)), columnTotals = columns.map((_, i) => cells.reduce((s, r) => s + r[i], 0))
  const expected = rows.map((_, r) => columns.map((_, c) => n ? rowTotals[r] * columnTotals[c] / n : 0))
  const chiSquared = n ? cells.reduce((sum, row, r) => sum + row.reduce((s, observed, c) => s + (expected[r][c] ? (observed - expected[r][c]) ** 2 / expected[r][c] : 0), 0), 0) : null
  return { kind: 'crosstab', n, rows, columns, cells, rowTotals, columnTotals, expected, chiSquared,
    degreesOfFreedom: (rows.length - 1) * (columns.length - 1), assumptionsMet: rows.length > 1 && columns.length > 1 && expected.every(r => r.every(e => e >= 5)),
    note: 'Frecuencias de pares completos. Chi cuadrado descriptivo; no se infiere causalidad. Revisa independencia y frecuencias esperadas antes de un contraste.' }
}
module.exports = { TYPES, EDIT_MS, uuid, randomUUID, fail, normalizeSchema, validateAnswers, activeQuestions, deadline, isOpen, descriptive, analytics, relations }
