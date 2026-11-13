'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const express = require('express')
const { registerLiveAudienceRoutes, ensureLiveAudienceTables } = require('../src/live-audience-routes')

const database = process.env.LIVE_AUDIENCE_TEST_DATABASE_URL
test('live polls and questions retain one vote per person across retries, changes, expiry and moderation',
  { skip: !database }, async t => {
    const target = new URL(database)
    assert.ok(['localhost','127.0.0.1'].includes(target.hostname))
    assert.equal(target.pathname, '/novvin_live_audience_test')
    const pool = new Pool({ connectionString: database })
    t.after(() => pool.end())
    await pool.query(`CREATE TABLE IF NOT EXISTS fallback_live_streams(id uuid PRIMARY KEY, host_user_id text, is_active boolean);
      CREATE TABLE IF NOT EXISTS fallback_rate_limit_windows(
        scope text,key_hash text,window_started_at timestamptz,request_count bigint,expires_at timestamptz,
        PRIMARY KEY(scope,key_hash))`)
    await ensureLiveAudienceTables(pool)
    const liveId = randomUUID(), host = randomUUID(), viewer = randomUUID(), other = randomUUID()
    await pool.query('INSERT INTO fallback_live_streams VALUES($1,$2,true)', [liveId, host])
    const actors = { host, viewer, other }, events = []
    const app = express()
    app.use(express.json())
    const authRequired = (req,res,next) => {
      const id = actors[req.headers.authorization]
      if (!id) return res.status(401).json({ error: 'AUTH_REQUIRED' })
      req.auth = { sub: id }
      next()
    }
    registerLiveAudienceRoutes(app, {
      pool, authRequired,
      getCurrentUser: async (_db, req) => ({ id: req.auth.sub, display_name: 'Participante sintético' }),
      userIdentity: user => ({ displayUserId: user.id, ids: new Set([user.id]), values: [user.id] }),
      streamById: async (db,id,{ forUpdate } = {}) =>
        (await db.query('SELECT * FROM fallback_live_streams WHERE id=$1' + (forUpdate ? ' FOR UPDATE' : ''), [id])).rows[0],
      ownsStream: (stream,identity) => identity.ids.has(stream.host_user_id),
      canViewStream: async () => true,
      moderationMembership: async (_db,stream,identity) => ({ canModerate: identity.ids.has(stream.host_user_id) }),
      publishEvent: async (...args) => events.push(args),
    })
    app.use((error,_req,res,_next) => res.status(500).json({ error: error.code || error.message }))
    const server = await new Promise(resolve => {
      const instance = app.listen(0,'127.0.0.1',() => resolve(instance))
    })
    t.after(() => new Promise(resolve => server.close(resolve)))
    const base = `http://127.0.0.1:${server.address().port}/v1/data/live/${liveId}/audience`
    async function request(actor,path = '',body,method = body ? 'POST' : 'GET',status = 200) {
      const response = await fetch(base + path, {
        method, headers: { Authorization: actor, 'Content-Type':'application/json' },
        body: body ? JSON.stringify(body) : undefined,
      })
      const value = await response.json()
      assert.equal(response.status, status, JSON.stringify(value))
      return value
    }
    const creation = { content: '¿Qué tema elegimos?', options: ['Arte','Música'], requestNonce: randomUUID() }
    const created = await request('host','/polls',creation,'POST',201)
    const poll = created.items[0].id
    assert.equal((await request('host','/polls',creation)).id,poll)
    assert.equal(events.length,1)
    await request('host','/polls',{ ...creation, durationSeconds: 600 },'POST',409)
    await request('viewer','/polls',{ ...creation, requestNonce: randomUUID() },'POST',403)
    await Promise.all([
      request('viewer',`/items/${poll}/vote`,{ choiceIndex: 0 }),
      request('other',`/items/${poll}/vote`,{ choiceIndex: 1 }),
    ])
    await request('viewer',`/items/${poll}/vote`,{ choiceIndex: 0 })
    let item = (await request('viewer')).items.find(item => item.id === poll)
    assert.deepEqual(item.counts,[1,1])
    assert.equal(item.myChoice,0)
    await request('viewer',`/items/${poll}/vote`,{ choiceIndex: 1 })
    item = (await request('viewer')).items.find(item => item.id === poll)
    assert.deepEqual(item.counts,[0,2])
    await request('viewer',`/items/${poll}`,{ status: 'closed' },'PATCH',403)
    await request('host',`/items/${poll}`,{ status: 'closed' },'PATCH')
    await request('viewer',`/items/${poll}/vote`,{ choiceIndex: 0 },'POST',409)
    const fresh = await request('host','/polls',{ ...creation, requestNonce: randomUUID() },'POST',201)
    await pool.query("UPDATE fallback_live_audience_items SET closes_at=now()-interval '1 second' WHERE id=$1", [fresh.id])
    await request('viewer',`/items/${fresh.id}/vote`,{ choiceIndex: 0 },'POST',409)
    assert.equal((await request('viewer')).items.find(item => item.id === fresh.id).status,'closed')
    const q = { content: '¿Habrá otra sesión?', requestNonce: randomUUID() }
    const asked = await request('viewer','/questions',q,'POST',201)
    assert.equal((await request('viewer','/questions',q)).id,asked.id)
    await request('other',`/items/${asked.id}/vote`,{ choiceIndex: 0 })
    await request('host',`/items/${asked.id}`,{ status:'answered', answer:'Sí, mañana.', highlighted:true },'PATCH')
    const answer = (await request('other')).items.find(item => item.id === asked.id)
    assert.equal(answer.answer,'Sí, mañana.')
    assert.equal(answer.totalVotes,1)
    assert.equal(answer.highlighted,true)
    await request('viewer',`/items/${asked.id}`,{ status:'dismissed' },'PATCH')
    assert.equal((await request('other')).items.some(item => item.id === asked.id),false)
    const active = await request('host','/polls',{ ...creation, requestNonce: randomUUID() },'POST',201)
    await pool.query(`INSERT INTO fallback_live_audience_items
      (live_id,author_id,author_name,kind,content,request_nonce,created_at)
      SELECT $1,$2,'Audiencia sintética','question','Pregunta '||n,gen_random_uuid(),now()+n*interval '1 second'
      FROM generate_series(1,100) AS n`, [liveId, viewer])
    const crowded = await request('viewer')
    assert.equal(crowded.hasMore,true)
    assert.equal(crowded.items.length,80)
    assert.equal(crowded.items[0].id,active.id,'The active poll remains reachable after many questions')
    await pool.query('UPDATE fallback_live_streams SET is_active=false WHERE id=$1', [liveId])
    await request('viewer','/questions',{ ...q, requestNonce: randomUUID() },'POST',409)
    assert.equal((await pool.query('SELECT count(*)::int AS total FROM fallback_live_audience_votes WHERE item_id=$1', [poll])).rows[0].total,2)
  })
