'use strict'

const norm = value => String(value ?? '').trim().toLowerCase()
const error = (message, status = 409) => Object.assign(new Error(message), { status })
const keyOf = row => String(row.id ?? '').trim() || String(row.uuid ?? '').trim() || `sender_id:${norm(row.sender_id)}|receiver_id:${norm(row.receiver_id)}`

async function ensureFriendRequestState(db) {
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_friend_legacy_requests (
    source TEXT NOT NULL CHECK(source IN ('imported','compat')), request_id TEXT NOT NULL,
    sender_id TEXT NOT NULL, receiver_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('pending','accepted','rejected','cancelled')),
    native_request_id TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(source,request_id))`)
  // This suppresses only legacy snapshots. A new native invitation is never
  // blocked or revived by an old import, even if an import is replayed later.
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_friend_legacy_barriers (
    sender_id TEXT NOT NULL, receiver_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(sender_id,receiver_id))`)
  await db.query(`CREATE OR REPLACE FUNCTION fallback_friend_identity(raw TEXT) RETURNS TEXT
    LANGUAGE plpgsql STABLE AS $$ DECLARE ids TEXT[]; BEGIN
      SELECT array_agg(lower(id::text)) INTO ids
        FROM fallback_users WHERE lower(id::text)=lower(btrim(raw)) OR lower(supabase_user_id::text)=lower(btrim(raw));
      IF cardinality(ids)>1 THEN RAISE EXCEPTION 'FRIEND_IDENTITY_AMBIGUOUS'; END IF;
      RETURN COALESCE(ids[1],lower(btrim(raw)));
    END $$`)
  await db.query(`CREATE OR REPLACE FUNCTION fallback_lock_friend_pair(a TEXT,b TEXT) RETURNS VOID
    LANGUAGE plpgsql AS $$ DECLARE x TEXT; y TEXT; BEGIN
      x:=fallback_friend_identity(a); y:=fallback_friend_identity(b);
      PERFORM pg_advisory_xact_lock(hashtextextended('friend:' || jsonb_build_array(LEAST(x,y),GREATEST(x,y))::text,0));
    END $$`)
  await db.query(`CREATE OR REPLACE FUNCTION fallback_friend_legacy_key(r JSONB) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $$ SELECT COALESCE(NULLIF(btrim(r->>'id'),''),NULLIF(btrim(r->>'uuid'),''),
      'sender_id:' || lower(btrim(r->>'sender_id')) || '|receiver_id:' || lower(btrim(r->>'receiver_id'))) $$`)
  await db.query(`CREATE OR REPLACE FUNCTION fallback_friend_legacy_status(origin TEXT,r JSONB) RETURNS TEXT
    LANGUAGE plpgsql STABLE AS $$ DECLARE a TEXT; b TEXT; decision RECORD; BEGIN
      a:=fallback_friend_identity(r->>'sender_id'); b:=fallback_friend_identity(r->>'receiver_id');
      IF EXISTS(SELECT 1 FROM fallback_friend_legacy_barriers WHERE fallback_friend_identity(sender_id)=a AND fallback_friend_identity(receiver_id)=b) THEN RETURN 'cancelled'; END IF;
      SELECT * INTO decision FROM fallback_friend_legacy_requests WHERE source=origin AND request_id=fallback_friend_legacy_key(r);
      IF FOUND THEN
        IF fallback_friend_identity(decision.sender_id)<>a OR fallback_friend_identity(decision.receiver_id)<>b THEN RETURN 'cancelled'; END IF;
        RETURN decision.status;
      END IF;
      RETURN lower(r->>'status');
    END $$`)
}

async function transaction(pool, work) {
  // PGlite owns its transaction queue. Production uses a dedicated pg client.
  if (typeof pool.transaction === 'function' && typeof pool.connect !== 'function') return pool.transaction(work)
  const db = await pool.connect()
  try { await db.query('BEGIN'); const result = await work(db); await db.query('COMMIT'); return result }
  catch (err) { await db.query('ROLLBACK').catch(() => {}); throw err }
  finally { db.release() }
}
async function withPair(pool, sender, receiver, work) {
  if (!norm(sender) || !norm(receiver)) throw error('FRIEND_PARTICIPANTS_REQUIRED',400)
  return transaction(pool, async db => {
    // Match administrative purge table order before taking any write locks.
    // RowExclusive locks are compatible between ordinary friendship requests.
    await db.query('LOCK TABLE fallback_friend_legacy_barriers, fallback_friend_legacy_requests, fallback_friends IN ROW EXCLUSIVE MODE')
    await db.query('SELECT fallback_lock_friend_pair($1,$2)', [sender, receiver])
    const { rows } = await db.query('SELECT fallback_friend_identity($1) AS sender, fallback_friend_identity($2) AS receiver',[sender,receiver])
    return work(db, rows[0].sender, rows[0].receiver)
  })
}
async function visibleLegacyFriends(db, source, rows, {includeMaterialized=false}={}) {
  if (!rows.length) return rows
  const result = await db.query(`SELECT ordinality::int AS position, fallback_friend_legacy_status($1,value) AS status,
    EXISTS(SELECT 1 FROM fallback_friend_legacy_requests r JOIN fallback_friends n ON n.id::text=r.native_request_id
      WHERE r.source=$1 AND r.request_id=fallback_friend_legacy_key(value)) AS materialized
    FROM jsonb_array_elements($2::jsonb) WITH ORDINALITY`,[source,JSON.stringify(rows)])
  return result.rows.filter(r=>r.status!=='cancelled' && (includeMaterialized || !r.materialized)).map(r=>({...rows[r.position-1],status:r.status}))
}
async function loadSource(db, source, id) {
  let result
  if (source === 'imported') {
    const exists = await db.query("SELECT to_regclass('supabase_import.friends') AS relation")
    if (!exists.rows[0]?.relation) return null
    result = await db.query('SELECT to_jsonb(t) AS row FROM supabase_import.friends t WHERE fallback_friend_legacy_key(to_jsonb(t))=$1 LIMIT 2',[id])
  } else if (source === 'compat') {
    result = await db.query("SELECT row FROM fallback_compat_rows WHERE table_name='friends' AND fallback_friend_legacy_key(row)=$1 LIMIT 2",[id])
  } else throw error('FRIEND_SOURCE_INVALID',400)
  if (result.rows.length>1) throw error('FRIEND_SOURCE_AMBIGUOUS')
  return result.rows[0]?.row || null
}
async function legacyContext(db, source, expected, actorIds, receiverOnly, sender, receiver, outgoingOnly=false) {
  const row=await loadSource(db,source,keyOf(expected))
  if (!row) throw error('REQUEST_NOT_FOUND',404)
  const {rows:pair}=await db.query('SELECT fallback_friend_identity($1) AS sender, fallback_friend_identity($2) AS receiver',[row.sender_id,row.receiver_id])
  if (pair[0].sender!==sender || pair[0].receiver!==receiver) throw error('FRIEND_SOURCE_CHANGED')
  const {rows:actors}=await db.query('SELECT DISTINCT fallback_friend_identity(value) AS id FROM unnest($1::text[]) value',[actorIds])
  if (!actors.some(a=>outgoingOnly ? a.id===sender : a.id===receiver || (!receiverOnly && a.id===sender))) throw error('FRIEND_REQUEST_RESPONSE_FORBIDDEN',403)
  const {rows:states}=await db.query('SELECT fallback_friend_legacy_status($1,$2::jsonb) AS status',[source,JSON.stringify(row)])
  if (states[0].status==='cancelled') throw error('FRIEND_REQUEST_CANCELLED')
  await db.query(`INSERT INTO fallback_friend_legacy_requests(source,request_id,sender_id,receiver_id,status)
    VALUES($1,$2,$3,$4,$5) ON CONFLICT(source,request_id) DO NOTHING`,[source,keyOf(row),sender,receiver,states[0].status])
  const {rows:records}=await db.query('SELECT * FROM fallback_friend_legacy_requests WHERE source=$1 AND request_id=$2 FOR UPDATE',[source,keyOf(row)])
  const record=records[0]
  const {rows:storedPair}=await db.query('SELECT fallback_friend_identity($1) AS sender, fallback_friend_identity($2) AS receiver',[record.sender_id,record.receiver_id])
  record.sender_id=storedPair[0].sender;record.receiver_id=storedPair[0].receiver
  if (record.sender_id!==sender || record.receiver_id!==receiver) throw error('FRIEND_SOURCE_CHANGED')
  return {row,record}
}
async function nativePair(db,sender,receiver) {
  return (await db.query(`SELECT id::text,sender_id,receiver_id,status,created_at FROM fallback_friends
    WHERE fallback_friend_identity(sender_id)=$1 AND fallback_friend_identity(receiver_id)=$2`,[sender,receiver])).rows
}
async function publicPair(db,sender,receiver) {
  const {rows}=await db.query(`SELECT
    COALESCE((SELECT NULLIF(supabase_user_id::text,'') FROM fallback_users WHERE id::text=$1),$1) AS sender,
    COALESCE((SELECT NULLIF(supabase_user_id::text,'') FROM fallback_users WHERE id::text=$2),$2) AS receiver`,[sender,receiver])
  return rows[0]
}
async function answerLegacyFriend(pool,source,row,status,actorIds) {
  if (!['accepted','rejected'].includes(status)) throw error('INVALID_STATUS',400)
  return withPair(pool,row.sender_id,row.receiver_id,async(db,sender,receiver)=>{
    const context=await legacyContext(db,source,row,actorIds,true,sender,receiver)
    const record=context.record
    const existing=await nativePair(db,sender,receiver)
    if (existing.some(r=>r.id!==record.native_request_id)) throw error('FRIEND_REQUEST_PROVENANCE_REQUIRED')
    if (record.status!=='pending') {
      if (record.status!==status) throw error('FRIEND_REQUEST_ALREADY_RESOLVED')
      if (!record.native_request_id) return {...context.row,status}
      const native=(await nativePair(db,sender,receiver)).find(r=>r.id===record.native_request_id)
      if (!native || native.status!==status) throw error('FRIEND_REQUEST_ALREADY_RESOLVED')
      return native
    }
    // Never adopt an unlinked historical/new request solely because its pair matches.
    if ((await nativePair(db,sender,receiver)).length) throw error('FRIEND_REQUEST_PROVENANCE_REQUIRED')
    const display=await publicPair(db,sender,receiver)
    const {rows}=await db.query(`INSERT INTO fallback_friends(sender_id,receiver_id,status) VALUES($1,$2,$3)
      ON CONFLICT(sender_id,receiver_id) DO NOTHING RETURNING id::text,sender_id,receiver_id,status,created_at`,[display.sender,display.receiver,status])
    if (!rows[0]) throw error('FRIEND_REQUEST_PROVENANCE_REQUIRED')
    await db.query(`UPDATE fallback_friend_legacy_requests SET status=$3,native_request_id=$4,updated_at=now()
      WHERE source=$1 AND request_id=$2`,[source,keyOf(context.row),status,rows[0].id])
    return rows[0]
  })
}
async function cancelLegacyFriend(pool,source,row,actorIds,{outgoingOnly=false}={}) {
  return withPair(pool,row.sender_id,row.receiver_id,async(db,sender,receiver)=>{
    const context=await legacyContext(db,source,row,actorIds,false,sender,receiver,outgoingOnly)
    if (outgoingOnly && context.record.status!=='pending') throw error('FRIEND_REQUEST_ALREADY_RESOLVED')
    if (context.record.status==='accepted') throw error('FRIEND_REQUEST_ALREADY_RESOLVED')
    if (context.record.native_request_id) {
      const {rows}=await db.query("DELETE FROM fallback_friends WHERE id::text=$1 AND status IN ('pending','rejected') RETURNING id",[context.record.native_request_id])
      if (!rows.length) throw error('FRIEND_REQUEST_ALREADY_RESOLVED')
    }
    await db.query("UPDATE fallback_friend_legacy_requests SET status='cancelled',updated_at=now() WHERE source=$1 AND request_id=$2",[source,keyOf(context.row)])
    return context.row
  })
}
async function suppressLegacyPair(db,sender,receiver) {
  await db.query(`INSERT INTO fallback_friend_legacy_barriers(sender_id,receiver_id) VALUES($1,$2)
    ON CONFLICT DO NOTHING`,[sender,receiver])
  await db.query("UPDATE fallback_friend_legacy_requests SET status='cancelled',updated_at=now() WHERE fallback_friend_identity(sender_id)=$1 AND fallback_friend_identity(receiver_id)=$2",[sender,receiver])
}
async function removeFriendPair(pool,a,b) {
  return withPair(pool,a,b,async(db,sender,receiver)=>{
    const {rows}=await db.query(`DELETE FROM fallback_friends WHERE
      (fallback_friend_identity(sender_id)=$1 AND fallback_friend_identity(receiver_id)=$2) OR
      (fallback_friend_identity(sender_id)=$2 AND fallback_friend_identity(receiver_id)=$1)
      RETURNING id::text,sender_id,receiver_id,status,created_at`,[sender,receiver])
    await suppressLegacyPair(db,sender,receiver)
    await suppressLegacyPair(db,receiver,sender)
    return rows
  })
}
async function createNativeFriend(pool,a,b) {
  return withPair(pool,a,b,async(db,sender,receiver)=>{
    if (!sender || !receiver || sender===receiver) throw error('INVALID_RECEIVER',400)
    const existing=await nativePair(db,sender,receiver)
    if (existing.length>1) throw error('FRIEND_REQUEST_AMBIGUOUS')
    if (existing[0]) return existing[0]
    const display=await publicPair(db,sender,receiver)
    const {rows}=await db.query("INSERT INTO fallback_friends(sender_id,receiver_id,status) VALUES($1,$2,'pending') RETURNING id::text,sender_id,receiver_id,status,created_at",[display.sender,display.receiver])
    return rows[0]
  })
}
async function findNativeRequest(db,id,actorIds,receiverOnly=false) {
  const {rows}=await db.query(`SELECT id::text,sender_id,receiver_id,status,created_at FROM fallback_friends
    WHERE id::text=$1 AND (lower(receiver_id)=ANY($2::text[]) ${receiverOnly?'':'OR lower(sender_id)=ANY($2::text[])'})`,[id,actorIds])
  return rows[0] || null
}
async function answerNativeFriend(pool,id,status,actorIds) {
  if (!['accepted','rejected'].includes(status)) throw error('INVALID_STATUS',400)
  const initial=await findNativeRequest(pool,id,actorIds,true)
  if (!initial) return null
  return withPair(pool,initial.sender_id,initial.receiver_id,async db=>{
    const {rows}=await db.query(`UPDATE fallback_friends SET status=$3 WHERE id::text=$1
      AND lower(receiver_id)=ANY($2::text[]) AND status='pending'
      RETURNING id::text,sender_id,receiver_id,status,created_at`,[id,actorIds,status])
    const confirmed=rows[0] || await findNativeRequest(db,id,actorIds,true)
    if (!confirmed) throw error('REQUEST_NOT_FOUND',404)
    if (confirmed.status!==status) throw error('FRIEND_REQUEST_ALREADY_RESOLVED')
    return confirmed
  })
}
async function cancelNativeFriend(pool,id,actorIds,{outgoingOnly=false}={}) {
  const initial=await findNativeRequest(pool,id,actorIds)
  if (!initial) return null
  if (outgoingOnly && !actorIds.map(norm).includes(norm(initial.sender_id))) throw error('FRIEND_REQUEST_RESPONSE_FORBIDDEN',403)
  return withPair(pool,initial.sender_id,initial.receiver_id,async(db,sender,receiver)=>{
    const {rows}=await db.query(`DELETE FROM fallback_friends WHERE id::text=$1
      AND (lower(sender_id)=ANY($2::text[]) OR lower(receiver_id)=ANY($2::text[]))
      AND ${outgoingOnly ? "status='pending'" : "status IN ('pending','rejected')"} RETURNING id::text,sender_id,receiver_id,status,created_at`,[id,actorIds])
    if (!rows[0]) {
      if (!await findNativeRequest(db,id,actorIds)) throw error('REQUEST_NOT_FOUND',404)
      throw error('FRIEND_REQUEST_ALREADY_RESOLVED')
    }
    await suppressLegacyPair(db,sender,receiver)
    return rows[0]
  })
}
async function findLegacyRequest(db,id,actorIds,receiverOnly=false) {
  const matches=[]
  for(const source of ['imported','compat']) {
    const row=await loadSource(db,source,id)
    if(row && actorIds.map(norm).some(actor=>actor===norm(row.receiver_id) || (!receiverOnly && actor===norm(row.sender_id)))) matches.push({source,row})
  }
  if(matches.length>1) throw error('FRIEND_SOURCE_AMBIGUOUS')
  return matches[0] || null
}
// Call only inside the account purge transaction, after purging legacy source
// rows and before deleting fallback_users, while its aliases still resolve.
async function purgeFriendRequestIdentity(db,ids) {
  const actors=[...new Set(ids.map(norm).filter(Boolean))]
  if (!actors.length) return
  for (const table of ['fallback_friend_legacy_requests','fallback_friend_legacy_barriers']) {
    await db.query(`DELETE FROM ${table} WHERE lower(sender_id)=ANY($1::text[]) OR lower(receiver_id)=ANY($1::text[])
      OR fallback_friend_identity(sender_id)=ANY($1::text[]) OR fallback_friend_identity(receiver_id)=ANY($1::text[])`,[actors])
  }
}
module.exports={purgeFriendRequestIdentity,ensureFriendRequestState,visibleLegacyFriends,answerLegacyFriend,cancelLegacyFriend,removeFriendPair,createNativeFriend,
  answerNativeFriend,cancelNativeFriend,findLegacyRequest,withPair,suppressLegacyPair,loadSource,keyOf}
