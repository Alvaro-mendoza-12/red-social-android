'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    MAX_ICE_CANDIDATE_LENGTH,
    MAX_SDP_LENGTH,
    normalizeSignalPayload,
} = require('../payload-policy');

test('accepts bounded WebRTC offer and ICE payloads', () => {
    assert.deepEqual(
        normalizeSignalPayload({
            target: 'socket-2',
            userId: 'user-1',
            name: 'Ana',
            offer: { type: 'offer', sdp: 'v=0\r\n' },
        }, 'offer'),
        {
            target: 'socket-2',
            userId: 'user-1',
            name: 'Ana',
            offer: { type: 'offer', sdp: 'v=0\r\n' },
        },
    );
    assert.ok(normalizeSignalPayload({
        target: 'socket-2',
        userId: 'user-1',
        candidate: { candidate: '', sdpMid: '0', sdpMLineIndex: 0 },
    }, 'ice-candidate'));
});

test('rejects malformed or oversized signaling payloads', () => {
    assert.equal(normalizeSignalPayload({}, 'offer'), null);
    assert.equal(normalizeSignalPayload({
        target: 'socket-2', userId: 'user-1', offer: { type: 'answer', sdp: 'v=0' },
    }, 'offer'), null);
    assert.equal(normalizeSignalPayload({
        target: 'socket-2', userId: 'user-1', offer: { type: 'offer', sdp: 'x'.repeat(MAX_SDP_LENGTH + 1) },
    }, 'offer'), null);
    assert.equal(normalizeSignalPayload({
        target: 'socket-2', userId: 'user-1', candidate: { candidate: 'x'.repeat(MAX_ICE_CANDIDATE_LENGTH + 1) },
    }, 'ice-candidate'), null);
});
