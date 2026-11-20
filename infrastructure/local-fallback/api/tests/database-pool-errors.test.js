'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { Pool } = require('pg')
const { monitorDatabasePoolErrors } = require('../src/database-pool-errors')

class FixtureClient extends EventEmitter {
  constructor() {
    super()
    this._queryable = true
  }
  connect(done) { queueMicrotask(done) }
  end(done) { done?.() }
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
