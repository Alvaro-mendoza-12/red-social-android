'use strict'
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Client } = require('pg')
const { _test } = require('../src/postgrest-compat-routes')

async function verify(purge = _test.purgeCompatIdentityRows) {
  const client = new Client({ connectionString: process.env.DATABASE_URL })
  await client.connect()
  try {
    await client.query('BEGIN')
    // pg_temp shadows the production table on this connection only.
    await client.query('CREATE TEMP TABLE fallback_compat_rows(row_id TEXT, row JSONB) ON COMMIT DROP')
    const id = crypto.randomUUID()
    const rows = [
      [id, {}], ['scalar', { user_id: id.toUpperCase() }],
      ['array', { participants: ['other', id.toUpperCase()] }],
      ['legacy-array-string', { participants: `{other,${id}}` }],
      ['unrelated-body', { body: id, caption: id, media: 'x'.repeat(100_000) }],
      ['different-scalar', { user_id: `${id}-unrelated` }],
      ['null', { user_id: null, participants: null }],
      ['foreign', { user_id: crypto.randomUUID(), participants: ['someone-else'] }],
    ]
    for (const [rowId, row] of rows) await client.query('INSERT INTO fallback_compat_rows VALUES($1,$2::jsonb)', [rowId, JSON.stringify(row)])
    const deleted = await purge(client, [id])
    assert.equal(deleted, 4)
    assert.deepEqual((await client.query('SELECT row_id FROM fallback_compat_rows ORDER BY row_id')).rows.map(r => r.row_id),
      ['different-scalar', 'foreign', 'null', 'unrelated-body'])
    return { passed: true, temporaryTableRolledBack: true, scalarAndLegacyArrayOwnershipPreserved: true,
      unrelatedPayloadPreserved: true, actualEmailsSent: 0 }
  } finally {
    await client.query('ROLLBACK')
    await client.end()
  }
}
module.exports = { verify }
if (require.main === module) verify().then(report => console.log(JSON.stringify(report))).catch(() => {
  console.error('COMPAT_CLEANUP_QA_FAILED')
  process.exitCode = 1
})
