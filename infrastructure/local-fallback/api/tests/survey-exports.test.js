'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const D = require('../src/surveys/domain')
const { exportSurvey, qrExport, exportChart } = require('../src/surveys/exports')
const sharp = require('sharp')
const JSZip = require('jszip')

const campaign = {
  id: '11111111-1111-4111-8111-111111111111', revision: 1, dictionary_revision: 1,
  created_at: '2026-10-03T10:00:00.000Z', closes_at: null, filters: {},
  schema: D.normalizeSchema({ title: 'Estudio Novvin', purpose: 'Consulta', contact: 'responsable@example.org',
    retention: 'Un año', consent: 'Acepto participar', sections: [{ id: 'inicio', title: 'Inicio' }],
    questions: [{ id: 'eleccion', section: 'inicio', type: 'single', title: '¿Cuál eliges?', required: true,
      options: [{ id: 'uno', label: 'Uno' }, { id: 'dos', label: 'Dos' }] }] })
}
const responses = [{ id: '22222222-2222-4222-8222-222222222222', account_id: '33333333-3333-4333-8333-333333333333',
  display_name: 'Participante', submitted_at: '2026-10-03T10:10:00.000Z', updated_at: '2026-10-03T10:10:00.000Z', answers: { eleccion: 'uno' } }]

test('Word y PDF incorporan imágenes privadas del instrumento y su alternativa textual', async () => {
  const imageId = '44444444-4444-4444-8444-444444444444'
  const image = await sharp({create:{width:8,height:4,channels:3,background:'#8064d4'}}).webp().toBuffer()
  const c = structuredClone(campaign)
  c.schema.cover=imageId;c.schema.coverAlt='Portada de ejemplo'
  c.schema.sections[0].image=imageId;c.schema.sections[0].imageAlt='Sección de ejemplo'
  c.schema.questions[0].image=imageId;c.schema.questions[0].imageAlt='Pregunta de ejemplo'
  c.schema.questions[0].options[0].image=imageId;c.schema.questions[0].options[0].imageAlt='Opción de ejemplo'
  c.instrumentImages=new Map([[imageId,image]])
  const word=await exportSurvey(c,[],{format:'docx',instrument:true})
  const zip=await JSZip.loadAsync(Buffer.from(word.data,'base64'))
  assert.ok(Object.keys(zip.files).some(name=>name.startsWith('word/media/') && name.endsWith('.png')))
  const xml=await zip.file('word/document.xml').async('string')
  assert.ok(xml.includes('Portada de ejemplo'));assert.ok(xml.includes('Opción de ejemplo'))
  const pdf=Buffer.from((await exportSurvey(c,[],{format:'pdf',instrument:true})).data,'base64')
  assert.ok(pdf.toString('latin1').includes('/Subtype /Image'))
})

test('Excel, Word, PDF y CSV generan documentos no vacíos', async () => {
  for (const format of ['xlsx', 'docx', 'pdf', 'csv']) {
    const result = await exportSurvey(campaign, responses, { format })
    const bytes = Buffer.from(result.data, 'base64')
    assert.ok(result.name.endsWith(`.${format}`))
    assert.ok(bytes.length > 100, `${format} no debe estar vacío`)
    if (format === 'pdf') assert.equal(bytes.subarray(0, 5).toString(), '%PDF-')
    if (format === 'xlsx' || format === 'docx') assert.equal(bytes.subarray(0, 2).toString(), 'PK')
  }
})

test('el QR móvil se genera en los formatos de descarga', async () => {
  const link = `conectate://survey/${campaign.id}?key=clave-de-prueba`
  for (const format of ['png', 'svg', 'pdf', 'docx']) {
    const result = await qrExport(campaign, link, format)
    assert.ok(result.name.endsWith(`.${format}`))
    assert.ok(Buffer.from(result.data, 'base64').length > 100)
  }
})

test('el propietario puede descargar el gráfico de una pregunta', async () => {
  const question = D.analytics(campaign.schema, responses).questions[0]
  const png = await exportChart(campaign, question, 'png')
  const svg = await exportChart(campaign, question, 'svg')
  assert.equal(Buffer.from(png.data, 'base64').subarray(0, 4).toString('hex'), '89504e47')
  assert.match(Buffer.from(svg.data, 'base64').toString(), /<svg/)
  const pie = await exportChart(campaign, question, 'svg', 'pie')
  assert.match(Buffer.from(pie.data, 'base64').toString(), /<circle/)
  await assert.rejects(exportChart(campaign, question, 'svg', 'box'), /cuantitativos/)
})

test('la descarga conserva el tipo cuantitativo seleccionado en el móvil', async () => {
  const schema = D.normalizeSchema({ ...campaign.schema, questions: [{ id: 'numero', title: 'Medida', section: 'inicio', type: 'number', measure: 'continuous' }] })
  const question = D.analytics(schema, [1, 2, 3, 4, 100].map(value => ({ answers: { numero: value }, submitted_at: '2026-10-03T10:10:00Z' }))).questions[0]
  const c = { ...campaign, schema }
  for (const kind of ['bars', 'sticks', 'histogram', 'box']) {
    const result = await exportChart(c, question, 'png', kind)
    assert.equal(Buffer.from(result.data, 'base64').subarray(0, 4).toString('hex'), '89504e47')
  }
  const box = await exportChart(c, question, 'svg', 'box')
  assert.match(Buffer.from(box.data, 'base64').toString(), /Mediana/)
  await assert.rejects(exportChart(c, question, 'png', 'pie'), /excluyentes/)
  await assert.rejects(exportChart(c, question, 'svg', 'incorrecto'), /inválido/)
})

test('los informes ordinales omiten la media y conservan el método de relación', async () => {
  const schema = D.normalizeSchema({ ...campaign.schema, questions: [
    { id: 'nivel', title: 'Nivel', section: 'inicio', type: 'scale', min: 1, max: 5 },
    { id: 'tiempo', title: 'Tiempo', section: 'inicio', type: 'number' }
  ] })
  const rows = responses.map(row => ({ ...row, answers: { nivel: 3, tiempo: 20 } }))
  for (const format of ['xlsx', 'docx', 'pdf']) {
    const result = await exportSurvey({ ...campaign, schema }, rows, { format, x: 'nivel', y: 'tiempo' })
    assert.ok(Buffer.from(result.data, 'base64').length > 100)
  }
})
