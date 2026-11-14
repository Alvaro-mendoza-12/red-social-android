'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const express = require('express')
const { Pool } = require('pg')
const { ensureLivePointsTables, registerLivePointsRoutes } = require('../src/live-points-routes')
const connectionString = process.env.NOVVIN_POINTS_TEST_DATABASE_URL

test('redemption ownership and concurrent retries with real PostgreSQL', { skip: !connectionString }, async t => {
  const url = new URL(connectionString)
  assert(['localhost', '127.0.0.1', '[::1]'].includes(url.hostname), 'Use only an isolated loopback database')
  assert(/^novvin_.*(?:qa|124|test)/.test(url.pathname.slice(1)), 'A dedicated QA database is required')
  const application = 'novvin_points_qa_' + crypto.randomBytes(6).toString('hex')
  const pool = new Pool({ connectionString, max: 12, application_name: application })
  const host = crypto.randomUUID(), a = crypto.randomUUID(), alias = crypto.randomUUID(), b = crypto.randomUUID()
  const actors = { a: { id: a, ids: [a, alias] }, alias: { id: alias, ids: [alias, a] }, b: { id: b, ids: [b] } }
  const previousFlag = process.env.LIVE_CHANNEL_POINTS_ENABLED
  process.env.LIVE_CHANNEL_POINTS_ENABLED = 'true'
  const app = express(); app.use(express.json())
  // Authentication is a fixture seam here; route, HTTP, transactions and row locks are real.
  registerLivePointsRoutes(app, {
    pool,
    authRequired(req, res, next) { req.actor = actors[req.get('x-qa-actor')]; return req.actor ? next() : res.sendStatus(401) },
    getCurrentUser: async (_pool, req) => req.actor,
    userIdentity: user => ({ displayUserId: user.id, ids: user.ids, values: user.ids }),
  })
  app.use((error, req, res, next) => res.status(500).json({ error: 'QA_ROUTE_ERROR', code: error.code }))
  const server = app.listen(0, '127.0.0.1')
  await new Promise(resolve => server.once('listening', resolve))
  const base = 'http://127.0.0.1:' + server.address().port
  const lives = []
  async function fixture() {
    const live = crypto.randomUUID(), reward = crypto.randomUUID(), otherReward = crypto.randomUUID()
    await pool.query('INSERT INTO fallback_live_streams(id,host_user_id,title,is_active) VALUES($1,$2,$3,TRUE)', [live, host, 'Synthetic redemption test'])
    lives.push(live)
    await pool.query('INSERT INTO fallback_channel_point_rewards(id,live_id,creator_id,title,cost,stock) VALUES($1,$3,$4,$5,10,10),($2,$3,$4,$5,10,10)', [reward, otherReward, live, host, 'Synthetic reward'])
    for (const viewer of [a, b]) await pool.query('INSERT INTO fallback_channel_points_balances(live_id,creator_id,viewer_id,balance) VALUES($1,$2,$3,100)', [live, host, viewer])
    return { live, reward, otherReward }
  }
  async function redeem(f, actor, nonce, reward = f.reward) {
    const response = await fetch(base + '/v1/data/live/' + f.live + '/points/redeem', {
      method: 'POST', headers: { 'content-type': 'application/json', 'x-qa-actor': actor },
      body: JSON.stringify({ rewardId: reward, requestNonce: nonce }), signal: AbortSignal.timeout(12_000),
    })
    return { status: response.status, body: await response.json() }
  }
  try {
    if (process.env.NOVVIN_POINTS_TEST_BOOTSTRAP === 'true') {
      // Standalone CI schema for these routes; production/lab schemas are left unchanged.
      await pool.query(`CREATE TABLE IF NOT EXISTS fallback_live_streams (
        id UUID PRIMARY KEY, host_user_id TEXT NOT NULL,
        title TEXT NOT NULL DEFAULT '', is_active BOOLEAN NOT NULL DEFAULT FALSE
      )`)
    }
    await ensureLivePointsTables(pool)
    await t.test('a replay belongs to its viewer and cannot reveal that viewers redemption to another account', async () => {
      const f = await fixture(), nonce = crypto.randomUUID()
      assert.equal((await redeem(f, 'a', nonce)).status, 201)
      const rejected = await redeem(f, 'b', nonce)
      assert.equal(rejected.status, 409); assert.equal(rejected.body.error, 'LIVE_POINT_REDEMPTION_NONCE_CONFLICT')
      assert.equal(rejected.body.redemption, undefined)
      const balance = await pool.query('SELECT balance FROM fallback_channel_points_balances WHERE live_id=$1 AND viewer_id=$2', [f.live, b])
      assert.equal(balance.rows[0].balance, 100)
    })
    await t.test('a replay cannot silently change the requested reward', async () => {
      const f = await fixture(), nonce = crypto.randomUUID()
      assert.equal((await redeem(f, 'a', nonce)).status, 201)
      const rejected = await redeem(f, 'a', nonce, f.otherReward)
      assert.equal(rejected.status, 409); assert.equal(rejected.body.error, 'LIVE_POINT_REDEMPTION_NONCE_CONFLICT')
      assert.equal(rejected.body.redemption, undefined)
    })
    await t.test('the same account and its verified identity alias recover a lost acknowledgement', async () => {
      const f = await fixture(), nonce = crypto.randomUUID()
      const first = await redeem(f, 'a', nonce)
      const replay = await redeem(f, 'alias', nonce)
      assert.equal(first.status, 201); assert.equal(replay.status, 200)
      assert.equal(replay.body.redemption.id, first.body.redemption.id)
      assert.equal(replay.body.duplicate, true)
      const balance = await pool.query('SELECT balance FROM fallback_channel_points_balances WHERE live_id=$1 AND viewer_id=$2', [f.live, a])
      assert.equal(balance.rows[0].balance, 90)
    })
    await t.test('five concurrent retries all resolve to one redemption, debit and stock decrement', async () => {
      const f = await fixture(), nonce = crypto.randomUUID(), blocker = await pool.connect()
      let responses = []
      try {
        await blocker.query('BEGIN')
        await blocker.query('SELECT id FROM fallback_channel_point_rewards WHERE id=$1 FOR UPDATE', [f.reward])
        const pending = Promise.all(Array.from({ length: 5 }, () => redeem(f, 'a', nonce)))
        try {
          const deadline = Date.now() + 6000
          let waiting = 0
          while (Date.now() < deadline) {
            const state = await pool.query("SELECT count(*)::int AS waiting FROM pg_stat_activity WHERE application_name=$1 AND wait_event_type='Lock'", [application])
            waiting = state.rows[0].waiting
            if (waiting >= 5) break
            await new Promise(resolve => setTimeout(resolve, 30))
          }
          assert.equal(waiting, 5, 'The requests must actually overlap before releasing the reward lock')
        } finally { await blocker.query('COMMIT') }
        responses = await pending
      } finally { await blocker.query('ROLLBACK').catch(() => {}); blocker.release() }
      assert(responses.every(response => [200, 201].includes(response.status)), JSON.stringify(responses.map(response => response.status)))
      assert.equal(new Set(responses.map(response => response.body.redemption.id)).size, 1)
      assert.equal(responses.filter(response => response.status === 201).length, 1)
      const records = await pool.query('SELECT count(*)::int AS total FROM fallback_channel_point_redemptions WHERE live_id=$1', [f.live])
      const balance = await pool.query('SELECT balance,redeemed_total FROM fallback_channel_points_balances WHERE live_id=$1 AND viewer_id=$2', [f.live, a])
      const stock = await pool.query('SELECT stock FROM fallback_channel_point_rewards WHERE id=$1', [f.reward])
      assert.equal(records.rows[0].total, 1); assert.equal(balance.rows[0].balance, 90)
      assert.equal(Number(balance.rows[0].redeemed_total), 10); assert.equal(stock.rows[0].stock, 9)
    })
  } finally {
    for (const live of lives) await pool.query('DELETE FROM fallback_live_streams WHERE id=$1 AND host_user_id=$2', [live, host])
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await pool.end()
    if (previousFlag === undefined) delete process.env.LIVE_CHANNEL_POINTS_ENABLED
    else process.env.LIVE_CHANNEL_POINTS_ENABLED = previousFlag
  }
})
