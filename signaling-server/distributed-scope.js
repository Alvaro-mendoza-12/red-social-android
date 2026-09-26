'use strict';

const MAX_JOINED_SCOPES = 8;
const DEFAULT_LOOKUP_TIMEOUT_MS = 3_000;

function normalizedScopeIds(joinedScopes) {
    if (!(joinedScopes instanceof Set) || joinedScopes.size === 0) return [];
    return Array.from(joinedScopes)
        .map((scopeId) => String(scopeId || '').trim())
        .filter(Boolean)
        .slice(0, MAX_JOINED_SCOPES);
}

function withTimeout(promise, timeoutMs) {
    let timer;
    return Promise.race([
        promise,
        new Promise((_, reject) => {
            timer = setTimeout(() => reject(Object.assign(
                new Error('Distributed socket lookup timed out'),
                { code: 'SOCKET_LOOKUP_TIMEOUT' }
            )), timeoutMs);
        })
    ]).finally(() => clearTimeout(timer));
}

async function sharesNamespaceScope(namespace, joinedScopes, rawTargetSocketId, options = {}) {
    const scopeIds = normalizedScopeIds(joinedScopes);
    const targetSocketId = String(rawTargetSocketId || '').trim();
    if (scopeIds.length === 0 || !targetSocketId) return false;
    if (!namespace || typeof namespace.in !== 'function') {
        throw new TypeError('A Socket.IO namespace is required');
    }

    const localTarget = namespace.sockets?.get?.(targetSocketId);
    if (localTarget && scopeIds.some((scopeId) => localTarget.rooms?.has?.(scopeId))) {
        return true;
    }

    const timeoutMs = Math.min(
        Math.max(Number(options.timeoutMs) || DEFAULT_LOOKUP_TIMEOUT_MS, 250),
        10_000
    );
    const matchingSockets = await withTimeout(
        namespace.in(scopeIds).fetchSockets(),
        timeoutMs
    );
    return matchingSockets.some((candidate) => candidate.id === targetSocketId);
}

async function fetchNamespaceScopeMembers(namespace, rawScopeId, metadataKey, options = {}) {
    const scopeId = String(rawScopeId || '').trim();
    const normalizedMetadataKey = String(metadataKey || '').trim();
    if (!scopeId || !normalizedMetadataKey) return [];
    if (!namespace || typeof namespace.in !== 'function') {
        throw new TypeError('A Socket.IO namespace is required');
    }

    const timeoutMs = Math.min(
        Math.max(Number(options.timeoutMs) || DEFAULT_LOOKUP_TIMEOUT_MS, 250),
        10_000
    );
    const sockets = await withTimeout(namespace.in(scopeId).fetchSockets(), timeoutMs);
    return sockets.flatMap((candidate) => {
        if (candidate.id === options.excludeSocketId) return [];
        const metadata = candidate.data?.[normalizedMetadataKey];
        if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return [];
        return [{ ...metadata, socketId: candidate.id }];
    });
}

module.exports = {
    DEFAULT_LOOKUP_TIMEOUT_MS,
    MAX_JOINED_SCOPES,
    fetchNamespaceScopeMembers,
    normalizedScopeIds,
    sharesNamespaceScope
};
