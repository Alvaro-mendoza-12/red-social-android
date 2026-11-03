require('dotenv').config();
const crypto = require('crypto');
const http = require('http');
const { Server } = require('socket.io');

const PORT = Number(process.env.PORT || 3001);
const { resolveLink } = require('./resolver');
const { normalizeSignalPayload } = require('./payload-policy');
const { registerSafeSocketEvent, validateSocketPayload } = require('./socket-event-guard');
const { authorizeCallJoin } = require('./call-join-policy');
const { updateLiveGuestMediaState } = require('./live-guest-media-state');
const { liveReactionPolicy } = require('./live-reaction-policy');
const { roomChatMessage, createRoomChatLedger } = require('./room-chat-policy');
const { createRoomChatTransport } = require('./room-chat-transport');

const callPermissionCache = new WeakMap();
async function refreshCallPermission(socket, force = false) {
    const callId = Array.from(socket.data.joinedCallIds || [])[0];
    if (!callId) return false;
    const cached = callPermissionCache.get(socket);
    if (!force && cached?.callId === callId && Date.now() - cached.at < 2000) return true;
    try {
        const access = await callUbuntuApi(`/v1/data/calls/${encodeURIComponent(callId)}/access`, {
            method: 'POST', accessToken: getSocketAuthToken(socket), body: {}, timeoutMs: 4000
        });
        if (access?.allowed !== true || access.callId !== callId || access.userId !== socket.data.authUserId ||
            !['ringing', 'active'].includes(access.state)) {
            throw Object.assign(new Error('CALL_ACCESS_DENIED'), { status: 403 });
        }
        if (!socket.connected || !socket.data.joinedCallIds.has(callId)) return false;
        callPermissionCache.set(socket, { callId, at: Date.now() });
        return true;
    } catch (error) {
        callPermissionCache.delete(socket);
        if (!error?.status || error.status >= 500 || error.status === 429) {
            // Temporary API failures pause signaling while existing media and reconnects survive.
            socket.emit('call-access-unavailable', { callId, error: 'CALL_ACCESS_UNAVAILABLE' });
            return false;
        }
        try {
            socket.emit('call-access-revoked', { callId, error: 'CALL_ACCESS_DENIED' });
            socket.disconnect(false);
        } catch {}
        return false;
    }
}

function safeSocketOn(socket, event, listener) {
    const guarded = socket.nsp?.name === '/call' && !['join-call', 'disconnect', 'disconnecting'].includes(event)
        ? async function (...args) {
            if (!(await refreshCallPermission(socket))) return;
            return listener.apply(this, args);
        }
        : listener;
    return registerSafeSocketEvent(socket, event, guarded, (name, error) => {
        console.error('[SIGNAL_EVENT_FAILED]', { event: name, error: diagnosticError(error) });
    });
}
const { classifyLiveLookupFailure } = require('./live-access-policy');
const { connectDistributedAdapter } = require('./distributed-adapter');
const { fetchNamespaceScopeMembers, sharesNamespaceScope } = require('./distributed-scope');
const {
    createNoopDistributedLiveState,
    hydrateLiveSession
} = require('./distributed-live-state');

const fallbackApiUrl = normalizeBaseUrl(
    process.env.FALLBACK_API_URL ||
    process.env.UBUNTU_API_URL ||
    process.env.LOCAL_FALLBACK_API_URL ||
    process.env.VITE_FALLBACK_API_URL
);
const ubuntuApiEnabled = Boolean(fallbackApiUrl);

if (!ubuntuApiEnabled) {
    console.warn('[SOCKET_DEBUG] [WARN] FALLBACK_API_URL is not set. Signaling auth will reject clients until the Ubuntu API URL is configured.');
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
const turnExternalIp = process.env.TURN_EXTERNAL_IP?.toString().trim() || '';
const staticTurnUrls = parseCsvEnv(process.env.TURN_STATIC_URLS || process.env.TURN_URLS);
const staticTurnUsername = process.env.TURN_STATIC_USERNAME?.toString().trim() || '';
const staticTurnCredential = process.env.TURN_STATIC_CREDENTIAL?.toString().trim() || '';
const staticStunUrls = parseCsvEnv(process.env.STUN_STATIC_URLS || process.env.STUN_URLS);
const hasStaticTurnConfigured = Boolean(staticTurnUrls.length > 0 && staticTurnUsername && staticTurnCredential);
const hasCoturnRestConfigured = Boolean(turnHost && turnSecret);
const turnConfigured = hasCoturnRestConfigured || hasStaticTurnConfigured;
const httpRateLimitPerMinute = clampInt(process.env.HTTP_RATE_LIMIT_PER_MINUTE, 180, 20, 5000);
const socketRateLimitPerMinute = clampInt(process.env.SOCKET_RATE_LIMIT_PER_MINUTE, 90, 10, 2000);
const socketEventRateLimitPerMinute = clampInt(process.env.SOCKET_EVENT_RATE_LIMIT_PER_MINUTE, 900, 120, 10_000);
// Keep signaling capacity aligned with the API capability advertised to Android.
// The default allows one host and six guests in the adaptive Android grid.
const maxLiveGuests = clampInt(process.env.LIVE_MAX_GUESTS, 6, 1, 8);
const liveInternalEventsToken = String(process.env.LIVE_INTERNAL_EVENTS_TOKEN || '').trim();
const signalingInstanceId = crypto.randomUUID();
const allowedOrigins = parseAllowedOrigins(process.env.SIGNALING_ALLOWED_ORIGINS);
const rateLimitBuckets = new Map();
const diagnosticSalt = crypto.randomBytes(32);
// Socket.IO adapters can serialize socket.data during cross-node socket lookups.
// Keep bearer tokens process-local so they can never enter Redis or a remote
// server-side socket-management response.
const socketAuthTokens = new WeakMap();
let distributedAdapterRuntime = {
    getStatus: () => ({
        mode: 'starting',
        configured: Boolean(String(process.env.SIGNALING_REDIS_URL || '').trim()),
        required: false,
        connected: false,
        streamCount: 0,
        connectionRecovery: false,
        lastErrorCode: null
    }),
    liveState: createNoopDistributedLiveState(),
    close: async () => undefined
};

function getSocketAuthToken(socket) {
    return socketAuthTokens.get(socket) || '';
}

if (String(process.env.NODE_ENV || '').trim().toLowerCase() === 'production' &&
    (allowedOrigins.size === 0 || allowedOrigins.has('*'))) {
    throw new Error('SIGNALING_ALLOWED_ORIGINS must list explicit trusted origins in production');
}
if (String(process.env.NODE_ENV || '').trim().toLowerCase() === 'production' && liveInternalEventsToken.length < 32) {
    throw new Error('LIVE_INTERNAL_EVENTS_TOKEN must contain at least 32 characters in production');
}

// Periodic cleanup to prevent memory leaks from inactive rate limit buckets
setInterval(() => {
    const now = Date.now();
    for (const [key, timestamps] of rateLimitBuckets.entries()) {
        const fresh = timestamps.filter(t => now - t < 60000);
        if (fresh.length === 0) {
            rateLimitBuckets.delete(key);
        } else {
            rateLimitBuckets.set(key, fresh);
        }
    }
}, 5 * 60 * 1000).unref();

if (allowedOrigins.size === 0) {
    console.warn('[SOCKET_DEBUG] [WARN] SIGNALING_ALLOWED_ORIGINS not set. Only local browser origins and native clients without Origin header will be allowed.');
}

function parseBooleanEnv(value, defaultValue) {
    if (value == null) return defaultValue;
    const normalized = value.toString().trim().toLowerCase();
    if (['1', 'true', 'yes', 'on'].includes(normalized)) return true;
    if (['0', 'false', 'no', 'off'].includes(normalized)) return false;
    return defaultValue;
}

function normalizeBaseUrl(rawValue) {
    return (rawValue || '').toString().trim().replace(/\/+$/, '');
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

function parseCsvEnv(rawValue) {
    return (rawValue || '')
        .toString()
        .split(',')
        .map((value) => value.trim())
        .filter((value) => value.length > 0);
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

function normalizedDisplayName(value) {
    return String(value || 'Usuario')
        .replace(/[\u0000-\u001f\u007f]/g, '')
        .trim()
        .slice(0, 100) || 'Usuario';
}

function normalizedAvatarUrl(value) {
    return String(value || '')
        .replace(/[\u0000-\u001f\u007f]/g, '')
        .trim()
        .slice(0, 2_048);
}

function diagnosticRef(value) {
    const normalized = value?.toString().trim();
    if (!normalized) return 'n/a';
    return crypto.createHmac('sha256', diagnosticSalt).update(normalized).digest('hex').slice(0, 12);
}

function diagnosticError(error) {
    return {
        status: Number(error?.status) || undefined,
        code: error?.code?.toString().slice(0, 80) || undefined,
        message: error?.message?.toString().slice(0, 160) || 'unknown_error'
    };
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

function buildUbuntuApiUrl(path) {
    const normalizedPath = path.toString().startsWith('/') ? path.toString() : `/${path}`;
    return `${fallbackApiUrl}${normalizedPath}`;
}

async function callUbuntuApi(path, options = {}) {
    if (!ubuntuApiEnabled) {
        const error = new Error('FALLBACK_API_URL is not configured on signaling server');
        error.status = 503;
        throw error;
    }

    const method = (options.method || 'GET').toString().toUpperCase();
    const headers = {
        Accept: 'application/json',
        'ngrok-skip-browser-warning': 'true',
        ...(options.headers || {})
    };
    if (options.accessToken) {
        headers.Authorization = `Bearer ${options.accessToken}`;
    }
    if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }

    const response = await fetch(buildUbuntuApiUrl(path), {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: AbortSignal.timeout(Math.max(1000, Math.min(10000, Number(options.timeoutMs) || 5000)))
    });

    const text = await response.text();
    let payload = {};
    if (text) {
        try {
            payload = JSON.parse(text);
        } catch {
            payload = { raw: text };
        }
    }

    if (!response.ok) {
        const error = new Error(payload?.error || `Ubuntu API request failed with HTTP ${response.status}`);
        error.status = response.status;
        error.payload = payload;
        throw error;
    }

    return payload;
}

async function resolveUbuntuUser(accessToken) {
    if (!accessToken) {
        return { error: 'Missing bearer token', status: 401 };
    }

    try {
        const payload = await callUbuntuApi('/v1/auth/me', { accessToken });
        const user = payload?.user;
        if (!user?.id) {
            return { error: 'Invalid Ubuntu auth response', status: 401 };
        }
        // CRITICAL FIX: Android uses displayUserId = supabaseUserId || localId.
        // The signaling server must resolve the same canonical userId so that
        // claimedUserId (from Android) matches authUserId (from server).
        // user.id = local fallback ID (from fallback_users.id, used as JWT sub)
        // user.supabaseUserId = UUID from Supabase (preferred as canonical ID)
        const canonicalId = (user.supabaseUserId || user.id).toString().trim();
        return { user: { ...user, canonicalId } };
    } catch (error) {
        console.error('[SOCKET_DEBUG] [AUTH] Failed to validate bearer token with Ubuntu API:', diagnosticError(error));
        return {
            error: error.status === 503 ? error.message : 'Invalid or expired bearer token',
            status: error.status || 401
        };
    }
}

async function authenticateHttpUser(req) {
    const accessToken = extractBearerToken(req.headers.authorization);
    return resolveUbuntuUser(accessToken);
}

function extractSocketToken(socket) {
    const authToken = socket.handshake?.auth?.token || socket.handshake?.auth?.accessToken;
    const headerToken = socket.handshake?.headers?.authorization;
    // Query-string bearer tokens leak through proxy access logs. Socket.IO
    // auth payloads and native Authorization headers cover every client.
    return extractBearerToken(authToken || headerToken);
}

function installSocketPacketGuard(socket) {
    socket.use(([event, payload], next) => {
        const validation = validateSocketPayload(event, payload);
        if (!validation.ok) {
            socket.emit('signal-error', { event: String(event).slice(0, 80), error: validation.code });
            next(new Error(validation.code));
            return;
        }
        const eventName = typeof event === 'string' ? event.slice(0, 80) : 'unknown';
        if (consumeRateLimitToken('socket-event', socket.id, socketEventRateLimitPerMinute, 60 * 1000)) {
            next();
            return;
        }
        console.warn(`[SOCKET_DEBUG] [RATE_LIMIT] Rejected event=${eventName} socket=${socket.id}`);
        next(new Error('Too many signaling events'));
    });
}

async function authenticateSocketConnection(socket, next) {
    if (!consumeRateLimitToken('socket', getClientIp(socket), socketRateLimitPerMinute, 60 * 1000)) {
        next(new Error('Too many socket connections'));
        return;
    }

    const accessToken = extractSocketToken(socket);
    const authResult = await resolveUbuntuUser(accessToken);
    if (!authResult.user) {
        next(new Error(authResult.error || 'Invalid or expired bearer token'));
        return;
    }

    try {
        socketAuthTokens.set(socket, accessToken);
        // Use canonicalId = supabaseUserId || localId, matching Android's displayUserId resolution
        socket.data.authUserId = authResult.user.canonicalId || authResult.user.id.toString().trim();
        socket.data.authLocalId = authResult.user.id.toString().trim();
        console.log(`[IDENTITY] Socket connected: socketId=${socket.id} | userRef=${diagnosticRef(socket.data.authUserId)}`);
        next();
    } catch (error) {
        console.error('[SOCKET_DEBUG] [AUTH] Failed to authenticate socket connection:', diagnosticError(error));
        next(new Error('Token validation failed'));
    }
}

function requireSocketUser(socket, claimedUserId) {
    const authenticatedUserId = socket.data.authUserId?.toString().trim();
    const localId = socket.data.authLocalId?.toString().trim();
    const normalizedClaim = claimedUserId?.toString().trim();
    if (!authenticatedUserId) {
        socket.emit('auth-error', { error: 'Socket is not authenticated' });
        return null;
    }
    if (normalizedClaim && normalizedClaim !== authenticatedUserId) {
        // Secondary check: claim may match the raw local fallback ID (JWT sub)
        if (localId && normalizedClaim === localId) {
            // Android is sending the local ID but server resolved supabaseUserId as canonical.
            // This is safe: return canonical ID so all routing uses the consistent display ID.
            console.warn(`[IDENTITY] [AUTH] Local/canonical alias resolved for socket=${socket.id} | userRef=${diagnosticRef(authenticatedUserId)}`);
            return authenticatedUserId;
        }
        // True mismatch: different account or stale session
        console.error(`[IDENTITY] [AUTH] MISMATCH - socketId=${socket.id} | claimRef=${diagnosticRef(normalizedClaim)} | userRef=${diagnosticRef(authenticatedUserId)}`);
        socket.emit('auth-error', { error: 'Socket user does not match authenticated account' });
        socket.data.hasIdentityMismatch = true;
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
        console.warn(`[SOCKET_DEBUG] [AUTH] Rejected ${eventLabel} from ${socket.id}; socket is not joined to ${scopeLabel} ref=${diagnosticRef(scopeId)}`
        );
        socket.emit('auth-error', { error: `Socket is not joined to requested ${scopeLabel}` });
        return null;
    }

    return scopeId;
}

async function requireSharedScopeTarget(socket, namespace, membershipKey, targetSocketId, scopeLabel, eventLabel) {
    try {
        const authorized = await sharesNamespaceScope(
            namespace,
            socket.data[membershipKey],
            targetSocketId
        );
        if (authorized) return true;
        socket.emit('auth-error', { error: `${eventLabel} target is not in a shared ${scopeLabel}` });
        return false;
    } catch (error) {
        console.error(`[SOCKET_DEBUG] [AUTH] Distributed ${eventLabel} authorization failed`, diagnosticError(error));
        socket.emit('signal-error', { error: `Unable to authorize ${eventLabel} target` });
        return false;
    }
}

function setVoiceParticipantSocketData(socket, participant) {
    if (!participant) {
        delete socket.data.voiceParticipant;
        return;
    }
    socket.data.voiceParticipant = {
        userId: participant.userId,
        name: participant.name,
        avatarUrl: participant.avatarUrl,
        role: participant.role,
        isMuted: Boolean(participant.isMuted),
        handRaised: Boolean(participant.handRaised),
        isCameraSharing: Boolean(participant.isCameraSharing),
        isScreenSharing: Boolean(participant.isScreenSharing),
        captionsEnabled: Boolean(participant.captionsEnabled),
        canControlCinema: Boolean(participant.canControlCinema)
    };
}

function setCallParticipantSocketData(socket, participant) {
    if (!participant) {
        delete socket.data.callParticipant;
        return;
    }
    socket.data.callParticipant = {
        userId: participant.userId,
        name: participant.name,
        avatarUrl: participant.avatarUrl,
        videoEnabled: Boolean(participant.videoEnabled)
    };
}

function summarizeTurnHealth() {
    const mode = hasStaticTurnConfigured
        ? 'static-turn'
        : hasCoturnRestConfigured
            ? 'coturn-rest'
            : 'none';
    const relayUrls = [];

    if (hasStaticTurnConfigured) {
        relayUrls.push(...staticTurnUrls);
    } else if (hasCoturnRestConfigured) {
        if (turnEnableTls) relayUrls.push(`turns:${turnHost}:${turnTlsPort}?transport=tcp`);
        if (turnEnableTcp) relayUrls.push(`turn:${turnHost}:${turnPort}?transport=tcp`);
        if (turnEnableUdp) relayUrls.push(`turn:${turnHost}:${turnPort}?transport=udp`);
    }

    return {
        configured: turnConfigured,
        mode,
        host: turnHost || null,
        realm: turnRealm || null,
        externalIpConfigured: Boolean(turnExternalIp),
        transports: {
            udp: turnConfigured && (hasStaticTurnConfigured || turnEnableUdp),
            tcp: turnConfigured && (hasStaticTurnConfigured || turnEnableTcp),
            tls: turnConfigured && (hasStaticTurnConfigured || turnEnableTls)
        },
        relayUrls,
        note: 'Cloudflare Tunnel only carries HTTP/WebSocket signaling. Public WebRTC media across CGNAT still requires a publicly reachable TURN/SFU relay.'
    };
}

function buildServiceHealthPayload() {
    const realtimeBus = distributedAdapterRuntime.getStatus();
    const realtimeBusHealthy = !realtimeBus.required || realtimeBus.connected;
    return {
        ok: realtimeBusHealthy,
        service: 'webrtc-signaling-server',
        ubuntuApiEnabled,
        turnConfigured,
        turn: summarizeTurnHealth(),
        namespaces: {
            voice: '/',
            call: '/call',
            live: '/live'
        },
        activeSessions: {
            voiceRooms: rooms.size,
            calls: calls.size,
            lives: lives.size,
            scope: 'local-process'
        },
        realtimeBus,
        stateConsistency: {
            signalingTransport: realtimeBus.connected ? 'distributed' : 'single-instance',
            voiceAndCallPresence: realtimeBus.connected ? 'distributed' : 'single-instance',
            liveSessionState: 'replica-affinity-required'
        }
    };
}

function buildTurnCredentials(userId) {
    if (hasStaticTurnConfigured) {
        const stunUrls = staticStunUrls.length > 0 ? staticStunUrls : ['stun:stun.l.google.com:19302'];
        return {
            provider: 'static-turn',
            realm: turnRealm || 'external-turn',
            ttlSeconds: null,
            expiresAtEpochSeconds: null,
            iceServers: [
                ...stunUrls.map((url) => ({ urls: [url] })),
                ...staticTurnUrls.map((url) => ({
                    urls: [url],
                    username: staticTurnUsername,
                    credential: staticTurnCredential
                }))
            ]
        };
    }

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
        const health = buildServiceHealthPayload();
        sendJson(req, res, health.ok ? 200 : 503, health);
        return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/') {
        const health = buildServiceHealthPayload();
        sendJson(req, res, health.ok ? 200 : 503, health);
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
            .filter((value) => value.length > 0 && value.length <= 128)
            .slice(0, 200);

        try {
            const liveSockets = await liveIo.fetchSockets();
            const liveIds = Array.from(new Set(
                liveSockets
                    .map((candidate) => candidate.data?.liveParticipant)
                    .filter((participant) => participant?.role === 'host' && participant?.liveId)
                    .map((participant) => String(participant.liveId))
                    .filter((liveId) => requestedIds.length === 0 || requestedIds.includes(liveId))
            ));
            sendJson(req, res, 200, { ok: true, liveIds });
        } catch (error) {
            console.error('[SOCKET_DEBUG] [LIVE] Distributed active-live lookup failed', diagnosticError(error));
            sendJson(req, res, 503, { ok: false, error: 'LIVE_PRESENCE_UNAVAILABLE' });
        }
        return;
    }

    sendJson(req, res, 404, { error: 'Not found' });
});

const io = new Server(server, {
    maxHttpBufferSize: 256 * 1024,
    perMessageDeflate: false,
    ...(parseBooleanEnv(process.env.SIGNALING_CONNECTION_RECOVERY_ENABLED, false)
        ? {
            connectionStateRecovery: {
                maxDisconnectionDuration: clampInt(
                    process.env.SIGNALING_CONNECTION_RECOVERY_MS,
                    2 * 60 * 1000,
                    10 * 1000,
                    10 * 60 * 1000
                ),
                // Authentication and account-status checks must run again after recovery.
                skipMiddlewares: false
            }
        }
        : {}),
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
    const normalizedCount = Math.max(0, Number(participantCount || 0));
    console.log(`[SOCKET_DEBUG] [DB] Voice room ref=${diagnosticRef(roomId)} presence is ${normalizedCount}; Ubuntu API derives the persisted count from participant rows.`);
}

async function persistVoiceRoomJoin(socket, roomId, participant) {
    const payload = await callUbuntuApi(`/v1/data/voice/rooms/${encodeURIComponent(roomId)}/join`, {
        method: 'POST',
        accessToken: getSocketAuthToken(socket),
        body: {
            socketId: socket.id,
            userName: participant?.name || 'Usuario',
            userAvatarUrl: participant?.avatarUrl || '',
            isMuted: Boolean(participant?.isMuted),
            password: participant?.password || undefined
        }
    });
    const persisted = Array.isArray(payload?.participants)
        ? payload.participants.find((item) => String(item?.user_id || item?.id || '').toLowerCase() === String(socket.data.authUserId || '').toLowerCase())
        : null;
    if (!persisted) {
        const error = new Error('VOICE_ROOM_JOIN_NOT_PERSISTED');
        error.status = 403;
        throw error;
    }
    return {
        persisted,
        canControlCinema: payload?.permissions?.canControlCinema === true
    };
}

function applyPersistedVoiceRoomAccess(socket, roomId, access) {
    const memoryParticipant = rooms.get(roomId)?.get(socket.id);
    if (!memoryParticipant || !access?.persisted) return;
    memoryParticipant.role = String(access.persisted.role || 'speaker').toLowerCase();
    memoryParticipant.isMuted = Boolean(access.persisted.is_muted);
    memoryParticipant.handRaised = Boolean(access.persisted.hand_raised);
    memoryParticipant.canControlCinema = access.canControlCinema === true;
    setVoiceParticipantSocketData(socket, memoryParticipant);
    socket.emit('voice-stage-state', {
        roomId,
        role: memoryParticipant.role,
        isMuted: memoryParticipant.isMuted,
        handRaised: memoryParticipant.handRaised
    });
}

async function syncVoiceRoomJoin(socket, roomId, participant) {
    try {
        const access = await persistVoiceRoomJoin(socket, roomId, participant);
        applyPersistedVoiceRoomAccess(socket, roomId, access);
    } catch (error) {
        console.error(`[SOCKET_DEBUG] [DB] Error syncing voice room join for ref=${diagnosticRef(roomId)}:`, diagnosticError(error));
        socket.emit('room-sync-warning', {
            roomId,
            error: error.message || 'VOICE_ROOM_JOIN_SYNC_FAILED'
        });
    }
}

async function syncVoiceRoomLeave(socket, roomId) {
    try {
        await callUbuntuApi(`/v1/data/voice/rooms/${encodeURIComponent(roomId)}/leave`, {
            method: 'POST',
            accessToken: getSocketAuthToken(socket),
            body: { socketId: socket.id }
        });
    } catch (error) {
        console.error(`[SOCKET_DEBUG] [DB] Error syncing voice room leave for ref=${diagnosticRef(roomId)}:`, diagnosticError(error));
    }
}

async function setLiveViewerCount(liveId, viewerCount, accessToken) {
    if (!accessToken) return;
    try {
        await callUbuntuApi(`/v1/data/live/${encodeURIComponent(liveId)}/state`, {
            method: 'PATCH',
            accessToken,
            body: { viewerCount: Math.max(0, Number(viewerCount || 0)) }
        });
    } catch (error) {
        console.error(`[SOCKET_DEBUG] [LIVE][DB] Failed to update viewer_count for ref=${diagnosticRef(liveId)}:`, diagnosticError(error));
    }
}

async function setLiveReactionCount(liveId, reactionCount, accessToken) {
    if (!accessToken) return;
    try {
        await callUbuntuApi(`/v1/data/live/${encodeURIComponent(liveId)}/state`, {
            method: 'PATCH',
            accessToken,
            body: { totalReactions: Math.max(0, Number(reactionCount || 0)) }
        });
    } catch (error) {
        console.error(`[SOCKET_DEBUG] [LIVE][DB] Failed to update total_reactions for ref=${diagnosticRef(liveId)}:`, diagnosticError(error));
    }
}

async function setLiveStreamActive(liveId, accessToken) {
    if (!accessToken) return;
    try {
        await callUbuntuApi(`/v1/data/live/${encodeURIComponent(liveId)}/state`, {
            method: 'PATCH',
            accessToken,
            body: { isActive: true }
        });
    } catch (error) {
        console.error(`[SOCKET_DEBUG] [LIVE][DB] Failed to activate ref=${diagnosticRef(liveId)}:`, diagnosticError(error));
    }
}

async function endLiveStream(liveId, accessToken, reactionCount = 0) {
    const normalizedReactionCount = Math.max(0, Number(reactionCount || 0));
    if (accessToken) {
        try {
            await callUbuntuApi(`/v1/data/live/${encodeURIComponent(liveId)}/state`, {
                method: 'PATCH',
                accessToken,
                body: {
                    isActive: false,
                    viewerCount: 0,
                    totalReactions: normalizedReactionCount
                }
            });
            return true;
        } catch (error) {
            console.error(`[SOCKET_DEBUG] [LIVE][DB] Bearer termination failed for ref=${diagnosticRef(liveId)}; trying internal recovery`, diagnosticError(error));
        }
    }
    if (!liveInternalEventsToken) {
        console.error(`[SOCKET_DEBUG] [LIVE][DB] Cannot terminate ref=${diagnosticRef(liveId)} without LIVE_INTERNAL_EVENTS_TOKEN`);
        return false;
    }
    try {
        await callUbuntuApi(`/v1/internal/live/${encodeURIComponent(liveId)}/terminate`, {
            method: 'POST',
            headers: { 'x-live-internal-token': liveInternalEventsToken },
            body: {
                reason: accessToken ? 'host-bearer-fallback' : 'signaling-host-lease-expired',
                totalReactions: normalizedReactionCount
            }
        });
        return true;
    } catch (error) {
        // Account deletion may have already removed the stream. Its persisted
        // termination is complete; remove the lease instead of retrying forever.
        if (error?.status === 404 && error?.message === 'LIVE_NOT_FOUND') return true;
        console.error(`[SOCKET_DEBUG] [LIVE][DB] Internal termination failed for ref=${diagnosticRef(liveId)}:`, diagnosticError(error));
        return false;
    }
}

async function getLiveStreamMeta(liveId, accessToken) {
    try {
        const payload = await callUbuntuApi(`/v1/data/live/${encodeURIComponent(liveId)}`, { accessToken });
        const stream = payload?.stream;
        return {
            exists: Boolean(stream?.id),
            isActive: Boolean(stream?.is_active ?? stream?.isActive),
            hostUserId: (stream?.host_user_id || stream?.hostUserId || '').toString().trim(),
            totalReactions: Math.max(0, Number(stream?.total_reactions ?? stream?.totalReactions ?? 0) || 0),
            allowGuestRequests: Boolean(stream?.allow_guest_requests ?? stream?.allowGuestRequests)
        };
    } catch (error) {
        const accessFailure = classifyLiveLookupFailure(error);
        if (accessFailure === 'unavailable') {
            console.error(`[SOCKET_DEBUG] [LIVE][DB] Failed to read ref=${diagnosticRef(liveId)}:`, diagnosticError(error));
        }
        return { exists: false, isActive: false, hostUserId: '', totalReactions: 0,
            allowGuestRequests: false, accessFailure };
    }
}

async function getLiveModerationState(liveId, accessToken) {
    const payload = await callUbuntuApi(
        `/v1/data/live/${encodeURIComponent(liveId)}/moderation`,
        { accessToken }
    );
    return payload?.moderation || {};
}

function publicLiveModerationState(session, viewer) {
    return {
        chatPaused: Boolean(session?.moderation?.chatPaused),
        canModerate: Boolean(viewer?.isModerator),
        isModerator: Boolean(viewer?.isModerator),
        isHost: false,
        blockedWords: viewer?.isModerator
            ? Array.from(session?.moderation?.blockedWords || [])
            : []
    };
}

function emitLiveModerationState(liveId, session) {
    const moderationViewers = [];
    session?.viewers?.forEach((viewer, socketId) => {
        moderationViewers.push({
            socketId,
            userId: viewer.userId,
            name: viewer.name,
            avatarUrl: viewer.avatarUrl,
            isModerator: Boolean(viewer.isModerator)
        });
    });
    if (session?.host?.socketId) {
        liveIo.to(session.host.socketId).emit('live-moderation-state', {
            chatPaused: Boolean(session.moderation?.chatPaused),
            canModerate: true,
            isModerator: false,
            isHost: true,
            moderators: Array.from(session.moderation?.moderators || []),
            blockedWords: Array.from(session.moderation?.blockedWords || [])
        });
    }
    session?.viewers?.forEach((viewer, socketId) => {
        liveIo.to(socketId).emit(
            'live-moderation-state',
            publicLiveModerationState(session, viewer)
        );
        if (viewer.isModerator) {
            liveIo.to(socketId).emit('live-moderation-viewers', moderationViewers);
        }
    });
}

// Track active voice rooms and their participants
// room_id -> Map(socket_id -> { userId, name, avatarUrl, isCameraSharing,
// isScreenSharing, captionsEnabled })
const rooms = new Map();
const roomChatLedger = createRoomChatLedger();
const broadcastRoomChat = createRoomChatTransport(io, roomChatLedger, () => distributedAdapterRuntime.getStatus().connected);
const roomChatExpiry = setInterval(() => {
    for (const expired of roomChatLedger.expire()) broadcastRoomChat('chat-message-deleted', expired);
}, 1000);
roomChatExpiry.unref?.();
const voiceRoomCleanupTimers = new Map();
const voiceCaptionTranscripts = new Map();
const VOICE_CAPTION_MAX_TEXT_LENGTH = 500;
const VOICE_CAPTION_MAX_FINAL_SEGMENTS = 200;
const VOICE_CAPTION_PARTIAL_THROTTLE_MS = 120;

function normalizedCaptionText(value) {
    return String(value || '')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, VOICE_CAPTION_MAX_TEXT_LENGTH);
}

function appendFinalVoiceCaption(roomId, caption) {
    const current = voiceCaptionTranscripts.get(roomId) || [];
    current.push(caption);
    voiceCaptionTranscripts.set(
        roomId,
        current.slice(-VOICE_CAPTION_MAX_FINAL_SEGMENTS)
    );
}

function buildExtractiveVoiceSummary(roomId) {
    const transcript = voiceCaptionTranscripts.get(roomId) || [];
    if (transcript.length === 0) return null;

    const participantIds = new Set();
    const bullets = [];
    for (const caption of transcript) {
        participantIds.add(caption.userId);
        const prefix = `${caption.userName || 'Usuario'}: `;
        const text = normalizedCaptionText(caption.text);
        if (!text) continue;
        const bullet = `${prefix}${text}`.slice(0, VOICE_CAPTION_MAX_TEXT_LENGTH + prefix.length);
        if (bullets[bullets.length - 1] !== bullet) bullets.push(bullet);
    }

    return {
        segmentCount: transcript.length,
        participantCount: participantIds.size,
        bullets: bullets.slice(-6)
    };
}

function voiceParticipantCanPublish(roomId, socketId) {
    const role = String(rooms.get(roomId)?.get(socketId)?.role || 'speaker').toLowerCase();
    return role === 'speaker' || role === 'moderator';
}

function voiceParticipantCanControlCinema(roomId, socketId) {
    return rooms.get(roomId)?.get(socketId)?.canControlCinema === true;
}

function requireCinemaController(socket, rawRoomId, eventLabel) {
    const roomId = requireJoinedScope(socket, 'joinedRoomIds', rawRoomId, 'room', eventLabel);
    if (!roomId) return null;
    if (!voiceParticipantCanControlCinema(roomId, socket.id)) {
        console.warn(`[SOCKET_DEBUG] [AUTH] Rejected ${eventLabel} from non-owner ${socket.id} in roomRef=${diagnosticRef(roomId)}`);
        socket.emit('auth-error', { error: 'Only the room owner can control cinema mode' });
        return null;
    }
    return roomId;
}

async function refreshVoiceParticipantAccess(socket, roomId) {
    try {
        const payload = await callUbuntuApi(`/v1/data/voice/rooms/${encodeURIComponent(roomId)}`, {
            accessToken: getSocketAuthToken(socket)
        });
        const persisted = Array.isArray(payload?.participants)
            ? payload.participants.find((item) => String(item?.user_id || item?.id || '').toLowerCase() === String(socket.data.authUserId || '').toLowerCase())
            : null;
        const memoryParticipant = rooms.get(roomId)?.get(socket.id);
        if (memoryParticipant && persisted) {
            memoryParticipant.role = String(persisted.role || 'speaker').toLowerCase();
            memoryParticipant.isMuted = Boolean(persisted.is_muted);
            memoryParticipant.handRaised = Boolean(persisted.hand_raised);
            setVoiceParticipantSocketData(socket, memoryParticipant);
        }
    } catch (error) {
        console.warn(`[SOCKET_DEBUG] [STAGE] Could not refresh role for roomRef=${diagnosticRef(roomId)}:`, diagnosticError(error));
    }
    return voiceParticipantCanPublish(roomId, socket.id);
}
const VOICE_EMPTY_ROOM_GRACE_MS = clampInt(process.env.VOICE_EMPTY_ROOM_GRACE_MS, 15 * 1000, 3 * 1000, 5 * 60 * 1000);
const VOICE_PRESENCE_HEARTBEAT_MS = clampInt(process.env.VOICE_PRESENCE_HEARTBEAT_MS, 30 * 1000, 15 * 1000, 2 * 60 * 1000);

const voicePresenceHeartbeat = setInterval(() => {
    rooms.forEach((participants, roomId) => {
        participants.forEach((participant, socketId) => {
            const activeSocket = io.sockets.sockets.get(socketId);
            if (!activeSocket?.connected) return;
            void syncVoiceRoomJoin(activeSocket, roomId, participant);
        });
    });
}, VOICE_PRESENCE_HEARTBEAT_MS);
voicePresenceHeartbeat.unref?.();

function clearPendingVoiceRoomCleanup(roomId) {
    const timer = voiceRoomCleanupTimers.get(roomId);
    if (timer) {
        clearTimeout(timer);
        voiceRoomCleanupTimers.delete(roomId);
    }
}

async function finalizeEmptyVoiceRoom(roomId) {
    clearPendingVoiceRoomCleanup(roomId);
    rooms.delete(roomId);
    voiceCaptionTranscripts.delete(roomId);
    console.log(`[SOCKET_DEBUG] [ROOM] roomRef=${diagnosticRef(roomId)} finalized in signaling memory. Ubuntu API participant rows are synced through join/leave.`);
}

function scheduleEmptyVoiceRoomCleanup(roomId) {
    clearPendingVoiceRoomCleanup(roomId);

    const timer = setTimeout(async () => {
        voiceRoomCleanupTimers.delete(roomId);
        const participants = rooms.get(roomId);
        if (participants && participants.size > 0) {
            console.log(`[SOCKET_DEBUG] [ROOM] roomRef=${diagnosticRef(roomId)} recovered before cleanup timeout`);
            return;
        }

        await finalizeEmptyVoiceRoom(roomId);
    }, VOICE_EMPTY_ROOM_GRACE_MS);

    voiceRoomCleanupTimers.set(roomId, timer);
}

io.on('connection', (socket) => {
    installSocketPacketGuard(socket);
    socket.data.joinedRoomIds = new Set();
    console.log(`[IDENTITY] [VOICE] Socket connected: socketId=${socket.id} | userRef=${diagnosticRef(socket.data.authUserId)}`);

    safeSocketOn(socket, 'join-room', async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const roomId = data.roomId?.toString().trim();
        const userId = requireSocketUser(socket, data.userId);
        const name = normalizedDisplayName(data.name);
        const avatarUrl = normalizedAvatarUrl(data.avatarUrl);

        if (!roomId || roomId.length > 128 || !userId) return;

        let persistedAccess;
        try {
            persistedAccess = await persistVoiceRoomJoin(socket, roomId, {
                name,
                avatarUrl,
                isMuted: Boolean(data.isMuted),
                password: data.password?.toString().slice(0, 256) || ''
            });
        } catch (error) {
            console.warn(`[SOCKET_DEBUG] [ROOM] Rejected join for userRef=${diagnosticRef(userId)} in roomRef=${diagnosticRef(roomId)}:`, diagnosticError(error));
            socket.emit('room-join-error', {
                roomId,
                error: error.message || 'VOICE_ROOM_JOIN_DENIED'
            });
            return;
        }
        if (!socket.connected) return;

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
                console.log(`[SOCKET_DEBUG] [CLEANUP] Removing ghost for userRef=${diagnosticRef(userId)} (old SID: ${sid})`);
                currentRoomParticipants.delete(sid);
                socket.to(roomId).emit('user-left', sid);
            }
        });

        currentRoomParticipants.set(socket.id, {
            userId,
            name,
            avatarUrl,
            role: 'pending',
            isMuted: Boolean(data.isMuted),
            handRaised: false,
            isCameraSharing: false,
            isScreenSharing: false,
            captionsEnabled: false,
            canControlCinema: persistedAccess.canControlCinema === true
        });
        socket.data.joinedRoomIds.add(roomId);
        applyPersistedVoiceRoomAccess(socket, roomId, persistedAccess);

        let distributedRoomParticipants;
        try {
            distributedRoomParticipants = await fetchNamespaceScopeMembers(
                io,
                roomId,
                'voiceParticipant'
            );
        } catch (error) {
            console.error(`[SOCKET_DEBUG] [ROOM] Could not build distributed participant snapshot for roomRef=${diagnosticRef(roomId)}`, diagnosticError(error));
            currentRoomParticipants.delete(socket.id);
            socket.data.joinedRoomIds.delete(roomId);
            setVoiceParticipantSocketData(socket, null);
            socket.leave(roomId);
            await syncVoiceRoomLeave(socket, roomId);
            socket.emit('room-join-error', { roomId, error: 'VOICE_ROOM_PRESENCE_UNAVAILABLE' });
            return;
        }

        const replacedSockets = distributedRoomParticipants.filter((participant) => (
            participant.socketId !== socket.id && participant.userId === userId
        ));
        for (const replaced of replacedSockets) {
            io.to(replaced.socketId).emit('voice-session-replaced', {
                roomId,
                message: 'La sala continúa desde otra conexión.'
            });
            io.in(replaced.socketId).disconnectSockets(false);
        }
        const participants = distributedRoomParticipants.filter((participant) => (
            participant.socketId !== socket.id && participant.userId !== userId
        ));
        const participantCount = participants.length + 1;

        // Confirm membership before any slow database synchronization. Clients must
        // not publish media/chat events until this acknowledgement arrives.
        socket.emit('room-joined', {
            roomId,
            socketId: socket.id,
            participantCount
        });

        console.log(`[IDENTITY] [VOICE] JOIN-ROOM | socketId=${socket.id} | userRef=${diagnosticRef(userId)} | roomRef=${diagnosticRef(roomId)} | mismatch=${!!socket.data.hasIdentityMismatch}`);
        console.log(`[SOCKET_DEBUG] [ROOMS] Active room count: ${rooms.size}`);
        void setParticipantCount(roomId, participantCount);

        // Notify others in the room
        socket.to(roomId).emit('user-joined', {
            socketId: socket.id,
            userId: userId,
            name: name,
            avatarUrl: avatarUrl,
            captionsEnabled: false
        });

        // The adapter snapshot includes peers connected to every signaling replica.
        socket.emit('current-participants', participants);
    });

    // --- EVENTOS MODO CINE ---
    safeSocketOn(socket, "cinema-state", (data) => {
        const roomId = requireCinemaController(socket, data?.roomId, 'cinema-state');
        if (!roomId) return;
        io.to(roomId).emit("cinema-state", { ...data, roomId });
    });

    safeSocketOn(socket, "cinema-sync", (data) => {
        const roomId = requireCinemaController(socket, data?.roomId, 'cinema-sync');
        if (!roomId) return;
        io.to(roomId).emit("cinema-sync", { ...data, roomId });
    });

    safeSocketOn(socket, "cinema-control", (data) => {
        const roomId = requireCinemaController(socket, data?.roomId, 'cinema-control');
        if (!roomId) return;
        io.to(roomId).emit("cinema-control", { ...data, roomId });
    });

    safeSocketOn(socket, "resolve-link", async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const { url, requestId } = data;
        const roomId = requireCinemaController(socket, data.roomId, 'resolve-link');
        if (!roomId) return;
        if (!url || !requestId || requestId.toString().length > 128) {
            socket.emit("link-resolved", { requestId: requestId || "", error: "Missing url or requestId" });
            return;
        }
        console.log(`[SOCKET_DEBUG] [RESOLVE] Request from ${socket.id}`);

        const result = await resolveLink(url);

        socket.emit("link-resolved", {
            requestId: requestId,
            ...result
        });
    });
    // --------------------------

    safeSocketOn(socket, 'chat-message', (data, acknowledge) => {
        const respond = value => { if (typeof acknowledge === 'function') acknowledge(value); };
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'chat-message');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) { respond({ ok: false, error: 'ROOM_CHAT_NOT_JOINED' }); return; }
        const message = roomChatMessage(data, roomId, userId, rooms.get(roomId)?.get(socket.id)?.name);
        if (!message) { respond({ ok: false, error: 'ROOM_CHAT_INVALID_MESSAGE' }); return; }
        if (message.imageData && !roomChatLedger.has(roomId, message.id)) {
            const now = Date.now();
            if (socket.data.lastRoomPhotoAt && now - socket.data.lastRoomPhotoAt < 2000) {
                socket.emit('signal-error', { event: 'chat-message', error: 'PHOTO_RATE_LIMIT' }); respond({ ok: false, error: 'PHOTO_RATE_LIMIT' }); return;
            }
        }
        const accepted = roomChatLedger.accept(message);
        if (!accepted.ok) { respond(accepted); return; }
        if (!accepted.duplicate) {
            if (message.imageData) socket.data.lastRoomPhotoAt = Date.now();
            broadcastRoomChat('chat-message', { ...message, timestamp: accepted.timestamp, expiresAt: accepted.expiresAt });
        }
        respond(accepted);
    });

    safeSocketOn(socket, 'chat-message-delete', (data, acknowledge) => {
        const respond = value => { if (typeof acknowledge === 'function') acknowledge(value); };
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'chat-message-delete');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) { respond({ ok: false, error: 'ROOM_CHAT_NOT_JOINED' }); return; }
        const id = typeof data.id === 'string' && /^[\w-]{1,160}$/.test(data.id) ? data.id : '';
        if (!id) { respond({ ok: false, error: 'ROOM_CHAT_INVALID_MESSAGE' }); return; }
        const result = roomChatLedger.remove(roomId, userId, id);
        if (result.ok && !result.absent) broadcastRoomChat('chat-message-deleted', result);
        respond(result);
    });

    safeSocketOn(socket, 'reaction', (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'reaction');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;
        io.to(roomId).emit('reaction', {
            ...data,
            userId,
            reactionId: data?.reactionId || crypto.randomUUID(),
            timestamp: Number(data?.timestamp) || Date.now()
        });
    });

    safeSocketOn(socket, 'caption-consent', async (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'caption-consent');
        const userId = requireSocketUser(socket, data?.userId);
        if (!roomId || !userId) return;

        const enabled = data?.enabled === true;
        if (enabled && !(await refreshVoiceParticipantAccess(socket, roomId))) {
            socket.emit('caption-error', {
                roomId,
                error: 'VOICE_CAPTION_PUBLISH_DENIED'
            });
            return;
        }

        const participant = rooms.get(roomId)?.get(socket.id);
        if (!participant) {
            socket.emit('caption-error', { roomId, error: 'VOICE_CAPTION_NOT_JOINED' });
            return;
        }
        participant.captionsEnabled = enabled;
        setVoiceParticipantSocketData(socket, participant);
        if (!enabled) {
            socket.data.lastCaptionAt = 0;
        }

        io.to(roomId).emit('caption-consent', {
            roomId,
            socketId: socket.id,
            userId,
            enabled
        });
    });

    safeSocketOn(socket, 'live-caption', async (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'live-caption');
        const userId = requireSocketUser(socket, data?.userId);
        if (!roomId || !userId) return;

        const participant = rooms.get(roomId)?.get(socket.id);
        if (!participant?.captionsEnabled) {
            socket.emit('caption-error', { roomId, error: 'VOICE_CAPTION_CONSENT_REQUIRED' });
            return;
        }

        const isFinal = data?.isFinal === true;
        if (!voiceParticipantCanPublish(roomId, socket.id) ||
            (isFinal && !(await refreshVoiceParticipantAccess(socket, roomId)))) {
            participant.captionsEnabled = false;
            setVoiceParticipantSocketData(socket, participant);
            io.to(roomId).emit('caption-consent', {
                roomId,
                socketId: socket.id,
                userId,
                enabled: false
            });
            socket.emit('caption-error', {
                roomId,
                error: 'VOICE_CAPTION_PUBLISH_DENIED'
            });
            return;
        }

        const text = normalizedCaptionText(data?.text);
        if (!text) return;

        const now = Date.now();
        const lastCaptionAt = Number(socket.data.lastCaptionAt || 0);
        if (!isFinal && now - lastCaptionAt < VOICE_CAPTION_PARTIAL_THROTTLE_MS) return;
        socket.data.lastCaptionAt = now;

        const caption = {
            id: data?.captionId?.toString().trim() || crypto.randomUUID(),
            roomId,
            socketId: socket.id,
            userId,
            userName: participant.name || 'Usuario',
            text,
            isFinal,
            timestamp: now
        };
        if (isFinal) appendFinalVoiceCaption(roomId, caption);
        io.to(roomId).emit('live-caption', caption);
    });

    safeSocketOn(socket, 'voice-summary-request', (data) => {
        const roomId = requireJoinedScope(
            socket,
            'joinedRoomIds',
            data?.roomId,
            'room',
            'voice-summary-request'
        );
        if (!roomId) return;

        const requestId = data?.requestId?.toString().trim() || crypto.randomUUID();
        if (data?.accepted !== true) {
            socket.emit('voice-summary-error', {
                roomId,
                requestId,
                error: 'VOICE_SUMMARY_ACCEPTANCE_REQUIRED'
            });
            return;
        }

        const now = Date.now();
        const lastSummaryAt = Number(socket.data.lastVoiceSummaryAt || 0);
        if (now - lastSummaryAt < 3_000) {
            socket.emit('voice-summary-error', {
                roomId,
                requestId,
                error: 'VOICE_SUMMARY_RATE_LIMITED'
            });
            return;
        }
        socket.data.lastVoiceSummaryAt = now;

        const summary = buildExtractiveVoiceSummary(roomId);
        if (!summary) {
            socket.emit('voice-summary-error', {
                roomId,
                requestId,
                error: 'VOICE_SUMMARY_NOT_AVAILABLE'
            });
            return;
        }

        // Private response: a requested summary must not reveal the transcript
        // to anyone who did not explicitly ask for it.
        socket.emit('voice-summary-result', {
            roomId,
            requestId,
            kind: 'extractive',
            generatedAt: now,
            ...summary
        });
    });

    safeSocketOn(socket, 'speaking-status', async (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'speaking-status');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;
        if (Boolean(data?.isSpeaking) && !(await refreshVoiceParticipantAccess(socket, roomId))) {
            socket.emit('voice-stage-denied', { roomId, action: 'speaking', error: 'VOICE_STAGE_AUDIENCE_MUTED' });
            return;
        }
        socket.to(roomId).emit('speaking-status', { ...data, userId });
    });

    safeSocketOn(socket, 'mute-state', async (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'mute-state');
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;
        if (data?.isMuted === false && !(await refreshVoiceParticipantAccess(socket, roomId))) {
            socket.emit('voice-stage-denied', { roomId, action: 'unmute', error: 'VOICE_STAGE_AUDIENCE_MUTED' });
            return;
        }
        const participant = rooms.get(roomId)?.get(socket.id);
        if (participant) {
            participant.isMuted = Boolean(data?.isMuted);
            setVoiceParticipantSocketData(socket, participant);
        }
        socket.to(roomId).emit('mute-state', { ...data, userId });
    });

    safeSocketOn(socket, 'media-status', async (data) => {
        const roomId = requireJoinedScope(socket, 'joinedRoomIds', data?.roomId, 'room', 'media-status');
        const { isCameraSharing, isScreenSharing } = data;
        const userId = requireSocketUser(socket, data.userId);
        if (!roomId || !userId) return;
        if ((Boolean(isCameraSharing) || Boolean(isScreenSharing)) && !(await refreshVoiceParticipantAccess(socket, roomId))) {
            socket.emit('voice-stage-denied', { roomId, action: 'media', error: 'VOICE_STAGE_AUDIENCE_MEDIA_DENIED' });
            return;
        }

        // Persist on server for late joiners
        const currentRoomParticipants = rooms.get(roomId);
        if (currentRoomParticipants && currentRoomParticipants.has(socket.id)) {
            const p = currentRoomParticipants.get(socket.id);
            p.isCameraSharing = isCameraSharing;
            p.isScreenSharing = isScreenSharing;
            setVoiceParticipantSocketData(socket, p);
        }

        // Broadcast media state to everyone else in the room
        socket.to(roomId).emit('media-status', { ...data, userId }, socket.id);
    });

    // WebRTC Signaling: Forwarding Offer, Answer, and ICE Candidates

    safeSocketOn(socket, 'offer', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'offer');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed offer payload' });
            return;
        }
        const { target, offer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, io, 'joinedRoomIds', target, 'room', 'offer'))) return;
        console.log(`[SOCKET_DEBUG] [SIGNAL] Forwarding offer from ${socket.id} to ${target}; SDP omitted`);
        io.to(target).emit('offer', {
            sender: socket.id,
            offer: offer,
            userId: authenticatedUserId,
            name: name
        });
    });

    safeSocketOn(socket, 'answer', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'answer');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed answer payload' });
            return;
        }
        const { target, answer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, io, 'joinedRoomIds', target, 'room', 'answer'))) return;
        console.log(`[SOCKET_DEBUG] [SIGNAL] Forwarding answer from ${socket.id} to ${target}; SDP omitted`);
        io.to(target).emit('answer', {
            sender: socket.id,
            answer: answer,
            userId: authenticatedUserId,
            name: name
        });
    });

    safeSocketOn(socket, 'ice-candidate', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'ice-candidate');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed ICE candidate payload' });
            return;
        }
        const { target, candidate, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, io, 'joinedRoomIds', target, 'room', 'ICE'))) return;
        console.log(`[SOCKET_DEBUG] [SIGNAL] Forwarding ice-candidate from ${socket.id} to ${target}. Type: ${candidate.candidate ? candidate.candidate.split(' ')[7] : 'unknown'}`);
        io.to(target).emit('ice-candidate', {
            sender: socket.id,
            candidate: candidate,
            userId: authenticatedUserId,
            name: name
        });
    });

    safeSocketOn(socket, 'disconnecting', async () => {
        const joinedRoomIds = Array.from(socket.data.joinedRoomIds || []);

        for (const roomId of joinedRoomIds) {
            const participants = rooms.get(roomId);
            if (!participants) continue;

            const wasParticipant = participants.delete(socket.id);
            if (!wasParticipant) continue;

            console.log(`[SOCKET_DEBUG] [LEAVE] User disconnected from roomRef=${diagnosticRef(roomId)}. Population: ${participants.size}`);
            socket.to(roomId).emit('user-left', socket.id);
            await syncVoiceRoomLeave(socket, roomId);
            await setParticipantCount(roomId, participants.size);

            if (participants.size === 0) {
                console.log(`[SOCKET_DEBUG] [ROOM] roomRef=${diagnosticRef(roomId)} is empty. Waiting ${VOICE_EMPTY_ROOM_GRACE_MS}ms before cleanup`);
                scheduleEmptyVoiceRoomCleanup(roomId);
            }
        }

        socket.data.joinedRoomIds.clear();
        setVoiceParticipantSocketData(socket, null);
    });

    safeSocketOn(socket, 'disconnect', () => {
        console.log(`[SOCKET_DEBUG] User disconnected: ${socket.id}`);
    });
});

// -------------------- CALLS (WhatsApp-style) --------------------
// call_id -> Map(socket_id -> {userId, name, avatarUrl})
const calls = new Map();

callIo.on('connection', (socket) => {
    installSocketPacketGuard(socket);
    socket.data.joinedCallIds = new Set();
    const callAccessHeartbeat = setInterval(() => {
        if (socket.connected && socket.data.joinedCallIds.size) void refreshCallPermission(socket, true);
    }, 30000);
    callAccessHeartbeat.unref();
    console.log(`[IDENTITY] [CALL] Socket connected: socketId=${socket.id} | userRef=${diagnosticRef(socket.data.authUserId)}`);

    safeSocketOn(socket, 'join-call', async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const callId = data.callId?.toString().trim();
        const userId = requireSocketUser(socket, data.userId);
        const name = normalizedDisplayName(data.name);
        const avatarUrl = normalizedAvatarUrl(data.avatarUrl);

        if (!callId || callId.length > 128 || !userId) return;

        const callAccess = await authorizeCallJoin({
            socket, callId, userId,
            lookupAccess: id => callUbuntuApi(`/v1/data/calls/${encodeURIComponent(id)}/access`, {
                method: 'POST', accessToken: getSocketAuthToken(socket), body: {}, timeoutMs: 4000
            })
        });
        if (!callAccess.allowed) {
            socket.emit('call-join-error', { callId, error: callAccess.error });
            return;
        }
        try {
        await socket.join(callId);
        if (!socket.connected) return;

        if (!calls.has(callId)) {
            calls.set(callId, new Map());
        }

        const currentCallParticipants = calls.get(callId);

        // Ghost prevention: same userId, old socket -> remove
        currentCallParticipants.forEach((val, sid) => {
            if (val.userId === userId) {
                console.log(`[SOCKET_DEBUG] [CALL][CLEANUP] Removing ghost for userRef=${diagnosticRef(userId)} (old SID: ${sid})`);
                currentCallParticipants.delete(sid);
                socket.to(callId).emit('user-left', sid);
            }
        });

        const currentParticipant = {
            userId,
            name,
            avatarUrl,
            videoEnabled: Boolean(data.videoEnabled)
        };
        currentCallParticipants.set(socket.id, currentParticipant);
        socket.data.joinedCallIds.add(callId);
        setCallParticipantSocketData(socket, currentParticipant);

        let distributedCallParticipants;
        try {
            distributedCallParticipants = await fetchNamespaceScopeMembers(
                callIo,
                callId,
                'callParticipant'
            );
        } catch (error) {
            console.error(`[SOCKET_DEBUG] [CALL] Could not build distributed participant snapshot for callRef=${diagnosticRef(callId)}`, diagnosticError(error));
            currentCallParticipants.delete(socket.id);
            socket.data.joinedCallIds.delete(callId);
            setCallParticipantSocketData(socket, null);
            socket.leave(callId);
            socket.emit('call-join-error', { callId, error: 'CALL_PRESENCE_UNAVAILABLE' });
            return;
        }

        const replacedSockets = distributedCallParticipants.filter((participant) => (
            participant.socketId !== socket.id && participant.userId === userId
        ));
        for (const replaced of replacedSockets) {
            callIo.to(replaced.socketId).emit('call-session-replaced', {
                callId,
                message: 'La llamada continúa desde otra conexión.'
            });
            callIo.in(replaced.socketId).disconnectSockets(false);
        }
        const participants = distributedCallParticipants.filter((participant) => (
            participant.socketId !== socket.id && participant.userId !== userId
        ));
        const participantCount = participants.length + 1;

        socket.emit('call-joined', {
            callId,
            socketId: socket.id,
            participantCount
        });

        console.log(`[IDENTITY] [CALL] JOIN-CALL | socketId=${socket.id} | userRef=${diagnosticRef(userId)} | callRef=${diagnosticRef(callId)} | population=${participantCount}`);

        socket.to(callId).emit('user-joined', {
            socketId: socket.id,
            userId,
            name,
            avatarUrl,
            videoEnabled: Boolean(data.videoEnabled)
        });

        socket.emit('current-participants', participants);
        } finally { callAccess.release(); }
    });

    safeSocketOn(socket, 'reaction', (data) => {
        const callId = requireJoinedScope(socket, 'joinedCallIds', data?.callId, 'call', 'reaction');
        const userId = requireSocketUser(socket, data.userId);
        if (!callId || !userId) return;
        socket.to(callId).emit('reaction', {
            ...data,
            userId,
            reactionId: data?.reactionId || crypto.randomUUID(),
            timestamp: Number(data?.timestamp) || Date.now()
        });
    });

    safeSocketOn(socket, 'mute-state', (data) => {
        const callId = requireJoinedScope(socket, 'joinedCallIds', data?.callId, 'call', 'mute-state');
        const userId = requireSocketUser(socket, data.userId);
        if (!callId || !userId) return;
        socket.to(callId).emit('mute-state', { ...data, userId });
    });

    safeSocketOn(socket, 'video-state', (data) => {
        const callId = requireJoinedScope(socket, 'joinedCallIds', data?.callId, 'call', 'video-state');
        const userId = requireSocketUser(socket, data?.userId);
        if (!callId || !userId) return;
        const participant = calls.get(callId)?.get(socket.id);
        const isVideoEnabled = Boolean(data?.isVideoEnabled);
        if (participant) {
            participant.videoEnabled = isVideoEnabled;
            setCallParticipantSocketData(socket, participant);
        }
        socket.to(callId).emit('video-state', {
            userId,
            socketId: socket.id,
            isVideoEnabled
        });
    });

    // WebRTC signaling (scoped to /call namespace)
    safeSocketOn(socket, 'offer', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'offer');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed offer payload' });
            return;
        }
        const { target, offer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, callIo, 'joinedCallIds', target, 'call', 'offer'))) return;
        callIo.to(target).emit('offer', {
            sender: socket.id,
            offer,
            userId: authenticatedUserId,
            name
        });
    });

    safeSocketOn(socket, 'answer', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'answer');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed answer payload' });
            return;
        }
        const { target, answer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, callIo, 'joinedCallIds', target, 'call', 'answer'))) return;
        callIo.to(target).emit('answer', {
            sender: socket.id,
            answer,
            userId: authenticatedUserId,
            name
        });
    });

    safeSocketOn(socket, 'ice-candidate', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'ice-candidate');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed ICE candidate payload' });
            return;
        }
        const { target, candidate, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, callIo, 'joinedCallIds', target, 'call', 'ICE'))) return;
        callIo.to(target).emit('ice-candidate', {
            sender: socket.id,
            candidate,
            userId: authenticatedUserId,
            name
        });
    });

    safeSocketOn(socket, 'disconnecting', () => {
        const joinedCallIds = Array.from(socket.data.joinedCallIds || []);

        for (const callId of joinedCallIds) {
            const participants = calls.get(callId);
            if (!participants) continue;

            const wasParticipant = participants.delete(socket.id);
            if (!wasParticipant) continue;

            console.log(`[SOCKET_DEBUG] [CALL][LEAVE] Socket left callRef=${diagnosticRef(callId)}. Population: ${participants.size}`);
            socket.to(callId).emit('user-left', socket.id);

            if (participants.size === 0) {
                calls.delete(callId);
            }
        }

        socket.data.joinedCallIds.clear();
        setCallParticipantSocketData(socket, null);
    });

    safeSocketOn(socket, 'disconnect', () => {
        clearInterval(callAccessHeartbeat);
        callPermissionCache.delete(socket);
        console.log(`[SOCKET_DEBUG] [CALL] User disconnected: ${socket.id}`);
    });
});

// -------------------- LIVES (namespace reserved) --------------------
// live_id -> {
//   host: {socketId,userId,name,avatarUrl} | null,
//   viewers: Map(socketId -> {...}),
//   state: { source, isPaused, videoEnabled, micEnabled },
//   moderation: { chatPaused, moderators: Set<userId> }
// }
const lives = new Map();
const liveEndTimers = new Map();
const liveBattleTimers = new Map();
const HOST_DISCONNECT_GRACE_MS = 90 * 1000;
let liveLeaseHeartbeatTimer = null;
let liveRecoveryMonitorTimer = null;

function currentLiveStateStore() {
    return distributedAdapterRuntime.liveState || createNoopDistributedLiveState();
}

function liveLeaseOwner(socketId) {
    return `${signalingInstanceId}:${String(socketId || '').trim()}`;
}

async function persistLiveSession(liveId, session) {
    const store = currentLiveStateStore();
    if (!store.enabled || !session) return false;
    try {
        return await store.save(liveId, session);
    } catch (error) {
        console.error(`[SIGNALING_REDIS] Failed to persist liveRef=${diagnosticRef(liveId)}`, diagnosticError(error));
        return false;
    }
}

function persistLiveSessionInBackground(liveId, session) {
    void persistLiveSession(liveId, session);
}

async function restoreLiveSession(liveId) {
    const store = currentLiveStateStore();
    if (!store.enabled) return null;
    try {
        const snapshot = await store.load(liveId);
        const session = hydrateLiveSession(snapshot);
        if (!session) return null;
        lives.set(liveId, session);
        if (session.battle?.status === 'active') startLiveBattleTimer(liveId, session);
        console.log(`[SIGNALING_REDIS] Restored liveRef=${diagnosticRef(liveId)} without credentials or stale socket ids`);
        return session;
    } catch (error) {
        console.error(`[SIGNALING_REDIS] Failed to restore liveRef=${diagnosticRef(liveId)}`, diagnosticError(error));
        return null;
    }
}

function ensureLiveInteractionState(session) {
    if (!(session.guestRequests instanceof Map)) session.guestRequests = new Map();
    if (!(session.guests instanceof Map)) session.guests = new Map();
    if (!(session.recoverableGuestRequests instanceof Map)) session.recoverableGuestRequests = new Map();
    if (!(session.recoverableGuests instanceof Map)) session.recoverableGuests = new Map();
    if (!session.battle || typeof session.battle !== 'object') {
        session.battle = {
            status: 'none',
            opponentSocketId: '',
            opponentUserId: '',
            opponentName: '',
            opponentAvatarUrl: '',
            hostScore: 0,
            opponentScore: 0,
            durationSeconds: 180,
            startedAtMs: 0,
            message: ''
        };
    }
}

function publicLiveGuest(viewer, socketId) {
    return {
        socketId,
        userId: String(viewer?.userId || '').trim(),
        name: String(viewer?.name || 'Usuario').trim() || 'Usuario',
        avatarUrl: String(viewer?.avatarUrl || '').trim(),
        videoEnabled: viewer?.videoEnabled !== false
    };
}

function emitLiveGuestState(liveId, session) {
    ensureLiveInteractionState(session);
    const requests = Array.from(session.guestRequests.entries()).map(([socketId, viewer]) => ({
        ...publicLiveGuest(viewer, socketId),
        requestedAtMs: Number(viewer?.requestedAtMs || Date.now())
    }));
    const guests = Array.from(session.guests.entries()).map(([socketId, viewer]) =>
        publicLiveGuest(viewer, socketId)
    );

    if (session.host?.socketId) {
        liveIo.to(session.host.socketId).emit('live-guest-requests', requests);
    }
    liveIo.to(liveId).emit('live-guests', guests);
}

function clearLiveBattleTimer(liveId) {
    const timer = liveBattleTimers.get(liveId);
    if (timer) clearTimeout(timer);
    liveBattleTimers.delete(liveId);
}

function emitLiveBattleState(liveId, session) {
    ensureLiveInteractionState(session);
    liveIo.to(liveId).emit('live-battle-state', session.battle);
}

function startLiveBattleTimer(liveId, session) {
    clearLiveBattleTimer(liveId);
    const durationMs = Math.max(30, Math.min(600, Number(session.battle.durationSeconds) || 180)) * 1000;
    const startedAtMs = Math.max(0, Number(session.battle.startedAtMs || 0) || 0);
    const remainingMs = startedAtMs > 0
        ? Math.max(0, (startedAtMs + durationMs) - Date.now())
        : durationMs;
    const timer = setTimeout(() => {
        liveBattleTimers.delete(liveId);
        const current = lives.get(liveId);
        if (!current || current.battle?.status !== 'active') return;
        current.battle = {
            ...current.battle,
            status: 'finished',
            message: current.battle.hostScore === current.battle.opponentScore
                ? 'Empate'
                : current.battle.hostScore > current.battle.opponentScore
                    ? 'Ganó el anfitrión'
                    : `Ganó ${current.battle.opponentName || 'el invitado'}`
        };
        emitLiveBattleState(liveId, current);
        persistLiveSessionInBackground(liveId, current);
    }, remainingMs);
    timer.unref?.();
    liveBattleTimers.set(liveId, timer);
}

function clearPendingLiveTermination(liveId) {
    const timer = liveEndTimers.get(liveId);
    if (timer) {
        clearTimeout(timer);
        liveEndTimers.delete(liveId);
    }
}

async function finalizeLiveTermination(liveId, reason = 'ended') {
    clearPendingLiveTermination(liveId);
    clearLiveBattleTimer(liveId);

    const session = lives.get(liveId);
    const accessToken = session?.host?.authToken || session?.hostAuthToken || '';
    const terminated = await endLiveStream(liveId, accessToken, session?.reactionCount || 0);
    if (!terminated) {
        console.error(`[SOCKET_DEBUG] [LIVE][END] ${reason} could not be committed for liveRef=${diagnosticRef(liveId)}`);
        return false;
    }

    console.log(`[SOCKET_DEBUG] [LIVE][END] ${reason}: liveRef=${diagnosticRef(liveId)}`);
    liveIo.to(liveId).emit('live-ended');
    lives.delete(liveId);
    try {
        await currentLiveStateStore().remove(liveId);
    } catch (error) {
        console.error(`[SIGNALING_REDIS] Failed to remove ended liveRef=${diagnosticRef(liveId)}`, diagnosticError(error));
    }
    return true;
}

function scheduleHostTerminationGrace(liveId, hostSnapshot, requestedDeadlineMs = Date.now() + HOST_DISCONNECT_GRACE_MS) {
    clearPendingLiveTermination(liveId);

    const scheduledSession = lives.get(liveId);
    if (scheduledSession) {
        scheduledSession.pendingHostSnapshot = hostSnapshot;
        persistLiveSessionInBackground(liveId, scheduledSession);
    }
    const deadlineMs = Math.max(Date.now(), Number(requestedDeadlineMs) || Date.now());
    const store = currentLiveStateStore();
    if (store.enabled) {
        void store.scheduleHostDeadline(liveId, deadlineMs).catch((error) => {
            console.error(`[SIGNALING_REDIS] Failed to schedule host deadline for liveRef=${diagnosticRef(liveId)}`, diagnosticError(error));
        });
    }

    const timer = setTimeout(async () => {
        liveEndTimers.delete(liveId);
        const session = lives.get(liveId);
        if (!session) return;

        // A reconnect handler can already be validating ownership/moderation
        // when the old timer callback has entered the event queue. Give that
        // authenticated reservation a fresh bounded grace window.
        if (session.hostJoinReservationToken) {
            scheduleHostTerminationGrace(liveId, hostSnapshot);
            return;
        }

        if (session.host?.userId === hostSnapshot.userId) {
            session.pendingHostSnapshot = null;
            void store.clearHostDeadline(liveId).catch(() => {});
            console.log(`[SOCKET_DEBUG] [LIVE][HOST] userRef=${diagnosticRef(hostSnapshot.userId)} reconnected to liveRef=${diagnosticRef(liveId)} before grace timeout`);
            return;
        }

        console.log(`[SOCKET_DEBUG] [LIVE][HOST] userRef=${diagnosticRef(hostSnapshot.userId)} did not reconnect within ${HOST_DISCONNECT_GRACE_MS}ms. Ending liveRef=${diagnosticRef(liveId)}`
        );
        await finalizeLiveTermination(liveId, 'host-disconnect-timeout');
    }, Math.max(0, deadlineMs - Date.now()));
    timer.unref?.();

    liveEndTimers.set(liveId, timer);
}

async function monitorRecoverableLives() {
    const store = currentLiveStateStore();
    if (!store.enabled) return;
    try {
        const now = Date.now();
        const activeIds = await store.listActiveIds();
        for (const liveId of activeIds.slice(0, 1_000)) {
            if (await store.hasHostLease(liveId)) {
                await store.clearHostDeadline(liveId);
                continue;
            }
            const deadline = await store.hostDeadline(liveId);
            if (deadline == null) {
                await store.scheduleHostDeadline(liveId, now + HOST_DISCONNECT_GRACE_MS);
                continue;
            }
            if (deadline > now) continue;
            const lockToken = await store.acquireTerminationLock(liveId);
            if (!lockToken) continue;
            try {
                if (await store.hasHostLease(liveId)) {
                    await store.clearHostDeadline(liveId);
                    continue;
                }
                if (!lives.has(liveId)) await restoreLiveSession(liveId);
                await finalizeLiveTermination(liveId, 'distributed-host-lease-timeout');
            } finally {
                await store.releaseTerminationLock(liveId, lockToken).catch(() => {});
            }
        }
    } catch (error) {
        console.error('[SIGNALING_REDIS] Live recovery monitor failed', diagnosticError(error));
    }
}

function startLiveRecoveryWorkers() {
    const store = currentLiveStateStore();
    if (!store.enabled || liveLeaseHeartbeatTimer || liveRecoveryMonitorTimer) return;
    const leaseSeconds = Number(store.config?.hostLeaseSeconds || 45);
    const heartbeatMs = Math.max(5_000, Math.min(30_000, Math.floor((leaseSeconds * 1_000) / 3)));
    liveLeaseHeartbeatTimer = setInterval(() => {
        for (const [liveId, session] of lives.entries()) {
            if (!session.host?.socketId) continue;
            void store.renewHostLease(liveId, liveLeaseOwner(session.host.socketId)).catch((error) => {
                console.error(`[SIGNALING_REDIS] Host lease renewal failed for liveRef=${diagnosticRef(liveId)}`, diagnosticError(error));
            });
        }
    }, heartbeatMs);
    liveLeaseHeartbeatTimer.unref?.();
    liveRecoveryMonitorTimer = setInterval(() => void monitorRecoverableLives(), 5_000);
    liveRecoveryMonitorTimer.unref?.();
    void monitorRecoverableLives();
}

liveIo.on('connection', (socket) => {
    installSocketPacketGuard(socket);
    socket.data.joinedLiveIds = new Set();
    socket.data.liveRole = null;
    socket.data.liveId = null;

    console.log(`[SOCKET_DEBUG] [LIVE] Socket connected: ${socket.id} userRef=${diagnosticRef(socket.data.authUserId)}`);

    safeSocketOn(socket, 'join-live', async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = data.liveId?.toString().trim();
        const userId = requireSocketUser(socket, data.userId);
        const name = normalizedDisplayName(data.name);
        const avatarUrl = normalizedAvatarUrl(data.avatarUrl);
        const role = data.role?.toString().trim().toLowerCase(); // "host" | "viewer"

        if (!liveId || liveId.length > 128 || !userId || (role !== 'host' && role !== 'viewer')) return;

        const restoredSession = !lives.has(liveId)
            ? await restoreLiveSession(liveId)
            : null;
        if (restoredSession && role !== 'host') {
            const restoredMeta = await getLiveStreamMeta(liveId, getSocketAuthToken(socket));
            if (!restoredMeta.exists || !restoredMeta.isActive) {
                lives.delete(liveId);
                await currentLiveStateStore().remove(liveId).catch(() => {});
                socket.emit('live-ended');
                return;
            }
            restoredSession.reactionCount = Math.max(
                Number(restoredSession.reactionCount || 0),
                Number(restoredMeta.totalReactions || 0)
            );
        }

        // Reserve a pending reconnect synchronously, before any API await. A
        // 90-second grace timer must not end the stream while the same,
        // authenticated host is still being revalidated.
        let reservedHostGrace = null;
        if (role === 'host') {
            const existingSession = lives.get(liveId);
            const pendingHost = existingSession?.pendingHostSnapshot;
            if (
                existingSession &&
                !existingSession.host &&
                pendingHost?.userId === userId &&
                liveEndTimers.has(liveId)
            ) {
                const reservationToken = crypto.randomUUID();
                existingSession.hostJoinReservationToken = reservationToken;
                reservedHostGrace = {
                    session: existingSession,
                    snapshot: pendingHost,
                    token: reservationToken
                };
                clearPendingLiveTermination(liveId);
            }
        }
        const restoreReservedHostGrace = () => {
            const reservation = reservedHostGrace;
            reservedHostGrace = null;
            if (!reservation) return;
            const currentSession = lives.get(liveId);
            if (currentSession !== reservation.session) return;
            if (currentSession.hostJoinReservationToken === reservation.token) {
                currentSession.hostJoinReservationToken = null;
            }
            if (!currentSession.host) {
                scheduleHostTerminationGrace(liveId, reservation.snapshot);
            }
        };

        if (!lives.has(liveId)) {
            const liveMeta = await getLiveStreamMeta(liveId, getSocketAuthToken(socket));
            if (!liveMeta.exists) {
                console.warn(`[SOCKET_DEBUG] [LIVE][JOIN] Rejected ${role} userRef=${diagnosticRef(userId)} because liveRef=${diagnosticRef(liveId)} does not exist`);
                socket.emit('live-ended');
                return;
            }

            if (role === 'host' && liveMeta.hostUserId && liveMeta.hostUserId !== userId) {
                console.warn(`[SOCKET_DEBUG] [LIVE][JOIN] Rejected host userRef=${diagnosticRef(userId)} for liveRef=${diagnosticRef(liveId)} because ownerRef=${diagnosticRef(liveMeta.hostUserId)}`
                );
                socket.emit('live-ended');
                return;
            }

            if (role !== 'host' && !liveMeta.isActive) {
                console.warn(`[SOCKET_DEBUG] [LIVE][JOIN] Rejected ${role} userRef=${diagnosticRef(userId)} for inactive liveRef=${diagnosticRef(liveId)}`);
                socket.emit('live-ended');
                return;
            }

            if (role !== 'host') {
                console.warn(`[SOCKET_DEBUG] [LIVE][JOIN] Rejected viewer userRef=${diagnosticRef(userId)} for liveRef=${diagnosticRef(liveId)} because there is no active host session`
                );
                socket.emit('live-ended');
                return;
            }

            lives.set(liveId, {
                host: null,
                hostAuthToken: '',
                viewers: new Map(),
                reactionCount: liveMeta.totalReactions,
                announcedGiftTransactionIds: new Set(),
                state: {
                    source: 'camera',
                    isPaused: false,
                    videoEnabled: true,
                    micEnabled: true
                },
                moderation: {
                    chatPaused: false,
                    moderators: new Set(),
                    blockedWords: []
                },
                guestRequests: new Map(),
                guests: new Map(),
                battle: null
            });
        }

        // Revalidate ownership even when an in-memory live session already exists.
        // Otherwise any authenticated viewer could reconnect with role=host and
        // replace the legitimate host for an active broadcast.
        if (role === 'host') {
            const liveMeta = await getLiveStreamMeta(liveId, getSocketAuthToken(socket));
            if (!liveMeta.exists || (liveMeta.hostUserId && liveMeta.hostUserId !== userId)) {
                console.warn(
                    `[SOCKET_DEBUG] [LIVE][JOIN] Rejected host userRef=${diagnosticRef(userId)} for liveRef=${diagnosticRef(liveId)} because ownerRef=${diagnosticRef(liveMeta.hostUserId)}`
                );
                restoreReservedHostGrace();
                socket.emit('live-ended');
                return;
            }
        }

        const sessionReference = lives.get(liveId);
        if (!sessionReference) {
            restoreReservedHostGrace();
            socket.emit('live-ended');
            return;
        }
        // Existing in-memory rooms must not bypass the API's visibility and
        // ban checks. GET /live/:id uses the viewer's own bearer token.
        if (role === 'viewer') {
            const viewerMeta = await getLiveStreamMeta(liveId, getSocketAuthToken(socket));
            if (viewerMeta.accessFailure === 'banned') {
                socket.emit('live-moderated', {
                    action: 'ban',
                    message: 'El equipo de moderación restringió tu acceso a este directo.'
                });
                return;
            }
            if (viewerMeta.accessFailure === 'unavailable') {
                socket.emit('live-moderation-error', { error: 'No se pudo validar tu acceso al directo. Intenta de nuevo.' });
                return;
            }
            if (!viewerMeta.exists || !viewerMeta.isActive) {
                socket.emit('live-ended');
                return;
            }
        }
        ensureLiveInteractionState(sessionReference);
        let requestedModeration;
        try {
            requestedModeration = await getLiveModerationState(liveId, getSocketAuthToken(socket));
        } catch (error) {
            console.error(`[SOCKET_DEBUG] [LIVE][MODERATION] Could not authorize userRef=${diagnosticRef(userId)} for liveRef=${diagnosticRef(liveId)}:`, diagnosticError(error));
            restoreReservedHostGrace();
            socket.emit('live-moderation-error', { error: 'No se pudo validar tu acceso al directo.' });
            return;
        }
        const session = lives.get(liveId);
        if (!socket.connected || !session || session !== sessionReference) {
            restoreReservedHostGrace();
            socket.emit('live-ended');
            return;
        }
        if (role !== 'host' && requestedModeration?.is_banned) {
            socket.emit('live-moderated', {
                action: 'ban',
                message: 'El equipo de moderación restringió tu acceso a este directo.'
            });
            return;
        }

        // A Redis lease is the distributed source of truth for the active
        // host. Never mutate the local session until the process either owns
        // that lease or Redis is intentionally disabled for single-instance
        // development. A local device hand-off may replace its own prior
        // socket atomically; a host in another signaling process cannot be
        // overwritten by a late join/heartbeat.
        if (role === 'host') {
            const store = currentLiveStateStore();
            const previousHost = session.host;
            const previousSocket = previousHost?.socketId
                ? liveIo.sockets.get(previousHost.socketId)
                : null;
            const replaceOwnerId = previousSocket && previousHost?.userId === userId
                ? liveLeaseOwner(previousHost.socketId)
                : '';
            if (store.enabled) {
                let claimed = false;
                try {
                    claimed = await store.renewHostLease(
                        liveId,
                        liveLeaseOwner(socket.id),
                        replaceOwnerId
                    );
                } catch (error) {
                    console.error(
                        `[SIGNALING_REDIS] Could not claim host lease for liveRef=${diagnosticRef(liveId)}`,
                        diagnosticError(error)
                    );
                }
                if (!claimed) {
                    restoreReservedHostGrace();
                    socket.emit('live-host-conflict', {
                        liveId,
                        message: 'Este directo ya tiene un anfitrión activo. Espera unos segundos e inténtalo de nuevo.'
                    });
                    return;
                }
            }
        }

        socket.join(liveId);
        socket.data.joinedLiveIds.add(liveId);
        socket.data.liveRole = role;
        socket.data.liveId = liveId;
        socket.data.liveParticipant = {
            liveId,
            userId,
            name,
            avatarUrl,
            role
        };

        if (role === 'host') {
            clearPendingLiveTermination(liveId);
            const previousHost = session.host;
            if (previousHost?.socketId && previousHost.socketId !== socket.id) {
                liveIo.to(liveId).emit('host-disconnected', {
                    socketId: previousHost.socketId,
                    userId: previousHost.userId,
                    replaced: true
                });
                const previousSocket = liveIo.sockets.get(previousHost.socketId);
                if (previousSocket) {
                    previousSocket.emit('live-session-replaced', {
                        liveId,
                        message: 'Este directo continúa desde otro dispositivo.'
                    });
                    previousSocket.data.joinedLiveIds?.delete(liveId);
                    if (previousSocket.data.liveId === liveId) {
                        previousSocket.data.liveId = null;
                        previousSocket.data.liveRole = null;
                    }
                    previousSocket.leave(liveId);
                }
            }
            if (
                reservedHostGrace &&
                session.hostJoinReservationToken === reservedHostGrace.token
            ) {
                session.hostJoinReservationToken = null;
            }
            reservedHostGrace = null;
            session.pendingHostSnapshot = null;
            session.host = { socketId: socket.id, userId, name, avatarUrl, authToken: getSocketAuthToken(socket) };
            session.hostAuthToken = getSocketAuthToken(socket);
            session.state = {
                source: (data.source || session.state?.source || 'camera').toString().trim().toLowerCase() === 'screen' ? 'screen' : 'camera',
                isPaused: Boolean(data.isPaused),
                videoEnabled: data.videoEnabled !== false,
                micEnabled: data.micEnabled !== false
            };
            session.moderation = {
                chatPaused: Boolean(requestedModeration?.chat_paused),
                moderators: new Set(
                    Array.isArray(requestedModeration?.moderators)
                        ? requestedModeration.moderators.map((entry) => String(entry?.user_id || '').trim()).filter(Boolean)
                        : []
                ),
                blockedWords: Array.isArray(requestedModeration?.blocked_words)
                    ? requestedModeration.blocked_words.map((word) => String(word || '').trim()).filter(Boolean)
                    : []
            };
            await currentLiveStateStore().clearHostDeadline(liveId).catch(() => {});
            await persistLiveSession(liveId, session);
        console.log(`[SOCKET_DEBUG] [LIVE][HOST] userRef=${diagnosticRef(userId)} is hosting liveRef=${diagnosticRef(liveId)}`);

            // Send existing viewers to host
            const currentViewers = [];
            session.viewers.forEach((v, sid) => {
                currentViewers.push({ socketId: sid, userId: v.userId, name: v.name, avatarUrl: v.avatarUrl });
            });
            socket.emit('current-viewers', currentViewers);
            socket.emit(
                'live-guest-requests',
                Array.from(session.guestRequests.entries()).map(([socketId, viewer]) => ({
                    ...publicLiveGuest(viewer, socketId),
                    requestedAtMs: Number(viewer?.requestedAtMs || Date.now())
                }))
            );

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
            socket.emit('reaction-count', { liveId, reactionCount: session.reactionCount });
            await setLiveStreamActive(liveId, session.hostAuthToken);
            await setLiveViewerCount(liveId, viewerCount, session.hostAuthToken);
            emitLiveModerationState(liveId, session);
            emitLiveGuestState(liveId, session);
            emitLiveBattleState(liveId, session);
            return;
        }

        // Viewer join
        session.viewers.set(socket.id, {
            userId,
            name,
            avatarUrl,
            authToken: getSocketAuthToken(socket),
            isModerator: Boolean(requestedModeration?.is_moderator)
        });
        const recoveredRequest = session.recoverableGuestRequests.get(userId);
        if (recoveredRequest) {
            session.recoverableGuestRequests.delete(userId);
            session.guestRequests.set(socket.id, {
                ...session.viewers.get(socket.id),
                requestedAtMs: Number(recoveredRequest.requestedAtMs || Date.now())
            });
        }
        if (session.recoverableGuests.has(userId)) {
            const recoveredGuest = session.recoverableGuests.get(userId);
            session.recoverableGuests.delete(userId);
            session.guests.set(socket.id, {
                ...session.viewers.get(socket.id),
                ...(typeof recoveredGuest?.videoEnabled === 'boolean'
                    ? { videoEnabled: recoveredGuest.videoEnabled } : {})
            });
        }
        if (session.battle?.opponentUserId === userId) {
            session.battle.opponentSocketId = socket.id;
        }
        persistLiveSessionInBackground(liveId, session);
        console.log(`[SOCKET_DEBUG] [LIVE][VIEWER] userRef=${diagnosticRef(userId)} joined liveRef=${diagnosticRef(liveId)}. Viewers: ${session.viewers.size}`);

        // Notify host and provide host info to viewer
        if (session.host?.socketId) {
            // Every participant should see the live join activity. The Android
            // viewer only updates the UI; only the host establishes a media peer.
            liveIo.to(liveId).emit('viewer-joined', {
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
            socket.emit(
                'live-guests',
                Array.from(session.guests.entries()).map(([socketId, viewer]) => publicLiveGuest(viewer, socketId))
            );
            socket.emit('live-battle-state', session.battle);
        }

        const viewerCount = session.viewers.size;
        liveIo.to(liveId).emit('viewer-count', { liveId, viewerCount });
        socket.emit('reaction-count', { liveId, reactionCount: session.reactionCount });
        await setLiveViewerCount(liveId, viewerCount, session.hostAuthToken);
        emitLiveModerationState(liveId, session);
    });

    safeSocketOn(socket, 'end-live', async (data, acknowledge) => {
        const requestedLiveId = data?.liveId?.toString().trim() || '';
        const denyEnd = (reason) => {
            socket.emit('end-live-denied', { liveId: requestedLiveId, reason });
            if (typeof acknowledge === 'function') acknowledge({ ok: false, reason });
        };
        const liveId = requireJoinedScope(socket, 'joinedLiveIds', data?.liveId, 'live', 'end-live');
        if (!liveId) {
            denyEnd('NOT_JOINED');
            return;
        }

        const session = lives.get(liveId);
        if (!session) {
            denyEnd('LIVE_NOT_FOUND');
            return;
        }

        // Only host can end
        if (session.host?.socketId !== socket.id) {
            denyEnd('HOST_SOCKET_REPLACED');
            return;
        }

        socket.emit('end-live-confirmed', { liveId });
        if (typeof acknowledge === 'function') acknowledge({ ok: true, liveId });
        await finalizeLiveTermination(liveId, 'host-ended');
    });

    // Free hearts are ephemeral UI events; this process owns the room aggregate.
    safeSocketOn(socket, 'send-reaction', (data) => {
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data?.liveId || socket.data.liveId,
            'live',
            'send-reaction'
        );
        if (!liveId) return;

        const userId = requireSocketUser(socket, data?.userId);
        if (!userId) return;

        const session = lives.get(liveId);
        if (!session) return;
        if (!consumeRateLimitToken('live-reaction', `${liveId}:${userId}`, 12, 2_000)) return;

        const effect = liveReactionPolicy(data?.emoji);
        if (!effect) return;
        session.reactionCount = Math.max(0, Number(session.reactionCount || 0)) + effect.heartDelta;
        const reactionId = typeof data?.reactionId === 'string' && /^[a-f0-9-]{36}$/i.test(data.reactionId) ? data.reactionId : crypto.randomUUID();
        liveIo.to(liveId).emit('reaction', { liveId, kind: effect.kind, emoji: effect.emoji, userId, reactionId });
        if (effect.heartDelta) liveIo.to(liveId).emit('reaction-count', {
            liveId,
            reactionCount: session.reactionCount
        });
        if (effect.heartDelta && session.reactionCount % 25 === 0) {
            void setLiveReactionCount(liveId, session.reactionCount, session.hostAuthToken);
            persistLiveSessionInBackground(liveId, session);
        }
    });

    // Mobile clients aggregate rapid taps for 500 ms (or ten taps). The batch
    // id prevents a reconnect/retry from counting the same hearts twice.
    safeSocketOn(socket, 'send-reaction-batch', (data) => {
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data?.liveId || socket.data.liveId,
            'live',
            'send-reaction-batch'
        );
        if (!liveId) return;

        const userId = requireSocketUser(socket, data?.userId);
        if (!userId) return;
        const session = lives.get(liveId);
        if (!session) return;

        const count = Math.max(1, Math.min(10, Math.trunc(Number(data?.count || 1)) || 1));
        const clientBatchId = String(data?.clientBatchId || '').trim();
        if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(clientBatchId)) return;
        if (!consumeRateLimitToken('live-reaction-batch', `${liveId}:${userId}`, 8, 2_000)) {
            socket.emit('reaction-batch-rejected', {
                liveId,
                clientBatchId,
                reactionCount: Math.max(0, Number(session.reactionCount || 0)),
                reason: 'RATE_LIMITED'
            });
            return;
        }

        session.reactionBatchIds ||= new Set();
        if (session.reactionBatchIds.has(clientBatchId)) {
            socket.emit('reaction-count', { liveId, reactionCount: session.reactionCount });
            return;
        }
        session.reactionBatchIds.add(clientBatchId);
        if (session.reactionBatchIds.size > 500) {
            session.reactionBatchIds.delete(session.reactionBatchIds.values().next().value);
        }

        session.reactionCount = Math.max(0, Number(session.reactionCount || 0)) + count;
        liveIo.to(liveId).emit('reaction', {
            liveId,
            kind: 'heart',
            userId,
            count,
            clientBatchId
        });
        liveIo.to(liveId).emit('reaction-count', {
            liveId,
            reactionCount: session.reactionCount
        });
        if (session.reactionCount % 25 < count) {
            void setLiveReactionCount(liveId, session.reactionCount, session.hostAuthToken);
            persistLiveSessionInBackground(liveId, session);
        }
    });

    // Cosmetic gift events are accepted only after the Ubuntu API verifies the
    // committed transaction for this authenticated sender.
    safeSocketOn(socket, 'gift-committed', async (data) => {
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data?.liveId || socket.data.liveId,
            'live',
            'gift-committed'
        );
        if (!liveId) return;
        const transactionId = String(data?.transactionId || '').trim();
        if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(transactionId)) return;
        // Match the authoritative wallet policy so every gift that can be
        // committed during the minute can also display its verified animation.
        if (!consumeRateLimitToken('live-gift', `${liveId}:${socket.data.authUserId}`, 20, 60_000)) return;

        try {
            const payload = await callUbuntuApi('/rest/v1/rpc/get_virtual_gift_receipt', {
                method: 'POST',
                accessToken: getSocketAuthToken(socket),
                body: { p_transaction_id: transactionId }
            });
            const receipt = Array.isArray(payload) ? payload[0] : payload;
            const session = lives.get(liveId);
            if (!session?.host?.userId) return;
            if (!receipt || String(receipt.live_stream_id || '').trim() !== liveId) return;
            if (receipt.is_public === false) return;
            const committedReceiverId = String(receipt.receiver_id || '').trim();
            const allowedReceiverIds = new Set([String(session.host.userId || '').trim()]);
            if (session.battle?.status === 'active' && session.battle.opponentUserId) {
                allowedReceiverIds.add(String(session.battle.opponentUserId).trim());
            }
            if (!allowedReceiverIds.has(committedReceiverId)) return;
            const committedAmount = Math.max(0, Number(receipt.amount_diamonds || 0));
            if (committedAmount <= 0) return;
            if (!session || session.announcedGiftTransactionIds?.has(transactionId)) return;
            session.announcedGiftTransactionIds ||= new Set();
            session.announcedGiftTransactionIds.add(transactionId);
            liveIo.to(liveId).emit('gift', {
                transactionId: receipt.id,
                liveId,
                senderId: receipt.sender_id,
                senderName: receipt.sender_name || 'Usuario',
                receiverId: receipt.receiver_id,
                gift: {
                    id: receipt.gift_id,
                    name: receipt.gift_name || 'Regalo Novvin',
                    imageUrl: receipt.gift_image_url || null,
                    animationUrl: receipt.gift_animation_url || null,
                    priceDiamonds: committedAmount
                }
            });
            if (session.battle?.status === 'active') {
                if (committedReceiverId === String(session.host?.userId || '')) {
                    session.battle.hostScore += committedAmount;
                }
                if (committedReceiverId === String(session.battle.opponentUserId || '')) {
                    session.battle.opponentScore += committedAmount;
                }
                emitLiveBattleState(liveId, session);
            }
            persistLiveSessionInBackground(liveId, session);
        } catch (error) {
            console.error(`[SOCKET_DEBUG] [LIVE][GIFT] Could not verify transactionRef=${diagnosticRef(transactionId)}:`, diagnosticError(error));
            socket.emit('gift-error', { message: 'No se pudo verificar la animación del regalo.' });
        }
    });

    safeSocketOn(socket, 'update-live-state', (data) => {
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
        persistLiveSessionInBackground(liveId, session);
    });

    safeSocketOn(socket, 'live-guest-request', async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data.liveId || socket.data.liveId,
            'live',
            'live-guest-request'
        );
        if (!liveId) return;
        const session = lives.get(liveId);
        if (!session || socket.data.liveRole !== 'viewer') return;
        ensureLiveInteractionState(session);
        const viewer = session.viewers.get(socket.id);
        if (!viewer) return;

        // Re-read room policy at request time so a stale socket can never
        // bypass a host setting changed after the viewer joined.
        const liveMeta = await getLiveStreamMeta(liveId, getSocketAuthToken(socket));
        if (!liveMeta.exists || !liveMeta.isActive || !liveMeta.allowGuestRequests) {
            socket.emit('live-guest-status', {
                status: 'rejected',
                message: 'El anfitrión no está aceptando solicitudes en este momento.'
            });
            return;
        }

        if (session.guests.has(socket.id)) {
            socket.emit('live-guest-status', { status: 'accepted', message: 'Ya estás en la sala multi-host.' });
            return;
        }
        if (session.guests.size >= maxLiveGuests) {
            socket.emit('live-guest-status', { status: 'rejected', message: 'La sala multi-host ya está llena.' });
            return;
        }
        const request = { ...viewer, requestedAtMs: Date.now() };
        session.guestRequests.set(socket.id, request);
        socket.emit('live-guest-status', { status: 'pending', message: 'Solicitud enviada al anfitrión.' });
        if (session.host?.socketId) {
            liveIo.to(session.host.socketId).emit('live-guest-request', {
                ...publicLiveGuest(request, socket.id),
                requestedAtMs: request.requestedAtMs
            });
        }
        emitLiveGuestState(liveId, session);
        persistLiveSessionInBackground(liveId, session);
    });

    safeSocketOn(socket, 'live-guest-cancel', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data.liveId || socket.data.liveId,
            'live',
            'live-guest-cancel'
        );
        if (!liveId) return;
        const session = lives.get(liveId);
        if (!session || socket.data.liveRole !== 'viewer') return;
        ensureLiveInteractionState(session);
        session.guestRequests.delete(socket.id);
        socket.emit('live-guest-status', { status: 'none', message: 'Solicitud cancelada.' });
        emitLiveGuestState(liveId, session);
        persistLiveSessionInBackground(liveId, session);
    });

    safeSocketOn(socket, 'live-guest-response', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data.liveId || socket.data.liveId,
            'live',
            'live-guest-response'
        );
        if (!liveId) return;
        const session = lives.get(liveId);
        if (!session || session.host?.socketId !== socket.id) return;
        ensureLiveInteractionState(session);
        const targetSocketId = String(data.targetSocketId || '').trim();
        const targetViewer = session.guestRequests.get(targetSocketId) || session.viewers.get(targetSocketId);
        if (!targetSocketId || !targetViewer) return;

        session.guestRequests.delete(targetSocketId);
        const accepted = Boolean(data.accepted) && session.guests.size < maxLiveGuests;
        if (accepted) session.guests.set(targetSocketId, targetViewer);
        liveIo.to(targetSocketId).emit('live-guest-status', {
            status: accepted ? 'accepted' : 'rejected',
            message: accepted
                ? 'El anfitrión aceptó tu solicitud. Configura cámara y micrófono.'
                : session.guests.size >= maxLiveGuests
                    ? 'La sala multi-host ya está llena.'
                    : 'El anfitrión rechazó la solicitud.'
        });
        emitLiveGuestState(liveId, session);
        persistLiveSessionInBackground(liveId, session);
    });

    safeSocketOn(socket, 'update-live-guest-media-state', (data) => {
        const liveId = requireJoinedScope(socket, 'joinedLiveIds', data.liveId || socket.data.liveId,
            'live', 'update-live-guest-media-state');
        if (!liveId) return;
        const session = lives.get(liveId);
        if (!updateLiveGuestMediaState(session, socket, data)) return;
        emitLiveGuestState(liveId, session);
        persistLiveSessionInBackground(liveId, session);
    });

    safeSocketOn(socket, 'live-guest-leave', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data.liveId || socket.data.liveId,
            'live',
            'live-guest-leave'
        );
        if (!liveId) return;
        const session = lives.get(liveId);
        if (!session) return;
        ensureLiveInteractionState(session);
        session.guestRequests.delete(socket.id);
        session.guests.delete(socket.id);
        socket.emit('live-guest-status', { status: 'none', message: 'Saliste de la sala multi-host.' });
        emitLiveGuestState(liveId, session);
        persistLiveSessionInBackground(liveId, session);
    });

    safeSocketOn(socket, 'live-battle-start', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = requireJoinedScope(socket, 'joinedLiveIds', data.liveId || socket.data.liveId, 'live', 'live-battle-start');
        if (!liveId) return;
        const session = lives.get(liveId);
        if (!session || session.host?.socketId !== socket.id) return;
        ensureLiveInteractionState(session);
        const targetSocketId = String(data.targetSocketId || '').trim();
        const opponent = session.guests.get(targetSocketId);
        if (!opponent) return;
        const durationSeconds = Math.max(30, Math.min(600, Number(data.durationSeconds) || 180));
        session.battle = {
            status: 'invited',
            opponentSocketId: targetSocketId,
            opponentUserId: opponent.userId,
            opponentName: opponent.name,
            opponentAvatarUrl: opponent.avatarUrl || '',
            hostScore: 0,
            opponentScore: 0,
            durationSeconds,
            startedAtMs: 0,
            message: 'Solicitud de batalla recibida.'
        };
        liveIo.to(targetSocketId).emit('live-battle-invitation', session.battle);
        // Keep the invitation private to the selected guest. The host receives
        // the waiting state, while ordinary viewers only see a battle after it
        // has actually been accepted.
        if (session.host?.socketId) {
            liveIo.to(session.host.socketId).emit('live-battle-state', session.battle);
        }
        persistLiveSessionInBackground(liveId, session);
    });

    safeSocketOn(socket, 'live-battle-response', (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = requireJoinedScope(socket, 'joinedLiveIds', data.liveId || socket.data.liveId, 'live', 'live-battle-response');
        if (!liveId) return;
        const session = lives.get(liveId);
        if (!session) return;
        ensureLiveInteractionState(session);
        if (session.battle.status !== 'invited' || session.battle.opponentSocketId !== socket.id) return;
        const accepted = Boolean(data.accepted);
        session.battle = {
            ...session.battle,
            status: accepted ? 'active' : 'rejected',
            startedAtMs: accepted ? Date.now() : 0,
            message: accepted ? 'Batalla en curso' : 'La invitación fue rechazada.'
        };
        emitLiveBattleState(liveId, session);
        if (accepted) startLiveBattleTimer(liveId, session);
        persistLiveSessionInBackground(liveId, session);
    });

    safeSocketOn(socket, 'live-moderation-command', async (rawData) => {
        const data = (rawData && typeof rawData === 'object') ? rawData : {};
        const liveId = requireJoinedScope(
            socket,
            'joinedLiveIds',
            data.liveId || socket.data.liveId,
            'live',
            'live-moderation-command'
        );
        if (!liveId) return;
        const session = lives.get(liveId);
        if (!session) return;

        const command = String(data.command || '').trim().toLowerCase();
        const targetUserId = String(data.targetUserId || '').trim();
        try {
            let payload;
            if (command === 'settings') {
                payload = await callUbuntuApi(
                    `/v1/data/live/${encodeURIComponent(liveId)}/moderation/settings`,
                    {
                        method: 'PATCH',
                        accessToken: getSocketAuthToken(socket),
                        body: {
                            ...(data.chatPaused !== undefined ? { chatPaused: Boolean(data.chatPaused) } : {}),
                            ...(Array.isArray(data.blockedWords) ? { blockedWords: data.blockedWords } : {})
                        }
                    }
                );
            } else if ((command === 'assign-moderator' || command === 'remove-moderator') && targetUserId) {
                payload = await callUbuntuApi(
                    `/v1/data/live/${encodeURIComponent(liveId)}/moderators/${encodeURIComponent(targetUserId)}`,
                    {
                        method: command === 'assign-moderator' ? 'PUT' : 'DELETE',
                        accessToken: getSocketAuthToken(socket)
                    }
                );
            } else if (['kick', 'ban', 'unban'].includes(command) && targetUserId) {
                payload = await callUbuntuApi(
                    `/v1/data/live/${encodeURIComponent(liveId)}/moderation/actions`,
                    {
                        method: 'POST',
                        accessToken: getSocketAuthToken(socket),
                        body: {
                            action: command,
                            targetUserId,
                            reason: String(data.reason || '').trim().slice(0, 300)
                        }
                    }
                );
            } else {
                socket.emit('live-moderation-error', { error: 'La acción de moderación no es válida.' });
                return;
            }

            const moderation = payload?.moderation || {};
            if (moderation.chat_paused !== undefined) {
                session.moderation.chatPaused = Boolean(moderation.chat_paused);
            }
            if (Array.isArray(moderation.moderators)) {
                session.moderation.moderators = new Set(
                    moderation.moderators
                        .map((entry) => String(entry?.user_id || '').trim())
                        .filter(Boolean)
                );
            }
            if (Array.isArray(moderation.blocked_words)) {
                session.moderation.blockedWords = moderation.blocked_words
                    .map((word) => String(word || '').trim())
                    .filter(Boolean);
            }
            session.viewers.forEach((viewer) => {
                viewer.isModerator = session.moderation.moderators.has(viewer.userId);
            });

            if (['kick', 'ban'].includes(command)) {
                for (const [viewerSocketId, viewer] of session.viewers.entries()) {
                    if (viewer.userId !== targetUserId) continue;
                    liveIo.to(viewerSocketId).emit('live-moderated', {
                        action: command,
                        message: command === 'ban'
                            ? 'El equipo de moderación restringió tu acceso a este directo.'
                            : 'El equipo de moderación te retiró de este directo.'
                    });
                    const targetSocket = liveIo.sockets.get(viewerSocketId);
                    if (targetSocket) {
                        targetSocket.data.joinedLiveIds?.delete(liveId);
                        targetSocket.leave(liveId);
                    }
                    session.viewers.delete(viewerSocketId);
                    if (session.host?.socketId) {
                        liveIo.to(liveId).emit('viewer-left', viewerSocketId);
                    }
                }
                const viewerCount = session.viewers.size;
                liveIo.to(liveId).emit('viewer-count', { liveId, viewerCount });
                await setLiveViewerCount(liveId, viewerCount, session.hostAuthToken);
            }

            emitLiveModerationState(liveId, session);
            persistLiveSessionInBackground(liveId, session);
            socket.emit('live-moderation-confirmed', { command, targetUserId });
        } catch (error) {
            console.error(`[SOCKET_DEBUG] [LIVE][MODERATION] ${command} failed for liveRef=${diagnosticRef(liveId)}:`, diagnosticError(error));
            const messageByCode = {
                LIVE_HOST_REQUIRED: 'Solo el anfitrión puede realizar esta acción.',
                LIVE_MODERATOR_REQUIRED: 'No tienes permisos para moderar este directo.',
                LIVE_HOST_CANNOT_BE_MODERATED: 'No se puede moderar al anfitrión del directo.'
            };
            socket.emit('live-moderation-error', {
                error: messageByCode[error.message] || 'No se pudo completar la acción de moderación.'
            });
        }
    });

    // WebRTC signaling (scoped to /live namespace)
    safeSocketOn(socket, 'offer', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'offer');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed offer payload' });
            return;
        }
        const { target, offer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, liveIo, 'joinedLiveIds', target, 'live session', 'offer'))) return;
        liveIo.to(target).emit('offer', {
            sender: socket.id,
            offer,
            userId: authenticatedUserId,
            name
        });
    });

    safeSocketOn(socket, 'answer', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'answer');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed answer payload' });
            return;
        }
        const { target, answer, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, liveIo, 'joinedLiveIds', target, 'live session', 'answer'))) return;
        liveIo.to(target).emit('answer', {
            sender: socket.id,
            answer,
            userId: authenticatedUserId,
            name
        });
    });

    safeSocketOn(socket, 'ice-candidate', async (rawData) => {
        const data = normalizeSignalPayload(rawData, 'ice-candidate');
        if (!data) {
            socket.emit('signal-error', { error: 'Malformed ICE candidate payload' });
            return;
        }
        const { target, candidate, userId, name } = data;
        const authenticatedUserId = requireSocketUser(socket, userId);
        if (!authenticatedUserId) return;
        if (!(await requireSharedScopeTarget(socket, liveIo, 'joinedLiveIds', target, 'live session', 'ICE'))) return;
        liveIo.to(target).emit('ice-candidate', {
            sender: socket.id,
            candidate,
            userId: authenticatedUserId,
            name
        });
    });

    safeSocketOn(socket, 'disconnecting', async () => {
        const joined = Array.from(socket.data.joinedLiveIds || []);

        for (const liveId of joined) {
            const session = lives.get(liveId);
            if (!session) continue;

            // Host disconnect => keep the live alive for a grace window
            if (session.host?.socketId === socket.id) {
                const disconnectedHost = session.host;
                socket.to(liveId).emit('host-disconnected', {
                    socketId: disconnectedHost.socketId,
                    userId: disconnectedHost.userId,
                    replaced: false,
                    graceMs: HOST_DISCONNECT_GRACE_MS
                });
                session.host = null;
                session.pendingHostSnapshot = disconnectedHost;
                await currentLiveStateStore()
                    .releaseHostLease(liveId, liveLeaseOwner(socket.id))
                    .catch((error) => {
                        console.error(`[SIGNALING_REDIS] Could not release host lease for liveRef=${diagnosticRef(liveId)}`, diagnosticError(error));
                    });
                console.log(`[SOCKET_DEBUG] [LIVE][HOST] userRef=${diagnosticRef(disconnectedHost.userId)} disconnected from liveRef=${diagnosticRef(liveId)}. Waiting ${HOST_DISCONNECT_GRACE_MS}ms for reconnection`
                );
                scheduleHostTerminationGrace(liveId, disconnectedHost);
                continue;
            }

            // Viewer disconnect
            const wasViewer = session.viewers.delete(socket.id);
            if (wasViewer) {
                ensureLiveInteractionState(session);
                session.guestRequests.delete(socket.id);
                const wasGuest = session.guests.delete(socket.id);
                if (wasGuest && session.battle?.opponentSocketId === socket.id && ['invited', 'active'].includes(session.battle.status)) {
                    clearLiveBattleTimer(liveId);
                    session.battle = { ...session.battle, status: 'finished', message: 'El invitado salió de la batalla.' };
                    emitLiveBattleState(liveId, session);
                }
                emitLiveGuestState(liveId, session);
                if (session.host?.socketId) {
                    liveIo.to(liveId).emit('viewer-left', socket.id);
                }

                const viewerCount = session.viewers.size;
                liveIo.to(liveId).emit('viewer-count', { liveId, viewerCount });
                await setLiveViewerCount(liveId, viewerCount, session.hostAuthToken);
                persistLiveSessionInBackground(liveId, session);

                if (!session.host && viewerCount === 0 && !liveEndTimers.has(liveId)) {
                    const pendingHost = session.pendingHostSnapshot;
                    if (pendingHost?.userId) {
                        scheduleHostTerminationGrace(liveId, pendingHost);
                    } else {
                        await finalizeLiveTermination(liveId, 'empty-orphaned-live');
                    }
                }
            }
        }

        socket.data.joinedLiveIds.clear();
        delete socket.data.liveParticipant;
    });

    safeSocketOn(socket, 'disconnect', () => {
        console.log(`[SOCKET_DEBUG] [LIVE] User disconnected: ${socket.id}`);
    });
});

let shuttingDown = false;

async function shutdown(signal) {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`[SOCKET_DEBUG] Graceful shutdown requested (${signal})`);
    if (liveLeaseHeartbeatTimer) clearInterval(liveLeaseHeartbeatTimer);
    if (liveRecoveryMonitorTimer) clearInterval(liveRecoveryMonitorTimer);
    liveLeaseHeartbeatTimer = null;
    liveRecoveryMonitorTimer = null;

    await new Promise((resolve) => {
        const timeout = setTimeout(resolve, 10_000);
        timeout.unref?.();
        io.close(() => {
            clearTimeout(timeout);
            resolve();
        });
    });

    try {
        await distributedAdapterRuntime.close();
    } catch (error) {
        console.error('[SIGNALING_REDIS] Failed to close Redis cleanly', diagnosticError(error));
    }
}

server.on('error', (error) => {
    console.error('[SOCKET_DEBUG] [FATAL] HTTP server error:', diagnosticError(error));
    process.exitCode = 1;
    void shutdown('server-error');
});

process.once('SIGTERM', () => void shutdown('SIGTERM'));
process.once('SIGINT', () => void shutdown('SIGINT'));

async function startSignalingServer() {
    distributedAdapterRuntime = await connectDistributedAdapter(io, {
        onRequiredDisconnect: () => {
            console.error('[SIGNALING_REDIS] Required Redis connection exhausted; restarting signaling process');
            process.exitCode = 1;
            void shutdown('redis-disconnected');
        }
    });
    startLiveRecoveryWorkers();
    await new Promise((resolve, reject) => {
        const onError = (error) => {
            server.off('listening', onListening);
            reject(error);
        };
        const onListening = () => {
            server.off('error', onError);
            resolve();
        };
        server.once('error', onError);
        server.once('listening', onListening);
        server.listen(PORT, '0.0.0.0');
    });
    console.log(`[SOCKET_DEBUG] Signaling server running on port ${PORT}`);
}

startSignalingServer().catch(async (error) => {
    console.error('[SOCKET_DEBUG] [FATAL] Signaling startup failed:', diagnosticError(error));
    process.exitCode = 1;
    await shutdown('startup-failure');
});
