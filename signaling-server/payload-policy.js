'use strict';

const MAX_SDP_LENGTH = 192 * 1024;
const MAX_ICE_CANDIDATE_LENGTH = 8 * 1024;

function boundedText(value, maxLength, required = true) {
    if (typeof value !== 'string') return required ? null : '';
    const normalized = value.trim();
    if ((required && !normalized) || normalized.length > maxLength) return null;
    return normalized;
}

function isPlainRecord(value) {
    return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function normalizeSessionDescription(value, expectedType) {
    if (!isPlainRecord(value)) return null;
    const type = boundedText(value.type, 16)?.toLowerCase();
    const sdp = typeof value.sdp === 'string' ? value.sdp : null;
    if (type !== expectedType || !sdp || sdp.length > MAX_SDP_LENGTH) return null;
    return { ...value, type, sdp };
}

function normalizeIceCandidate(value) {
    if (!isPlainRecord(value)) return null;
    const candidate = typeof value.candidate === 'string' ? value.candidate : null;
    if (candidate === null || candidate.length > MAX_ICE_CANDIDATE_LENGTH) return null;
    if (value.sdpMid != null && boundedText(value.sdpMid, 256, false) === null) return null;
    if (
        value.sdpMLineIndex != null &&
        (!Number.isInteger(Number(value.sdpMLineIndex)) || Number(value.sdpMLineIndex) < 0 || Number(value.sdpMLineIndex) > 65_535)
    ) return null;
    return value;
}

function normalizeSignalPayload(rawValue, kind) {
    if (!isPlainRecord(rawValue)) return null;
    const target = boundedText(rawValue.target, 128);
    const userId = boundedText(rawValue.userId, 128);
    const name = boundedText(rawValue.name ?? '', 160, false);
    if (!target || !userId || name === null) return null;

    if (kind === 'offer' || kind === 'answer') {
        const description = normalizeSessionDescription(rawValue[kind], kind);
        if (!description) return null;
        return { target, userId, name, [kind]: description };
    }
    if (kind === 'ice-candidate') {
        const candidate = normalizeIceCandidate(rawValue.candidate);
        if (!candidate) return null;
        return { target, userId, name, candidate };
    }
    return null;
}

module.exports = {
    MAX_ICE_CANDIDATE_LENGTH,
    MAX_SDP_LENGTH,
    normalizeSignalPayload,
};
