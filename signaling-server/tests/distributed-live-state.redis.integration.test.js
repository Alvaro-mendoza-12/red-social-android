'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const test = require('node:test');
const { createClient } = require('redis');
const { createDistributedLiveState } = require('../distributed-live-state');

const redisUrl = String(process.env.NOVVIN_TEST_REDIS_URL || '').trim();

test('Redis enforces Live host lease contention atomically', { skip: !redisUrl }, async (t) => {
    const client = createClient({ url: redisUrl });
    const prefix = `novvin:test:live:${crypto.randomUUID()}:`;
    await client.connect();
    t.after(async () => {
        const keys = await client.keys(`${prefix}*`);
        if (keys.length > 0) await client.del(keys);
        await client.quit();
    });

    const storeA = createDistributedLiveState(client, {
        env: { SIGNALING_LIVE_STATE_PREFIX: prefix }
    });
    const storeB = createDistributedLiveState(client, {
        env: { SIGNALING_LIVE_STATE_PREFIX: prefix }
    });

    assert.equal(await storeA.renewHostLease('live-1', 'instance-a:socket-a'), true);
    assert.equal(await storeB.renewHostLease('live-1', 'instance-b:socket-b'), false);
    assert.equal(await storeB.renewHostLease(
        'live-1',
        'instance-b:socket-b',
        'instance-a:socket-a'
    ), true);
    assert.equal(await storeA.releaseHostLease('live-1', 'instance-a:socket-a'), false);
    assert.equal(await storeB.releaseHostLease('live-1', 'instance-b:socket-b'), true);
});

test('ending a Live releases its host lease and clears only that Live', { skip: !redisUrl }, async (t) => {
    const client = createClient({ url: redisUrl });
    await client.connect();
    const prefix = `novvin:test:live:${crypto.randomUUID()}:`;
    t.after(async () => {
        try {
            const keys = await client.keys(`${prefix}*`);
            if (keys.length) await client.del(keys);
        } finally {
            await client.quit();
        }
    });
    const store = createDistributedLiveState(client, { env: { SIGNALING_LIVE_STATE_PREFIX: prefix } });
    await store.save('ending', { host: { userId: 'host' } });
    await store.renewHostLease('ending', 'first-owner');
    await store.renewHostLease('ongoing', 'other-owner');
    await store.scheduleHostDeadline('ending', Date.now() + 10_000);

    assert.equal(await store.remove('ending'), true);
    assert.equal(await store.load('ending'), null);
    assert.deepEqual(await store.listActiveIds(), []);
    assert.equal(await store.hostDeadline('ending'), null);
    assert.equal(await store.hasHostLease('ending'), false);
    assert.equal(await store.hasHostLease('ongoing'), true);
    assert.equal(await store.renewHostLease('ending', 'next-owner'), true);
});
