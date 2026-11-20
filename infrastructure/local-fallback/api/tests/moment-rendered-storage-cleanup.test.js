'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')

test('removing rendered Moment music retires both legacy files and immutable revisions', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-moment-cleanup-'))
  const previousRoot = process.env.STORAGE_ROOT
  process.env.STORAGE_ROOT = root
  t.after(async () => {
    if (previousRoot === undefined) delete process.env.STORAGE_ROOT
    else process.env.STORAGE_ROOT = previousRoot
    await fs.rm(root, { recursive: true, force: true })
  })
  const { registerMomentsRoutes } = require('../src/moments-routes')
  const { revisionFilePath } = require('../src/storage-scan-coordinator')
  const owner = crypto.randomUUID()
  for (const revision of [null, crypto.randomUUID()]) {
    const bucket = 'community-music'
    const name = `${owner}/moments/rendered/${crypto.randomUUID()}.m4a`
    const localPath = revision ? revisionFilePath(root, revision) : path.join(root, bucket, name)
    await fs.mkdir(path.dirname(localPath), { recursive: true })
    await fs.writeFile(localPath, 'synthetic audio')
    const momentId = crypto.randomUUID()
    const pool = { async query(sql, params) {
      if (sql.includes('UPDATE fallback_moments AS moment')) {
        assert.deepEqual(params, [momentId, [owner]])
        return { rows: [{ id: momentId, music_audio_url: `/storage/v1/object/public/${bucket}/${name}` }] }
      }
      if (sql.includes('DELETE FROM fallback_moment_music')) return { rows: [] }
      assert.match(sql, /DELETE FROM fallback_storage_objects/)
      assert.deepEqual(params, [bucket, name])
      const row = { bucket_id: bucket, name, local_path: localPath, object_revision: revision }
      const fields = sql.split('RETURNING')[1].trim().split(/\s*,\s*/)
      return { rows: [Object.fromEntries(fields.map(field => [field, row[field]]))] }
    } }
    const routes = new Map()
    const app = Object.fromEntries(['get', 'post', 'put', 'delete'].map(method =>
      [method, (url, ...handlers) => routes.set(method + ' ' + url, handlers.at(-1))]))
    registerMomentsRoutes(app, { pool, authRequired() {}, getCurrentUser: async () => ({ id: owner }),
      userIdentity: () => ({ ids: new Set([owner]) }) })
    const handler = routes.get('delete /v1/data/moments/:momentId/music')
    await handler({ params: { momentId } }, { json: body => assert.deepEqual(body, { removed: true }) },
      error => { throw error })
    await assert.rejects(fs.stat(localPath), { code: 'ENOENT' })
  }
})
