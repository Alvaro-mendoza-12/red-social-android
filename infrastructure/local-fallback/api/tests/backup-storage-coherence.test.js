'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

const script = path.resolve(__dirname, '../../scripts/verify-backup-storage.py')
const linux = { skip: process.platform !== 'linux' }
const original = Buffer.from('abcdefgh')
const sha = crypto.createHash('sha256').update(original).digest('hex')

function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-backup-storage-'))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }))
  const media = path.join(dir, 'media')
  fs.mkdirSync(media)
  return {
    media,
    check({ expected = '', current = '', members = ['.'], truncate = false }) {
      const tar = path.join(dir, 'storage.tar.gz')
      const packed = spawnSync('tar', ['-czf', tar, '-C', media, ...members], { encoding: 'utf8' })
      assert.equal(packed.status, 0, packed.stderr)
      if (truncate) fs.truncateSync(tar, fs.statSync(tar).size - 5)
      const catalog = path.join(dir, 'expected.csv')
      const versions = path.join(dir, 'current.csv')
      fs.writeFileSync(catalog, expected)
      fs.writeFileSync(versions, current)
      const r = spawnSync('python3', [script, catalog, versions, tar], { encoding: 'utf8' })
      assert.ifError(r.error)
      return r
    },
  }
}

test('a verified archive accepts an unrelated concurrent insert and a captured digest', linux, t => {
  const f = fixture(t)
  fs.writeFileSync(path.join(f.media, 'picture'), original)
  const r = f.check({ expected: 'id,/data/storage/picture,8,' + sha + ',42\n', current: 'new,43\n' })
  assert.equal(r.status, 0, r.stderr)
  assert.match(r.stdout, /STORAGE_CATALOG_VERIFIED=1 SHA256_FROM_DATABASE=1/)
})

test('a catalog object may be a tar hard link to a file outside the catalog', linux, t => {
  const f = fixture(t)
  fs.writeFileSync(path.join(f.media, 'old'), original)
  fs.linkSync(path.join(f.media, 'old'), path.join(f.media, 'new'))
  const r = f.check({ expected: 'id,/data/storage/new,8,' + sha + ',42\n', members: ['old', 'new'] })
  assert.equal(r.status, 0, r.stderr)
})

for (const example of [
  { name: 'missing member', file: null, digest: sha, failure: 'missing=1' },
  { name: 'wrong length', file: 'longer bytes', digest: '', current: 'id,42\n', failure: 'size=1' },
  { name: 'wrong digest at equal length', file: 'abcdwxyz', digest: sha, failure: 'digest=1' },
  { name: 'mutable row replaced at equal length', file: 'abcdwxyz', digest: '', current: 'id,43\n', failure: 'changed_unhashed=1' },
  { name: 'mutable row deleted during the copy', file: 'abcdefgh', digest: '', failure: 'changed_unhashed=1' },
]) {
  test('an archive rejects a ' + example.name, linux, t => {
    const f = fixture(t)
    if (example.file !== null) fs.writeFileSync(path.join(f.media, 'picture'), example.file)
    const r = f.check({ expected: 'id,/data/storage/picture,8,' + example.digest + ',42\n', current: example.current })
    assert.notEqual(r.status, 0)
    assert.ok(r.stderr.includes(example.failure), r.stderr)
  })
}

test('a stable legacy object remains recoverable in a path with a comma', linux, t => {
  const f = fixture(t)
  fs.writeFileSync(path.join(f.media, 'a,b'), original)
  const r = f.check({ expected: 'id,"/data/storage/a,b",8,,42\n', current: 'id,42\n' })
  assert.equal(r.status, 0, r.stderr)
})

test('an empty catalog still checks the gzip trailer', linux, t => {
  const f = fixture(t)
  assert.equal(f.check({}).status, 0)
  assert.notEqual(f.check({ truncate: true }).status, 0)
})
