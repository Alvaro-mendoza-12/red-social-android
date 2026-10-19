const test=require('node:test'),assert=require('node:assert/strict');
const {createRoomChatTransport}=require('../room-chat-transport');
const {createRoomChatLedger,ROOM_CHAT_TTL_MS}=require('../room-chat-policy');
function node(distributed=true){
 const listeners={},received=[],outbound=[];const io={local:{to:room=>({emit:(event,data)=>received.push({room,event,data})})},on:(event,fn)=>listeners[event]=fn,serverSideEmit:(event,data)=>outbound.push({event,data})};const ledger=createRoomChatLedger();return {send:createRoomChatTransport(io,ledger,()=>distributed),ledger,listeners,received,outbound}
}
test('messages relay across nodes through server Pub/Sub and never through an ordinary distributed broadcast',()=>{
 const a=node(),b=node(),message={id:'message',userId:'owner',userName:'Owner',roomId:'room',text:'temporary',timestamp:Date.now(),expiresAt:Date.now()+ROOM_CHAT_TTL_MS};a.ledger.accept(message);a.send('chat-message',message)
 assert.equal(a.received.length,1);const forwarded=a.outbound[0];b.listeners[forwarded.event](forwarded.data);b.listeners[forwarded.event](forwarded.data);assert.equal(b.received.length,1)
 b.listeners[forwarded.event]({event:'chat-message-deleted',data:{id:message.id,userId:'owner',roomId:'room'}})
 assert.equal(b.received.at(-1).event,'chat-message-deleted');b.listeners[forwarded.event](forwarded.data);assert.equal(b.received.length,2)
})
test('standalone servers deliver locally without requiring a Redis adapter',()=>{
 const a=node(false);a.send('chat-message-deleted',{roomId:'room',userId:'owner',id:'id'});assert.equal(a.outbound.length,0);assert.equal(a.received.length,1)
})
