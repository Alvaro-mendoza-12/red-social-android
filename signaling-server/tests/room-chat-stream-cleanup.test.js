const test=require('node:test'),assert=require('node:assert/strict');
const {createRoomChatStreamCleanup}=require('../room-chat-stream-cleanup');
const {ROOM_CHAT_TTL_MS}=require('../room-chat-policy');
test('only expired legacy room chat frames are removed, preserving other signaling and younger messages',async()=>{
 const calls=[],removed=[],client={xRange:async(stream,from,until)=>{calls.push({stream,from,until});return from==='-'?[{id:'100-0',message:{nsp:'/',data:JSON.stringify({packet:{data:['chat-message',{text:'private'}]}})}},{id:'101-0',message:{nsp:'/',data:JSON.stringify({packet:{data:['offer',{}]}})}},{id:'102-0',message:{nsp:'/call',data:JSON.stringify({packet:{data:['chat-message',{}]}})}}]:[]},xDel:async(stream,ids)=>{removed.push({stream,ids});return ids.length}}
 const prune=createRoomChatStreamCleanup(client,{streamCount:1,streamName:'events'},()=>ROOM_CHAT_TTL_MS+200)
 assert.deepEqual(await prune(),{deleted:1});assert.deepEqual(removed,[{stream:'events',ids:['100-0']}]);assert.equal(calls[0].until,'200-0')
 assert.deepEqual(await prune(),{deleted:0});assert.equal(calls.at(-1).from,'(102-0')
})
