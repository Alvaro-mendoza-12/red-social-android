'use strict';

const { createAdapter } = require('@socket.io/redis-streams-adapter');
const { createClient } = require('redis');
const fs = require('node:fs');
const {
    createDistributedLiveState,
    createNoopDistributedLiveState
} = require('./distributed-live-state');

const DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
const DEFAULT_STREAM_MAX_LENGTH = 20_000;
const DEFAULT_STREAM_COUNT = 4;

function parseBoolean(value, fallback = false) {
    if (value == null || value === '') return fallback;
    const normalized = String(value).trim().toLowerCase();
    if (['1', 'true', 'yes', 'on'].includes(normalized)) return true;
    if (['0', 'false', 'no', 'off'].includes(normalized)) return false;
    return fallback;
}

function boundedInteger(value, fallback, min, max) {
    const parsed = Number.parseInt(value ?? '', 10);
    if (!Number.isFinite(parsed)) return fallback;
    return Math.min(Math.max(parsed, min), max);
}

function buildRedisAdapterConfig(env = process.env) {
    const production = String(env.NODE_ENV || '').trim().toLowerCase() === 'production';
    const url = String(env.SIGNALING_REDIS_URL || '').trim();
    const required = parseBoolean(env.SIGNALING_REDIS_REQUIRED, production);

    if (url) {
        let parsed;
        try {
            parsed = new URL(url);
        } catch {
            throw new Error('SIGNALING_REDIS_URL must be a valid redis:// or rediss:// URL');
        }
        if (!['redis:', 'rediss:'].includes(parsed.protocol)) {
            throw new Error('SIGNALING_REDIS_URL must use redis:// or rediss://');
        }
    }

    const passwordFile = String(env.SIGNALING_REDIS_PASSWORD_FILE || '').trim();
    let password = String(env.SIGNALING_REDIS_PASSWORD || '').trim() || undefined;
    if (passwordFile) {
        try {
            password = fs.readFileSync(passwordFile, 'utf8').trim() || undefined;
        } catch (error) {
            if (required) {
                const fileError = new Error('SIGNALING_REDIS_PASSWORD_FILE cannot be read');
                fileError.code = 'SIGNALING_REDIS_PASSWORD_FILE_UNREADABLE';
                fileError.cause = error;
                throw fileError;
            }
        }
    }

    return {
        url,
        password,
        passwordFileConfigured: Boolean(passwordFile),
        required,
        connectionRecoveryEnabled: parseBoolean(env.SIGNALING_CONNECTION_RECOVERY_ENABLED, false),
        connectTimeoutMs: boundedInteger(
            env.SIGNALING_REDIS_CONNECT_TIMEOUT_MS,
            DEFAULT_CONNECT_TIMEOUT_MS,
            1_000,
            60_000
        ),
        maxReconnectAttempts: boundedInteger(
            env.SIGNALING_REDIS_MAX_RECONNECT_ATTEMPTS,
            12,
            1,
            100
        ),
        streamName: String(env.SIGNALING_REDIS_STREAM_NAME || 'novvin:signaling:events').trim(),
        channelPrefix: String(env.SIGNALING_REDIS_CHANNEL_PREFIX || 'novvin:signaling').trim(),
        sessionKeyPrefix: String(env.SIGNALING_REDIS_SESSION_PREFIX || 'novvin:signaling:session:').trim(),
        streamCount: boundedInteger(
            env.SIGNALING_REDIS_STREAM_COUNT,
            DEFAULT_STREAM_COUNT,
            1,
            32
        ),
        maxLen: boundedInteger(
            env.SIGNALING_REDIS_STREAM_MAX_LENGTH,
            DEFAULT_STREAM_MAX_LENGTH,
            1_000,
            500_000
        ),
        useShardedPubSub: parseBoolean(env.SIGNALING_REDIS_SHARDED_PUBSUB, true)
    };
}

function publicRedisStatus(config, connected, lastErrorCode = null) {
    return {
        mode: connected ? 'redis-streams' : 'memory',
        configured: Boolean(config.url),
        required: Boolean(config.required),
        connected: Boolean(connected),
        streamCount: connected ? config.streamCount : 0,
        connectionRecovery: connected && config.connectionRecoveryEnabled,
        lastErrorCode
    };
}

async function connectDistributedAdapter(io, options = {}) {
    if (!io || typeof io.adapter !== 'function') {
        throw new TypeError('A Socket.IO Server instance is required');
    }

    const logger = options.logger || console;
    const config = buildRedisAdapterConfig(options.env || process.env);
    let status = publicRedisStatus(config, false);
    let closing = false;
    let connectedOnce = false;

    if (!config.url) {
        if (config.required) {
            throw new Error('SIGNALING_REDIS_URL is required in this environment');
        }
        logger.warn?.('[SIGNALING_REDIS] Redis Streams is not configured; single-instance memory mode only.');
        return {
            getStatus: () => ({ ...status }),
            liveState: createNoopDistributedLiveState(),
            close: async () => undefined
        };
    }

    const redisClient = createClient({
        url: config.url,
        password: config.password,
        socket: {
            connectTimeout: config.connectTimeoutMs,
            reconnectStrategy: (attempts) => {
                if (attempts >= config.maxReconnectAttempts) {
                    return new Error('SIGNALING_REDIS_RECONNECT_EXHAUSTED');
                }
                return Math.min(250 * (attempts + 1), 5_000);
            }
        }
    });

    redisClient.on('error', (error) => {
        const code = String(error?.code || error?.name || 'REDIS_ERROR').slice(0, 80);
        status = publicRedisStatus(config, redisClient.isReady, code);
        logger.error?.('[SIGNALING_REDIS] Redis client error', { code });
    });
    redisClient.on('ready', () => {
        connectedOnce = true;
        status = publicRedisStatus(config, true);
        logger.info?.('[SIGNALING_REDIS] Redis Streams adapter connected');
    });
    redisClient.on('end', () => {
        status = publicRedisStatus(config, false, 'REDIS_CONNECTION_ENDED');
        logger.warn?.('[SIGNALING_REDIS] Redis connection ended');
        if (config.required && connectedOnce && !closing) {
            options.onRequiredDisconnect?.();
        }
    });

    try {
        await redisClient.connect();
        io.adapter(createAdapter(redisClient, {
            streamName: config.streamName,
            streamCount: config.streamCount,
            channelPrefix: config.channelPrefix,
            sessionKeyPrefix: config.sessionKeyPrefix,
            maxLen: config.maxLen,
            useShardedPubSub: config.useShardedPubSub,
            onlyPlaintext: false
        }));
        status = publicRedisStatus(config, true);
    } catch (error) {
        const code = String(error?.code || error?.name || 'REDIS_CONNECT_FAILED').slice(0, 80);
        status = publicRedisStatus(config, false, code);
        if (redisClient.isOpen) {
            redisClient.destroy();
        }
        if (config.required) throw error;
        logger.error?.('[SIGNALING_REDIS] Redis unavailable; continuing in single-instance memory mode', { code });
    }

    return {
        getStatus: () => ({ ...status }),
        liveState: redisClient.isReady
            ? createDistributedLiveState(redisClient, { env: options.env || process.env })
            : createNoopDistributedLiveState(),
        close: async () => {
            closing = true;
            if (redisClient.isOpen) {
                await redisClient.quit();
            }
        }
    };
}

module.exports = {
    buildRedisAdapterConfig,
    connectDistributedAdapter,
    publicRedisStatus
};
