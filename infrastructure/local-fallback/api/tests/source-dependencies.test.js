const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { missingSourceDependencies } = require('../scripts/verify-source-dependencies')

test('a missing module blocks deployment without executing source code', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-module-check-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  fs.writeFileSync(path.join(root, 'server.js'), "throw Error('must not execute'); require('./message-insert-store')")
  assert.deepEqual(missingSourceDependencies(root), [{ file: 'server.js', dependency: './message-insert-store' }])
  fs.writeFileSync(path.join(root, 'message-insert-store.js'), "require('node:crypto')")
  assert.deepEqual(missingSourceDependencies(root), [])
})

test('the checked-out API has a complete literal dependency closure', () => {
  assert.deepEqual(missingSourceDependencies(path.join(__dirname, '../src')), [])
})
