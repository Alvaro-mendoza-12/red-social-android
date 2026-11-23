'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { PGlite } = require('@electric-sql/pglite')
const { registerDataRoutes } = require('../src/data-routes')
const realtime = require('../src/realtime-publish')

test('authenticated reaction HTTP writes are atomic and independent of optional feed data', async (t) => {
  const db = new PGlite()
  t.after(() => db.close())
  await db.exec(`
    CREATE TABLE fallback_posts(id text PRIMARY KEY, user_id text, visibility text,
      user_name text DEFAULT 'Author', user_profile_image text, content text DEFAULT '', media_url text, media_urls text[],
      media_type text, survey_id text, remix_allowed boolean DEFAULT false, remix_audience text, remix_revoked_at timestamptz,
      remix_source_post_id text, remix_root_post_id text, remix_source_user_id text, remix_source_user_name text,
      remix_depth int DEFAULT 0, created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now());
    CREATE TABLE fallback_follows(id text PRIMARY KEY DEFAULT gen_random_uuid()::text,
      follower_id text, following_id text, created_at timestamptz DEFAULT now());
    CREATE TABLE fallback_post_reactions(post_id text, user_id text, reaction_type text,
      created_at timestamptz, updated_at timestamptz, is_deleted boolean,
      PRIMARY KEY (post_id, user_id));
    CREATE TABLE fallback_shares(id text, post_id text, user_id text, content_type text, created_at timestamptz);
    CREATE TABLE survey_responses(campaign_id text, removed_at timestamptz, expires_at timestamptz);
    CREATE TABLE fallback_post_comments(id text, post_id text, user_id text, content text, parent_id text,
      media_url text, media_type text, created_at timestamptz, updated_at timestamptz, is_deleted boolean);
    CREATE SCHEMA supabase_import;
    CREATE TABLE supabase_import.posts(id text, user_id text, visibility text);
    INSERT INTO fallback_posts(id, user_id, visibility) VALUES ('public-post', 'owner', 'public'),
      ('private-post', 'owner', 'private'), ('followers-post', 'owner', 'followers'),
      ('atomic-post', 'owner', 'public'), ('retry-post', 'owner', 'public'), ('lost-response', 'owner', 'public');
    INSERT INTO supabase_import.posts VALUES ('ImPoRtEd-PoSt', 'owner', 'public');
    INSERT INTO fallback_follows(follower_id,following_id) VALUES ('viewer-alias','owner');
  `)
  // The route, permissions policy and reaction store are real. Omit unused
  // projection columns; emulate views for the pre-fix query so the tests can
  // expose individual faults rather than all failing on an unrelated column.
  let failShares = false
  const notices = []
  const publish = t.mock.method(realtime, 'publishRealtimeEvent', async () => true)
  const pool = { query: (sql, values) => {
    if (sql.includes('FROM fallback_posts') && sql.includes('survey_response_count')) {
      return db.query('SELECT * FROM fallback_posts WHERE lower(id) = ANY($1::text[])', values)
    }
    if (sql.includes('FROM fallback_users')) return { rows: [] }
    if (failShares && sql.includes('FROM fallback_shares')) throw new Error('optional share read unavailable')
    if (sql.includes('INSERT INTO fallback_notifications')) {
      notices.push(values)
      return { rows: [] } // No external delivery in an isolated fixture.
    }
    return db.query(sql, values)
  } }
  const app = express()
  app.use(express.json())
  app.use((req, res, next) => {
    if (req.get('X-Fixture-Drop-Ack') === '1') {
      // The routes have committed by the time they send their success receipt.
      const original = res.json.bind(res)
      res.json = body => {
        if (res.statusCode === 200 && body?.ok === true) {
          req.socket.destroy()
          return res
        }
        return original(body)
      }
    }
    next()
  })
  registerDataRoutes(app, { pool, authRequired(req, res, next) {
    const token = req.get('Authorization')
    const user = token === 'Bearer viewer' ? { id: 'viewer', supabase_user_id: 'viewer-alias' }
      : token === 'Bearer stranger' ? { id: 'stranger' } : token === 'Bearer owner' ? { id: 'owner' } : null
    if (!user) return res.sendStatus(401)
    req.auth = { sub: user.id }
    req._currentUser = user
    next()
  } })
  app.use((_error, _req, res, _next) => res.sendStatus(500))
  const server = await new Promise((resolve) => {
    const listener = app.listen(0, '127.0.0.1', () => resolve(listener))
  })
  t.after(() => new Promise(resolve => server.close(resolve)))
  const send = (id, { method = 'POST', body, authorization = 'Bearer viewer', minimal = true } = {}) => fetch(
    `http://127.0.0.1:${server.address().port}/v1/data/feed/posts/${id}/reaction${minimal ? '?response=minimal' : ''}`,
    { method, headers: { Authorization: authorization, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) },
  )
  const read = async id => (await db.query(`SELECT user_id, reaction_type, is_deleted, xmin::text AS revision
    FROM fallback_post_reactions WHERE post_id = $1 ORDER BY user_id`, [id])).rows

  await t.test('minimal POST and DELETE still commit when the shares read fails', async () => {
    failShares = true
    try {
      assert.equal((await send('public-post', { body: { reactionType: 'LOVE' } })).status, 200)
      assert.deepEqual((await read('public-post')).map(({ reaction_type, is_deleted }) => [reaction_type, is_deleted]),
        [['LOVE', true], ['LOVE', false]])
      assert.equal((await send('public-post', { method: 'DELETE' })).status, 200)
      assert.ok((await read('public-post')).every(row => row.is_deleted))
    } finally { failShares = false }
  })

  await t.test('constraint failure on the canonical alias rolls back the entire write', async () => {
    await db.exec(`ALTER TABLE fallback_post_reactions ADD CONSTRAINT reject_canonical
      CHECK (post_id <> 'atomic-post' OR user_id <> 'viewer-alias')`)
    try {
      assert.equal((await send('atomic-post')).status, 500)
      assert.deepEqual(await read('atomic-post'), [])
      assert.equal((await send('atomic-post', { method: 'DELETE' })).status, 500)
      assert.deepEqual(await read('atomic-post'), [])
    } finally { await db.exec('ALTER TABLE fallback_post_reactions DROP CONSTRAINT reject_canonical') }
  })

  await t.test('identical retries do not rewrite the two records', async () => {
    const notificationsBefore = notices.length
    const eventsBefore = publish.mock.callCount()
    assert.equal((await send('retry-post')).status, 200)
    const liked = await read('retry-post')
    assert.equal(liked.length, 2)
    const retries = await Promise.all([send('retry-post'), send('retry-post')])
    assert.deepEqual(retries.map(response => response.status), [200, 200])
    assert.deepEqual(await read('retry-post'), liked)
    assert.equal(notices.length - notificationsBefore, 1)
    assert.equal(publish.mock.callCount() - eventsBefore, 2)
    assert.equal((await send('retry-post', { method: 'DELETE' })).status, 200)
    const removed = await read('retry-post')
    assert.ok(removed.every(row => row.is_deleted))
    assert.equal((await send('retry-post', { method: 'DELETE' })).status, 200)
    assert.deepEqual(await read('retry-post'), removed)
  })

  await t.test('only authenticated viewers with access may change records', async () => {
    assert.equal((await send('public-post', { authorization: '' })).status, 401)
    assert.equal((await send('private-post')).status, 404)
    assert.equal((await send('missing-post')).status, 404)
    assert.equal((await send('private-post', { method: 'DELETE' })).status, 200)
    assert.equal((await send('missing-post', { method: 'DELETE' })).status, 200)
    assert.deepEqual(await read('private-post'), [])
    assert.deepEqual(await read('missing-post'), [])
    assert.equal((await send('private-post', { authorization: 'Bearer owner' })).status, 200)
    assert.deepEqual((await read('private-post')).map(row => row.user_id), ['owner'])
    assert.equal((await send('followers-post', { authorization: 'Bearer stranger' })).status, 404)
    assert.equal((await send('followers-post')).status, 200)
    assert.equal((await send('IMPORTED-POST')).status, 200)
    assert.equal((await read('imported-post')).filter(row => !row.is_deleted).length, 1)
  })

  await t.test('invalid input never mutates state and a failed optional projection does not undo the ACK', async () => {
    const before = await read('public-post')
    for (const type of [null, 0, false, '', [], 'UNKNOWN']) {
      assert.equal((await send('public-post', { body: { reactionType: type } })).status, 400)
    }
    assert.deepEqual(await read('public-post'), before)
    failShares = true
    try {
      const response = await send('public-post', { minimal: false, body: { reaction_type: 'Fire' } })
      assert.equal(response.status, 200)
      assert.deepEqual(await response.json(), { ok: true, post: null })
      assert.equal((await read('public-post')).find(row => !row.is_deleted).reaction_type, 'FIRE')
    } finally { failShares = false }
  })
  await t.test('read confirms a committed reaction after the HTTP receipt was dropped', async () => {
    const base = `http://127.0.0.1:${server.address().port}/v1/data/feed/posts/lost-response`
    const headers = {
      Authorization: 'Bearer viewer',
      'X-Fixture-Drop-Ack': '1',
      'Content-Type': 'application/json',
    }
    const readAs = user => fetch(base, { headers: { Authorization: 'Bearer ' + user } })
    await assert.rejects(fetch(base + '/reaction?response=minimal', {
      method: 'POST', headers, body: JSON.stringify({ reactionType: 'LOVE' })
    }), TypeError)
    const receipt = await readAs('viewer')
    assert.equal(receipt.status, 200)
    const confirmed = (await receipt.json()).post
    assert.equal(confirmed.my_reaction, 'LOVE')
    assert.equal(confirmed.reaction_counts.LOVE, 1)
    const stranger = await readAs('stranger')
    assert.equal(stranger.status, 200)
    assert.equal((await stranger.json()).post.my_reaction, null)
    assert.equal((await read('lost-response')).filter(row => !row.is_deleted).length, 1)
    await assert.rejects(fetch(base + '/reaction?response=minimal', { method: 'DELETE', headers }), TypeError)
    const removal = await readAs('viewer')
    assert.equal(removal.status, 200)
    assert.equal((await removal.json()).post.my_reaction, null)
    assert.ok((await read('lost-response')).every(row => row.is_deleted))
  })
})
