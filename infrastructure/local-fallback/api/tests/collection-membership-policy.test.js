const test=require('node:test'),assert=require('node:assert/strict')
const {collectionHistoryAllows:allows,parseHistoryGrant:grant}=require('../src/collection-membership-policy')
const h={isMember:true,joinedAt:'2026-06-01T12:00:00Z',historyFrom:'2026-03-01T00:00:00Z',historyUntil:'2026-04-01T00:00:00Z'}
for(const [at,expected] of [['2026-02-28T23:59:59.999Z',false],['2026-03-01T00:00:00Z',true],['2026-04-01T00:00:00Z',true],['2026-04-01T00:00:00.001Z',false],['2026-06-01T11:59:59.999Z',false],['2026-06-01T12:00:00Z',true],['2027-01-01T00:00:00Z',true],['invalid',false]])test(`membership history boundary ${at}`,()=>assert.equal(allows(h,{created_at:at}),expected))
test('nonmember and invalid membership epochs fail closed',()=>{assert.equal(allows({...h,isMember:false,allHistory:true},{created_at:h.joinedAt}),false);assert.equal(allows({...h,joinedAt:null},{created_at:h.joinedAt}),false)})
test('creator can read history and share any valid earlier interval',()=>{assert.equal(allows({...h,allHistory:true},{created_at:'2026-01-01'}),true);assert.equal(grant({historyFrom:'2026-01-01',historyUntil:'2026-02-01'},{isMember:true,allHistory:true},'2026-06-01').from,'2026-01-01T00:00:00.000Z')})
test('default grant is empty; incomplete reversed and future intervals are rejected',()=>{assert.deepEqual(grant({},h,'2026-08-01'),{from:null,until:null});for(const body of [{historyFrom:'2026-03-01'},{historyUntil:'2026-03-01'},{historyFrom:'invalid',historyUntil:'2026-03-01'},{historyFrom:'2026-03-01',historyUntil:'2027-03-01'},{historyFrom:'2026-04-01',historyUntil:'2026-03-01'}])assert.throws(()=>grant(body,{allHistory:true},'2026-08-01'),/HISTORY_RANGE_INVALID/)})
test('member can share an accessible interval but cannot bridge an inaccessible gap',()=>{assert.equal(grant({historyFrom:'2026-03-02',historyUntil:'2026-03-03'},h,'2026-08-01').from,'2026-03-02T00:00:00.000Z');assert.throws(()=>grant({historyFrom:'2026-03-02',historyUntil:'2026-07-01'},h,'2026-08-01'),/HISTORY_RANGE_NOT_ACCESSIBLE/)})

test('a zero-length interval inside a history gap must be denied',()=>assert.throws(()=>grant({historyFrom:'2026-04-15',historyUntil:'2026-04-15'},h,'2026-08-01'),/HISTORY_RANGE_NOT_ACCESSIBLE/))

const {freezeCollectionHistory}=require('../src/collection-membership-policy')
test('departure freezes current epoch and explicit grant without filling gaps',()=>{
 const ranges=freezeCollectionHistory(h,'2026-08-01T00:00:00Z')
 const archived={isMember:false,canReadHistory:true,retainedRanges:ranges}
 for(const [at,expected] of [['2026-03-15',true],['2026-05-01',false],['2026-07-01',true],['2026-08-01T00:00:00.001Z',false],['invalid',false]])
  assert.equal(allows(archived,{created_at:at}),expected)
})
test('rejoin keeps already authorized history without exposing the absence interval',()=>{
 const first=freezeCollectionHistory(h,'2026-08-01T00:00:00Z')
 const next={isMember:true,joinedAt:'2026-09-01T00:00:00Z',retainedRanges:first}
 assert.equal(allows(next,{created_at:'2026-07-01'}),true)
 assert.equal(allows(next,{created_at:'2026-08-15'}),false)
 const last={isMember:false,canReadHistory:true,retainedRanges:freezeCollectionHistory(next,'2026-10-01T00:00:00Z')}
 assert.equal(allows(last,{created_at:'2026-09-15'}),true)
 assert.equal(allows(last,{created_at:'2026-08-15'}),false)
 assert.equal(allows(last,{created_at:'2026-10-02'}),false)
})
test('former creator retains previous history but never new messages',()=>{
 const archived={isMember:false,canReadHistory:true,retainedRanges:freezeCollectionHistory({isMember:true,allHistory:true},'2026-08-01')}
 assert.equal(allows(archived,{created_at:'2020-01-01'}),true)
 assert.equal(allows(archived,{created_at:'2026-08-02'}),false)
})
