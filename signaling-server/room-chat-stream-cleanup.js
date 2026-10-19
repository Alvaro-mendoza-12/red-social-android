'use strict';
const { ROOM_CHAT_TTL_MS } = require('./room-chat-policy');
/** Migration hygiene for chat frames written by older Streams deployments. */
function createRoomChatStreamCleanup(client, config, now = Date.now) {
    const cursors = new Map();
    return async () => {
        let deleted = 0;
        for (let index = 0; index < config.streamCount; index++) {
            const stream = config.streamCount === 1 ? config.streamName : `${config.streamName}-${index}`;
            const until = `${Math.max(0, now() - ROOM_CHAT_TTL_MS)}-0`;
            for (let batch = 0; batch < 200; batch++) {
                const from = cursors.has(stream) ? '(' + cursors.get(stream) : '-';
                const records = await client.xRange(stream, from, until, { COUNT: 250 });
                if (!records.length) break;
                const expired = records.filter(record => {
                    // Photo/text chat packets are JSON (no binary buffers).
                    if (record.message.nsp !== '/') return false;
                    try { return JSON.parse(record.message.data)?.packet?.data?.[0] === 'chat-message'; } catch { return false; }
                }).map(record => record.id);
                if (expired.length) deleted += Number(await client.xDel(stream, expired));
                cursors.set(stream, records.at(-1).id);
            }
        }
        return { deleted };
    };
}
module.exports = { createRoomChatStreamCleanup };
