'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { updateVoiceRoomSettings, deleteVoiceRoomRecord, visibleVoiceDirectoryRows } = require('../src/voice-room-settings')
const room = { id: 'room', creator_id: 'Owner', is_public: false, password_hash: 'existing-hash', readable_id: '100031', participant_count: 3 }
function db() { const writes=[]; return { writes, query: async (sql, args) => { writes.push({sql,args}); return {rows:[{...room,name:args[1],is_public:args[2],password_hash:args[3]}]} } } }
test('editing a private room preserves its password and immutable room identity', async () => {
 const pool=db();const result=await updateVoiceRoomSettings(pool,room,new Set(['owner']),{name:' Sala nueva ',isPublic:false})
 assert.equal(result.password_hash,'existing-hash');assert.equal(result.readable_id,'100031');assert.equal(result.name,'Sala nueva')
 assert.match(pool.writes[0].sql,/lower\(creator_id\)=ANY/);assert.doesNotMatch(pool.writes[0].sql,/SET.*readable_id/)
})
test('a nonowner or community-managed room cannot be modified', async () => {
 const pool=db();await assert.rejects(()=>updateVoiceRoomSettings(pool,room,new Set(['other']),{name:'X',isPublic:true}),{code:'VOICE_ROOM_UPDATE_DENIED'})
 await assert.rejects(()=>updateVoiceRoomSettings(pool,{...room,community_id:'community'},new Set(['owner']),{name:'X',isPublic:true}),{code:'VOICE_ROOM_COMMUNITY_MANAGED'})
 assert.equal(pool.writes.length,0)
})
test('changing visibility or password validates before writing and hashes instead of storing plaintext', async () => {
 const pool=db();const actors=new Set(['owner'])
 await assert.rejects(()=>updateVoiceRoomSettings(pool,{...room,password_hash:null},actors,{name:'X',isPublic:false}),{code:'VOICE_ROOM_PASSWORD_REQUIRED'})
 await assert.rejects(()=>updateVoiceRoomSettings(pool,room,actors,{name:'X',isPublic:false,password:'😀'.repeat(20)}),{code:'VOICE_ROOM_PASSWORD_INVALID'})
 assert.equal(pool.writes.length,0)
 await updateVoiceRoomSettings(pool,room,actors,{name:'X',isPublic:false,password:'new-password'},async ()=>'new-hash')
 assert.equal(pool.writes[0].args[3],'new-hash')
 await updateVoiceRoomSettings(pool,room,actors,{name:'X',isPublic:true});assert.equal(pool.writes[1].args[3],null)
})
test('a deleted room or concurrent ownership change cannot report a saved update', async () => {
 await assert.rejects(()=>updateVoiceRoomSettings({query:async()=>({rows:[]})},room,new Set(['owner']),{name:'X',isPublic:true}),{code:'VOICE_ROOM_NOT_FOUND'})
})

test('an authorized imported edit retains its channel and alias and cannot resurrect an existing tombstone', async () => {
 const queries=[]
 const legacy={...room,agora_channel_name:'same-channel',readable_id:'old-alias'}
 const pool={query:async(sql,args)=>{queries.push({sql,args});return {rows:queries.length===2?[{...legacy,name:args[1]}]:[]}}}
 const updated=await updateVoiceRoomSettings(pool,legacy,new Set(['owner']),{name:'Renamed',isPublic:false},undefined,{allowLegacyInsert:true})
 assert.equal(updated.name,'Renamed');assert.equal(queries[1].args[7],'same-channel');assert.equal(queries[1].args[8],'old-alias')
 assert.match(queries[1].sql,/ON CONFLICT \(id\) DO NOTHING/)
 await assert.rejects(()=>updateVoiceRoomSettings({query:async()=>({rows:[]})},legacy,new Set(['owner']),{name:'X',isPublic:true},undefined,{allowLegacyInsert:true}),{code:'VOICE_ROOM_NOT_FOUND'})
})

test('deleting an imported room creates a durable inactive record and rejects a concurrent owner change', async () => {
 const queries=[]
 await deleteVoiceRoomRecord({query:async(sql,args)=>{queries.push({sql,args});return {rows:[{id:room.id}]}}},room,new Set(['owner']))
 assert.match(queries[0].sql,/FALSE/);assert.match(queries[0].sql,/WHERE lower\(fallback_voice_rooms.creator_id\)=ANY/)
 await assert.rejects(()=>deleteVoiceRoomRecord({query:async()=>({rows:[]})},room,new Set(['owner'])),{code:'VOICE_ROOM_DELETE_DENIED'})
 await assert.rejects(()=>deleteVoiceRoomRecord({query:async()=>{throw Error('must not write')}},room,new Set(['other'])),{code:'VOICE_ROOM_DELETE_DENIED'})
})

test('a stale import cannot make a deleted room reappear in discovery or semantic search', async () => {
 const rows=await visibleVoiceDirectoryRows({query:async()=>({rows:[{id:'deleted'}]})},[{id:'deleted',is_active:true},{id:'inactive',is_active:false},{id:'active',is_active:true}])
 assert.deepEqual(rows,[{id:'active',is_active:true}])
 assert.deepEqual(await visibleVoiceDirectoryRows({query:async()=>{throw Error('empty reads no database')}},[]),[])
})
