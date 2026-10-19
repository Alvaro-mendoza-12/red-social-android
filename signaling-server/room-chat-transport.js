'use strict';
const { validRoomImage, ROOM_CHAT_TTL_MS } = require('./room-chat-policy');
/** Chat content uses transient Pub/Sub between servers, never the recovery stream. */
function createRoomChatTransport(io, ledger, isDistributed, now = Date.now) {
    const relay = 'novvin-ephemeral-room-chat';
    function valid(data) {
        return data && /^[\w-]{1,160}$/.test(data.id || '') && typeof data.roomId === 'string' && data.roomId.length <= 160 &&
            typeof data.userId === 'string' && data.userId.length <= 160;
    }
    function local(event, data) { io.local.to(data.roomId).emit(event, data); }
    io.on(relay, envelope => {
        const data = envelope?.data;
        if (!valid(data)) return;
        if (envelope.event === 'chat-message') {
            if (typeof data.text !== 'string' || data.text.length > 2000 || typeof data.userName !== 'string' || data.userName.length > 100 ||
                !Number.isFinite(data.timestamp) || !Number.isFinite(data.expiresAt) || data.expiresAt <= now() || data.expiresAt > now() + ROOM_CHAT_TTL_MS + 30000 ||
                data.imageData && !validRoomImage(data.imageData)) return;
            const accepted = ledger.accept(data);
            if (accepted.ok && !accepted.duplicate) local(envelope.event, data);
        } else if (envelope.event === 'chat-message-deleted') {
            if (ledger.remove(data.roomId, data.userId, data.id).ok) local(envelope.event, data);
        }
    });
    return (event, data) => {
        local(event, data);
        // The Streams adapter sends SERVER_SIDE_EMIT via Pub/Sub, without XADD.
        if (isDistributed()) io.serverSideEmit(relay, { event, data });
    };
}
module.exports = { createRoomChatTransport };
