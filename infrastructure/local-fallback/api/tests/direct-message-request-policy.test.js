const assert = require('node:assert/strict')
const test = require('node:test')
const { ensureDirectMessageRequestPolicy, introPolicyError, publishAutomaticFriendRequest } = require('../src/direct-message-request-policy')

test('all direct INSERT transports share an atomic one-introduction guard', async () => {
  const statements=[]
  await ensureDirectMessageRequestPolicy({query:async sql=>{statements.push(sql);return {rows:[]}}})
  const guard=statements.find(sql=>sql.includes('FUNCTION fallback_guard_introductory_message'))
  assert.ok(guard.includes("RAISE EXCEPTION 'MESSAGE_REQUEST_PENDING'"))
  assert.ok(guard.includes("RAISE EXCEPTION 'MESSAGE_REQUEST_REJECTED'"))
  assert.ok(guard.includes("RAISE EXCEPTION 'CHAT_BLOCKED_BY_RECIPIENT'"))
  assert.ok(guard.includes('pg_advisory_xact_lock(hashtextextended(lower(NEW.chat_id::text),0))'))
  assert.ok(guard.includes('WHERE id=NEW.id AND chat_id=NEW.chat_id'))
  assert.ok(guard.includes('INSERT INTO fallback_friends'))
  assert.ok(guard.includes("VALUES(actor,other,'pending')"))
  assert.ok(guard.includes("VALUES(state->>'senderId',actor,'accepted')"))
  assert.ok(statements.some(sql=>sql.includes("table_name='messages'")))
  assert.ok(!statements.some(sql=>sql.includes('chat.intro_policy_started_at IS NULL')))
})
test('policy errors remain explicit terminal HTTP conflicts',()=>{
  for(const code of ['FRIEND_REQUEST_PENDING','FRIEND_REQUEST_REJECTED','MESSAGE_REQUEST_PENDING','MESSAGE_REQUEST_REJECTED','CHAT_BLOCKED_BY_RECIPIENT'])
    assert.equal(introPolicyError(new Error(code)),code)
  assert.equal(introPolicyError(new Error('unrelated error')),null)
})
test('first-message alert does not also create a friendship alert',async()=>{
  assert.equal(await publishAutomaticFriendRequest(),0)
})
