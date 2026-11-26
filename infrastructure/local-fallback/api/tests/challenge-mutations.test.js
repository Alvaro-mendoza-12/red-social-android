'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { challengeTimeline, withChallengeTransaction, requireOpenRound, submitChallenge, setChallengeVote, deleteChallengeMaster } = require('../src/challenge-mutations')

const identity = { displayUserId: 'public-actor', ids: new Set(['public-actor', 'local-actor']) }
const active = () => ({ id: 'round', challenge_id: 'master', status: 'active',
  checked_at: '2026-11-01T12:00:00Z', expires_at: '2026-11-01T12:10:00Z', voting_expires_at: '2026-11-01T12:10:30Z' })

function fixture(reply = () => ({ rows: [] })) {
  const events = []
  const client = {
    async query(sql, values) { events.push({ sql, values }); return reply(sql, values) },
    release() { events.push({ sql: 'RELEASE' }) },
  }
  return { events, pool: { async connect() { events.push({ sql: 'CONNECT' }); return client } } }
}

test('round operations commit one transaction and release its connection', async () => {
  const f = fixture()
  assert.equal(await withChallengeTransaction(f.pool, async client => { await client.query('WORK'); return 'ok' }), 'ok')
  assert.deepEqual(f.events.map(event => event.sql), [
    'CONNECT', 'BEGIN', "SET LOCAL lock_timeout = '5s'", 'SELECT pg_advisory_xact_lock(19110, 101)', 'WORK', 'COMMIT', 'RELEASE',
  ])
})

test('failed operations roll back and release before propagating the original failure', async () => {
  const f = fixture()
  const error = new Error('database write failed')
  await assert.rejects(() => withChallengeTransaction(f.pool, async () => { throw error }), candidate => candidate === error)
  assert.deepEqual(f.events.slice(-2).map(event => event.sql), ['ROLLBACK', 'RELEASE'])
  assert.ok(!f.events.some(event => event.sql === 'COMMIT'))
})

test('an unavailable round lock has a bounded wait and a retryable public error', async () => {
  const f = fixture(sql => {
    if (sql.startsWith('SELECT pg_advisory')) throw Object.assign(new Error('lock timed out'), { code: '55P03' })
    return { rows: [] }
  })
  await assert.rejects(() => withChallengeTransaction(f.pool, async () => assert.fail('must not run')),
    { code: 'CHALLENGE_BUSY', status: 503 })
  assert.equal(f.events.at(-1).sql, 'RELEASE')
})

test('only the appropriate unexpired phases allow submissions and votes', () => {
  assert.doesNotThrow(() => requireOpenRound(active()))
  assert.doesNotThrow(() => requireOpenRound(active(), true))
  assert.doesNotThrow(() => requireOpenRound({ ...active(), status: 'voting' }, true))
  for (const row of [
    { ...active(), status: 'voting' }, { ...active(), status: 'completed' },
    { ...active(), expires_at: '2026-11-01T11:59:00Z' }, { ...active(), expires_at: 'invalid' },
  ]) assert.throws(() => requireOpenRound(row), { code: 'CHALLENGE_SUBMISSION_ENDED', status: 409 })
  for (const row of [
    { ...active(), status: 'completed' }, { ...active(), status: 'unknown' },
    { ...active(), voting_expires_at: '2026-11-01T11:59:00Z' },
  ]) assert.throws(() => requireOpenRound(row, true), { code: 'CHALLENGE_VOTING_ENDED', status: 409 })
})

test('a legacy vote without a deadline expires after its original window', () => {
  const round = { ...active(), status: 'voting', voting_expires_at: null }
  assert.equal(challengeTimeline(round, Date.parse('2026-11-01T12:10:29Z')).phase, 'voting')
  assert.equal(challengeTimeline(round, Date.parse('2026-11-01T12:10:30Z')).phase, 'completed')
  assert.equal(challengeTimeline(round, Date.parse('2026-11-01T12:10:36Z')).phase, 'expired')
  assert.throws(() => requireOpenRound({ ...round, checked_at: '2026-11-03T12:10:00Z' }, true),
    { code: 'CHALLENGE_VOTING_ENDED' })
})

test('a participant returning days later does not restart the vote or the podium', () => {
  const now = Date.parse('2026-11-03T12:00:00Z')
  const round = { ...active(), created_at: '2026-11-01T11:55:00Z' }
  for (const status of ['active', 'voting', 'completed']) {
    assert.equal(challengeTimeline({ ...round, status }, now).phase, 'expired')
  }
  const longLegacyRound = { ...round, expires_at: '2026-11-02T11:55:00Z', voting_expires_at: '2026-11-02T11:55:30Z' }
  assert.equal(challengeTimeline(longLegacyRound, Date.parse('2026-11-01T12:09:25Z')).phase, 'voting')
  assert.equal(challengeTimeline(longLegacyRound, Date.parse('2026-11-01T12:10:00Z')).phase, 'expired')
  assert.equal(challengeTimeline(longLegacyRound, now).phase, 'expired')
})

test('returning to an old voting round with a submission opens a different challenge immediately', async t => {
  const { PGlite } = require('@electric-sql/pglite')
  const { ensureActiveChallenge } = require('../src/data-routes')
  const db = new PGlite()
  t.after(() => db.close())
  await db.exec(`CREATE TABLE fallback_challenges_master (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), title TEXT, description TEXT,
    challenge_type TEXT, difficulty TEXT, creator_id TEXT, created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now());
    CREATE TABLE fallback_active_challenges (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(), challenge_id UUID, expires_at TIMESTAMPTZ,
      voting_expires_at TIMESTAMPTZ, status TEXT, winner_ids JSONB DEFAULT '[]',
      created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now());
    CREATE TABLE fallback_challenge_submissions (id UUID DEFAULT gen_random_uuid(), active_challenge_id TEXT);
    CREATE TABLE fallback_challenge_votes (submission_id TEXT);
    INSERT INTO fallback_challenges_master(title) SELECT 'Reto ' || value FROM generate_series(1,8) value;`)
  const { rows: [old] } = await db.query(`INSERT INTO fallback_active_challenges
    (challenge_id, expires_at, created_at, status)
    SELECT id, now() - interval '2 days', now() - interval '2 days 15 minutes', 'voting'
    FROM fallback_challenges_master ORDER BY title LIMIT 1 RETURNING id::text, challenge_id::text`)
  await db.query('INSERT INTO fallback_challenge_submissions(active_challenge_id) VALUES($1)', [old.id])
  const pool = { connect: async () => ({ query: (...args) => db.query(...args), release() {} }) }
  const next = await ensureActiveChallenge(pool, { id: 'test-user' })
  assert.equal(next.status, 'active')
  assert.notEqual(next.id, old.id)
  assert.notEqual(next.challenge_id, old.challenge_id)
  assert.equal(Date.parse(next.expires_at) - Date.parse(next.created_at), (14 * 60 + 24) * 1000)
  assert.equal(Date.parse(next.voting_expires_at) + 6000 - Date.parse(next.created_at), 15 * 60_000)
  const again = await ensureActiveChallenge(pool, { id: 'test-user' })
  assert.equal(again.id, next.id)
})

test('a missing round cannot create an orphan submission', async () => {
  const f = fixture()
  await assert.rejects(() => submitChallenge(f.pool, identity, 'missing', { textContent: 'text' }, async () => true),
    { code: 'CHALLENGE_NOT_FOUND', status: 404 })
  assert.ok(!f.events.some(event => event.sql.startsWith('INSERT')))
})

test('a submission cannot replace the authoritative master id', async () => {
  const f = fixture(sql => ({ rows: sql.includes('FROM fallback_active_challenges') ? [active()] : [] }))
  await assert.rejects(() => submitChallenge(f.pool, identity, 'round', { textContent: 'text', challengeId: 'another-master' }, async () => true),
    { code: 'CHALLENGE_ROUND_CHANGED', status: 409 })
  assert.ok(!f.events.some(event => event.sql.startsWith('INSERT')))
})

test('the server supplies the canonical master and actor for a valid submission', async () => {
  const f = fixture(sql => ({ rows: sql.includes('FROM fallback_active_challenges') ? [active()] : [{ id: 'submission' }] }))
  assert.deepEqual(await submitChallenge(f.pool, identity, 'ROUND', { textContent: ' response ' }, async () => true), { id: 'submission' })
  const write = f.events.find(event => event.sql.startsWith('INSERT INTO fallback_challenge_submissions'))
  assert.deepEqual(write.values, ['master', 'round', 'public-actor', null, 'response'])
})

test('media ownership is still required before accepting a challenge submission', async () => {
  const f = fixture()
  await assert.rejects(() => submitChallenge(f.pool, identity, 'round', { contentUrl: 'https://example.test/private' }, async () => false),
    { code: 'CHALLENGE_MEDIA_STORAGE_OWNERSHIP_REQUIRED', status: 403 })
  assert.deepEqual(f.events, [])
})

test('resubmitting a legacy actor alias keeps its submission and canonicalizes the owner', async () => {
  const f = fixture(sql => ({ rows: sql.includes('FROM fallback_active_challenges') ? [active()]
    : sql.startsWith('SELECT id::text,user_id') ? [{id:'kept',user_id:'local-actor'}]
    : sql.startsWith('INSERT INTO fallback_challenge_submissions') ? [{id:'kept'}] : [] }))
  assert.deepEqual(await submitChallenge(f.pool,identity,'round',{textContent:'retry'},async()=>true),{id:'kept'})
  const lookup=f.events.find(event=>event.sql.startsWith('SELECT id::text,user_id'))
  assert.deepEqual(lookup.values,['round',['public-actor','local-actor'],'public-actor'])
  const update=f.events.find(event=>event.sql.startsWith('UPDATE fallback_challenge_submissions'))
  assert.deepEqual(update.values,['kept','public-actor'])
  assert.ok(!f.events.some(event=>event.sql.startsWith('DELETE FROM fallback_challenge_submissions')))
})

test('duplicate owned aliases merge their votes before removing only the redundant submission', async () => {
  const f = fixture(sql => ({ rows: sql.includes('FROM fallback_active_challenges') ? [active()]
    : sql.startsWith('SELECT id::text,user_id') ? [{id:'canonical',user_id:'public-actor'},{id:'legacy',user_id:'local-actor'}]
    : sql.startsWith('INSERT INTO fallback_challenge_submissions') ? [{id:'canonical'}] : [] }))
  await submitChallenge(f.pool,identity,'round',{textContent:'updated'},async()=>true)
  const merge=f.events.findIndex(event=>event.sql.startsWith('INSERT INTO fallback_challenge_votes'))
  const remove=f.events.findIndex(event=>event.sql.startsWith('DELETE FROM fallback_challenge_submissions'))
  assert.ok(merge>0 && remove>merge)
  assert.deepEqual(f.events[merge].values,['canonical',['legacy']])
  assert.deepEqual(f.events[remove].values,[['legacy']])
})

test('a vote without a real submission and active round cannot create orphan rows', async () => {
  const f = fixture()
  await assert.rejects(() => setChallengeVote(f.pool, identity, 'missing', { votedByMe: true }),
    { code: 'CHALLENGE_NOT_FOUND', status: 404 })
  assert.ok(!f.events.some(event => event.sql.startsWith('INSERT')))
})

test('a malformed desired vote is rejected before accessing the database', async () => {
  for (const votedByMe of ['true', null, 1]) {
    const f = fixture()
    await assert.rejects(() => setChallengeVote(f.pool, identity, 'submission', { votedByMe }),
      { code: 'CHALLENGE_VOTE_STATE_INVALID', status: 400 })
    assert.deepEqual(f.events, [])
  }
})

test('a vote from an obsolete round is rejected before changing votes', async () => {
  const f = fixture(sql => ({ rows: sql.includes('JOIN fallback_active_challenges') ? [active()] : [] }))
  await assert.rejects(() => setChallengeVote(f.pool, identity, 'submission', { votedByMe: true, activeChallengeId: 'old-round' }),
    { code: 'CHALLENGE_ROUND_CHANGED', status: 409 })
  assert.ok(!f.events.some(event => event.sql.startsWith('DELETE') || event.sql.startsWith('INSERT')))
})

test('removing a vote uses all actor aliases and returns the confirmed count', async () => {
  const f = fixture(sql => ({ rows: sql.includes('JOIN fallback_active_challenges') ? [active()]
    : sql.includes('COUNT(*)') ? [{ total: 2 }] : [] }))
  assert.deepEqual(await setChallengeVote(f.pool, identity, 'submission', { votedByMe: false }), { vote_count: 2, voted_by_me: false })
  const removal = f.events.find(event => event.sql.startsWith('DELETE'))
  assert.deepEqual(removal.values, ['submission', ['public-actor', 'local-actor'], false, 'public-actor'])
  assert.ok(!f.events.some(event => event.sql.startsWith('INSERT')))
})

test('a master cannot be removed by someone outside its owner aliases', async () => {
  const f = fixture()
  assert.deepEqual(await deleteChallengeMaster(f.pool, identity, 'master'), { rowCount: 0 })
  assert.ok(!f.events.some(event => event.sql.startsWith('DELETE')))
})

test('deleting a currently running master is rejected without purging its participants', async () => {
  const f = fixture(sql => ({ rows: sql.includes('FROM fallback_challenges_master') ? [{ id:'master' }]
    : sql.includes('FROM fallback_active_challenges') ? [active()] : [] }))
  await assert.rejects(() => deleteChallengeMaster(f.pool, identity, 'master'), { code:'CHALLENGE_IN_USE',status:409 })
  assert.ok(!f.events.some(event => event.sql.startsWith('DELETE')))
})
