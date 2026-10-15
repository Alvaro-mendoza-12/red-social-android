'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { trustedOrigins, parseObjectReference, objectReferenceUrls, messageEligible, createStorageObjectReadGuard } = require('../src/storage-read-access');
const ORIGIN = 'https://api.example.invalid';
const NOW = Date.parse('2026-10-08T00:00:00Z');
const identities = [{ id: 'owner', supabase_user_id: 'legacy-owner' }, { id: 'peer', supabase_user_id: 'legacy-peer' }, { id: 'outsider', supabase_user_id: null }];
const object = (bucket = 'chat-media') => ({ bucket_id: bucket, name: 'owner/photo.png', owner_id: 'owner', public_url: `${ORIGIN}/storage/v1/object/public/${bucket}/owner/photo.png`, created_at: new Date(NOW - 1000).toISOString() });
function fixture(overrides = {}) {
  const state = { dm: [], rooms: [], random: [], history: [], chats: [], chatParticipants: [], collectionRooms: [], members: [], sessions: [], blocked: false, imported: {}, localOverrides: [], ...overrides };
  const queries = [], headers = {}; let released = 0, authenticatedCalls = 0;
  const withMedia=row=>({...row,room_kind:row.room_kind??row.access_kind,media_url:row.media_url??state.currentObject?.public_url});
  const db = { async query(sql, params = []) {
    queries.push({ sql, params });
    if (state.failSql && sql.includes(state.failSql)) throw new Error('DB_TEST_FAILURE');
    if (/^(BEGIN|COMMIT|ROLLBACK|SET LOCAL)/.test(sql)) return { rows: [] };
    if (sql.includes('FROM fallback_compat_rows') && params.at(-1)?.startsWith('sticker_')) return {rows:[]};
    if (sql.includes('to_regclass')) return { rows: [{ relation: state.imported[params[0]] ? params[0] : null }] };
    if (sql.includes('FROM pg_attribute')) return { rows: [{ name: 'media_url', text_type: true }, { name: 'thumbnail_url', text_type: true }] };
    if(sql.includes('FROM public.fallback_storage_private_bindings'))return{rows:[
      ...state.dm.map(row=>({source_schema:'public',source_table:'fallback_messages',message_id:String(row.id)})),
      ...state.rooms.map(row=>({source_schema:'public',source_table:'fallback_room_messages',message_id:String(row.id)})),
      ...state.random.map(row=>({source_schema:'public',source_table:'fallback_random_chat_messages',message_id:String(row.id)})),
      ...Object.entries(state.imported).filter(([name])=>['supabase_import.messages','supabase_import.group_messages','supabase_import.community_messages','supabase_import.proximity_messages'].includes(name)).flatMap(([name,rows])=>rows.map(row=>({source_schema:'supabase_import',source_table:name.split('.')[1],message_id:String(row.id)}))),
    ]};
    if (sql.includes('FROM supabase_import.')) { const table = sql.match(/supabase_import\."([^"]+)"/)[1]; return { rows: (state.imported[`supabase_import.${table}`] || []).map(row => ({ row })) }; }
    if (sql.includes('FROM public.fallback_storage_content_bindings') || sql.includes('FROM fallback_storage_content_history')) return {rows:[]};
    if (sql.includes('FROM fallback_storage_private_history')) return { rows: state.history };
    if (sql.includes('FROM fallback_users')) return { rows: identities.filter(user => [user.id, user.supabase_user_id].includes(params[0])) };
    if (sql.includes('FROM fallback_user_blocks')) return { rows: state.blocked ? [{ one: 1 }] : [] };
    if (sql.includes('FROM fallback_messages')) return { rows: [...state.dm,...state.localOverrides].filter(row=>(params[0]||[]).includes(String(row.id))).map(withMedia) };
    if (sql.includes('FROM fallback_room_messages')) return { rows: [...state.rooms,...state.localOverrides].filter(row=>(params[0]||[]).includes(String(row.id))).map(withMedia) };
    if (sql.includes('FROM fallback_random_chat_messages')) return { rows: state.random.map(withMedia) };
    if (sql.includes('FROM fallback_collection_chat_history')) return {rows:state.archives || []};
    if (sql.includes('fallback_collection_policies') || sql.includes('fallback_collection_history_grants') || sql.includes('FROM fallback_compat_rows')) return { rows: [] };
    if (sql.includes('FROM fallback_collection_rooms')) return { rows: state.collectionRooms };
    if (sql.includes('FROM fallback_collection_members')) return { rows: state.members };
    if (sql.includes('FROM fallback_chats')) return { rows: state.chats };
    if (sql.includes('FROM fallback_chat_participants')) return { rows: state.chatParticipants };
    if (sql.includes('FROM fallback_random_chat_sessions')) return { rows: state.sessions };
    if (sql.includes('FROM fallback_community_channels')) return { rows: state.channels || [] };
    throw new Error(`UNHANDLED_TEST_QUERY: ${sql}`);
  }, release() { released++; } };
  const guard = createStorageObjectReadGuard({ pool: { connect: async () => db }, origins: [ORIGIN], now: () => NOW, allowUnreferencedPublicBuckets: state.compatibility === true,
    authRequired: async (req, res, next) => { authenticatedCalls++; if (!req.testActor || state.suspended) { res.responded = true; return; } req.auth = { sub: req.testActor }; next(); },
  });
  const res = { setHeader(key, value) { headers[key] = value; } };
  return { state, queries, headers, res, guard, counts: () => ({ released, authenticatedCalls }), read: (actor, row = object(), extra = {}) => { state.currentObject=row;return guard({ testActor: actor, ...extra, headers: { ...(actor ? { authorization: 'Bearer synthetic-unit-fixture' } : {}), ...(extra.headers || {}) } }, res, row); } };
}
const dmFixture = (changes = {}) => fixture({ dm: [{ id: 'message', access_kind: 'dm', sender_id: 'legacy-owner', chat_id: 'chat', media_url: object().public_url }], chats: [{ id: 'chat', participants: ['legacy-owner', 'legacy-peer'] }], ...changes });

test('trusted origins require exact HTTPS origin configuration', () => {
  assert.deepEqual(trustedOrigins([ORIGIN, ORIGIN]), [ORIGIN]);
  for (const bad of ['http://api.example.invalid', `${ORIGIN}/path`, 'https://a:b@api.example.invalid', `${ORIGIN}?x=1`]) assert.throws(() => trustedOrigins([bad]));
});
test('object URL recognizes original aliases and query variants exactly', () => {
  for (const prefix of ['public/', 'sign/', '']) assert.deepEqual(parseObjectReference(`${ORIGIN}/storage/v1/object/${prefix}chat-media/owner/photo.png?width=256&token=stolen`, [ORIGIN]), { bucket: 'chat-media', name: 'owner/photo.png' });
});
test('object URL rejects origin, credentials, port, traversal and nested escaping tricks', () => {
  const suffix = '/storage/v1/object/public/chat-media/owner/photo.png';
  for (const bad of ['https://api.example.invalid.evil'+suffix, 'https://evil.invalid/'+ORIGIN+suffix, 'https://user@api.example.invalid'+suffix, 'http://api.example.invalid'+suffix, 'https://api.example.invalid:444'+suffix, ORIGIN+'/storage/v1/object/public/chat-media/a%2fb', ORIGIN+'/storage/v1/object/public/chat-media/a%252fb', ORIGIN+'/storage/v1/object/public/chat-media/a%5cb', ORIGIN+suffix+'#x']) assert.equal(parseObjectReference(bad, [ORIGIN]), null, bad);
  for(const bad of [ORIGIN+'/storage/v1/object/public/chat-media/owner/../photo.png',ORIGIN+'/storage/v1/object/public/chat-media/owner/%2e%2e/photo.png','https://%61pi.example.invalid'+suffix])assert.equal(parseObjectReference(bad,[ORIGIN]),null,bad);
});
test('object exact identity rejects a corrupt stored path instead of normalizing it', () => { assert.throws(() => objectReferenceUrls({ ...object(), name: 'owner/../photo.png' }, [ORIGIN])); });
test('foreign public_url does not add an untrusted lookup origin', () => { assert.ok(objectReferenceUrls({ ...object(), public_url: 'https://evil.invalid/storage/v1/object/public/chat-media/owner/photo.png' }, [ORIGIN]).every(url => url.startsWith(ORIGIN))); });
test('tombstones, local hides and view-once fail eligibility', () => {
  assert.equal(messageEligible({ deleted_for_all: true }, ['peer']), false);
  assert.equal(messageEligible({ deleted_by: '["legacy-peer"]' }, ['peer', 'legacy-peer']), false);
  assert.equal(messageEligible({ content_type: 'view_once_image' }, ['peer']), false);
  assert.equal(messageEligible({ deleted_by: ['outsider'] }, ['peer']), true);
});
test('current DM participant reads with authenticated local/imported alias', async () => { const f = dmFixture(); assert.equal((await f.read('peer')).scope, 'dm'); assert.equal(f.headers['Cache-Control'], 'private, no-store'); assert.equal(f.counts().released, 1); });
test('another authenticated account cannot read DM object', async () => { await assert.rejects(dmFixture().read('outsider'), { status: 404 }); });
test('an anonymous reader cannot read with copied signed token', async () => { const f = dmFixture(); const result = await f.read(null, object(), { query: { token: 'signed-capability' } }); assert.equal(result.allowed, false); assert.equal(result.responseHandled, true); });
test('Range and matching ETag never skip membership checks', async () => { for (const headers of [{ range: 'bytes=0-9' }, { 'if-none-match': '"cached"' }]) await assert.rejects(dmFixture().read('outsider', object(), { headers }), { status: 404 }); });
test('bidirectional canonical block revokes current DM access', async () => { const f = dmFixture({ blocked: true }); await assert.rejects(f.read('peer'), { status: 404 }); });
test('cleared conversation cannot expose previous media to that viewer', async () => { const f = dmFixture({ chatParticipants: [{ user_id: 'legacy-peer', cleared_at: '2026-10-07T23:59:00Z' }] }); f.state.dm[0].created_at = '2026-10-07T23:58:00Z'; await assert.rejects(f.read('peer'), { status: 404 }); });
test('owner does not bypass revoked/tombstoned message', async () => { const f = dmFixture(); f.state.dm[0].deleted_for_all = true; await assert.rejects(f.read('owner'), { status: 404 }); });
test('view-once is deliberately denied until atomic view lease exists', async () => { const f = dmFixture(); f.state.dm[0].content_type = 'view_once_video'; await assert.rejects(f.read('peer'), { status: 404 }); });
test('other live authorized reference permits shared object', async () => { const f = dmFixture(); f.state.dm.push({ ...f.state.dm[0], id: 'another' }); f.state.dm[0].deleted_for_all = true; assert.equal((await f.read('peer')).allowed, true); });
test('public group metadata never grants a nonmember access to message media', async () => {
  const f = fixture({ rooms: [{ id: 'm', access_kind: 'group', sender_id: 'owner', room_id: 'g' }], collectionRooms: [{ id: 'g', creator_id: 'owner', is_public: true }] });
  await assert.rejects(f.read('outsider', object('group-media')), {status:404});
});
test('private group denies nonmember preview and public messages still need authentication', async () => {
  for (const is_public of [true,false]) { const f=fixture({ rooms:[{ id:'m',access_kind:'group',sender_id:'owner',room_id:'g' }],collectionRooms:[{id:'g',creator_id:'owner',is_public}] });
    assert.equal((await f.read(null,object('group-media'))).allowed,false);
    if(!is_public) await assert.rejects(f.read('outsider',object('group-media')),{status:404});
  }
});
test('current group member is admitted after joining', async () => {
  const f = fixture({ rooms: [{ id: 'm', access_kind: 'group', sender_id: 'owner', room_id: 'g', created_at:'2026-09-01T00:00:00Z' }], collectionRooms: [{ id: 'g', creator_id: 'owner' }], members: [{ user_id: 'peer', is_deleted: false, joined_at:'2026-08-01T00:00:00Z' }] });
  assert.equal((await f.read('peer', object('group-media'))).scope, 'group');
});
test('removed group member and removed creator are denied', async () => {
  for (const actor of ['peer', 'owner']) {
    const f = fixture({ rooms: [{ id: 'm', access_kind: 'community', sender_id: 'owner', room_id: 'g' }], collectionRooms: [{ id: 'g', creator_id: 'owner',is_public:true }], members: [{ user_id: actor, is_deleted: true }] });
    await assert.rejects(f.read(actor, object('community-media')), { status: 404 });
  }
});
test('deleted community channel revokes its media', async () => {
  const f = fixture({ rooms: [{ id: 'm', access_kind: 'community', sender_id: 'owner', room_id: 'g', channel_id: 'c' }], collectionRooms: [{ id: 'g', creator_id: 'owner' }], members: [{ user_id: 'peer', is_deleted: false }], channels: [{ community_id: 'g', is_deleted: true }] });
  await assert.rejects(f.read('peer', object('community-media')), { status: 404 });
});
test('authentication runs before reserving a pool transaction connection', async () => {
  const ordering = [];
  const f = dmFixture();
  const guard = createStorageObjectReadGuard({ origins: [ORIGIN], pool: { connect: async () => { ordering.push('connect'); throw new Error('fixture-stop-after-connect'); } }, authRequired: async (req,res,next) => { ordering.push('authenticate'); req.auth = { sub: 'peer' }; next(); } });
  await assert.rejects(guard({ headers: { authorization: 'Bearer fixture' } }, f.res, object()), /fixture-stop-after-connect/);
  assert.deepEqual(ordering, ['authenticate','connect']);
});
test('public room avatar in mixed bucket stays anonymous readable', async () => { const f = fixture({ collectionRooms: [{ id: 'g', creator_id: 'legacy-owner', is_public: true, is_deleted: false }] }); assert.equal((await f.read(null, object('groups'))).scope, 'public-room-avatar'); assert.equal(f.counts().authenticatedCalls, 0); });
test('a room cannot publish another uploader private object by URL alone', async () => { const f = fixture({ collectionRooms: [{ id: 'g', creator_id: 'outsider', is_public: true, is_deleted: false }] }); assert.equal((await f.read(null, object('groups'))).allowed, false); });
test('private history vetoes publicly reusing an old private object', async () => { const f = fixture({ history: [{ one: 1 }], collectionRooms: [{ id: 'g', is_public: true, is_deleted: false }] }); await assert.rejects(f.read('owner', object('groups')), { status: 404 }); });
test('active random session participant can read', async () => { const f = fixture({ random: [{ id: 'r', access_kind: 'random', sender_id: 'owner', session_id: 's' }], sessions: [{ user_a: 'legacy-owner', user_b: 'legacy-peer', status: 'active' }] }); assert.equal((await f.read('peer')).scope, 'random'); });
test('ended random conversation preserves participant history multimedia', async () => { const f = fixture({ random: [{ id: 'r', access_kind: 'random', sender_id: 'owner', session_id: 's' }], sessions: [{ user_a: 'owner', user_b: 'peer', status:'ended' }] }); assert.equal((await f.read('peer')).scope,'random'); });
test('stranger and blocked peer cannot read active or ended random history', async () => { for (const [actor,status,blocked] of [['outsider','active',false],['outsider','ended',false],['peer','ended',true]]) { const f = fixture({ blocked,random: [{ id: 'r', access_kind: 'random', sender_id: 'owner', session_id: 's' }], sessions: [{ user_a: 'owner', user_b: 'peer', status }] }); await assert.rejects(f.read(actor), { status: 404 }); } });
test('owner gets short-lived preview only before any private binding', async () => { const f = fixture(); assert.equal((await f.read('owner')).scope, 'owner-upload-preview'); });
test('expired or future upload has no ownership read bypass', async () => { for (const delta of [-16*60*1000, 1000]) await assert.rejects(fixture().read('owner', { ...object(), created_at: new Date(NOW+delta).toISOString() }), { status: 404 }); });
test('hard-deleted message remains private and cannot revive owner preview', async () => { await assert.rejects(fixture({ history: [{ one: 1 }] }).read('owner'), { status: 404 }); });
test('suspended token holder has no private read', async () => { const f = dmFixture({ suspended: true }); assert.equal((await f.read('peer')).responseHandled, true); });
test('report evidence has no generic owner preview', async () => { await assert.rejects(fixture().read('owner', object('report-evidence')), { status: 404 }); });
test('legacy message is classified private and local replacement suppresses old media', async () => {
  const f = fixture({ imported: { 'supabase_import.messages': [{ id: 'old', chat_id: 'chat', sender_id: 'owner', media_url: object().public_url }] }, localOverrides: [{ id: 'old', chat_id: 'chat', sender_id: 'owner', media_url: 'different' }], chats: [{ id: 'chat', participants: ['owner','peer'] }] });
  await assert.rejects(f.read('peer'), { status: 404 });
});
test('reference fanout overflow fails closed without silent partial grant', async () => { const f = dmFixture({ dm: Array.from({ length: 65 }, (_, id) => ({ id, access_kind: 'dm', sender_id: 'owner', chat_id: 'chat' })) }); await assert.rejects(f.read('peer'), { status: 503, code: 'STORAGE_REFERENCE_LIMIT' }); });
test('SQL failure rolls back and releases owned connection', async () => { const f = fixture({ failSql: 'fallback_storage_private_history' }); await assert.rejects(f.read('owner'), /DB_TEST_FAILURE/); assert.equal(f.counts().released, 1); assert.ok(f.queries.some(entry => entry.sql === 'ROLLBACK')); });
test('strict candidate does not infer public policy from bucket alone', async () => { await assert.rejects(fixture().read('outsider', object('videos')), { status: 404 }); });
test('explicit compatibility flag is recorded as unverified scope', async () => { const f = fixture({ compatibility: true }); assert.equal((await f.read(null, object('videos'))).scope, 'compatibility-public-unverified'); });


const oldRoomObject = (bucket='groups') => ({...object(bucket),created_at:new Date(NOW-24*60*60*1000).toISOString()});
const avatarRoom = (bucket='groups',extra={}) => ({id:'avatar-room',room_kind:bucket==='groups'?'group':'community',creator_id:'legacy-owner',is_public:false,is_deleted:false,image:oldRoomObject(bucket).public_url,...extra});
test('private group creator and current member retain avatar access after upload TTL',async()=>{
  for(const actor of ['owner','peer']) {
    const f=fixture({collectionRooms:[avatarRoom()],members:actor==='peer'?[{user_id:'legacy-peer',is_deleted:false}]:[]});
    assert.equal((await f.read(actor,oldRoomObject())).scope,'group-avatar');
    assert.equal(f.headers['Cache-Control'],'private, no-store');
  }
});
test('private community avatar admits its current member without inferred public visibility',async()=>{
  const f=fixture({collectionRooms:[avatarRoom('communities',{is_public:undefined})],members:[{user_id:'peer',is_deleted:false}]});
  assert.equal((await f.read('peer',oldRoomObject('communities'))).scope,'community-avatar');
});
test('private room avatar denies unrelated and anonymous readers after TTL',async()=>{
  for(const bucket of ['groups','communities']) {
    const f=fixture({collectionRooms:[avatarRoom(bucket)]});
    await assert.rejects(f.read('outsider',oldRoomObject(bucket)),{status:404});
    assert.equal((await f.read(null,oldRoomObject(bucket))).allowed,false);
  }
});
test('missing community visibility does not publish its avatar to outsiders',async()=>{
  const f=fixture({collectionRooms:[avatarRoom('communities',{is_public:undefined})]});
  await assert.rejects(f.read('outsider',oldRoomObject('communities')),{status:404});
});
test('removed creator and member cannot recover a private room avatar',async()=>{
  for(const actor of ['owner','peer']) {
    const f=fixture({collectionRooms:[avatarRoom()],members:[{user_id:actor,is_deleted:true}]});
    await assert.rejects(f.read(actor,oldRoomObject()),{status:404});
  }
});
test('room avatar deletion and malformed tombstones are denied',async()=>{
  for(const tombstone of [true,'true',1,'unknown']) {
    const f=fixture({collectionRooms:[avatarRoom('groups',{is_deleted:tombstone})]});
    await assert.rejects(f.read('owner',oldRoomObject()),{status:404});
  }
});
test('a room URL cannot grant access to an unrelated upload owner',async()=>{
  const f=fixture({collectionRooms:[avatarRoom('groups',{creator_id:'outsider'})],members:[{user_id:'peer',is_deleted:false}]});
  await assert.rejects(f.read('peer',oldRoomObject()),{status:404});
});
test('private room avatar cannot override earlier private message history',async()=>{
  const f=fixture({history:[{one:1}],collectionRooms:[avatarRoom()]});
  await assert.rejects(f.read('owner',oldRoomObject()),{status:404});
});
test('private room avatar cannot override an inaccessible message binding',async()=>{
  const f=fixture({collectionRooms:[avatarRoom()],dm:[{id:'private-message',access_kind:'dm',sender_id:'owner',chat_id:'missing',deleted_for_all:true}]});
  await assert.rejects(f.read('owner',oldRoomObject()),{status:404});
});
test('Range and ETag requests still enforce private room avatar membership',async()=>{
  for(const headers of [{range:'bytes=0-9'},{'if-none-match':'"copied"'}]) {
    const f=fixture({collectionRooms:[avatarRoom()]});
    await assert.rejects(f.read('outsider',oldRoomObject(),{headers}),{status:404});
  }
});
test('current private imported group creator retains its avatar',async()=>{
  const f=fixture({imported:{'supabase_import.groups':[avatarRoom()]}});
  assert.equal((await f.read('owner',oldRoomObject())).scope,'group-avatar');
});
test('current local photo replacement or deletion defeats a stale import',async()=>{
  for(const change of [{is_deleted:true},{image:ORIGIN+'/storage/v1/object/public/groups/owner/replacement.png'}]) {
    const f=fixture({collectionRooms:[avatarRoom('groups',change)],imported:{'supabase_import.groups':[avatarRoom()]}});
    await assert.rejects(f.read('owner',oldRoomObject()),{status:404});
  }
});
test('avatar candidate overflow fails closed',async()=>{
  const f=fixture({collectionRooms:Array.from({length:65},(_,i)=>avatarRoom('groups',{id:'room-'+i}))});
  await assert.rejects(f.read('owner',oldRoomObject()),{status:503,code:'STORAGE_REFERENCE_LIMIT'});
});
test('suspended session does not read a current private room avatar',async()=>{
  const f=fixture({suspended:true,collectionRooms:[avatarRoom()]});
  assert.equal((await f.read('owner',oldRoomObject())).allowed,false);
});

for (const [at,expected] of [['2026-09-15T00:00:00Z',true],['2026-10-02T00:00:00Z',false],['2026-08-15T00:00:00Z',false]]) {
 test('former member attachment boundary '+at,async()=>{
  const f=fixture({rooms:[{id:'m',access_kind:'group',sender_id:'owner',room_id:'g',created_at:at}],collectionRooms:[{id:'g',creator_id:'owner'}],members:[{user_id:'peer',is_deleted:true}],
    archives:[{user_id:'peer',departed_at:'2026-10-01T00:00:00Z',hidden:false,read_ranges:[{from:'2026-09-01T00:00:00Z',until:'2026-10-01T00:00:00Z'}]}]})
  if(expected)assert.equal((await f.read('peer',object('group-media'))).scope,'group')
  else await assert.rejects(f.read('peer',object('group-media')),{status:404})
 })
}
