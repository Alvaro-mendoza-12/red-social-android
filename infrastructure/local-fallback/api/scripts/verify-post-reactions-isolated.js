'use strict'

// Real HTTP handlers and PostgreSQL, with test-only JWTs. Never accepts the
// production DATABASE_URL. The caller creates/removes the empty isolated DB.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const express = require('express')
const jwt = require('jsonwebtoken')
const { Pool } = require('pg')
const { registerDataRoutes, invalidateImportedDataCache } = require('../src/data-routes')

async function main() {
  const url = new URL(process.env.POST_REACTIONS_TEST_DATABASE_URL || '')
  assert.match(url.pathname, /^\/novvin_likes104_[a-z0-9_]+$/)
  const pool = new Pool({ connectionString: url.href, max: 4 })
  let server
  try {
    assert.equal((await pool.query('SELECT current_database() AS name')).rows[0].name, url.pathname.slice(1))
    const owner = crypto.randomUUID(), actor = crypto.randomUUID(), alias = crypto.randomUUID()
    await pool.query(`INSERT INTO fallback_users(id,email,display_name,supabase_user_id)
      VALUES ($1,$2,'QA author',NULL),($3,$4,'QA reactor',$5)`,
      [owner, `owner-${owner}@example.invalid`, actor, `actor-${actor}@example.invalid`, alias])
    // All schema changes below are confined to the explicitly guarded test DB.
    await pool.query(`CREATE SCHEMA IF NOT EXISTS supabase_import;
      DROP TABLE IF EXISTS supabase_import.posts CASCADE;
      DROP TABLE IF EXISTS supabase_import.post_likes CASCADE;
      DROP TABLE IF EXISTS supabase_import.post_reactions CASCADE;
      CREATE TABLE supabase_import.posts(id text PRIMARY KEY,user_id text,content text,visibility text,created_at timestamptz);
      CREATE TABLE supabase_import.post_likes(id text PRIMARY KEY,"postId" text,"userId" text);
      CREATE TABLE supabase_import.post_reactions(id text PRIMARY KEY,"postId" text,"userId" text,"reactionType" text)`)
    const postId = 'PoSt-' + crypto.randomUUID()
    await pool.query(`INSERT INTO supabase_import.posts VALUES($1,$2,'QA only','public',now())`, [postId, owner])
    invalidateImportedDataCache()
    const secret = crypto.randomBytes(48).toString('hex')
    const app = express(); app.use(express.json())
    const authRequired = (req, res, next) => {
      try { req.auth = jwt.verify(String(req.headers.authorization || '').replace(/^Bearer /, ''), secret, { algorithms: ['HS256'] }); next() }
      catch (_) { res.status(401).json({ error: 'UNAUTHORIZED' }) }
    }
    registerDataRoutes(app, { pool, authRequired })
    app.use((error, _req, res, _next) => { console.error('HANDLER_ERROR', error.code || error.name); res.status(500).json({ error: 'QA_HANDLER_FAILURE' }) })
    server = await new Promise(resolve => { const instance = app.listen(0, '127.0.0.1', () => resolve(instance)) })
    const origin = `http://127.0.0.1:${server.address().port}`
    const mobile = jwt.sign({ sub: actor, testClient: 'android' }, secret, { expiresIn: '5m' })
    const second = jwt.sign({ sub: actor, testClient: 'second-session' }, secret, { expiresIn: '5m' })
    const other = jwt.sign({ sub: owner }, secret, { expiresIn: '5m' })
    const path = `/v1/data/feed/posts/${postId}`
    async function request(token, endpoint = path, method = 'GET', body) {
      const response = await fetch(origin + endpoint, { method, signal: AbortSignal.timeout(15_000),
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
      assert.equal(response.status, 200, `${method} ${endpoint}: ${response.status}`)
      return response.json()
    }
    async function check(token, reaction) {
      const result = await request(token)
      assert.equal(result.post.id, postId)
      assert.equal(result.post.liked_by_me, reaction != null)
      assert.equal(result.post.my_reaction, reaction)
      const feed = await request(token, '/v1/data/feed?limit=20&offset=0')
      const item = feed.items.find(it => it.id === postId)
      assert.ok(item, 'The same post is visible in the feed')
      assert.equal(item.my_reaction, reaction)
      assert.equal(item.liked_by_me, reaction != null)
    }
    await check(mobile, null)
    const denied = await fetch(origin + path + '/reaction?response=minimal', { method: 'POST', headers: { authorization: 'Bearer invalid' } })
    assert.equal(denied.status, 401)
    for (const reaction of ['LIKE', 'LOVE', 'FIRE']) {
      // Explicit desired state must be idempotent when an ACK is lost and retried.
      await request(mobile, path + '/reaction?response=minimal', 'POST', { reactionType: reaction })
      await request(mobile, path + '/reaction?response=minimal', 'POST', { reactionType: reaction })
      await check(second, reaction)
      const rows = (await pool.query('SELECT user_id,reaction_type,is_deleted FROM fallback_post_reactions WHERE post_id=$1', [postId.toLowerCase()])).rows
      assert.equal(rows.filter(it => !it.is_deleted).length, 1)
      assert.equal(rows.find(it => !it.is_deleted).reaction_type, reaction)
    }
    const otherPost = (await request(other)).post
    assert.equal(otherPost.my_reaction, null)
    assert.equal(otherPost.like_count, 1)
    await pool.query(`INSERT INTO supabase_import.post_likes VALUES('legacy-like',$1,$2)`, [postId.toUpperCase(), actor])
    invalidateImportedDataCache()
    await request(second, path + '/reaction?response=minimal', 'DELETE')
    await request(second, path + '/reaction?response=minimal', 'DELETE')
    await check(mobile, null)
    await pool.end() // Reopen the database pool, retaining only persisted state.
    const reopened = new Pool({ connectionString: url.href })
    try {
      const active = await reopened.query('SELECT count(*)::int AS n FROM fallback_post_reactions WHERE post_id=$1 AND NOT is_deleted', [postId.toLowerCase()])
      assert.equal(active.rows[0].n, 0)
    } finally { await reopened.end() }
    console.log(JSON.stringify({ result: 'PASS', database: 'isolated-empty-schema-copy', realHttpHandlers: true,
      realPostgres: true, authBoundary: 'test-only-jwt', sessions: 2, writes: 8,
      checks: ['mixed-case-id', 'desired-state-retry', 'detail-and-feed', 'alias-tombstones', 'account-isolation', '401', 'reopened-database'] }))
  } finally {
    if (server) await new Promise(resolve => server.close(resolve))
    if (!pool.ended) await pool.end()
  }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1 })
