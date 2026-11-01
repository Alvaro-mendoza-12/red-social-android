'use strict'
// Manual regression: accepts only the dedicated isolated audit PostgreSQL host/database.
// It never falls back to the production DATABASE_URL.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const { ensureActiveChallenge } = require('../src/data-routes')
const { submitChallenge, setChallengeVote, withChallengeTransaction, deleteChallengeMaster } = require('../src/challenge-mutations')

async function main() {
  const url = new URL(process.env.CHALLENGE_TEST_DATABASE_URL)
  assert.equal(url.hostname, 'novvin-audit101-challenges-db')
  assert.equal(url.pathname, '/audit101_isolated_challenges')
  const pool = new Pool({ connectionString: url.toString(), max: 8,
    connectionTimeoutMillis: 10_000, statement_timeout: 8_000 })
  const started = Date.now()
  try {
    assert.equal((await pool.query('SELECT current_database() AS db')).rows[0].db, 'audit101_isolated_challenges')
    await pool.query(`CREATE TABLE fallback_challenges_master (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(), title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
      challenge_type TEXT NOT NULL DEFAULT 'mixed', difficulty TEXT NOT NULL DEFAULT 'normal', creator_id TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
      CREATE TABLE fallback_active_challenges (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),challenge_id UUID NOT NULL REFERENCES fallback_challenges_master(id) ON DELETE CASCADE,
      expires_at TIMESTAMPTZ NOT NULL,voting_expires_at TIMESTAMPTZ,status TEXT NOT NULL DEFAULT 'active',
      winner_ids JSONB NOT NULL DEFAULT '[]'::jsonb,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
      CREATE TABLE fallback_challenge_submissions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),challenge_id TEXT NOT NULL,active_challenge_id TEXT NOT NULL,user_id TEXT NOT NULL,
      content_url TEXT,text_content TEXT,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(active_challenge_id,user_id));
      CREATE TABLE fallback_challenge_votes (submission_id TEXT NOT NULL,voter_id TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),PRIMARY KEY(submission_id,voter_id))`)
    const user = { id: randomUUID(), supabase_user_id: randomUUID(), email: 'isolated@example.test' }
    const identity = { displayUserId: user.supabase_user_id, ids: new Set([user.id, user.supabase_user_id]) }
    const another = { displayUserId: randomUUID() }; another.ids = new Set([another.displayUserId])
    const rounds = await Promise.all(Array.from({length:100}, () => ensureActiveChallenge(pool, user)))
    assert.equal(new Set(rounds.map(row => row.id)).size, 1)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_active_challenges')).rows[0].n, 1)
    let round = rounds[0]
    await pool.query("UPDATE fallback_active_challenges SET expires_at=NOW()-INTERVAL '1 second' WHERE id=$1::uuid",[round.id])
    const fresh = await ensureActiveChallenge(pool,user)
    assert.notEqual(fresh.id,round.id)
    assert.equal(fresh.status,'active')
    round = fresh
    await assert.rejects(() => deleteChallengeMaster(pool,identity,round.challenge_id), {code:'CHALLENGE_IN_USE'})
    assert.equal((await deleteChallengeMaster(pool,another,round.challenge_id)).rowCount,0)
    const submissions = await Promise.all(Array.from({length:20}, () => submitChallenge(pool, identity, round.id,
      { challengeId: round.challenge_id, textContent: 'Isolated fixture response' }, async () => true)))
    assert.equal(new Set(submissions.map(row => row.id)).size, 1)
    const submission = submissions[0]
    await pool.query('UPDATE fallback_challenge_submissions SET user_id=$2 WHERE id=$1::uuid',[submission.id,user.id])
    const aliasedRetry=await submitChallenge(pool,identity,round.id,{textContent:'Retry of legacy owner'},async()=>true)
    assert.equal(aliasedRetry.id,submission.id)
    assert.equal(aliasedRetry.user_id,identity.displayUserId)
    const input = { votedByMe: true, activeChallengeId: round.id }
    const votes = await Promise.all(Array.from({length:20}, () => setChallengeVote(pool, identity, submission.id, input)))
    assert.ok(votes.every(vote => vote.voted_by_me && vote.vote_count === 1))
    await Promise.all(Array.from({length:20}, () => setChallengeVote(pool, another, submission.id, input)))
    const removals = await Promise.all(Array.from({length:20}, () => setChallengeVote(pool, identity, submission.id,
      { ...input, votedByMe: false })))
    assert.ok(removals.every(vote => !vote.voted_by_me && vote.vote_count === 1))
    const duplicate=randomUUID()
    await pool.query(`INSERT INTO fallback_challenge_submissions(id,challenge_id,active_challenge_id,user_id,text_content)
      VALUES ($1::uuid,$2,$3,$4,'duplicate alias fixture')`,[duplicate,round.challenge_id,round.id,user.id])
    await pool.query('INSERT INTO fallback_challenge_votes(submission_id,voter_id) VALUES ($1,$2)',[duplicate,another.displayUserId])
    assert.equal((await submitChallenge(pool,identity,round.id,{textContent:'Consolidated own answer'},async()=>true)).id,submission.id)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_submissions WHERE active_challenge_id=$1',[round.id])).rows[0].n,1)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_votes WHERE submission_id=$1',[submission.id])).rows[0].n,1)
    await pool.query('INSERT INTO fallback_challenge_votes(submission_id,voter_id) VALUES ($1,$2)', [submission.id,user.id])
    assert.deepEqual(await setChallengeVote(pool, identity, submission.id, input), { vote_count:2,voted_by_me:true })
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_votes WHERE voter_id=$1', [user.id])).rows[0].n, 0)
    await assert.rejects(() => submitChallenge(pool, identity, randomUUID(), {textContent:'orphan'}, async () => true), {code:'CHALLENGE_NOT_FOUND'})
    await assert.rejects(() => setChallengeVote(pool, identity, randomUUID(), input), {code:'CHALLENGE_NOT_FOUND'})
    await assert.rejects(() => submitChallenge(pool, identity, round.id,
      {textContent:'wrong-master',challengeId:randomUUID()}, async () => true), {code:'CHALLENGE_ROUND_CHANGED'})
    await pool.query("UPDATE fallback_active_challenges SET expires_at=NOW()-INTERVAL '1 second' WHERE id=$1::uuid", [round.id])
    await assert.rejects(() => submitChallenge(pool, identity, round.id, {textContent:'late'}, async () => true), {code:'CHALLENGE_SUBMISSION_ENDED'})
    assert.equal((await ensureActiveChallenge(pool,user)).status, 'voting')
    await pool.query("UPDATE fallback_active_challenges SET voting_expires_at=NOW()-INTERVAL '1 second' WHERE id=$1::uuid", [round.id])
    const completed = await ensureActiveChallenge(pool,user)
    assert.equal(completed.status, 'completed')
    assert.ok(completed.winner_ids.includes(identity.displayUserId))
    await assert.rejects(() => setChallengeVote(pool, identity, submission.id, input), {code:'CHALLENGE_VOTING_ENDED'})
    await pool.query("UPDATE fallback_active_challenges SET expires_at=NOW()-INTERVAL '1 second' WHERE id=$1::uuid", [round.id])
    const next = await ensureActiveChallenge(pool,user)
    assert.notEqual(next.id, round.id)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_submissions')).rows[0].n, 0)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_votes')).rows[0].n, 0)
    const marker = randomUUID()
    await assert.rejects(() => withChallengeTransaction(pool, async client => {
      await client.query("INSERT INTO fallback_challenges_master(id,title) VALUES ($1::uuid,'rollback fixture')",[marker])
      throw new Error('intentional rollback')
    }), /intentional rollback/)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenges_master WHERE id=$1::uuid',[marker])).rows[0].n, 0)
    assert.equal((await ensureActiveChallenge(pool,user)).id,next.id)
    const expiredMaster=randomUUID(),expiredRound=randomUUID(),expiredSubmission=randomUUID()
    await pool.query("INSERT INTO fallback_challenges_master(id,title,creator_id) VALUES ($1::uuid,'expired owned fixture',$2)",[expiredMaster,user.id])
    await pool.query(`INSERT INTO fallback_active_challenges(id,challenge_id,status,expires_at)
      VALUES ($1::uuid,$2::uuid,'completed',NOW()-INTERVAL '1 second')`,[expiredRound,expiredMaster])
    await pool.query(`INSERT INTO fallback_challenge_submissions(id,challenge_id,active_challenge_id,user_id,text_content)
      VALUES ($1::uuid,$2,$3,$4,'expired fixture')`,[expiredSubmission,expiredMaster,expiredRound,user.id])
    await pool.query('INSERT INTO fallback_challenge_votes(submission_id,voter_id) VALUES ($1,$2)',[expiredSubmission,user.id])
    assert.equal((await deleteChallengeMaster(pool,identity,expiredMaster)).rowCount,1)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_votes WHERE submission_id=$1',[expiredSubmission])).rows[0].n,0)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_challenge_submissions WHERE id=$1::uuid',[expiredSubmission])).rows[0].n,0)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_active_challenges WHERE id=$1::uuid',[expiredRound])).rows[0].n,0)
    return {ok:true,database:'isolated-container',productionDataTouched:false,concurrentRoundReads:100,
      concurrentSubmissions:20,concurrentVoteOperations:60,oneRound:true,idempotentVotes:true,
      aliasVotesCanonicalized:true,expiredMutationsDenied:true,oldRoundPurged:true,rollbackReleasedLock:true,
      legacySubmissionOwnerCanonicalized:true,duplicateAliasSubmissionVotesPreserved:true,
      runningMasterDeletionDenied:true,expiredMasterDeletionPurgesChildren:true,
      emptyExpiredRoundSkipped:true,
      elapsedMillis:Date.now()-started}
  } finally { await pool.end() }
}
main().then(report=>console.log(JSON.stringify(report))).catch(error=>{
  console.error(JSON.stringify({ok:false,code:error.code||error.name,message:error.message}));process.exitCode=1
})
