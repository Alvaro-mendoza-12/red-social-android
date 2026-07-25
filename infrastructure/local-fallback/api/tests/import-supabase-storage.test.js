const assert = require('node:assert/strict')
const {
  ownerCandidatesFromObjectPath,
  resolveObjectOwnerId,
} = require('../src/import-supabase-storage')
const { reconcileStorageOwners } = require('../src/reconcile-storage-owners')

const publicId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const fileId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

assert.deepEqual(
  ownerCandidatesFromObjectPath(`challenge_submissions/${fileId}/${publicId}.png`),
  [fileId, publicId],
)
assert.deepEqual(ownerCandidatesFromObjectPath('stickers/not-a-user/icon.png'), [])

async function run() {
  let candidates
  const resolved = await resolveObjectOwnerId({
    async query(_sql, params) {
      candidates = params[0]
      return { rows: [{ id: '11111111-1111-4111-8111-111111111111' }] }
    },
  }, `challenge_submissions/${fileId}/${publicId}.png`)
  assert.equal(resolved, '11111111-1111-4111-8111-111111111111')
  assert.deepEqual(candidates, [fileId, publicId])
  assert.equal(await resolveObjectOwnerId({ async query() { return { rows: [] } } }, 'plain/file.png'), null)
  assert.equal(
    await resolveObjectOwnerId({ async query() { return { rows: [{ id: 'a' }, { id: 'b' }] } } }, `${fileId}/${publicId}`),
    null,
  )

  const calls = []
  const result = await reconcileStorageOwners({
    async query(sql) {
      calls.push(String(sql))
      if (calls.length === 1) return { rows: [{ count: 569 }] }
      if (calls.length === 2) return { rowCount: 251, rows: [] }
      return { rows: [{ count: 318 }] }
    },
  })
  assert.deepEqual(result, { before: 569, reconciled: 251, unresolved: 318 })
  assert.match(calls[1], /HAVING COUNT\(\*\) = 1/)
  assert.match(calls[1], /o\.owner_id IS NULL/)

  console.log('Supabase storage ownership tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
