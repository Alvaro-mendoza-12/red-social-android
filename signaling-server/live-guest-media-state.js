'use strict';

// The sender may change only its own already approved seat in its joined LIVE.
function updateLiveGuestMediaState(session, socket, data) {
    if (typeof data?.videoEnabled !== 'boolean' || socket?.data?.liveRole !== 'viewer') return false;
    const guest = session?.guests?.get(socket.id);
    if (!guest || guest.userId !== socket.data.authUserId || !session.viewers?.has(socket.id)) return false;
    if (guest.videoEnabled === data.videoEnabled) return false;
    session.guests.set(socket.id, { ...guest, videoEnabled: data.videoEnabled });
    return true;
}
module.exports = { updateLiveGuestMediaState };
