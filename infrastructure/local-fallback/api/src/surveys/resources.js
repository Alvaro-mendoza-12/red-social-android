'use strict'

const { scanPdfWithClamd } = require('./clamd-client')
const D = require('./domain')

function singleJob(code, message) {
  let running = false
  return async task => {
    if (running) D.fail(message, 503, code)
    running = true
    try { return await task() } finally { running = false }
  }
}
// Fail promptly instead of keeping unbounded request bodies in a waiting queue.
const scanSlot = singleJob('SURVEY_SCAN_BUSY', 'El antivirus está validando otro archivo. Inténtalo de nuevo en unos segundos.')
const exportSlot = singleJob('SURVEY_EXPORT_BUSY', 'Hay otra exportación en curso. Inténtalo de nuevo en unos segundos.')
const imageSlot = singleJob('SURVEY_IMAGE_BUSY', 'Se está validando otra imagen. Inténtalo de nuevo en unos segundos.')
const analysisSlot = singleJob('SURVEY_ANALYSIS_BUSY', 'Se está preparando otro resumen. Inténtalo de nuevo en unos segundos.')

async function scanPdf(bytes) {
  return scanSlot(() => scanPdfWithClamd(bytes))
}
function assertExportBudget(campaign, responses, options = {}) {
  const cells = responses.length * (2*campaign.schema.questions.length+3+(options.identity === true ? 2 : 0))
  const bytes = Buffer.byteLength(JSON.stringify(responses))
  if (cells > 250000 || bytes > 16 * 1024 * 1024) D.fail('La selección es demasiado grande para una descarga móvil. Reduce el periodo o los filtros y descarga varios archivos.', 413, 'SURVEY_EXPORT_LIMIT')
}

module.exports = { scanPdf, exportSlot, imageSlot, analysisSlot, singleJob, assertExportBudget }
