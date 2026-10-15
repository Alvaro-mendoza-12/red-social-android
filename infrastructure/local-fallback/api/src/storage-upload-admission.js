'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { Pool } = require('pg')
const leasePools = new WeakMap()
const finalizationPools = new WeakMap()
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const LEASE_PREFIX = 'novvin-storage-upload-reservation:'
const error = (code, status = 503) => Object.assign(new Error(code), { code, status, expose: true, retryAfterSeconds: 2 })
const SCHEMA_SQL = `CREATE TABLE IF NOT EXISTS fallback_storage_upload_reservations (
  id uuid PRIMARY KEY, owner_id uuid NOT NULL, kind text NOT NULL CHECK(kind IN ('raw','multipart','tus')),
  input_bytes bigint NOT NULL CHECK(input_bytes>0 AND input_bytes<=536870912),
  reserved_bytes bigint NOT NULL CHECK(reserved_bytes>=input_bytes),
  active boolean NOT NULL DEFAULT true,
  expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE fallback_storage_upload_reservations ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;
CREATE INDEX IF NOT EXISTS storage_upload_reservations_owner ON fallback_storage_upload_reservations(owner_id);
CREATE INDEX IF NOT EXISTS storage_upload_reservations_expiry ON fallback_storage_upload_reservations(expires_at);
INSERT INTO fallback_storage_upload_reservations(id,owner_id,kind,input_bytes,reserved_bytes,expires_at,active)
  SELECT id,owner_id,'tus',upload_length,upload_length*4,expires_at,false FROM fallback_resumable_uploads
  WHERE state IN ('uploading','finalizing') AND upload_length>0 AND upload_length<=536870912
  ON CONFLICT(id) DO NOTHING;`

// Finalization holds a second advisory lock through the scanner promise.
// Keep it off the API pool: two TUS finalizers plus two scans must not consume
// all four API connections and starve authentication/control for two minutes.
async function acquireStorageFinalizationClient(pool) {
  if(!pool?.options)throw error('STORAGE_UPLOAD_BUDGET_DATABASE_INVALID',500)
  let dedicated=finalizationPools.get(pool)
  if(!dedicated){
    const max=setting(process.env.STORAGE_PENDING_GLOBAL_SLOTS,6,1,64)
    dedicated=new Pool({...pool.options,max,connectionTimeoutMillis:2500,idleTimeoutMillis:30000,allowExitOnIdle:true})
    dedicated.on('error',()=>{});finalizationPools.set(pool,dedicated)
  }
  try{return await dedicated.connect()}
  catch{throw error('STORAGE_UPLOAD_FINALIZATION_UNAVAILABLE')}
}
async function closeStorageFinalizationPool(pool) {
  const dedicated=finalizationPools.get(pool)
  if(dedicated){finalizationPools.delete(pool);await dedicated.end()}
}
async function closeStorageUploadPools(pool) {
  const leases=leasePools.get(pool)
  if(leases)leasePools.delete(pool)
  await Promise.all([closeStorageFinalizationPool(pool),leases?.end()])
}

async function ensureStorageUploadAdmissionSchema(db, { callerTransaction = false } = {}) {
  const borrowed=typeof db?.release==='function'||db?._connected===true||typeof db?.connect!=='function'
  const client=borrowed?db:await db.connect();let transaction=false
  try {
    if(!callerTransaction){await client.query('BEGIN');transaction=true}
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended('novvin-storage-upload-admission-schema-v1',0))")
    const ready = await client.query("SELECT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=to_regclass('fallback_storage_upload_reservations') AND attname='active' AND NOT attisdropped) AND to_regclass('storage_upload_reservations_owner') IS NOT NULL AND to_regclass('storage_upload_reservations_expiry') IS NOT NULL AS ready")
    if (ready.rows[0]?.ready !== true) await client.query(SCHEMA_SQL)
    if(transaction){await client.query('COMMIT');transaction=false}
  }finally{if(transaction)await client.query('ROLLBACK').catch(()=>{});if(!borrowed)client.release()}
}
function setting(raw, fallback, minimum, maximum) {
  const value = raw == null || raw === '' ? fallback : Number(raw)
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw error('STORAGE_UPLOAD_BUDGET_CONFIGURATION_INVALID', 500)
  return value
}
function createStorageUploadAdmission({pool, root, globalBytes = process.env.STORAGE_PENDING_GLOBAL_BYTES,
  userBytes = process.env.STORAGE_PENDING_USER_BYTES, globalSlots = process.env.STORAGE_PENDING_GLOBAL_SLOTS,
  userSlots = process.env.STORAGE_PENDING_USER_SLOTS, minFreeBytes = process.env.STORAGE_MIN_FREE_BYTES,
  globalActiveBytes = process.env.STORAGE_ACTIVE_GLOBAL_BYTES, userActiveBytes = process.env.STORAGE_ACTIVE_USER_BYTES,
  statfs = fs.promises.statfs, now = Date.now, leasePool} = {}) {
  if (!pool?.connect || !path.isAbsolute(root || '')) throw error('STORAGE_UPLOAD_BUDGET_CONFIGURATION_INVALID', 500)
  const limits = Object.freeze({globalBytes:setting(globalBytes,32*1024**3,4*1024**2,1024**4),
    userBytes:setting(userBytes,4*1024**3,4*1024**2,1024**4),globalSlots:setting(globalSlots,6,1,64),
    globalActiveBytes:setting(globalActiveBytes,4*1024**3,4*1024**2,1024**4),userActiveBytes:setting(userActiveBytes,2*1024**3,4*1024**2,1024**4),
    userSlots:setting(userSlots,3,1,16),minFreeBytes:setting(minFreeBytes,8*1024**3,0,1024**4),pendingTusGlobal:256,pendingTusUser:16,copyFactor:4})
  if (limits.userBytes>limits.globalBytes || limits.userSlots>limits.globalSlots) throw error('STORAGE_UPLOAD_BUDGET_CONFIGURATION_INVALID',500)
  if(limits.userActiveBytes>limits.globalActiveBytes||limits.globalActiveBytes>limits.globalBytes||limits.userActiveBytes>limits.userBytes)throw error('STORAGE_UPLOAD_BUDGET_CONFIGURATION_INVALID',500)
  // A lease occupies one connection for the whole upload. It must not consume
  // the API pool needed by scans, authentication and finalization (a configured
  // API pool of four remains usable with four active admission leases).
  if (!leasePool) {
    leasePool=leasePools.get(pool)
    if(!leasePool) {
      if(!pool.options)throw error('STORAGE_UPLOAD_BUDGET_DATABASE_INVALID',500)
      leasePool=new Pool({...pool.options,max:limits.globalSlots+1,connectionTimeoutMillis:2500,idleTimeoutMillis:30000,allowExitOnIdle:true})
      leasePool.on('error',()=>{})
      leasePools.set(pool,leasePool)
    }
  }
  const tempRoot=path.join(root,'.tmp'),tusRoot=path.join(tempRoot,'resumable')
  async function totals(client,ownerId,excludeId=null) {
    // A crashed/disconnected TUS request leaves bytes reserved for resumption,
    // but must not hold an active scanner slot for its entire 24-hour lifetime.
    // A live request owns the same session lock and is never cleared here.
    await client.query(`UPDATE fallback_storage_upload_reservations SET active=false
      WHERE kind='tus' AND active AND pg_try_advisory_xact_lock(hashtextextended($1||id::text,0))`,[LEASE_PREFIX])
    return (await client.query(`SELECT count(*) FILTER(WHERE active)::int AS slots,
      COALESCE(sum(reserved_bytes),0)::text AS bytes,
      count(*) FILTER(WHERE active AND owner_id=$1)::int AS user_slots,
      COALESCE(sum(reserved_bytes) FILTER(WHERE owner_id=$1),0)::text AS user_bytes,
      COALESCE(sum(reserved_bytes) FILTER(WHERE active),0)::text AS active_bytes,
      COALESCE(sum(reserved_bytes) FILTER(WHERE active AND owner_id=$1),0)::text AS user_active_bytes,
      count(*) FILTER(WHERE kind='tus')::int AS tus_count,
      count(*) FILTER(WHERE kind='tus' AND owner_id=$1)::int AS user_tus_count
      FROM fallback_storage_upload_reservations WHERE ($2::uuid IS NULL OR id<>$2)`,[ownerId,excludeId])).rows[0]
  }
  function activeCapacity(total,reserved) {
    if(total.user_slots>=limits.userSlots||Number(total.user_active_bytes)+reserved>limits.userActiveBytes)throw error('STORAGE_UPLOAD_USER_CAPACITY',429)
    if(total.slots>=limits.globalSlots||Number(total.active_bytes)+reserved>limits.globalActiveBytes)throw error('STORAGE_UPLOAD_GLOBAL_CAPACITY')
  }
  async function connection() {
    try{return await leasePool.connect()}
    catch{throw error('STORAGE_UPLOAD_BUDGET_UNAVAILABLE')}
  }
  async function reserve({ownerId,bytes,kind,id=crypto.randomUUID(),expiresAt}={}) {
    if (!UUID.test(id) || !/^[0-9a-f-]{36}$/.test(ownerId || '') || !['raw','multipart','tus'].includes(kind) ||
        !Number.isSafeInteger(bytes) || bytes<1 || bytes>512*1024**2) throw error('STORAGE_UPLOAD_RESERVATION_INVALID',400)
    const expiry=new Date(expiresAt || now()+60*60_000)
    if (!Number.isFinite(expiry.getTime()) || expiry.getTime()<=now()) throw error('STORAGE_UPLOAD_RESERVATION_INVALID',400)
    const client=await connection();let transaction=false,locked=false,committed=false
    try {
      await client.query('BEGIN');transaction=true
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended('novvin-storage-upload-global-budget-v1',0))")
      const total=await totals(client,ownerId)
      const reserved=bytes*limits.copyFactor
      activeCapacity(total,reserved)
      if (Number(total.user_bytes)+reserved>limits.userBytes || kind==='tus'&&total.user_tus_count>=limits.pendingTusUser) throw error('STORAGE_UPLOAD_USER_CAPACITY',429)
      if (Number(total.bytes)+reserved>limits.globalBytes || kind==='tus'&&total.tus_count>=limits.pendingTusGlobal) throw error('STORAGE_UPLOAD_GLOBAL_CAPACITY')
      const disk=await statfs(root,{bigint:true}),free=BigInt(disk.bavail)*BigInt(disk.bsize)
      // Reserve against bytes still promised to all API replicas, in addition to
      // existing disk use. This is deliberately conservative during a large copy.
      if (free-BigInt(total.bytes)-BigInt(reserved)<BigInt(limits.minFreeBytes)) throw error('STORAGE_UPLOAD_DISK_CAPACITY')
      await client.query('INSERT INTO fallback_storage_upload_reservations(id,owner_id,kind,input_bytes,reserved_bytes,expires_at) VALUES($1,$2,$3,$4,$5,$6)',[id,ownerId,kind,bytes,reserved,expiry])
      await client.query('SELECT pg_advisory_lock(hashtextextended($1,0))',[LEASE_PREFIX+id]);locked=true
      await client.query('COMMIT');transaction=false;committed=true
      return makeLease(client,{id,ownerId,kind,bytes,expiresAt:expiry})
    } catch (cause) {
      if(transaction)await client.query('ROLLBACK').catch(()=>{})
      if(locked)await client.query('SELECT pg_advisory_unlock(hashtextextended($1,0))',[LEASE_PREFIX+id]).catch(()=>{})
      client.release()
      // If COMMIT was ambiguous, retain the reservation. Reconciliation can
      // release it only after expiry and successful acquisition of its lock.
      throw cause
    }
  }
  async function claimTus(id,ownerId,{activate=true}={}) {
    if(!UUID.test(id))throw error('TUS_UPLOAD_NOT_FOUND',404)
    const client=await connection();let locked=false,transaction=false
    try {
      const authorized=(await client.query('SELECT 1 FROM fallback_resumable_uploads WHERE id=$1 AND owner_id=$2',[id,ownerId])).rows.length>0
      if(!authorized)throw error('TUS_UPLOAD_NOT_FOUND',404)
      await client.query('BEGIN');transaction=true
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended('novvin-storage-upload-global-budget-v1',0))")
      locked=(await client.query('SELECT pg_try_advisory_lock(hashtextextended($1,0)) AS acquired',[LEASE_PREFIX+id])).rows[0]?.acquired===true
      if(!locked)throw error('TUS_UPLOAD_BUSY',409)
      const row=(await client.query(`SELECT r.*,u.state,u.expires_at AS upload_expires_at FROM fallback_storage_upload_reservations r
        JOIN fallback_resumable_uploads u ON u.id=r.id WHERE r.id=$1 AND r.owner_id=$2 AND u.owner_id=$2`,[id,ownerId])).rows[0]
      if(!row) {
        const completed=(await client.query("SELECT state FROM fallback_resumable_uploads WHERE id=$1 AND owner_id=$2",[id,ownerId])).rows[0]
        if(completed?.state==='completed') {await client.query('COMMIT');transaction=false;await client.query('SELECT pg_advisory_unlock(hashtextextended($1,0))',[LEASE_PREFIX+id]);locked=false;client.release();return null}
        throw error('TUS_UPLOAD_NOT_FOUND',404)
      }
      const activeNeeded=activate===true||activate==='finalizing'&&row.state==='finalizing'
      if(activeNeeded)activeCapacity(await totals(client,ownerId,id),Number(row.reserved_bytes))
      await client.query('UPDATE fallback_storage_upload_reservations SET active=$2,updated_at=now() WHERE id=$1',[id,activeNeeded])
      await client.query('COMMIT');transaction=false
      return makeLease(client,{id,ownerId,kind:'tus',bytes:Number(row.input_bytes),expiresAt:row.upload_expires_at})
    }catch(cause){if(transaction)await client.query('ROLLBACK').catch(()=>{});if(locked)await client.query('SELECT pg_advisory_unlock(hashtextextended($1,0))',[LEASE_PREFIX+id]).catch(()=>{});client.release();throw cause}
  }
  function makeLease(client,info) {
    let finished=false,lost=false;const callbacks=new Set()
    const connectionFailure=()=>{lost=true;for(const callback of callbacks){try{callback()}catch{}}}
    client.on('error',connectionFailure)
    return {...info,
      assertAlive(){if(finished||lost)throw error('STORAGE_UPLOAD_RESERVATION_LOST')},
      onLost(callback){if(typeof callback!=='function')throw TypeError('callback required');if(lost)callback();else callbacks.add(callback);return()=>callbacks.delete(callback)},
      async finish({keep=false,expiresAt=info.expiresAt}={}) {
        if(finished)return;finished=true
        try {
          if(keep)await client.query('UPDATE fallback_storage_upload_reservations SET active=false,expires_at=$2,updated_at=now() WHERE id=$1',[info.id,expiresAt])
          else {await removeOwnedTemps(info.id,info.kind);await client.query('DELETE FROM fallback_storage_upload_reservations WHERE id=$1',[info.id])}
        }finally{await client.query('SELECT pg_advisory_unlock(hashtextextended($1,0))',[LEASE_PREFIX+info.id]).catch(()=>{});client.removeListener('error',connectionFailure);client.release()}
      },
    }
  }
  async function removeOwnedTemps(id,kind) {
    if(!UUID.test(id))throw error('STORAGE_UPLOAD_RESERVATION_INVALID',500)
    const files=kind==='tus'?[path.join(tusRoot,id+'.part')]:
      (await fs.promises.readdir(tempRoot).catch(e=>e.code==='ENOENT'?[]:Promise.reject(e))).filter(n=>n.startsWith('admitted-'+id+'-')&&/^admitted-[a-f0-9-]+$/.test(n)).map(n=>path.join(tempRoot,n))
    for(const file of files) {
      const resolved=path.resolve(file);if(!resolved.startsWith(path.resolve(tempRoot)+path.sep))throw error('STORAGE_UPLOAD_TEMP_PATH_INVALID',500)
      const stat=await fs.promises.lstat(file).catch(e=>e.code==='ENOENT'?null:Promise.reject(e))
      if(stat){if(!stat.isFile()&&!stat.isSymbolicLink())throw error('STORAGE_UPLOAD_TEMP_PATH_INVALID',500);await fs.promises.unlink(file)}
    }
  }
  async function reconcileExpired({maxRows=64}={}) {
    const candidates=(await pool.query('SELECT id FROM fallback_storage_upload_reservations WHERE expires_at<now() ORDER BY expires_at LIMIT $1',[maxRows])).rows
    const result={released:0,activeSkipped:0}
    for(const {id}of candidates) {
      const client=await pool.connect();let transaction=false
      try {
        await client.query('BEGIN');transaction=true
        const lock=(await client.query('SELECT pg_try_advisory_xact_lock(hashtextextended($1,0)) AS acquired',[LEASE_PREFIX+id])).rows[0]?.acquired===true
        if(!lock){result.activeSkipped++;await client.query('ROLLBACK');transaction=false;continue}
        const row=(await client.query('SELECT * FROM fallback_storage_upload_reservations WHERE id=$1 AND expires_at<now() FOR UPDATE',[id])).rows[0]
        if(row) {
          if(row.kind==='tus') {
            const upload=(await client.query('SELECT * FROM fallback_resumable_uploads WHERE id=$1 FOR UPDATE',[id])).rows[0]
            if(upload&&new Date(upload.expires_at).getTime()>now()){await client.query('UPDATE fallback_storage_upload_reservations SET expires_at=$2 WHERE id=$1',[id,upload.expires_at]);await client.query('COMMIT');transaction=false;continue}
            await removeOwnedTemps(id,'tus');await client.query('DELETE FROM fallback_resumable_uploads WHERE id=$1',[id])
          }else await removeOwnedTemps(id,row.kind)
          await client.query('DELETE FROM fallback_storage_upload_reservations WHERE id=$1',[id]);result.released++
        }
        await client.query('COMMIT');transaction=false
      }finally{if(transaction)await client.query('ROLLBACK').catch(()=>{});client.release()}
    }
    return result
  }
  return {reserve,claimTus,reconcileExpired,limits,removeOwnedTemps}
}
module.exports={createStorageUploadAdmission,acquireStorageFinalizationClient,closeStorageFinalizationPool,closeStorageUploadPools,ensureStorageUploadAdmissionSchema,STORAGE_UPLOAD_ADMISSION_SCHEMA_SQL:SCHEMA_SQL}
