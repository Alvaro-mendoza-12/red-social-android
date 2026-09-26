'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {
    buildDistributedLiveConfig,
    createDistributedLiveState,
    hydrateLiveSession,
    parseLiveSnapshot,
    serializeLiveSession
} = require('../distributed-live-state');

function sessionFixture() {
    return {
        host: {
            socketId: 'socket-secret',
            userId: 'host-1',
            name: 'Ana',
            avatarUrl: 'https://cdn.test/ana.jpg',
            authToken: 'bearer-must-never-persist'
        },
        hostAuthToken: 'another-secret',
        viewers: new Map(),
        reactionCount: 42,
        reactionBatchIds: new Set(['batch-1']),
        announcedGiftTransactionIds: new Set(['gift-1']),
        state: { source: 'screen', isPaused: true, videoEnabled: false, micEnabled: true },
        moderation: { chatPaused: true, moderators: new Set(['mod-1']), blockedWords: ['spam'] },
        guestRequests: new Map([['request-socket', { userId: 'guest-1', name: 'Luis', authToken: 'secret' }]]),
        guests: new Map([['guest-socket', { userId: 'guest-2', name: 'Sol' }]]),
        battle: {
            status: 'active',
            opponentSocketId: 'guest-socket',
            opponentUserId: 'guest-2',
            opponentName: 'Sol',
            hostScore: 5,
            opponentScore: 7,
            durationSeconds: 120,
            startedAtMs: 10_000
        }
    };
}

test('serializes recoverable live state without bearer tokens or socket identifiers', () => {
    const snapshot = serializeLiveSession('live-1', sessionFixture(), 20_000);
    const encoded = JSON.stringify(snapshot);
    assert.equal(snapshot.hostConnected, true);
    assert.equal(snapshot.host.userId, 'host-1');
    assert.equal(snapshot.battle.opponentUserId, 'guest-2');
    assert.doesNotMatch(encoded, /bearer-must-never-persist|another-secret|socket-secret|guest-socket|request-socket/);
    assert.doesNotMatch(encoded, /authToken|hostAuthToken|socketId|opponentSocketId/);
});

test('hydrates Redis snapshots into fresh process-local maps and rebindable participants', () => {
    const restored = hydrateLiveSession(serializeLiveSession('live-1', sessionFixture()));
    assert.equal(restored.host, null);
    assert.equal(restored.hostAuthToken, '');
    assert.equal(restored.pendingHostSnapshot.userId, 'host-1');
    assert.equal(restored.guests.size, 0);
    assert.equal(restored.recoverableGuests.get('guest-2').name, 'Sol');
    assert.equal(restored.battle.opponentSocketId, '');
    assert.equal(restored.battle.status, 'active');
});

test('rejects malformed and unsupported live snapshots', () => {
    assert.equal(parseLiveSnapshot('{invalid'), null);
    assert.equal(parseLiveSnapshot({ version: 99, liveId: 'live-1' }), null);
    assert.equal(parseLiveSnapshot({ version: 1, liveId: '' }), null);
});

test('bounds live state retention and lease settings', () => {
    const config = buildDistributedLiveConfig({
        SIGNALING_LIVE_STATE_TTL_SECONDS: '1',
        SIGNALING_LIVE_HOST_LEASE_SECONDS: '9999',
        SIGNALING_LIVE_TERMINATION_LOCK_MS: '1'
    });
    assert.equal(config.stateTtlSeconds, 300);
    assert.equal(config.hostLeaseSeconds, 300);
    assert.equal(config.terminationLockMs, 5_000);
});

function createLeaseRedisFixture() {
    const values = new Map();
    return {
        values,
        get: async (key) => values.get(key) || null,
        set: async (key, value, options = {}) => {
            if (options.NX && values.has(key)) return null;
            values.set(key, value);
            return 'OK';
        },
        sAdd: async () => 1,
        sMembers: async () => [],
        sRem: async () => 1,
        del: async (...keys) => keys.reduce((count, key) => count + (values.delete(key) ? 1 : 0), 0),
        exists: async (key) => values.has(key) ? 1 : 0,
        zAdd: async () => 1,
        zRem: async () => 1,
        zScore: async () => null,
        zRangeByScore: async () => [],
        eval: async (script, { keys, arguments: args }) => {
            const [key] = keys;
            const current = values.get(key) || null;
            if (script.includes("redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])")) {
                const [owner, , replaceOwner] = args;
                if (!current || current === owner || (replaceOwner && current === replaceOwner)) {
                    values.set(key, owner);
                    return 1;
                }
                return 0;
            }
            if (script.includes("redis.call('DEL', KEYS[1])")) {
                if (current === args[0]) {
                    values.delete(key);
                    return 1;
                }
                return 0;
            }
            throw new Error(`Unexpected Lua script: ${script}`);
        }
    };
}

test('host leases cannot be stolen by a second signaling instance', async () => {
    const redis = createLeaseRedisFixture();
    const storeA = createDistributedLiveState(redis, { env: {} });
    const storeB = createDistributedLiveState(redis, { env: {} });

    assert.equal(await storeA.renewHostLease('live-1', 'instance-a:socket-a'), true);
    assert.equal(await storeB.renewHostLease('live-1', 'instance-b:socket-b'), false);
    assert.equal(await storeB.renewHostLease(
        'live-1',
        'instance-b:socket-b',
        'instance-a:socket-a'
    ), true);
    assert.equal(await storeA.renewHostLease('live-1', 'instance-a:socket-a'), false);
    assert.equal(await storeA.releaseHostLease('live-1', 'instance-a:socket-a'), false);
    assert.equal(await storeB.releaseHostLease('live-1', 'instance-b:socket-b'), true);
});

test('persists snapshots and active membership through the Redis contract', async () => {
    const calls = [];
    const redis = {
        set: async (...args) => { calls.push(['set', ...args]); return 'OK'; },
        sAdd: async (...args) => { calls.push(['sAdd', ...args]); return 1; },
        get: async () => JSON.stringify(serializeLiveSession('live-1', sessionFixture())),
        del: async (...args) => { calls.push(['del', ...args]); return 2; },
        sRem: async (...args) => { calls.push(['sRem', ...args]); return 1; },
        zRem: async (...args) => { calls.push(['zRem', ...args]); return 1; },
        sMembers: async () => ['live-1'],
        exists: async () => 1,
        zAdd: async (...args) => { calls.push(['zAdd', ...args]); return 1; },
        zScore: async () => '1234',
        zRangeByScore: async () => ['live-1'],
        eval: async () => 1
    };
    const store = createDistributedLiveState(redis, { env: {} });
    assert.equal(await store.save('live-1', sessionFixture()), true);
    assert.equal((await store.load('live-1')).liveId, 'live-1');
    assert.deepEqual(await store.listActiveIds(), ['live-1']);
    assert.equal(await store.hasHostLease('live-1'), true);
    assert.equal(await store.scheduleHostDeadline('live-1', 1234), true);
    assert.equal(await store.hostDeadline('live-1'), 1234);
    assert.deepEqual(await store.dueHostDeadlines(1234), ['live-1']);
    assert.equal(await store.remove('live-1'), true);
    assert.ok(calls.some(([name]) => name === 'set'));
    assert.ok(calls.some(([name]) => name === 'sAdd'));
});
