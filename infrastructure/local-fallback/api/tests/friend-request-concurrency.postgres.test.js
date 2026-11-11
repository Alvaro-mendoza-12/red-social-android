'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { Pool } = require('pg')
const { ensureFriendRequestState, answerLegacyFriend, cancelLegacyFriend, removeFriendPair, createNativeFriend } = require('../src/friend-request-state')
const url = process.env.NOVVIN_FRIEND_CONCURRENCY_DATABASE_URL

// Opt-in only: this fixture destroys its dedicated disposable database schema.
test('independent PostgreSQL sessions serialize legacy decisions, cancellation and fresh invitations', {skip: !url}, async t => {
  const parsed = new URL(url)
  assert.ok(['127.0.0.1','localhost','[::1]'].includes(parsed.hostname))
  assert.equal(parsed.pathname, '/novvin_friend_concurrency_test')
  const pool = new Pool({connectionString:url,max:5})
  t.after(()=>pool.end())
  await pool.query(`DROP SCHEMA IF EXISTS supabase_import CASCADE; DROP SCHEMA public CASCADE; CREATE SCHEMA public;
    CREATE TABLE fallback_users(id text PRIMARY KEY,supabase_user_id text UNIQUE);
    INSERT INTO fallback_users VALUES('a-local','a'),('b-local','b');
    CREATE TABLE fallback_friends(id text PRIMARY KEY DEFAULT gen_random_uuid()::text,sender_id text,receiver_id text,status text,created_at timestamptz DEFAULT now(),UNIQUE(sender_id,receiver_id));
    CREATE TABLE fallback_compat_rows(table_name text,row_id text,row jsonb);
    CREATE SCHEMA supabase_import;
    CREATE TABLE supabase_import.friends(id text PRIMARY KEY,sender_id text,receiver_id text,status text);
    INSERT INTO supabase_import.friends VALUES('old','a','b','pending');`)
  await ensureFriendRequestState(pool)
  const row={id:'old',sender_id:'a',receiver_id:'b',status:'pending'}
  // Pause the first operation after it holds the advisory lock. Confirm the
  // second backend is actually waiting in PostgreSQL, rather than relying on timing.
  async function contend(first, second) {
    let release, entered
    const gate=new Promise(resolve=>{release=resolve})
    const locked=new Promise(resolve=>{entered=resolve})
    const held={connect:async()=>{
      const client=await pool.connect()
      return {release:()=>client.release(),query:async(sql,params)=>{
        const result=await client.query(sql,params)
        if (sql==='SELECT fallback_lock_friend_pair($1,$2)') {entered();await gate}
        return result
      }}
    },query:(...args)=>pool.query(...args)}
    const a=first(held).then(value=>({value}),error=>({error}))
    await locked
    const b=second(pool).then(value=>({value}),error=>({error}))
    try {
      const deadline=Date.now()+5000
      let waiting=false
      while(Date.now()<deadline) {
        const result=await pool.query("SELECT count(DISTINCT pid)::int AS n FROM pg_locks WHERE locktype='advisory' AND NOT granted")
        if(result.rows[0].n>0){waiting=true;break}
        await new Promise(resolve=>setTimeout(resolve,10))
      }
      assert.ok(waiting,'second PostgreSQL session must wait on the pair lock')
    } finally {release()}
    return Promise.all([a,b])
  }
  let results=await contend(db=>cancelLegacyFriend(db,'imported',row,['a']),db=>answerLegacyFriend(db,'imported',row,'accepted',['b']))
  assert.ok(results[0].value);assert.equal(results[1].error?.message,'FRIEND_REQUEST_CANCELLED')
  assert.equal((await pool.query('SELECT * FROM fallback_friends')).rowCount,0)
  await pool.query('TRUNCATE fallback_friend_legacy_requests')
  results=await contend(db=>answerLegacyFriend(db,'imported',row,'accepted',['b']),db=>cancelLegacyFriend(db,'imported',row,['a']))
  assert.equal(results[0].value.status,'accepted');assert.equal(results[1].error?.status,409)
  assert.equal((await pool.query('SELECT * FROM fallback_friends')).rowCount,1)
  results=await contend(db=>removeFriendPair(db,'a','b'),db=>answerLegacyFriend(db,'imported',row,'accepted',['b']))
  assert.ok(results[0].value);assert.equal(results[1].error?.message,'FRIEND_REQUEST_CANCELLED')
  results=await contend(db=>removeFriendPair(db,'a','b'),db=>createNativeFriend(db,'a','b'))
  assert.equal(results[1].value.status,'pending')
  assert.equal((await pool.query('SELECT * FROM fallback_friends')).rowCount,1)
  const admin=await pool.connect()
  let removal
  try {
    await admin.query('BEGIN')
    await admin.query('LOCK TABLE fallback_friend_legacy_barriers IN SHARE ROW EXCLUSIVE MODE')
    removal=removeFriendPair(pool,'a','b')
    const deadline=Date.now()+5000
    let waiting=false
    while(Date.now()<deadline) {
      const {rows}=await pool.query("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation='fallback_friend_legacy_barriers'::regclass AND NOT granted) AS waiting")
      if(rows[0].waiting){waiting=true;break}
      await new Promise(resolve=>setTimeout(resolve,10))
    }
    assert.ok(waiting,'friendship operation must wait for administrative table lock')
    // This fails if unfriend already acquired friends then waited for barriers:
    // administrative purge would retain barriers while waiting for friends.
    await admin.query('LOCK TABLE fallback_friends IN SHARE ROW EXCLUSIVE MODE NOWAIT')
  } finally {
    await admin.query('ROLLBACK');admin.release()
    if(removal) await removal
  }
})
