'use strict';
const crypto=require('node:crypto');
const LEASE_MS=5*60*1000;
const MAX_FILE_BYTES=512*1024*1024;
const norm=value=>typeof value==='string'?value.trim().toLowerCase():'';
const error=(code='STORAGE_READ_DENIED',status=404)=>Object.assign(new Error(code),{code,status,statusCode:status});
const tokenDigest=token=>crypto.createHash('sha256').update(token).digest('hex');
function scopeOf(reference){return reference.access_kind==='dm'?'chat':reference.access_kind;}
function viewOnceType(message){return ['view_once_image','view_once_video'].includes(norm(message.content_type??message.contentType));}
function revisionOf(object){return String(object.object_revision||new Date(object.updated_at).toISOString());}
async function ensureStorageViewOnceSchema(db) {
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_view_once_consumptions (
    scope_type text NOT NULL CHECK(scope_type IN ('chat','group','community')),message_id uuid NOT NULL,user_id text NOT NULL,
    device_id text,consumed_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(scope_type,message_id,user_id))`);
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_storage_view_once_leases (
    scope_type text NOT NULL CHECK(scope_type IN ('chat','group','community')),message_id uuid NOT NULL,user_id uuid NOT NULL,
    token_hash text NOT NULL CHECK(token_hash~'^[0-9a-f]{64}$'),bucket_id text NOT NULL,object_name text NOT NULL,
    object_id uuid NOT NULL,object_revision text NOT NULL,size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND ${MAX_FILE_BYTES}),
    next_offset bigint NOT NULL DEFAULT 0 CHECK(next_offset>=0),expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(scope_type,message_id,user_id),UNIQUE(token_hash))`);
  await db.query('CREATE INDEX IF NOT EXISTS idx_storage_view_once_expiry ON fallback_storage_view_once_leases(expires_at)');
}
async function claimViewOnceLease({pool,auth,scopeType,messageId,deviceId,origins,maxFileBytes=MAX_FILE_BYTES,now=()=>Date.now(),randomBytes=crypto.randomBytes}) {
  if(!['chat','group','community'].includes(scopeType)||!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(norm(messageId)))throw error('INVALID_VIEW_ONCE_SCOPE_OR_MESSAGE',400);
  if(!Number.isSafeInteger(maxFileBytes)||maxFileBytes<1||maxFileBytes>MAX_FILE_BYTES)throw error('INVALID_VIEW_ONCE_CLIENT_LIMIT',400);
  const {trustedOrigins,parseObjectReference,referenceAllows}=require('./storage-read-access');
  const trusted=trustedOrigins(origins);
  const localId=norm(auth?.sub);if(!localId)throw error('AUTH_REQUIRED',401);
  const db=await pool.connect();let begun=false;
  try{
    await db.query('BEGIN');begun=true;await db.query("SET LOCAL statement_timeout='3000ms'");
    const viewer=(await db.query('SELECT id::text,supabase_user_id::text FROM fallback_users WHERE lower(id::text)=$1 LIMIT 1',[localId])).rows[0];
    if(!viewer)throw error('AUTH_REQUIRED',401);
    const viewerIds=[localId,norm(viewer.supabase_user_id)].filter(Boolean);
    const table=scopeType==='chat'?'fallback_messages':'fallback_room_messages';
    const condition=scopeType==='chat'?'':' AND room_kind=$2';
    const message=(await db.query(`SELECT * FROM ${table} WHERE id=$1::uuid${condition} FOR UPDATE`,scopeType==='chat'?[messageId]:[messageId,scopeType])).rows[0];
    if(!message)throw error('MESSAGE_NOT_FOUND',404);
    if(!viewOnceType(message)||!message.media_url)throw error('MESSAGE_IS_NOT_VIEW_ONCE',409);
    const reference={...message,access_kind:scopeType==='chat'?'dm':scopeType};
    if(!await referenceAllows(db,reference,viewerIds,{allowViewOnce:true,allowPublicPreview:false}))throw error('MESSAGE_ACCESS_DENIED',403);
    const consumed=await db.query('SELECT 1 FROM fallback_view_once_consumptions WHERE scope_type=$1 AND message_id=$2::uuid AND lower(user_id)=ANY($3::text[])',[scopeType,messageId,viewerIds]);
    if(consumed.rows.length){await db.query('COMMIT');begun=false;return {allowed:false,already_consumed:true,consumed_at:null};}
    const ref=parseObjectReference(message.media_url,trusted);if(!ref)throw error('VIEW_ONCE_STORAGE_REFERENCE_REQUIRED',409);
    const object=(await db.query('SELECT * FROM fallback_storage_objects WHERE bucket_id=$1 AND name=$2 FOR KEY SHARE',[ref.bucket,ref.name])).rows[0];
    if(!object||!Number.isSafeInteger(Number(object.size_bytes))||Number(object.size_bytes)<1||Number(object.size_bytes)>MAX_FILE_BYTES)throw error('VIEW_ONCE_OBJECT_UNAVAILABLE',409);
    if(Number(object.size_bytes)>maxFileBytes)throw error('VIEW_ONCE_CLIENT_LIMIT_EXCEEDED',413);
    const token=randomBytes(32).toString('base64url');
    const expires=new Date(now()+LEASE_MS);
    const inserted=await db.query(`INSERT INTO fallback_view_once_consumptions(scope_type,message_id,user_id,device_id)
      VALUES($1,$2::uuid,$3,$4) ON CONFLICT DO NOTHING RETURNING consumed_at`,[scopeType,messageId,localId,String(deviceId||'').trim().slice(0,120)||null]);
    if(!inserted.rows.length)throw error('VIEW_ONCE_ALREADY_CONSUMED',409);
    await db.query(`INSERT INTO fallback_storage_view_once_leases(scope_type,message_id,user_id,token_hash,bucket_id,object_name,object_id,object_revision,size_bytes,expires_at)
      VALUES($1,$2::uuid,$3::uuid,$4,$5,$6,$7::uuid,$8,$9,$10)`,[scopeType,messageId,localId,tokenDigest(token),ref.bucket,ref.name,object.id,revisionOf(object),Number(object.size_bytes),expires]);
    await db.query('COMMIT');begun=false;
    const media=new URL(`${trusted[0]}/storage/v1/object/public/${encodeURIComponent(ref.bucket)}/${ref.name.split('/').map(encodeURIComponent).join('/')}`);media.searchParams.set('lease',token);
    return {allowed:true,already_consumed:false,consumed_at:inserted.rows[0].consumed_at,media_url:media.href,lease_expires_at:expires.toISOString(),media_size_bytes:Number(object.size_bytes),content_type:message.content_type};
  }catch(failure){if(begun)await db.query('ROLLBACK').catch(()=>{});throw failure;}finally{db.release();}
}
function deliveryWindow(req,reference,size) {
  if(req.method!=='GET'||req.headers?.['if-none-match']||req.headers?.['if-modified-since']||req.headers?.['if-range'])throw error();
  if(Object.keys(req.query||{}).some(key=>!['lease','token'].includes(key)))throw error();
  const range=req.headers?.range;
  if(!range)return {start:0,end:size-1};
  if(norm(reference.content_type)==='view_once_image')throw error();
  const match=/^bytes=(\d+)-(\d*)$/.exec(String(range));if(!match)throw error();
  const start=Number(match[1]),end=match[2]?Math.min(Number(match[2]),size-1):size-1;
  if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start<0||start>=size||end<start)throw error();
  return {start,end};
}
async function consumeViewOnceDelivery({db,req,reference,object,user,now=()=>Date.now()}) {
  const raw=req.query?.lease;
  if(typeof raw!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(raw)||!viewOnceType(reference))throw error();
  const lease=(await db.query(`SELECT * FROM fallback_storage_view_once_leases WHERE token_hash=$1 AND user_id=$2::uuid
    AND scope_type=$3 AND message_id=$4::uuid FOR UPDATE`,[tokenDigest(raw),user.id,scopeOf(reference),reference.id])).rows[0];
  if(!lease||lease.bucket_id!==object.bucket_id||lease.object_name!==object.name||norm(lease.object_id)!==norm(object.id)||lease.object_revision!==revisionOf(object)||
    Number(lease.size_bytes)!==Number(object.size_bytes)||Date.parse(lease.expires_at)<=now())throw error();
  const window=deliveryWindow(req,reference,Number(lease.size_bytes));
  if(Number(lease.next_offset)!==window.start)throw error(); // Atomic, monotonic byte budget: no replay or second opening.
  const updated=await db.query(`UPDATE fallback_storage_view_once_leases SET next_offset=$2
    WHERE token_hash=$1 AND next_offset=$3 RETURNING next_offset`,[lease.token_hash,window.end+1,window.start]);
  if(!updated.rows.length)throw error();
  if(req.headers?.range)req.headers.range=`bytes=${window.start}-${window.end}`;
  return {allowed:true,private:true,viewOnce:true,scope:'view-once',byteStart:window.start,byteEnd:window.end};
}
module.exports={LEASE_MS,MAX_FILE_BYTES,ensureStorageViewOnceSchema,claimViewOnceLease,consumeViewOnceDelivery,deliveryWindow,viewOnceType};
