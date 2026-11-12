'use strict'
const { ensureFriendRequestState } = require('./friend-request-state')

const INTRO_ERRORS = new Set(['FRIEND_REQUEST_PENDING', 'FRIEND_REQUEST_REJECTED',
  'MESSAGE_REQUEST_PENDING', 'MESSAGE_REQUEST_REJECTED', 'CHAT_BLOCKED_BY_RECIPIENT', 'CHAT_PARTICIPANT_REQUIRED'])

async function ensureDirectMessageRequestPolicy(pool) {
  await ensureFriendRequestState(pool)
  // Existing conversations with replies remain usable. Unanswered strangers
  // get one introduction, enforced for every INSERT transport and legacy chat.
  await pool.query(`ALTER TABLE fallback_chats ADD COLUMN IF NOT EXISTS intro_policy_started_at TIMESTAMPTZ`)
  await pool.query(`ALTER TABLE fallback_chats ALTER COLUMN intro_policy_started_at SET DEFAULT NOW()`)
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_message_requests (
    chat_id UUID PRIMARY KEY REFERENCES fallback_chats(id) ON DELETE CASCADE,
    sender_id TEXT NOT NULL, recipient_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('pending','accepted','rejected')),
    first_message_id TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), CHECK(sender_id <> recipient_id)
  )`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_message_requests_recipient
    ON fallback_message_requests(lower(recipient_id),status,created_at DESC)`)
  await pool.query(`CREATE OR REPLACE FUNCTION fallback_request_identity(raw_id TEXT)
    RETURNS TEXT LANGUAGE sql STABLE AS $$
      SELECT COALESCE((SELECT lower(COALESCE(supabase_user_id::text,id::text))
        FROM fallback_users WHERE lower(id::text)=lower(raw_id)
          OR lower(COALESCE(supabase_user_id::text,''))=lower(raw_id) LIMIT 1),lower(raw_id))
    $$`)
  await pool.query(`CREATE OR REPLACE FUNCTION fallback_request_pair_trusted(a TEXT,b TEXT)
    RETURNS BOOLEAN LANGUAGE plpgsql STABLE AS $$
    DECLARE trusted BOOLEAN; edges JSONB; outgoing BOOLEAN; incoming BOOLEAN;
    BEGIN
      IF EXISTS (SELECT 1 FROM fallback_friends WHERE status='accepted' AND
          ((fallback_request_identity(sender_id)=a AND fallback_request_identity(receiver_id)=b)
          OR (fallback_request_identity(sender_id)=b AND fallback_request_identity(receiver_id)=a)))
        OR EXISTS (SELECT 1 FROM fallback_compat_rows WHERE table_name='friends' AND fallback_friend_legacy_status('compat',row)='accepted' AND
          ((fallback_request_identity(row->>'sender_id')=a AND fallback_request_identity(row->>'receiver_id')=b)
          OR (fallback_request_identity(row->>'sender_id')=b AND fallback_request_identity(row->>'receiver_id')=a))) THEN RETURN TRUE; END IF;
      IF to_regclass('supabase_import.friends') IS NOT NULL THEN
        EXECUTE 'SELECT EXISTS(SELECT 1 FROM supabase_import.friends t WHERE fallback_friend_legacy_status(''imported'',to_jsonb(t))=''accepted'' AND
          ((fallback_request_identity(to_jsonb(t)->>''sender_id'')=$1 AND fallback_request_identity(to_jsonb(t)->>''receiver_id'')=$2)
          OR (fallback_request_identity(to_jsonb(t)->>''sender_id'')=$2 AND fallback_request_identity(to_jsonb(t)->>''receiver_id'')=$1)))'
          INTO trusted USING a,b;
        IF trusted THEN RETURN TRUE; END IF;
      END IF;
      SELECT COALESCE(bool_or(fallback_request_identity(follower_id)=a AND fallback_request_identity(following_id)=b),FALSE),
             COALESCE(bool_or(fallback_request_identity(follower_id)=b AND fallback_request_identity(following_id)=a),FALSE)
        INTO outgoing,incoming FROM fallback_follows
        WHERE fallback_request_identity(follower_id) IN (a,b) AND fallback_request_identity(following_id) IN (a,b);
      SELECT outgoing OR COALESCE(bool_or(fallback_request_identity(row->>'follower_id')=a AND fallback_request_identity(row->>'following_id')=b),FALSE),
             incoming OR COALESCE(bool_or(fallback_request_identity(row->>'follower_id')=b AND fallback_request_identity(row->>'following_id')=a),FALSE)
        INTO outgoing,incoming FROM fallback_compat_rows WHERE table_name='follows';
      IF outgoing AND incoming THEN RETURN TRUE; END IF;
      IF to_regclass('supabase_import.follows') IS NOT NULL THEN
        EXECUTE 'SELECT COALESCE(jsonb_agg(to_jsonb(t)),''[]''::jsonb) FROM supabase_import.follows t
          WHERE fallback_request_identity(to_jsonb(t)->>''follower_id'') IN ($1,$2)
            AND fallback_request_identity(to_jsonb(t)->>''following_id'') IN ($1,$2)'
          INTO edges USING a,b;
        SELECT outgoing OR COALESCE(bool_or(fallback_request_identity(e->>'follower_id')=a AND fallback_request_identity(e->>'following_id')=b),FALSE),
               incoming OR COALESCE(bool_or(fallback_request_identity(e->>'follower_id')=b AND fallback_request_identity(e->>'following_id')=a),FALSE)
          INTO outgoing,incoming FROM jsonb_array_elements(edges) e;
      END IF;
      RETURN outgoing AND incoming;
    END $$`)
  await pool.query(`CREATE OR REPLACE FUNCTION fallback_direct_message_request_state(raw_chat TEXT,raw_viewer TEXT)
    RETURNS JSONB LANGUAGE plpgsql STABLE AS $$
    DECLARE chat RECORD; request RECORD; viewer TEXT; other TEXT; original_sender TEXT; senders INTEGER;
      sender_ids TEXT[]; imported_ids TEXT[];
    BEGIN
      viewer:=fallback_request_identity(raw_viewer);
      SELECT * INTO chat FROM fallback_chats WHERE id::text=lower(raw_chat);
      IF NOT FOUND OR NOT EXISTS (SELECT 1 FROM unnest(chat.participants) p WHERE fallback_request_identity(p)=viewer)
        THEN RETURN jsonb_build_object('status','unavailable'); END IF;
      IF chat.is_group THEN RETURN jsonb_build_object('status','open'); END IF;
      SELECT fallback_request_identity(p) INTO other FROM unnest(chat.participants) p
        WHERE fallback_request_identity(p)<>viewer LIMIT 1;
      IF other IS NULL OR (SELECT count(DISTINCT fallback_request_identity(p)) FROM unnest(chat.participants) p)<>2
        THEN RETURN jsonb_build_object('status','unavailable'); END IF;
      IF EXISTS (SELECT 1 FROM fallback_user_blocks WHERE
        (fallback_request_identity(blocker_id)=viewer AND fallback_request_identity(blocked_id)=other)
        OR (fallback_request_identity(blocker_id)=other AND fallback_request_identity(blocked_id)=viewer))
        THEN RETURN jsonb_build_object('status','blocked'); END IF;
      SELECT * INTO request FROM fallback_message_requests WHERE chat_id=chat.id;
      IF request.status='rejected' THEN RETURN jsonb_build_object('status','rejected','senderId',request.sender_id,'recipientId',request.recipient_id); END IF;
      IF EXISTS (SELECT 1 FROM fallback_friends WHERE status='rejected'
        AND fallback_request_identity(sender_id)=viewer AND fallback_request_identity(receiver_id)=other)
        THEN RETURN jsonb_build_object('status','rejected'); END IF;
      IF request.status='accepted' OR fallback_request_pair_trusted(viewer,other)
        THEN RETURN jsonb_build_object('status','open'); END IF;
      IF request.status='pending' THEN
        RETURN jsonb_build_object('status',CASE WHEN request.sender_id=viewer THEN 'pending_outgoing' ELSE 'pending_incoming' END,
          'senderId',request.sender_id,'recipientId',request.recipient_id);
      END IF;
      -- Existing conversations with replies stay open. Unanswered introductions
      -- are limited even when created by an older Android version.
      SELECT COALESCE(array_agg(DISTINCT fallback_request_identity(sender_id)),ARRAY[]::TEXT[])
        INTO sender_ids FROM fallback_messages WHERE chat_id::text=chat.id::text;
      SELECT COALESCE(array_agg(DISTINCT fallback_request_identity(COALESCE(row->>'sender_id',row->>'senderId'))),ARRAY[]::TEXT[])
        INTO imported_ids FROM fallback_compat_rows WHERE table_name='messages'
        AND COALESCE(row->>'chat_id',row->>'chatId')=chat.id::text;
      sender_ids:=sender_ids||imported_ids;
      IF to_regclass('supabase_import.messages') IS NOT NULL THEN
        EXECUTE 'SELECT COALESCE(array_agg(DISTINCT fallback_request_identity(COALESCE(to_jsonb(t)->>''sender_id'',to_jsonb(t)->>''senderId''))),ARRAY[]::TEXT[])
          FROM supabase_import.messages t WHERE COALESCE(to_jsonb(t)->>''chat_id'',to_jsonb(t)->>''chatId'')=$1'
          INTO imported_ids USING chat.id::text;
        sender_ids:=sender_ids||imported_ids;
      END IF;
      SELECT count(DISTINCT s) INTO senders FROM unnest(sender_ids) s WHERE s IN (viewer,other);
      IF senders>1 THEN RETURN jsonb_build_object('status','open'); END IF;
      SELECT s INTO original_sender FROM unnest(sender_ids) s WHERE s IN (viewer,other) LIMIT 1;
      IF original_sender IS NULL THEN RETURN jsonb_build_object('status','new'); END IF;
      RETURN jsonb_build_object('status',CASE WHEN original_sender=viewer THEN 'pending_outgoing' ELSE 'pending_incoming' END,
        'senderId',original_sender,'recipientId',CASE WHEN original_sender=viewer THEN other ELSE viewer END);
    END $$`)
  await pool.query(`CREATE OR REPLACE FUNCTION fallback_guard_introductory_message()
    RETURNS trigger LANGUAGE plpgsql AS $$
    DECLARE state JSONB; actor TEXT; other TEXT; direct BOOLEAN;
    BEGIN
      SELECT NOT is_group INTO direct FROM fallback_chats WHERE id::text=lower(NEW.chat_id::text);
      IF direct IS NOT TRUE THEN RETURN NEW; END IF;
      -- Both senders and all transports share the same transaction lock.
      PERFORM pg_advisory_xact_lock(hashtextextended(lower(NEW.chat_id::text),0));
      actor:=fallback_request_identity(NEW.sender_id);
      SELECT fallback_request_identity(p) INTO other FROM fallback_chats,unnest(participants) p
        WHERE id::text=lower(NEW.chat_id::text) AND fallback_request_identity(p)<>actor LIMIT 1;
      IF other IS NOT NULL THEN PERFORM fallback_lock_friend_pair(actor,other); END IF;
      state:=fallback_direct_message_request_state(NEW.chat_id::text,actor);
      IF state->>'status'='blocked' THEN RAISE EXCEPTION 'CHAT_BLOCKED_BY_RECIPIENT'; END IF;
      IF state->>'status'='unavailable' THEN RAISE EXCEPTION 'CHAT_PARTICIPANT_REQUIRED'; END IF;
      IF EXISTS (SELECT 1 FROM fallback_messages WHERE id=NEW.id AND chat_id=NEW.chat_id
        AND fallback_request_identity(sender_id)=actor) THEN RETURN NEW; END IF;
      IF state->>'status'='rejected' THEN RAISE EXCEPTION 'MESSAGE_REQUEST_REJECTED'; END IF;
      IF state->>'status'='pending_outgoing' THEN RAISE EXCEPTION 'MESSAGE_REQUEST_PENDING'; END IF;
      IF state->>'status'='pending_incoming' THEN
        INSERT INTO fallback_message_requests(chat_id,sender_id,recipient_id,status)
          VALUES(NEW.chat_id::uuid,state->>'senderId',actor,'accepted')
          ON CONFLICT(chat_id) DO UPDATE SET status='accepted',updated_at=NOW();
        INSERT INTO fallback_friends(sender_id,receiver_id,status)
          VALUES(state->>'senderId',actor,'accepted')
          ON CONFLICT(sender_id,receiver_id) DO UPDATE SET status='accepted';
      ELSIF state->>'status'='new' THEN
        SELECT fallback_request_identity(p) INTO other FROM fallback_chats,unnest(participants) p
          WHERE id::text=lower(NEW.chat_id::text) AND fallback_request_identity(p)<>actor LIMIT 1;
        INSERT INTO fallback_message_requests(chat_id,sender_id,recipient_id,status,first_message_id)
          VALUES(NEW.chat_id::uuid,actor,other,'pending',NEW.id::text);
        IF NOT EXISTS(SELECT 1 FROM fallback_friends WHERE status='accepted' AND
          ((fallback_request_identity(sender_id)=actor AND fallback_request_identity(receiver_id)=other)
          OR (fallback_request_identity(sender_id)=other AND fallback_request_identity(receiver_id)=actor))) THEN
          INSERT INTO fallback_friends(sender_id,receiver_id,status) VALUES(actor,other,'pending')
            ON CONFLICT(sender_id,receiver_id) DO NOTHING;
        END IF;
      ELSE
        -- Once a pending conversation becomes trusted, it remains accepted.
        UPDATE fallback_message_requests SET status='accepted',updated_at=NOW()
          WHERE chat_id=NEW.chat_id::uuid AND status='pending';
      END IF;
      RETURN NEW;
    END $$`)
  await pool.query(`DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_trigger
    WHERE tgrelid='fallback_messages'::regclass AND tgname='trg_fallback_guard_introductory_message') THEN
      CREATE TRIGGER trg_fallback_guard_introductory_message BEFORE INSERT ON fallback_messages
      FOR EACH ROW EXECUTE FUNCTION fallback_guard_introductory_message(); END IF; END $$`)
}

function introPolicyError(error) {
  return INTRO_ERRORS.has(String(error?.message || '').trim()) ? error.message.trim() : null
}

// Preserve delivery of already persisted legacy friendship events. The new
// introduction guard creates the pending friendship; its alert is the first message.
async function publishAutomaticFriendRequest(pool, { senderId, chatId, messageId } = {}, publish) {
  if (!senderId || !chatId || !messageId) return 0
  const { rows } = await pool.query(
    `SELECT * FROM fallback_notifications
     WHERE type = 'friend_request' AND lower(actor_id) = lower($1)
       AND extra_data->>'chat_id' = $2 AND extra_data->>'message_id' = $3`,
    [senderId, chatId, messageId],
  )
  const send = publish || require('./realtime-publish').publishRealtimeEvent
  await Promise.all(rows.map((row) => {
    // PostgreSQL timestamps are Dates. Convert them before the storage URL
    // canonicalizer walks the envelope, so Android receives ISO strings.
    const notification = JSON.parse(JSON.stringify(row))
    return send(`user:${notification.recipient_id}`, {
      event: 'notification', notification, extraData: notification.extra_data || {},
    })
  }))
  return rows.length
}


function registerDirectMessageRequestRoutes(app, { pool, authRequired, getCurrentUser, userIdentity, privateChatAccessContext }) {
  app.get('/v1/data/messages/requests/eligibility/:userId',authRequired,async(req,res,next)=>{
    try {
      const user=await getCurrentUser(pool,req);if(!user)return res.status(404).json({error:'USER_NOT_FOUND'})
      const id=String(req.params.userId||'').trim().toLowerCase()
      if(!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(id))return res.status(400).json({error:'USER_ID_REQUIRED'})
      const identity=userIdentity(user)
      const {rows}=await pool.query(`SELECT id::text FROM fallback_users WHERE lower(id::text)=$1 OR lower(COALESCE(supabase_user_id::text,''))=$1 LIMIT 1`,[id])
      if(!rows.length)return res.status(404).json({error:'USER_NOT_FOUND'})
      const {rows:state}=await pool.query(`SELECT CASE WHEN EXISTS(SELECT 1 FROM fallback_user_blocks WHERE
        (fallback_request_identity(blocker_id)=fallback_request_identity($1) AND fallback_request_identity(blocked_id)=fallback_request_identity($2))
        OR (fallback_request_identity(blocker_id)=fallback_request_identity($2) AND fallback_request_identity(blocked_id)=fallback_request_identity($1)))
        THEN 'blocked' WHEN fallback_request_pair_trusted(fallback_request_identity($1),fallback_request_identity($2)) THEN 'open' ELSE 'new' END AS status`,[identity.displayUserId,id])
      res.json({status:state[0].status})
    }catch(error){next(error)}
  })
  app.get('/v1/data/messages/chats/:chatId/request',authRequired,async(req,res,next)=>{
    try {
      const user=await getCurrentUser(pool,req);if(!user)return res.status(404).json({error:'USER_NOT_FOUND'})
      const identity=userIdentity(user),chatId=String(req.params.chatId||'').trim().toLowerCase()
      const access=await privateChatAccessContext(pool,chatId,identity)
      if(!access.chat||!access.hasAccess)return res.status(404).json({error:'CHAT_NOT_FOUND'})
      const {rows}=await pool.query('SELECT fallback_direct_message_request_state($1,$2) AS state',[chatId,identity.displayUserId])
      res.json(rows[0].state)
    }catch(error){next(error)}
  })
  app.post('/v1/data/messages/chats/:chatId/request',authRequired,async(req,res,next)=>{
    let client
    try {
      const user=await getCurrentUser(pool,req);if(!user)return res.status(404).json({error:'USER_NOT_FOUND'})
      const action=req.body?.action
      if(!['accept','reject'].includes(action))return res.status(400).json({error:'REQUEST_ACTION_REQUIRED'})
      const identity=userIdentity(user),chatId=String(req.params.chatId||'').trim().toLowerCase()
      client=await pool.connect();await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[chatId])
      const access=await privateChatAccessContext(client,chatId,identity)
      if(!access.chat||!access.hasAccess){await client.query('ROLLBACK');return res.status(404).json({error:'CHAT_NOT_FOUND'})}
      await client.query(`SELECT fallback_lock_friend_pair($2,p) FROM fallback_chats,unnest(participants) p
        WHERE id::text=$1 AND fallback_friend_identity(p)<>fallback_friend_identity($2) LIMIT 1`,[chatId,identity.localUserId])
      const {rows}=await client.query('SELECT fallback_direct_message_request_state($1,$2) AS state',[chatId,identity.displayUserId])
      const state=rows[0].state
      const {rows:who}=await client.query('SELECT fallback_request_identity($1) AS actor',[identity.displayUserId])
      const actor=who[0].actor
      if(state.status!=='pending_incoming') {
        // Only the recipient can repeat a completed decision; the sender cannot accept themselves.
        const {rows:existing}=await client.query('SELECT status,recipient_id FROM fallback_message_requests WHERE chat_id::text=$1',[chatId])
        const desired=action==='accept'?'accepted':'rejected'
        if(existing[0]?.recipient_id===actor&&existing[0]?.status===desired){await client.query('COMMIT');return res.json({status:desired==='accepted'?'open':'rejected'})}
        await client.query('ROLLBACK');return res.status(409).json({error:'MESSAGE_REQUEST_DECISION_NOT_ALLOWED'})
      }
      const status=action==='accept'?'accepted':'rejected'
      await client.query(`INSERT INTO fallback_message_requests(chat_id,sender_id,recipient_id,status)
        VALUES($1::uuid,$2,$3,$4) ON CONFLICT(chat_id) DO UPDATE SET status=EXCLUDED.status,updated_at=NOW()`,[chatId,state.senderId,actor,status])
      if(status==='accepted') await client.query(`INSERT INTO fallback_friends(sender_id,receiver_id,status) VALUES($1,$2,'accepted')
        ON CONFLICT(sender_id,receiver_id) DO UPDATE SET status='accepted'`,[state.senderId,actor])
      else await client.query(`UPDATE fallback_friends SET status='rejected' WHERE status='pending'
        AND fallback_request_identity(sender_id)=$1 AND fallback_request_identity(receiver_id)=$2`,[state.senderId,actor])
      await client.query('COMMIT')
      res.json({status:status==='accepted'?'open':'rejected'})
    }catch(error){if(client)await client.query('ROLLBACK').catch(()=>{});next(error)}finally{client?.release()}
  })
}

module.exports={ensureDirectMessageRequestPolicy,introPolicyError,publishAutomaticFriendRequest,registerDirectMessageRequestRoutes}
