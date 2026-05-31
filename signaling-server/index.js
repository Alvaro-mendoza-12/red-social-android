require('dotenv').config();
const crypto = require('crypto');
const http = require('http');
const { Server } = require('socket.io');
let createClient = null;

try {
    ({ createClient } = require('@supabase/supabase-js'));
} catch (error) {
    console.warn('[WARN] @supabase/supabase-js not installed. Supabase auth bridge is disabled.');
}

const PORT = Number(process.env.PORT || 3001);
const { resolveLink, resolveCinemaItem } = require('./resolver');

const supabaseUrl = process.env.SUPABASE_URL;
const supabaseServiceRoleKey = process.env.SUPABASE_SERVICE_ROLE_KEY;
const localFallbackApiUrl = (
    process.env.LOCAL_FALLBACK_API_URL ||
    process.env.FALLBACK_API_URL ||
    process.env.UBUNTU_API_URL ||
    ''
).toString().trim().replace(/\/+$/, '');
const localAuthEnabled = Boolean(localFallbackApiUrl);
const supabaseEnabled = Boolean(createClient && supabaseUrl && supabaseServiceRoleKey);

const supabase = supabaseEnabled
    ? createClient(supabaseUrl, supabaseServiceRoleKey, {
        auth: {
            autoRefreshToken: false,
            persistSession: false
        }
    })
    : null;

if (!localAuthEnabled && !supabaseEnabled) {
    console.warn('[WARN] No auth backend configured. Set LOCAL_FALLBACK_API_URL for Ubuntu auth or SUPABASE_URL/SUPABASE_SERVICE_ROLE_KEY for the legacy bridge.');
} else if (!supabaseEnabled) {
    console.warn('[WARN] Supabase bridge disabled. Voice room DB sync to Supabase is disabled.');
}

const turnHost = process.env.TURN_HOST?.toString().trim() || '';
const turnSecret = process.env.TURN_SECRET?.toString().trim() || '';
const turnRealm = process.env.TURN_REALM?.toString().trim() || turnHost;
const turnPort = parseInt(process.env.TURN_PORT || '3478', 10);
const turnTlsPort = parseInt(process.env.TURN_TLS_PORT || '5349', 10);
const turnTtlSeconds = clampInt(process.env.TURN_TTL_SECONDS, 3600, 60, 24 * 60 * 60);
const turnEnableUdp = parseBooleanEnv(process.env.TURN_ENABLE_UDP, true);
const turnEnableTcp = parseBooleanEnv(process.env.TURN_ENABLE_TCP, true);
const turnEnableTls = parseBooleanEnv(process.env.TURN_ENABLE_TLS, false);
const turnConfigured = Boolean(turnHost && turnSecret);
const httpRateLimitPerMinute = clampInt(process.env.HTTP_RATE_LIMIT_PER_MINUTE, 180, 20, 5000);
const socketRateLimitPerMinute = clampInt(process.env.SOCKET_RATE_LIMIT_PER_MINUTE, 90, 10, 2000);
const allowedOrigins = parseAllowedOrigins(process.env.SIGNALING_ALLOWED_ORIGINS);
const rateLimitBuckets = new Map();

if (allowedOrigins.size === 0) {
    console.warn('[WARN] SIGNALING_ALLOWED_ORIGINS not set. Only local browser origins and native clients without Origin header will be allowed.');
}

function parseBooleanEnv(value, defaultValue) {
    if (value == null) return defaultValue;
    const normalized = value.toString().trim().toLowerCase();
    if (['1', 'true', 'yes', 'on'].includes(normalized)) return true;
    if (['0', 'false', 'no', 'off'].includes(normalized)) return false;
    return defaultValue;
}

function clampInt(rawValue, fallback, min, max) {
    const parsed = Number.parseInt(rawValue ?? '', 10);
    if (!Number.isFinite(parsed)) return fallback;
    return Math.min(Math.max(parsed, min), max);
}

function parseAllowedOrigins(rawValue) {
    const normalized = (rawValue || '')
        .split(',')
        .map((value) => value.trim())
        .filter((value) => value.length > 0);
    return new Set(normalized);
}

function isOriginAllowed(origin) {
    if (!origin) return true;
    if (allowedOrigins.has('*')) return true;
    if (allowedOrigins.size > 0) {
        return allowedOrigins.has(origin);
    }
    return [
        'http://localhost',
        'http://127.0.0.1',
        'https://localhost',
        'https://127.0.0.1',
        'capacitor://localhost',
        'ionic://localhost'
    ].includes(origin);
}

function setSecurityHeaders(res) {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
}

function setCorsHeaders(req, res) {
    const origin = req.headers.origin?.toString().trim();
    if (origin && isOriginAllowed(origin)) {
        res.setHeader('Access-Control-Allow-Origin', origin);
        res.setHeader('Vary', 'Origin');
    }
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
}

function sendJson(req, res, statusCode, payload) {
    setCorsHeaders(req, res);
    setSecurityHeaders(res);
    res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(payload));
}

function getClientIp(reqOrSocket) {
    const headers = reqOrSocket?.headers || reqOrSocket?.handshake?.headers || {};
    const forwarded = headers['x-forwarded-for'];
    if (typeof forwarded === 'string' && forwarded.trim()) {
        return forwarded.split(',')[0].trim();
    }
    return (
        reqOrSocket?.socket?.remoteAddress ||
        reqOrSocket?.request?.socket?.remoteAddress ||
        reqOrSocket?.handshake?.address ||
        'unknown'
    ).toString();
}

function consumeRateLimitToken(bucket, key, limit, windowMs) {
    const now = Date.now();
    const compositeKey = `${bucket}:${key}`;
    const window = rateLimitBuckets.get(compositeKey) || [];
    const fresh = window.filter((timestamp) => now - timestamp < windowMs);
    if (fresh.length >= limit) {
        rateLimitBuckets.set(compositeKey, fresh);
        return false;
    }
    fresh.push(now);
    rateLimitBuckets.set(compositeKey, fresh);
    return true;
}

function enforceHttpRateLimit(req, res, bucket, limit = httpRateLimitPerMinute) {
    const key = getClientIp(req);
    const allowed = consumeRateLimitToken(bucket, key, limit, 60 * 1000);
    if (allowed) return true;
    sendJson(req, res, 429, {
        error: 'Too many requests',
        retryAfterSeconds: 60
    });
    return false;
}

function extractBearerToken(rawValue) {
    const header = (rawValue || '').toString().trim();
    if (!header) return '';
    if (header.toLowerCase().startsWith('bearer ')) {
        return header.slice('Bearer '.length).trim();
    }
    return header;
}

async function readJsonBody(req) {
    const chunks = [];
    let size = 0;

    for await (const chunk of req) {
        size += chunk.length;
        if (size > 64 * 1024) {
            throw new Error('Request body too large');
        }
        chunks.push(Buffer.from(chunk));
    }

    const rawBody = Buffer.concat(chunks).toString('utf8').trim();
    if (!rawBody) return {};

    try {
        return JSON.parse(rawBody);
    } catch (error) {
        throw new Error('Invalid JSON payload');
    }
}

async function authenticateLocalFallbackToken(accessToken) {
    if (!localAuthEnabled || !accessToken) return null;

    const response = await fetch(`${localFallbackApiUrl}/v1/auth/me`, {
        headers: {
            Accept: 'application/json',
            Authorization: `Bearer ${accessToken}`,
            'ngrok-skip-browser-warning': 'true'
        }
    });

    if (!response.ok) {
        return null;
    }

    const payload = await response.json();
    const user = payload?.user;
    if (!user?.id) return null;

    const authId = (user.supabaseUserId || user.id).toString().trim();
    if (!authId) return null;

    return {
        id: authId,
        localFallbackId: user.id.toString().trim(),
        email: user.email?.toString().trim().toLowerCase() || '',
        user_metadata: {
            display_name: user.displayName || '',
            avatar_url: user.avatarUrl || '',
            local_fallback_id: user.id
        }
    };
}

async function authenticateHttpUser(req) {
    const accessToken = extractBearerToken(req.headers.authorization);
    if (!accessToken) {
        return { error: 'Missing bearer token', status: 401 };
    }

    if (localAuthEnabled) {
        try {
            const localUser = await authenticateLocalFallbackToken(accessToken);
            if (localUser?.id) {
                return { user: localUser };
            }
        } catch (error) {
            console.error('[AUTH] Failed to validate local fallback token:', error);
        }
    }

    if (!supabase) {
        return { error: 'No auth backend configured on signaling server', status: 503 };
    }

    try {
        const { data, error } = await supabase.auth.getUser(accessToken);
        if (error || !data?.user?.id) {
            return { error: 'Invalid or expired bearer token', status: 401 };
        }
        return { user: data.user };
    } catch (error) {
        console.error('[AUTH] Failed to validate bearer token:', error);
        return { error: 'Token validation failed', status: 401 };
    }
}

function extractSocketToken(socket) {
    const authToken = socket.handshake?.auth?.token || socket.handshake?.auth?.accessToken;
    const headerToken = socket.handshake?.headers?.authorization;
    const queryToken = socket.handshake?.query?.token;
    return extractBearerToken(authToken || headerToken || queryToken);
}

async function authenticateSocketConnection(socket, next) {
    if (!consumeRateLimitToken('socket', getClientIp(socket), socketRateLimitPerMinute, 60 * 1000)) {
        next(new Error('Too many socket connections'));
        return;
    }

    const accessToken = extractSocketToken(socket);
    if (!accessToken) {
        next(new Error('Missing bearer token'));
        return;
    }

    if (localAuthEnabled) {
        try {
            const localUser = await authenticateLocalFallbackToken(accessToken);
            if (localUser?.id) {
                socket.data.authUserId = localUser.id.toString().trim();
                socket.data.authLocalFallbackId = localUser.localFallbackId?.toString().trim() || '';
                socket.data.authEmail = localUser.email?.toString().trim().toLowerCase() || '';
                socket.data.authToken = accessToken;
                next();
                return;
            }
        } catch (error) {
            console.error('[AUTH] Failed to authenticate local fallback socket:', error);
        }
    }

    if (!supabase) {
        next(new Error('No auth backend is configured on signaling server'));
        return;
    }

    try {
        const { data, error } = await supabase.auth.getUser(accessToken);
        if (error || !data?.user?.id) {
            next(new Error('Invalid or expired bearer token'));
            return;
        }
        socket.data.authUserId = data.user.id.toString().trim();
        socket.data.authEmail = data.user.email?.toString().trim().toLowerCase() || '';
        socket.data.authToken = accessToken;
        next();
    } catch (error) {
        console.error('[AUTH] Failed to authenticate socket connection:', error);
        next(new Error('Token validation failed'));
    }
}

async function callLocalFallbackApi(socket, path, options = {}) {
    const accessToken = socket?.data?.authToken?.toString().trim();
    if (!localAuthEnabled || !accessToken) return null;

    try {
        const response = await fetch(`${localFallbackApiUrl}${path}`, {
            method: options.method || 'GET',
            headers: {
                Accept: 'application/json',
                Authorization: `Bearer ${accessToken}`,
                'Content-Type': 'application/json',
                'ngrok-skip-browser-warning': 'true'
            },
            body: options.body == null ? undefined : JSON.stringify(options.body)
        });

        const rawText = await response.text();
        let payload = {};
        if (rawText.trim()) {
            try {
                payload = JSON.parse(rawText);
            } catch {
                payload = { message: rawText };
            }
        }

        if (!response.ok) {
            console.warn(`[LOCAL-FALLBACK] ${options.method || 'GET'} ${path} -> ${response.status}`, payload);
            return null;
        }

        return payload;
    } catch (error) {
        console.warn(`[LOCAL-FALLBACK] Failed ${options.method || 'GET'} ${path}:`, error.message || error);
        return null;
    }
}

async function syncLocalVoiceJoin(socket, roomId, details = {}) {
    if (!socket?.data?.authLocalFallbackId) return null;
    const cleanRoomId = roomId?.toString().trim();
    if (!cleanRoomId) return null;

    return callLocalFallbackApi(socket, `/v1/data/voice/rooms/${encodeURIComponent(cleanRoomId)}/join`, {
        method: 'POST',
        body: {
            userName: details.name?.toString().trim() || socket.data.authEmail || socket.data.authUserId,
            userAvatarUrl: details.avatarUrl?.toString().trim() || '',
            socketId: socket.id,
            isMuted: Boolean(details.isMuted)
        }
    });
}

async function syncLocalVoiceLeave(socket, roomId) {
    if (!socket?.data?.authLocalFallbackId) return null;
    const cleanRoomId = roomId?.toString().trim();
    if (!cleanRoomId) return null;

    return callLocalFallbackApi(socket, `/v1/data/voice/rooms/${encodeURIComponent(cleanRoomId)}/leave`, {
        method: 'POST',
        body: {}
    });
}

function requireSocketUser(socket, claimedUserId) {
    const authenticatedUserId = socket.data.authUserId?.toString().trim();
    const normalizedClaim = claimedUserId?.toString().trim();
    if (!authenticatedUserId) {
        socket.emit('auth-error', { error: 'Socket is not authenticated' });
        return null;
    }
    if (normalizedClaim && normalizedClaim !== authenticatedUserId) {
        socket.emit('auth-error', { error: 'Authenticated user does not match requested userId' });
        return null;
    }
    return authenticatedUserId;
}

function requireJoinedScope(socket, membershipKey, rawScopeId, scopeLabel, eventLabel) {
    const scopeId = rawScopeId?.toString().trim();
    if (!scopeId) {
        socket.emit('auth-error', { error: `Missing ${scopeLabel} id for ${eventLabel}` });
        return null;
    }

    const joinedScopes = socket.data[membershipKey];
    if (!(joinedScopes instanceof Set) || !joinedScopes.has(scopeId)) {
        console.warn(
            `[AUTH] Rejected ${eventLabel} from ${socket.id}; socket is not joined to ${scopeLabel} ${scopeId}`
        );
        socket.emit('auth-error', { error: `Socket is not joined to requested ${scopeLabel}` });
        return null;
    }

    return scopeId;
}

function sharesScopeWithTarget(scopeMap, joinedScopes, targetSocketId) {
    if (!(joinedScopes instanceof Set) || joinedScopes.size === 0) return false;
    const targetId = targetSocketId?.toString().trim();
    if (!targetId) return false;

    for (const scopeId of joinedScopes) {
        const participants = scopeMap.get(scopeId);
        if (participants?.has?.(targetId)) {
            return true;
        }
    }

    return false;
}

function buildTurnCredentials(userId) {
    const expiresAt = Math.floor(Date.now() / 1000) + turnTtlSeconds;
    const username = `${expiresAt}:${userId}`;
    const credential = crypto
        .createHmac('sha1', turnSecret)
        .update(username)
        .digest('base64');

    const relayUrls = [];
    if (turnEnableTls) {
        relayUrls.push(`turns:${turnHost}:${turnTlsPort}?transport=tcp`);
    }
    if (turnEnableTcp) {
        relayUrls.push(`turn:${turnHost}:${turnPort}?transport=tcp`);
    }
    if (turnEnableUdp) {
        relayUrls.push(`turn:${turnHost}:${turnPort}?transport=udp`);
    }

    return {
        provider: 'coturn-rest',
        realm: turnRealm,
        ttlSeconds: turnTtlSeconds,
        expiresAtEpochSeconds: expiresAt,
        iceServers: [
            { urls: [`stun:${turnHost}:${turnPort}`] },
            ...relayUrls.map((url) => ({
                urls: [url],
                username,
                credential
            }))
        ]
    };
}

const server = http.createServer(async (req, res) => {
    const requestUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);

    if (requestUrl.pathname.startsWith('/socket.io/')) {
        return;
    }

    if (req.method === 'OPTIONS') {
        if (req.headers.origin && !isOriginAllowed(req.headers.origin.toString().trim())) {
            res.writeHead(403);
            res.end();
            return;
        }
        setCorsHeaders(req, res);
        setSecurityHeaders(res);
        res.writeHead(204);
        res.end();
        return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/health') {
        sendJson(req, res, 200, {
            ok: true,
            localAuthEnabled,
            supabaseEnabled,
            turnConfigured
        });
        return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/') {
        sendJson(req, res, 200, {
            ok: true,
            service: 'webrtc-signaling-server',
            localAuthEnabled,
            supabaseEnabled,
            turnConfigured
        });
        return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/turn-credentials') {
        if (!enforceHttpRateLimit(req, res, 'turn-credentials', 90)) {
            return;
        }
        if (!turnConfigured) {
            sendJson(req, res, 503, {
                error: 'TURN is not configured on server',
                hint: 'Set TURN_HOST and TURN_SECRET in signaling-server/.env'
            });
            return;
        }

        const authResult = await authenticateHttpUser(req);
        if (!authResult.user) {
            sendJson(req, res, authResult.status || 401, { error: authResult.error || 'Unauthorized' });
            return;
        }

        const userId = authResult.user.id.toString().trim();
        if (!userId) {
            sendJson(req, res, 401, { error: 'Authenticated user is missing an id' });
            return;
        }

        sendJson(req, res, 200, buildTurnCredentials(userId));
        return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/live-active-ids') {
        if (!enforceHttpRateLimit(req, res, 'live-active-ids', 240)) {
            return;
        }
        const requestedIds = (requestUrl.searchParams.get('ids') || '')
            .split(',')
            .map((value) => value.trim())
            .filter((value) => value.length > 0);

        const liveIds = Array.from(lives.entries())
            .filter(([liveId, session]) => {
                if (!session?.host?.socketId) return false;
                if (requestedIds.length === 0) return true;
                return requestedIds.includes(liveId);
            })
            .map(([liveId]) => liveId);

        sendJson(req, res, 200, { ok: true, liveIds });
        return;
    }

    if (req.method === 'POST' && requestUrl.pathname === '/resolve-cinema') {
        if (!enforceHttpRateLimit(req, res, 'resolve-cinema', 45)) {
            return;
        }
        const authResult = await authenticateHttpUser(req);
        if (!authResult.user) {
            sendJson(req, res, authResult.status || 401, { error: authResult.error || 'Unauthorized' });
            return;
        }

        let payload;
        try {
            payload = await readJsonBody(req);
        } catch (error) {
            sendJson(req, res, 400, { error: error.message || 'Invalid request body' });
            return;
        }

        const result = await resolveCinemaItem(payload);
        sendJson(req, res, result.error ? 422 : 200, result);
        return;
    }

    sendJson(req, res, 404, { error: 'Not found' });
});

const io = new Server(server, {
    cors: {
        origin: (origin, callback) => {
            if (isOriginAllowed(origin)) {
                callback(null, true);
                return;
            }
            callback(new Error('Origin not allowed by signaling server'));
        },
        methods: ['GET', 'POST'],
        allowedHeaders: ['Authorization', 'Content-Type']
    }
});

// Independent namespaces (do not share state with Voice Rooms)
const callIo = io.of('/call');
const liveIo = io.of('/live');

io.use(authenticateSocketConnection);
callIo.use(authenticateSocketConnection);
liveIo.use(authenticateSocketConnection);

async function setParticipantCount(roomId, participantCount) {
    if (!supabase) return;

    try {
        const normalizedCount = Math.max(0, Number(participantCount || 0));
        const { error: updateError } = await supabase
            .from('voice_rooms')
            .update({
                participant_count: normalizedCount,
                is_active: normalizedCount > 0
            })
            .eq('id', roomId);

        if (updateError) throw updateError;
        console.log(`[DB] Synced count for room ${roomId} to ${normalizedCount}`);
    } catch (error) {
        console.error(`[DB] Error syncing participant_count for room ${roomId}:`, error);
    }
}

async function setLiveViewerCount(liveId, viewerCount) {
    if (!supabase) return;

    try {
        const { error } = await supabase
            .from('live_streams')
            .update({ viewer_count: Math.max(0, Number(viewerCount || 0)) })
            .eq('id', liveId);

        if (error) throw error;
        console.log(`[DB] Synced viewer_count for live ${liveId} to ${viewerCount}`);
    } catch (error) {
        console.error(`[DB] Error syncing viewer_count for live ${liveId}:`, error);
    }
}

async function setLiveStreamActive(liveId) {
    if (!supabase) return;

    try {
        const { error } = await supabase
            .from('live_streams')
            .update({ is_active: true })
            .eq('id', liveId);

        if (error) throw error;
        console.log(`[DB] Marked live ${liveId} as active`);
    } catch (error) {
        console.error(`[DB] Error activating live ${liveId}:`, error);
    }
}

async function endLiveStream(liveId) {
    if (!supabase) return;

    try {
        let error = null;

        try {
            ({ error } = await supabase
                .from('live_streams')
                .update({
                    is_active: false,
                    viewer_count: 0,
                    ended_at: new Date().toISOString()
                })
                .eq('id', liveId));
        } catch (innerError) {
            ({ error } = await supabase
                .from('live_streams')
                .update({ is_active: false, viewer_count: 0 })
                .eq('id', liveId));
            if (error) throw error;
            if (innerError) {
                console.warn(`[DB] ended_at column missing while ending live ${liveId}, fallback applied`);
            }
            console.log(`[DB] Marked live ${liveId} as ended`);
            return;
        }

        if (error) throw error;
        console.log(`[DB] Marked live ${liveId} as ended`);
    } catch (error) {
        console.error(`[DB] Error ending live ${liveId}:`, error);
    }
}

async function getLiveStreamMeta(liveId) {
    if (!supabase) {
        return {
            exists: true,
            isActive: true,
            hostUserId: ''
        };
    }

    try {
        const { data, error } = await supabase
            .from('live_streams')
            .select('id, is_active, host_user_id')
            .eq('id', liveId)
            .maybeSingle();

        if (error) throw error;
        return {
            exists: Boolean(data?.id),
            isActive: data?.is_active === true,
            hostUserId: data?.host_user_id?.toString().trim() || ''
        };
    } catch (error) {
        console.error(`[DB] Error checking live ${liveId} metadata:`, error);
        return {
            exists: true,
            isActive: true,
            hostUserId: ''
        };
    }
}

// Track active rooms and their participants
// room_id -> Set(socket_id)
const rooms = new Map();
const voiceRoomCleanupTimers = new Map();
const VOICE_EMPTY_ROOM_GRACE_MS = clampInt(process.env.VOICE_EMPTY_ROOM_GRACE_MS, 15 * 1000, 3 * 1000, 5 * 60 * 1000);

function clearPendingVoiceRoomCleanup(roomId) {
    const timer = voiceRoomCleanupTimers.get(roomId);
    if (timer) {
        clearTimeout(timer);
        voiceRoomCleanupTimers.delete(roomId);
    }
}

async function finalizeEmptyVoiceRoom(roomId) {
    clearPendingVoiceRoomCleanup(roomId);

    if (!supabase) {
        rooms.delete(roomId);
        return;
    }

    try {
        const { data, error } = await supabase
            .from('voice_rooms')
            .select('id, is_public, name')
            .eq('id', roomId)
            .maybeSingle();

        if (error) throw error;
        if (!data?.id) {
            rooms.delete(roomId);
            return;
        }

        if (data.is_public === false) {
            const { error: deleteError } = await supabase
                .from('voice_rooms')
                .delete()
                .eq('id', roomId);

            if (deleteError) throw deleteError;
            console.log(`[DB] Deleted orphan private voice room ${roomId}`);
        } else {
            const { error: updateError } = await supabase
                .from('voice_rooms')
                .update({ participant_count: 0, is_active: false })
                .eq('id', roomId);

            if (updateError) throw updateError;
            console.log(`[DB] Marked empty voice room ${roomId} inactive`);
        }
    } catch (error) {
        console.error(`[DB] Error finalizing empty voice room ${roomId}:`, error);
    } finally {
        rooms.delete(roomId);
    }
}

function scheduleEmptyVoiceRoomCleanup(roomId) {
    clearPendingVoiceRoomCleanup(roomId);

    const timer = setTimeout(async () => {
        voiceRoomCleanupTimers.delete(roomId);
        const participants = rooms.get(roomId);
        if (participants && participants.size > 0) {
            console.log(`[ROOM] ${roomId} recovered before cleanup timeout`);
            return;
        }

        await finalizeEmptyVoiceRoom(roomId);
    }, VOICE_EMPTY_ROOM_GRACE_MS);

    voiceRoomCleanupTimers.set(roomId, timer);
}

io.on('connection', (socket) => {
    socket.data.joinedRoomIds = new Set();
    console.log(`User connected: ${socket.id} user=${socket.data.authUserId}`);

    socket.on('join-room', async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const roomId = data.roomId?.toString().trim();
        const userId = requireSocketUser(socket, data.userId);
        const { name, avatarUrl } = data;

        if (!roomId || !userId) return;

        socket.join(roomId);
        clearPendingVoiceRoomCleanup(roomId);

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
            isMuted: Boolean(data.isMuted),
            isCameraSharing: false,
            isScreenSharing: false
        });
        socket.data.joinedRoomIds.add(roomId);

        console.log(`[JOIN] User ${userId} joined room ${roomId}. Room population: ${currentRoomParticipants.size}`);
        console.log(`[ROOMS] Currently active: ${Array.from(rooms.keys()).join(', ')}`);
        await syncLocalVoiceJoin(socket, roomId, {
            name,
            avatarUrl,
            isMuted: Boolean(data.isMuted)
        });
        await setParticipantCount(roomId, currentRoomParticipants.size);

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
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'cinema-state');
        if (!roomId) return;
        io.to(roomId).emit("cinema-state", { ...data, roomId });
    });

    socket.on("cinema-sync", (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'cinema-sync');
        if (!roomId) return;
        io.to(roomId).emit("cinema-sync", { ...data, roomId });
    });

    socket.on("cinema-control", (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'cinema-control');
        if (!roomId) return;
        io.to(roomId).emit("cinema-control", { ...data, roomId });
    });

    socket.on("resolve-link", async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { url, requestId } = data;
        if (!url || !requestId) {
            socket.emit("link-resolved", { requestId: requestId || "", error: "Missing url or requestId" });
            return;
        }
        console.log(`[RESOLVE] Request from ${socket.id} for ${url}`);

        const result = await resolveLink(url);

        socket.emit("link-resolved", {
            requestId: requestId,
            ...result
        });
    });
    // --------------------------

    socket.on('chat-message', (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'chat-message');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;
        socket.to(roomId).emit('chat-message', { ...data, userId });
    });

    socket.on('reaction', (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'reaction');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;
        socket.to(roomId).emit('reaction', { ...data, userId });
    });

    socket.on('speaking-status', (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'speaking-status');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;
        socket.to(roomId).emit('speaking-status', { ...data, userId });
    });

    socket?.on('mute-state', (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'mute-state');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;
        socket.to(roomId).emit('mute-state', { ...data, userId });
    });

    socket?.on('media-status', (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'media-status');
        const { isCameraSharing, isScreenSharing } = data;
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;

        // Persist on server for late joiners
        const currentRoomParticipants = rooms.get(roomId);
        if (currentRoomParticipants && currentRoomParticipants.has(socket.id)) {
            const p = currentRoomParticipants.get(socket.id);
            p.isCameraSharing = isCameraSharing;
            p.isScreenSharing = isScreenSharing;
        }

        // Broadcast media state to everyone else in the room
        socket.to(roomId).emit('media-status', { ...data, userId }, socket.id);
    });

    // WebRTC Signaling: Forwarding Offer, Answer, and ICE Candidates

    socket.on('offer', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, offer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(rooms, socket.data.joinedRoomIds, target)) {
            socket.emit('auth-error', { error: 'Offer target is not in a shared room' });
            return;
        }
        const sdpPreview = offer.sdp ? offer.sdp.substring(0, 50).replace(/\n/g, "") : "no sdp";
        console.log(`[SIGNAL] Forwarding offer from ${socket.id} to ${target}. SDP: ${sdpPreview}...`);
        io.to(target).emit('offer', {
            sender: socket.id,
            offer: offer,
            userId: authenticatedUserId,
            name: name
        });
    });

    socket.on('answer', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, answer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(rooms, socket.data.joinedRoomIds, target)) {
            socket.emit('auth-error', { error: 'Answer target is not in a shared room' });
            return;
        }
        const sdpPreview = answer.sdp ? answer.sdp.substring(0, 50).replace(/\n/g, "") : "no sdp";
        console.log(`[SIGNAL] Forwarding answer from ${socket.id} to ${target}. SDP: ${sdpPreview}...`);
        io.to(target).emit('answer', {
            sender: socket.id,
            answer: answer,
            userId: authenticatedUserId,
            name: name
        });
    });

    socket.on('ice-candidate', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, candidate, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(rooms, socket.data.joinedRoomIds, target)) {
            socket.emit('auth-error', { error: 'ICE target is not in a shared room' });
            return;
        }
        console.log(`[SIGNAL] Forwarding ice-candidate from ${socket.id} to ${target}. Type: ${candidate.candidate ? candidate.candidate.split(' ')[7] : 'unknown'}`);
        io.to(target).emit('ice-candidate', {
            sender: socket.id,
            candidate: candidate,
            userId: authenticatedUserId,
            name: name
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
            await syncLocalVoiceLeave(socket, roomId);
            await setParticipantCount(roomId, participants.size);

            if (participants.size === 0) {
                console.log(`[ROOM] ${roomId} is empty. Waiting ${VOICE_EMPTY_ROOM_GRACE_MS}ms before cleanup`);
                scheduleEmptyVoiceRoomCleanup(roomId);
            }
        }

        socket.data.joinedRoomIds.clear();
    });

    socket.on('disconnect', () => {
        console.log(`User disconnected: ${socket.id}`);
    });
});

// -------------------- CALLS (WhatsApp-style) --------------------
// call_id -> Map(socket_id -> {userId, name, avatarUrl})
const calls = new Map();

callIo.on('connection', (socket) => {
    socket.data.joinedCallIds = new Set();
    console.log(`[CALL] User connected: ${socket.id} user=${socket.data.authUserId}`);

    socket.on('join-call', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const callId = data.callId?.toString().trim();
        const userId = requireSocketUser(socket, data.userId);
        const { name, avatarUrl } = data;

        if (!callId || !userId) return;

        socket.join(callId);

        if (!calls.has(callId)) {
            calls.set(callId, new Map());
        }

        const currentCallParticipants = calls.get(callId);

        // Ghost prevention: same userId, old socket -> remove
        currentCallParticipants.forEach((val, sid) => {
            if (val.userId === userId) {
                console.log(`[CALL][CLEANUP] Removing ghost for user ${userId} (old SID: ${sid})`);
                currentCallParticipants.delete(sid);
                socket.to(callId).emit('user-left', sid);
            }
        });

        currentCallParticipants.set(socket.id, {
            userId,
            name,
            avatarUrl
        });
        socket.data.joinedCallIds.add(callId);

        console.log(`[CALL][JOIN] ${userId} joined ${callId}. Population: ${currentCallParticipants.size}`);

        socket.to(callId).emit('user-joined', {
            socketId: socket.id,
            userId,
            name,
            avatarUrl
        });

        const participants = [];
        currentCallParticipants.forEach((p, sid) => {
            if (sid !== socket.id) {
                participants.push({
                    socketId: sid,
                    userId: p.userId,
                    name: p.name,
                    avatarUrl: p.avatarUrl
                });
            }
        });
        socket.emit('current-participants', participants);
    });

    socket.on('reaction', (data) => {
        const callId = requireJoinedScope(socket, 'joinedCallIds', data?.callId, 'call', 'reaction');
        const userId = requireSocketUser(socket, data.userId);
        if (!callId || !userId) return;
        socket.to(callId).emit('reaction', { ...data, userId });
    });

    socket.on('mute-state', (data) => {
        const callId = requireJoinedScope(socket, 'joinedCallIds', data?.callId, 'call', 'mute-state');
        const userId = requireSocketUser(socket, data.userId);
        if (!callId || !userId) return;
        socket.to(callId).emit('mute-state', { ...data, userId });
    });

    // WebRTC signaling (scoped to /call namespace)
    socket.on('offer', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, offer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(calls, socket.data.joinedCallIds, target)) {
            socket.emit('auth-error', { error: 'Offer target is not in a shared call' });
            return;
        }
        callIo.to(target).emit('offer', {
            sender: socket.id,
            offer,
            userId: authenticatedUserId,
            name
        });
    });

    socket.on('answer', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, answer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(calls, socket.data.joinedCallIds, target)) {
            socket.emit('auth-error', { error: 'Answer target is not in a shared call' });
            return;
        }
        callIo.to(target).emit('answer', {
            sender: socket.id,
            answer,
            userId: authenticatedUserId,
            name
        });
    });

    socket.on('ice-candidate', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, candidate, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(calls, socket.data.joinedCallIds, target)) {
            socket.emit('auth-error', { error: 'ICE target is not in a shared call' });
            return;
        }
        callIo.to(target).emit('ice-candidate', {
            sender: socket.id,
            candidate,
            userId: authenticatedUserId,
            name
        });
    });

    socket.on('disconnecting', () => {
        const joinedCallIds = Array.from(socket.data.joinedCallIds || []);

        for (const callId of joinedCallIds) {
            const participants = calls.get(callId);
            if (!participants) continue;

            const wasParticipant = participants.delete(socket.id);
            if (!wasParticipant) continue;

            console.log(`[CALL][LEAVE] Socket left ${callId}. Population: ${participants.size}`);
            socket.to(callId).emit('user-left', socket.id);

            if (participants.size === 0) {
                calls.delete(callId);
            }
        }

        socket.data.joinedCallIds.clear();
    });

    socket.on('disconnect', () => {
        console.log(`[CALL] User disconnected: ${socket.id}`);
    });
});

// -------------------- LIVES (namespace reserved) --------------------
// live_id -> {
//   host: {socketId,userId,name,avatarUrl} | null,
//   viewers: Map(socketId -> {...}),
//   state: { source, isPaused, videoEnabled, micEnabled }
// }
const lives = new Map();
const liveEndTimers = new Map();
const HOST_DISCONNECT_GRACE_MS = 90 * 1000;

function clearPendingLiveTermination(liveId) {
    const timer = liveEndTimers.get(liveId);
    if (timer) {
        clearTimeout(timer);
        liveEndTimers.delete(liveId);
    }
}

async function finalizeLiveTermination(liveId, reason = 'ended') {
    clearPendingLiveTermination(liveId);

    if (!lives.has(liveId)) {
        await endLiveStream(liveId);
        return;
    }

    console.log(`[LIVE][END] ${reason}: ${liveId}`);
    liveIo.to(liveId).emit('live-ended');
    lives.delete(liveId);
    await endLiveStream(liveId);
}

function scheduleHostTerminationGrace(liveId, hostSnapshot) {
    clearPendingLiveTermination(liveId);

    const timer = setTimeout(async () => {
        liveEndTimers.delete(liveId);
        const session = lives.get(liveId);
        if (!session) return;

        if (session.host?.userId === hostSnapshot.userId) {
            console.log(`[LIVE][HOST] ${hostSnapshot.userId} reconnected to ${liveId} before grace timeout`);
            return;
        }

        console.log(
            `[LIVE][HOST] ${hostSnapshot.userId} did not reconnect within ${HOST_DISCONNECT_GRACE_MS}ms. Ending ${liveId}`
        );
        await finalizeLiveTermination(liveId, 'host-disconnect-timeout');
    }, HOST_DISCONNECT_GRACE_MS);

    liveEndTimers.set(liveId, timer);
}

liveIo.on('connection', (socket) => {
    socket.data.joinedLiveIds = new Set();
    socket.data.liveRole = null;
    socket.data.liveId = null;

    console.log(`[LIVE] User connected: ${socket.id} user=${socket.data.authUserId}`);

    socket.on('join-live', async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = data.liveId?.toString().trim();
        const userId = requireSocketUser(socket, data.userId);
        const { name, avatarUrl } = data;
        const role = data.role?.toString().trim().toLowerCase(); // "host" | "viewer"

        if (!liveId || !userId || (role !== 'host' && role !== 'viewer')) return;

        socket.join(liveId);
        socket.data.joinedLiveIds.add(liveId);
        socket.data.liveRole = role;
        socket.data.liveId = liveId;

        if (!lives.has(liveId)) {
            const liveMeta = await getLiveStreamMeta(liveId);
            if (!liveMeta.exists) {
                console.warn(`[LIVE][JOIN] Rejected ${role} ${userId} because live ${liveId} does not exist`);
                socket.emit('live-ended');
                return;
            }

            if (role === 'host' && liveMeta.hostUserId && liveMeta.hostUserId !== userId) {
                console.warn(
                    `[LIVE][JOIN] Rejected host ${userId} for ${liveId} because owner is ${liveMeta.hostUserId}`
                );
                socket.emit('live-ended');
                return;
            }

            if (role !== 'host' && !liveMeta.isActive) {
                console.warn(`[LIVE][JOIN] Rejected ${role} ${userId} for inactive live ${liveId}`);
                socket.emit('live-ended');
                return;
            }

            if (role !== 'host') {
                console.warn(
                    `[LIVE][JOIN] Rejected viewer ${userId} for ${liveId} because there is no active host session`
                );
                socket.emit('live-ended');
                return;
            }

            lives.set(liveId, {
                host: null,
                viewers: new Map(),
                state: {
                    source: 'camera',
                    isPaused: false,
                    videoEnabled: true,
                    micEnabled: true
                }
            });
        }

        const session = lives.get(liveId);

        if (role === 'host') {
            clearPendingLiveTermination(liveId);
            session.host = { socketId: socket.id, userId, name, avatarUrl };
            session.state = {
                source: (data.source || session.state?.source || 'camera').toString().trim().toLowerCase() === 'screen' ? 'screen' : 'camera',
                isPaused: Boolean(data.isPaused),
                videoEnabled: data.videoEnabled !== false,
                micEnabled: data.micEnabled !== false
            };
            console.log(`[LIVE][HOST] ${userId} is hosting ${liveId}`);

            // Send existing viewers to host
            const currentViewers = [];
            session.viewers.forEach((v, sid) => {
                currentViewers.push({ socketId: sid, userId: v.userId, name: v.name, avatarUrl: v.avatarUrl });
            });
            socket.emit('current-viewers', currentViewers);

            // Inform viewers about host (so they can connect)
            session.viewers.forEach((v, sid) => {
                liveIo.to(sid).emit('host-info', {
                    socketId: socket.id,
                    userId,
                    name,
                    avatarUrl
                });
                liveIo.to(sid).emit('live-state', session.state);
            });

            // Emit count
            const viewerCount = session.viewers.size;
            liveIo.to(liveId).emit('viewer-count', { liveId, viewerCount });
            await setLiveStreamActive(liveId);
            await setLiveViewerCount(liveId, viewerCount);
            return;
        }

        // Viewer join
        session.viewers.set(socket.id, { userId, name, avatarUrl });
        console.log(`[LIVE][VIEWER] ${userId} joined ${liveId}. Viewers: ${session.viewers.size}`);

        // Notify host and provide host info to viewer
        if (session.host?.socketId) {
            liveIo.to(session.host.socketId).emit('viewer-joined', {
                socketId: socket.id,
                userId,
                name,
                avatarUrl
            });

            socket.emit('host-info', {
                socketId: session.host.socketId,
                userId: session.host.userId,
                name: session.host.name,
                avatarUrl: session.host.avatarUrl
            });
            socket.emit('live-state', session.state);
        }

        const viewerCount = session.viewers.size;
        liveIo.to(liveId).emit('viewer-count', { liveId, viewerCount });
        await setLiveViewerCount(liveId, viewerCount);
    });

    socket.on('end-live', async (data) => {
        const liveId = requireJoinedScope(socket, 'joinedLiveIds', data?.liveId, 'live', 'end-live');
        if (!liveId) return;

        const session = lives.get(liveId);
        if (!session) return;

        // Only host can end
        if (session.host?.socketId !== socket.id) return;

        await finalizeLiveTermination(liveId, 'host-ended');
    });

    // Reactions (floating UI like TikTok)
    socket.on('send-reaction', (data) => {
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data?.liveId || socket.data.liveId,
            'live',
            'send-reaction'
        );
        if (!liveId) return;

        const emoji = (data?.emoji || '❤️').toString().trim() || '❤️';
        const userId = requireSocketUser(socket, data?.userId);
        if (!userId) return;

        liveIo.to(liveId).emit('reaction', { liveId, emoji, userId });
    });

    socket.on('update-live-state', (data) => {
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data?.liveId || socket.data.liveId,
            'live',
            'update-live-state'
        );
        if (!liveId) return;

        const session = lives.get(liveId);
        if (!session || session.host?.socketId !== socket.id) return;

        session.state = {
            source: (data?.source || session.state?.source || 'camera').toString().trim().toLowerCase() === 'screen' ? 'screen' : 'camera',
            isPaused: Boolean(data?.isPaused),
            videoEnabled: data?.videoEnabled !== false,
            micEnabled: data?.micEnabled !== false
        };

        liveIo.to(liveId).emit('live-state', session.state);
    });

    // WebRTC signaling (scoped to /live namespace)
    socket.on('offer', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, offer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(lives, socket.data.joinedLiveIds, target)) {
            socket.emit('auth-error', { error: 'Offer target is not in a shared live session' });
            return;
        }
        liveIo.to(target).emit('offer', {
            sender: socket.id,
            offer,
            userId: authenticatedUserId,
            name
        });
    });

    socket.on('answer', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, answer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(lives, socket.data.joinedLiveIds, target)) {
            socket.emit('auth-error', { error: 'Answer target is not in a shared live session' });
            return;
        }
        liveIo.to(target).emit('answer', {
            sender: socket.id,
            answer,
            userId: authenticatedUserId,
            name
        });
    });

    socket.on('ice-candidate', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { target, candidate, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!sharesScopeWithTarget(lives, socket.data.joinedLiveIds, target)) {
            socket.emit('auth-error', { error: 'ICE target is not in a shared live session' });
            return;
        }
        liveIo.to(target).emit('ice-candidate', {
            sender: socket.id,
            candidate,
            userId: authenticatedUserId,
            name
        });
    });

    socket.on('disconnecting', async () => {
        const joined = Array.from(socket.data.joinedLiveIds || []);

        for (const liveId of joined) {
            const session = lives.get(liveId);
            if (!session) continue;

            // Host disconnect => keep the live alive for a grace window
            if (session.host?.socketId === socket.id) {
                const disconnectedHost = session.host;
                session.host = null;
                console.log(
                    `[LIVE][HOST] ${disconnectedHost.userId} disconnected from ${liveId}. Waiting ${HOST_DISCONNECT_GRACE_MS}ms for reconnection`
                );
                scheduleHostTerminationGrace(liveId, disconnectedHost);
                continue;
            }

            // Viewer disconnect
            const wasViewer = session.viewers.delete(socket.id);
            if (wasViewer) {
                if (session.host?.socketId) {
                    liveIo.to(session.host.socketId).emit('viewer-left', socket.id);
                }

                const viewerCount = session.viewers.size;
                liveIo.to(liveId).emit('viewer-count', { liveId, viewerCount });
                await setLiveViewerCount(liveId, viewerCount);

                if (!session.host && viewerCount === 0) {
                    clearPendingLiveTermination(liveId);
                    lives.delete(liveId);
                    await endLiveStream(liveId);
                }
            }
        }

        socket.data.joinedLiveIds.clear();
    });

    socket.on('disconnect', () => {
        console.log(`[LIVE] User disconnected: ${socket.id}`);
    });
});

server.on('error', (error) => {
    console.error('[FATAL] HTTP server error:', error);
    process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`Signaling server running on port ${PORT}`);
});
