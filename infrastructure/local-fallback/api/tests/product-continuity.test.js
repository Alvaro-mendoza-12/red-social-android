const test=require('node:test'),assert=require('node:assert/strict')
const {collectionMembershipStateFromSnapshot:state,collectionHistoryAllows:allows}=require('../src/collection-membership-policy')
const {reactionNotice}=require('../src/message-reaction-notifications')
const joined='2026-01-01T00:00:00Z',later='2026-10-18T00:00:00Z'
function snapshot(){return {kind:'group',roomId:'room',oldRooms:[],localRooms:{rows:[{id:'room',creator_id:'owner'}]},oldMembers:[{user_id:'member',joined_at:joined,role:'member'}],localMembers:{rows:[{user_id:'member',joined_at:later,role:'admin'}]},policies:{rows:[]},grants:{rows:[]},archives:{rows:[]}}}
test('metadata shadow cannot reset uninterrupted imported membership',()=>{
 const result=state(snapshot(),['member'])
 assert.equal(result.history.joinedAt,'2026-01-01T00:00:00.000Z')
 assert.equal(allows(result.history,{created_at:'2026-05-01'}),true)
 assert.equal(result.canManage,true)
})
test('explicit rejoin epoch is never widened to the imported period',()=>{
 const input=snapshot();input.grants.rows=[{user_id:'member',joined_at:later}]
 const result=state(input,['member']);assert.equal(result.history.joinedAt,'2026-10-18T00:00:00.000Z')
 assert.equal(allows(result.history,{created_at:'2026-10-01'}),false)
})
test('departure archive prevents old imported membership from filling a gap',()=>{
 const input=snapshot();input.archives.rows=[{user_id:'member',departed_at:'2026-02-01',read_ranges:[{from:joined,until:'2026-02-01'}]}]
 const result=state(input,['member']);assert.equal(allows(result.history,{created_at:'2026-01-05'}),true)
 assert.equal(allows(result.history,{created_at:'2026-07-01'}),false)
})
test('a tombstone still denies current membership despite imported aliases',()=>{
 const input=snapshot();input.localMembers.rows[0].is_deleted=true
 assert.equal(state(input,['member']).member,false)
})
test('reaction notice is only for an added reaction from another person',()=>{
 const input={actorId:'actor',actorIds:['actor','actor-alias'],ownerId:'owner',emoji:'❤️',reactions:{'❤️':['actor-alias']}}
 assert.equal(reactionNotice(input).ownerId,'owner')
 assert.equal(reactionNotice({...input,ownerId:'actor-alias'}),null)
 assert.equal(reactionNotice({...input,reactions:{}}),null)
 assert.equal(reactionNotice({...input,deleted:true}),null)
 assert.equal(reactionNotice({...input,deletedBy:['owner-alias'],ownerIds:['owner','owner-alias']}),null)
})
