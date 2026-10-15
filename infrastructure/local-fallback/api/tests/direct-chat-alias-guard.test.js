'use strict'
const test=require('node:test'),assert=require('node:assert/strict')
const {_test:{canonicalDirectChatParticipants}}=require('../src/postgrest-compat-routes')
const pool=ids=>({async query(sql,args){assert.match(sql,/fallback_request_identity/);assert.equal(args[0].length,2);return {rows:ids.map(id=>({id}))}}})
test('direct-chat aliases use the same sorted canonical conversation key',async()=>{
 const first=await canonicalDirectChatParticipants(pool(['b','a']),['b-local','a-local'])
 const second=await canonicalDirectChatParticipants(pool(['a','b']),['a-public','b-public'])
 assert.equal(first.join(':'),second.join(':'));assert.deepEqual(first,['a','b'])
})
test('two aliases of one person cannot create a self-chat',async()=>{
 await assert.rejects(canonicalDirectChatParticipants(pool(['a','a']),['a-local','a-public']),/DIRECT_CHAT_REQUIRES_TWO_PARTICIPANTS/)
})
test('empty canonical database results fail closed',async()=>{
 await assert.rejects(canonicalDirectChatParticipants(pool([]),['a','b']),/DIRECT_CHAT_REQUIRES_TWO_PARTICIPANTS/)
})
test('group-sized and empty direct participant lists are rejected before the database',async()=>{
 const forbidden={query(){throw Error('unexpected query')}}
 for(const value of [[],['a'],['a','b','c']])await assert.rejects(canonicalDirectChatParticipants(forbidden,value),/DIRECT_CHAT_REQUIRES_TWO_PARTICIPANTS/)
})
