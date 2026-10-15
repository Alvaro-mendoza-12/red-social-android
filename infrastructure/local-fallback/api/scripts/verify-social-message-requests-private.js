'use strict'
const assert=require('node:assert/strict'),crypto=require('node:crypto')
const Module=require('node:module')
for(const name of ['realtime-publish','push-notifications']) {
  const id=require.resolve('../src/'+name)
  const entry=new Module(id,module)
  entry.exports=new Proxy({}, {get:()=>async()=>({disabledForPrivateTest:true})})
  require.cache[id]=entry
}
const {Pool}=require('pg')
const {ensureDirectMessageRequestPolicy,registerDirectMessageRequestRoutes}=require('../src/direct-message-request-policy')
const {notifyPublicMentions}=require('../src/data-routes')
const pool=new Pool({connectionString:process.env.DATABASE_URL,max:5})
const cases=[]
const check=async(name,fn)=>{const began=performance.now();await fn();cases.push({name,passed:true,durationMs:Math.round((performance.now()-began)*100)/100})}
const uid=()=>crypto.randomUUID()
let fixtures=[]
async function main(){
  const {rows:db}=await pool.query('SELECT current_database() AS name')
  assert.match(db[0].name,/^novvin_(?:social|request)_/)
  await ensureDirectMessageRequestPolicy(pool)
  const A=uid(),B=uid(),C=uid(),alias=uid()
  fixtures=[A,B,C]
  await pool.query(`INSERT INTO fallback_users(id,email,display_name,supabase_user_id)
    VALUES($1,$4,'Solicitud A',$7),($2,$5,'Solicitud B',NULL),($3,$6,'Solicitud C',NULL)`,[A,B,C,`${A}@fixture.invalid`,`${B}@fixture.invalid`,`${C}@fixture.invalid`,alias])
  const chat=async(a=A,b=B,legacy=false)=>{
    const id=uid()
    await pool.query('INSERT INTO fallback_chats(id,participants,is_group,intro_policy_started_at) VALUES($1,$2,FALSE,CASE WHEN $3 THEN NULL ELSE NOW() END)',[id,[a,b],legacy]);return id
  }
  const send=async(id,from,msg=uid(),executor=pool)=>{
    await executor.query(`INSERT INTO fallback_messages(id,chat_id,sender_id,content) VALUES($1,$2,$3,'fixture only') ON CONFLICT(id) DO NOTHING`,[msg,id,from]);return msg
  }
  const state=async(id,viewer)=> (await pool.query('SELECT fallback_direct_message_request_state($1,$2) AS s',[id,viewer])).rows[0].s
  const first=await chat()
  let firstId
  await check('unknown recipient sees new, sender gets exactly one introduction',async()=>{
    assert.equal((await state(first,A)).status,'new');firstId=await send(first,A)
    assert.equal((await state(first,alias)).status,'pending_outgoing')
    assert.equal((await state(first,B)).status,'pending_incoming')
    await assert.rejects(send(first,alias),/MESSAGE_REQUEST_PENDING/)
    assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_messages WHERE chat_id=$1',[first])).rows[0].n),1)
  })
  await check('same first-message id is idempotent through canonical and legacy aliases',async()=>{
    await send(first,alias,firstId)
    assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_messages WHERE chat_id=$1',[first])).rows[0].n),1)
  })
  await check('recipient response accepts conversation without adding a friendship',async()=>{
    await send(first,B);await send(first,A)
    assert.equal((await state(first,A)).status,'open')
    assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_friends WHERE sender_id=ANY($1::text[])',[fixtures])).rows[0].n),0)
  })
  await check('concurrent first sends from the same user commit only one message',async()=>{
    const id=await chat(A,C)
    const clients=await Promise.all([pool.connect(),pool.connect()])
    try {
      const results=await Promise.allSettled(clients.map(async client=>{
        await client.query('BEGIN')
        try {await send(id,A,uid(),client);await client.query('COMMIT')}
        catch(error){await client.query('ROLLBACK');throw error}
      }))
      assert.equal(results.filter(x=>x.status==='fulfilled').length,1)
      assert.match(results.find(x=>x.status==='rejected').reason.message,/MESSAGE_REQUEST_PENDING/)
      assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_messages WHERE chat_id=$1',[id])).rows[0].n),1)
    }finally{clients.forEach(c=>c.release())}
  })
  await check('opposite concurrent first messages form a valid replied conversation',async()=>{
    const id=await chat(A,B)
    await Promise.all([send(id,A),send(id,B)])
    assert.equal((await state(id,A)).status,'open')
    assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_messages WHERE chat_id=$1',[id])).rows[0].n),2)
  })
  await check('blocks work with local and imported aliases, including after acceptance',async()=>{
    await pool.query('INSERT INTO fallback_user_blocks(blocker_id,blocked_id) VALUES($1,$2)',[B,A])
    try {assert.equal((await state(first,alias)).status,'blocked');await assert.rejects(send(first,alias),/CHAT_BLOCKED_BY_RECIPIENT/)}
    finally{await pool.query('DELETE FROM fallback_user_blocks WHERE blocker_id=$1 AND blocked_id=$2',[B,A])}
  })
  await check('outsiders cannot insert or inspect a request, even in legacy conversations',async()=>{
    const id=await chat(A,B,true)
    assert.equal((await state(id,C)).status,'unavailable')
    await assert.rejects(send(id,C),/CHAT_PARTICIPANT_REQUIRED/)
    await send(id,A);await assert.rejects(send(id,A),/MESSAGE_REQUEST_PENDING/)
  })
  await check('imported legacy conversations with replies remain open; unilateral history stays pending',async()=>{
    const id=await chat(A,B,true)
    await pool.query("INSERT INTO fallback_compat_rows(table_name,row_id,row) VALUES('messages',$1,$2::jsonb)",[uid(),JSON.stringify({chat_id:id,sender_id:alias})])
    assert.equal((await state(id,A)).status,'pending_outgoing')
    await pool.query("INSERT INTO fallback_compat_rows(table_name,row_id,row) VALUES('messages',$1,$2::jsonb)",[uid(),JSON.stringify({chat_id:id,sender_id:B})])
    assert.equal((await state(id,A)).status,'open');await send(id,A);await send(id,A)
  })
  await check('mutual following is trusted but a unilateral follow is not',async()=>{
    const id=await chat(A,C)
    await pool.query('INSERT INTO fallback_follows(follower_id,following_id) VALUES($1,$2)',[alias,C])
    assert.equal((await state(id,A)).status,'new')
    await pool.query('INSERT INTO fallback_follows(follower_id,following_id) VALUES($1,$2)',[C,A])
    assert.equal((await state(id,A)).status,'open');await send(id,A);await send(id,A)
  })
  await check('accepted imported and local friendships remain usable',async()=>{
    const id=await chat(B,C)
    await pool.query("INSERT INTO fallback_compat_rows(table_name,row_id,row) VALUES('friends',$1,$2::jsonb)",[uid(),JSON.stringify({sender_id:B,receiver_id:C,status:'accepted'})])
    assert.equal((await state(id,B)).status,'open');await send(id,B);await send(id,B)
  })
  // Exercise the real registered HTTP handlers with the actual PostgreSQL state.
  const routes=new Map(),app={get:(path,...handlers)=>routes.set('GET '+path,handlers.at(-1)),post:(path,...handlers)=>routes.set('POST '+path,handlers.at(-1))}
  registerDirectMessageRequestRoutes(app,{pool,authRequired:()=>{},getCurrentUser:async(_p,req)=>({id:req.actor}),userIdentity:u=>({displayUserId:u.id,values:[u.id],ids:new Set([u.id])}),
    privateChatAccessContext:async(executor,id,identity)=>{
      const {rows}=await executor.query('SELECT * FROM fallback_chats WHERE id::text=$1',[id])
      const s=await executor.query('SELECT fallback_direct_message_request_state($1,$2) AS s',[id,identity.displayUserId])
      return {chat:rows[0],hasAccess:s.rows[0].s.status!=='unavailable'}
    }})
  const invoke=async(method,path,actor,id,action)=>{
    let status=200,payload,problem
    const res={status(code){status=code;return this},json(value){payload=value;return this}}
    await routes.get(method+' '+path)({actor,params:{chatId:id,userId:id},body:{action}},res,error=>{problem=error})
    if(problem)throw problem
    return {status,payload}
  }
  await check('sender cannot accept their own request; outsiders cannot decide',async()=>{
    const id=await chat();await send(id,A)
    assert.equal((await invoke('POST','/v1/data/messages/chats/:chatId/request',A,id,'accept')).status,409)
    assert.equal((await invoke('POST','/v1/data/messages/chats/:chatId/request',C,id,'accept')).status,404)
  })
  await check('recipient explicitly accepts, repeated accept is idempotent',async()=>{
    const id=await chat();await send(id,A)
    for(let i=0;i<2;i++)assert.equal((await invoke('POST','/v1/data/messages/chats/:chatId/request',B,id,'accept')).payload.status,'open')
    await send(id,A)
  })
  await check('rejection prevents subsequent messages and cannot be changed by the sender',async()=>{
    const id=await chat();await send(id,A)
    assert.equal((await invoke('POST','/v1/data/messages/chats/:chatId/request',B,id,'reject')).payload.status,'rejected')
    await assert.rejects(send(id,A),/MESSAGE_REQUEST_REJECTED/)
    assert.equal((await invoke('POST','/v1/data/messages/chats/:chatId/request',A,id,'accept')).status,409)
    assert.equal((await invoke('POST','/v1/data/messages/chats/:chatId/request',B,id,'reject')).status,200)
  })
  await check('group conversations are outside the one-to-one introduction policy',async()=>{
    const id=await chat();await pool.query('UPDATE fallback_chats SET is_group=TRUE WHERE id=$1',[id])
    await send(id,A);await send(id,A);assert.equal((await state(id,B)).status,'open')
  })
  // Mention tests persist only private-clone notifications; pushes/realtime are
  // disabled by the runner. No production account is messaged or created.
  await pool.query("INSERT INTO fallback_profile_overrides(user_id,profile) VALUES($1,$2::jsonb),($3,$4::jsonb)",[A,JSON.stringify({username:'fixture_actor',display_name:'Actor'}),B,JSON.stringify({username:'fixture_recipient',display_name:'Recipient'})])
  const actor=(await pool.query('SELECT * FROM fallback_users WHERE id=$1',[A])).rows[0]
  const identity={displayUserId:alias,values:[A,alias],ids:new Set([A,alias])}
  const post={id:uid(),user_id:alias,visibility:'public'}
  const mentions=async(visibility='public')=>notifyPublicMentions(pool,{content:'@fixture_recipient @fixture_recipient @fixture_actor',targetId:post.id,actor,identity,type:'mention_post',publication:{...post,visibility}})
  await check('post mention persists once per recipient with exact post navigation metadata',async()=>{
    await mentions()
    const {rows}=await pool.query('SELECT * FROM fallback_notifications WHERE target_id=$1',[post.id])
    assert.equal(rows.length,1);assert.equal(rows[0].recipient_id,B)
    assert.equal(rows[0].target_type,'post');assert.equal(rows[0].type,'mention_post')
    assert.equal(rows[0].extra_data.post_id,post.id)
    assert.equal(rows[0].content,'Te mencionó en esta publicación.')
  })
  await pool.query('DELETE FROM fallback_notifications WHERE target_id=$1',[post.id])
  await check('private post cannot disclose a mention to another user',async()=>{
    await mentions('private');assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_notifications WHERE target_id=$1',[post.id])).rows[0].n),0)
  })
  await check('followers-only post requires the recipient to follow the author',async()=>{
    await mentions('followers');assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_notifications WHERE target_id=$1',[post.id])).rows[0].n),0)
    await pool.query('INSERT INTO fallback_follows(follower_id,following_id) VALUES($1,$2)',[B,alias])
    await mentions('followers');assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_notifications WHERE target_id=$1',[post.id])).rows[0].n),1)
  })
  await pool.query('DELETE FROM fallback_notifications WHERE target_id=$1',[post.id])
  await check('blocked user receives no post mention',async()=>{
    await pool.query('INSERT INTO fallback_user_blocks(blocker_id,blocked_id) VALUES($1,$2)',[B,A])
    await mentions();assert.equal(Number((await pool.query('SELECT count(*) n FROM fallback_notifications WHERE target_id=$1',[post.id])).rows[0].n),0)
  })
  return {passed:true,cases,productionAccountsCreated:false,productionMessagesSent:false,realPostgres:true,actualFCMDelivery:false}
}
main().then(report=>console.log(JSON.stringify(report))).catch(error=>{
  console.log(JSON.stringify({passed:false,cases,error:{message:error.message,code:error.code,where:error.where}}));process.exitCode=1
}).finally(()=>pool.end())
