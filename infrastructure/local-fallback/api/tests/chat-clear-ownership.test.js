const test = require('node:test')
const assert = require('node:assert/strict')
const { _test } = require('../src/postgrest-compat-routes')
const actor = '11111111-1111-4111-8111-111111111111'
const peer = '22222222-2222-4222-8222-222222222222'
const identity = { ids: new Set([actor]), values: [actor], preferredUserId: actor }
const noQuery = { query() { throw Error('Unauthorized state change reached database') } }
test('a participant cannot clear, hide or edit the peer membership through compatibility routes', async () => {
 for (const patch of [{cleared_at: new Date().toISOString()}, {is_archived:true}, {user_id:actor}, {last_read_at:new Date().toISOString()}])
  assert.equal(await _test.authorizeMutation(noQuery, 'chat_participants', identity, {chat_id:'shared',user_id:peer}, patch, 'patch'), false)
})
test('shared chat and membership rows cannot be removed through generic delete', async () => {
 for (const table of ['chats','chat_participants']) assert.equal(await _test.authorizeMutation(noQuery, table, identity, {id:'shared',chat_id:'shared',user_id:actor}, {}, 'delete'), false)
})
test('chat updates cannot replace participants or manufacture a shared clear cutoff', async () => {
 for (const patch of [{participants:[actor]},{cleared_at:new Date().toISOString()},{id:'changed'}])
  assert.equal(await _test.authorizeMutation(noQuery,'chats',identity,{id:'shared'},patch,'patch'),false)
})

const {conversationHiddenForViewer}=require('../src/data-routes')
test('hiding an already cleared chat stays hidden with no new message activity',()=>{
 assert.equal(conversationHiddenForViewer('2026-10-17T10:00:00Z',''),true)
 assert.equal(conversationHiddenForViewer('2026-10-17T10:00:00Z','2026-10-17T09:59:59Z'),true)
 assert.equal(conversationHiddenForViewer('2026-10-17T10:00:00Z','2026-10-17T10:00:00Z'),true)
})
test('only newer message activity restores a hidden conversation',()=>{
 assert.equal(conversationHiddenForViewer('2026-10-17T10:00:00Z','2026-10-17T10:00:00.001Z'),false)
 assert.equal(conversationHiddenForViewer(null,'2026-10-17T10:00:00Z'),false)
})
