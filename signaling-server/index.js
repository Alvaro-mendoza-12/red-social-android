require('dotenv').config();
const http = require('http');
const { Server } = require('socket.io');
const { createClient } = require('@supabase/supabase-js');

const server = http.createServer();
const io = new Server(server, {
    cors: {
        origin: "*",
        methods: ["GET", "POST"]
    }
});

const PORT = Number(process.env.PORT || 3001);
const { resolveLink } = require('./resolver');

const supabaseUrl = process.env.SUPABASE_URL;
const supabaseServiceRoleKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
const supabaseEnabled = Boolean(supabaseUrl && supabaseServiceRoleKey);

const supabase = supabaseEnabled
    ? createClient(supabaseUrl, supabaseServiceRoleKey, {
        auth: {
            autoRefreshToken: false,
            persistSession: false
        }
    })
    : null;

if (!supabaseEnabled) {
    console.warn('[WARN] SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY not set. participant_count sync is disabled.');
}

async function decrementParticipantCount(roomId) {
    if (!supabase) return;

    try {
        const { data: currentRoom, error: selectError } = await supabase
            .from('voice_rooms')
            .select('participant_count')
            .eq('id', roomId)
            .maybeSingle();

        if (selectError) throw selectError;
        if (!currentRoom) return;

        const newCount = Math.max(0, Number(currentRoom.participant_count || 0) - 1);
        const { error: updateError } = await supabase
            .from('voice_rooms')
            .update({ participant_count: newCount })
            .eq('id', roomId);

        if (updateError) throw updateError;
        console.log(`[DB] Decremented count for room ${roomId} to ${newCount}`);
    } catch (error) {
        console.error(`[DB] Error updating participant_count for room ${roomId}:`, error);
    }
}

// Track active rooms and their participants
// room_id -> Set(socket_id)
const rooms = new Map();

io.on('connection', (socket) => {
    socket.data.joinedRoomIds = new Set();
    console.log(`User connected: ${socket.id}`);

    socket.on('join-room', (data) => {
        const roomId = data.roomId?.toString().trim();
        const userId = data.userId?.toString().trim();
        const { name, avatarUrl } = data;

        if (!roomId || !userId) return;

        socket.join(roomId);

        // Track participant details
        if (!rooms.has(roomId)) {
            rooms.set(roomId, new Map());
        }

        // Clean up any old sessions for this same userId in this room (Ghost prevention)
        const currentRoomParticipants = rooms.get(roomId);
        currentRoomParticipants.forEach((val, sid) => {
            if (val.userId === userId) {
                console.log(`[CLEANUP] Removing ghost for user ${userId} (old SID: ${sid})`);
                currentRoomParticipants.delete(sid);
                socket.to(roomId).emit('user-left', sid);
            }
        });

        currentRoomParticipants.set(socket.id, {
            userId,
            name,
            avatarUrl,
            isCameraSharing: false,
            isScreenSharing: false
        });
        socket.data.joinedRoomIds.add(roomId);

        console.log(`[JOIN] User ${userId} joined room ${roomId}. Room population: ${currentRoomParticipants.size}`);
        console.log(`[ROOMS] Currently active: ${Array.from(rooms.keys()).join(', ')}`);

        // Notify others in the room
        socket.to(roomId).emit('user-joined', {
            socketId: socket.id,
            userId: userId,
            name: name,
            avatarUrl: avatarUrl
        });

        // Send current participants back to the new user
        const participants = [];
        rooms.get(roomId).forEach((data, sid) => {
            if (sid !== socket.id) {
                participants.push({
                    socketId: sid,
                    userId: data.userId,
                    name: data.name,
                    avatarUrl: data.avatarUrl,
                    isCameraSharing: data.isCameraSharing || false,
                    isScreenSharing: data.isScreenSharing || false
                });
            }
        });
        socket.emit('current-participants', participants);
    });

    // --- EVENTOS MODO CINE ---
    socket.on("cinema-state", (data) => {
        io.to(data.roomId).emit("cinema-state", data);
    });

    socket.on("cinema-sync", (data) => {
        io.to(data.roomId).emit("cinema-sync", data);
    });

    socket.on("cinema-control", (data) => {
        io.to(data.roomId).emit("cinema-control", data);
    });

    socket.on("resolve-link", async (data) => {
        const { url, requestId } = data;
        console.log(`[RESOLVE] Request from ${socket.id} for ${url}`);

        const result = await resolveLink(url);

        socket.emit("link-resolved", {
            requestId: requestId,
            ...result
        });
    });
    // --------------------------

    socket.on('chat-message', (data) => {
        const { roomId } = data;
        socket.to(roomId).emit('chat-message', data);
    });

    socket.on('reaction', (data) => {
        const { roomId } = data;
        socket.to(roomId).emit('reaction', data);
    });

    socket.on('speaking-status', (data) => {
        const { roomId } = data;
        socket.to(roomId).emit('speaking-status', data);
    });

    socket?.on('mute-state', (data) => {
        const { roomId } = data;
        socket.to(roomId).emit('mute-state', data);
    });

    socket?.on('media-status', (data) => {
        const { roomId, isCameraSharing, isScreenSharing } = data;

        // Persist on server for late joiners
        const currentRoomParticipants = rooms.get(roomId);
        if (currentRoomParticipants && currentRoomParticipants.has(socket.id)) {
            const p = currentRoomParticipants.get(socket.id);
            p.isCameraSharing = isCameraSharing;
            p.isScreenSharing = isScreenSharing;
        }

        // Broadcast media state to everyone else in the room
        socket.to(roomId).emit('media-status', data, socket.id);
    });

    // WebRTC Signaling: Forwarding Offer, Answer, and ICE Candidates

    socket.on('offer', (data) => {
        const { target, offer, userId, name } = data;
        const sdpPreview = offer.sdp ? offer.sdp.substring(0, 50).replace(/\n/g, "") : "no sdp";
        console.log(`[SIGNAL] Forwarding offer from ${socket.id} to ${target}. SDP: ${sdpPreview}...`);
        io.to(target).emit('offer', {
            sender: socket.id,
            offer: offer,
            userId: userId,
            name: name
        });
    });

    socket.on('answer', (data) => {
        const { target, answer } = data;
        const sdpPreview = answer.sdp ? answer.sdp.substring(0, 50).replace(/\n/g, "") : "no sdp";
        console.log(`[SIGNAL] Forwarding answer from ${socket.id} to ${target}. SDP: ${sdpPreview}...`);
        io.to(target).emit('answer', {
            sender: socket.id,
            answer: answer
        });
    });

    socket.on('ice-candidate', (data) => {
        const { target, candidate } = data;
        console.log(`[SIGNAL] Forwarding ice-candidate from ${socket.id} to ${target}. Type: ${candidate.candidate ? candidate.candidate.split(' ')[7] : 'unknown'}`);
        io.to(target).emit('ice-candidate', {
            sender: socket.id,
            candidate: candidate
        });
    });

    socket.on('disconnecting', async () => {
        const joinedRoomIds = Array.from(socket.data.joinedRoomIds || []);

        for (const roomId of joinedRoomIds) {
            const participants = rooms.get(roomId);
            if (!participants) continue;

            const wasParticipant = participants.delete(socket.id);
            if (!wasParticipant) continue;

            console.log(`[LEAVE] User disconnected from room ${roomId}. Population: ${participants.size}`);
            socket.to(roomId).emit('user-left', socket.id);
            await decrementParticipantCount(roomId);

            if (participants.size === 0) {
                rooms.delete(roomId);
            }
        }

        socket.data.joinedRoomIds.clear();
    });

    socket.on('disconnect', () => {
        console.log(`User disconnected: ${socket.id}`);
    });
});

server.on('error', (error) => {
    console.error('[FATAL] HTTP server error:', error);
    process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`Signaling server running on port ${PORT}`);
});
