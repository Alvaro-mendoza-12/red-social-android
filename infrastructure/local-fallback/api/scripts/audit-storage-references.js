const fs = require('fs')
const path = require('path')
const { Pool } = require('pg')
const { auditStorageReferences, DEFAULT_GRACE_HOURS } = require('../src/storage-reference-audit')

function argumentValue(name, fallback = '') {
  const prefix = `--${name}=`
  const entry = process.argv.slice(2).find((item) => item.startsWith(prefix))
  return entry ? entry.slice(prefix.length) : fallback
}

function enabledFlag(name) {
  return process.argv.slice(2).includes(`--${name}`)
}

async function writeReport(reportPath, report) {
  const resolved = path.resolve(reportPath)
  await fs.promises.mkdir(path.dirname(resolved), { recursive: true })
  await fs.promises.writeFile(resolved, `${JSON.stringify(report, null, 2)}\n`, { encoding: 'utf8', flag: 'w' })
  return resolved
}

async function main() {
  const destructiveFlags = new Set(['--apply', '--delete', '--purge', '--quarantine'])
  if (process.argv.some((item) => destructiveFlags.has(String(item).split('=', 1)[0]))) {
    throw new Error('STORAGE_REFERENCE_AUDIT_IS_READ_ONLY')
  }
  const databaseUrl = String(process.env.DATABASE_URL || '').trim()
  if (!databaseUrl) throw new Error('DATABASE_URL is required')
  const storageRoot = path.resolve(process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'))
  const graceHours = Number(argumentValue('grace-hours', process.env.STORAGE_AUDIT_GRACE_HOURS || DEFAULT_GRACE_HOURS))
  const schemas = argumentValue('schemas', process.env.STORAGE_AUDIT_SCHEMAS || 'public,supabase_import')
  const reportPath = argumentValue(
    'report',
    process.env.STORAGE_AUDIT_REPORT || path.join(process.cwd(), 'build', 'reports', 'storage-reference-audit.json'),
  )
  const pool = new Pool({ connectionString: databaseUrl, max: 1 })
  const client = await pool.connect()
  let transactionStarted = false
  try {
    await client.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY')
    transactionStarted = true
    const report = await auditStorageReferences(client, { storageRoot, graceHours, schemas })
    await client.query('COMMIT')
    transactionStarted = false
    const savedAt = await writeReport(reportPath, report)
    console.log(JSON.stringify({
      mode: report.mode,
      destructiveActions: report.destructiveActions,
      report: savedAt,
      summary: report.summary,
    }))
    if (enabledFlag('fail-on-critical') && report.summary.critical > 0) process.exitCode = 2
    if (enabledFlag('fail-on-candidates') && report.summary.candidates > 0) process.exitCode = 3
  } finally {
    if (transactionStarted) await client.query('ROLLBACK').catch(() => {})
    client.release()
    await pool.end()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
