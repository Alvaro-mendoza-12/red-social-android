'use strict'
process.env.NOVVIN_EXPERIMENTAL_PURPOSES='true'
const {test}=require('node:test')
const assert=require('node:assert/strict')
const {randomUUID}=require('node:crypto')
const {Pool}=require('pg')
const express=require('express')
const {registerDataRoutes}=require('../src/data-routes')
const {ensurePurposeTables,purgePurposeForAccounts}=require('../src/purpose-store')
const A='11111111-1111-4111-8111-111111111111',B='22222222-2222-4222-8222-222222222222',C='33333333-3333-4333-8333-333333333333'
const ALIAS_A='44444444-4444-4444-8444-444444444444',ALIAS_B='55555555-5555-4555-8555-555555555555'
const POST='66666666-6666-4666-8666-666666666666',COMMENT='77777777-7777-4777-8777-777777777777'
const COMMUNITY='88888888-8888-4888-8888-888888888888',QUESTION='99999999-9999-4999-8999-999999999999',MESSAGE='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',EVENT='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const base='/v1/experimental/purpose'
const cmd=(action,expectedRevision=0,extra={})=>({action,expectedRevision,operationId:randomUUID(),...extra})
async function fixture(connectionString) {
  const url=new URL(connectionString)
  if (!['localhost','127.0.0.1'].includes(url.hostname) || url.pathname!=='/novvin_purpose_test') throw Error('Requires isolated loopback novvin_purpose_test')
  const pool=new Pool({connectionString,max:8})
  await ensurePurposeTables(pool);await ensurePurposeTables(pool)
  await pool.query(`INSERT INTO fallback_users(id,supabase_user_id,email,password_hash,display_name) VALUES
    ($1,$2,'qa-a@example.invalid','not-a-real-hash','Ana'),($3,$4,'qa-b@example.invalid','not-a-real-hash','Bruno'),($5,NULL,'qa-c@example.invalid','not-a-real-hash','Otro')`,[A,ALIAS_A,B,ALIAS_B,C])
  await pool.query('INSERT INTO fallback_posts(id,user_id,user_name,content) VALUES($1,$2,\'Ana\',\'¿Cómo lo resolvemos?\')',[POST,ALIAS_A])
  await pool.query('INSERT INTO fallback_post_comments(id,post_id,user_id,content) VALUES($1,$2,$3,\'Probar esta alternativa\')',[COMMENT,POST,ALIAS_B])
  await pool.query("INSERT INTO fallback_collection_rooms(id,room_kind,name,creator_id) VALUES($1,'community','QA comunidad',$2)",[COMMUNITY,ALIAS_A])
  await pool.query("INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role) VALUES('community',$1,$2,'owner'),('community',$1,$3,'member')",[COMMUNITY,ALIAS_A,ALIAS_B])
  await pool.query("INSERT INTO fallback_room_messages(id,room_kind,room_id,sender_id,content,content_type) VALUES($1,'community',$2,$3,'Pregunta de QA','question')",[MESSAGE,COMMUNITY,ALIAS_A])
  await pool.query("INSERT INTO fallback_community_questions(id,room_kind,room_id,creator_id,prompt,max_length,message_id) VALUES($1,'community',$2,$3,'¿Cómo empezar?',40,$4)",[QUESTION,COMMUNITY,ALIAS_A,MESSAGE])
  await pool.query("INSERT INTO fallback_community_events(id,community_id,title,starts_at,created_by) VALUES($1,$2,'Encuentro de QA',NOW()+INTERVAL '2 hours',$3)",[EVENT,COMMUNITY,ALIAS_A])
  await pool.query('INSERT INTO fallback_gaming_profiles(user_id,adult_confirmed) VALUES($1,TRUE),($2,TRUE)',[A,B])
  await pool.query("INSERT INTO fallback_game_catalog(id,name,platforms) VALUES('purpose-qa','Juego QA','[\"PC\"]')")
  await pool.query("INSERT INTO fallback_user_games(user_id,game_id,platform) VALUES($1,'purpose-qa','PC'),($2,'purpose-qa','PC')",[A,B])
  await pool.query("INSERT INTO fallback_gaming_connection_requests(requester_user_id,recipient_user_id,status,idempotency_key) VALUES($1,$2,'ACCEPTED','qa-purpose')",[A,B])
  const app=express();app.use(express.json({limit:'32kb'}))
  const authRequired=(req,res,next)=>{const actor=({ 'Bearer qa-a':A,'Bearer qa-b':B,'Bearer qa-c':C })[req.headers.authorization];if(!actor)return res.status(401).json({error:'UNAUTHORIZED'});req.auth={sub:actor};next()}
  registerDataRoutes(app,{pool,authRequired,appealAuthRequired:authRequired})
  app.use((error,_req,res,_next)=>{console.error('Purpose QA error',{code:error.code,message:error.message,stack:error.stack});res.status(500).json({error:'QA_FAILURE'})})
  return {pool,app}
}
test('complete purpose flows use HTTP, existing permissions and real PostgreSQL', {skip:!process.env.PURPOSE_TEST_DATABASE_URL || process.env.PURPOSE_QA_SERVE==='true'},async t=>{
  const {pool,app}=await fixture(process.env.PURPOSE_TEST_DATABASE_URL)
  const server=await new Promise(resolve=>{const value=app.listen(0,'127.0.0.1',()=>resolve(value))})
  t.after(async()=>{await new Promise(resolve=>server.close(resolve));await pool.end()})
  const origin=`http://127.0.0.1:${server.address().port}`
  const request=async(path,actor='qa-a',command=null,status=200)=>{
    const response=await fetch(origin+base+path,{method:command?'POST':'GET',headers:{Authorization:`Bearer ${actor}`,'Content-Type':'application/json'},...(command?{body:JSON.stringify(command)}:{})})
    const body=await response.json();assert.equal(response.status,status,JSON.stringify(body));return body
  }
  await t.test('social: origin, author, references, lost acknowledgement, conflict and withdrawal',async()=>{
    assert.equal((await request('/capabilities')).supported,true)
    const path=`/posts/${POST}/outcome`
    const initial=await request(path);assert.equal(initial.canEdit,true);assert.equal(initial.eligible[0].id,COMMENT)
    await request(path,'qa-b',cmd('start'),403)
    const start=cmd('start')
    const pair=await Promise.all([request(path,'qa-a',start),request(path,'qa-a',start)])
    assert.deepEqual(pair.map(row=>row.outcome.revision),[1,1])
    assert.equal((await pool.query('SELECT count(*)::int AS total FROM experimental_purpose_operations WHERE operation_id=$1',[start.operationId])).rows[0].total,1)
    const conclude=cmd('conclude',1,{conclusion:'La alternativa sí nos ayudó.',contributionIds:[COMMENT]})
    let result=await request(path,'qa-a',conclude);assert.equal(result.outcome.status,'concluded');assert.equal(result.contributions[0].content,'Probar esta alternativa')
    await request(path,'qa-a',cmd('reopen',1),409)
    await request(path,'qa-a',{...conclude,conclusion:'Un reintento diferente'},409)
    await pool.query('UPDATE fallback_post_comments SET is_deleted=TRUE WHERE id=$1',[COMMENT])
    result=await request(path,'qa-a',conclude);assert.equal(result.outcome.revision,2);assert.equal(result.contributions[0].available,false)
    result=await request(path,'qa-a',cmd('retract',2));assert.equal(result.outcome.conclusion,'');assert.equal(result.outcome.status,'withdrawn')
    await pool.query('INSERT INTO fallback_user_blocks(blocker_id,blocked_id) VALUES($1,$2)',[ALIAS_B,ALIAS_A])
    await request(path,'qa-b',null,404)
    await pool.query('DELETE FROM fallback_user_blocks WHERE blocker_id=$1 AND blocked_id=$2',[ALIAS_B,ALIAS_A])
  })
  await t.test('community: real destinations, explicit guide offer, expiry, report after closure and membership loss',async()=>{
    const path=`/communities/${COMMUNITY}`
    await request(path,'qa-c',null,403)
    let data=await request(path,'qa-b')
    assert.equal(data.targets.find(row=>row.kind==='question').messageId,MESSAGE)
    assert.equal(data.targets.find(row=>row.kind==='event').id,EVENT)
    await request(path+'/guide','qa-b',cmd('offer',0,{hours:2}))
    assert.equal((await request(path)).targets.find(row=>row.kind==='guide').id,ALIAS_B)
    await pool.query("UPDATE experimental_purpose_objects SET state=jsonb_set(state,'{expiresAt}',to_jsonb((NOW()-INTERVAL '1 hour')::text)) WHERE kind='guide'")
    assert.equal((await request(path)).targets.some(row=>row.kind==='guide'),false)
    await request(path+'/guide','qa-b',cmd('offer',1,{hours:8}))
    data=await request(path+'/community_start','qa-b',cmd('select',0,{targetKind:'question',targetId:QUESTION}))
    assert.equal(data.journey.opened,false);assert.equal(data.journey.outcome,null)
    data=await request(path+'/community_start','qa-b',cmd('opened',1));assert.equal(data.journey.opened,true)
    await pool.query('UPDATE fallback_community_questions SET closed=TRUE WHERE id=$1',[QUESTION])
    await request(path+'/community_start','qa-b',cmd('opened',2),409)
    data=await request(path+'/community_start','qa-b',cmd('report',2,{outcome:'helped'}));assert.equal(data.journey.outcome,'helped')
    await pool.query("UPDATE fallback_collection_members SET is_deleted=TRUE WHERE room_id=$1 AND user_id=$2",[COMMUNITY,ALIAS_B])
    assert.equal((await request(path)).targets.some(row=>row.kind==='guide'),false)
    await request(path,'qa-b',null,403)
    await pool.query('UPDATE fallback_collection_members SET is_deleted=FALSE WHERE room_id=$1 AND user_id=$2',[COMMUNITY,ALIAS_B])
  })
  await t.test('gaming: invite, both accept, reconfirm, revise, cancel, private outcome and revoked connection',async()=>{
    const proposal=await request(`/gaming/with/${ALIAS_B}`);assert.equal(proposal.games[0].id,'purpose-qa')
    const planId=randomUUID(),path=`/gaming/${planId}`
    const fields={gameId:'purpose-qa',objective:'Practicar una ronda',durationMinutes:45,startsAt:new Date(Date.now()+45*60000).toISOString()}
    const create=cmd('propose',0,fields)
    let data=await request(`/gaming/with/${ALIAS_B}/${planId}`,'qa-a',create)
    await request(`/gaming/with/${ALIAS_B}/${planId}`,'qa-a',create)
    const notice=(await pool.query("SELECT recipient_id,target_type,extra_data FROM fallback_notifications WHERE type='gaming_plan' AND target_id=$1",[planId])).rows
    assert.equal(notice.length,1);assert.equal(notice[0].recipient_id,ALIAS_B);assert.equal(notice[0].target_type,'gaming_plan');assert.equal(notice[0].extra_data.plan_id,planId)
    assert.equal(data.plan.status,'pending')
    assert.equal((await request('/gaming','qa-b')).plans[0].id,planId)
    await request(path,'qa-c',null,404)
    data=await request(path+'/actions','qa-a',cmd('accept',1))
    await request(path+'/actions','qa-b',cmd('accept',1),409)
    data=await request(path+'/actions','qa-b',cmd('accept',2));assert.equal(data.plan.status,'agreed')
    data=await request(path+'/actions','qa-a',cmd('confirm',3))
    data=await request(path+'/actions','qa-b',cmd('confirm',4));assert.equal(data.plan.status,'ready')
    await request(path+'/actions','qa-b',cmd('replan',5,fields),403)
    data=await request(path+'/actions','qa-a',cmd('replan',5,{...fields,startsAt:new Date(Date.now()+2*3600000).toISOString()}))
    assert.equal(data.plan.planRevision,2);assert.deepEqual(data.plan.accepted,{})
    assert.equal((await pool.query("SELECT count(*)::int AS total FROM fallback_notifications WHERE type='gaming_plan' AND target_id=$1",[planId])).rows[0].total,2)
    data=await request(path+'/actions','qa-b',cmd('decline',6));assert.equal(data.plan.status,'cancelled')
    await request(path+'/actions','qa-a',cmd('feedback',7,{outcome:'played'}),409)
    await pool.query("UPDATE experimental_purpose_objects SET state=jsonb_set(state,'{startsAt}',to_jsonb((NOW()-INTERVAL '1 hour')::text)) WHERE kind='gaming' AND object_key=$1",[planId])
    data=await request(path+'/actions','qa-a',cmd('feedback',7,{outcome:'played'}));assert.equal(data.plan.myOutcome,'played')
    data=await request(path,'qa-b');assert.equal(data.plan.myOutcome,null);assert.equal(Object.hasOwn(data.plan,'outcomes'),false)
    await pool.query("UPDATE fallback_gaming_connection_requests SET status='REJECTED' WHERE requester_user_id=$1 AND recipient_user_id=$2",[A,B])
    assert.equal((await request('/gaming','qa-b')).plans.length,0)
    await request(path,'qa-b',null,403)
  })
  await t.test('deletion removes account objects, shared plans and operation receipts inside the owning transaction',async()=>{
    const db=await pool.connect()
    try {
      await db.query('BEGIN')
      const result=await purgePurposeForAccounts(db,[A,ALIAS_A])
      assert.ok(result.objects>=2);assert.ok(result.operations>=1)
      assert.equal((await db.query("SELECT count(*)::int AS total FROM experimental_purpose_objects WHERE kind='gaming'")).rows[0].total,0)
      await db.query('ROLLBACK')
      assert.ok((await pool.query("SELECT count(*)::int AS total FROM experimental_purpose_objects WHERE kind='gaming'")).rows[0].total>0)
    } finally {db.release()}
    assert.equal((await fetch(origin+base+'/gaming')).status,401)
  })
})
module.exports={fixture}
