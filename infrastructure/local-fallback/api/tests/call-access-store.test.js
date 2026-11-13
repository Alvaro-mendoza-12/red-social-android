'use strict';
const assert = require('assert/strict');
const { createCallAccessStore, MAX_ACTIVE_PARTICIPANTS, MAX_INVITEES } = require('../src/call-access-store');

// SQL handlers and transaction calls are real; database results are synthetic.
// Real PostgreSQL concurrency and the Android/SFU integration require a separate suite.
const A = '11111111-1111-4111-8111-111111111111';
const B = '22222222-2222-4222-8222-222222222222';
const C = '33333333-3333-4333-8333-333333333333';
const D = '44444444-4444-4444-8444-444444444444';
const CALL = '55555555-5555-4555-8555-555555555555';
const OTHER = '66666666-6666-4666-8666-666666666666';
function fixture(kind = 'dm') {
  let sessions = new Map(), invitees = new Map(), snapshot;
  const members = new Set(kind === 'dm' ? [A, B] : [A, B, D]);
  const blocks = new Set();
  const identities = new Map([A, B, C, D].map(userId => [userId, { id: userId, supabase_user_id: null }]));
  const sqlLog = [];
  const key = (call, user) => `${call}:${user}`;
  const database = { async query(sql, params = []) {
    const text = String(sql).replace(/\s+/g, ' ').trim(); sqlLog.push(text);
    if (text === 'BEGIN') { snapshot = { sessions: structuredClone(sessions), invitees: structuredClone(invitees) }; return { rows: [] }; }
    if (text === 'COMMIT') { snapshot = undefined; return { rows: [] }; }
    if (text === 'ROLLBACK') { if (snapshot) { sessions = snapshot.sessions; invitees = snapshot.invitees; } snapshot = undefined; return { rows: [] }; }
    if (text.startsWith('SELECT pg_advisory_xact_lock')) return { rows: [] };
    if (text.includes('FROM fallback_users')) return { rows: [...identities.values()].filter(user => user.id === params[0] || user.supabase_user_id === params[0]).slice(0, 1) };
    if (text.includes('FROM fallback_user_blocks')) return { rows: blocks.has(`${params[0][0]}:${params[1][0]}`) || blocks.has(`${params[1][0]}:${params[0][0]}`) ? [{ found: 1 }] : [] };
    if (text.startsWith('SELECT 1 FROM fallback_call_sessions')) return { rows: [...sessions.values()].filter(session => session.id !== params[1] && ['ringing', 'active'].includes(session.state) && new Date(session.expires_at) > new Date() && invitees.get(key(session.id, params[0]))?.state === 'accepted').map(() => ({ found: 1 })) };
    if (text.startsWith('INSERT INTO fallback_call_sessions')) {
      if (sessions.has(params[0])) return { rows: [] };
      const session = { id: params[0], owner_user_id: params[1], context_type: params[2], context_id: params[3], request_hash: params[4], state: 'ringing', expires_at: new Date(Date.now() + params[5] * 1000).toISOString() };
      sessions.set(session.id, session); return { rows: [{ ...session }] };
    }
    if (text.startsWith('INSERT INTO fallback_call_invitees')) {
      const existing = invitees.get(key(params[0], params[1]));
      const accepted = text.includes("'owner','accepted'") || text.includes("'member','accepted'");
      invitees.set(key(params[0], params[1]), { role: existing?.role || (text.includes("'owner'") ? 'owner' : 'member'), state: accepted ? 'accepted' : 'invited', updated_at: new Date().toISOString() }); return { rows: [] };
    }
    if (text.startsWith('SELECT id::text') && text.includes('FROM fallback_call_sessions')) return { rows: sessions.has(params[0]) ? [{ ...sessions.get(params[0]) }] : [] };
    if (text.startsWith('SELECT user_id FROM fallback_call_invitees')) return { rows: [...invitees.entries()]
      .filter(([entry, invite]) => entry.startsWith(params[0] + ':') && entry !== key(params[0], params[1]) && ['invited', 'accepted'].includes(invite.state) && (typeof params[2] !== 'boolean' || !params[2] || invite.state === 'accepted') && (typeof params[2] !== 'number' || invite.state === 'accepted' || new Date(invite.updated_at).getTime() > Date.now() - params[2] * 1000))
      .map(([entry]) => ({ user_id: entry.slice(params[0].length + 1) })) };
    if (text.startsWith('SELECT COUNT(*)::int')) return { rows: [{ count: [...invitees.entries()].filter(([entry, invite]) => entry.startsWith(params[0] + ':') && entry !== key(params[0], params[1]) && invite.state === 'accepted').length }] };
    if ((text.startsWith('SELECT state') || text.startsWith('SELECT role,state')) && text.includes('FROM fallback_call_invitees')) return { rows: invitees.has(key(params[0], params[1])) ? [{ ...invitees.get(key(params[0], params[1])) }] : [] };
    if (text.startsWith('UPDATE fallback_call_sessions')) {
      const session = sessions.get(params[0]);
      if (text.includes('expanded=TRUE')) { session.expanded = true; return { rows: [] }; }
      if (text.includes("state='ended'")) { session.state = 'ended'; session.expires_at = new Date().toISOString(); }
      else { if (text.includes("state='active'")) session.state = 'active'; session.expires_at = new Date(Date.now() + params[1] * 1000).toISOString(); }
      return { rows: [{ expires_at: session.expires_at }] };
    }
    if (text.startsWith('UPDATE fallback_call_invitees')) { invitees.get(key(params[0], params[1])).state = params[2]; return { rows: [] }; }
    if (text.startsWith('SELECT c.id::text')) {
      const session = sessions.get(params[0]), actor = invitees.get(key(params[0], params[1])), recipient = invitees.get(key(params[0], params[2]));
      return { rows: session && actor && recipient ? [{ ...session, actor_state: actor.state, recipient_state: recipient.state, recipient_updated_at: recipient.updated_at }] : [] };
    }
    throw new Error(`UNHANDLED_SYNTHETIC_SQL:${text.slice(0, 100)}`);
  }, async connect() { return { query: database.query, release() {} }; } };
  const store = createCallAccessStore({ pool: database, loadContext: async (_db, user) => members.has(user.supabase_user_id || identities.get(user.id)?.supabase_user_id || user.id) ? ({ memberIds: [...members] }) : null });
  const prepare = (callId = CALL, targets = [B]) => store.prepare({ id: A }, { callId, contextType: kind, contextId: 'context-test', inviteeIds: targets });
  return { store, prepare, members, blocks, identities, database, sqlLog,
    invite: (call, user) => invitees.get(key(call, user)), session: call => sessions.get(call), sessionCount: () => sessions.size };
}
async function rejected(action, code = 'CALL_ACCESS_DENIED') { await assert.rejects(action, error => error.code === code); }
async function run() {
  let cases = 0;
  {
    const f = fixture(); await f.prepare(); await f.store.accept({ id: B }, CALL);
    await f.store.invite({ id: A }, CALL, { inviteeIds: [C] });
    f.invite(CALL, C).updated_at = new Date(Date.now() - 46000).toISOString();
    await rejected(() => f.store.accept({ id: C }, CALL), 'CALL_INVITE_EXPIRED'); cases++;
    await rejected(() => f.store.validateAlert({ id: A }, { type: 'call_invite', targetId: CALL, recipientId: C })); cases++;
    await f.store.invite({ id: A }, CALL, { inviteeIds: [C] });
    assert.equal((await f.store.accept({ id: C }, CALL)).state, 'active'); cases++;
  }
  {
    const f = fixture(); await f.prepare();
    await rejected(() => f.store.invite({ id: A }, CALL, { inviteeIds: [C] })); cases++;
    await f.store.accept({ id: B }, CALL);
    await rejected(() => f.store.invite({ id: B }, CALL, { inviteeIds: [C] })); cases++;
    const invitation = await f.store.invite({ id: A }, CALL, { inviteeIds: [C] });
    assert.equal(invitation.state, 'invited'); assert.equal(invitation.participantLimit, 8);
    assert.equal((await f.store.invite({ id: A }, CALL, { inviteeIds: [C] })).state, 'invited'); cases++;
    const alert = await f.store.validateAlert({ id: A }, { type: 'call_invite', targetId: CALL, recipientId: C, extraData: { call_context_id: 'forged' } });
    assert.equal(alert.extraData.call_context_id, ''); assert.equal(f.members.has(C), false); cases++;
    await rejected(() => f.store.access({ id: C }, CALL)); cases++;
    await f.store.accept({ id: C }, CALL); assert.equal((await f.store.access({ id: C }, CALL)).allowed, true); cases++;
    await rejected(() => f.store.accept({ id: D }, CALL)); cases++;
    await f.store.leave({ id: C }, CALL, 'end'); assert.equal(f.session(CALL).state, 'active');
    await f.store.validateAlert({ id: C }, { type: 'call_cancel', targetId: CALL, recipientId: A, extraData: { call_action: 'end' } });
    assert.equal((await f.store.access({ id: B }, CALL)).allowed, true); cases++;
    await f.store.invite({ id: A }, CALL, { inviteeIds: [D] }); await f.store.leave({ id: D }, CALL, 'decline');
    assert.equal((await f.store.access({ id: A }, CALL)).state, 'active'); cases++;
    await f.store.leave({ id: A }, CALL, 'end'); await rejected(() => f.store.access({ id: B }, CALL)); cases++;
  }
  {
    const f = fixture(); await f.prepare(); await f.store.accept({ id: B }, CALL);
    f.blocks.add(`${C}:${B}`); await rejected(() => f.store.invite({ id: A }, CALL, { inviteeIds: [C] }));
    assert.equal(f.invite(CALL, C), undefined); assert.equal(f.session(CALL).expanded, undefined); cases++;
    f.blocks.clear(); await f.store.invite({ id: A }, CALL, { inviteeIds: [C] });
    f.blocks.add(`${C}:${B}`); await rejected(() => f.store.accept({ id: C }, CALL));
    assert.equal((await f.store.access({ id: B }, CALL)).allowed, true); cases++;
  }
  {
    const f = fixture('group'); await f.prepare(); await f.store.accept({ id: B }, CALL);
    await rejected(() => f.store.invite({ id: A }, CALL, { inviteeIds: [C] })); cases++;
  }
  {
    const f = fixture(); await f.prepare(); await f.store.accept({ id: B }, CALL);
    for (let i = 0; i < 6; i++) {
      const target = `${String(i + 10).padStart(8, '0')}-7777-4777-8777-777777777777`;
      f.identities.set(target, { id: target }); await f.store.invite({ id: A }, CALL, { inviteeIds: [target] });
    }
    await rejected(() => f.store.invite({ id: A }, CALL, { inviteeIds: [C] }), 'CALL_PARTICIPANT_LIMIT_REACHED'); cases++;
  }
  {
    const f = fixture(); await rejected(() => f.store.access({ id: A }, CALL)); cases++;
    await f.prepare(); assert.equal(f.sessionCount(), 1);
    // Caller may join immediately after prepare, before notification delivery completes.
    assert.equal((await f.store.access({ id: A }, CALL)).allowed, true); cases++;
    await rejected(() => f.store.access({ id: C }, CALL)); cases++;
    await rejected(() => f.store.access({ id: B }, CALL)); cases++;
    await f.store.accept({ id: B }, CALL); assert.equal((await f.store.access({ id: B }, CALL)).allowed, true); cases++;
    f.blocks.add(`${A}:${B}`); await rejected(() => f.store.access({ id: B }, CALL)); cases++;
  }
  {
    const f = fixture(); await f.prepare(); f.blocks.add(`${B}:${A}`);
    await rejected(() => f.store.accept({ id: B }, CALL)); cases++;
  }
  {
    const f = fixture(); await f.prepare(); await f.store.accept({ id: B }, CALL);
    f.members.delete(B); await rejected(() => f.store.access({ id: B }, CALL)); cases++;
  }
  {
    const f = fixture(); await f.prepare(); await f.store.leave({ id: A }, CALL, 'cancel');
    await rejected(() => f.store.access({ id: A }, CALL)); await rejected(() => f.store.accept({ id: B }, CALL)); cases++;
  }
  {
    const f = fixture(); await f.prepare(); f.session(CALL).expires_at = new Date(Date.now() - 1).toISOString();
    await rejected(() => f.store.access({ id: A }, CALL)); await rejected(() => f.store.accept({ id: B }, CALL)); cases++;
  }
  {
    const f = fixture('group'); await f.prepare(); await f.store.accept({ id: D }, CALL);
    assert.equal((await f.store.access({ id: D }, CALL)).allowed, true); cases++;
    await rejected(() => f.store.accept({ id: C }, CALL)); cases++;
  }
  {
    const f = fixture(); await f.prepare(); await f.prepare(); assert.equal(f.sessionCount(), 1); cases++;
    await rejected(() => f.prepare(OTHER), 'CALL_ACCOUNT_BUSY'); cases++;
  }
  {
    const f = fixture('group'); await f.prepare(); await rejected(() => f.prepare(CALL, [B, D]), 'CALL_IDEMPOTENCY_CONFLICT'); assert.equal(f.sessionCount(), 1); cases++;
  }
  {
    const f = fixture(); await f.prepare();
    const alert = await f.store.validateAlert({ id: A }, { type: 'call_invite', targetId: CALL, recipientId: B,
      extraData: { from_user_id: C, call_context_type: 'group', call_context_id: 'forged-room' } });
    assert.equal(alert.extraData.from_user_id, A); assert.equal(alert.extraData.call_context_type, 'dm'); assert.equal(alert.extraData.call_context_id, 'context-test'); cases++;
    await rejected(() => f.store.validateAlert({ id: C }, { type: 'call_invite', targetId: CALL, recipientId: B })); cases++;
  }
  {
    const f = fixture(); f.blocks.add(`${B}:${A}`); await rejected(() => f.prepare()); assert.equal(f.sessionCount(), 0); cases++;
  }
  {
    const f = fixture(); await f.prepare(); await f.store.accept({ id: B }, CALL);
    const response = await f.store.validateAlert({ id: B }, { type: 'call_response', targetId: CALL, recipientId: A, extraData: { call_action: 'accept', from_user_id: C } });
    assert.equal(response.extraData.from_user_id, B); cases++;
    // A fresh signaling socket uses the same persisted acceptance after reconnect.
    assert.equal((await f.store.access({ id: B }, CALL)).state, 'active'); cases++;
    await f.store.leave({ id: A }, CALL, 'cancel');
    await rejected(() => f.store.validateAlert({ id: B }, { type: 'call_response', targetId: CALL, recipientId: A, extraData: { call_action: 'accept' } })); cases++;
    const cancellation = await f.store.validateAlert({ id: A }, { type: 'call_cancel', targetId: CALL, recipientId: B, extraData: { call_action: 'cancel' } });
    assert.equal(cancellation.targetId, CALL); cases++;
    await f.store.leave({ id: A }, CALL, 'cancel'); assert.equal(f.session(CALL).state, 'ended'); cases++;
    await f.prepare(OTHER); assert.equal(f.session(OTHER).state, 'ringing'); cases++;
  }
  for (const action of ['decline', 'busy', 'collision', 'missed']) {
    const f = fixture(); await f.prepare(); await f.store.leave({ id: B }, CALL, action);
    const alert = await f.store.validateAlert({ id: B }, { type: 'call_cancel', targetId: CALL, recipientId: A, extraData: { call_action: action } });
    assert.equal(alert.extraData.call_action, action); assert.equal(f.session(CALL).state, 'ended');
    await rejected(() => f.store.access({ id: A }, CALL)); cases++;
  }
  {
    const f = fixture('group'); await f.prepare(); await f.store.leave({ id: B }, CALL, 'decline');
    assert.equal(f.session(CALL).state, 'ringing'); await rejected(() => f.store.accept({ id: B }, CALL)); cases++;
    await f.store.accept({ id: D }, CALL); await f.store.leave({ id: D }, CALL, 'end');
    assert.equal(f.session(CALL).state, 'ended'); await rejected(() => f.store.access({ id: A }, CALL)); cases++;
  }
  {
    const f = fixture('community'); await f.prepare(); f.members.delete(A);
    await rejected(() => f.store.accept({ id: D }, CALL)); cases++;
  }
  {
    const f = fixture('group'); await f.prepare(); f.members.delete(B);
    await rejected(() => f.store.validateAlert({ id: A }, { type: 'call_invite', targetId: CALL, recipientId: B })); cases++;
  }
  {
    const f = fixture(); await f.prepare(); await f.store.accept({ id: B }, CALL); f.blocks.add(`${A}:${B}`);
    await rejected(() => f.store.validateAlert({ id: B }, { type: 'call_response', targetId: CALL, recipientId: A, extraData: { call_action: 'accept' } })); cases++;
  }
  {
    const f = fixture(); const alias = '77777777-7777-4777-8777-777777777777';
    f.identities.delete(B); f.identities.set(alias, { id: alias, supabase_user_id: B });
    await f.prepare(CALL, [alias]); await f.store.accept({ id: alias }, CALL);
    assert.equal((await f.store.access({ id: alias }, CALL)).userId, B); cases++;
  }
  {
    const f = fixture('group'); await f.prepare();
    const additional = Array.from({ length: MAX_ACTIVE_PARTICIPANTS }, (_, i) => `${String(i + 10).padStart(8, '0')}-7777-4777-8777-777777777777`);
    for (const userId of additional) { f.members.add(userId); f.identities.set(userId, { id: userId, supabase_user_id: null }); }
    for (const userId of additional.slice(0, MAX_ACTIVE_PARTICIPANTS - 1)) await f.store.accept({ id: userId }, CALL);
    await rejected(() => f.store.accept({ id: additional.at(-1) }, CALL), 'CALL_PARTICIPANT_LIMIT_REACHED'); cases++;
    assert.equal(f.invite(CALL, additional.at(-1)), undefined); cases++;
  }
  {
    const f = fixture(); await rejected(() => f.prepare(CALL, Array(MAX_INVITEES + 1).fill(B)), 'CALL_REQUEST_INVALID');
    assert.equal(f.sessionCount(), 0); cases++;
    await f.prepare(); await rejected(() => f.store.leave({ id: C }, CALL));
    assert.equal(f.session(CALL).state, 'ringing'); cases++;
  }
  {
    const f = fixture(); await f.prepare();
    await f.store.prepare({ id: B }, { callId: OTHER, contextType: 'dm', contextId: 'context-test', inviteeIds: [A] });
    await rejected(() => f.store.accept({ id: B }, CALL), 'CALL_ACCOUNT_BUSY'); cases++;
    await f.store.leave({ id: B }, OTHER, 'collision'); await f.store.accept({ id: B }, CALL);
    const acceptSql = f.sqlLog.slice(f.sqlLog.findLastIndex(sql => sql === 'BEGIN'));
    assert(acceptSql.findIndex(sql => sql.startsWith('SELECT pg_advisory_xact_lock')) <
      acceptSql.findIndex(sql => sql.includes('FROM fallback_call_sessions WHERE id=') && sql.includes('FOR UPDATE'))); cases++;
    assert.equal((await f.store.access({ id: B }, CALL)).allowed, true); cases++;
    const acceptLog = f.sqlLog.slice(f.sqlLog.findLastIndex(sql => sql === 'BEGIN'));
    assert(acceptLog.at(-1) === 'COMMIT'); cases++;
  }
  for (const blockedPair of [`${A}:${B}`, `${B}:${A}`]) {
    const f = fixture(); await f.prepare(); await f.store.accept({ id: B }, CALL);
    f.blocks.add(blockedPair);
    await rejected(() => f.store.access({ id: A }, CALL)); cases++;
    await rejected(() => f.store.access({ id: B }, CALL)); cases++;
  }
  for (const kind of ['group', 'community']) {
    const f = fixture(kind); await f.prepare(); await f.store.accept({ id: B }, CALL); await f.store.accept({ id: D }, CALL);
    f.blocks.add(`${B}:${A}`);
    assert.equal((await f.store.access({ id: A }, CALL)).allowed, true);
    assert.equal((await f.store.access({ id: D }, CALL)).allowed, true); cases++;
    await rejected(() => f.store.access({ id: B }, CALL)); cases++;
  }
  console.log(JSON.stringify({ suite: 'call-access-store', cases, databaseMock: true, networkUsed: false }));
  return { suite: 'call-access-store', cases, databaseMock: true, networkUsed: false };
}
module.exports = { fixture, rejected, run, A, B, C, D, CALL, OTHER };
if (require.main === module) run().catch(error => { console.error(error); process.exitCode = 1; });
