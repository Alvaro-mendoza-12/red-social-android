const assert = require('node:assert/strict')
const { test } = require('node:test')
const { mkdtemp, mkdir, writeFile, access, rm } = require('node:fs/promises')
const { tmpdir } = require('node:os')
const { join } = require('node:path')
const { createRequire } = require('node:module')

test('Firebase Admin constructs Firestore and Messaging clients without network access', async () => {
  const { initializeApp, deleteApp } = require('firebase-admin/app')
  const { getFirestore } = require('firebase-admin/firestore')
  const { getMessaging } = require('firebase-admin/messaging')
  const app = initializeApp({ projectId: 'dependency-compat-test' }, 'dependency-compat-test')
  const firestore = getFirestore(app)
  try {
    assert.equal(firestore.collection('checks').doc('local').path, 'checks/local')
    assert.equal(getMessaging(app).app, app)
    assert.equal(typeof require('google-gax').GrpcClient, 'function')
  } finally {
    await firestore.terminate()
    await deleteApp(app)
  }
})

test('the scoped google-gax rimraf override keeps its async deletion API compatible', async () => {
  const requireFromGax = createRequire(require.resolve('google-gax'))
  const { rimraf } = requireFromGax('rimraf')
  const root = await mkdtemp(join(tmpdir(), 'firebase-rimraf-compat-'))
  try {
    const target = join(root, 'remove-me')
    await mkdir(target)
    await writeFile(join(target, 'file.txt'), 'temporary test fixture')
    assert.equal(await rimraf(target), true)
    await assert.rejects(access(target), { code: 'ENOENT' })
    await access(root)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
