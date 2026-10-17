'use strict'
const test=require('node:test'),assert=require('node:assert/strict'),{randomUUID}=require('node:crypto'),{Pool}=require('pg'),express=require('express')
const {ensureCommunityQuestionTables,registerCommunityQuestionRoutes,collectionPublicationAllowed}=require('../src/community-questions')
const connectionString=process.env.RAFFLE_TEST_DATABASE_URL
test('question persistence, creator permissions, Unicode limits, votes, pagination and revoked access',{skip:!connectionString},async()=>{
  assert.ok(['localhost','127.0.0.1','[::1]'].includes(new URL(connectionString).hostname))
  const schema='question_test_'+randomUUID().replaceAll('-',''),admin=new Pool({connectionString});await admin.query(`CREATE SCHEMA ${schema}`)
  const pool=new Pool({connectionString,options:`-c search_path=${schema},public`});let server
  try{
    await pool.query('CREATE TABLE fallback_room_messages(id UUID PRIMARY KEY,room_kind TEXT,room_id TEXT,channel_id TEXT,sender_id TEXT,content TEXT,content_type TEXT,media_url TEXT,deleted_for_all BOOLEAN DEFAULT FALSE,created_at TIMESTAMPTZ DEFAULT NOW())')
    await ensureCommunityQuestionTables(pool);await ensureCommunityQuestionTables(pool)
    const owner={id:randomUUID(),display_name:'Creador'},member={id:randomUUID(),display_name:'María'},other={id:randomUUID()},outsider={id:randomUUID()},users=[owner,member,other,outsider],roomId=randomUUID();let revoked=false
    const app=express();app.use(express.json())
    registerCommunityQuestionRoutes(app,{pool,authRequired:(req,res,next)=>{req.auth={sub:req.headers['x-test-user']};return req.auth.sub?next():res.status(401).json({error:'AUTH_REQUIRED'})},getCurrentUser:async(_db,req)=>users.find(u=>u.id===req.auth.sub),userIdentity:u=>({ids:new Set([u.id]),displayUserId:u.id,localUserId:u.id}),
      loadCollectionRoomAccess:async(_db,user,kind,id)=>id!==roomId||user.id===outsider.id||revoked&&user.id===member.id?null:{room:{creator_id:owner.id},roomMembers:[owner,member,other].map(u=>({user_id:u.id})),historyAccess:{isMember:true,allHistory:true}},memberUserId:m=>m.user_id,rowMatchesAny:(r,keys,ids)=>keys.some(k=>ids.has(r[k])),publicProfilesByIds:async()=>[owner,member,other],ensureDefaultCommunityChannel:async()=>({id:randomUUID()}),publishRoomCard:async()=>{}})
    app.use((error,_req,res,_next)=>res.status(500).json({error:error.message}))
    server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s))})
    const base=`http://127.0.0.1:${server.address().port}/v1/data/questions/community/${roomId}`
    const call=(path='',user=owner,method='GET',body)=>fetch(base+path,{method,headers:{'Content-Type':'application/json','x-test-user':user.id},body:body?JSON.stringify(body):undefined})
    assert.equal((await call('',member,'POST',{prompt:'3 + 2',maxLength:20})).status,403)
    assert.equal((await call('/policy',member,'PATCH',{onlyCreator:true})).status,403)
    assert.equal((await call('/policy',owner,'PATCH',{onlyCreator:true})).status,200)
    assert.equal(await collectionPublicationAllowed(pool,'community',roomId,false),false)
    assert.equal(await collectionPublicationAllowed(pool,'community',roomId,true),true)
    const created=await call('',owner,'POST',{prompt:'3 + 2',maxLength:20});assert.equal(created.status,200);const q=await created.json()
    assert.equal((await call('/'+q.id,outsider)).status,403)
    assert.equal((await call('/'+q.id+'/answers',member,'POST',{content:'x'.repeat(21)})).status,400)
    assert.equal((await call('/'+q.id+'/answers',member,'POST',{content:'😀'.repeat(20)})).status,200)
    assert.equal((await call('/'+q.id+'/answers',member,'POST',{content:'5'})).status,200)
    let detail=await(await call('/'+q.id,member)).json();assert.equal(detail.total,1);assert.equal(detail.answers[0].content,'5')
    const answerId=detail.answers[0].id
    assert.equal((await call('/'+q.id+'/votes',other,'POST',{answerId,value:1})).status,200)
    await call('/'+q.id+'/votes',other,'POST',{answerId,value:-1})
    detail=await(await call('/'+q.id,other)).json();assert.equal(detail.answers[0].likes,0);assert.equal(detail.answers[0].dislikes,1);assert.equal(detail.answers[0].myVote,-1)
    await call('/'+q.id+'/votes',other,'POST',{answerId,value:0});assert.equal((await(await call('/'+q.id)).json()).answers[0].dislikes,0)
    assert.equal((await call('/'+q.id+'?offset=-1')).status,400)
    assert.equal((await call('/'+q.id+'/votes',other,'POST',{answerId:randomUUID(),value:1})).status,404)
    await pool.query("INSERT INTO fallback_question_answers(id,question_id,user_id,content,created_at) SELECT gen_random_uuid(),$1,'pagination-'||i,'Respuesta '||i,NOW()+i*INTERVAL '1 second' FROM generate_series(1,60) i",[q.id])
    detail=await(await call('/'+q.id,member)).json();assert.equal(detail.total,61);assert.equal(detail.answers.length,50);assert.equal(detail.nextOffset,50);assert.equal(detail.myAnswer.content,'5');assert.equal(detail.myAnswer.userId,member.id);assert.ok(!detail.answers.some(a=>a.userId===member.id))
    revoked=true;assert.equal((await call('/'+q.id,member)).status,403);assert.equal((await call('/'+q.id+'/answers',member,'POST',{content:'6'})).status,403)
    await pool.query('UPDATE fallback_room_messages SET deleted_for_all=TRUE WHERE id=$1',[q.messageId]);assert.equal((await call('/'+q.id)).status,404)
  }finally{if(server)await new Promise(resolve=>server.close(resolve));await pool.end();await admin.query(`DROP SCHEMA ${schema} CASCADE`);await admin.end()}
})
