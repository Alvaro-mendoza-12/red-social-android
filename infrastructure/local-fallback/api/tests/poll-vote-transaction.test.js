const assert = require('node:assert/strict')
const test = require('node:test')
const { _test } = require('../src/postgrest-compat-routes')

const actor = '11111111-1111-4111-8111-111111111111'
const alias = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const other = '22222222-2222-4222-8222-222222222222'
const pollId = '33333333-3333-4333-8333-333333333333'
const roomId = '44444444-4444-4444-8444-444444444444'
const a = '55555555-5555-4555-8555-555555555555'
const b = '66666666-6666-4666-8666-666666666666'

function fixture({ multiple = false, imported = [], closed = false, failInsert = false } = {}) {
  const db = {
    tables: {
      message_polls: [{ id: pollId, scope_type: 'group', scope_id: roomId, creator_id: other, created_at:'2026-09-01T00:00:00Z', allows_multiple: multiple, is_closed: closed }],
      message_poll_options: [{ id: a, poll_id: pollId, option_text: 'Uno' }, { id: b, poll_id: pollId, option_text: 'Dos' }],
      message_poll_votes: [],
    },
    imported: structuredClone(imported),
    history: [], releaseCount: 0,
  }
  let tail = Promise.resolve()
  let insertFailure = failInsert
  function remove(rows, params) {
    const [poll, users, all, option] = params
    assert.equal(poll, pollId)
    assert.deepEqual(users, [actor, alias])
    return rows.filter(v => !(v.poll_id === poll && users.includes(v.user_id.toLowerCase()) && (all || v.option_id === option)))
  }
  async function query(sql, params = [], connection) {
    const text = String(sql).trim()
    db.history.push({ text, params, connection: connection?.id || 'pool' })
    if (text.includes('FROM fallback_users')) {
      return { rows: [{ local_user_id: params[0], public_user_id: params[0] === actor ? alias : null }] }
    }
    if (!connection && text.includes('FROM fallback_collection_members')) return { rows: [] }
    assert.ok(connection, 'Every policy read and vote mutation must use the transaction connection')
    if (text === 'BEGIN') return { rows: [] }
    if (text.includes('pg_advisory_xact_lock')) {
      assert.deepEqual(params, [`poll-vote:${pollId}:${alias}`])
      const preceding = tail
      tail = new Promise(resolve => { connection.unlock = resolve })
      await preceding
      connection.snapshot = structuredClone({ tables: db.tables, imported: db.imported })
      return { rows: [] }
    }
    if (text === 'COMMIT' || text === 'ROLLBACK') {
      if (text === 'ROLLBACK' && connection.snapshot) Object.assign(db, connection.snapshot)
      connection.unlock?.()
      return { rows: [] }
    }
    if (text.includes('SELECT to_regclass')) {
      return { rows: [{ oid: params[0] === 'supabase_import.message_poll_votes' ? 1 : null }] }
    }
    if (text.startsWith('SELECT') && text.includes('"supabase_import"."message_poll_votes"')) {
      return { rows: db.imported.map(row => ({ row })) }
    }
    if (text.startsWith('SELECT') && text.includes('FROM fallback_compat_rows')) {
      return { rows: (db.tables[params[0]] || []).map(row => ({ row })) }
    }
    if (text.includes('FROM fallback_chats')) return { rows: [] }
    if (text.includes('FROM fallback_collection_chat_history') || text.includes('FROM fallback_collection_policies') || text.includes('FROM fallback_collection_history_grants')) return {rows:[]}
    if (text.includes('FROM fallback_collection_rooms')) return {rows:[{id:roomId,room_kind:'group',creator_id:other}]}
    if (text.includes('SELECT * FROM fallback_collection_members')) return {rows:params[0]==='group'?[{room_id:roomId,user_id:alias,joined_at:'2026-08-01T00:00:00Z'}]:[]}
    if (text.startsWith('SELECT') && text.includes('FROM fallback_collection_members')) {
      return { rows: params[0] === 'group' && params[1].includes(actor) ? [{ id: roomId }] : [] }
    }
    if (text.startsWith('DELETE FROM fallback_compat_rows')) {
      assert.match(text, /table_name = 'message_poll_votes'/)
      assert.match(text, /lower\(row->>'poll_id'\) = \$1/)
      assert.match(text, /lower\(row->>'user_id'\) = ANY\(\$2::text\[\]\)/)
      db.tables.message_poll_votes = remove(db.tables.message_poll_votes, params)
      return { rows: [] }
    }
    if (text.startsWith('SELECT novvin_import_mutation.delete_poll_votes(')) {
      assert.match(text, /\$1::text,\$2::text\[\],\$3::boolean,\$4::text/)
      db.imported = remove(db.imported, params)
      return { rows: [] }
    }
    if (text.startsWith('INSERT INTO fallback_compat_rows')) {
      assert.equal(params[0], 'message_poll_votes')
      if (insertFailure) { insertFailure = false; throw new Error('injected write failure') }
      db.tables.message_poll_votes.push(structuredClone(params[2]))
      return { rows: [] }
    }
    assert.fail('Unexpected query: ' + text)
  }
  let nextId = 0
  const pool = {
    query: (sql, params) => query(sql, params),
    async connect() {
      const connection = { id: ++nextId, query: (sql, params) => query(sql, params, connection), release() { db.releaseCount++ } }
      return connection
    },
  }
  const vote = (option, selected = true) => _test.votePollOption(pool, { sub: actor }, { p_poll_id: pollId, p_option_id: option, p_selected: selected })
  return { db, pool, vote }
}

test('a single-choice vote can be changed and removed immediately', async () => {
  const { db, vote } = fixture()
  await vote(a)
  const changed = await vote(b)
  assert.equal(changed.find(x => x.option_id === a).vote_count, 0)
  assert.equal(changed.find(x => x.option_id === b).voted_by_me, true)
  const removed = await vote(b, false)
  assert.equal(removed[0].total_votes, 0)
  assert.equal(db.releaseCount, 3)
})

test('concurrent choices from one account serialize to exactly one vote', async () => {
  const { db, vote } = fixture()
  await Promise.all([vote(a), vote(b), vote(a)])
  assert.equal(db.tables.message_poll_votes.length, 1)
  assert.equal(db.tables.message_poll_votes[0].option_id, a)
  assert.equal(db.history.filter(x => x.text === 'COMMIT').length, 3)
  assert.equal(db.releaseCount, 3)
})

test('multiple-choice selection is idempotent and removal leaves the other choice', async () => {
  const { vote } = fixture({ multiple: true })
  await vote(a)
  await vote(b)
  assert.equal((await vote(a))[0].total_votes, 2)
  const removed = await vote(a, false)
  assert.equal(removed[0].vote_count, 0)
  assert.equal(removed[1].vote_count, 1)
})

test('imported alias votes are replaced without deleting another voter or poll', async () => {
  const { db, vote } = fixture({ imported: [
    { id: 'old', poll_id: pollId, option_id: a, user_id: actor.toUpperCase() },
    { id: 'peer', poll_id: pollId, option_id: a, user_id: other },
    { id: 'different', poll_id: 'different', option_id: a, user_id: actor },
  ] })
  const state = await vote(b)
  assert.deepEqual(db.imported.map(x => x.id), ['peer', 'different'])
  assert.equal(state[0].vote_count, 1)
  assert.equal(state[1].vote_count, 1)
  assert.equal((await vote(b, false))[0].total_votes, 1)
})

test('failed insertion rolls back the previous choice and releases the connection', async () => {
  const { db, vote } = fixture({ failInsert: true, imported: [{ id: 'old', poll_id: pollId, option_id: a, user_id: actor }] })
  await assert.rejects(vote(b), /injected write failure/)
  assert.equal(db.imported[0].option_id, a)
  assert.equal(db.tables.message_poll_votes.length, 0)
  assert.equal(db.history.at(-1).text, 'ROLLBACK')
  assert.equal(db.releaseCount, 1)
})

test('closed polls and options from a different poll cannot mutate votes', async () => {
  for (const closed of [true, false]) {
    const { db, vote } = fixture({ closed })
    await assert.rejects(vote(closed ? a : 'different-option'), new RegExp(closed ? 'POLL_CLOSED' : 'POLL_OPTION_NOT_FOUND'))
    assert.equal(db.history.filter(x => x.text.startsWith('DELETE')).length, 0)
    assert.equal(db.history.at(-1).text, 'ROLLBACK')
    assert.equal(db.releaseCount, 1)
  }
})
