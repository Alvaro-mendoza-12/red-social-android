'use strict'
const ExcelJS = require('exceljs')
const PDFDocument = require('pdfkit')
const QR = require('qrcode')
const sharp = require('sharp')
const { Document, Packer, Paragraph, TextRun, HeadingLevel, ImageRun } = require('docx')
const { analytics, relations, fail } = require('./domain')
const xml = value => String(value).replace(/[<>&"']/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[c])
const safeCell = value => typeof value === 'string' && /^\s*[=+@-]/.test(value) ? `'${value}` : value
const csvCell = value => `"${String(safeCell(value) ?? '').replace(/"/g, '""')}"`
const filename = c => c.schema.title.normalize('NFKD').replace(/[^a-zA-Z0-9 _-]/g, '').trim().slice(0, 70) || 'encuesta'
const envelope = (name, mime, bytes) => {
  if (Buffer.byteLength(bytes)>24*1024*1024) fail('El archivo supera el tamaño de una descarga móvil. Reduce los filtros o las imágenes.', 413, 'SURVEY_EXPORT_LIMIT')
  return { name, mime, data: Buffer.from(bytes).toString('base64') }
}
const intervalNote = q => q.measure === 'ordinal' ? (q.treatAsInterval ? 'Supuesto declarado: puntos tratados como intervalares por el investigador.' : 'Escala ordinal: media y desviación estándar no aplicables.') : ''
async function instrumentImage(c, id) {
  const source = id && c.instrumentImages?.get(id)
  if (!source) return null
  const { data, info } = await sharp(source).resize(640,480,{ fit:'inside',withoutEnlargement:true }).png().toBuffer({ resolveWithObject: true })
  return { data, width: info.width, height: info.height }
}
function displayAnswer(q, value) {
  if (value == null) return ''
  if (Array.isArray(value)) return value.map(v => q.options.find(o => o.id === v)?.label || v).join('; ')
  if (typeof value === 'object') return q.rows.map(r => `${r.label}: ${q.options.find(o => o.id === value[r.id])?.label || ''}`).join('; ')
  return q.options.find(o => o.id === value)?.label || value
}
function dataRows(c, responses, identity) {
  const header = ['Respuesta', 'Enviada UTC', 'Actualizada UTC', ...(identity ? ['Cuenta', 'Nombre'] : []), ...c.schema.questions.flatMap(q => [q.title, `${q.title} — estado`])]
  const rows = responses.map(r => [r.id, new Date(r.submitted_at).toISOString(), new Date(r.updated_at).toISOString(), ...(identity ? [r.account_id, r.display_name || ''] : []),
    ...c.schema.questions.flatMap(q => [displayAnswer(q, r.answers[q.id]), !Object.hasOwn(r.answers, q.id) ? 'No aplicable por salto' : r.answers[q.id] == null ? 'Sin respuesta' : 'Respondida'])])
  return [header, ...rows]
}
function chartSvg(q, kind = q.descriptive && q.measure === 'continuous' ? 'histogram' : 'bars') {
  if (!['bars', 'sticks', 'histogram', 'box', 'pie'].includes(kind)) fail('Tipo de gráfico inválido.')
  if (['box', 'histogram', 'sticks'].includes(kind) && !q.descriptive) fail('Este gráfico requiere datos cuantitativos.')
  if (kind === 'pie' && !['single', 'dropdown', 'scale'].includes(q.type)) fail('El gráfico de pastel requiere categorías mutuamente excluyentes.')
  const wrap = (body, height) => `<svg xmlns="http://www.w3.org/2000/svg" width="700" height="${height}" viewBox="0 0 700 ${height}"><rect width="700" height="${height}" fill="white"/><text x="12" y="25" font-family="sans-serif" font-size="16" fill="#172033">${xml(q.title.slice(0, 70))}</text>${body}</svg>`
  if (kind === 'histogram' || kind === 'sticks') {
    const source = kind === 'histogram' ? q.histogram : q.frequencies
    const entries = source.slice(0,40), low = kind === 'histogram' ? entries[0].low : Number(entries[0].value)
    const high = kind === 'histogram' ? entries.at(-1).high : Number(entries.at(-1).value)
    const max = Math.max(1,...entries.map(v=>v.absolute)), x = v => high===low ? 370 : 55+610*(v-low)/(high-low)
    const shapes = entries.map(v=>{
      const y=260-200*v.absolute/max
      return kind==='histogram' ? `<rect x="${x(v.low)}" y="${y}" width="${Math.max(1,x(v.high)-x(v.low))}" height="${260-y}" fill="#8064d4" stroke="white" stroke-width=".5"/>`
        : `<line x1="${x(Number(v.value))}" y1="260" x2="${x(Number(v.value))}" y2="${y}" stroke="#8064d4" stroke-width="2"/><circle cx="${x(Number(v.value))}" cy="${y}" r="3" fill="#8064d4"/>`
    }).join('')
    const labels = Array.from({length:5},(_,i)=>{const value=low+(high-low)*i/4;return `<text x="${55+610*i/4}" y="285" text-anchor="middle" font-family="sans-serif" font-size="12">${Number(value.toFixed(3))}</text>`}).join('')
    return wrap(`<path d="M55 50 V260 H665" fill="none" stroke="#172033"/><text x="12" y="50" font-family="sans-serif" font-size="12">${max}</text><text x="20" y="260" font-family="sans-serif" font-size="12">0</text>${shapes}${labels}<text x="12" y="315" font-family="sans-serif" font-size="12">Frecuencia absoluta · ${kind==='histogram' ? 'Intervalos contiguos; último intervalo incluye el extremo superior.' : 'Valor numérico'}</text>${source.length>40?`<text x="12" y="340" font-family="sans-serif" font-size="12">Primeros 40 de ${source.length} valores. El archivo de datos contiene todos.</text>`:''}`,source.length>40?360:335)
  }
  if (kind === 'box') {
    const d = q.descriptive, x = v => d.max === d.min ? 350 : 35 + 620 * (v - d.min) / (d.max - d.min)
    return wrap(`<line x1="${x(d.whiskerMin)}" y1="100" x2="${x(d.whiskerMax)}" y2="100" stroke="#172033"/><rect x="${x(d.q1)}" y="70" width="${Math.max(2, x(d.q3) - x(d.q1))}" height="60" fill="#8064d4"/><line x1="${x(d.median)}" y1="70" x2="${x(d.median)}" y2="130" stroke="#172033" stroke-width="3"/>${[d.whiskerMin, d.whiskerMax].map(v => `<line x1="${x(v)}" y1="80" x2="${x(v)}" y2="120" stroke="#172033"/>`).join('')}${d.outliers.map(v => `<circle cx="${x(v)}" cy="100" r="4" fill="#172033"/>`).join('')}<text x="35" y="160" font-family="sans-serif" font-size="13">${d.min}</text><text x="600" y="160" font-family="sans-serif" font-size="13">${d.max}</text><text x="12" y="190" font-family="sans-serif" font-size="12">Q1 ${d.q1} · Mediana ${d.median} · Q3 ${d.q3} · Bigotes: 1,5 × RIC</text>`, 215)
  }
  if (kind === 'pie') {
    const entries = q.frequencies.filter(v => v.absolute > 0)
    if (entries.length > 20) fail('Hay demasiadas categorías para un gráfico de pastel legible. Elige barras.')
    const total = entries.reduce((sum, v) => sum + v.absolute, 0), colors = ['#8064d4', '#398fbb', '#2d9670', '#dc9950', '#c65d87', '#777ab8']
    let angle = -Math.PI / 2
    const slices = entries.map((v, i) => {
      const start = angle; angle += 2 * Math.PI * v.absolute / total
      const color = colors[i % colors.length], shape = v.absolute === total ? `<circle cx="185" cy="215" r="135" fill="${color}"/>` : `<path d="M185 215 L${185 + 135 * Math.cos(start)} ${215 + 135 * Math.sin(start)} A135 135 0 ${angle - start > Math.PI ? 1 : 0} 1 ${185 + 135 * Math.cos(angle)} ${215 + 135 * Math.sin(angle)} Z" fill="${color}"/>`
      return `${shape}<rect x="355" y="${48 + i * 25}" width="12" height="12" fill="${color}"/><text x="375" y="${59 + i * 25}" font-family="sans-serif" font-size="12">${xml(v.label.slice(0, 28))}: ${v.absolute} (${(100 * v.absolute / total).toFixed(1)}%)</text>`
    }).join('')
    return wrap(slices, Math.max(385, 80 + entries.length * 25))
  }
  const allEntries = kind === 'histogram' ? q.histogram.map(b => ({ label: `${b.low.toFixed(2)}–${b.high.toFixed(2)}`, absolute: b.absolute })) : q.frequencies
  const entries = allEntries.slice(0, 40), max = Math.max(1, ...entries.map(v => v.absolute)), height = Math.max(120, 85 + entries.length * 30)
  return wrap(`${entries.map((v, i) => `<text x="12" y="${60 + i * 30}" font-family="sans-serif" font-size="13" fill="#172033">${xml(v.label.slice(0, 25))}</text><rect x="225" y="${44 + i * 30}" width="${400 * v.absolute / max}" height="${kind === 'sticks' ? 5 : 22}" rx="${kind === 'sticks' ? 0 : 4}" fill="#6750c8"/><text x="${235 + 400 * v.absolute / max}" y="${60 + i * 30}" font-family="sans-serif" font-size="13">${v.absolute}</text>`).join('')}${allEntries.length > 40 ? `<text x="12" y="${height - 10}" font-family="sans-serif" font-size="12">Se muestran 40 de ${allEntries.length} categorías. El archivo de datos contiene todas.</text>` : ''}`, height)
}
async function chartPng(q, kind) { return sharp(Buffer.from(chartSvg(q, kind))).png().toBuffer() }
async function exportChart(c, question, format, kind) {
  if (!question.answered) fail('No hay respuestas analizables para este gráfico con los filtros actuales.')
  if (!question.frequencies.length && !question.histogram.length) fail('Esta pregunta no tiene categorías para un gráfico.')
  if (format === 'svg') return envelope(`${filename(c)}-${question.id}-grafico.svg`, 'image/svg+xml', chartSvg(question, kind))
  if (format === 'png') return envelope(`${filename(c)}-${question.id}-grafico.png`, 'image/png', await chartPng(question, kind))
  fail('Formato de gráfico inválido.')
}
const dictRows = c => [['ID', 'Pregunta', 'Tipo', 'Medición', 'Rol', 'Unidad', 'Hipótesis', 'Obligatoria', 'Opciones/códigos', 'Puntos tratados como intervalares'], ...c.schema.questions.map(q => [q.id, q.title, q.type, q.measure, q.role, q.unit || '', q.hypothesis || '', q.required ? 'Sí' : 'No', q.options.map(o => `${o.id}=${o.label}`).join('; '), q.treatAsInterval ? 'Sí: supuesto del investigador' : 'No'])]
const metadata = c => [['Campo', 'Valor'], ['Encuesta', c.id], ['Título', c.schema.title], ['Revisión inmutable', c.revision], ['Revisión del diccionario', c.dictionary_revision || 1], ['Generación UTC', new Date().toISOString()], ['Filtros aplicados', JSON.stringify(c.filters || {})], ['Creación UTC', new Date(c.created_at).toISOString()], ['Cierre UTC', c.closes_at ? new Date(c.closes_at).toISOString() : 'Manual'], ['Finalidad', c.schema.purpose], ['Contacto', c.schema.contact], ['Conservación declarada', c.schema.retention], ['Consentimiento', c.schema.consent], ['Identificación', 'Las respuestas son identificables para el creador. Una por cuenta.'], ['Advertencia', 'Muestra voluntaria, sin garantía de representatividad ni causalidad.']]
function sheet(wb, name, rows) {
  const ws = wb.addWorksheet(name, { views: [{ state: 'frozen', ySplit: 1 }] })
  rows.forEach(row => ws.addRow(row.map(safeCell)))
  ws.getRow(1).font = { bold: true, color: { argb: 'FFFFFFFF' } }
  ws.getRow(1).fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF6750C8' } }
  ws.columns.forEach((col, i) => { col.width = Math.min(60, Math.max(15, String(rows[0]?.[i] || '').length + 3)); col.alignment = { vertical: 'top', wrapText: true } })
  if (rows.length) ws.autoFilter = { from: { row: 1, column: 1 }, to: { row: rows.length, column: rows[0].length } }
  return ws
}
async function pdfBuffer(write) {
  return new Promise((resolve, reject) => {
    const doc = new PDFDocument({ size: 'A4', margin: 45, info: { Title: 'Novvin · Encuestas' } }), chunks = []
    doc.on('data', b => chunks.push(b)); doc.on('end', () => resolve(Buffer.concat(chunks))); doc.on('error', reject)
    Promise.resolve(write(doc)).then(() => doc.end()).catch(reject)
  })
}
async function exportSurvey(c, responses, options = {}) {
  const { format, identity = false, instrument = false } = options
  if (!['csv', 'xlsx', 'docx', 'pdf'].includes(format)) fail('Formato de descarga inválido.')
  const stats = analytics(c.schema, responses), rows = dataRows(c, responses, identity === true), name = filename(c)
  if (['docx','pdf'].includes(format) && !instrument && stats.questions.reduce((sum,q)=>sum+q.frequencies.length,0)>10000) fail('El informe tiene demasiadas categorías. Reduce los filtros o descarga CSV para analizar los datos.', 413, 'SURVEY_EXPORT_LIMIT')
  const related = options.x && options.y ? relations(c.schema, responses, options.x, options.y) : null
  if (format === 'csv') return envelope(`${name}.csv`, 'text/csv;charset=utf-8', '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n'))
  if (format === 'xlsx') {
    const wb = new ExcelJS.Workbook(); wb.creator = 'Novvin'; wb.created = new Date()
    sheet(wb, 'Datos', rows); sheet(wb, 'Diccionario', dictRows(c)); sheet(wb, 'Metadatos', metadata(c))
    sheet(wb, 'Frecuencias', [['Pregunta', 'Valor', 'Frecuencia absoluta', 'Frecuencia relativa', 'Porcentaje', 'Acumulada', 'Respondidas', 'Sin respuesta', 'No aplicables'], ...stats.questions.flatMap(q => q.frequencies.map(f => [q.title, f.label, f.absolute, f.relative, f.relative * 100, f.cumulative, q.answered, q.missing, q.notApplicable]))])
    const keys = ['n', 'mean', 'median', 'min', 'max', 'q1', 'q3', 'iqr', 'variance', 'sd']
    sheet(wb, 'Descriptivos', [['Pregunta', ...keys, 'Modas'], ...stats.questions.filter(q => q.descriptive).map(q => [q.title, ...keys.map(k => q.descriptive[k]), q.descriptive.modes.join('; ')])])
    sheet(wb, 'Participación', [['Día UTC', 'Respuestas'], ...stats.timeline.map(t => [t.date, t.count])])
    const charts = wb.addWorksheet('Gráficos'); let row = 1
    for (const q of stats.questions.filter(q => q.frequencies.length)) {
      charts.getCell(row, 1).value = q.title; charts.getCell(row, 1).font = { bold: true }
      const bytes = await chartPng(q), dimensions = await sharp(bytes).metadata(), height = dimensions.height
      const id = wb.addImage({ buffer: bytes, extension: 'png' })
      charts.addImage(id, { tl: { col: 0, row: row + 1 }, ext: { width: 700, height } }); row += Math.ceil(height / 20) + 4
    }
    if (related?.kind === 'crosstab') sheet(wb, 'Cruces', [['Variable / categoría', ...related.columns, 'Total'], ...related.rows.map((v, i) => [v, ...related.cells[i], related.rowTotals[i]]), ['Total', ...related.columnTotals, related.n]])
    if (related?.kind === 'scatter') sheet(wb, 'Relación', [['X', 'Y'], ...related.pairs])
    if (related) sheet(wb, 'Método relación', [['Campo', 'Valor'], ['Método', related.method || 'Tabla de contingencia'], ['N pares completos', related.n], ['Coeficiente', related.coefficient ?? null], ['Chi cuadrado descriptivo', related.chiSquared ?? null], ['Nota', related.note]])
    return envelope(`${name}.xlsx`, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', await wb.xlsx.writeBuffer())
  }
  if (format === 'docx') {
    const children = [new Paragraph({ text: c.schema.title, heading: HeadingLevel.TITLE }), new Paragraph({ text: instrument ? 'Instrumento de encuesta' : 'Informe de resultados', heading: HeadingLevel.HEADING_1 })]
    metadata(c).slice(1).forEach(([k, v]) => children.push(new Paragraph({ children: [new TextRun({ text: `${k}: `, bold: true }), new TextRun(String(v))] })))
    if (!instrument) children.push(new Paragraph(`Respuestas: ${stats.total}. ${stats.caveat}`))
    const addImage = async (id, alt) => {
      const image = await instrumentImage(c,id)
      if (image) children.push(new Paragraph({ children: [new ImageRun({ type:'png',data:image.data,transformation:{ width:Math.min(500,image.width),height:image.height*Math.min(500,image.width)/image.width },altText:{title:alt || 'Imagen del instrumento',description:alt || 'Imagen del instrumento',name:id} })] }))
    }
    await addImage(c.schema.cover,c.schema.coverAlt)
    for (const section of c.schema.sections) {
      children.push(new Paragraph({ text: section.title, heading: HeadingLevel.HEADING_1 }), new Paragraph(section.description))
      await addImage(section.image,section.imageAlt)
      for (const q of c.schema.questions.filter(q => q.section === section.id)) {
        children.push(new Paragraph({ text: `${q.title}${q.required ? ' *' : ''}`, heading: HeadingLevel.HEADING_2 }), new Paragraph(q.description), new Paragraph(`Tipo: ${q.type} · Medición: ${q.measure} · Rol: ${q.role}`))
        if (intervalNote(q)) children.push(new Paragraph(intervalNote(q)))
        await addImage(q.image,q.imageAlt)
        if (instrument) { for (const o of q.options) { children.push(new Paragraph(`☐ ${o.label}`)); await addImage(o.image,o.imageAlt) }; q.rows.forEach(r => children.push(new Paragraph(r.label))); children.push(new Paragraph('Respuesta: _____________________________________')) }
        else {
          const stat = stats.questions.find(s => s.id === q.id)
          children.push(new Paragraph(`Respondidas: ${stat.answered} · Sin respuesta: ${stat.missing} · No aplicables: ${stat.notApplicable}`))
          stat.frequencies.forEach(f => children.push(new Paragraph(`${f.label}: ${f.absolute} (${(f.relative * 100).toFixed(2)} %)${f.cumulative != null ? ` · Acumulada: ${f.cumulative}` : ''}`)))
          if (stat.descriptive) children.push(new Paragraph(`Media ${stat.descriptive.mean?.toFixed(3) || 'No aplicable para escala ordinal'} · Mediana ${stat.descriptive.median} · Q1 ${stat.descriptive.q1} · Q3 ${stat.descriptive.q3} · DE ${stat.descriptive.sd?.toFixed(3) || 'No aplicable'}`))
          if (stat.frequencies.length) {
            const image=await chartPng(stat), size=await sharp(image).metadata(), scale=Math.min(560/size.width,600/size.height)
            children.push(new Paragraph({ children: [new ImageRun({ type: 'png', data: image, transformation: { width: size.width*scale,height:size.height*scale } })] }))
          }
          for (const o of q.options) if (o.image) { children.push(new Paragraph(o.label)); await addImage(o.image,o.imageAlt) }
        }
      }
    }
    if (related) children.push(new Paragraph({ text: 'Relación entre variables', heading: HeadingLevel.HEADING_1 }), new Paragraph(JSON.stringify(related)))
    if (identity === true && !instrument) responses.forEach(r => children.push(new Paragraph(`${r.display_name || 'Cuenta'} (${r.account_id}) · ${new Date(r.submitted_at).toISOString()}`)))
    return envelope(`${name}${instrument ? '-instrumento' : '-informe'}.docx`, 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', await Packer.toBuffer(new Document({ sections: [{ children }] })))
  }
  const bytes = await pdfBuffer(async doc => {
    doc.fontSize(22).text(c.schema.title).moveDown(); doc.fontSize(10)
    metadata(c).slice(1).forEach(([k, v]) => doc.text(`${k}: ${v}`).moveDown(.3))
    if (!instrument) doc.text(`Respuestas: ${stats.total}. ${stats.caveat}`)
    const addImage = async (id,alt) => {
      const image = await instrumentImage(c,id)
      if (!image) return
      const height=Math.min(260,image.height*Math.min(400,image.width)/image.width)
      if (doc.y+height+35>doc.page.height-doc.page.margins.bottom) doc.addPage()
      doc.image(image.data,doc.x,doc.y,{fit:[400,height]});doc.y+=height+8
      if (alt) doc.fontSize(9).text(alt).moveDown(.3)
    }
    await addImage(c.schema.cover,c.schema.coverAlt)
    let sectionId
    for (const q of c.schema.questions) {
      doc.addPage(); doc.fontSize(16).text(`${q.title}${q.required ? ' *' : ''}`); doc.fontSize(10).text(q.description).moveDown()
      if (sectionId!==q.section) {
        sectionId=q.section
        const section=c.schema.sections.find(s=>s.id===sectionId)
        doc.fontSize(12).text(section.title).fontSize(10).text(section.description).moveDown(.3)
        await addImage(section.image,section.imageAlt)
      }
      doc.text(`Tipo: ${q.type} · Medición: ${q.measure} · Rol: ${q.role}`).moveDown()
      if (intervalNote(q)) doc.text(intervalNote(q)).moveDown(.3)
      await addImage(q.image,q.imageAlt)
      if (instrument) { for (const o of q.options) { doc.text(`[ ] ${o.label}`).moveDown(.3); await addImage(o.image,o.imageAlt) }; q.rows.forEach(r => doc.text(r.label)); doc.moveDown().text('Respuesta: _____________________________________') }
      else {
        const stat = stats.questions.find(s => s.id === q.id)
        doc.text(`Respondidas: ${stat.answered} · Sin respuesta: ${stat.missing} · No aplicables: ${stat.notApplicable}`)
        if (stat.descriptive) doc.text(`Media: ${stat.descriptive.mean?.toFixed(3) || 'No aplicable para escala ordinal'} · Mediana: ${stat.descriptive.median} · Q1: ${stat.descriptive.q1} · Q3: ${stat.descriptive.q3} · DE: ${stat.descriptive.sd?.toFixed(3) || 'No aplicable'}`)
        doc.moveDown()
        if (stat.frequencies.length) {
          const image=await chartPng(stat), size=await sharp(image).metadata(), height=Math.min(410,500*size.height/size.width)
          if (doc.y+height+Math.min(12,stat.frequencies.length)*12+25>doc.page.height-doc.page.margins.bottom) doc.addPage()
          const top=doc.y;doc.image(image,doc.x,top,{fit:[500,410]});doc.y=top+height+12
        }
        stat.frequencies.forEach(f => doc.fontSize(9).text(`${f.label}: ${f.absolute} · ${(f.relative * 100).toFixed(2)} %${f.cumulative != null ? ` · Acumulada: ${f.cumulative}` : ''}`))
        for (const o of q.options) if (o.image) { doc.text(o.label); await addImage(o.image,o.imageAlt) }
      }
    }
    if (related) doc.addPage().fontSize(14).text('Relación entre variables').fontSize(10).text(JSON.stringify(related))
    if (identity === true && !instrument) { doc.addPage().fontSize(14).text('Participantes').fontSize(10); responses.forEach(r => doc.text(`${r.display_name || 'Cuenta'} (${r.account_id}) · ${new Date(r.submitted_at).toISOString()}`)) }
  })
  return envelope(`${name}${instrument ? '-instrumento' : '-informe'}.pdf`, 'application/pdf', bytes)
}
async function qrExport(c, link, format) {
  if (format === 'svg') return envelope(`${filename(c)}-qr.svg`, 'image/svg+xml', await QR.toString(link, { type: 'svg', errorCorrectionLevel: 'M' }))
  const png = await QR.toBuffer(link, { width: 640, margin: 4, errorCorrectionLevel: 'M' })
  if (format === 'png') return envelope(`${filename(c)}-qr.png`, 'image/png', png)
  if (format === 'pdf') return envelope(`${filename(c)}-qr.pdf`, 'application/pdf', await pdfBuffer(doc => {
    doc.fontSize(22).text(c.schema.title).moveDown(); doc.image(png, { fit: [400, 400], align: 'center' }).moveDown(); doc.fontSize(10).text(link).moveDown().text('Participación voluntaria. Requiere una cuenta Novvin. Una respuesta por cuenta. Las respuestas son identificables para el creador.')
  }))
  if (format === 'docx') return envelope(`${filename(c)}-qr.docx`, 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', await Packer.toBuffer(new Document({ sections: [{ children: [new Paragraph({ text: c.schema.title, heading: HeadingLevel.TITLE }), new Paragraph({ children: [new ImageRun({ type: 'png', data: png, transformation: { width: 400, height: 400 } })] }), new Paragraph(link), new Paragraph('Participación voluntaria. Requiere una cuenta Novvin. Una respuesta por cuenta. Respuestas identificables para el creador.')] }] })))
  fail('Formato QR inválido.')
}
module.exports = { exportSurvey, qrExport, exportChart, safeCell }
