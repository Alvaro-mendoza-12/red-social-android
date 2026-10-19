const test=require('node:test'),assert=require('node:assert/strict');
const {validRoomImage,roomChatMessage,createRoomChatLedger,ROOM_CHAT_TTL_MS}=require('../room-chat-policy');
const {validateSocketPayload}=require('../socket-event-guard');
function photo(width=720,height=480){const data=Buffer.from([255,216,255,192,0,17,8,height>>8,height&255,width>>8,width&255,3,1,17,0,2,17,1,3,17,1,255,217]);return 'data:image/jpeg;base64,'+data.toString('base64')}

test('session ledger authorizes deletion, prevents retry resurrection, and expires metadata without retaining media',()=>{
 let at=1000;const ledger=createRoomChatLedger({now:()=>at,ttl:100,capacity:2})
 const message={roomId:'r',userId:'owner',id:'id',text:'secret',imageData:photo()}
 const first=ledger.accept(message);assert.equal(first.expiresAt,1100);assert.equal(ledger.accept(message).duplicate,true)
 assert.equal(ledger.accept({...message,userId:'attacker'}).ok,false)
 assert.equal(ledger.remove('r','attacker','id').ok,false)
 assert.equal(ledger.remove('r','owner','id').ok,true)
 assert.equal(ledger.accept(message).ok,false)
 ledger.accept({...message,id:'second'})
 assert.equal(ledger.accept({...message,id:'third'}).error,'ROOM_CHAT_BUSY')
 at=1101;const expired=ledger.expire();assert.equal(ledger.size(),0)
 assert.deepEqual(expired,[{roomId:'r',userId:'owner',id:'second',reason:'expired'}])
 assert.equal(ROOM_CHAT_TTL_MS,90*60*1000)
})
test('deletion after metadata loss is scoped to the authenticated author and blocks retries',()=>{
 const ledger=createRoomChatLedger()
 assert.deepEqual(ledger.remove('r','owner','old'),{ok:true,id:'old',roomId:'r',userId:'owner',reason:'deleted'})
 assert.equal(ledger.accept({roomId:'r',userId:'owner',id:'old',text:'late retry'}).ok,false)
 assert.equal(ledger.remove('r','attacker','old').ok,false)
})
test('bounded JPEG frame, dimensions and content are validated',()=>{
 assert.equal(validRoomImage(photo()),true);assert.equal(validRoomImage(photo(721)),false);
 for(const raw of ['https://outside.example/x.jpg','data:image/svg+xml,<svg/>','data:image/jpeg;base64,abcd',photo()+'a'.repeat(140000)])assert.equal(validRoomImage(raw),false);
 const value=roomChatMessage({imageData:photo(),userName:'Spoof',userId:'other'},'joined-room','authenticated-user','Known member');
 assert.equal(value.userId,'authenticated-user');assert.equal(value.userName,'Known member');assert.equal(value.text,'📷 Foto');
 assert.equal(roomChatMessage({text:''},'r','u','n'),null);
 assert.equal(roomChatMessage({text:'safe',imageData:'javascript:alert(1)'},'r','u','n'),null);
});
test('only chat photo fields receive the higher transport limit',()=>{
 const data={roomId:'room',userId:'user',text:'photo',imageData:'x'.repeat(9000)};
 assert.equal(validateSocketPayload('chat-message',data).ok,true);
 assert.equal(validateSocketPayload('reaction',data).ok,false);
 assert.equal(validateSocketPayload('chat-message',{...data,text:'x'.repeat(9000)}).ok,false);
});
