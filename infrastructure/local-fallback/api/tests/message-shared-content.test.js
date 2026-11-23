'use strict'

const { randomUUID } = require('node:crypto')
const assert = require('node:assert/strict')
const { test } = require('node:test')
const express = require('express')
const { PGlite } = require('@electric-sql/pglite')
const { Pool } = require('pg')
const { registerDataRoutes } = require('../src/data-routes')
const { ensureCollectionMembershipTables } = require('../src/collection-membership-policy')
const { redactDeletedMessage } = require('../src/message-deletion-policy')
const { parseSharedMessage } = require('../src/message-shared-content')
const realtime = require('../src/realtime-publish')

test('the share contract is opt-in and rejects conflicting payloads', () => {
  const id = randomUUID()
  const sharedContent = { kind: 'post', id: 'Original-ID' }
  assert.equal(parseSharedMessage({ content: 'A regular link' }, id), null)
  assert.deepEqual(parseSharedMessage({ sharedContent }, id), { kind: 'post', id: 'original-id' })
  for (const payload of [{ contentType: 'Shared_Content' }, { content_type: 'shared_content' },
    { shared_content: sharedContent }, { sharedContent: { ...sharedContent, kind: 'result' } },
    { sharedContent: { kind: 'post', id: '../other' } }, { sharedContent: { ...sharedContent, url: 'not allowed' } },
    { sharedContent, isForwarded: true }, { sharedContent, mediaUrl: 'not allowed' },
    { sharedContent, contentType: 'text' }, { sharedContent, content: 'a'.repeat(801) }]) {
    assert.throws(() => parseSharedMessage(payload, id), { message: 'SHARED_CONTENT_INVALID', status: 400 })
  }
  assert.throws(() => parseSharedMessage({ sharedContent }, null), { message: 'SHARED_CONTENT_INVALID', status: 400 })
  assert.equal(redactDeletedMessage({ content_type: 'shared_content', shared_content: sharedContent }).shared_content, undefined)
})

const nativeUrl = process.env.NOVVIN_SHARED_CONTENT_TEST_DATABASE_URL
if (nativeUrl) {
  const url = new URL(nativeUrl)
  if (!['localhost', '127.0.0.1'].includes(url.hostname) || url.pathname !== '/novvin_shared_content_test') {
    throw new Error('Isolated loopback novvin_shared_content_test database required')
  }
}

test('HTTP sends persist a typed post in DMs, groups and communities and recheck both scopes on read', async t => {
  const nativePool = nativeUrl && new Pool({ connectionString: nativeUrl, max: 2 })
  const db = nativePool ? await nativePool.connect() : new PGlite()
  t.after(async () => { if (nativePool) { db.release(); await nativePool.end() } else { await db.close() } })
  const chat = randomUUID(), group = randomUUID(), community = randomUUID(), channel = randomUUID()
  const post = randomUUID(), privatePost = randomUUID(), otherPost = randomUUID()
  const schema = `
    CREATE TABLE fallback_posts(id uuid PRIMARY KEY, user_id text, visibility text, content text, is_sensitive boolean DEFAULT false);
    CREATE TABLE fallback_user_blocks(blocker_id text, blocked_id text);
    CREATE TABLE fallback_chats(id uuid PRIMARY KEY, participants text[], participant_key text, is_group boolean DEFAULT false,
      group_name text, last_message text, last_message_at timestamptz, last_message_sender_id text,
      last_message_id text, created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now());
    CREATE TABLE fallback_chat_participants(chat_id uuid, user_id text, joined_at timestamptz DEFAULT now(), last_read_at timestamptz,
      is_muted boolean, is_pinned boolean, is_archived boolean, is_favorite boolean, cleared_at timestamptz);
    CREATE TABLE fallback_chat_hidden(chat_id uuid, user_id text);
    CREATE TABLE fallback_messages(id uuid PRIMARY KEY, chat_id text, sender_id text, content text, content_type text,
      media_url text, thumbnail_url text, media_size bigint, reply_to_id text, poll_id text,
      is_forwarded boolean, forwarded_from_message_id text, forwarded_from_user_id text, shared_content jsonb,
      status text DEFAULT 'sent', delivered_at timestamptz, read_at timestamptz, reactions jsonb,
      deleted_by jsonb DEFAULT '[]'::jsonb, deleted_for_all boolean DEFAULT false, is_edited boolean,
      created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now());
    CREATE TABLE fallback_room_messages(LIKE fallback_messages INCLUDING ALL);
    ALTER TABLE fallback_room_messages DROP COLUMN chat_id, ADD COLUMN room_id text, ADD COLUMN room_kind text, ADD COLUMN channel_id text;
    CREATE TABLE fallback_collection_rooms(id uuid PRIMARY KEY, room_kind text, creator_id text, is_public boolean DEFAULT false);
    CREATE TABLE fallback_collection_members(room_kind text, room_id text, user_id text, role text, joined_at timestamptz DEFAULT now());
    CREATE TABLE fallback_collection_publication_policy(room_kind text, room_id text, only_creator boolean);
    CREATE TABLE fallback_compat_rows(table_name text, row_id text, row jsonb, updated_at timestamptz DEFAULT now());
    CREATE TABLE fallback_community_channels(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), community_id text, channel_type text, paused_until timestamptz,
      pause_reason text, is_deleted boolean DEFAULT false, is_default boolean, position integer DEFAULT 0, name text, description text, created_by text,
      created_at timestamptz DEFAULT now());
    CREATE TABLE fallback_notifications(type text, actor_id text, extra_data jsonb);
  `
  if (nativePool) await db.query(schema.replaceAll('CREATE TABLE', 'CREATE TEMP TABLE'))
  else await db.exec(schema)
  await ensureCollectionMembershipTables(db)
  await db.query(`INSERT INTO fallback_posts(id,user_id,visibility,content) VALUES
    ($1,'sender','public','Original post, not copied into message'),($2,'recipient','private','Private recipient text'),
    ($3,'sender','public','Different publication')`, [post, privatePost, otherPost])
  await db.query(`INSERT INTO fallback_chats(id,participants) VALUES($1,ARRAY['sender','recipient'])`, [chat])
  await db.query("INSERT INTO fallback_chat_participants(chat_id,user_id) VALUES($1,'recipient')", [chat])
  await db.query(`INSERT INTO fallback_collection_rooms(id,room_kind,creator_id) VALUES($1,'group','sender'),($2,'community','sender')`, [group, community])
  await db.query(`INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role) VALUES
    ('group',$1,'recipient','member'),('community',$2,'recipient','member')`, [group, community])
  await db.query(`INSERT INTO fallback_community_channels(id,community_id,channel_type,is_default) VALUES($1,$2,'text',true)`, [channel, community])
  const events = t.mock.method(realtime, 'publishChatMessageEvent', async () => true)
  const pool = { query(sql, parameters) {
    if (sql.includes('FROM fallback_users u') || sql.includes('fallback_fcm_tokens') ||
        sql.includes('FROM fallback_user_notification_preferences') || sql.includes('FROM fallback_friends')) return { rows: [] }
    return query(sql, parameters)
  } }
  let pending = Promise.resolve()
  function query(sql, parameters) {
    if (!nativePool) return db.query(sql, parameters)
    const result = pending.then(() => db.query(sql, parameters))
    pending = result.catch(() => {})
    return result
  }
  const app = express()
  app.use(express.json())
  registerDataRoutes(app, { pool, authRequired(req, res, next) {
    const id = /^Bearer (sender|recipient|outsider)$/.exec(req.get('Authorization') || '')?.[1]
    if (!id) return res.sendStatus(401)
    req.auth = { sub: id }
    req._currentUser = { id }
    return next()
  } })
  app.use((error, _req, res, _next) => res.status(error.status || 500).json({ error: error.message }))
  const server = await new Promise(resolve => { const listener = app.listen(0, '127.0.0.1', () => resolve(listener)) })
  t.after(() => new Promise(resolve => server.close(resolve)))
  function call(path, actor = 'recipient', body) {
    return fetch(`http://127.0.0.1:${server.address().port}/v1/data/messages/${path}`, {
      method: body === undefined ? 'GET' : 'POST', headers: { Authorization: actor ? `Bearer ${actor}` : '', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  }
  const shared = { kind: 'post', id: post }
  const direct = `chats/${chat}/messages`
  const ids = {}
  for (const [key, scope, table] of [[`chats/${chat}`, chat, 'fallback_messages'],
    [`groups/${group}`, group, 'fallback_room_messages'], [`communities/${community}`, community, 'fallback_room_messages']]) {
    const id = randomUUID()
    const messages = `${key}/messages`
    const path = `${messages}/${id}/shared-content`
    ids[scope] = { id, path }
    const body = { id, sharedContent: shared, ...(scope === community && { channelId: channel }) }
    const sent = await call(messages, 'sender', body)
    const payload = await sent.json()
    assert.equal(sent.status, 201, JSON.stringify(payload))
    assert.equal(payload.message.content_type, 'shared_content')
    assert.equal(payload.message.content, '')
    assert.ok(!('shared_content' in payload.message))
    assert.deepEqual((await db.query(`SELECT shared_content FROM ${table} WHERE id=$1`, [id])).rows, [{ shared_content: shared }])
    const received = await call(path)
    assert.equal(received.headers.get('Cache-Control'), 'private, no-store')
    assert.equal(received.status, 200)
    assert.deepEqual(await received.json(), { reference: shared, title: 'Publicación de Novvin', summary: 'Original post, not copied into message' })
    assert.equal((await call(path, 'outsider')).status, 404)
    assert.equal((await call(path, '')).status, 401)
    const retry = await call(messages, 'sender', body)
    assert.equal(retry.status, 200)
    const conflict = await call(messages, 'sender', { ...body, sharedContent: { ...shared, id: otherPost } })
    assert.equal(conflict.status, 409)
  }
  assert.ok(events.mock.calls.every(({ arguments: [event] }) => !('shared_content' in event.message)))

  assert.equal((await call(direct, 'sender', { id: randomUUID(), sharedContent: { ...shared, id: privatePost } })).status, 404)
  assert.equal((await call(direct, 'outsider', { id: randomUUID(), sharedContent: shared })).status, 403)
  assert.equal((await call(direct, 'sender', { id: ids[chat].id, sharedContent: shared, content: 'Different note' })).status, 409)
  const note = await call(direct, 'sender', { id: randomUUID(), sharedContent: shared, content: 'Optional note' })
  assert.equal(note.status, 201)
  assert.equal((await note.json()).message.content, 'Optional note')
  assert.equal((await db.query('SELECT last_message FROM fallback_chats WHERE id=$1', [chat])).rows[0].last_message, 'Contenido compartido')
  const oldNote = await call(direct, 'sender', { id: randomUUID(), content: 'Plain text still works' })
  assert.equal(oldNote.status, 201)
  assert.equal((await oldNote.json()).message.content_type, 'text')
  const raceBody = { id: randomUUID(), sharedContent: shared }
  const racing = await Promise.all([call(direct, 'sender', raceBody), call(direct, 'sender', raceBody)])
  assert.deepEqual(racing.map(response => response.status).sort(), [200, 201])
  assert.equal((await db.query('SELECT count(*)::int AS count FROM fallback_messages WHERE id=$1', [raceBody.id])).rows[0].count, 1)
  await db.query("INSERT INTO fallback_user_blocks VALUES('sender','recipient')")
  assert.equal((await call(ids[chat].path)).status, 404)
  await db.query('DELETE FROM fallback_user_blocks')
  await db.query('UPDATE fallback_posts SET is_sensitive=true WHERE id=$1', [post])
  assert.equal((await (await call(ids[chat].path)).json()).summary, 'Contenido sensible')
  await db.query("UPDATE fallback_posts SET visibility='private' WHERE id=$1", [post])
  for (const { path } of Object.values(ids)) assert.equal((await call(path)).status, 404)
  await db.query("UPDATE fallback_posts SET visibility='public' WHERE id=$1", [post])
  await db.query('DELETE FROM fallback_posts WHERE id=$1', [post])
  const replayAfterRemoval = await call(direct, 'sender', { id: raceBody.id, sharedContent: shared })
  assert.equal(replayAfterRemoval.status, 200)
  assert.equal((await call(`${direct}/${raceBody.id}/shared-content`)).status, 404)
  await db.query("INSERT INTO fallback_posts(id,user_id,visibility,content) VALUES($1,'sender','public','Restored origin')", [post])
  await db.query('UPDATE fallback_messages SET deleted_for_all=true WHERE id=$1', [ids[chat].id])
  await db.query('UPDATE fallback_community_channels SET is_deleted=true WHERE id=$1', [channel])
  await db.query("DELETE FROM fallback_collection_members WHERE room_kind='group' AND room_id=$1 AND user_id='recipient'", [group])
  for (const { path } of Object.values(ids)) assert.equal((await call(path)).status, 404)
})