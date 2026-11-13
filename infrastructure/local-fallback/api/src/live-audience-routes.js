'use strict'

const crypto = require('node:crypto')
const { createDistributedRateLimitMiddleware } = require('./distributed-rate-limit')
const { publishRealtimeEvent } = require('./realtime-publish')

async function ensureLiveAudienceTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_audience_items (
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id uuid NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      author_id text NOT NULL,
      author_name text NOT NULL,
      kind text NOT NULL CHECK (kind IN ('poll', 'question')),
      content text NOT NULL CHECK (char_length(content) BETWEEN 1 AND 300),
      options jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(options) = 'array'),
      status text NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed','answered','dismissed')),
      answer text NOT NULL DEFAULT '' CHECK (char_length(answer) <= 500),
      highlighted boolean NOT NULL DEFAULT false,
      request_nonce uuid NOT NULL,
      closes_at timestamptz,
      created_at timestamptz NOT NULL DEFAULT now(),
      updated_at timestamptz NOT NULL DEFAULT now(),
      UNIQUE (live_id, author_id, request_nonce)
    );
    CREATE UNIQUE INDEX IF NOT EXISTS idx_live_audience_open_poll
      ON fallback_live_audience_items(live_id) WHERE kind = 'poll' AND status = 'open';
    CREATE INDEX IF NOT EXISTS idx_live_audience_timeline
      ON fallback_live_audience_items(live_id, created_at DESC, id DESC);
    CREATE TABLE IF NOT EXISTS fallback_live_audience_votes (
      item_id uuid NOT NULL REFERENCES fallback_live_audience_items(id) ON DELETE CASCADE,
      user_id text NOT NULL,
      choice_index smallint NOT NULL CHECK (choice_index BETWEEN 0 AND 5),
      created_at timestamptz NOT NULL DEFAULT now(),
      PRIMARY KEY (item_id, user_id)
    )
  `)
}

function fail(code, status = 400) {
  return Object.assign(new Error(code), { status })
}

function uuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(String(value || ''))
    ? String(value).toLowerCase() : null
}

function text(value, maximum) {
  if (typeof value !== 'string') throw fail('LIVE_AUDIENCE_TEXT_INVALID')
  const result = value.trim()
  if (!result || result.length > maximum || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(result)) {
    throw fail('LIVE_AUDIENCE_TEXT_INVALID')
  }
  return result
}

async function transaction(pool, work) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const result = await work(client)
    await client.query('COMMIT')
    return result
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally { client.release() }
}

async function expirePolls(db, liveId) {
  await db.query(`UPDATE fallback_live_audience_items SET status='closed', updated_at=now()
    WHERE live_id=$1 AND kind='poll' AND status='open' AND closes_at<=now()`, [liveId])
}

async function snapshot(db, context) {
  await expirePolls(db, context.liveId)
  const { rows } = await db.query(`SELECT * FROM fallback_live_audience_items
    WHERE live_id=$1 AND (status<>'dismissed' OR author_id=ANY($2::text[]) OR $3)
    ORDER BY (kind='poll' AND status='open') DESC, highlighted DESC, created_at DESC, id DESC LIMIT 81`,
  [context.liveId, context.identity.values, context.canModerate])
  const items = rows.slice(0, 80)
  const ids = items.map(item => item.id)
  const votes = await db.query(`SELECT item_id,choice_index,count(*)::int AS total,
    bool_or(user_id=ANY($2::text[])) AS mine FROM fallback_live_audience_votes
    WHERE item_id=ANY($1::uuid[]) GROUP BY item_id,choice_index`, [ids, context.identity.values])
  return {
    canModerate: context.canModerate,
    hasMore: rows.length > 80,
    items: items.map(item => {
      const counts = Array(item.kind === 'poll' ? item.options.length : 1).fill(0)
      let myChoice = null
      for (const vote of votes.rows.filter(vote => vote.item_id === item.id)) {
        counts[vote.choice_index] = vote.total
        if (vote.mine) myChoice = vote.choice_index
      }
      return {
        id: item.id, kind: item.kind, content: item.content, options: item.options,
        status: item.status, answer: item.answer, highlighted: item.highlighted,
        authorId: item.author_id, authorName: item.author_name,
        closesAt: item.closes_at, createdAt: item.created_at,
        counts, totalVotes: counts.reduce((a, b) => a + b, 0), myChoice,
      }
    }),
  }
}

function registerLiveAudienceRoutes(app, { pool, authRequired, getCurrentUser, userIdentity,
  streamById, ownsStream, canViewStream, moderationMembership, containsBlockedWord,
  publishEvent = publishRealtimeEvent }) {
  async function context(req, db = pool, writable = false) {
    const user = await getCurrentUser(db, req)
    const liveId = uuid(req.params.liveId)
    if (!user || !liveId) throw fail('LIVE_NOT_FOUND', 404)
    const stream = await streamById(db, liveId, { forUpdate: writable })
    const identity = userIdentity(user)
    if (!stream || !(await canViewStream(db, stream, identity))) throw fail('LIVE_NOT_FOUND', 404)
    if (!stream.is_active && (writable || !ownsStream(stream, identity))) throw fail('LIVE_NOT_ACTIVE', 409)
    const membership = await moderationMembership(db, stream, identity)
    return { liveId, user, identity, stream, canModerate: membership.canModerate }
  }
  const limit = createDistributedRateLimitMiddleware({
    pool, scope: 'live:audience', limit: 30, windowMs: 60_000,
    code: 'LIVE_AUDIENCE_RATE_LIMITED', key: req => `${req.auth?.sub}:${req.params.liveId}`,
    methods: ['POST', 'PATCH'],
  })
  const changed = liveId => publishEvent(`live-audience:${liveId}`, { event: 'audience_updated' })
  function route(work) {
    return async (req, res, next) => {
      try { await work(req, res) } catch (error) {
        if (error.status) return res.status(error.status).json({ error: error.message })
        next(error)
      }
    }
  }

  app.get('/v1/data/live/:liveId/audience', authRequired, route(async (req, res) => {
    const scope = await context(req)
    res.setHeader('Cache-Control', 'private, no-store')
    res.json(await snapshot(pool, scope))
  }))

  for (const kind of ['poll', 'question']) {
    app.post(`/v1/data/live/:liveId/audience/${kind}s`, authRequired, limit, route(async (req, res) => {
      const nonce = uuid(req.body?.requestNonce)
      if (!nonce) throw fail('LIVE_AUDIENCE_NONCE_REQUIRED')
      const content = text(req.body?.content, 300)
      let options = [], duration = null
      if (kind === 'poll') {
        if (!Array.isArray(req.body?.options) || req.body.options.length < 2 || req.body.options.length > 6) {
          throw fail('LIVE_POLL_OPTIONS_INVALID')
        }
        options = req.body.options.map(option => text(option, 80))
        if (new Set(options.map(option => option.toLocaleLowerCase())).size !== options.length) throw fail('LIVE_POLL_OPTIONS_INVALID')
        duration = Number(req.body.durationSeconds ?? 300)
        if (!Number.isInteger(duration) || duration < 30 || duration > 3600) throw fail('LIVE_POLL_DURATION_INVALID')
      }
      const outcome = await transaction(pool, async db => {
        const scope = await context(req, db, true)
        if (kind === 'poll' && !scope.canModerate) throw fail('LIVE_MODERATOR_REQUIRED', 403)
        if (!scope.canModerate && containsBlockedWord?.(content, scope.stream.blocked_words)) {
          throw fail('LIVE_COMMENT_BLOCKED', 422)
        }
        const previous = await db.query(`SELECT * FROM fallback_live_audience_items
          WHERE live_id=$1 AND author_id=$2 AND request_nonce=$3`,
        [scope.liveId, scope.identity.displayUserId, nonce])
        if (previous.rows[0]) {
          const item = previous.rows[0]
          const previousDuration = item.closes_at ? Math.round((new Date(item.closes_at) - new Date(item.created_at)) / 1000) : null
          if (item.kind !== kind || item.content !== content || JSON.stringify(item.options) !== JSON.stringify(options) || previousDuration !== duration) {
            throw fail('LIVE_AUDIENCE_NONCE_CONFLICT', 409)
          }
          return { scope, id: item.id, created: false }
        }
        await expirePolls(db, scope.liveId)
        if (kind === 'poll') {
          const active = await db.query("SELECT 1 FROM fallback_live_audience_items WHERE live_id=$1 AND kind='poll' AND status='open'", [scope.liveId])
          if (active.rowCount) throw fail('LIVE_POLL_ALREADY_OPEN', 409)
        }
        const id = crypto.randomUUID()
        await db.query(`INSERT INTO fallback_live_audience_items(id,live_id,author_id,author_name,kind,content,options,request_nonce,closes_at)
          VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,$8,CASE WHEN $9::int IS NULL THEN NULL ELSE now()+$9*interval '1 second' END)`,
        [id, scope.liveId, scope.identity.displayUserId, String(scope.user.display_name || 'Participante').slice(0, 160),
          kind, content, JSON.stringify(options), nonce, duration])
        return { scope, id, created: true }
      })
      if (outcome.created) await changed(outcome.scope.liveId)
      res.status(outcome.created ? 201 : 200).json({ id: outcome.id, ...await snapshot(pool, outcome.scope) })
    }))
  }

  app.post('/v1/data/live/:liveId/audience/items/:itemId/vote', authRequired, limit, route(async (req, res) => {
    const outcome = await transaction(pool, async db => {
      const scope = await context(req, db, true)
      await expirePolls(db, scope.liveId)
      const { rows } = await db.query('SELECT * FROM fallback_live_audience_items WHERE id=$1 AND live_id=$2 FOR UPDATE',
        [uuid(req.params.itemId), scope.liveId])
      const item = rows[0]
      if (!item || item.status === 'dismissed') throw fail('LIVE_AUDIENCE_ITEM_NOT_FOUND', 404)
      const choice = req.body?.choiceIndex
      if (!Number.isInteger(choice) || choice < 0 || choice >= (item.kind === 'poll' ? item.options.length : 1)) throw fail('LIVE_POLL_CHOICE_INVALID')
      if (item.status !== 'open') throw fail('LIVE_POLL_CLOSED', 409)
      const voted = await db.query(`INSERT INTO fallback_live_audience_votes(item_id,user_id,choice_index)
        VALUES($1,$2,$3) ON CONFLICT(item_id,user_id) DO UPDATE SET choice_index=EXCLUDED.choice_index
        WHERE fallback_live_audience_votes.choice_index<>EXCLUDED.choice_index RETURNING item_id`,
      [item.id, scope.identity.displayUserId, choice])
      return { scope, changed: voted.rowCount > 0 }
    })
    if (outcome.changed) await changed(outcome.scope.liveId)
    res.json(await snapshot(pool, outcome.scope))
  }))

  app.patch('/v1/data/live/:liveId/audience/items/:itemId', authRequired, limit, route(async (req, res) => {
    const scope = await transaction(pool, async db => {
      const current = await context(req, db, true)
      const { rows } = await db.query('SELECT * FROM fallback_live_audience_items WHERE id=$1 AND live_id=$2 FOR UPDATE',
        [uuid(req.params.itemId), current.liveId])
      const item = rows[0]
      if (!item) throw fail('LIVE_AUDIENCE_ITEM_NOT_FOUND', 404)
      const status = req.body?.status || item.status
      const allowed = item.kind === 'poll' ? ['open','closed','dismissed'] : ['open','answered','dismissed']
      if (!allowed.includes(status) || (item.status !== 'open' && status === 'open')) throw fail('LIVE_AUDIENCE_STATUS_INVALID')
      const ownRemoval = item.kind === 'question' && current.identity.ids.has(item.author_id) && status === 'dismissed' &&
        req.body?.answer === undefined && req.body?.highlighted === undefined
      if (!current.canModerate && !ownRemoval) throw fail('LIVE_MODERATOR_REQUIRED', 403)
      const answer = req.body?.answer === undefined ? item.answer : text(req.body.answer, 500)
      if (status === 'answered' && !answer) throw fail('LIVE_AUDIENCE_ANSWER_REQUIRED')
      const highlighted = req.body?.highlighted ?? item.highlighted
      if (typeof highlighted !== 'boolean') throw fail('LIVE_AUDIENCE_HIGHLIGHT_INVALID')
      if (highlighted) await db.query('UPDATE fallback_live_audience_items SET highlighted=false WHERE live_id=$1 AND highlighted=true', [current.liveId])
      await db.query('UPDATE fallback_live_audience_items SET status=$2,answer=$3,highlighted=$4,updated_at=now() WHERE id=$1',
        [item.id, status, answer, status === 'dismissed' ? false : highlighted])
      return current
    })
    await changed(scope.liveId)
    res.json(await snapshot(pool, scope))
  }))
}

module.exports = { ensureLiveAudienceTables, registerLiveAudienceRoutes }
