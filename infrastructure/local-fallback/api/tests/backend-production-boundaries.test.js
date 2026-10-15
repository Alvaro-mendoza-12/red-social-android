'use strict';
const assert = require('assert/strict');
const jwt = require('jsonwebtoken');
const { registerPostgrestCompatRoutes } = require('../src/postgrest-compat-routes');
const admin = require('../src/livekit-admin');
const adminModule = require.resolve('../src/livekit-admin');
let activeClientFactory;
require.cache[adminModule].exports = {
  ...admin,
  removeLiveKitParticipant: options => admin.removeLiveKitParticipant({ ...options, clientFactory: activeClientFactory }),
  muteLiveKitParticipantAudio: options => admin.muteLiveKitParticipantAudio({ ...options, clientFactory: activeClientFactory }),
};
const realtimeModule = require.resolve('../src/realtime-publish');
require.cache[realtimeModule] = { id: realtimeModule, filename: realtimeModule, loaded: true, exports: { publishRealtimeEvent: async () => true } };
const collaborationModule = require.resolve('../src/live-collaboration-events');
require.cache[collaborationModule] = { id: collaborationModule, filename: collaborationModule, loaded: true, exports: { publishLiveCollaborationEvent: async () => true } };
const { registerLiveRoutes } = require('../src/live-routes');

// Tests use only synthetic actors, rooms, SDK responses and PostgreSQL results.
global.fetch = async () => { throw new Error('TEST_NETWORK_FORBIDDEN'); };
function capture() {
  const handlers = new Map();
  const app = Object.fromEntries(['get', 'post', 'put', 'patch', 'delete', 'head', 'options'].map(method =>
    [method, (route, ...middleware) => handlers.set(`${method} ${route}`, middleware.at(-1))]));
  return { app, handlers };
}
async function invoke(handler, req) {
  let payload, status = 200, error;
  const res = { setHeader() {}, status(value) { status = value; return this; }, json(value) { payload = value; return this; } };
  await handler(req, res, e => { error = e; });
  if (error) throw error;
  return { payload, status };
}
async function rpcPrivacy() {
  const { app, handlers } = capture(); let queries = 0;
  registerPostgrestCompatRoutes(app, { authOptional() {}, pool: { query() { queries++; throw new Error('PRIVATE_QUERY_FORBIDDEN'); } } });
  const rpc = [...handlers.entries()].find(([key]) => key.startsWith('post /^\\/rest\\/v1\\/rpc'))?.[1];
  assert.ok(rpc, 'Compatibility RPC registered');
  for (const identifier of ['synthetic name', 'unknown synthetic', 'alias@example.test']) {
    const response = await invoke(rpc, { params: { 0: 'resolve_login_email' }, body: { identifier } });
    assert.equal(response.status, 200); assert.equal(response.payload, identifier);
  }
  assert.equal(queries, 0);
}
async function liveModeration() {
  process.env.LIVE_GUESTS_BATTLES_ENABLED = 'true'; process.env.LIVE_SFU_ENABLED = 'true';
  process.env.LIVEKIT_URL = 'wss://synthetic-sfu.invalid'; process.env.LIVEKIT_API_KEY = 'synthetic-test';
  process.env.LIVEKIT_API_SECRET = 'synthetic-test-only-not-deployed';
  const liveId = '11111111-1111-4111-8111-111111111111';
  const hostId = '44444444-4444-4444-8444-444444444444', guestId = '55555555-5555-4555-8555-555555555555';
  for (const battle of [false, true]) for (const operation of ['remove', 'mute']) {
    const { app, handlers } = capture();
    const room = battle ? 'novvin-battle-synthetic-test' : `novvin-live-${liveId}`;
    const state = { connected: true, muted: false, hostMuted: false, canPublishMicrophone: true };
    activeClientFactory = async () => ({
      async removeParticipant(actual) { assert.equal(actual, room); state.connected = false; },
      async getParticipant(actual) { assert.equal(actual, room); return { tracks: [{ sid: 'audio-test', type: 'audio' }] }; },
      async updateParticipant(actual, _userId, options) { assert.equal(actual, room); assert.deepEqual(options.permission.canPublishSources, [1, 3]); state.canPublishMicrophone = false; },
      async mutePublishedTrack(actual, _userId, _sid, muted) { assert.equal(actual, room); state.muted = muted; },
    });
    const pool = { async query(sql) {
      const text = String(sql).replace(/\s+/g, ' ');
      if (text.includes('FROM fallback_live_streams')) return { rows: [{ id: liveId, host_user_id: hostId, is_active: true, visibility: 'public' }] };
      if (text.includes('FROM fallback_live_bans')) return { rows: [] };
      if (text.includes('COUNT(*)::int AS count')) return { rows: [{ count: 0 }] };
      if (text.includes('FROM fallback_live_guest_invitations')) return { rows: [{ status: 'accepted' }] };
      if (text.includes('FROM fallback_live_battles')) return { rows: battle ? [{ id: 'battle-test', room_name: room }] : [] };
      if (text.includes('FROM fallback_live_participants')) return { rows: [{ id: '66666666-6666-4666-8666-666666666666', user_id: guestId, participant_identity: guestId, state: 'connected', audio_muted_by_host: state.hostMuted }] };
      if (text.includes('SET audio_muted_by_host = TRUE')) state.hostMuted = true;
      assert.equal(text.includes('audio_muted_by_host = FALSE'), false);
      return { rows: [] };
    }, async connect() { return { query: pool.query, release() {} }; } };
    registerLiveRoutes(app, { pool, authRequired() {}, getCurrentUser: async (_db, req) => ({ id: req.actorId }),
      userIdentity: user => ({ displayUserId: user.id, displayName: 'Synthetic', ids: new Set([user.id]), values: [user.id] }) });
    const req = (actorId, body = {}) => ({ actorId, params: { liveId, guestUserId: guestId }, body });
    const token = await invoke(handlers.get('post /v1/data/live/:liveId/sfu-token'), req(guestId, { role: 'guest' }));
    assert.equal(token.status, 200); assert.equal(token.payload.roomName, room);
    const response = await invoke(handlers.get(`post /v1/data/live/:liveId/guests/:guestUserId/${operation}`), req(hostId));
    assert.equal(response.status, 200);
    if (operation === 'remove') assert.equal(state.connected, false);
    else {
      assert.equal(state.muted, true); assert.equal(state.hostMuted, true); assert.equal(state.canPublishMicrophone, false);
      const renewed = await invoke(handlers.get('post /v1/data/live/:liveId/sfu-token'), req(guestId, { role: 'guest' }));
      assert.deepEqual(jwt.decode(renewed.payload.token).video.canPublishSources, ['camera', 'screen_share']);
      assert.equal(state.hostMuted, true);
    }
  }
}
async function run() { await rpcPrivacy(); await liveModeration(); console.log('backend production boundary regressions passed'); }
run().catch(error => { console.error(error); process.exitCode = 1; });
