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
