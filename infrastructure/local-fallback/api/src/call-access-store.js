'use strict';

const crypto = require('crypto');
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const INVITE_TTL_SECONDS = 45;
const ACTIVE_LEASE_SECONDS = 120;
const MAX_INVITEES = 256;
const MAX_ACTIVE_PARTICIPANTS = 8;
function denied(code = 'CALL_ACCESS_DENIED', status = 404) { return Object.assign(new Error(code), { status, code }); }
function id(value) { return typeof value === 'string' ? value.trim().toLowerCase() : ''; }

async function ensureCallAccessTables(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_call_sessions (
    id UUID PRIMARY KEY,
    owner_user_id TEXT NOT NULL,
    context_type TEXT NOT NULL CHECK (context_type IN ('dm','group','community')),
    context_id TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'ringing' CHECK (state IN ('ringing','active','ended')),
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  )`);
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_call_invitees (
    call_id UUID NOT NULL REFERENCES fallback_call_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('owner','member')),
    state TEXT NOT NULL CHECK (state IN ('invited','accepted','declined','left')),
    accepted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (call_id,user_id)
  )`);
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_call_sessions_live_owner
    ON fallback_call_sessions(owner_user_id,expires_at) WHERE state IN ('ringing','active')`);
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_call_invitees_user
    ON fallback_call_invitees(user_id,call_id) WHERE state='accepted'`);
}

async function canonicalIdentity(pool, raw) {
  const value = id(raw);
  if (!UUID.test(value)) throw denied();
  const { rows } = await pool.query(`SELECT id::text, supabase_user_id::text
    FROM fallback_users WHERE id::text=$1 OR supabase_user_id::text=$1 LIMIT 1`, [value]);
  const row = rows[0];
  if (!row) throw denied();
  return { userId: id(row.supabase_user_id || row.id), aliases: [id(row.id), id(row.supabase_user_id)].filter(Boolean) };
}

async function pairBlocked(pool, left, right) {
  const { rows } = await pool.query(`SELECT 1 FROM fallback_user_blocks
    WHERE (lower(blocker_id)=ANY($1::text[]) AND lower(blocked_id)=ANY($2::text[]))
       OR (lower(blocker_id)=ANY($2::text[]) AND lower(blocked_id)=ANY($1::text[])) LIMIT 1`, [left.aliases, right.aliases]);
  return rows.length > 0;
}

function createCallAccessStore({ pool, loadContext, now = () => Date.now() }) {
  if (typeof loadContext !== 'function') throw new Error('CALL_CONTEXT_AUTHORIZER_REQUIRED');
  async function contextFor(db, user, contextType, contextId) {
    const context = await loadContext(db, user, contextType, contextId);
    if (!context?.memberIds?.length) throw denied();
    return new Set(context.memberIds.map(id));
  }
  async function currentMembership(db, session, user) {
    const current = await canonicalIdentity(db, user.id);
    const members = await contextFor(db, user, session.context_type, session.context_id);
    if (!current.aliases.some(alias => members.has(alias))) throw denied();
    const owner = await canonicalIdentity(db, session.owner_user_id);
    if (!owner.aliases.some(alias => members.has(alias))) throw denied();
    if (current.userId !== owner.userId && await pairBlocked(db, current, owner)) throw denied();
    if (current.userId === owner.userId && session.context_type === 'dm') {
      // A direct call needs both sides to remain eligible. Owners are not exempt
      // when the recipient blocks them; shared group/community calls stay scoped
      // to the requesting participant's relationship with the owner.
      const peers = await db.query(`SELECT user_id FROM fallback_call_invitees
        WHERE call_id=$1::uuid AND user_id<>$2 AND state IN ('invited','accepted')`, [session.id, owner.userId]);
      for (const peer of peers.rows) {
        const target = await canonicalIdentity(db, peer.user_id);
        if (!target.aliases.some(alias => members.has(alias)) || await pairBlocked(db, owner, target)) throw denied();
      }
    }
    return current;
  }
  async function transaction(task) {
    const client = await pool.connect();
    try {
      await client.query('BEGIN'); const result = await task(client); await client.query('COMMIT'); return result;
    } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error; }
    finally { client.release(); }
  }
  async function lockedSession(db, callId) {
    if (!UUID.test(id(callId))) throw denied();
    const { rows } = await db.query(`SELECT id::text,owner_user_id,context_type,context_id,request_hash,state,expires_at
      FROM fallback_call_sessions WHERE id=$1::uuid FOR UPDATE`, [id(callId)]);
    const session = rows[0];
    if (!session || !['ringing', 'active'].includes(session.state) || new Date(session.expires_at).getTime() <= now()) throw denied();
    return session;
  }
  async function quota(db, userId, excludedCallId) {
    await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`call-user:${userId}`]);
    const { rows } = await db.query(`SELECT 1 FROM fallback_call_sessions c JOIN fallback_call_invitees i ON i.call_id=c.id
      WHERE i.user_id=$1 AND i.state='accepted' AND c.state IN ('ringing','active')
        AND c.expires_at>NOW() AND c.id<>$2::uuid LIMIT 1`, [userId, excludedCallId]);
    if (rows.length) throw denied('CALL_ACCOUNT_BUSY', 409);
  }
  return {
    async prepare(user, input) {
      const callId = id(input?.callId), contextType = id(input?.contextType), contextId = id(input?.contextId);
      if (!UUID.test(callId) || !['dm', 'group', 'community'].includes(contextType) || !contextId || contextId.length > 160 ||
          !Array.isArray(input?.inviteeIds) || !input.inviteeIds.length || input.inviteeIds.length > MAX_INVITEES) throw denied('CALL_REQUEST_INVALID', 400);
      const owner = await canonicalIdentity(pool, user.id);
      const members = await contextFor(pool, user, contextType, contextId);
      if (!owner.aliases.some(alias => members.has(alias))) throw denied();
      const inviteeIds = new Set();
      for (const raw of new Set(input.inviteeIds.map(id))) {
        const target = await canonicalIdentity(pool, raw);
        if (target.userId === owner.userId || !target.aliases.some(alias => members.has(alias)) || await pairBlocked(pool, owner, target)) throw denied();
        inviteeIds.add(target.userId);
      }
      const hash = crypto.createHash('sha256').update(JSON.stringify([owner.userId, contextType, contextId, [...inviteeIds].sort()])).digest('hex');
      return transaction(async db => {
        await quota(db, owner.userId, callId);
        const inserted = await db.query(`INSERT INTO fallback_call_sessions(id,owner_user_id,context_type,context_id,request_hash,expires_at)
          VALUES($1::uuid,$2,$3,$4,$5,NOW()+($6::int*INTERVAL '1 second')) ON CONFLICT(id) DO NOTHING
          RETURNING id::text,state,expires_at`, [callId, owner.userId, contextType, contextId, hash, INVITE_TTL_SECONDS]);
        if (!inserted.rows.length) {
          const existing = await lockedSession(db, callId);
          if (existing.owner_user_id !== owner.userId || existing.request_hash !== hash) throw denied('CALL_IDEMPOTENCY_CONFLICT', 409);
          return { callId, state: existing.state, expiresAt: existing.expires_at };
        }
        await db.query(`INSERT INTO fallback_call_invitees(call_id,user_id,role,state,accepted_at)
          VALUES($1::uuid,$2,'owner','accepted',NOW())`, [callId, owner.userId]);
        for (const target of inviteeIds) await db.query(`INSERT INTO fallback_call_invitees(call_id,user_id,role,state)
          VALUES($1::uuid,$2,'member','invited')`, [callId, target]);
        return { callId, state: inserted.rows[0].state, expiresAt: inserted.rows[0].expires_at };
      });
    },
    async accept(user, callId) {
      if (!UUID.test(id(callId))) throw denied();
      const joining = await canonicalIdentity(pool, user.id);
      return transaction(async db => {
        // The per-account advisory lock always precedes the session row lock.
        // prepare and accept therefore use the same lock order on retries.
        await quota(db, joining.userId, id(callId));
        const session = await lockedSession(db, callId);
        const current = await currentMembership(db, session, user);
        const { rows } = await db.query(`SELECT state FROM fallback_call_invitees WHERE call_id=$1::uuid AND user_id=$2`, [session.id, current.userId]);
        const invite = rows[0];
        // Group/community members may join a persisted call_start from their room.
        // A public community preview is never sufficient: currentMembership requires actual membership.
        if (session.context_type === 'dm' && (!invite || !['invited', 'accepted'].includes(invite.state))) throw denied();
        if (invite?.state === 'declined') throw denied();
        const count = await db.query(`SELECT COUNT(*)::int AS count FROM fallback_call_invitees WHERE call_id=$1::uuid AND state='accepted' AND user_id<>$2`, [session.id, current.userId]);
        if (Number(count.rows[0]?.count || 0) >= MAX_ACTIVE_PARTICIPANTS) throw denied('CALL_PARTICIPANT_LIMIT_REACHED', 409);
        await db.query(`INSERT INTO fallback_call_invitees(call_id,user_id,role,state,accepted_at)
          VALUES($1::uuid,$2,'member','accepted',NOW()) ON CONFLICT(call_id,user_id) DO UPDATE
          SET state='accepted',accepted_at=COALESCE(fallback_call_invitees.accepted_at,NOW()),updated_at=NOW()`, [session.id, current.userId]);
        const updated = await db.query(`UPDATE fallback_call_sessions SET state='active',expires_at=NOW()+($2::int*INTERVAL '1 second'),updated_at=NOW()
          WHERE id=$1::uuid RETURNING expires_at`, [session.id, ACTIVE_LEASE_SECONDS]);
        return { callId: session.id, state: 'active', expiresAt: updated.rows[0]?.expires_at };
      });
    },
    async access(user, callId) {
      return transaction(async db => {
        const session = await lockedSession(db, callId);
        const current = await currentMembership(db, session, user);
        const { rows } = await db.query(`SELECT role,state FROM fallback_call_invitees WHERE call_id=$1::uuid AND user_id=$2`, [session.id, current.userId]);
        if (rows[0]?.state !== 'accepted') throw denied();
        let expiresAt = session.expires_at;
        if (session.state === 'active') {
          const updated = await db.query(`UPDATE fallback_call_sessions SET expires_at=NOW()+($2::int*INTERVAL '1 second'),updated_at=NOW()
            WHERE id=$1::uuid RETURNING expires_at`, [session.id, ACTIVE_LEASE_SECONDS]);
          expiresAt = updated.rows[0]?.expires_at;
        }
        return { allowed: true, callId: session.id, userId: current.userId, state: session.state, expiresAt };
      });
    },
    async leave(user, callId, action = 'end') {
      if (!['end', 'cancel', 'decline', 'busy', 'missed', 'collision'].includes(id(action))) throw denied('CALL_ACTION_INVALID', 400);
      return transaction(async db => {
        // Terminal state is idempotent; do not use lockedSession, which excludes ended calls.
        if (!UUID.test(id(callId))) throw denied();
        const { rows } = await db.query(`SELECT id::text,owner_user_id,context_type,state FROM fallback_call_sessions WHERE id=$1::uuid FOR UPDATE`, [id(callId)]);
        const session = rows[0]; if (!session) throw denied();
        const current = await canonicalIdentity(db, user.id);
        const member = await db.query(`SELECT state FROM fallback_call_invitees WHERE call_id=$1::uuid AND user_id=$2`, [session.id, current.userId]);
        if (!member.rows.length) throw denied();
        const state = ['decline', 'busy', 'missed', 'collision'].includes(id(action)) ? 'declined' : 'left';
        await db.query(`UPDATE fallback_call_invitees SET state=$3,updated_at=NOW() WHERE call_id=$1::uuid AND user_id=$2`, [session.id, current.userId, state]);
        if (current.userId === session.owner_user_id || session.context_type === 'dm' ||
            (id(action) === 'end' && member.rows[0]?.state === 'accepted')) {
          await db.query(`UPDATE fallback_call_sessions SET state='ended',ended_at=COALESCE(ended_at,NOW()),expires_at=NOW(),updated_at=NOW() WHERE id=$1::uuid`, [session.id]);
        }
        return { callId: session.id, left: true };
      });
    },
    async validateAlert(user, input) {
      const type = id(input?.type);
      if (!['call_invite', 'call_response', 'call_cancel'].includes(type)) return input;
      const callId = id(input?.targetId || input?.target_id || input?.extraData?.call_id || input?.extra_data?.call_id);
      if (!UUID.test(callId)) throw denied();
      const actor = await canonicalIdentity(pool, user.id);
      const recipient = await canonicalIdentity(pool, input?.recipientId || input?.recipient_id);
      const { rows } = await pool.query(`SELECT c.id::text,c.owner_user_id,c.context_type,c.context_id,c.state,c.expires_at,
        a.state AS actor_state,r.state AS recipient_state FROM fallback_call_sessions c
        JOIN fallback_call_invitees a ON a.call_id=c.id AND a.user_id=$2
        JOIN fallback_call_invitees r ON r.call_id=c.id AND r.user_id=$3 WHERE c.id=$1::uuid`, [callId, actor.userId, recipient.userId]);
      const call = rows[0]; if (!call) throw denied();
      if (type === 'call_invite' && (call.owner_user_id !== actor.userId || !['ringing', 'active'].includes(call.state) ||
          new Date(call.expires_at).getTime() <= now() || !['invited', 'accepted'].includes(call.recipient_state))) throw denied();
      // Every event's actor is bound to authenticated identity; extras cannot impersonate another caller.
      const extra = input.extraData || input.extra_data || {};
      const action = id(extra.call_action || extra.callAction || (type === 'call_cancel' ? 'cancel' : ''));
      if (type === 'call_response' && (action !== 'accept' || call.actor_state !== 'accepted' ||
          call.state !== 'active' || new Date(call.expires_at).getTime() <= now() ||
          actor.userId === call.owner_user_id || recipient.userId !== call.owner_user_id)) throw denied();
      if (type === 'call_cancel') {
        if (!['decline', 'busy', 'collision', 'missed', 'end', 'cancel'].includes(action)) throw denied('CALL_ACTION_INVALID', 400);
        const ownerCancel = actor.userId === call.owner_user_id && call.state === 'ended';
        const recipientDecline = actor.userId !== call.owner_user_id && recipient.userId === call.owner_user_id &&
          call.actor_state === 'declined' && ['decline', 'busy', 'collision', 'missed'].includes(action);
        const participantEnd = action === 'end' && call.actor_state === 'left' && call.state === 'ended';
        if (!ownerCancel && !recipientDecline && !participantEnd) throw denied();
      }
      if (type === 'call_invite') {
        await currentMembership(pool, call, user);
        await currentMembership(pool, call, { id: recipient.aliases[0], supabase_user_id: recipient.userId });
        if (await pairBlocked(pool, actor, recipient)) throw denied();
      }
      if (type === 'call_response') {
        await currentMembership(pool, call, user);
        if (await pairBlocked(pool, actor, recipient)) throw denied();
      }
      return { ...input, targetId: callId, recipientId: recipient.userId, extraData: { ...extra,
        call_id: callId, from_user_id: actor.userId, call_context_type: call.context_type, call_context_id: call.context_id } };
    },
  };
}

function registerCallAccessRoutes(app, dependencies) {
  const { pool, authRequired, getCurrentUser } = dependencies;
  const store = createCallAccessStore(dependencies);
  function route(operation) {
    return async (req, res, next) => {
      try {
        const user = await getCurrentUser(pool, req); if (!user) return res.status(401).json({ error: 'AUTH_REQUIRED' });
        const result = await operation(store, user, req);
        res.setHeader('Cache-Control', 'private, no-store'); res.json(result);
      } catch (error) {
        if (error?.status) return res.status(error.status).json({ error: error.code || 'CALL_ACCESS_DENIED' });
        next(error);
      }
    };
  }
  app.post('/v1/data/calls/prepare', authRequired, route((store, user, req) => store.prepare(user, req.body)));
  app.post('/v1/data/calls/:callId/accept', authRequired, route((store, user, req) => store.accept(user, req.params.callId)));
  app.post('/v1/data/calls/:callId/access', authRequired, route((store, user, req) => store.access(user, req.params.callId)));
  app.post('/v1/data/calls/:callId/leave', authRequired, route((store, user, req) => store.leave(user, req.params.callId, req.body?.action)));
  return store;
}

module.exports = { ACTIVE_LEASE_SECONDS, INVITE_TTL_SECONDS, MAX_ACTIVE_PARTICIPANTS, MAX_INVITEES,
  createCallAccessStore, ensureCallAccessTables, registerCallAccessRoutes };
