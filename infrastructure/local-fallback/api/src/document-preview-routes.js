'use strict'

const multer = require('multer')
const MAX_BYTES = 32 * 1024 * 1024
const extensions = new Set(['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'])

function documentFormat(file) {
  const name = String(file?.originalname || '').split(/[\\/]/).pop()
  const extension = name.split('.').pop().toLowerCase()
  if (!extensions.has(extension) || !file?.buffer?.length) return null
  const signature = file.buffer.subarray(0, 8)
  const modern = extension.endsWith('x') && signature.subarray(0, 4).equals(Buffer.from([80, 75, 3, 4]))
  const legacy = !extension.endsWith('x') && signature.equals(Buffer.from([208, 207, 17, 224, 161, 177, 26, 225]))
  return modern || legacy ? extension : null
}

async function boundedPdf(response) {
  const chunks = []; let size = 0
  for await (const chunk of response.body) {
    size += chunk.length
    if (size > MAX_BYTES) throw Object.assign(new Error('OFFICE_PREVIEW_TOO_LARGE'), { status: 413 })
    chunks.push(Buffer.from(chunk))
  }
  const bytes = Buffer.concat(chunks)
  if (!bytes.subarray(0, 5).equals(Buffer.from('%PDF-'))) throw new Error('OFFICE_PREVIEW_UNAVAILABLE')
  return bytes
}

/** Uploaded snapshots are transient; the converter is private and has no outbound network. */
function registerDocumentPreviewRoutes(app, { authRequired, rateLimit = (_, __, next) => next(),
  rendererUrl = process.env.OFFICE_PREVIEW_INTERNAL_URL, fetchImpl = fetch, maxConcurrent = 2, timeoutMs = 30_000 }) {
  let active = 0
  const receive = multer({ storage: multer.memoryStorage(), limits: {
    fileSize: MAX_BYTES, files: 1, fields: 0, parts: 2,
  } }).single('file')
  app.post('/v1/documents/preview', authRequired, rateLimit, async (req, res) => {
    res.set('Cache-Control', 'no-store')
    if (!rendererUrl) return res.status(503).json({ error: 'OFFICE_PREVIEW_UNAVAILABLE' })
    if (!req.is('multipart/form-data')) return res.status(415).json({ error: 'OFFICE_DOCUMENT_REQUIRED' })
    if (active >= maxConcurrent) return res.status(429).set('Retry-After', '5').json({ error: 'OFFICE_PREVIEW_BUSY' })
    active++
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), timeoutMs)
    const closed = () => { if (!res.writableFinished) controller.abort() }
    res.once('close', closed)
    try {
      await new Promise((resolve, reject) => {
        const aborted = () => {
          req.unpipe(); req.destroy()
          reject(new Error('OFFICE_PREVIEW_UNAVAILABLE'))
        }
        controller.signal.addEventListener('abort', aborted, { once: true })
        if (controller.signal.aborted) { aborted(); return }
        receive(req, res, error => {
          controller.signal.removeEventListener('abort', aborted)
          error ? reject(error) : resolve()
        })
      })
      const extension = documentFormat(req.file)
      if (!extension) return res.status(415).json({ error: 'OFFICE_DOCUMENT_UNSUPPORTED' })
      if (controller.signal.aborted) throw new Error('OFFICE_PREVIEW_UNAVAILABLE')
      const form = new FormData()
      // Do not pass client filenames, URLs, passwords, macros or arbitrary conversion options.
      form.append('files', new Blob([req.file.buffer]), `document.${extension}`)
      const response = await fetchImpl(`${String(rendererUrl).replace(/\/+$/, '')}/forms/libreoffice/convert`, {
        method: 'POST', body: form, signal: controller.signal, redirect: 'error',
      })
      if (!response.ok) return res.status(response.status === 400 ? 422 : 503)
        .json({ error: response.status === 400 ? 'OFFICE_DOCUMENT_UNREADABLE' : 'OFFICE_PREVIEW_UNAVAILABLE' })
      const pdf = await boundedPdf(response)
      if (!res.destroyed) res.type('application/pdf').set('X-Content-Type-Options', 'nosniff')
        .set('Content-Disposition', 'inline; filename="preview.pdf"').send(pdf)
    } catch (error) {
      if (!res.destroyed && !res.headersSent) {
        const status = error.code === 'LIMIT_FILE_SIZE' ? 413 : error instanceof multer.MulterError ? 400 : error.status || 503
        res.status(status).json({ error: status === 413 ? 'OFFICE_PREVIEW_TOO_LARGE' : status === 400 ? 'OFFICE_DOCUMENT_REQUIRED' : 'OFFICE_PREVIEW_UNAVAILABLE' })
      }
    } finally {
      clearTimeout(timeout); res.removeListener('close', closed)
      if (req.file) req.file.buffer = null
      active--
    }
  })
}

module.exports = { MAX_BYTES, documentFormat, registerDocumentPreviewRoutes }
