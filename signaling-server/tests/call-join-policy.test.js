'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {authorizeCallJoin}=require('../call-join-policy');
const call='11111111-1111-4111-8111-111111111111',other='22222222-2222-4222-8222-222222222222',user='33333333-3333-4333-8333-333333333333';
const socket=()=>({connected:true,data:{joinedCallIds:new Set()}});
const access=(id=call)=>({allowed:true,callId:id,userId:user,state:'active',expiresAt:'2099-01-01T00:00:00.000Z'});
test('invalid identifier is denied before API lookup',async()=>{
 let queried=false;const result=await authorizeCallJoin({socket:socket(),callId:'not-a-uuid',userId:user,lookupAccess:async()=>{queried=true;return access();}});
 assert.equal(result.error,'CALL_ACCESS_DENIED');assert.equal(queried,false);
});
for(const state of ['ringing','active'])test(`authorized ${state} call may join and release reservation`,async()=>{
 const s=socket();const result=await authorizeCallJoin({socket:s,callId:call,userId:user,lookupAccess:async()=>({...access(),state})});
 assert.equal(result.allowed,true);assert.equal(s.data.pendingCallJoins.has(call),true);result.release();assert.equal(s.data.pendingCallJoins.size,0);
});
for(const [name,value] of [['wrong call',{callId:other}],['wrong user',{userId:other}],['ended',{state:'ended'}],['not allowed',{allowed:false}]])test(`API response ${name} fails closed`,async()=>{
 const s=socket();const result=await authorizeCallJoin({socket:s,callId:call,userId:user,lookupAccess:async()=>({...access(),...value})});
 assert.equal(result.error,'CALL_ACCESS_DENIED');assert.equal(s.data.pendingCallJoins.size,0);
});
for(const [status,expected] of [[503,'CALL_ACCESS_UNAVAILABLE'],[429,'CALL_ACCESS_UNAVAILABLE'],[404,'CALL_ACCESS_DENIED']])test(`API ${status} produces controlled ${expected}`,async()=>{
 const s=socket();const result=await authorizeCallJoin({socket:s,callId:call,userId:user,lookupAccess:async()=>{throw Object.assign(new Error('fixture'),{status});}});
 assert.equal(result.error,expected);assert.equal(s.data.pendingCallJoins.size,0);
});
test('parallel different calls cannot bypass per socket quota',async()=>{
 const s=socket();let release;const gate=new Promise(resolve=>{release=resolve});
 const first=authorizeCallJoin({socket:s,callId:call,userId:user,lookupAccess:async()=>{await gate;return access();}});
 let queried=false;const second=await authorizeCallJoin({socket:s,callId:other,userId:user,lookupAccess:async()=>{queried=true;return access(other);}});
 assert.equal(second.error,'CALL_SCOPE_LIMIT_REACHED');assert.equal(queried,false);release();const joined=await first;joined.release();
});
test('parallel same call is not admitted twice',async()=>{
 const s=socket();let release;const gate=new Promise(resolve=>{release=resolve});
 const first=authorizeCallJoin({socket:s,callId:call,userId:user,lookupAccess:async()=>{await gate;return access();}});
 const second=await authorizeCallJoin({socket:s,callId:call,userId:user,lookupAccess:async()=>access()});
 assert.equal(second.error,'CALL_JOIN_IN_PROGRESS');release();(await first).release();
});
test('disconnect while permission lookup waits denies and clears reservation',async()=>{
 const s=socket();const result=await authorizeCallJoin({socket:s,callId:call,userId:user,lookupAccess:async()=>{s.connected=false;return access();}});
 assert.equal(result.error,'CALL_ACCESS_DENIED');assert.equal(s.data.pendingCallJoins.size,0);
});
test('rejoin to existing scope still requires fresh API authorization',async()=>{
 const s=socket();s.data.joinedCallIds.add(call);let queried=false;
 const result=await authorizeCallJoin({socket:s,callId:call,userId:user,lookupAccess:async()=>{queried=true;return access();}});
 assert.equal(result.allowed,true);assert.equal(queried,true);result.release();
});
