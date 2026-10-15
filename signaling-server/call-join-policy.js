'use strict';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_ACTIVE_CALLS_PER_SOCKET = 1;

async function authorizeCallJoin({ socket, callId, userId, lookupAccess, maxCalls = MAX_ACTIVE_CALLS_PER_SOCKET }) {
    if (!UUID.test(callId || '')) return { allowed: false, error: 'CALL_ACCESS_DENIED' };
    const joined = socket.data.joinedCallIds;
    const pending = socket.data.pendingCallJoins ||= new Set();
    const occupied = new Set([...(joined || []), ...pending]);
    if (!occupied.has(callId) && occupied.size >= maxCalls) return { allowed: false, error: 'CALL_SCOPE_LIMIT_REACHED' };
    // Reserve before awaiting the API so concurrent joins cannot exceed quota.
    const alreadyPending = pending.has(callId);
    if (alreadyPending) return { allowed: false, error: 'CALL_JOIN_IN_PROGRESS' };
    pending.add(callId);
    let authorized = false;
    try {
        const access = await lookupAccess(callId);
        if (!socket.connected || access?.allowed !== true || access.callId !== callId || access.userId !== userId) {
            return { allowed: false, error: 'CALL_ACCESS_DENIED' };
        }
        if (!['ringing', 'active'].includes(access.state)) return { allowed: false, error: 'CALL_ACCESS_DENIED' };
        authorized = true;
        return { allowed: true, expiresAt: access.expiresAt, release: () => pending.delete(callId) };
    } catch (error) {
        return { allowed: false, error: !error?.status || error.status >= 500 || error.status === 429
            ? 'CALL_ACCESS_UNAVAILABLE' : 'CALL_ACCESS_DENIED' };
    } finally { if (!authorized) pending.delete(callId); }
}

module.exports = { MAX_ACTIVE_CALLS_PER_SOCKET, authorizeCallJoin };
