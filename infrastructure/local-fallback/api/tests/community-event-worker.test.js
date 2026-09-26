const assert = require('assert/strict')
const {
  birthdayDateParts,
  processBirthdayNotifications,
  processDueCommunityEventReminders,
} = require('../src/data-routes')

const dueRow = {
  event_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  user_id: 'recipient-1',
  community_id: 'community-1',
  title: 'Encuentro',
  starts_at: '2026-08-25T12:00:00.000Z',
}

function workerPool({ failNotification = false, breakMode = 'off' } = {}) {
  const calls = []
  const client = {
    async query(sql) {
      const statement = String(sql)
      calls.push(statement)
      if (statement.includes('FROM fallback_community_event_attendees a')) {
        return { rows: [dueRow] }
      }
      if (statement.includes("AS break_mode")) {
        return { rows: [{ break_mode: breakMode }] }
      }
      if (statement.includes('fallback_notification_digest_preferences')) {
        return { rows: [] }
      }
      if (statement.includes('INSERT INTO fallback_notifications')) {
        if (failNotification) throw new Error('notification insert failed')
        return { rows: [] }
      }
      return { rows: [], rowCount: 1 }
    },
    release() { calls.push('RELEASE') },
  }
  return {
    calls,
    pool: {
      async connect() { return client },
      async query() { throw new Error('unexpected pool query') },
    },
  }
}

async function run() {
  assert.deepEqual(
    birthdayDateParts(new Date('2026-08-25T04:30:00.000Z')),
    { dateKey: '2026-08-24', monthDay: '08-24', year: 2026 },
  )

  const birthdayQueries = []
  const skippedBirthdayResult = await processBirthdayNotifications({
    async query(sql, params) {
      birthdayQueries.push({ sql: String(sql), params })
      return { rows: [], rowCount: 0 }
    },
  }, { now: new Date('2026-08-25T04:30:00.000Z') })
  assert.deepEqual(skippedBirthdayResult, { birthdays: 0, delivered: 0, skipped: true })
  assert.match(birthdayQueries[0].sql, /ON CONFLICT \(birthday_date\) DO UPDATE/)
  assert.match(birthdayQueries[0].sql, /locked_at < NOW\(\) - INTERVAL '30 minutes'/)

  const failure = workerPool({ failNotification: true })
  await assert.rejects(
    processDueCommunityEventReminders(failure.pool),
    /notification insert failed/,
  )
  assert.match(failure.calls[1], /FOR UPDATE OF a SKIP LOCKED/)
  assert.equal(
    failure.calls.some((sql) => sql.includes('SET reminder_sent_at = NOW()')),
    false,
    'a failed notification must remain eligible for retry',
  )
  assert.equal(failure.calls.includes('ROLLBACK'), true)
  assert.equal(failure.calls.at(-1), 'RELEASE')

  const muted = workerPool({ breakMode: 'mute' })
  const result = await processDueCommunityEventReminders(muted.pool)
  assert.deepEqual(result, { processed: 1, persisted: 0 })
  const markIndex = muted.calls.findIndex((sql) => sql.includes('SET reminder_sent_at = NOW()'))
  const commitIndex = muted.calls.indexOf('COMMIT')
  assert.equal(markIndex > 0, true)
  assert.equal(commitIndex > markIndex, true)
  assert.equal(muted.calls.at(-1), 'RELEASE')

  console.log('community event worker tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exit(1)
})
