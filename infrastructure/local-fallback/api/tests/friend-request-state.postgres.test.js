'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { PGlite } = require('@electric-sql/pglite')
const { ensureFriendRequestState, answerLegacyFriend, cancelLegacyFriend, visibleLegacyFriends, removeFriendPair, createNativeFriend } = require('../src/friend-request-state')

async function fixture(t) {
  const db=new PGlite();t.after(()=>db.close())
  await db.exec(`CREATE TABLE fallback_users(id text PRIMARY KEY,supabase_user_id text UNIQUE);
    INSERT INTO fallback_users VALUES('a-local','a'),('b-local','b'),('stranger-local','stranger');
    CREATE TABLE fallback_friends(id text PRIMARY KEY DEFAULT gen_random_uuid()::text,sender_id text,receiver_id text,status text,created_at timestamptz DEFAULT now(),UNIQUE(sender_id,receiver_id));
    CREATE TABLE fallback_compat_rows(table_name text,row_id text,row jsonb,updated_at timestamptz DEFAULT now(),PRIMARY KEY(table_name,row_id));
    CREATE SCHEMA supabase_import;
    CREATE TABLE supabase_import.friends(id text PRIMARY KEY,sender_id text,receiver_id text,status text,created_at timestamptz DEFAULT now());`)
  await ensureFriendRequestState(db)
  await ensureFriendRequestState(db)
  const row={id:'old',sender_id:'a',receiver_id:'b',status:'pending'}
  await db.query('INSERT INTO supabase_import.friends(id,sender_id,receiver_id,status) VALUES($1,$2,$3,$4)',Object.values(row))
  return {db,row}
}

test('legacy cancellation persists through retries and a fresh native request stays usable',async t=>{
  const {db,row}=await fixture(t)
  await cancelLegacyFriend(db,'imported',row,['a-local','a'])
  assert.deepEqual(await visibleLegacyFriends(db,'imported',[row]),[])
  await assert.rejects(answerLegacyFriend(db,'imported',row,'accepted',['b-local','b']),{status:409})
  assert.equal((await db.query('SELECT * FROM fallback_friends')).rows.length,0)
  const fresh=await createNativeFriend(db,'a','b')
  assert.equal(fresh.status,'pending')
  assert.equal(fresh.sender_id,'a');assert.equal(fresh.receiver_id,'b')
  await assert.rejects(answerLegacyFriend(db,'imported',row,'accepted',['b-local','b']),{status:409})
  assert.equal((await db.query('SELECT status FROM fallback_friends WHERE id=$1',[fresh.id])).rows[0].status,'pending')
})

test('legacy decisions materialize once and cancellation cannot remove accepted friendship',async t=>{
  const {db,row}=await fixture(t)
  const accepted=await answerLegacyFriend(db,'imported',row,'accepted',['b-local','b'])
  assert.equal(accepted.sender_id,'a');assert.equal(accepted.receiver_id,'b')
  assert.equal((await answerLegacyFriend(db,'imported',row,'accepted',['b-local','b'])).id,accepted.id)
  await assert.rejects(answerLegacyFriend(db,'imported',row,'rejected',['b-local','b']),{status:409})
  await assert.rejects(cancelLegacyFriend(db,'imported',row,['a-local','a']),{status:409})
  assert.equal((await db.query('SELECT status FROM fallback_friends')).rows[0].status,'accepted')
  await removeFriendPair(db,'a','b')
  assert.deepEqual(await visibleLegacyFriends(db,'imported',[row]),[])
  await assert.rejects(answerLegacyFriend(db,'imported',row,'accepted',['b-local','b']),{status:409})
  assert.equal((await db.query('SELECT * FROM fallback_friends')).rows.length,0)
})

test('unlinked native requests are never adopted by historical IDs',async t=>{
  const {db,row}=await fixture(t)
  const fresh=await createNativeFriend(db,'a-local','b-local')
  await assert.rejects(answerLegacyFriend(db,'imported',row,'accepted',['b-local','b']),{status:409})
  await cancelLegacyFriend(db,'imported',row,['b-local','b'])
  assert.equal((await db.query('SELECT status FROM fallback_friends WHERE id=$1',[fresh.id])).rows[0].status,'pending')
})

test('actor checks reject unrelated cancellation and sender answers without persisting decisions',async t=>{
  const {db,row}=await fixture(t)
  await assert.rejects(answerLegacyFriend(db,'imported',row,'accepted',['a-local','a']),{status:403})
  await assert.rejects(cancelLegacyFriend(db,'imported',row,['stranger-local','stranger']),{status:403})
  assert.equal((await db.query('SELECT * FROM fallback_friend_legacy_requests')).rows.length,0)
})

test('linking a migrated identity does not remove a legacy suppression barrier',async t=>{
  const {db}=await fixture(t)
  await db.query("UPDATE fallback_users SET supabase_user_id=NULL WHERE id='a-local'")
  const row={id:'before-link',sender_id:'a-local',receiver_id:'b-local',status:'accepted'}
  await removeFriendPair(db,'a-local','b-local')
  assert.deepEqual(await visibleLegacyFriends(db,'imported',[row]),[])
  await db.query("UPDATE fallback_users SET supabase_user_id='a-new-external' WHERE id='a-local'")
  assert.deepEqual(await visibleLegacyFriends(db,'imported',[row]),[])
  assert.deepEqual(await visibleLegacyFriends(db,'imported',[{...row,sender_id:'a-new-external'}]),[])
})

test('JavaScript and SQL use the same nonempty legacy identifier',async t=>{
  const {db}=await fixture(t)
  const {keyOf}=require('../src/friend-request-state')
  for(const row of [{id:'',uuid:'uuid-id',sender_id:'A',receiver_id:'B'},{id:' ',uuid:' ',sender_id:' A ',receiver_id:' B '},{id:'explicit',uuid:'other'}]){
    const {rows}=await db.query('SELECT fallback_friend_legacy_key($1::jsonb) AS id',[JSON.stringify(row)])
    assert.equal(keyOf(row),rows[0].id)
  }
})

test('compat cancellation and unfriend suppress old sources without hiding a new native request',async t=>{
  const {db}=await fixture(t)
  const row={id:'compat-old',sender_id:'b',receiver_id:'a',status:'pending'}
  await db.query("INSERT INTO fallback_compat_rows(table_name,row_id,row) VALUES('friends',$1,$2::jsonb)",[row.id,JSON.stringify(row)])
  await cancelLegacyFriend(db,'compat',row,['a-local','a'])
  assert.deepEqual(await visibleLegacyFriends(db,'compat',[row]),[])
  await assert.rejects(answerLegacyFriend(db,'compat',row,'accepted',['a-local','a']),{status:409})
  await removeFriendPair(db,'a-local','b-local')
  const fresh=await createNativeFriend(db,'b','a')
  assert.equal(fresh.status,'pending')
  assert.deepEqual(await visibleLegacyFriends(db,'compat',[row]),[])
})

test('source-to-native linkage rolls back when persisting the decision fails',async t=>{
  const {db,row}=await fixture(t)
  const failing={transaction:work=>db.transaction(tx=>work({query:async(sql,params)=>{
    if(sql.includes('SET status=$3,native_request_id=$4')) throw new Error('injected ledger failure')
    return tx.query(sql,params)
  }}))}
  await assert.rejects(answerLegacyFriend(failing,'imported',row,'accepted',['b-local','b']),/injected ledger failure/)
  assert.equal((await db.query('SELECT * FROM fallback_friends')).rows.length,0)
  assert.equal((await db.query('SELECT * FROM fallback_friend_legacy_requests')).rows.length,0)
})

test('SQL message trust stops using cancelled imported and compat sources',async t=>{
  const {db,row}=await fixture(t)
  await db.exec(`CREATE TABLE fallback_chats(id uuid PRIMARY KEY,participants text[],is_group boolean);
    CREATE TABLE fallback_messages(id uuid PRIMARY KEY,chat_id uuid,sender_id text,content text);
    CREATE TABLE fallback_follows(follower_id text,following_id text);`)
  const {ensureDirectMessageRequestPolicy}=require('../src/direct-message-request-policy')
  await ensureDirectMessageRequestPolicy(db)
  await db.query("UPDATE supabase_import.friends SET status='accepted' WHERE id='old'")
  const trust=async()=>(await db.query("SELECT fallback_request_pair_trusted('a','b') AS trusted")).rows[0].trusted
  assert.equal(await trust(),true)
  await removeFriendPair(db,'a-local','b-local')
  assert.equal(await trust(),false)
  await db.query("INSERT INTO fallback_compat_rows(table_name,row_id,row) VALUES('friends','reimported',$1::jsonb)",[JSON.stringify({...row,id:'reimported',status:'accepted'})])
  assert.equal(await trust(),false,'replayed source must not restore old trust')
  await createNativeFriend(db,'a','b')
  const {answerNativeFriend}=require('../src/friend-request-state')
  const native=(await db.query('SELECT id FROM fallback_friends')).rows[0]
  await answerNativeFriend(db,native.id,'accepted',['b-local','b'])
  assert.equal(await trust(),true,'a genuinely new native decision may restore trust')
})

test('a legacy barrier survives importing a previously unknown target account',async t=>{
  const {db}=await fixture(t)
  const row={id:'historical',sender_id:'a',receiver_id:'historical-remote',status:'accepted'}
  await db.query('INSERT INTO supabase_import.friends(id,sender_id,receiver_id,status) VALUES($1,$2,$3,$4)',Object.values(row))
  await removeFriendPair(db,'a','historical-remote')
  assert.deepEqual(await visibleLegacyFriends(db,'imported',[row]),[])
  await db.query("INSERT INTO fallback_users VALUES('new-local','historical-remote')")
  assert.deepEqual(await visibleLegacyFriends(db,'imported',[row]),[])
  await assert.rejects(answerLegacyFriend(db,'imported',row,'accepted',['new-local','historical-remote']),{status:409})
})
