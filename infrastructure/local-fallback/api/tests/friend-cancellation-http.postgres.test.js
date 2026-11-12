'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { once } = require('node:events')
const { Pool } = require('pg')
const { ensureFriendRequestState, answerNativeFriend } = require('../src/friend-request-state')
const { registerDataRoutes } = require('../src/data-routes')
const url = process.env.NOVVIN_FRIEND_HTTP_DATABASE_URL

test('HTTP cancellation preserves accepted friendships, enforces sender ownership and serializes acceptance', { skip: !url, timeout: 30_000 }, async t => {
  const parsed = new URL(url)
  assert.equal(parsed.hostname, '127.0.0.1')
  assert.equal(parsed.pathname, '/novvin_friend_cancel_http_test')
  const pool = new Pool({ connectionString: url, max: 6 })
  t.after(() => pool.end())
  await pool.query(`DROP SCHEMA IF EXISTS supabase_import CASCADE; DROP SCHEMA public CASCADE; CREATE SCHEMA public;
    CREATE TABLE fallback_users(id text PRIMARY KEY, supabase_user_id text UNIQUE, email text, display_name text, avatar_url text,
      sync_source text, sync_status text, created_at timestamptz, updated_at timestamptz, last_login_at timestamptz);
    INSERT INTO fallback_users(id,supabase_user_id) VALUES('a-local','a'),('b-local','b'),('c-local','c');
    CREATE TABLE fallback_friends(id text PRIMARY KEY, sender_id text, receiver_id text, status text, created_at timestamptz DEFAULT now());
    CREATE TABLE fallback_compat_rows(table_name text,row_id text,row jsonb);
    CREATE SCHEMA supabase_import;
    CREATE TABLE supabase_import.friends(id text PRIMARY KEY,sender_id text,receiver_id text,status text);`)
  await ensureFriendRequestState(pool)
  let pauseNext = false, entered, release
  const routePool = {
    query: (...args) => pool.query(...args),
    connect: async () => {
      const client = await pool.connect(), pause = pauseNext
      pauseNext = false
      return { release: () => client.release(), query: async (sql, args) => {
        const result = await client.query(sql, args)
        if (pause && sql === 'SELECT fallback_lock_friend_pair($1,$2)') { entered(); await release.gate }
        return result
      } }
    },
  }
  const app = express()
  app.use(express.json())
  registerDataRoutes(app, { pool: routePool, authRequired(req, res, next) {
    const actor = req.headers['x-fixture-user']
    if (!['a-local','b-local','c-local'].includes(actor)) return res.status(401).json({error:'AUTH_REQUIRED'})
    req.auth = { sub: actor }; next()
  } })
  app.use((err, req, res, next) => res.status(err.status || 500).json({ error: err.message }))
  const server = app.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections() }))
  const endpoint = `http://127.0.0.1:${server.address().port}`
  const discovery = await fetch(`${endpoint}/v1/data/friends?tab=outgoing`, { headers: { 'x-fixture-user': 'a-local' } })
  assert.equal(discovery.status,200)
  assert.equal((await discovery.json()).capabilities?.cancelOutgoingRequests,true)
  const cancel = async (id, actor='a-local') => {
    const response = await fetch(`${endpoint}/v1/data/friends/${id}/cancel`, {method:'POST',headers:{'x-fixture-user':actor}})
    return { status: response.status, body: await response.json() }
  }
  const insert = (id,status='pending') => pool.query('INSERT INTO fallback_friends(id,sender_id,receiver_id,status) VALUES($1,$2,$3,$4)',[id,'a','b',status])
  const status = async id => (await pool.query('SELECT status FROM fallback_friends WHERE id=$1',[id])).rows[0]?.status
  await insert('permission')
  assert.equal((await cancel('permission','b-local')).status,403)
  assert.equal((await cancel('permission','c-local')).status,404)
  assert.equal(await status('permission'),'pending')
  assert.equal((await cancel('permission')).status,200)
  assert.equal(await status('permission'),undefined)
  assert.equal((await cancel('permission')).status,404)
  for (const state of ['accepted','rejected']) {
    await insert(state,state)
    assert.equal((await cancel(state)).status,409)
    assert.equal(await status(state),state)
  }
  for (const source of ['imported','compat']) {
    const row={id:`legacy-${source}`,sender_id:'a',receiver_id:'b',status:'pending'}
    // Reset only our fixture's legacy state, including the previous native cancellation barrier.
    await pool.query('TRUNCATE fallback_friend_legacy_barriers, fallback_friend_legacy_requests')
    if(source==='imported') await pool.query('INSERT INTO supabase_import.friends VALUES($1,$2,$3,$4)',Object.values(row))
    else await pool.query("INSERT INTO fallback_compat_rows VALUES('friends',$1,$2::jsonb)",[row.id,JSON.stringify(row)])
    assert.equal((await cancel(row.id,'b-local')).status,403)
    assert.equal((await cancel(row.id)).status,200)
    assert.equal((await pool.query('SELECT status FROM fallback_friend_legacy_requests WHERE source=$1 AND request_id=$2',[source,row.id])).rows[0].status,'cancelled')
    assert.equal(await status('accepted'),'accepted')
  }
  // Ensure the second session is blocked on a real PostgreSQL advisory lock.
  const waitForContender = async () => {
    const deadline=Date.now()+5000
    while(Date.now()<deadline) {
      if ((await pool.query("SELECT count(*)::int AS n FROM pg_locks WHERE locktype='advisory' AND NOT granted")).rows[0].n) return
      await new Promise(resolve=>setTimeout(resolve,10))
    }
    throw new Error('The competing operation never waited in PostgreSQL')
  }
  await insert('cancel-wins')
  let unlock
  release={gate:new Promise(resolve=>{unlock=resolve})}
  const held=new Promise(resolve=>{entered=resolve})
  pauseNext=true
  const cancelling=cancel('cancel-wins')
  await held
  const accepting=answerNativeFriend(pool,'cancel-wins','accepted',['b-local','b']).then(value=>({value}),error=>({error}))
  try { await waitForContender() } finally { unlock() }
  assert.equal((await cancelling).status,200)
  assert.equal((await accepting).error?.status,404)
  assert.equal(await status('cancel-wins'),undefined)

  await insert('accept-wins')
  release={gate:new Promise(resolve=>{unlock=resolve})}
  const heldAcceptance=new Promise(resolve=>{entered=resolve})
  pauseNext=true
  const accepted=answerNativeFriend(routePool,'accept-wins','accepted',['b-local','b'])
  await heldAcceptance
  const cancelled=cancel('accept-wins')
  try { await waitForContender() } finally { unlock() }
  assert.equal((await accepted).status,'accepted')
  assert.equal((await cancelled).status,409)
  assert.equal(await status('accept-wins'),'accepted')
})
