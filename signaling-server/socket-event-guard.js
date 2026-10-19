'use strict';

const { normalizeSignalPayload, MAX_SDP_LENGTH } = require('./payload-policy');
const LIFECYCLE_EVENTS = new Set(['disconnect', 'disconnecting']);
const MAX_PACKET_BYTES = 256 * 1024;
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor']);

function isRecord(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const prototype = Object.getPrototypeOf(value);
    return prototype === Object.prototype || prototype === null;
}

// This is a transport boundary. Domain permission checks remain in each handler.
function validateSocketPayload(event, value) {
    if (LIFECYCLE_EVENTS.has(event)) return { ok: true };
    if (!isRecord(value)) return { ok: false, code: 'INVALID_SIGNAL_PAYLOAD' };
    const seen = new WeakSet();
    let nodes = 0;
    let bytes = 0;
    function visit(item, depth, key = '') {
        if (++nodes > 4096 || depth > 8) return false;
        if (item == null || typeof item === 'boolean') { bytes += 5; return true; }
        if (typeof item === 'number') { bytes += 24; return Number.isFinite(item); }
        if (typeof item === 'string') {
            const limit = key === 'sdp' ? MAX_SDP_LENGTH : event === 'chat-message' && key === 'imageData' ? 132000 : 8 * 1024;
            if (item.length > limit) return false;
            bytes += Buffer.byteLength(item, 'utf8') + 2;
            return bytes <= MAX_PACKET_BYTES;
        }
        if (typeof item !== 'object' || seen.has(item)) return false;
        if (!Array.isArray(item) && !isRecord(item)) return false;
        seen.add(item);
        const entries = Object.entries(item);
        if (entries.length > (Array.isArray(item) ? 128 : 128)) return false;
        for (const [childKey, child] of entries) {
            if (FORBIDDEN_KEYS.has(childKey) || childKey.length > 128) return false;
            bytes += Buffer.byteLength(childKey, 'utf8') + 4;
            if (bytes > MAX_PACKET_BYTES || !visit(child, depth + 1, childKey)) return false;
        }
        seen.delete(item);
        return true;
    }
    if (!visit(value, 0)) return { ok: false, code: 'INVALID_SIGNAL_PAYLOAD' };
    for (const key of ['roomId', 'callId', 'liveId', 'userId', 'target', 'targetSocketId', 'targetUserId']) {
        if (value[key] != null && (typeof value[key] !== 'string' || !value[key].trim() || value[key].length > 160)) {
            return { ok: false, code: 'INVALID_SIGNAL_PAYLOAD' };
        }
    }
    for (const key of ['isSpeaking', 'isMuted', 'isVideoEnabled', 'videoEnabled', 'isCameraSharing', 'isScreenSharing', 'accepted', 'micEnabled', 'isPaused']) {
        if (value[key] != null && typeof value[key] !== 'boolean') return { ok: false, code: 'INVALID_SIGNAL_PAYLOAD' };
    }
    if (['offer', 'answer', 'ice-candidate'].includes(event) && !normalizeSignalPayload(value, event)) {
        return { ok: false, code: 'INVALID_SIGNAL_PAYLOAD' };
    }
    return { ok: true };
}

function rejectSocketEvent(socket, event, code, acknowledge) {
    const response = { ok: false, event, error: code };
    try {
        if (typeof acknowledge === 'function') acknowledge(response);
        socket.emit('signal-error', response);
    } catch { /* A disconnected socket or a failed ACK must not terminate Node. */ }
}

function registerSafeSocketEvent(socket, event, listener, reportError = () => {}) {
    return socket.on(event, function guardedListener(...args) {
        const validation = validateSocketPayload(event, args[0]);
        const acknowledge = typeof args.at(-1) === 'function' ? args.at(-1) : undefined;
        if (!validation.ok) {
            rejectSocketEvent(socket, event, validation.code, acknowledge);
            return;
        }
        function fail(error) {
            try { reportError(event, error); } catch { /* Diagnostics must be isolated too. */ }
            if (!LIFECYCLE_EVENTS.has(event)) rejectSocketEvent(socket, event, 'SIGNAL_OPERATION_FAILED', acknowledge);
        }
        try {
            const result = listener.apply(this, args);
            // Socket.IO does not consume rejected async listener results.
            if (result && typeof result.then === 'function') return Promise.resolve(result).catch(fail);
            return result;
        } catch (error) { fail(error); }
    });
}

module.exports = { MAX_PACKET_BYTES, LIFECYCLE_EVENTS, registerSafeSocketEvent, validateSocketPayload };
