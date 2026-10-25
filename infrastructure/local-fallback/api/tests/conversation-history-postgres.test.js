'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')
const { Pool } = require('pg')
const { readConversationHistoryPage } = require('../src/data-routes')
const { historyPageOptions } = require('../src/conversation-history-page')

const connectionString = process.env.HISTORY_PAGE_TEST_DATABASE_URL
// The URL must point at a disposable local cluster; this test creates only its fixture tables.
test('real PostgreSQL pages, imported overlays, permissions and index plans', { skip: !connectionString }, async () => {
  const url = new URL(connectionString)
  assert.ok(['127.0.0.1', 'localhost'].includes(url.hostname), 'Use a disposable local database')
  const pool = new Pool({ connectionString, max: 2 })
  try {
    await pool.query(`CREATE SCHEMA IF NOT EXISTS supabase_import;
      CREATE TABLE fallback_messages (id uuid PRIMARY KEY, chat_id text, sender_id text, content text,
        content_type text DEFAULT 'text', created_at timestamptz, deleted_by jsonb DEFAULT '[]', deleted_for_all boolean DEFAULT false);
      CREATE TABLE fallback_room_messages (LIKE fallback_messages INCLUDING DEFAULTS);
      ALTER TABLE fallback_room_messages ADD COLUMN room_kind text, ADD COLUMN room_id text;
      CREATE TABLE supabase_import.messages (LIKE fallback_messages INCLUDING DEFAULTS);
      CREATE TABLE supabase_import.group_messages (LIKE fallback_messages INCLUDING DEFAULTS);
      ALTER TABLE supabase_import.group_messages ADD COLUMN group_id text;
      CREATE TABLE supabase_import.community_messages (LIKE fallback_messages INCLUDING DEFAULTS);
      ALTER TABLE supabase_import.community_messages ADD COLUMN community_id text;
      CREATE INDEX idx_fixture_direct ON fallback_messages (lower(chat_id),created_at,id);
      CREATE INDEX idx_fixture_room ON fallback_room_messages (room_kind,lower(room_id),created_at,id);
      INSERT INTO fallback_messages (id,chat_id,sender_id,content,created_at)
        SELECT ('00000000-0000-4000-8000-'||lpad(g::text,12,'0'))::uuid, 'direct','me','message-'||g,
          '2026-10-24T00:00:00Z'::timestamptz + g * interval '1 microsecond' FROM generate_series(1,10000) g;
      INSERT INTO fallback_room_messages (id,room_kind,room_id,sender_id,content,created_at)
        SELECT id,kind,kind,sender_id,content,created_at FROM fallback_messages CROSS JOIN unnest(ARRAY['group','community']) kind;
      INSERT INTO supabase_import.messages SELECT * FROM fallback_messages WHERE created_at <= '2026-10-24T00:00:00.000241Z';
      INSERT INTO supabase_import.group_messages SELECT *,chat_id FROM supabase_import.messages;
      INSERT INTO supabase_import.community_messages SELECT *,chat_id FROM supabase_import.messages;
      UPDATE supabase_import.group_messages SET group_id='group';
      UPDATE supabase_import.community_messages SET community_id='community';
      UPDATE fallback_messages SET deleted_by='["me"]' WHERE content='message-240';
      UPDATE fallback_messages SET deleted_for_all=true WHERE content='message-239';
      ANALYZE fallback_messages; ANALYZE fallback_room_messages;`)
    const durations = []
    for (const kind of ['chat', 'group', 'community']) {
      const room = kind === 'chat' ? 'direct' : kind
      const visible = row => !row.deleted_by.includes('me') && Date.parse(row.created_at) <= Date.parse('2026-10-24T00:00:00.001Z')
      const options = historyPageOptions({ limit: 80 })
      const start = performance.now()
      const first = await readConversationHistoryPage(pool, kind, room, options, () => true)
      durations.push({ kind, firstPageMs: Number((performance.now() - start).toFixed(2)), returned: first.messages.length,
        payloadBytes: Buffer.byteLength(JSON.stringify(first)) })
      assert.equal(first.messages.length, 80)
      assert.equal(first.messages.at(-1).content, 'message-10000')
      // Restrict subsequent traversal to a small fixture interval, then cross the imported/local overlap.
      let pageOptions = historyPageOptions({ limit: 80, before: Buffer.from(JSON.stringify({ at: '2026-10-24T00:00:00.000242Z', id: '00000000-0000-4000-8000-000000000000' })).toString('base64url') })
      const all = []
      do {
        const result = await readConversationHistoryPage(pool, kind, room, pageOptions, visible)
        all.unshift(...result.messages)
        pageOptions = result.historyPage.hasMore ? historyPageOptions({ limit: 80, before: result.historyPage.nextCursor }) : null
      } while (pageOptions)
      assert.equal(all.length, kind === 'chat' ? 240 : 241)
      assert.equal(new Set(all.map(row => row.id)).size, all.length)
      if (kind === 'chat') {
        assert.ok(!all.some(row => row.content === 'message-240'))
        assert.equal(all.find(row => row.id.endsWith('000000000239')).content, '')
      }
    }
    const plan = await pool.query(`EXPLAIN (FORMAT JSON) SELECT to_jsonb(t) FROM fallback_messages t
      WHERE lower(chat_id)='direct' ORDER BY created_at DESC,id::text COLLATE "C" DESC LIMIT 81`)
    assert.match(JSON.stringify(plan.rows), /idx_fixture_direct/)
    const baseline = await pool.query(`SELECT to_jsonb(t) AS row FROM fallback_messages t WHERE lower(chat_id)='direct' ORDER BY created_at ASC`)
    console.log(JSON.stringify({ fixture: 'disposable local PostgreSQL; not production latency', durations,
      fullHistoryRows: baseline.rows.length, fullHistoryPayloadBytes: Buffer.byteLength(JSON.stringify(baseline.rows)),
      queryPlan: plan.rows }, null, 2))
  } finally { await pool.end() }
})
