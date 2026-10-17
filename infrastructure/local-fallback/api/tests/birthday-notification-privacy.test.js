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
