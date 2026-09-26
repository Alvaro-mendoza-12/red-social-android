'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {
    fetchNamespaceScopeMembers,
    MAX_JOINED_SCOPES,
    normalizedScopeIds,
    sharesNamespaceScope
} = require('../distributed-scope');

function namespaceFixture({ localSockets = new Map(), remoteSockets = [] } = {}) {
    const calls = [];
    return {
        calls,
        sockets: localSockets,
        in(scopeIds) {
            calls.push(scopeIds);
            return {
                fetchSockets: async () => remoteSockets
            };
        }
    };
}

test('authorizes a target present in a shared local room without a distributed lookup', async () => {
    const namespace = namespaceFixture({
        localSockets: new Map([['target-2', { rooms: new Set(['room-1']) }]])
    });
    assert.equal(
        await sharesNamespaceScope(namespace, new Set(['room-1']), 'target-2'),
        true
    );
    assert.deepEqual(namespace.calls, []);
});

test('authorizes a target returned by a remote adapter lookup', async () => {
    const namespace = namespaceFixture({ remoteSockets: [{ id: 'target-remote' }] });
    assert.equal(
        await sharesNamespaceScope(namespace, new Set(['room-1', 'room-2']), 'target-remote'),
        true
    );
    assert.deepEqual(namespace.calls, [['room-1', 'room-2']]);
});

test('rejects missing and unshared targets fail closed', async () => {
    const namespace = namespaceFixture({ remoteSockets: [{ id: 'somebody-else' }] });
    assert.equal(await sharesNamespaceScope(namespace, new Set(['room-1']), 'target-2'), false);
    assert.equal(await sharesNamespaceScope(namespace, new Set(), 'target-2'), false);
    assert.equal(await sharesNamespaceScope(namespace, new Set(['room-1']), ''), false);
});

test('bounds attacker-controlled joined scope lookups', () => {
    const scopes = new Set(Array.from({ length: MAX_JOINED_SCOPES + 20 }, (_, index) => `room-${index}`));
    assert.equal(normalizedScopeIds(scopes).length, MAX_JOINED_SCOPES);
});

test('times out a stalled distributed lookup', async () => {
    const namespace = {
        sockets: new Map(),
        in() {
            return { fetchSockets: () => new Promise(() => undefined) };
        }
    };
    await assert.rejects(
        sharesNamespaceScope(namespace, new Set(['room-1']), 'target-2', { timeoutMs: 250 }),
        { code: 'SOCKET_LOOKUP_TIMEOUT' }
    );
});

test('returns only public scope metadata from local and remote sockets', async () => {
    const namespace = namespaceFixture({
        remoteSockets: [
            {
                id: 'socket-1',
                data: {
                    voiceParticipant: { userId: 'user-1', name: 'Ana' },
                    authToken: 'must-not-leak'
                }
            },
            { id: 'socket-2', data: { otherMetadata: { userId: 'user-2' } } }
        ]
    });
    assert.deepEqual(
        await fetchNamespaceScopeMembers(namespace, 'room-1', 'voiceParticipant'),
        [{ socketId: 'socket-1', userId: 'user-1', name: 'Ana' }]
    );
});

test('excludes the requesting socket from distributed participant snapshots', async () => {
    const namespace = namespaceFixture({
        remoteSockets: [
            { id: 'self', data: { callParticipant: { userId: 'user-1' } } },
            { id: 'peer', data: { callParticipant: { userId: 'user-2' } } }
        ]
    });
    assert.deepEqual(
        await fetchNamespaceScopeMembers(namespace, 'call-1', 'callParticipant', { excludeSocketId: 'self' }),
        [{ socketId: 'peer', userId: 'user-2' }]
    );
});
