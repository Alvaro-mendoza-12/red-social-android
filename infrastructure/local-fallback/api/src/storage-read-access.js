const { loadCollectionMembershipState, collectionHistoryAllows } = require('./collection-membership-policy')
'use strict';

// Call this guard after looking up the object, before scan/variant/fs/ETag/Range
// or X-Accel delivery. URL signatures authorize neither a user nor a message.
const MAX_REFERENCES = 64;
const PREVIEW_TTL_MS = 15 * 60 * 1000;
const PRIVATE_OR_MIXED_BUCKETS = new Set(['chat-media', 'group-media', 'community-media', 'group-audio', 'groups', 'communities', 'thumbnails']);
const IMPORTED_MESSAGE_TABLES = Object.freeze({ messages: 'dm', group_messages: 'group', community_messages: 'community', proximity_messages: 'proximity' });
function norm(value) { return typeof value === 'string' ? value.trim().toLowerCase() : ''; }
function deny(code = 'STORAGE_READ_DENIED', status = 404) { return Object.assign(new Error(code), { code, status }); }
function list(value) { if (Array.isArray(value)) return value; if (typeof value === 'string') { try { const parsed = JSON.parse(value); return Array.isArray(parsed) ? parsed : []; } catch {} } return []; }
function bool(value) { return value === true || value === 'true'; }
function aliases(user) { return [...new Set([norm(user?.id), norm(user?.supabase_user_id)].filter(Boolean))]; }
function trustedOrigins(values) {
  if (!Array.isArray(values) || !values.length || values.length > 8) throw new Error('STORAGE_READ_ORIGINS_REQUIRED');
  return [...new Set(values.map(value => {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('STORAGE_READ_ORIGIN_INVALID');
    return url.origin;
  }))];
}
function parseObjectReference(rawUrl, origins) {
  if (typeof rawUrl !== 'string' || rawUrl.length > 4096) return null;
  const lexical=rawUrl.match(/^https:\/\/([^/?#]+)([^?#]*)/i);
  if(!lexical||/[^\x21-\x7e]|[%\\]/.test(lexical[1]))return null;
  // URL() normalizes dot segments before exposing pathname. Reject them in
  // the original spelling so SQL classification and HTTP admission agree.
  try { if(lexical[2].split('/').some(segment=>['.','..'].includes(decodeURIComponent(segment))))return null; } catch {return null;}
  let url; try { url = new URL(rawUrl); } catch { return null; }
  if (url.protocol !== 'https:' || url.username || url.password || !origins.includes(url.origin) || url.hash) return null;
  const match = url.pathname.match(/^\/storage\/v1\/object\/(?:(public|sign)\/)?([^/]+)\/(.+)$/);
  if (!match) return null;
  try {
    const bucket = decodeURIComponent(match[2]);
    const segments = match[3].split('/').map(segment => {
      const decoded = decodeURIComponent(segment);
      if (!decoded || decoded === '.' || decoded === '..' || /[\/\\\x00-\x1f\x7f]/.test(decoded) || /%[0-9a-f]{2}/i.test(decoded)) throw new Error('INVALID_SEGMENT');
      return decoded;
    });
    if (!/^[a-zA-Z0-9_-]{1,128}$/.test(bucket)) return null;
    return { bucket, name: segments.join('/') };
  } catch { return null; }
}
function objectReferenceUrls(row, origins) {
  const bucket = String(row?.bucket_id || ''), name = String(row?.name || '');
  const encodedPath = name.split('/').map(encodeURIComponent).join('/');
  const urls = origins.flatMap(origin => ['public/', 'sign/', ''].map(prefix => `${origin}/storage/v1/object/${prefix}${encodeURIComponent(bucket)}/${encodedPath}`));
  const recorded = parseObjectReference(row?.public_url, origins);
  if (recorded && recorded.bucket === bucket && recorded.name === name) urls.push(new URL(row.public_url).origin + new URL(row.public_url).pathname);
  // A malformed path must never be interpreted as a different object.
  if (!urls.every(url => { const ref = parseObjectReference(url, origins); return ref?.bucket === bucket && ref?.name === name; })) throw deny();
  return [...new Set(urls)];
}
function messageEligible(row, viewerIds, {allowViewOnce=false}={}) {
  if (bool(row.deleted_for_all) || bool(row.deletedForAll) || bool(row.is_deleted) || bool(row.deleted)) return false;
  if (list(row.deleted_by ?? row.deletedBy).map(norm).some(id => viewerIds.includes(id))) return false;
  // There is no durable, atomic one-view media lease in the existing schema.
  // Fail closed until that separate capability is implemented and verified.
  if (!allowViewOnce && (norm(row.content_type ?? row.contentType).startsWith('view_once') || bool(row.is_view_once) || bool(row.viewOnce))) return false;
  return true;
}
function privateCacheHeaders(res) {
  res.setHeader('Cache-Control', 'private, no-store');
  res.setHeader('Vary', 'Authorization');
  res.setHeader('X-Content-Type-Options', 'nosniff');
}
async function importedRows(db, table, predicate, params) {
  // table is selected exclusively from constants in this module.
  const exists = await db.query('SELECT to_regclass($1) AS relation', [`supabase_import.${table}`]);
  if (!exists.rows[0]?.relation) return [];
  if (predicate.includes("to_jsonb(t)->>'media_url'")) {
    const columns = await db.query(`SELECT a.attname AS name, a.atttypid IN ('text'::regtype,'varchar'::regtype) AS text_type FROM pg_attribute a WHERE a.attrelid=$1::regclass AND a.attnum>0 AND NOT a.attisdropped AND a.attname=ANY($2::text[])`, [`supabase_import.${table}`, ['media_url', 'thumbnail_url']]);
    for (const field of ['media_url', 'thumbnail_url']) {
      const column = columns.rows.find(entry => entry.name === field);
      if (column && !column.text_type) throw deny('STORAGE_IMPORT_REFERENCE_SCHEMA_UNSUPPORTED', 503);
      predicate = predicate.replaceAll(`COALESCE(to_jsonb(t)->>'${field}','')`, column ? `COALESCE(t."${field}",'')` : "''");
    }
  }
  const result = await db.query(`SELECT to_jsonb(t) AS row FROM supabase_import."${table}" t WHERE ${predicate} LIMIT ${MAX_REFERENCES + 1}`, params);
  if (result.rows.length > MAX_REFERENCES) throw deny('STORAGE_REFERENCE_LIMIT', 503);
  return result.rows.map(entry => entry.row);
}
function rowReferencesObject(message,object,origins) {
  return [message.media_url,message.thumbnail_url].some(raw=> {
    const ref=parseObjectReference(raw,origins);
    return ref?.bucket===object.bucket_id&&ref?.name===object.name;
  });
}
async function referenceRows(db,object,origins) {
  const bindings=await db.query(`SELECT source_schema,source_table,message_id FROM public.fallback_storage_private_bindings
    WHERE bucket_id=$1 AND object_name=$2 LIMIT ${MAX_REFERENCES+1}`,[object.bucket_id,object.name]);
  if(bindings.rows.length>MAX_REFERENCES)throw deny('STORAGE_REFERENCE_LIMIT',503);
  const local=[],legacy=[];
  const localKinds={fallback_messages:'dm',fallback_room_messages:null,fallback_random_chat_messages:'random'};
  if(bindings.rows.some(binding=>binding.source_schema==='public'?!Object.hasOwn(localKinds,binding.source_table):binding.source_schema!=='supabase_import'||!Object.hasOwn(IMPORTED_MESSAGE_TABLES,binding.source_table)))throw deny('STORAGE_REFERENCE_SCHEMA_UNSUPPORTED',503);
  for(const [table,kind]of Object.entries(localKinds)) {
    const ids=bindings.rows.filter(row=>row.source_schema==='public'&&row.source_table===table).map(row=>norm(row.message_id));
    if(!ids.length)continue;
    const current=await db.query(`SELECT * FROM ${table} WHERE lower(id::text)=ANY($1::text[]) LIMIT ${MAX_REFERENCES+1}`,[ids]);
    if(current.rows.length>MAX_REFERENCES)throw deny('STORAGE_REFERENCE_LIMIT',503);
    local.push(...current.rows.filter(row=>rowReferencesObject(row,object,origins)).map(row=>({...row,access_kind:kind||row.room_kind})));
  }
  for(const [table,kind]of Object.entries(IMPORTED_MESSAGE_TABLES)) {
    const ids=bindings.rows.filter(row=>row.source_schema==='supabase_import'&&row.source_table===table).map(row=>norm(row.message_id));
    if(!ids.length)continue;
    const current=await db.query(`SELECT to_jsonb(t) AS row FROM supabase_import."${table}" t WHERE lower(t.id::text)=ANY($1::text[]) LIMIT ${MAX_REFERENCES+1}`,[ids]);
    if(current.rows.length>MAX_REFERENCES)throw deny('STORAGE_REFERENCE_LIMIT',503);
    legacy.push(...current.rows.map(entry=>entry.row).filter(row=>rowReferencesObject(row,object,origins)).map(row=>({...row,access_kind:kind,imported:true})));
  }
  if(local.length+legacy.length>MAX_REFERENCES)throw deny('STORAGE_REFERENCE_LIMIT',503);
  const merged=new Map(legacy.map(row=>[`${row.access_kind}:${norm(row.id)}`,row]));
  for(const row of local)merged.set(`${row.access_kind}:${norm(row.id)}`,row);
  // A current local replacement/tombstone overrides an imported row even when
  // its binding points at a different object. Never trust an index alone.
  for(const kind of ['dm','group','community']) {
    const ids=legacy.filter(row=>row.access_kind===kind).map(row=>norm(row.id)).filter(Boolean);
    if(!ids.length)continue;
    const table=kind==='dm'?'fallback_messages':'fallback_room_messages';
    const current=await db.query(`SELECT * FROM ${table} WHERE lower(id::text)=ANY($1::text[])${kind==='dm'?'':' AND room_kind=$2'}`,kind==='dm'?[ids]:[ids,kind]);
    for(const row of current.rows)merged.set(`${kind}:${norm(row.id)}`,{...row,access_kind:kind,deleted_for_all:!rowReferencesObject(row,object,origins)||row.deleted_for_all});
  }
  return {references:[...merged.values()],bindingPresent:bindings.rows.length>0};
}
async function canonicalUser(db, rawId) {
  const value = norm(rawId);
  if (!value) return null;
  const result = await db.query(`SELECT id::text, supabase_user_id::text FROM fallback_users WHERE lower(id::text)=$1 OR lower(COALESCE(supabase_user_id::text,''))=$1 LIMIT 1`, [value]);
  return result.rows[0] || null;
}
async function pairBlocked(db, viewerIds, peerIds) {
  if (!peerIds.length) return false;
  const result = await db.query(`SELECT 1 FROM fallback_user_blocks WHERE (lower(blocker_id)=ANY($1::text[]) AND lower(blocked_id)=ANY($2::text[])) OR (lower(blocker_id)=ANY($2::text[]) AND lower(blocked_id)=ANY($1::text[])) LIMIT 1`, [viewerIds, peerIds]);
  return result.rows.length > 0;
}
async function roomAccess(db, kind, roomId, viewerIds, {allowPublicPreview=true}={}) {
  const id = norm(roomId); if (!id) return false;
  const local = await db.query(`SELECT * FROM fallback_collection_rooms WHERE room_kind=$1 AND lower(id::text)=$2 LIMIT 1`, [kind, id]);
  const legacy = local.rows.length ? [] : await importedRows(db, kind === 'group' ? 'groups' : 'communities', `lower(COALESCE(to_jsonb(t)->>'id',''))=$1`, [id]);
  const room = local.rows[0] || legacy[0];
  if (!room || bool(room.is_deleted) || bool(room.deleted)) return false;
  // A deleted membership beats an imported membership, including a creator.
  const members = await db.query(`SELECT user_id, is_deleted FROM fallback_collection_members WHERE room_kind=$1 AND lower(room_id)=$2 AND lower(user_id)=ANY($3::text[])`, [kind, id, viewerIds]);
  if (members.rows.some(member => bool(member.is_deleted))) {
    const state = await loadCollectionMembershipState(db, kind, id, viewerIds);
    return state.history.canReadHistory === true;
  }
  if (members.rows.some(member => !bool(member.is_deleted))) return true;
  if (viewerIds.includes(norm(room.creator_id ?? room.owner_id ?? room.user_id))) return true;
  // Existing room endpoints allow an authenticated preview of current public
  // rooms. Preserve that scope while an explicit removal remains a denial.
  const publicValue = room.is_public ?? room.public;
  if (allowPublicPreview && (publicValue === undefined ? kind === 'community' : bool(publicValue))) return true;
  const imported = await importedRows(db, kind === 'group' ? 'group_members' : 'community_members', `lower(COALESCE(to_jsonb(t)->>'${kind}_id',to_jsonb(t)->>'room_id',''))=$1 AND lower(COALESCE(to_jsonb(t)->>'user_id',to_jsonb(t)->>'profile_id',''))=ANY($2::text[])`, [id, viewerIds]);
  return imported.some(member => !bool(member.is_deleted) && !bool(member.deleted));
}
async function dmAccess(db, message, viewerIds) {
  const chatId = norm(message.chat_id ?? message.chatId);
  if (!chatId) return false;
  const result = await db.query(`SELECT * FROM fallback_chats WHERE lower(id::text)=$1 LIMIT 1`, [chatId]);
  const imported = result.rows.length ? [] : await importedRows(db, 'chats', `lower(COALESCE(to_jsonb(t)->>'id',''))=$1`, [chatId]);
  const chat = result.rows[0] || imported[0];
  if (!chat || bool(chat.is_deleted) || bool(chat.deleted)) return false;
  const participants = await db.query(`SELECT user_id,cleared_at FROM fallback_chat_participants WHERE lower(chat_id::text)=$1`, [chatId]);
  const oldParticipants = await importedRows(db, 'chat_participants', `lower(COALESCE(to_jsonb(t)->>'chat_id',to_jsonb(t)->>'chatId',''))=$1`, [chatId]);
  const ids = [...new Set([...list(chat.participants), ...participants.rows.map(row => row.user_id), ...oldParticipants.filter(row => !bool(row.is_deleted) && !bool(row.deleted)).map(row => row.user_id ?? row.profile_id)].map(norm).filter(Boolean))];
  if (!viewerIds.some(id => ids.includes(id))) return false;
  const messageTime = new Date(message.created_at).getTime();
  for (const participant of [...oldParticipants, ...participants.rows]) {
    if (!viewerIds.includes(norm(participant.user_id ?? participant.profile_id)) || !participant.cleared_at) continue;
    const clearTime = new Date(participant.cleared_at).getTime();
    if (!Number.isFinite(messageTime) || !Number.isFinite(clearTime) || messageTime <= clearTime) return false;
  }
  for (const peerId of ids.filter(id => !viewerIds.includes(id))) {
    const peer = await canonicalUser(db, peerId);
    if (!peer || await pairBlocked(db, viewerIds, aliases(peer))) return false;
  }
  return true;
}
async function referenceAllows(db, message, viewerIds, options={}) {
  if (!messageEligible(message, viewerIds, options)) return false;
  const sender = await canonicalUser(db, message.sender_id ?? message.user_id);
  if (!sender || await pairBlocked(db, viewerIds, aliases(sender))) return false;
  if (message.access_kind === 'dm') return dmAccess(db, message, viewerIds);
  if (message.access_kind === 'group' || message.access_kind === 'community') {
    const roomId = message.room_id ?? message[`${message.access_kind}_id`];
    if (message.access_kind === 'community' && message.channel_id) {
      const channels = await db.query('SELECT community_id,is_deleted FROM fallback_community_channels WHERE lower(id::text)=$1 LIMIT 1', [norm(message.channel_id)]);
      if (!channels.rows[0] || bool(channels.rows[0].is_deleted) || norm(channels.rows[0].community_id) !== norm(roomId)) return false;
    }
    if (!await roomAccess(db, message.access_kind, roomId, viewerIds, options)) return false;
    const state = await loadCollectionMembershipState(db, message.access_kind, roomId, viewerIds);
    return collectionHistoryAllows(state.history, message);
  }
  if (message.access_kind === 'random') {
    const sessions = await db.query(`SELECT user_a, user_b, status FROM fallback_random_chat_sessions WHERE lower(id::text)=$1 LIMIT 1`, [norm(message.session_id)]);
    const session = sessions.rows[0];
    // Ending a conversation disables new sends; its existing history endpoint
    // still admits either participant. Keep the same read scope for multimedia.
    if (!session || !['active','ended'].includes(session.status) || !viewerIds.some(id => [norm(session.user_a), norm(session.user_b)].includes(id))) return false;
    const peer = await canonicalUser(db, viewerIds.includes(norm(session.user_a)) ? session.user_b : session.user_a);
    return Boolean(peer && !(await pairBlocked(db, viewerIds, aliases(peer))));
  }
  // Legacy proximity schemas do not exist in the fallback route inventory.
  // Their objects are classified private but not admitted by an assumed path.
  return false;
}
async function publicRoomAvatar(db, urls, objectOwnerId) {
  const result = await db.query(`SELECT * FROM fallback_collection_rooms WHERE split_part(COALESCE(image,''),'?',1)=ANY($1::text[]) LIMIT ${MAX_REFERENCES + 1}`, [urls]);
  if (result.rows.length > MAX_REFERENCES) throw deny('STORAGE_REFERENCE_LIMIT', 503);
  // Imported avatars stay fail closed until imported room/deletion overrides
  // are covered by backfill, avoiding stale public policy resurrection.
  for (const row of result.rows) {
    if (bool(row.is_deleted) || !bool(row.is_public)) continue;
    const creator = await canonicalUser(db, row.creator_id);
    // An arbitrary room image URL must not publish someone else's upload.
    // Admin avatar updates need a recorded publisher binding before allowing
    // the public optimization for an uploader different from the creator.
    if (creator && norm(creator.id) === norm(objectOwnerId)) return true;
  }
  return false;
}
// A room image is durable after the short upload preview, but only for its
// current audience. An image URL is not proof that another uploader consented.
async function currentRoomAvatar(db, object, urls, origins, viewerIds) {
  const kind = object.bucket_id === 'groups' ? 'group' : object.bucket_id === 'communities' ? 'community' : null;
  if (!kind) return { known: false, allowed: false };
  const pointsHere = row => {
    const ref = parseObjectReference(row?.image, origins);
    return ref?.bucket === object.bucket_id && ref?.name === object.name;
  };
  const deleted = row => [row?.is_deleted, row?.deleted].some(value => value != null && value !== false && value !== 'false');
  const local = await db.query(`SELECT * FROM fallback_collection_rooms WHERE room_kind=$1 AND split_part(COALESCE(image,''),'?',1)=ANY($2::text[]) LIMIT ${MAX_REFERENCES + 1}`, [kind, urls]);
  if (local.rows.length > MAX_REFERENCES) throw deny('STORAGE_REFERENCE_LIMIT', 503);
  const imported = await importedRows(db, kind === 'group' ? 'groups' : 'communities', `split_part(COALESCE(to_jsonb(t)->>'image',''),'?',1)=ANY($1::text[])`, [urls]);
  if (local.rows.length + imported.length > MAX_REFERENCES) throw deny('STORAGE_REFERENCE_LIMIT', 503);
  const candidates = local.rows.filter(row => row.room_kind === kind && pointsHere(row));
  let known = candidates.length > 0;
  for (const row of imported.filter(pointsHere)) {
    known = true;
    // A local deletion or changed photo must override a still-existing import.
    const current = await db.query('SELECT * FROM fallback_collection_rooms WHERE room_kind=$1 AND lower(id::text)=$2 LIMIT 1', [kind, norm(row.id)]);
    candidates.push(current.rows[0] || row);
  }
  for (const room of candidates) {
    if (!norm(room.id) || deleted(room) || !pointsHere(room)) continue;
    const creator = await canonicalUser(db, room.creator_id ?? room.owner_id ?? room.user_id);
    if (!creator || norm(creator.id) !== norm(object.owner_id)) continue;
    // Missing legacy community visibility is not an avatar publication grant.
    if (await roomAccess(db, kind, room.id, viewerIds, { allowPublicPreview: bool(room.is_public ?? room.public) })) {
      return { known: true, allowed: true, scope: `${kind}-avatar` };
    }
  }
  return { known, allowed: false };
}
function createStorageObjectReadGuard({ pool, authRequired, origins, now = () => Date.now(), allowUnreferencedPublicBuckets = false,
  guestJwtSecret = process.env.JWT_SECRET, guestJwtIssuer = process.env.SERVICE_NAME || 'conectate-local-fallback' }) {
  const allowedOrigins = trustedOrigins(origins);
  if (!pool || typeof authRequired !== 'function') throw new Error('STORAGE_READ_AUTHORIZER_REQUIRED');
  return async function guard(req, res, row) {
    const credentialPresent = Boolean(req.headers?.authorization || req.auth?.sub);
    const guest = require('./guest-storage-read').verifiedGuestStorageIdentity(req,
      {secret:guestJwtSecret,issuer:guestJwtIssuer,now:now()});
    let authenticated = false, authenticationError;
    async function authenticate() {
      await authRequired(req, res, error => { if (error) authenticationError = error; else authenticated = true; });
      if (authenticationError) throw authenticationError;
    }
    // Middleware itself queries the pool. Authenticate before reserving a
    // transaction client, or concurrent private image reads can exhaust the
    // pool while every reserved client waits for another auth connection.
    if (credentialPresent && !guest) {
      await authenticate();
      if (!authenticated) return { allowed: false, responseHandled: true, private: true };
    }
    const db = await pool.connect();
    let began = false, released = false;
    try {
      await db.query('BEGIN ISOLATION LEVEL REPEATABLE READ'); began = true;
      await db.query("SET LOCAL statement_timeout='3000ms'");
      const urls = objectReferenceUrls(row, allowedOrigins);
      const {references,bindingPresent} = await referenceRows(db, row, allowedOrigins);
      const history = await db.query('SELECT 1 FROM fallback_storage_private_history WHERE bucket_id=$1 AND object_name=$2 LIMIT 1', [row.bucket_id, row.name]);
      const wasPrivate = history.rows.length > 0 || bindingPresent;
      if (guest) {
        privateCacheHeaders(res);
        if (wasPrivate || references.length || req.query?.lease || row.bucket_id === 'report-evidence') throw deny();
        const content = await require('./storage-content-read').loadContentReferences(db,row,allowedOrigins);
        for (const reference of content.references) {
          if (await require('./guest-storage-read').guestPublicContentAllows(db,reference,row,allowedOrigins,now())) {
            await db.query('COMMIT'); began=false;
            return {allowed:true,private:true,scope:`guest-content-${reference.access_kind}`};
          }
        }
        throw deny();
      }
      const publicAvatar = !wasPrivate && !references.length && ['groups', 'communities'].includes(row.bucket_id) && await publicRoomAvatar(db, urls, row.owner_id);
      const compatibilityPublic = !wasPrivate && !references.length && !PRIVATE_OR_MIXED_BUCKETS.has(row.bucket_id) && row.bucket_id !== 'report-evidence' && allowUnreferencedPublicBuckets;
      if (publicAvatar || compatibilityPublic) {
        await db.query('COMMIT'); began = false;
        return { allowed: true, private: false, scope: publicAvatar ? 'public-room-avatar' : 'compatibility-public-unverified' };
      }
      privateCacheHeaders(res);
      // authRequired verifies signature, session revocation and suspension.
      // Await its Promise; false means middleware already responded.
      if (!authenticated) {
        await db.query('ROLLBACK'); began = false;
        db.release(); released = true;
        await authenticate();
        // An anonymous storage route is expected to have no other credentials.
        // A middleware that authenticates cookies must retry this guard with
        // the authenticated subject; never reuse the released transaction.
        return { allowed: false, responseHandled: !authenticated, private: true, retryAuthenticated: authenticated };
      }
      const user = await canonicalUser(db, req.auth?.sub);
      if (!user || norm(user.id) !== norm(req.auth?.sub)) throw deny();
      const viewerIds = aliases(user);
      // Never use storage ownership as an override for a message reference.
      if (references.length) {
        for (const reference of references) {
          if (require('./storage-view-once').viewOnceType(reference)) {
            if (typeof req.query?.lease==='string' && await referenceAllows(db,reference,viewerIds,{allowViewOnce:true,allowPublicPreview:false})) {
              const admission=await require('./storage-view-once').consumeViewOnceDelivery({db,req,reference,object:row,user,now});
              await db.query('COMMIT'); began=false;
              return admission;
            }
            continue;
          }
          if (req.query?.lease) continue;
          if (await referenceAllows(db, reference, viewerIds)) {
          await db.query('COMMIT'); began = false;
          return { allowed: true, private: true, scope: reference.access_kind };
          }
        }
        throw deny();
      }
      // Indexed bindings are re-read against current rows and privacy on every
      // request. A stale URL, deletion or unrelated publisher cannot grant access.
      const content = await require('./storage-content-read').loadContentReferences(db,row,allowedOrigins);
      if (!wasPrivate && content.references.length) {
        for (const reference of content.references) {
          if (await require('./storage-content-read').contentReferenceAllows(db,reference,row,allowedOrigins,viewerIds,now())) {
            await db.query('COMMIT'); began = false;
            return { allowed:true, private:true, scope:`content-${reference.access_kind}` };
          }
        }
        // A separately approved community track may also be used by an
        // expired Moment. Its public catalog permission is independent.
      }
      // Message/private/content history retains precedence over room avatars.
      const avatar = !wasPrivate && !content.known
        ? await currentRoomAvatar(db,row,urls,allowedOrigins,viewerIds)
        : {known:false,allowed:false};
      if (avatar.known) {
        if (!avatar.allowed) throw deny();
        await db.query('COMMIT'); began = false;
        return {allowed:true,private:true,scope:avatar.scope};
      }
      // A current catalog row grants durable delivery only when its creator
      // owns the object. Historical private/content bindings retain precedence.
      const catalog = !wasPrivate && (!content.known || ['community-music','thumbnails'].includes(row.bucket_id))
        ? await require('./storage-catalog-read').loadCatalogReferences(db,row,allowedOrigins)
        : {known:false,references:[]};
      if (catalog.known) {
        for (const reference of catalog.references) {
          // Only a separately owned music publication is independent of an
          // expired content binding. Sticker overrides retain earlier rules.
          if (content.known && reference.kind !== 'music') continue;
          if (await require('./storage-catalog-read').catalogReferenceAllows(db,reference,row,allowedOrigins,viewerIds,now())) {
            await db.query('COMMIT'); began = false;
            return {allowed:true,private:true,scope:reference.kind==='music'?'catalog-music':'catalog-sticker'};
          }
        }
        throw deny();
      }
      if (content.known) throw deny();
      const createdAt = new Date(row.created_at).getTime();
      const recent = Number.isFinite(createdAt) && createdAt <= now() && now() - createdAt <= PREVIEW_TTL_MS;
      if (!wasPrivate && !content.known && row.bucket_id !== 'report-evidence' && norm(row.owner_id) === norm(user.id) && recent) {
        await db.query('COMMIT'); began = false;
        return { allowed: true, private: true, scope: 'owner-upload-preview' };
      }
      throw deny();
    } catch (error) {
      if (began) await db.query('ROLLBACK').catch(() => {});
      // A competing read may have locked the same one-use lease after this
      // repeatable-read snapshot. Treat its SQL serialization conflict as a
      // consumed capability, never expose a server error or retry the read.
      if (req.query?.lease && error?.code === '40001') throw deny();
      throw error;
    } finally { if (!released) db.release(); }
  };
}

module.exports = { MAX_REFERENCES, PREVIEW_TTL_MS, PRIVATE_OR_MIXED_BUCKETS, trustedOrigins, parseObjectReference, objectReferenceUrls, messageEligible, privateCacheHeaders, referenceAllows, createStorageObjectReadGuard };
