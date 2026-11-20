'use strict'

function monitorDatabasePoolErrors(pool, reportError) {
  // pg-pool forwards idle connection errors to the pool. Between checkout
  // and release, pg emits them on the client, including between two queries.
  pool.on('error', reportError)
  pool.on('acquire', client => client.on('error', reportError))
  pool.on('release', (_error, client) => client.removeListener('error', reportError))
}

module.exports = { monitorDatabasePoolErrors }
