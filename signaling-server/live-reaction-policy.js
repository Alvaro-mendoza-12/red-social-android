'use strict';
const hearts = new Set(['❤️', '❤', '💜', '💗']);
const effects = new Set(['👏', '🔥', '😮']);
function liveReactionPolicy(emoji) {
    const value = emoji == null ? '❤️' : String(emoji).trim();
    if (!hearts.has(value) && !effects.has(value)) return null;
    return { emoji: value, kind: hearts.has(value) ? 'heart' : value, heartDelta: hearts.has(value) ? 1 : 0 };
}
module.exports = { liveReactionPolicy };
