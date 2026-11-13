'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const { ensureLiveDrawGuessTables, reconcileExpiredLiveDrawGuessGames } = require('../src/live-draw-guess-routes')

const database = process.env.LIVE_DRAW_TIMER_TEST_DATABASE_URL
test('an idle expired round is finalized exactly once by concurrent timers in PostgreSQL', { skip: !database }, async t => {
  const target = new URL(database)
  assert.ok(['localhost', '127.0.0.1'].includes(target.hostname))
  assert.equal(target.pathname, '/novvin_live_draw_timer_test')
  const pool = new Pool({ connectionString: database })
  t.after(() => pool.end())
  const flag = process.env.LIVE_DRAW_GUESS_ENABLED
  process.env.LIVE_DRAW_GUESS_ENABLED = 'true'
  t.after(() => {
    if (flag === undefined) delete process.env.LIVE_DRAW_GUESS_ENABLED
    else process.env.LIVE_DRAW_GUESS_ENABLED = flag
  })
  await pool.query('CREATE TABLE IF NOT EXISTS fallback_live_streams (id uuid PRIMARY KEY)')
  await ensureLiveDrawGuessTables(pool)
  const live = randomUUID(), game = randomUUID(), actor = randomUUID()
  await pool.query('INSERT INTO fallback_live_streams(id) VALUES($1)', [live])
  await pool.query(`INSERT INTO fallback_live_draw_games(id,live_id,host_user_id,status,total_rounds,current_round_number,request_nonce)
    VALUES($1,$2,$3,'active',1,1,$4)`, [game, live, actor, randomUUID()])
  await pool.query(`INSERT INTO fallback_live_draw_players(game_id,user_id,seat_index,status) VALUES($1,$2,0,'accepted')`, [game, actor])
  await pool.query(`INSERT INTO fallback_live_draw_rounds(game_id,round_number,drawer_user_id,prompt_key,started_at,ends_at)
    VALUES($1,1,$2,'luna',now()-interval '1 minute',now()-interval '1 second')`, [game, actor])
  const events = []
  const tick = () => reconcileExpiredLiveDrawGuessGames({ pool, publishEvent: async (...args) => events.push(args) })
  const workers = await Promise.all([tick(), tick()])
  assert.equal(workers.reduce((total, worker) => total + worker.failed, 0), 0)
  assert.equal(workers.reduce((total, worker) => total + worker.advanced, 0), 1)
  assert.equal(events.length, 1)
  assert.equal((await tick()).advanced, 0)
  const result = await pool.query(`SELECT g.status AS game_status, r.status AS round_status
    FROM fallback_live_draw_games g JOIN fallback_live_draw_rounds r ON r.game_id=g.id WHERE g.id=$1`, [game])
  assert.deepEqual(result.rows, [{ game_status: 'completed', round_status: 'expired' }])
})
