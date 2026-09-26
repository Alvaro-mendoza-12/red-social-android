function boundedTimeout(value, fallback = 180_000) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.max(30_000, Math.min(Math.trunc(parsed), 15 * 60_000))
}

/**
 * Serializes startup DDL across API replicas using a session-level PostgreSQL
 * advisory lock. The same client executes every schema operation, so the lock
 * cannot be bypassed by the pool handing a query to another connection.
 */
async function withSchemaInitializationLock({
  pool,
  task,
  lockName = 'novvin:api:schema-initialization:v1',
  statementTimeoutMs = 180_000,
}) {
  if (!pool || typeof pool.connect !== 'function') throw new TypeError('pool.connect is required')
  if (typeof task !== 'function') throw new TypeError('task is required')

  const client = await pool.connect()
  let locked = false
  let result
  let primaryError = null
  let cleanupError = null

  try {
    await client.query(
      "SELECT set_config('statement_timeout', $1, false)",
      [`${boundedTimeout(statementTimeoutMs)}ms`],
    )
    await client.query('SELECT pg_advisory_lock(hashtext($1))', [lockName])
    locked = true
    result = await task(client)
  } catch (error) {
    primaryError = error
  }

  if (locked) {
    try {
      await client.query('SELECT pg_advisory_unlock(hashtext($1))', [lockName])
    } catch (error) {
      cleanupError = error
    }
  }
  try {
    await client.query('RESET statement_timeout')
  } catch (error) {
    cleanupError ||= error
  }

  // Passing a cleanup error destroys the pooled connection instead of
  // returning a possibly locked/misconfigured session to future requests.
  client.release(cleanupError || undefined)

  if (primaryError) {
    if (cleanupError && primaryError.cause == null) primaryError.cause = cleanupError
    throw primaryError
  }
  if (cleanupError) throw cleanupError
  return result
}

module.exports = {
  boundedTimeout,
  withSchemaInitializationLock,
}
