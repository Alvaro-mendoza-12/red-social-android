'use strict'

function problem(code, status = 400) { return Object.assign(new Error(code), { code, status }) }
const clean = value => String(value || '').trim().toLowerCase()

/** The global round and its mutations use the same short transaction-level lock. */
async function withChallengeTransaction(pool, operation) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    await client.query("SET LOCAL lock_timeout = '5s'")
    await client.query('SELECT pg_advisory_xact_lock(19110, 101)')
    const result = await operation(client)
    await client.query('COMMIT')
    return result
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    if (error.code === '55P03') throw problem('CHALLENGE_BUSY', 503)
    throw error
  } finally { client.release() }
}

function requireOpenRound(row, voting = false) {
  if (!row) throw problem('CHALLENGE_NOT_FOUND', 404)
  const now = new Date(row.checked_at).getTime()
  const deadline = new Date(voting ? row.voting_expires_at || row.expires_at : row.expires_at).getTime()
  const allowed = voting ? ['active', 'voting'].includes(row.status) : row.status === 'active'
  if (!allowed || !Number.isFinite(now) || !Number.isFinite(deadline) || deadline <= now)
    throw problem(voting ? 'CHALLENGE_VOTING_ENDED' : 'CHALLENGE_SUBMISSION_ENDED', 409)
}

async function purgeChallengeRound(client, activeChallengeId) {
  const id = clean(activeChallengeId)
  if (!id) return
  await client.query(`DELETE FROM fallback_challenge_votes WHERE lower(submission_id) IN
    (SELECT lower(id::text) FROM fallback_challenge_submissions WHERE lower(active_challenge_id)=$1)`, [id])
  await client.query('DELETE FROM fallback_challenge_submissions WHERE lower(active_challenge_id)=$1', [id])
  await client.query('DELETE FROM fallback_active_challenges WHERE lower(id::text)=$1', [id])
}

async function deleteChallengeMaster(pool, identity, challengeId) {
  const id = clean(challengeId)
  const aliases = [...new Set([identity.displayUserId, ...Array.from(identity.ids || [])].map(clean).filter(Boolean))]
  return withChallengeTransaction(pool, async client => {
    const { rows: masters } = await client.query(`SELECT id FROM fallback_challenges_master
      WHERE lower(id::text)=$1 AND lower(creator_id)=ANY($2::text[]) FOR UPDATE`, [id, aliases])
    if (masters.length === 0) return { rowCount: 0 }
    const { rows: rounds } = await client.query(`SELECT id::text,status,expires_at,voting_expires_at,
      clock_timestamp() AS checked_at FROM fallback_active_challenges WHERE challenge_id=$1::uuid FOR UPDATE`, [id])
    const isRunning = rounds.some(round => {
      const deadline = round.status === 'completed' ? round.expires_at : round.voting_expires_at || round.expires_at
      return new Date(deadline).getTime() > new Date(round.checked_at).getTime()
    })
    if (isRunning) throw problem('CHALLENGE_IN_USE', 409)
    for (const round of rounds) await purgeChallengeRound(client, round.id)
    return client.query(`DELETE FROM fallback_challenges_master
      WHERE lower(id::text)=$1 AND lower(creator_id)=ANY($2::text[])`, [id, aliases])
  })
}

async function submitChallenge(pool, identity, activeChallengeId, input, authorizeMedia) {
  const activeId = clean(activeChallengeId)
  const actor = clean(identity.displayUserId)
  const contentUrl = String(input.contentUrl || input.content_url || '').trim() || null
  const textContent = String(input.textContent || input.text_content || '').trim() || null
  const requestedMaster = clean(input.challengeId || input.challenge_id)
  if (!actor || !activeId || activeId.length > 128 || (!contentUrl && !textContent))
    throw problem('ACTIVE_CHALLENGE_AND_CONTENT_REQUIRED')
  if (textContent && textContent.length > 10_000) throw problem('CHALLENGE_TEXT_TOO_LONG', 413)
  if (contentUrl && !(await authorizeMedia(contentUrl))) throw problem('CHALLENGE_MEDIA_STORAGE_OWNERSHIP_REQUIRED', 403)
  return withChallengeTransaction(pool, async client => {
    const { rows: rounds } = await client.query(`SELECT id::text, challenge_id::text, status,
      expires_at, voting_expires_at, clock_timestamp() AS checked_at FROM fallback_active_challenges
      WHERE lower(id::text)=$1 FOR UPDATE`, [activeId])
    const round = rounds[0]
    requireOpenRound(round)
    const master = clean(round.challenge_id)
    if (requestedMaster && requestedMaster !== master) throw problem('CHALLENGE_ROUND_CHANGED', 409)
    const { rows } = await client.query(`INSERT INTO fallback_challenge_submissions
      (challenge_id,active_challenge_id,user_id,content_url,text_content)
      VALUES ($1,$2,$3,$4,$5) ON CONFLICT (active_challenge_id,user_id)
      DO UPDATE SET challenge_id=EXCLUDED.challenge_id,content_url=EXCLUDED.content_url,
        text_content=EXCLUDED.text_content,updated_at=NOW()
      RETURNING id::text,challenge_id,active_challenge_id,user_id,content_url,text_content,created_at,updated_at`,
    [master, activeId, actor, contentUrl, textContent])
    return rows[0]
  })
}

async function setChallengeVote(pool, identity, submissionId, input = {}) {
  const id = clean(submissionId)
  const actor = clean(identity.displayUserId)
  const aliases = [...new Set([actor, ...Array.from(identity.ids || [])].map(clean).filter(Boolean))]
  const field = Object.hasOwn(input, 'votedByMe') ? 'votedByMe' : Object.hasOwn(input, 'voted_by_me') ? 'voted_by_me' : null
  if (!actor || !id || id.length > 128) throw problem('CHALLENGE_SUBMISSION_REQUIRED')
  if (field && typeof input[field] !== 'boolean') throw problem('CHALLENGE_VOTE_STATE_INVALID')
  return withChallengeTransaction(pool, async client => {
    const { rows } = await client.query(`SELECT a.id::text, a.challenge_id::text, a.status,
      a.expires_at,a.voting_expires_at,clock_timestamp() AS checked_at
      FROM fallback_challenge_submissions s JOIN fallback_active_challenges a
        ON lower(a.id::text)=lower(s.active_challenge_id)
      WHERE lower(s.id::text)=$1 FOR UPDATE OF a,s`, [id])
    const round = rows[0]
    requireOpenRound(round, true)
    const expectedRound = clean(input.activeChallengeId || input.active_challenge_id)
    if (expectedRound && expectedRound !== clean(round.id)) throw problem('CHALLENGE_ROUND_CHANGED', 409)
    const current = await client.query(`SELECT 1 FROM fallback_challenge_votes
      WHERE lower(submission_id)=$1 AND lower(voter_id)=ANY($2::text[]) LIMIT 1`, [id, aliases])
    const votedByMe = field ? input[field] : current.rows.length === 0
    await client.query(`DELETE FROM fallback_challenge_votes WHERE lower(submission_id)=$1
      AND lower(voter_id)=ANY($2::text[]) AND ($3=FALSE OR lower(voter_id)<>$4)`, [id, aliases, votedByMe, actor])
    if (votedByMe) await client.query(`INSERT INTO fallback_challenge_votes(submission_id,voter_id)
      VALUES ($1,$2) ON CONFLICT (submission_id,voter_id) DO NOTHING`, [id, actor])
    const count = await client.query(`SELECT COUNT(*)::int AS total FROM fallback_challenge_votes
      WHERE lower(submission_id)=$1`, [id])
    return { vote_count: Number(count.rows[0]?.total || 0), voted_by_me: votedByMe }
  })
}

module.exports = { withChallengeTransaction, requireOpenRound, purgeChallengeRound, deleteChallengeMaster, submitChallenge, setChallengeVote }
