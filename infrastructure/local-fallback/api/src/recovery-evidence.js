const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const multer = require('multer')
const MAX_FILES = 5
const MAX_BYTES = 8 * 1024 * 1024
const root = path.resolve(process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'), '.recovery-evidence')
fs.mkdirSync(root, { recursive: true, mode: 0o700 })

function evidenceType(bytes, filename) {
  if (bytes.subarray(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]))) return 'image/jpeg'
  if (bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) return 'image/png'
  const ascii = bytes.toString('latin1')
  if (/^GIF8[79]a/.test(ascii)) return 'image/gif'
  if (ascii.startsWith('RIFF') && ascii.slice(8,12) === 'WEBP') return 'image/webp'
  if (ascii.startsWith('%PDF-')) return 'application/pdf'
  if (ascii.slice(4,8) === 'ftyp') return /\.(m4a|aac)$/i.test(filename) ? 'audio/mp4' : 'video/mp4'
  if (bytes.subarray(0,4).equals(Buffer.from([0x1a,0x45,0xdf,0xa3]))) return 'video/webm'
  if (ascii.startsWith('OggS')) return 'audio/ogg'
  if (ascii.startsWith('RIFF') && ascii.slice(8,12) === 'WAVE') return 'audio/wav'
  if (ascii.startsWith('ID3') || (bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0)) return 'audio/mpeg'
  if (/\.txt$/i.test(filename) && !bytes.includes(0) && !/<\s*(script|html|svg|iframe)\b/i.test(ascii)) {
    try { new TextDecoder('utf-8', { fatal: true }).decode(bytes, { stream: true }); return 'text/plain' } catch {}
  }
  return null
}
function safeName(name) {
  return path.basename(String(name || '').replace(/\\/g, '/')).normalize('NFKC').replace(/[\x00-\x1f\x7f"<>]/g, '').slice(0, 160) || 'evidencia'
}
async function cleanup(files) { await Promise.all((files || []).map(f => fs.promises.unlink(f.path).catch(() => {}))) }
const receive = multer({ storage: multer.diskStorage({
  destination: root,
  filename: (req, file, cb) => cb(null, crypto.randomUUID()),
}), limits: { files: MAX_FILES, fileSize: MAX_BYTES, fields: 5, fieldSize: 4096, parts: 10 },
}).array('evidence', MAX_FILES)

function receiveRecoveryEvidence(req, res, next) {
  if (!req.is('multipart/form-data')) return next()
  receive(req, res, async error => {
    if (error) { await cleanup(req.files); return res.status(error.code === 'LIMIT_FILE_SIZE' ? 413 : 400).json({ error: 'RECOVERY_EVIDENCE_LIMIT', maxFiles: MAX_FILES, maxBytes: MAX_BYTES }) }
    try {
      for (const file of req.files || []) {
        const handle = await fs.promises.open(file.path, 'r')
        const header = Buffer.alloc(Math.min(file.size, 4096))
        try { await handle.read(header, 0, header.length, 0) } finally { await handle.close() }
        file.evidenceType = evidenceType(header, file.originalname)
        if (!file.size || !file.evidenceType) throw new Error('RECOVERY_EVIDENCE_TYPE')
        await fs.promises.chmod(file.path, 0o600)
      }
      res.once('finish', () => { if (!req.recoveryEvidenceCommitted) cleanup(req.files) })
      res.once('close', () => { if (!req.recoveryEvidenceCommitted) cleanup(req.files) })
      next()
    } catch (error) { await cleanup(req.files); return res.status(400).json({ error: 'RECOVERY_EVIDENCE_TYPE' }) }
  })
}
async function ensureRecoveryEvidenceSchema(executor) {
  await executor.query(`CREATE TABLE IF NOT EXISTS fallback_recovery_evidence (
    id UUID PRIMARY KEY, case_id UUID NOT NULL REFERENCES fallback_account_recovery_cases(id) ON DELETE CASCADE,
    name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL CHECK (size > 0 AND size <= 8388608),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '30 days'
  )`)
  await executor.query('CREATE INDEX IF NOT EXISTS idx_recovery_evidence_case ON fallback_recovery_evidence(case_id)')
}
async function persistRecoveryEvidence(client, caseId, files) {
  for (const file of files || []) await client.query(
    'INSERT INTO fallback_recovery_evidence(id, case_id, name, mime, size) VALUES ($1,$2,$3,$4,$5)',
    [file.filename, caseId, safeName(file.originalname), file.evidenceType, file.size])
}
function registerRecoveryEvidenceDownload(app, { pool, recoveryAdminRequired, recordAudit }) {
  app.get(['/v1/admin/account-recovery/cases/:caseId/evidence/:evidenceId', '/v1/data/admin/account-recovery/cases/:caseId/evidence/:evidenceId'], recoveryAdminRequired, async (req,res,next) => {
    try {
      if (![req.params.caseId, req.params.evidenceId].every(x => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(x))) return res.status(400).json({ error: 'RECOVERY_EVIDENCE_REQUIRED' })
      const { rows } = await pool.query('SELECT id, name, mime FROM fallback_recovery_evidence WHERE id=$1 AND case_id=$2 AND expires_at>NOW()', [req.params.evidenceId, req.params.caseId])
      const file = rows[0]
      if (!file) return res.status(404).json({ error: 'RECOVERY_EVIDENCE_NOT_FOUND' })
      res.set({ 'Cache-Control':'private, no-store', 'X-Content-Type-Options':'nosniff', 'Content-Type':file.mime })
      await recordAudit('admin_recovery_evidence_viewed', req.auth?.sub || null, { caseId: req.params.caseId, evidenceId: file.id })
      res.download(path.join(root,file.id), safeName(file.name), { dotfiles: 'allow' }, error => { if (error && !res.headersSent) next(error) })
    } catch (error) { next(error) }
  })
  const timer = setInterval(async () => {
    try {
      const { rows } = await pool.query('DELETE FROM fallback_recovery_evidence WHERE expires_at <= NOW() RETURNING id')
      await cleanup(rows.map(x => ({ path: path.join(root, x.id) })))
      const cutoff = Date.now() - 60 * 60_000
      for (const name of await fs.promises.readdir(root)) {
        if (!/^[0-9a-f-]{36}$/i.test(name)) continue
        const file = path.join(root,name)
        if ((await fs.promises.stat(file)).mtimeMs > cutoff) continue
        const exists = await pool.query('SELECT 1 FROM fallback_recovery_evidence WHERE id=$1', [name])
        if (!exists.rows.length) await fs.promises.unlink(file).catch(() => {})
      }
    } catch (error) { console.error('[recovery] evidence cleanup failed', error?.code || error?.name) }
  },60*60_000)
  timer.unref()
}
module.exports = { receiveRecoveryEvidence, ensureRecoveryEvidenceSchema, persistRecoveryEvidence, registerRecoveryEvidenceDownload, evidenceType, safeName, MAX_FILES, MAX_BYTES }
