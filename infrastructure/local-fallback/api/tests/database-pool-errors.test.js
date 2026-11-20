'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { Pool } = require('pg')
const { monitorDatabasePoolErrors } = require('../src/database-pool-errors')
const {
  createStorageUploadAdmission,
  acquireStorageFinalizationClient,
  closeStorageUploadPools,
} = require('../src/storage-upload-admission')
const { tmpdir } = require('node:os')
const { randomUUID } = require('node:crypto')

class FixtureClient extends EventEmitter {
  constructor() {
    super()
    this._queryable = true
  }
  connect(done) { queueMicrotask(done) }
  end(done) { done?.() }
  unref() {}
}

test('reports a borrowed connection failure and permits the next checkout', async () => {
  const pool = new Pool({ Client: FixtureClient, max: 1 })
  const errors = []
  monitorDatabasePoolErrors(pool, error => errors.push(error))
  try {
    const client = await pool.connect()
    const failure = new Error('connection closed during transaction')
    client.emit('error', failure)
    client.release(failure)
    const next = await pool.connect()
    assert.notEqual(next, client)
    next.release()
    assert.deepEqual(errors, [failure])
  } finally { await pool.end() }
})

test('reusing a connection reports an idle failure exactly once', async () => {
  const pool = new Pool({ Client: FixtureClient, max: 1 })
  const errors = []
  monitorDatabasePoolErrors(pool, error => errors.push(error))
  try {
    const first = await pool.connect()
    first.release()
    const reused = await pool.connect()
    assert.equal(reused, first)
    reused.release()
    const failure = new Error('idle connection closed')
    reused.emit('error', failure)
    assert.deepEqual(errors, [failure])
    assert.equal(pool.totalCount, 0)
  } finally { await pool.end() }
})

test('an upload can retry after losing its reservation connection during the disk check', async t => {
  const log = t.mock.method(console, 'error', () => {})
  const disconnect = new Error('synthetic PostgreSQL restart')
  let borrowed
  class AdmissionClient extends FixtureClient {
    async query(statement) {
      borrowed = this
      if (!this._queryable) throw disconnect
      if (statement.includes('AS user_tus_count')) {
        return { rows: [{ bytes: 0, user_bytes: 0, slots: 0, user_slots: 0,
          active_bytes: 0, user_active_bytes: 0, tus_count: 0, user_tus_count: 0 }] }
      }
      return { rows: [] }
    }
  }
  const pool = new Pool({ Client: AdmissionClient })
  let loseConnection = true
  const admission = createStorageUploadAdmission({ pool, root: tmpdir(), minFreeBytes: 0,
    statfs: async () => {
      if (loseConnection) {
        loseConnection = false
        borrowed._queryable = false
        assert.doesNotThrow(() => borrowed.emit('error', disconnect))
      }
      return { bavail: 4096n, bsize: 4096n }
    },
  })
  const upload = { ownerId: randomUUID(), bytes: 128, kind: 'raw' }
  try {
    await assert.rejects(admission.reserve(upload), e => e === disconnect)
    const lease = await admission.reserve(upload)
    lease.assertAlive()
    await lease.finish({ keep: true })
    assert.equal(log.mock.callCount(), 1)
  } finally { await closeStorageUploadPools(pool); await pool.end() }
})

test('finalization reports a connection loss before its request handler is attached', async t => {
  const log = t.mock.method(console, 'error', () => {})
  const pool = new Pool({ Client: FixtureClient })
  try {
    const client = await acquireStorageFinalizationClient(pool)
    const disconnect = new Error('synthetic PostgreSQL restart')
    try { assert.doesNotThrow(() => client.emit('error', disconnect)) }
    finally { client.release(disconnect) }
    const replacement = await acquireStorageFinalizationClient(pool)
    assert.notEqual(replacement, client)
    replacement.release()
    assert.equal(log.mock.callCount(), 1)
  } finally { await closeStorageUploadPools(pool); await pool.end() }
})
