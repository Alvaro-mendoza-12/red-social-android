'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { PGlite } = require('@electric-sql/pglite')
const { ensureFriendRequestState } = require('../src/friend-request-state')
const { _test: { handlePatch, handleDelete } } = require('../src/postgrest-compat-routes')

async function fixture(t) {
  const db = new PGlite()
  t.after(() => db.close())
  await db.exec(`CREATE TABLE fallback_users(id text, supabase_user_id text, email text, display_name text, avatar_url text);
    INSERT INTO fallback_users(id,supabase_user_id) VALUES ('receiver-local','receiver'),('sender-local','sender'),('stranger','stranger');
    CREATE TABLE fallback_profile_moderation(user_id text,is_admin boolean,admin_access_level text,admin_is_owner boolean);
    CREATE TABLE fallback_friends(id text PRIMARY KEY DEFAULT gen_random_uuid()::text, sender_id text, receiver_id text, status text, created_at timestamptz DEFAULT now(), UNIQUE(sender_id,receiver_id));
    CREATE TABLE fallback_compat_rows(table_name text, row_id text, row jsonb, updated_at timestamptz DEFAULT now());
    CREATE SCHEMA supabase_import;
    CREATE TABLE supabase_import.friends(id text, sender_id text, receiver_id text, status text, created_at timestamptz DEFAULT now());`)
  await ensureFriendRequestState(db)
  return { db,
    patch: (id,status,actor='receiver-local') => handlePatch(db,'friends',{sub:actor},{id:`eq.${id}`},{status}),
    insert: (table,id,status='pending') => db.query(`INSERT INTO ${table}(id,sender_id,receiver_id,status) VALUES($1,'sender','receiver',$2)`,[id,status]),
  }
}

for (const final of ['accepted','rejected']) {
  test(`compat native ${final} decisions are stable and same-decision retries do not write`, async t => {
    const {db,patch,insert}=await fixture(t)
    await insert('fallback_friends','native')
    assert.equal((await patch('native',final))[0].status,final)
    const before=(await db.query("SELECT xmin::text AS revision FROM fallback_friends WHERE id='native'")).rows[0].revision
    assert.equal((await patch('native',final))[0].status,final)
    assert.equal((await db.query("SELECT xmin::text AS revision FROM fallback_friends WHERE id='native'")).rows[0].revision,before)
    await assert.rejects(patch('native',final==='accepted'?'rejected':'accepted'),{status:409,message:'FRIEND_REQUEST_ALREADY_RESOLVED'})
    assert.equal((await db.query("SELECT status FROM fallback_friends WHERE id='native'")).rows[0].status,final)
  })
}

test('a stale imported pending row cannot overwrite the resolved native relationship',async t=>{
  const {db,patch,insert}=await fixture(t)
  await insert('supabase_import.friends','imported')
  await insert('fallback_friends','native','accepted')
  await assert.rejects(patch('imported','rejected'),{status:409,message:'FRIEND_REQUEST_PROVENANCE_REQUIRED'})
  await assert.rejects(patch('imported','accepted'),{status:409})
  assert.equal((await db.query('SELECT status FROM fallback_friends')).rows[0].status,'accepted')
})

test('resolved import-only decisions cannot be reversed or materialized by a retry',async t=>{
  const {db,patch,insert}=await fixture(t)
  await insert('supabase_import.friends','imported','accepted')
  await assert.rejects(patch('imported','rejected'),{status:409,message:'FRIEND_REQUEST_ALREADY_RESOLVED'})
  assert.equal((await patch('imported','accepted'))[0].status,'accepted')
  assert.equal((await db.query('SELECT * FROM fallback_friends')).rows.length,0)
})

test('pending imports still resolve and another account cannot answer them',async t=>{
  const {patch,insert}=await fixture(t)
  await insert('supabase_import.friends','imported')
  await assert.rejects(patch('imported','accepted','sender-local'),{status:403,message:'FRIEND_REQUEST_RESPONSE_FORBIDDEN'})
  assert.deepEqual(await patch('imported','accepted','stranger'),[])
  assert.equal((await patch('imported','accepted'))[0].status,'accepted')
})

test('a resolved imported row cannot acknowledge a decision different from the native pair',async t=>{
  const {patch,insert}=await fixture(t)
  await insert('supabase_import.friends','imported','accepted')
  await insert('fallback_friends','native','rejected')
  await assert.rejects(patch('imported','accepted'),{status:409,message:'FRIEND_REQUEST_PROVENANCE_REQUIRED'})
  await assert.rejects(patch('imported','rejected'),{status:409})
})

test('a native request deleted after the read is never recreated by its response',async t=>{
  const {db,insert}=await fixture(t)
  await insert('fallback_friends','native')
  let deleted=false
  const pool={transaction:work=>db.transaction(work),query:async(sql,params)=>{
    const result=await db.query(sql,params)
    if(!deleted && /FROM fallback_friends\s+ORDER BY/.test(sql)){
      deleted=true
      await db.query("DELETE FROM fallback_friends WHERE id='native'")
    }
    return result
  }}
  await assert.rejects(handlePatch(pool,'friends',{sub:'receiver-local'},{id:'eq.native'},{status:'accepted'}),{status:404})
  assert.equal(deleted,true)
  assert.deepEqual((await db.query('SELECT * FROM fallback_friends')).rows,[])
})

test('compat cancellation survives rereads and cannot resurrect an imported request',async t=>{
  const {db,patch,insert}=await fixture(t)
  await insert('supabase_import.friends','imported')
  const result=await handleDelete(db,'friends',{sub:'sender-local'},{id:'eq.imported',status:'eq.pending'})
  assert.equal(result.length,1)
  assert.deepEqual(await patch('imported','accepted'),[])
  assert.equal((await db.query('SELECT * FROM fallback_friends')).rowCount,0)
  assert.equal((await db.query('SELECT status FROM fallback_friend_legacy_requests')).rows[0].status,'cancelled')
})

test('legacy rejection cannot delete accepted friendship; explicit unfriend can',async t=>{
  const {db,insert}=await fixture(t)
  await insert('supabase_import.friends','imported','accepted')
  const auth={sub:'receiver-local'},pair={sender_id:'eq.sender',receiver_id:'eq.receiver'}
  assert.deepEqual(await handleDelete(db,'friends',auth,{...pair,status:'eq.pending'}),[])
  await assert.rejects(handleDelete(db,'friends',auth,pair),{status:409})
  assert.equal((await handleDelete(db,'friends',auth,{...pair,status:'eq.accepted'})).length,1)
  assert.deepEqual(await handleDelete(db,'friends',auth,{...pair,status:'eq.accepted'}),[])
})
