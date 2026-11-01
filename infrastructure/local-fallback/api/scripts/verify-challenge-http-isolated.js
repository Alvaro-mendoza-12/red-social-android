'use strict'
// Manual integration check. Both endpoints MUST belong to the disposable audit network.
const assert = require('node:assert/strict')
const {randomUUID,randomBytes} = require('node:crypto')
const {Pool} = require('pg')
const base = new URL(process.env.CHALLENGE_TEST_API_URL || 'http://invalid/')
const database = new URL(process.env.CHALLENGE_TEST_DATABASE_URL || 'postgresql://invalid/invalid')
assert.equal(base.origin,'http://novvin-audit101-challenges-api:3001')
assert.equal(database.hostname,'novvin-audit101-challenges-db')
assert.equal(database.pathname,'/audit101_isolated_challenges')
const pool = new Pool({connectionString:database.toString(),max:2})
let requests=0
async function request(path,{token,method='GET',body,status=200}={}) {
  requests++
  const response=await fetch(new URL(path,base),{method,signal:AbortSignal.timeout(20_000),
    headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
    ...(body===undefined?{}:{body:JSON.stringify(body)})})
  const json=await response.json()
  assert.equal(response.status,status,`${method} ${path}: expected ${status}, received ${response.status}; ${json.error || 'unexpected response'}`)
  return json
}
async function register(label) {
  const body=await request('/v1/auth/register',{method:'POST',status:201,body:{
    email:`audit101-${label}-${randomUUID()}@example.invalid`,password:randomBytes(20).toString('hex'),
    displayName:`QA Rondas ${label}`,username:`qa101${randomBytes(5).toString('hex')}`}})
  const token=body.accessToken||body.access_token||body.token
  assert.equal(typeof token,'string','Registration must return the native access token')
  assert.ok(body.user?.id)
  return {token,user:body.user}
}
async function main() {
  const started=Date.now()
  try {
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_users')).rows[0].n,0,
      'A fresh fixture cannot contain existing users')
    await request('/v1/data/challenges/active',{status:401})
    const a=await register('A'),b=await register('B')
    const master=(await request('/v1/data/challenges/master',{token:a.token,method:'POST',status:201,
      body:{title:'Isolated HTTP prompt',description:'Temporary round for integration verification',type:'text'}})).challenge
    assert.ok(master?.id)
    const rounds=await Promise.all(Array.from({length:12},()=>request('/v1/data/challenges/active',{token:a.token})))
    const active=rounds[0].activeChallenge
    assert.equal(new Set(rounds.map(row=>row.activeChallenge.id)).size,1)
    // The engine can choose any of its seeded prompts, not necessarily the one just created.
    const roundMaster=active.challenge_id||active.challengeId
    assert.ok(roundMaster,'The active round must return its authoritative master')
    const id=active.id
    const invited=await request('/v1/data/notifications',{token:a.token,method:'POST',status:201,
      body:{recipientId:b.user.id,type:'challenge_invite',content:'Isolated round invitation',extraData:{challenge_title:active.challenge?.title||'Ronda'}}})
    assert.ok(invited.notification?.id,'Inviting a QA friend must produce a stored notification')
    const inbox=await request('/v1/data/notifications',{token:b.token})
    assert.ok(inbox.items.some(row=>row.id===invited.notification.id && row.type==='challenge_invite'),
      'The recipient must be able to load the invitation')
    assert.equal((await request(`/v1/data/challenges/${id}/participation`,{token:a.token})).participating,false)
    const input={challengeId:roundMaster,textContent:'Native HTTP fixture answer'}
    const first=(await request(`/v1/data/challenges/${id}/submissions`,{token:a.token,method:'POST',body:input,status:201})).submission
    assert.ok(first?.id)
    // Link a second identity only in this fresh fixture: existing answers must remain visible.
    const publicId=randomUUID()
    await pool.query('UPDATE fallback_users SET supabase_user_id=$2::uuid WHERE id=$1::uuid',[a.user.id,publicId])
    assert.equal((await request(`/v1/data/challenges/${id}/participation`,{token:a.token})).participating,true)
    assert.equal((await request(`/v1/data/challenges/${id}/submissions/me`,{token:a.token})).submission.id,first.id)
    const retry=(await request(`/v1/data/challenges/${id}/submissions`,{token:a.token,method:'POST',body:input,status:201})).submission
    assert.equal(retry.id,first.id)
    assert.equal(retry.user_id||retry.userId,publicId)
    assert.equal((await request(`/v1/data/challenges/${id}/submissions`,{token:b.token})).items.length,1)
    assert.ok((await request('/v1/data/challenges/master/mine',{token:a.token})).items.some(row=>row.id===master.id))
    const vote=`/v1/data/challenges/submissions/${first.id}/vote`
    for(let n=0;n<2;n++) {
      const result=await request(vote,{token:b.token,method:'POST',body:{votedByMe:true,activeChallengeId:id}})
      assert.equal(result.vote_count,1);assert.equal(result.voted_by_me,true)
    }
    assert.equal((await request(`/v1/data/challenges/${id}/submissions`,{token:b.token})).items[0].voted_by_me,true)
    assert.equal((await request(vote,{token:b.token,method:'POST',body:{votedByMe:false,activeChallengeId:id}})).vote_count,0)
    // Installed clients that still omit a desired state retain their toggle contract.
    assert.equal((await request(vote,{token:b.token,method:'POST',body:{}})).vote_count,1)
    assert.equal((await request(vote,{token:b.token,method:'POST',body:{}})).vote_count,0)
    await request(vote,{token:b.token,method:'POST',body:{votedByMe:true,activeChallengeId:randomUUID()},status:409})
    await request(`/v1/data/challenges/${id}/submissions`,{token:a.token,method:'POST',
      body:{...input,challengeId:randomUUID()},status:409})
    await request(`/v1/data/challenges/${randomUUID()}/submissions`,{token:a.token,method:'POST',body:input,status:404})
    await request(`/v1/data/challenges/master/${roundMaster}`,{token:a.token,method:'DELETE',status:409})
    await request(`/v1/data/challenges/master/${roundMaster}`,{token:b.token,method:'DELETE',status:404})
    const unused=(await request('/v1/data/challenges/master',{token:a.token,method:'POST',status:201,
      body:{title:'Unused own prompt',description:'Delete only this isolated unused prompt',type:'drawing'}})).challenge
    await request(`/v1/data/challenges/master/${unused.id}`,{token:a.token,method:'DELETE'})
    assert.ok(!(await request('/v1/data/challenges/master/mine',{token:a.token})).items.some(row=>row.id===unused.id))
    await pool.query("UPDATE fallback_active_challenges SET expires_at=NOW()-INTERVAL '1 second' WHERE id=$1::uuid",[id])
    await request(`/v1/data/challenges/${id}/submissions`,{token:a.token,method:'POST',body:input,status:409})
    assert.equal((await request('/v1/data/challenges/active',{token:a.token})).activeChallenge.status,'voting')
    await request(vote,{token:b.token,method:'POST',body:{votedByMe:true,activeChallengeId:id}})
    await pool.query("UPDATE fallback_active_challenges SET voting_expires_at=NOW()-INTERVAL '1 second' WHERE id=$1::uuid",[id])
    const completed=(await request('/v1/data/challenges/active',{token:a.token})).activeChallenge
    assert.equal(completed.status,'completed')
    assert.ok((completed.winner_ids||completed.winnerIds).includes(publicId))
    await request(vote,{token:b.token,method:'POST',body:{votedByMe:true,activeChallengeId:id},status:409})
    await pool.query("UPDATE fallback_active_challenges SET expires_at=NOW()-INTERVAL '1 second' WHERE id=$1::uuid",[id])
    const next=(await request('/v1/data/challenges/active',{token:a.token})).activeChallenge
    assert.notEqual(next.id,id)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_submissions')).rows[0].n,0)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_votes')).rows[0].n,0)
    return {ok:true,scope:'isolated-api-and-postgres',productionDataTouched:false,requests,
      anonymousDenied:true,accounts:2,oneRound:true,submissionRetryPreservesId:true,
      legacyIdentityParticipationFound:true,idempotentVotes:true,legacyToggleCompatible:true,
      invitationStoredAndReceived:true,
      invalidTargetsDenied:true,ownMasterDeletion:true,expiredMutationsDenied:true,
      votingAndPodium:true,winnerIsUserId:true,nextRoundPurgesChildren:true,elapsedMillis:Date.now()-started}
  } finally {await pool.end()}
}
main().then(report=>console.log(JSON.stringify(report))).catch(error=>{
  console.error(JSON.stringify({ok:false,code:error.code||error.name,message:error.message,location:error.stack?.split('\n')[1]?.trim()}));process.exitCode=1
})
