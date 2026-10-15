const crypto = require('crypto')
const { redactDeletedMessage, refreshChatSummary } = require('./message-deletion-policy')
const { publishChatMessageEvent } = require('./realtime-publish')

const validScopes = new Set(['dm', 'group', 'community'])
const validContentTypes = new Set([
  'text', 'image', 'video', 'audio', 'file', 'gif', 'sticker', 'contact', 'location',
])

function publicError(message, status = 400) {
  const error = new Error(message)
  error.status = status
  return error
}

function cleanText(value, maxLength) {
  return String(value || '').trim().slice(0, maxLength)
}

function cleanUuid(value) {
  const clean = String(value || '').trim().toLowerCase()
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(clean)
    ? clean
    : ''
}

function normalizeScheduledInput(body, now = Date.now()) {
  const scopeType = cleanText(body.p_scope_type ?? body.scope_type, 20).toLowerCase()
  const targetId = cleanUuid(body.p_target_id ?? body.target_id)
  const content = cleanText(body.p_content ?? body.content, 4000)
  const contentType = cleanText(body.p_content_type ?? body.content_type ?? 'text', 40).toLowerCase() || 'text'
  const mediaUrl = cleanText(body.p_media_url ?? body.media_url, 1500) || null
  const scheduledForRaw = body.p_scheduled_for ?? body.scheduled_for
  const scheduledForMs = new Date(scheduledForRaw || '').getTime()
  const translateTo = cleanText(body.p_translate_to ?? body.translate_to, 16).toLowerCase() || null
  const selfDestructRaw = Number(body.p_self_destruct_seconds ?? body.self_destruct_seconds ?? 0)
  const selfDestructSeconds = Number.isFinite(selfDestructRaw) && selfDestructRaw > 0
    ? Math.min(Math.trunc(selfDestructRaw), 30 * 24 * 60 * 60)
    : null
  const suppliedIdempotencyKey = cleanText(body.p_idempotency_key ?? body.idempotency_key, 120)

  if (!validScopes.has(scopeType)) throw publicError('INVALID_SCOPE')
  if (!targetId) throw publicError('TARGET_REQUIRED')
  if (!content && !mediaUrl) throw publicError('CONTENT_REQUIRED')
  if (!validContentTypes.has(contentType)) throw publicError('INVALID_CONTENT_TYPE')
  if (!Number.isFinite(scheduledForMs)) throw publicError('INVALID_SCHEDULE_TIME')
  if (scheduledForMs < now + 5_000) throw publicError('SCHEDULE_TIME_MUST_BE_IN_FUTURE')
  if (scheduledForMs > now + 366 * 24 * 60 * 60 * 1000) throw publicError('SCHEDULE_TIME_TOO_FAR')
  if (mediaUrl && !/^https:\/\//i.test(mediaUrl) && !mediaUrl.startsWith('/storage/v1/object/public/')) {
    throw publicError('INVALID_MEDIA_URL')
  }
  if (translateTo) throw publicError('TRANSLATION_PROVIDER_DISABLED', 409)

  return {
    scopeType,
    targetId,
    content,
    contentType,
    mediaUrl,
    scheduledFor: new Date(scheduledForMs).toISOString(),
    translateTo,
    selfDestructSeconds,
    idempotencyKey: suppliedIdempotencyKey || crypto.randomUUID(),
  }
}

function retryDelaySeconds(attemptCount) {
  return Math.min(15 * 60, Math.max(5, 5 * (2 ** Math.max(0, attemptCount - 1))))
}

async function ensureScheduledMessageTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_scheduled_message_jobs (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      owner_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      sender_id TEXT NOT NULL,
      scope_type TEXT NOT NULL CHECK (scope_type IN ('dm', 'group', 'community')),
      target_id UUID NOT NULL,
      content TEXT NOT NULL DEFAULT '',
      content_type TEXT NOT NULL DEFAULT 'text',
      media_url TEXT,
      translate_to TEXT,
      self_destruct_seconds INTEGER,
      scheduled_for TIMESTAMPTZ NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'processing', 'sent', 'cancelled', 'failed')),
      error_message TEXT,
      attempt_count INTEGER NOT NULL DEFAULT 0,
      max_attempts INTEGER NOT NULL DEFAULT 6,
      next_attempt_at TIMESTAMPTZ NOT NULL,
      locked_at TIMESTAMPTZ,
      locked_by TEXT,
      idempotency_key TEXT NOT NULL,
      message_id UUID NOT NULL DEFAULT gen_random_uuid(),
      sent_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(owner_id, idempotency_key),
      UNIQUE(message_id)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_scheduled_jobs_due
    ON fallback_scheduled_message_jobs(status, next_attempt_at, scheduled_for)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_scheduled_jobs_owner_target
    ON fallback_scheduled_message_jobs(owner_id, scope_type, target_id, scheduled_for DESC)
  `)
}

async function loadOwnerIdentity(queryable, ownerId) {
  const { rows } = await queryable.query(
    `SELECT id::text AS local_id,
            NULLIF(supabase_user_id::text, '') AS public_id
     FROM fallback_users
     WHERE id = $1::uuid
     LIMIT 1`,
    [ownerId],
  )
  const row = rows[0]
  if (!row) throw publicError('USER_NOT_FOUND', 404)
  const aliases = [...new Set([row.local_id, row.public_id].filter(Boolean).map((value) => value.toLowerCase()))]
  return {
    ownerId: row.local_id,
    senderId: row.public_id || row.local_id,
    aliases,
  }
}

async function requireTargetAccess(queryable, identity, scopeType, targetId) {
  if (scopeType === 'dm') {
    const { rows } = await queryable.query(
      `SELECT c.id::text
       FROM fallback_chats c
       WHERE c.id = $1::uuid
         AND (
           EXISTS (
             SELECT 1 FROM fallback_chat_participants cp
             WHERE cp.chat_id = c.id AND lower(cp.user_id) = ANY($2::text[])
           )
           OR EXISTS (
             SELECT 1 FROM unnest(c.participants) participant
             WHERE lower(participant) = ANY($2::text[])
           )
         )
       LIMIT 1`,
      [targetId, identity.aliases],
    )
    if (!rows[0]) throw publicError('CHAT_ACCESS_DENIED', 403)
    return
  }

  const { rows } = await queryable.query(
    `SELECT room.id::text
     FROM fallback_collection_rooms room
     WHERE room.id = $1::uuid
       AND room.room_kind = $2
       AND room.is_deleted = FALSE
       AND (
         lower(room.creator_id) = ANY($3::text[])
         OR EXISTS (
           SELECT 1 FROM fallback_collection_members member
           WHERE member.room_kind = room.room_kind
             AND lower(member.room_id) = lower(room.id::text)
             AND lower(member.user_id) = ANY($3::text[])
             AND member.is_deleted = FALSE
         )
       )
     LIMIT 1`,
    [targetId, scopeType, identity.aliases],
  )
  if (!rows[0]) throw publicError(`${scopeType.toUpperCase()}_ACCESS_DENIED`, 403)
}

function mapScheduledJob(row) {
  return {
    id: row.id,
    owner_id: row.owner_id,
    scope_type: row.scope_type,
    target_id: row.target_id,
    content: row.content,
    content_type: row.content_type,
    media_url: row.media_url,
    translate_to: row.translate_to,
    self_destruct_seconds: row.self_destruct_seconds,
    scheduled_for: row.scheduled_for,
    status: row.status,
    error_message: row.error_message,
    attempt_count: row.attempt_count,
    created_at: row.created_at,
    updated_at: row.updated_at,
  }
}

async function scheduleMessageJob(pool, auth, body) {
  const ownerId = cleanUuid(auth?.sub)
  if (!ownerId) throw publicError('AUTH_REQUIRED', 401)
  const input = normalizeScheduledInput(body)
  const identity = await loadOwnerIdentity(pool, ownerId)
  await requireTargetAccess(pool, identity, input.scopeType, input.targetId)
  const { rows } = await pool.query(
    `INSERT INTO fallback_scheduled_message_jobs(
       owner_id, sender_id, scope_type, target_id, content, content_type, media_url,
       translate_to, self_destruct_seconds, scheduled_for, next_attempt_at, idempotency_key
     )
     VALUES ($1::uuid, $2, $3, $4::uuid, $5, $6, $7, $8, $9, $10::timestamptz, $10::timestamptz, $11)
     ON CONFLICT (owner_id, idempotency_key) DO UPDATE SET
       updated_at = fallback_scheduled_message_jobs.updated_at
     RETURNING *`,
    [
      identity.ownerId, identity.senderId, input.scopeType, input.targetId, input.content,
      input.contentType, input.mediaUrl, input.translateTo, input.selfDestructSeconds,
      input.scheduledFor, input.idempotencyKey,
    ],
  )
  return mapScheduledJob(rows[0])
}

async function listScheduledMessageJobs(pool, auth, body) {
  const ownerId = cleanUuid(auth?.sub)
  if (!ownerId) throw publicError('AUTH_REQUIRED', 401)
  const scopeType = cleanText(body.p_scope_type ?? body.scope_type, 20).toLowerCase()
  const targetId = cleanUuid(body.p_target_id ?? body.target_id)
  if (scopeType && !validScopes.has(scopeType)) throw publicError('INVALID_SCOPE')
  if ((scopeType && !targetId) || (!scopeType && targetId)) throw publicError('SCOPE_AND_TARGET_REQUIRED')
  const { rows } = await pool.query(
    `SELECT * FROM fallback_scheduled_message_jobs
     WHERE owner_id = $1::uuid
       AND ($2::text IS NULL OR scope_type = $2)
       AND ($3::uuid IS NULL OR target_id = $3::uuid)
       AND status IN ('pending', 'processing', 'failed')
     ORDER BY scheduled_for ASC
     LIMIT 100`,
    [ownerId, scopeType || null, targetId || null],
  )
  return rows.map(mapScheduledJob)
}

async function cancelScheduledMessageJob(pool, auth, body) {
  const ownerId = cleanUuid(auth?.sub)
  const jobId = cleanUuid(body.p_job_id ?? body.job_id ?? body.id)
  if (!ownerId) throw publicError('AUTH_REQUIRED', 401)
  if (!jobId) throw publicError('JOB_REQUIRED')
  const { rows } = await pool.query(
    `UPDATE fallback_scheduled_message_jobs
     SET status = 'cancelled', locked_at = NULL, locked_by = NULL, updated_at = NOW()
     WHERE id = $1::uuid AND owner_id = $2::uuid AND status IN ('pending', 'failed')
     RETURNING *`,
    [jobId, ownerId],
  )
  if (!rows[0]) throw publicError('SCHEDULED_JOB_NOT_CANCELLABLE', 409)
  return mapScheduledJob(rows[0])
}

async function recipientIdsForJob(queryable, job, aliases) {
  if (job.scope_type === 'dm') {
    const { rows } = await queryable.query(
      `SELECT DISTINCT lower(user_id) AS user_id
       FROM fallback_chat_participants
       WHERE chat_id = $1::uuid AND NOT (lower(user_id) = ANY($2::text[]))`,
      [job.target_id, aliases],
    )
    return rows.map((row) => row.user_id).filter(Boolean)
  }
  const { rows } = await queryable.query(
    `SELECT DISTINCT lower(user_id) AS user_id
     FROM fallback_collection_members
     WHERE room_kind = $1 AND lower(room_id) = lower($2)
       AND is_deleted = FALSE AND NOT (lower(user_id) = ANY($3::text[]))`,
    [job.scope_type, job.target_id, aliases],
  )
  return rows.map((row) => row.user_id).filter(Boolean)
}

async function deliverClaimedJob(client, job) {
  const identity = await loadOwnerIdentity(client, job.owner_id)
  await requireTargetAccess(client, identity, job.scope_type, job.target_id)
  let message
  if (job.scope_type === 'dm') {
    const { rows } = await client.query(
      `INSERT INTO fallback_messages(
         id, chat_id, sender_id, content, content_type, media_url, status
       ) VALUES ($1::uuid, $2, $3, $4, $5, $6, 'sent')
       ON CONFLICT (id) DO NOTHING
       RETURNING id::text, chat_id, sender_id, content, content_type, media_url,
                 status, created_at, updated_at`,
      [job.message_id, job.target_id, identity.senderId, job.content, job.content_type, job.media_url],
    )
    if (!rows[0]) {
      const existing = await client.query(
        `SELECT id::text, chat_id, sender_id, content, content_type, media_url,
                status, created_at, updated_at, deleted_for_all
         FROM fallback_messages WHERE id = $1::uuid LIMIT 1`,
        [job.message_id],
      )
      message = existing.rows[0]
    } else {
      message = rows[0]
    }
    await refreshChatSummary(client, job.target_id)
  } else {
    const { rows } = await client.query(
      `INSERT INTO fallback_room_messages(
         id, room_kind, room_id, sender_id, content, content_type, media_url, status
       ) VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, 'sent')
       ON CONFLICT (id) DO NOTHING
       RETURNING id::text, room_id,
         CASE WHEN room_kind = 'group' THEN room_id ELSE NULL END AS group_id,
         CASE WHEN room_kind = 'community' THEN room_id ELSE NULL END AS community_id,
         sender_id, content, content_type, media_url, status, created_at, updated_at`,
      [job.message_id, job.scope_type, job.target_id, identity.senderId, job.content, job.content_type, job.media_url],
    )
    if (!rows[0]) {
      const existing = await client.query(
        `SELECT id::text, room_id, sender_id, content, content_type, media_url,
                status, created_at, updated_at, deleted_for_all
         FROM fallback_room_messages WHERE id = $1::uuid LIMIT 1`,
        [job.message_id],
      )
      message = existing.rows[0]
    } else {
      message = rows[0]
    }
  }
  if (!message) throw new Error('SCHEDULED_MESSAGE_INSERT_FAILED')
  const recipients = await recipientIdsForJob(client, job, identity.aliases)
  await client.query(
    `UPDATE fallback_scheduled_message_jobs
     SET status = 'sent', sent_at = NOW(), error_message = NULL,
         locked_at = NULL, locked_by = NULL, updated_at = NOW()
     WHERE id = $1::uuid`,
    [job.id],
  )
  return { message: redactDeletedMessage(message), recipients }
}

async function processDueScheduledMessageJobs(pool, options = {}) {
  const workerId = cleanText(options.workerId || `${process.pid}-${crypto.randomUUID()}`, 120)
  const limit = Math.max(1, Math.min(Number(options.limit || 20), 100))
  const { rows: candidates } = await pool.query(
    `SELECT id::text
     FROM fallback_scheduled_message_jobs
     WHERE (
       (status = 'pending' AND next_attempt_at <= NOW())
       OR (status = 'processing' AND locked_at < NOW() - INTERVAL '2 minutes')
     )
       AND scheduled_for <= NOW()
     ORDER BY scheduled_for ASC
     LIMIT $1`,
    [limit],
  )
  let sent = 0
  for (const candidate of candidates) {
    const client = await pool.connect()
    let claimedJob = null
    try {
      await client.query('BEGIN')
      const { rows } = await client.query(
        `UPDATE fallback_scheduled_message_jobs
         SET status = 'processing', locked_at = NOW(), locked_by = $2,
             attempt_count = attempt_count + 1, updated_at = NOW()
         WHERE id = $1::uuid
           AND (
             (status = 'pending' AND next_attempt_at <= NOW())
             OR (status = 'processing' AND locked_at < NOW() - INTERVAL '2 minutes')
           )
         RETURNING *`,
        [candidate.id, workerId],
      )
      claimedJob = rows[0]
      if (!claimedJob) {
        await client.query('ROLLBACK')
        continue
      }
      const delivered = await deliverClaimedJob(client, claimedJob)
      await client.query('COMMIT')
      sent += 1
      const channel = claimedJob.scope_type === 'dm'
        ? claimedJob.target_id
        : `room:${claimedJob.scope_type}:${claimedJob.target_id}`
      void publishChatMessageEvent({
        pool,
        chatId: channel,
        message: delivered.message,
        recipientIds: delivered.recipients,
      })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      if (claimedJob) {
        const attempts = Number(claimedJob.attempt_count || 1)
        const terminal = attempts >= Number(claimedJob.max_attempts || 6)
        await pool.query(
          `UPDATE fallback_scheduled_message_jobs
           SET status = $2,
               error_message = $3,
               next_attempt_at = NOW() + ($4::integer * INTERVAL '1 second'),
               locked_at = NULL, locked_by = NULL, updated_at = NOW()
           WHERE id = $1::uuid AND status = 'processing' AND locked_by = $5`,
          [
            claimedJob.id,
            terminal ? 'failed' : 'pending',
            cleanText(error?.message || 'SCHEDULED_DELIVERY_FAILED', 240),
            retryDelaySeconds(attempts),
            workerId,
          ],
        ).catch(() => {})
      }
    } finally {
      client.release()
    }
  }
  return sent
}

module.exports = {
  cancelScheduledMessageJob,
  ensureScheduledMessageTables,
  listScheduledMessageJobs,
  normalizeScheduledInput,
  processDueScheduledMessageJobs,
  retryDelaySeconds,
  scheduleMessageJob,
}
