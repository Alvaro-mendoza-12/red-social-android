// Explicitly opt in. All writable fallback tables live in a disposable schema.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Pool } = require('pg')

async function verifyPollVotes(testApi) {
  assert.equal(process.env.VERIFY_POLL_TEST_ALLOW_TEMP_SCHEMA, '1', 'Temporary-schema opt-in required')
  const connectionString = process.env.VERIFY_POLL_TEST_DATABASE_URL
  assert.ok(connectionString, 'Explicit test database URL required')
  const schema = 'qa_poll_' + crypto.randomBytes(10).toString('hex')
  assert.match(schema, /^qa_poll_[a-f0-9]{20}$/)
  const setup = new Pool({ connectionString, max: 1, connectionTimeoutMillis: 5000 })
  let pool
  const actor = crypto.randomUUID(), alias = crypto.randomUUID(), peer = crypto.randomUUID()
  const outsider = crypto.randomUUID(), room = crypto.randomUUID(), poll = crypto.randomUUID()
  const optionA = crypto.randomUUID(), optionB = crypto.randomUUID()
  let created = false
  try {
    await setup.query(`CREATE SCHEMA ${schema}`)
    created = true
    pool = new Pool({ connectionString, max: 8, connectionTimeoutMillis: 5000,
      options: `-c search_path=${schema},public -c statement_timeout=10000` })
    await pool.query(`
      CREATE TABLE ${schema}.fallback_users(id uuid PRIMARY KEY, supabase_user_id text, email text, display_name text, avatar_url text);
      CREATE TABLE ${schema}.fallback_chats(id uuid PRIMARY KEY, participants text[]);
      CREATE TABLE ${schema}.fallback_chat_participants(chat_id uuid, user_id text);
      CREATE TABLE ${schema}.fallback_collection_rooms(id uuid PRIMARY KEY, room_kind text, creator_id text);
      CREATE TABLE ${schema}.fallback_collection_members(room_id text, room_kind text, user_id text);
      CREATE TABLE ${schema}.fallback_compat_rows(table_name text, row_id text, row jsonb, updated_at timestamptz DEFAULT now(), PRIMARY KEY(table_name,row_id));
    `)
    await pool.query(`INSERT INTO fallback_users(id,supabase_user_id,display_name) VALUES($1,$2,'QA poll actor'),($3,NULL,'QA poll peer'),($4,NULL,'QA outsider')`, [actor,alias,peer,outsider])
    await pool.query(`INSERT INTO fallback_collection_members(room_id,room_kind,user_id) VALUES($1,'group',$2),($1,'group',$3)`, [room,alias,peer])
    const store = (table, row) => pool.query(`INSERT INTO fallback_compat_rows(table_name,row_id,row) VALUES($1,$2,$3)`, [table,row.id,row])
    await store('message_polls', { id: poll, scope_type: 'group', scope_id: room, creator_id: peer, allows_multiple: false, is_closed: false })
    await store('message_poll_options', { id: optionA, poll_id: poll, option_text: 'Uno', display_order: 0 })
    await store('message_poll_options', { id: optionB, poll_id: poll, option_text: 'Dos', display_order: 1 })
    // Imported tables use an explicit schema. Prove the synthetic poll has no
    // imported rows before invoking the exact, actor-scoped deletion path.
    const imported = await setup.query("SELECT to_regclass('supabase_import.message_poll_votes') AS oid")
    if (imported.rows[0].oid) {
      const count = await setup.query(`SELECT count(*)::int AS n FROM supabase_import.message_poll_votes v WHERE lower(to_jsonb(v)->>'poll_id')=$1`, [poll])
      assert.equal(count.rows[0].n, 0)
    }
    const vote = (user, option, selected = true) => testApi.votePollOption(pool, { sub: user }, { p_poll_id: poll, p_option_id: option, p_selected: selected })
    const checks = []
    let state = await vote(actor, optionA)
    assert.equal(state[0].voted_by_me, true); checks.push('select')
    await vote(peer, optionA)
    state = await vote(actor, optionB)
    assert.equal(state[0].vote_count, 1); assert.equal(state[1].vote_count, 1); checks.push('change-preserves-peer')
    state = await vote(actor, optionB, false)
    assert.equal(state[0].total_votes, 1); checks.push('remove-preserves-peer')
    await Promise.all(Array.from({ length: 20 }, (_, i) => vote(actor, i % 2 ? optionA : optionB)))
    const ownCount = await pool.query(`SELECT count(*)::int AS n FROM fallback_compat_rows WHERE table_name='message_poll_votes' AND row->>'poll_id'=$1 AND row->>'user_id'=$2`, [poll,alias])
    assert.equal(ownCount.rows[0].n, 1); checks.push('20-concurrent-choices-one-vote')
    await assert.rejects(vote(outsider, optionA), /POLL_ACCESS_DENIED/); checks.push('outsider-denied')
    await assert.rejects(vote(actor, crypto.randomUUID()), /POLL_OPTION_NOT_FOUND/); checks.push('foreign-option-denied')
    await pool.query(`UPDATE fallback_compat_rows SET row=row||'{"is_closed":true}'::jsonb WHERE table_name='message_polls' AND row_id=$1`, [poll])
    await assert.rejects(vote(actor, optionA), /POLL_CLOSED/); checks.push('closed-denied')
    await pool.query(`UPDATE fallback_compat_rows SET row=row||'{"is_closed":false,"allows_multiple":true}'::jsonb WHERE table_name='message_polls' AND row_id=$1`, [poll])
    await vote(actor, optionA); await vote(actor, optionB); state = await vote(actor, optionB)
    assert.equal(state.filter(x => x.voted_by_me).length, 2)
    state = await vote(actor, optionA, false)
    assert.equal(state.filter(x => x.voted_by_me).length, 1); checks.push('multiple-idempotent-remove')
    await pool.query(`UPDATE fallback_compat_rows SET row=row||'{"allows_multiple":false}'::jsonb WHERE table_name='message_polls' AND row_id=$1`, [poll])
    await pool.query(`CREATE FUNCTION ${schema}.reject_qa_vote() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'QA_INJECTED_FAILURE'; END $$;
      CREATE TRIGGER reject_qa_vote BEFORE INSERT ON ${schema}.fallback_compat_rows FOR EACH ROW WHEN(NEW.table_name='message_poll_votes') EXECUTE FUNCTION ${schema}.reject_qa_vote();`)
    await assert.rejects(vote(actor, optionA), /QA_INJECTED_FAILURE/)
    const current = await testApi.buildPollState(pool, { values:[actor,alias],ids:new Set([actor,alias]),preferredUserId:alias }, poll)
    assert.equal(current.find(x => x.option_id === optionB).voted_by_me, true); checks.push('write-failure-rollback')
    return { checks, count: checks.length, isolated_schema: true }
  } finally {
    if (pool) await pool.end()
    if (created) await setup.query(`DROP SCHEMA ${schema} CASCADE`)
    if (created) assert.equal((await setup.query('SELECT 1 FROM pg_namespace WHERE nspname=$1',[schema])).rowCount, 0)
    await setup.end()
  }
}

module.exports = { verifyPollVotes }
if (require.main === module) {
  verifyPollVotes(require('../src/postgrest-compat-routes')._test)
    .then(result => console.log(JSON.stringify({ ...result, cleanup_verified: true })))
    .catch(error => { console.error('POLL_QA_FAILED', error.code || error.name); process.exitCode = 1 })
}
