const assert = require('node:assert/strict')
const test = require('node:test')
const { readControlData, createConversationReadPublisher, latestConversationReadAt } = require('../src/conversation-read-sync')
const { buildData } = require('../src/push-notifications')
const account='11111111-1111-4111-8111-111111111111', room='22222222-2222-4222-8222-222222222222'
const ids=Array.from({length:70},(_,i)=>`33333333-3333-4333-8333-${String(i).padStart(12,'0')}`)
const notification={type:'conversation_read',recipient_id:account,target_type:'chat',target_id:room,content:'must not leak'}

test('combining canonical and imported aliases never regresses a conversation read cursor',()=>{
 const recent='2026-10-19T05:00:00Z',old='2026-10-18T05:00:00Z'
 for(const entries of [[old,recent,''],[recent,old,''],['',old,recent]])assert.equal(entries.reduce(latestConversationReadAt,''),recent)
 assert.equal(latestConversationReadAt(recent,'invalid'),recent)
})

test('read controls contain only account scope and read identities, never message text or a visible notification',()=>{
 const data=buildData(notification,{message_ids:ids.slice(0,32),content:'private',media_url:'private'})
 assert.equal(data.type,'conversation_read');assert.equal(data.recipient_id,account)
 assert.equal(data.conversation_id,room);assert.deepEqual(JSON.parse(data.message_ids),ids.slice(0,32))
 assert.ok(Buffer.byteLength(JSON.stringify(data))<4096)
 assert.equal(data.content,undefined);assert.equal(data.media_url,undefined);assert.equal(data.title,undefined)
 // Older clients must not misroute the control as a new message or system alert.
 assert.equal(data.body,undefined);assert.equal(data.chat_id,undefined);assert.equal(data.target_id,undefined)
})
test('read controls reject malformed batches, empty events, unrelated scopes and invalid cutoffs',()=>{
 for(const extra of [{},{message_ids:['bad']},{message_ids:ids},{message_ids:'invalid'},{read_through:'invalid'}])
  assert.equal(readControlData(notification,extra),null)
 assert.equal(readControlData({...notification,target_type:'voice'},{message_ids:ids.slice(0,1)}),null)
 assert.equal(readControlData(notification,{read_through:'2026-10-18T12:00:00Z'}).read_through,'2026-10-18T12:00:00.000Z')
})
test('read state reaches only the readers private aliases and all their devices in bounded batches',async()=>{
 const events=[],pushes=[]
 const publish=createConversationReadPublisher({pool:{},publish:async(channel,payload)=>events.push({channel,payload}),push:async(pool,n,extra)=>pushes.push({n,extra})})
 await publish({identity:{displayUserId:account,ids:new Set([account,'alias'])},kind:'community',id:room,messageIds:[...ids,ids[0]]})
 assert.equal(events.length,6);assert.equal(pushes.length,3)
 assert.deepEqual([...new Set(events.map(e=>e.channel))],[`user:${account}`,'user:alias'])
 assert.ok(events.every(e=>e.payload.accountId===e.channel.slice(5)&&e.payload.kind==='community'&&e.payload.messageIds.length<=32))
 assert.deepEqual(pushes.flatMap(p=>p.extra.message_ids),ids)
 assert.ok(pushes.every(p=>p.n.recipient_id===account&&p.n.type==='conversation_read'))
})
test('full read cutoff is preserved without deriving a later client time or manufacturing message IDs',async()=>{
 const events=[],pushes=[]
 const publish=createConversationReadPublisher({pool:{},publish:async(c,p)=>events.push(p),push:async(p,n,e)=>pushes.push(e)})
 await publish({identity:{displayUserId:account,ids:new Set([account])},kind:'group',id:room,through:'2026-10-18T12:00:00Z'})
 assert.equal(events.length,1);assert.deepEqual(events[0].messageIds,[])
 assert.equal(events[0].through,'2026-10-18T12:00:00Z');assert.equal(pushes[0].read_through,events[0].through)
 await publish({identity:{displayUserId:account,ids:new Set([account])},kind:'voice',id:room,messageIds:ids})
 assert.equal(events.length,1)
})
