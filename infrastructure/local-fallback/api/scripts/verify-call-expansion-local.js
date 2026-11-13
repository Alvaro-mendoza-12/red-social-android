'use strict';
// Uses only an explicitly selected loopback API and synthetic QA accounts.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { randomUUID, randomBytes } = require('node:crypto');
const { Pool } = require('pg');
const base = process.env.QA_API_URL || 'http://127.0.0.1:55731';
const database = process.env.QA_DATABASE_URL;
if (new URL(base).hostname !== '127.0.0.1' || !database || new URL(database).hostname !== '127.0.0.1') throw Error('LOOPBACK_QA_REQUIRED');
const accounts = JSON.parse(fs.readFileSync(process.env.QA_ACCOUNT_FILE, 'utf8'));
const pool = new Pool({ connectionString: database });
const checks = [];
async function request(path, token, body, expected = 200, method = 'POST') {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: method === 'GET' ? undefined : JSON.stringify(body || {}), signal: AbortSignal.timeout(15000) });
  const data = await response.json();
  assert.equal(response.status, expected, path + ': ' + (data.error || response.status));
  return data;
}
async function login(account) { return (await request('/v1/auth/login', '', { email: account.email, password: account.password })).accessToken; }
async function main() {
  const [a, b, c] = [accounts.a, accounts.b, accounts.c];
  const [ta, tb, tc] = await Promise.all([a, b, c].map(login));
  const callId = randomUUID(), route = '/v1/data/calls/' + callId;
  let fourth, blocked = false;
  const check = async (name, fn) => { await fn(); checks.push(name); console.log('PASS', name); };
  try {
    await request('/v1/data/calls/prepare', ta, { callId, contextType: 'dm', contextId: accounts.chat, inviteeIds: [b.id] });
    await request(route + '/accept', tb);
    await check('unaccepted outsider and accepted non-owner cannot invite', async () => {
      for (const token of [tb, tc]) await request(route + '/invite', token, { inviteeIds: [c.id] }, 404);
    });
    await check('block against any current participant rejects the invitation', async () => {
      await request('/v1/data/profiles/' + c.id + '/block', tb); blocked = true;
      await request(route + '/invite', ta, { inviteeIds: [c.id] }, 404);
      await request('/v1/data/profiles/' + c.id + '/block', tb, {}, 200, 'DELETE'); blocked = false;
    });
    await check('concurrent invitation retries create exactly one authorization', async () => {
      const results = await Promise.all(Array.from({ length: 5 }, () => request(route + '/invite', ta, { inviteeIds: [c.id] })));
      assert(results.every(result => result.inviteeId === c.id && result.state === 'invited'));
      const { rows } = await pool.query('SELECT COUNT(*)::int AS count FROM fallback_call_invitees WHERE call_id=$1::uuid AND user_id=$2', [callId, c.id]);
      assert.equal(rows[0].count, 1);
    });
    await check('the invitee gains no permission to the original private chat', async () => {
      await request('/v1/data/messages/chats/' + accounts.chat, tc, null, 404, 'GET');
      const alert = await request('/v1/data/notifications', ta, { recipientId: c.id, type: 'call_invite', targetId: callId, content: 'QA local', extraData: { call_context_id: accounts.chat } }, 201);
      const notification = alert.notification || alert;
      const extra = notification.extra_data || notification.extraData;
      if (extra) assert.equal(extra.call_context_id, '');
      await request(route + '/access', tc, {}, 404);
    });
    await check('an expired invitation cannot be accepted and can be renewed without duplicates', async () => {
      await pool.query("UPDATE fallback_call_invitees SET updated_at=NOW()-INTERVAL '46 seconds' WHERE call_id=$1::uuid AND user_id=$2", [callId, c.id]);
      const expired = await request(route + '/accept', tc, {}, 404); assert.equal(expired.error, 'CALL_INVITE_EXPIRED');
      await request(route + '/invite', ta, { inviteeIds: [c.id] });
      const { rows } = await pool.query('SELECT COUNT(*)::int AS count FROM fallback_call_invitees WHERE call_id=$1::uuid AND user_id=$2', [callId, c.id]);
      assert.equal(rows[0].count, 1);
    });
    await check('a block introduced after invitation prevents acceptance', async () => {
      await request('/v1/data/profiles/' + c.id + '/block', tb); blocked = true;
      await request(route + '/accept', tc, {}, 404);
      assert.equal((await request(route + '/access', tb)).state, 'active');
      await request('/v1/data/profiles/' + c.id + '/block', tb, {}, 200, 'DELETE'); blocked = false;
    });
    await request(route + '/accept', tc);
    await check('one participant leaving preserves both original permissions', async () => {
      await request(route + '/leave', tc, { action: 'end' });
      for (const token of [ta, tb]) assert.equal((await request(route + '/access', token)).state, 'active');
    });
    const password = 'Qa9!' + randomBytes(20).toString('hex');
    fourth = await request('/v1/auth/register', '', { email: 'call-' + randomUUID() + '@example.invalid', password, displayName: 'Invitado QA 129', username: 'qa129_' + randomBytes(6).toString('hex'), birthDate: '2000-01-14' }, 201);
    await check('a declined extra invitation does not terminate the original call', async () => {
      await request(route + '/invite', ta, { inviteeIds: [fourth.user.id] });
      await request(route + '/leave', fourth.accessToken, { action: 'decline' });
      for (const token of [ta, tb]) assert.equal((await request(route + '/access', token)).state, 'active');
    });
    await check('ending by the owner revokes every participant', async () => {
      await request(route + '/leave', ta, { action: 'end' });
      for (const token of [ta, tb, tc]) await request(route + '/access', token, {}, 404);
    });
    const report = { checks, database: 'PostgreSQL loopback', syntheticAccounts: true, productionRequests: false };
    if (process.env.QA_RESULT_FILE) fs.writeFileSync(process.env.QA_RESULT_FILE, JSON.stringify(report, null, 2));
  } finally {
    if (blocked) await request('/v1/data/profiles/' + c.id + '/block', tb, {}, 200, 'DELETE').catch(() => {});
    await request(route + '/leave', ta, { action: 'end' }).catch(() => {});
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; }).finally(() => pool.end());
