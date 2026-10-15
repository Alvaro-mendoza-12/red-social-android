'use strict';
const assert = require('assert/strict');
const { registerSafeSocketEvent, validateSocketPayload } = require('../socket-event-guard');

async function run() {
  const callbacks = new Map();
  const events = [];
  const socket = { on(name, fn) { callbacks.set(name, fn); return this; }, emit(name, data) { events.push({ name, data }); } };
  let invoked = 0;
  registerSafeSocketEvent(socket, 'chat-message', data => { invoked++; return data.userId; });
  for (const payload of [undefined, null, 0, false, '', [], { text: 'x'.repeat(300000) }]) await callbacks.get('chat-message')(payload);
  assert.equal(invoked, 0);
  assert.equal(events.length, 7);
  await callbacks.get('chat-message')({ roomId: 'room', userId: 'user', message: 'hola' });
  assert.equal(invoked, 1);
  let caught = 0;
  registerSafeSocketEvent(socket, 'reaction', () => { throw new Error('sync'); }, () => { caught++; });
  await callbacks.get('reaction')({});
  registerSafeSocketEvent(socket, 'reaction', async () => { throw new Error('async'); }, () => { caught++; });
  await callbacks.get('reaction')({});
  registerSafeSocketEvent(socket, 'disconnecting', async () => { throw new Error('cleanup'); }, () => { caught++; });
  await callbacks.get('disconnecting')('transport close');
  assert.equal(caught, 3);
  assert.equal(validateSocketPayload('chat-message', JSON.parse('{"__proto__": {"userId":"other"}}')).ok, false);
  assert.equal(validateSocketPayload('media-status', { isCameraSharing: 'true' }).ok, false);
  assert.equal(validateSocketPayload('offer', { target: 'peer', userId: 'user', offer: { type: 'offer', sdp: 'v=0' } }).ok, true);
  assert.equal(validateSocketPayload('offer', { target: 'peer', userId: 'user', offer: { type: 'answer', sdp: 'v=0' } }).ok, false);
  console.log('socket event guard regression passed');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
