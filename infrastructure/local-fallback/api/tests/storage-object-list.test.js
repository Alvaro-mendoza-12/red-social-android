const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { registerStorageRoutes } = require('../src/storage-routes')

const owner = '11111111-1111-4111-8111-111111111111'
const rows = [
  { id: 'first', owner_id: owner, bucket_id: 'chat-media', name: `${owner}/dm/presentation.pptx`, size_bytes: 10 },
  { id: 'second', owner_id: owner, bucket_id: 'chat-media', name: `${owner}/other/note.txt`, size_bytes: 20 },
  { id: 'private-other-owner', owner_id: 'another-account', bucket_id: 'chat-media', name: 'another-account/dm/file.txt', size_bytes: 30 },
]

for (const [name, query, expected] of [
  ['omitted prefix lists the authenticated owner objects', '', ['first', 'second']],
  ['explicit empty prefix behaves like omitted prefix', '?prefix=', ['first', 'second']],
  ['bucket alone does not invent a filename prefix', '?bucket=chat-media', ['first', 'second']],
  ['a requested prefix still limits the owner listing', `?bucket=chat-media&prefix=${owner}/dm/`, ['first']],
  ['a query owner cannot change the authenticated owner', '?ownerId=another-account', ['first', 'second']],
]) {
  test(name, async t => {
    const app = express()
    registerStorageRoutes(app, {
      pool: { async query(sql, [bucket, prefix, account]) {
        assert.match(sql, /owner_id = \$3/)
        assert.equal(account, owner)
        return { rows: rows.filter(row => row.owner_id === account && (!bucket || row.bucket_id === bucket) && (!prefix || row.name.startsWith(prefix))) }
      } },
      authRequired(req, _res, next) { req.auth = { sub: owner }; next() },
    })
    app.use((error, _req, res, _next) => res.status(500).json({error: error.message}))
    const server = await new Promise(resolve => {
      const listener = app.listen(0, '127.0.0.1', () => resolve(listener))
    })
    t.after(() => new Promise(resolve => server.close(resolve)))
    const response = await fetch(`http://127.0.0.1:${server.address().port}/v1/storage/objects${query}`)
    assert.equal(response.status, 200)
    assert.deepEqual((await response.json()).objects.map(object => object.id), expected)
  })
}
