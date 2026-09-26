'use strict';

const crypto = require('node:crypto');

const LIVE_STATE_VERSION = 1;
const DEFAULT_STATE_TTL_SECONDS = 24 * 60 * 60;
const DEFAULT_HOST_LEASE_SECONDS = 45;
const DEFAULT_TERMINATION_LOCK_MS = 30_000;
const MAX_DEDUPE_IDS = 500;
const MAX_RECOVERABLE_PARTICIPANTS = 16;

function boundedInteger(value, fallback, min, max) {
    const parsed = Number.parseInt(value ?? '', 10);
    if (!Number.isFinite(parsed)) return fallback;
    return Math.min(Math.max(parsed, min), max);
}

function cleanString(value, maxLength = 2_048) {
    return String(value || '')
        .replace(/[\u0000-\u001f\u007f]/g, '')
        .trim()
        .slice(0, maxLength);
}

function publicParticipant(value) {
    const userId = cleanString(value?.userId, 160);
    if (!userId) return null;
    return {
        userId,
        name: cleanString(value?.name, 100) || 'Usuario',
        avatarUrl: cleanString(value?.avatarUrl, 2_048)
    };
}

function uniqueParticipants(values, limit = MAX_RECOVERABLE_PARTICIPANTS) {
    const result = [];
    const seen = new Set();
    for (const rawValue of values || []) {
        const participant = publicParticipant(rawValue);
        if (!participant || seen.has(participant.userId)) continue;
        seen.add(participant.userId);
        result.push(participant);
        if (result.length >= limit) break;
    }
    return result;
}

function mapValues(map) {
    if (!(map instanceof Map)) return [];
    return Array.from(map.values());
}

function boundedIdList(value) {
    const values = value instanceof Set ? Array.from(value) : (Array.isArray(value) ? value : []);
    return values
        .map((entry) => cleanString(entry, 160))
        .filter(Boolean)
        .slice(-MAX_DEDUPE_IDS);
}

function normalizedBattle(value) {
    const battle = value && typeof value === 'object' ? value : {};
    const status = ['none', 'invited', 'active', 'rejected', 'finished'].includes(battle.status)
        ? battle.status
        : 'none';
    return {
        status,
        opponentUserId: cleanString(battle.opponentUserId, 160),
        opponentName: cleanString(battle.opponentName, 100),
        opponentAvatarUrl: cleanString(battle.opponentAvatarUrl, 2_048),
        hostScore: Math.max(0, Number(battle.hostScore || 0) || 0),
        opponentScore: Math.max(0, Number(battle.opponentScore || 0) || 0),
        durationSeconds: boundedInteger(battle.durationSeconds, 180, 30, 600),
        startedAtMs: Math.max(0, Number(battle.startedAtMs || 0) || 0),
        message: cleanString(battle.message, 200)
    };
}

function serializeLiveSession(liveId, session, now = Date.now()) {
    const normalizedLiveId = cleanString(liveId, 128);
    if (!normalizedLiveId || !session || typeof session !== 'object') return null;
    const host = publicParticipant(session.host || session.pendingHostSnapshot);
    const guestRequests = uniqueParticipants([
        ...mapValues(session.guestRequests),
        ...mapValues(session.recoverableGuestRequests)
    ]);
    const guests = uniqueParticipants([
        ...mapValues(session.guests),
        ...mapValues(session.recoverableGuests)
    ]);
    const moderators = session.moderation?.moderators instanceof Set
        ? Array.from(session.moderation.moderators)
        : (Array.isArray(session.moderation?.moderators) ? session.moderation.moderators : []);

    return {
        version: LIVE_STATE_VERSION,
        liveId: normalizedLiveId,
        updatedAtMs: Math.max(0, Number(now) || Date.now()),
        host,
        hostConnected: Boolean(session.host?.socketId),
        state: {
            source: session.state?.source === 'screen' ? 'screen' : 'camera',
            isPaused: Boolean(session.state?.isPaused),
            videoEnabled: session.state?.videoEnabled !== false,
            micEnabled: session.state?.micEnabled !== false
        },
        reactionCount: Math.max(0, Number(session.reactionCount || 0) || 0),
        reactionBatchIds: boundedIdList(session.reactionBatchIds),
        announcedGiftTransactionIds: boundedIdList(session.announcedGiftTransactionIds),
        moderation: {
            chatPaused: Boolean(session.moderation?.chatPaused),
            moderators: moderators.map((entry) => cleanString(entry, 160)).filter(Boolean).slice(0, 200),
            blockedWords: (Array.isArray(session.moderation?.blockedWords) ? session.moderation.blockedWords : [])
                .map((entry) => cleanString(entry, 80))
                .filter(Boolean)
                .slice(0, 200)
        },
        guestRequests,
        guests,
        battle: normalizedBattle(session.battle)
    };
}

function parseLiveSnapshot(rawValue) {
    let parsed = rawValue;
    if (typeof rawValue === 'string') {
        try {
            parsed = JSON.parse(rawValue);
        } catch {
            return null;
        }
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    if (Number(parsed.version) !== LIVE_STATE_VERSION) return null;
    const liveId = cleanString(parsed.liveId, 128);
    if (!liveId) return null;
    return {
        version: LIVE_STATE_VERSION,
        liveId,
        updatedAtMs: Math.max(0, Number(parsed.updatedAtMs || 0) || 0),
        host: publicParticipant(parsed.host),
        hostConnected: Boolean(parsed.hostConnected),
        state: {
            source: parsed.state?.source === 'screen' ? 'screen' : 'camera',
            isPaused: Boolean(parsed.state?.isPaused),
            videoEnabled: parsed.state?.videoEnabled !== false,
            micEnabled: parsed.state?.micEnabled !== false
        },
        reactionCount: Math.max(0, Number(parsed.reactionCount || 0) || 0),
        reactionBatchIds: boundedIdList(parsed.reactionBatchIds),
        announcedGiftTransactionIds: boundedIdList(parsed.announcedGiftTransactionIds),
        moderation: {
            chatPaused: Boolean(parsed.moderation?.chatPaused),
            moderators: (Array.isArray(parsed.moderation?.moderators) ? parsed.moderation.moderators : [])
                .map((entry) => cleanString(entry, 160)).filter(Boolean).slice(0, 200),
            blockedWords: (Array.isArray(parsed.moderation?.blockedWords) ? parsed.moderation.blockedWords : [])
                .map((entry) => cleanString(entry, 80)).filter(Boolean).slice(0, 200)
        },
        guestRequests: uniqueParticipants(parsed.guestRequests),
        guests: uniqueParticipants(parsed.guests),
        battle: normalizedBattle(parsed.battle)
    };
}

function hydrateLiveSession(rawSnapshot) {
    const snapshot = parseLiveSnapshot(rawSnapshot);
    if (!snapshot) return null;
    const recoverableGuestRequests = new Map(snapshot.guestRequests.map((entry) => [entry.userId, entry]));
    const recoverableGuests = new Map(snapshot.guests.map((entry) => [entry.userId, entry]));
    return {
        host: null,
        hostAuthToken: '',
        pendingHostSnapshot: snapshot.host,
        hostJoinReservationToken: null,
        viewers: new Map(),
        reactionCount: snapshot.reactionCount,
        reactionBatchIds: new Set(snapshot.reactionBatchIds),
        announcedGiftTransactionIds: new Set(snapshot.announcedGiftTransactionIds),
        state: snapshot.state,
        moderation: {
            chatPaused: snapshot.moderation.chatPaused,
            moderators: new Set(snapshot.moderation.moderators),
            blockedWords: snapshot.moderation.blockedWords
        },
        guestRequests: new Map(),
        guests: new Map(),
        recoverableGuestRequests,
        recoverableGuests,
        battle: {
            ...snapshot.battle,
            opponentSocketId: ''
        }
    };
}

function buildDistributedLiveConfig(env = process.env) {
    const prefix = cleanString(env.SIGNALING_LIVE_STATE_PREFIX || 'novvin:signaling:live:', 200);
    if (!prefix) throw new Error('SIGNALING_LIVE_STATE_PREFIX cannot be empty');
    return {
        prefix,
        stateTtlSeconds: boundedInteger(
            env.SIGNALING_LIVE_STATE_TTL_SECONDS,
            DEFAULT_STATE_TTL_SECONDS,
            300,
            7 * 24 * 60 * 60
        ),
        hostLeaseSeconds: boundedInteger(
            env.SIGNALING_LIVE_HOST_LEASE_SECONDS,
            DEFAULT_HOST_LEASE_SECONDS,
            15,
            300
        ),
        terminationLockMs: boundedInteger(
            env.SIGNALING_LIVE_TERMINATION_LOCK_MS,
            DEFAULT_TERMINATION_LOCK_MS,
            5_000,
            120_000
        )
    };
}

function createNoopDistributedLiveState() {
    return {
        enabled: false,
        save: async () => false,
        load: async () => null,
        remove: async () => false,
        listActiveIds: async () => [],
        renewHostLease: async () => false,
        releaseHostLease: async () => false,
        hasHostLease: async () => false,
        scheduleHostDeadline: async () => false,
        clearHostDeadline: async () => false,
        hostDeadline: async () => null,
        dueHostDeadlines: async () => [],
        acquireTerminationLock: async () => null,
        releaseTerminationLock: async () => false
    };
}

function createDistributedLiveState(redisClient, options = {}) {
    if (!redisClient || typeof redisClient.get !== 'function') {
        return createNoopDistributedLiveState();
    }
    const config = buildDistributedLiveConfig(options.env || process.env);
    const key = (suffix) => `${config.prefix}${suffix}`;
    const stateKey = (liveId) => key(`state:${cleanString(liveId, 128)}`);
    const leaseKey = (liveId) => key(`host-lease:${cleanString(liveId, 128)}`);
    const lockKey = (liveId) => key(`termination-lock:${cleanString(liveId, 128)}`);
    const activeKey = key('active');
    const deadlinesKey = key('host-deadlines');

    return {
        enabled: true,
        config: { ...config },
        async save(liveId, sessionOrSnapshot) {
            const snapshot = sessionOrSnapshot?.version === LIVE_STATE_VERSION
                ? parseLiveSnapshot(sessionOrSnapshot)
                : serializeLiveSession(liveId, sessionOrSnapshot);
            if (!snapshot || snapshot.liveId !== cleanString(liveId, 128)) return false;
            await Promise.all([
                redisClient.set(stateKey(liveId), JSON.stringify(snapshot), { EX: config.stateTtlSeconds }),
                redisClient.sAdd(activeKey, snapshot.liveId)
            ]);
            return true;
        },
        async load(liveId) {
            return parseLiveSnapshot(await redisClient.get(stateKey(liveId)));
        },
        async remove(liveId) {
            const normalizedLiveId = cleanString(liveId, 128);
            if (!normalizedLiveId) return false;
            await Promise.all([
                redisClient.del(stateKey(normalizedLiveId), leaseKey(normalizedLiveId)),
                redisClient.sRem(activeKey, normalizedLiveId),
                redisClient.zRem(deadlinesKey, normalizedLiveId)
            ]);
            return true;
        },
        async listActiveIds() {
            const values = await redisClient.sMembers(activeKey);
            return (values || []).map((entry) => cleanString(entry, 128)).filter(Boolean);
        },
        /**
         * Acquires an unowned lease, renews the caller's lease, or atomically
         * replaces a locally-owned predecessor during an intentional device
         * hand-off. It must never let a second signaling process steal an
         * active host lease merely because it sent a heartbeat later.
         */
        async renewHostLease(liveId, ownerId, replaceOwnerId = '') {
            const owner = cleanString(ownerId, 200);
            if (!owner) return false;
            const previousOwner = cleanString(replaceOwnerId, 200);
            const result = await redisClient.eval(
                "local current = redis.call('GET', KEYS[1]) " +
                "if not current or current == ARGV[1] or (ARGV[3] ~= '' and current == ARGV[3]) then " +
                "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) return 1 end return 0",
                {
                    keys: [leaseKey(liveId)],
                    arguments: [owner, String(config.hostLeaseSeconds), previousOwner]
                }
            );
            if (Number(result) <= 0) return false;
            await redisClient.zRem(deadlinesKey, cleanString(liveId, 128));
            return true;
        },
        async releaseHostLease(liveId, ownerId) {
            const owner = cleanString(ownerId, 200);
            if (!owner) return false;
            const result = await redisClient.eval(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
                { keys: [leaseKey(liveId)], arguments: [owner] }
            );
            return Number(result) > 0;
        },
        async hasHostLease(liveId) {
            return Number(await redisClient.exists(leaseKey(liveId))) > 0;
        },
        async scheduleHostDeadline(liveId, deadlineMs) {
            const normalizedLiveId = cleanString(liveId, 128);
            const score = Math.max(0, Number(deadlineMs) || 0);
            if (!normalizedLiveId || !score) return false;
            await redisClient.zAdd(deadlinesKey, [{ score, value: normalizedLiveId }]);
            return true;
        },
        async clearHostDeadline(liveId) {
            return Number(await redisClient.zRem(deadlinesKey, cleanString(liveId, 128))) > 0;
        },
        async hostDeadline(liveId) {
            const score = await redisClient.zScore(deadlinesKey, cleanString(liveId, 128));
            return score == null ? null : Number(score);
        },
        async dueHostDeadlines(nowMs = Date.now(), limit = 100) {
            return redisClient.zRangeByScore(
                deadlinesKey,
                0,
                Math.max(0, Number(nowMs) || Date.now()),
                { LIMIT: { offset: 0, count: boundedInteger(limit, 100, 1, 500) } }
            );
        },
        async acquireTerminationLock(liveId) {
            const token = crypto.randomUUID();
            const result = await redisClient.set(lockKey(liveId), token, {
                NX: true,
                PX: config.terminationLockMs
            });
            return result === 'OK' ? token : null;
        },
        async releaseTerminationLock(liveId, token) {
            const normalizedToken = cleanString(token, 200);
            if (!normalizedToken) return false;
            const result = await redisClient.eval(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
                { keys: [lockKey(liveId)], arguments: [normalizedToken] }
            );
            return Number(result) > 0;
        }
    };
}

module.exports = {
    LIVE_STATE_VERSION,
    MAX_DEDUPE_IDS,
    buildDistributedLiveConfig,
    createDistributedLiveState,
    createNoopDistributedLiveState,
    hydrateLiveSession,
    parseLiveSnapshot,
    serializeLiveSession
};
