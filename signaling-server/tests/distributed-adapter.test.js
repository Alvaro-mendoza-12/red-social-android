'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {
    buildRedisAdapterConfig,
    publicRedisStatus
} = require('../distributed-adapter');

test('requires Redis Streams by default in production', () => {
    const config = buildRedisAdapterConfig({ NODE_ENV: 'production' });
    assert.equal(config.required, true);
    assert.equal(config.url, '');
});

test('accepts bounded Redis Streams configuration without exposing credentials', () => {
    const config = buildRedisAdapterConfig({
        NODE_ENV: 'production',
        SIGNALING_REDIS_URL: 'rediss://redis.internal:6379',
        SIGNALING_REDIS_PASSWORD: 'top-secret',
        SIGNALING_REDIS_STREAM_COUNT: '999',
        SIGNALING_REDIS_STREAM_MAX_LENGTH: '10',
        SIGNALING_REDIS_MAX_RECONNECT_ATTEMPTS: '999'
    });
    assert.equal(config.required, true);
    assert.equal(config.streamCount, 32);
    assert.equal(config.maxLen, 1_000);
    assert.equal(config.maxReconnectAttempts, 100);
    assert.equal(config.passwordFileConfigured, false);

    const status = publicRedisStatus(config, true);
    assert.equal(status.mode, 'redis-streams');
    assert.equal(status.connected, true);
    assert.equal(status.connectionRecovery, false);
    assert.equal(JSON.stringify(status).includes('top-secret'), false);
    assert.equal(JSON.stringify(status).includes('redis.internal'), false);
});

test('reports connection recovery only when it is explicitly enabled', () => {
    const config = buildRedisAdapterConfig({
        SIGNALING_REDIS_URL: 'redis://redis.internal:6379',
        SIGNALING_CONNECTION_RECOVERY_ENABLED: 'true'
    });
    assert.equal(publicRedisStatus(config, true).connectionRecovery, true);
});

test('rejects non-Redis adapter URLs', () => {
    assert.throws(
        () => buildRedisAdapterConfig({ SIGNALING_REDIS_URL: 'https://redis.internal' }),
        /redis:\/\/ or rediss:\/\//
    );
});
