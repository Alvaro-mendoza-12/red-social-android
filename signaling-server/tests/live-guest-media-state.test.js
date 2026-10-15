'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const { updateLiveGuestMediaState } = require('../live-guest-media-state');
const { serializeLiveSession, hydrateLiveSession } = require('../distributed-live-state');
function fixture() {
 const socket = { id: 'guest-socket', data: { liveRole: 'viewer', authUserId: 'guest-user' } };
 const guest = { userId: 'guest-user', name: 'Invitado', avatarUrl: 'https://example.test/photo' };
 return { socket, session: { guests: new Map([[socket.id, guest]]), viewers: new Map([[socket.id, guest]]) } };
}
test('approved guest disables and re-enables only its own camera', () => {
 const {session,socket}=fixture(); assert(updateLiveGuestMediaState(session,socket,{videoEnabled:false,targetSocketId:'other'}));
 assert.equal(session.guests.get(socket.id).videoEnabled,false); assert(updateLiveGuestMediaState(session,socket,{videoEnabled:true}));
 assert.equal(session.guests.get(socket.id).avatarUrl,'https://example.test/photo');
});
test('ordinary viewers and rejected guests cannot change camera state', () => {
 const {session,socket}=fixture(); session.guests.delete(socket.id);
 assert.equal(updateLiveGuestMediaState(session,socket,{videoEnabled:false}),false);
});
test('foreign identity, host role and missing room membership are rejected', () => {
 for(const change of [s=>s.socket.data.authUserId='foreign',s=>s.socket.data.liveRole='host',s=>s.session.viewers.clear()]) {
  const f=fixture();change(f);assert.equal(updateLiveGuestMediaState(f.session,f.socket,{videoEnabled:false}),false);
 }
});
test('only booleans are accepted and repeated state does not broadcast', () => {
 const {session,socket}=fixture();for(const value of ['false',0,null,{},undefined])assert.equal(updateLiveGuestMediaState(session,socket,{videoEnabled:value}),false);
 assert(updateLiveGuestMediaState(session,socket,{videoEnabled:false}));assert.equal(updateLiveGuestMediaState(session,socket,{videoEnabled:false}),false);
});
test('camera off survives a distributed signaling reconnect without persisting credentials', () => {
 const {session,socket}=fixture();updateLiveGuestMediaState(session,socket,{videoEnabled:false});
 const snapshot=serializeLiveSession('live',session);assert.equal(snapshot.guests[0].videoEnabled,false);
 const recovered=hydrateLiveSession(snapshot);assert.equal(recovered.recoverableGuests.get('guest-user').videoEnabled,false);
});
