const test = require('node:test')
const assert = require('node:assert/strict')
const {processBirthdayNotifications} = require('../src/data-routes')
test('birthday worker excludes hidden dates and keeps the daily claim', async () => {
 const queries=[]
 const pool={query:async(sql,params)=>{queries.push({sql:String(sql),params});return String(sql).includes('RETURNING cursor_user_id')?{rows:[{cursor_user_id:null}]}:{rows:[]}}}
 await processBirthdayNotifications(pool, { now:new Date('2026-10-17T15:00:00Z') })
 const eligible=queries.find(q=>q.sql.includes('JOIN fallback_profile_overrides o'))
 assert.ok(eligible,'Birthday eligibility query must run')
 assert.match(eligible.sql,/profile_section_visibility/)
 assert.match(eligible.sql,/NOT IN \('only_me', 'nobody'\)/)
 assert.equal(queries.some(q=>q.sql.includes('INSERT INTO fallback_birthday_notification_runs')),true)
})

const birthdayActor = {
  local_user_id: 'birthday-local',
  public_user_id: 'birthday-migrated',
  birthday_visibility: 'everyone',
  profile_visibility: 'everyone',
}

async function birthdayDeliveryAttempts(blocks) {
  const attempts = []
  const pool = {
    async query(sql, params) {
      if (sql.includes('RETURNING cursor_user_id')) return { rows: [{ cursor_user_id: null }] }
      if (sql.includes('JOIN fallback_profile_overrides o')) return { rows: [birthdayActor], rowCount: 1 }
      if (sql.includes('FROM fallback_friends')) {
        return { rows: [{ sender_id: birthdayActor.public_user_id, receiver_id: 'friend-migrated' }] }
      }
      if (sql.includes('JOIN LATERAL')) {
        return { rows: [{ alias: 'friend-migrated', id: 'friend-local', supabase_user_id: 'friend-migrated' }] }
      }
      if (sql.includes('FROM fallback_user_blocks')) {
        assert.deepEqual(new Set(params[0]), new Set(['birthday-local', 'birthday-migrated']))
        return { rows: blocks }
      }
      if (sql.includes('INSERT INTO fallback_birthday_notification_deliveries')) {
        attempts.push(params[1])
        // An existing claim stops this fixture before the worker can notify anyone.
        return { rowCount: 0, rows: [] }
      }
      if (sql.includes('UPDATE fallback_birthday_notification_runs') || sql.includes('DELETE FROM fallback_birthday_notification_deliveries')) {
        return { rows: [] }
      }
      throw new Error('Unexpected birthday worker query')
    },
  }
  await processBirthdayNotifications(pool, { now: new Date('2026-10-17T15:00:00Z') })
  return attempts
}

test('birthday delivery is skipped when the account blocked a friend under a local alias', async () => {
  assert.deepEqual(await birthdayDeliveryAttempts([
    { blocker_id: 'birthday-migrated', blocked_id: 'friend-local' },
  ]), [])
})

test('birthday delivery is skipped when a friend blocked a migrated account', async () => {
  assert.deepEqual(await birthdayDeliveryAttempts([
    { blocker_id: 'friend-migrated', blocked_id: 'birthday-local' },
  ]), [])
})

test('another blocked account does not suppress delivery to an unblocked friend', async () => {
  assert.deepEqual(await birthdayDeliveryAttempts([
    { blocker_id: 'birthday-migrated', blocked_id: 'someone-else' },
  ]), ['friend-migrated'])
})
