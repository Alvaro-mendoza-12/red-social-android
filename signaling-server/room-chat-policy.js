'use strict';
const crypto = require('crypto');
const MAX_IMAGE_DATA = 132000;
const ROOM_CHAT_TTL_MS = 60 * 60 * 1000;
function validRoomImage(value) {
    if (typeof value !== 'string' || value.length > MAX_IMAGE_DATA || !/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(value)) return false;
    const bytes = Buffer.from(value.slice(23), 'base64');
    if (bytes.length < 12 || bytes[0] !== 255 || bytes[1] !== 216) return false;
    for (let at = 2; at + 8 < bytes.length;) {
        if (bytes[at++] !== 255) return false;
        while (bytes[at] === 255) at++;
        const marker = bytes[at++];
        if (marker === 217 || marker === 218) return false;
        const size = bytes.readUInt16BE(at);
        if (size < 2 || at + size > bytes.length) return false;
        if ([192,193,194].includes(marker)) {
            const height = bytes.readUInt16BE(at + 3), width = bytes.readUInt16BE(at + 5);
            return width > 0 && height > 0 && width <= 720 && height <= 720;
        }
        at += size;
    }
    return false;
}
function roomChatMessage(data, roomId, userId, name) {
    const imageData = data.imageData || '';
    if (imageData && !validRoomImage(imageData)) return null;
    const text = String(data.text || '').trim().slice(0, 2000);
    if (!text && !imageData) return null;
    return { roomId, userId, userName: String(name || 'Participante').slice(0,100),
        text: text || '📷 Foto', id: /^[\w-]{1,160}$/.test(data.id || '') ? data.id : crypto.randomUUID(),
        timestamp: Date.now(), ...(imageData ? { imageData } : {}) };
}
/** Metadata only: text and photo bytes are relayed and never retained here. */
function createRoomChatLedger({ now = Date.now, ttl = ROOM_CHAT_TTL_MS, capacity = 50000 } = {}) {
    const records = new Map();
    const key = (room, id) => JSON.stringify([room, id]);
    function expire() {
        const expired = [], at = now();
        for (const [id, entry] of records) if (entry.expiresAt <= at) {
            records.delete(id);
            if (!entry.deleted) expired.push({ roomId: entry.roomId, userId: entry.userId, id: entry.id, reason: 'expired' });
        }
        return expired;
    }
    function accept(message) {
        const at = now(), name = key(message.roomId, message.id);
        const previous = records.get(name);
        const digest = crypto.createHash('sha256').update(message.text).update('\0').update(message.imageData || '').digest('hex');
        if (previous) {
            if (previous.userId !== message.userId || previous.digest !== digest) return { ok: false, error: 'MESSAGE_ID_CONFLICT' };
            if (previous.deleted || previous.expiresAt <= at) return { ok: false, error: 'MESSAGE_EXPIRED_OR_DELETED' };
            return { ok: true, duplicate: true, timestamp: previous.timestamp, expiresAt: previous.expiresAt, id: previous.id };
        }
        // Do not evict active ownership checks: callers may retry after expiry.
        if (records.size >= capacity) return { ok: false, error: 'ROOM_CHAT_BUSY' };
        const timestamp = Number.isFinite(message.timestamp) ? Math.min(at, message.timestamp) : at;
        const expiresAt = Math.min(timestamp + ttl, Number.isFinite(message.expiresAt) ? message.expiresAt : Infinity);
        if (expiresAt <= at) return { ok: false, error: 'MESSAGE_EXPIRED_OR_DELETED' };
        const metadata = { roomId: message.roomId, userId: message.userId, id: message.id, timestamp, expiresAt, digest, deleted: false };
        records.set(name, metadata);
        return { ok: true, duplicate: false, id: message.id, timestamp: at, expiresAt: metadata.expiresAt };
    }
    function remove(roomId, userId, id) {
        const previous = records.get(key(roomId, id));
        if (!previous || previous.expiresAt <= now()) {
            // A restart drops metadata, but recipients still key deletion by the
            // authenticated author AND id. Never accept a caller-supplied owner.
            if (!previous && records.size >= capacity) return { ok: false, error: 'ROOM_CHAT_BUSY' };
            records.set(key(roomId,id), {roomId,userId,id,timestamp:now(),expiresAt:now()+ttl,deleted:true,digest:''});
            return {ok:true,id,roomId,userId,reason:'deleted'};
        }
        if (previous.userId !== userId) return { ok: false, error: 'MESSAGE_DELETE_DENIED' };
        previous.deleted = true;
        return { ok: true, id, roomId, userId, reason: 'deleted' };
    }
    return { accept, remove, expire, has: (roomId, id) => records.has(key(roomId, id)), size: () => records.size };
}
module.exports = { MAX_IMAGE_DATA, ROOM_CHAT_TTL_MS, validRoomImage, roomChatMessage, createRoomChatLedger };
