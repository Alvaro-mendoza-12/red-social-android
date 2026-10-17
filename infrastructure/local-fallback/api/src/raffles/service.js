'use strict'

const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const { publishRealtimeEvent } = require('../realtime-publish')

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const INTERVAL_MS = 6000
const MAX_PARTICIPANTS = 1000
const clean = (v) => String(v ?? '').trim()
const lower = (v) => clean(v).toLowerCase()
const hash = (v) => crypto.createHash('sha256').update(JSON.stringify(v)).digest('hex')
class RaffleError extends Error {
  constructor(code, status = 400) { super(code); this.code = code; this.status = status }
}
function requireUuid(value) {
  if (!UUID.test(clean(value))) throw new RaffleError('INVALID_RAFFLE_ID')
  return lower(value)
}
function validateTarget(kind, roomId) {
  if (!['group', 'community'].includes(kind)) throw new RaffleError('INVALID_RAFFLE_KIND')
  return { kind, roomId: requireUuid(roomId) }
}
function activeMember(member) {
  return member.is_deleted !== true && member.deleted !== true &&
    !['left', 'removed', 'deleted', 'banned', 'blocked'].includes(lower(member.status || member.member_status))
}
async function ensureRaffleTables(pool) {
  await pool.query(fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8'))
}
function mapRaffle(row) {
  return { id: row.id, kind: row.room_kind, roomId: row.room_id, name: row.name,
    description: row.description, creatorId: row.creator_id, creatorName: row.creator_name,
    createdAt: row.created_at, updatedAt: row.updated_at, status: row.status, version: row.version,
    participantCount: row.participant_count, roundCount: row.round_count }
}
function mapParticipant(row) {
  return { id: row.id, kind: row.kind, userId: row.user_id, name: row.name, avatarUrl: row.avatar_url || '' }
}

function createRaffleService({ pool, getCurrentUser, userIdentity, loadCollectionRoomAccess,
  memberUserId, rowMatchesAny, publicProfilesByIds, isSuspended = async () => false,
  ensureDefaultCommunityChannel = async () => null,
  publishRoomCard = async () => {}, publish = publishRealtimeEvent }) {
  async function access(db, user, kind, roomId, manage = false) {
    validateTarget(kind, roomId)
    const roomAccess = await loadCollectionRoomAccess(db, user, kind, roomId)
    if (!roomAccess) throw new RaffleError('RAFFLE_ACCESS_DENIED', 403)
    const identity = userIdentity(user)
    const owner = rowMatchesAny(roomAccess.room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
    const members = roomAccess.roomMembers.filter(activeMember)
    const member = members.find((row) => identity.ids.has(memberUserId(row)))
    const revoked = roomAccess.roomMembers.some((row) => identity.ids.has(memberUserId(row)) && !activeMember(row))
    if (revoked || (!owner && !member)) throw new RaffleError('RAFFLE_ACCESS_DENIED', 403)
    const canManage = owner || ['owner', 'creator', 'admin'].includes(lower(member?.role))
    if (manage && !canManage) throw new RaffleError('RAFFLE_ADMIN_REQUIRED', 403)
    return { identity, members, owner, canManage, canCreate: owner, room: roomAccess.room }
  }
  // Group members run their own draws; administrators retain moderation of all draws.
  // Active membership is checked before this policy, including for the creator.
  function canManageRaffle(ctx, raffle) {
    return ctx.canManage || (raffle.room_kind === 'group' &&
      (raffle.creator_local_id === ctx.identity.localUserId || ctx.identity.ids.has(raffle.creator_id)))
  }
  async function eligible(db, user, kind, roomId) {
    const ctx = await access(db, user, kind, roomId)
    const ids = [...new Set(ctx.members.map(memberUserId).filter(Boolean))]
    const ownerId = lower(ctx.room.creator_id || ctx.room.owner_id || ctx.room.user_id)
    if (ownerId && !ids.includes(ownerId)) ids.push(ownerId)
    const profiles = await publicProfilesByIds(db, ids)
    const memberById = new Map(ctx.members.map((row) => [memberUserId(row), row]))
    const byId = new Map()
    for (const profile of profiles) {
      for (const key of ['id', 'user_id', 'userId', 'profile_id', 'profileId', 'supabase_user_id', 'supabaseUserId', 'local_user_id']) if (profile[key]) byId.set(lower(profile[key]), profile)
    }
    const participants = ids.filter((id) => {
      const profile = byId.get(id)
      return profile && profile.is_deleted !== true && profile.deleted !== true && !profile.deleted_at
    }).map((id) => {
      const profile = byId.get(id)
      const member = memberById.get(id) || {}
      const canonicalId = lower(profile.supabase_user_id || profile.id || id)
      return { id: canonicalId, kind: 'REGISTERED_MEMBER', userId: canonicalId,
        name: clean(profile.display_name || profile.full_name || profile.username || member.user_name || member.name || 'Usuario').slice(0, 80),
        avatarUrl: clean(profile.avatar_url || member.user_avatar_url || '').slice(0, 1000) }
    })
    return { ctx, participants: [...new Map(participants.map((p) => [p.id, p])).values()] }
  }
  async function transaction(task) {
    const db = await pool.connect()
    try { await db.query('BEGIN'); const result = await task(db); await db.query('COMMIT'); return result }
    catch (error) { await db.query('ROLLBACK'); throw error }
    finally { db.release() }
  }
  async function readRound(db, row) {
    if (!row) return null
    const { rows } = await db.query('SELECT * FROM fallback_raffle_results WHERE round_id=$1 ORDER BY position', [row.id])
    const participants = row.participants
    const participantMap = new Map(participants.map((p) => [p.id, p]))
    return { id: row.id, number: row.number, status: row.status, participants,
      initiatedBy: row.initiated_by, automatic: row.automatic, nextDrawAt: row.next_draw_at,
      startedAt: row.started_at, finishedAt: row.finished_at,
      results: rows.map((r) => ({ position: r.position, participant: participantMap.get(r.participant_id), selectedAt: r.selected_at })) }
  }
  async function detail(db, user, kind, roomId, id, beforeRound = 2147483647) {
    const ctx = await access(db, user, kind, roomId)
    const { rows } = await db.query('SELECT * FROM fallback_raffles WHERE id=$1 AND room_kind=$2 AND room_id=$3', [id, kind, roomId])
    const row = rows[0]
    if (!row) throw new RaffleError('RAFFLE_NOT_FOUND', 404)
    const [latest, history] = await Promise.all([
      db.query('SELECT * FROM fallback_raffle_rounds WHERE raffle_id=$1 ORDER BY number DESC LIMIT 1', [id]),
      db.query('SELECT * FROM fallback_raffle_rounds WHERE raffle_id=$1 AND number<$2 ORDER BY number DESC LIMIT 11', [id, beforeRound]),
    ])
    // Retained participant rows support historical result foreign keys. They are
    // not the current roster and must not accumulate in every detail response.
    const participants = latest.rows[0]?.participants ||
      (await db.query('SELECT * FROM fallback_raffle_participants WHERE raffle_id=$1 ORDER BY ordinal', [id])).rows.map(mapParticipant)
    return { raffle: mapRaffle(row), canManage: canManageRaffle(ctx, row), participants,
      currentRound: await readRound(db, latest.rows[0]),
      rounds: await Promise.all(history.rows.slice(0, 10).map((r) => readRound(db, r))),
      nextRound: history.rows.length > 10 ? history.rows[9].number : null }
  }
  async function create(user, kind, roomId, body) {
    const key = requireUuid(body.requestId)
    if (typeof body.name !== 'string' || (body.description != null && typeof body.description !== 'string')) throw new RaffleError('INVALID_RAFFLE_NAME')
    const name = clean(body.name), description = clean(body.description)
    if (!name || name.length > 120 || description.length > 500) throw new RaffleError('INVALID_RAFFLE_NAME')
    if (!Array.isArray(body.memberIds) || !Array.isArray(body.manualNames || [])) throw new RaffleError('INVALID_PARTICIPANTS')
    if (body.memberIds.some((v) => typeof v !== 'string') || (body.manualNames || []).some((v) => typeof v !== 'string')) throw new RaffleError('INVALID_PARTICIPANTS')
    if (body.memberIds.length + (body.manualNames || []).length > MAX_PARTICIPANTS) throw new RaffleError('PARTICIPANT_LIMIT')
    const memberIds = [...new Set(body.memberIds.map(requireUuid))].sort()
    const manualNames = (body.manualNames || []).map((v) => clean(v).normalize('NFKC'))
    if (manualNames.some((n) => !n || n.length > 80 || /[\x00-\x1f]/.test(n))) throw new RaffleError('INVALID_MANUAL_NAME')
    if (new Set(manualNames.map(lower)).size !== manualNames.length) throw new RaffleError('DUPLICATE_MANUAL_NAME')
    if (memberIds.length + manualNames.length < 1 || memberIds.length + manualNames.length > MAX_PARTICIPANTS) throw new RaffleError('PARTICIPANT_LIMIT', 400)
    const start = body.start === true, automatic = body.automatic === true
    const creationHash = hash({ kind, roomId, name, description, memberIds, manualNames, start, automatic })
    const result = await transaction(async (db) => {
      const ctx = await access(db, user, kind, roomId)
      if (!ctx.canCreate) throw new RaffleError('RAFFLE_ADMIN_REQUIRED', 403)
      await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`raffle-create:${ctx.identity.localUserId}`])
      const existing = (await db.query('SELECT * FROM fallback_raffles WHERE creator_local_id=$1 AND creation_key=$2', [ctx.identity.localUserId, key])).rows[0]
      if (existing) {
        if (existing.creation_hash !== creationHash) throw new RaffleError('IDEMPOTENCY_CONFLICT', 409)
        return { id: existing.id }
      }
      // Keep accidental repeated taps with new keys bounded; an idempotent retry above is always allowed.
      const count = (await db.query("SELECT count(*)::int AS count FROM fallback_raffles WHERE creator_local_id=$1 AND created_at>NOW()-INTERVAL '1 hour'", [ctx.identity.localUserId])).rows[0].count
      if (count >= 30) throw new RaffleError('RAFFLE_CREATE_LIMIT', 429)
      const { participants: available } = await eligible(db, user, kind, roomId)
      const byId = new Map(available.map((p) => [p.userId, p]))
      if (memberIds.some((id) => !byId.has(id))) throw new RaffleError('MEMBERS_CHANGED', 409)
      const snapshots = [...memberIds.map((id) => byId.get(id)), ...manualNames.map((name) => ({ kind: 'MANUAL_PARTICIPANT', userId: null, name, avatarUrl: '' }))]
        .map((p) => ({ ...p, id: crypto.randomUUID() }))
      const id = crypto.randomUUID(), messageId = crypto.randomUUID()
      await db.query(`INSERT INTO fallback_raffles(id,room_kind,room_id,name,description,creator_id,creator_local_id,creator_name,
        creation_key,creation_hash,status,participant_count,message_id) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,
      [id, kind, roomId, name, description, ctx.identity.displayUserId, ctx.identity.localUserId,
        clean(user.display_name || 'Usuario').slice(0, 80), key, creationHash, start ? 'running' : 'prepared', snapshots.length, messageId])
      for (let i = 0; i < snapshots.length; i++) {
        const p = snapshots[i]
        await db.query('INSERT INTO fallback_raffle_participants(id,raffle_id,kind,user_id,name,avatar_url,ordinal) VALUES($1,$2,$3,$4,$5,$6,$7)',
          [p.id, id, p.kind, p.userId, p.name, p.avatarUrl, i])
      }
      const channel = kind === 'community' ? await ensureDefaultCommunityChannel(db, roomId, ctx.identity.displayUserId) : null
      await db.query(`INSERT INTO fallback_room_messages(id,room_kind,room_id,channel_id,sender_id,content,content_type,media_url)
        VALUES($1,$2,$3,$4,$5,$6,'raffle',$7)`, [messageId, kind, roomId, channel?.id || null, ctx.identity.displayUserId,
        `${name}\n${snapshots.length} participantes`, id])
      if (start) await beginRound(db, id, ctx.identity, snapshots, automatic)
      return { id }
    })
    await notify(kind, roomId, result.id, 'created')
    return detail(pool, user, kind, roomId, result.id)
  }
  async function beginRound(db, id, identity, participants, automatic) {
    const row = (await db.query('SELECT round_count FROM fallback_raffles WHERE id=$1', [id])).rows[0]
    await db.query(`INSERT INTO fallback_raffle_rounds(id,raffle_id,number,status,participants,initiated_by,initiator_local_id,automatic,next_draw_at)
      VALUES($1,$2,$3,'running',$4,$5,$6,$7,NOW()+INTERVAL '1 second')`,
    [crypto.randomUUID(), id, row.round_count + 1, JSON.stringify(participants), identity.displayUserId, identity.localUserId, automatic])
    await db.query("UPDATE fallback_raffles SET status='running',round_count=round_count+1 WHERE id=$1", [id])
  }
  async function notify(kind, roomId, raffleId, action) {
    // Invalidations only: a recently expelled observer cannot receive private winners/names.
    await publish(`raffles:${kind}:${roomId}`, { event: 'raffle_changed', raffleId, action })
    if (['created', 'completed', 'cancelled', 'new-round'].includes(action)) {
      const raffle = (await pool.query('SELECT * FROM fallback_raffles WHERE id=$1', [raffleId])).rows[0]
      if (!raffle) return
      const latest = (await pool.query('SELECT initiator_local_id FROM fallback_raffle_rounds WHERE raffle_id=$1 ORDER BY number DESC LIMIT 1', [raffleId])).rows[0]
      const actor = await getCurrentUser(pool, { auth: { sub: latest?.initiator_local_id || raffle.creator_local_id } })
      const ctx = actor ? await loadCollectionRoomAccess(pool, actor, kind, roomId) : null
      if (!ctx) return
      const message = (await pool.query('SELECT * FROM fallback_room_messages WHERE id=$1', [raffle.message_id])).rows[0]
      if (!message) return
      const recipientIds = [...new Set(ctx.roomMembers.filter(activeMember).map(memberUserId))]
      await publishRoomCard({ chatId: `room:${kind}:${roomId}`, recipientIds, action: action === 'created' ? 'created' : 'updated',
        message: { ...message, [kind === 'group' ? 'group_id' : 'community_id']: roomId,
          sender_name: raffle.creator_name, created_at: new Date(message.created_at).toISOString(), updated_at: new Date(message.updated_at).toISOString() } })
    }
  }
  async function updateCard(db, raffle, status) {
    await db.query("UPDATE fallback_room_messages SET content=$2,updated_at=NOW() WHERE id=$1", [raffle.message_id,
      `${status === 'completed' ? '🎉 Sorteo completado' : status === 'cancelled' ? 'Sorteo cancelado' : '🎡 Sorteo'}: ${raffle.name}\n${raffle.participant_count} participantes`])
  }
  async function select(db, raffle, round) {
    const results = (await db.query('SELECT participant_id FROM fallback_raffle_results WHERE round_id=$1 ORDER BY position', [round.id])).rows
    const seen = new Set(results.map((r) => r.participant_id))
    const remaining = round.participants.filter((p) => !seen.has(p.id))
    if (!remaining.length) throw new RaffleError('ROUND_COMPLETED', 409)
    // Uniform selection is entirely server-side; request winner/index fields are never read.
    const winner = remaining[crypto.randomInt(remaining.length)]
    const position = results.length + 1
    await db.query('INSERT INTO fallback_raffle_results(raffle_id,round_id,participant_id,position) VALUES($1,$2,$3,$4)', [raffle.id, round.id, winner.id, position])
    const completed = remaining.length === 1
    await db.query(`UPDATE fallback_raffle_rounds SET status=$2,finished_at=CASE WHEN $3 THEN NOW() ELSE NULL END,
      next_draw_at=NOW()+($4::int * INTERVAL '1 millisecond') WHERE id=$1`, [round.id, completed ? 'completed' : 'running', completed, INTERVAL_MS])
    await db.query('UPDATE fallback_raffles SET status=$2,version=version+1,updated_at=NOW() WHERE id=$1', [raffle.id, completed ? 'completed' : 'running'])
    if (completed) await updateCard(db, raffle, 'completed')
    return { position, participantId: winner.id, completed }
  }
  async function command(user, kind, roomId, id, action, body) {
    const requestId = requireUuid(body.requestId)
    if (!Number.isInteger(body.expectedVersion) || body.expectedVersion < 0) throw new RaffleError('VERSION_REQUIRED')
    if (!['start', 'spin', 'automatic', 'new-round', 'cancel'].includes(action)) throw new RaffleError('INVALID_RAFFLE_ACTION')
    let selection = null
    if (action === 'new-round' && (body.memberIds != null || body.manualNames != null)) {
      if (!Array.isArray(body.memberIds) || !Array.isArray(body.manualNames) ||
        body.memberIds.length + body.manualNames.length > MAX_PARTICIPANTS ||
        body.memberIds.some(v => typeof v !== 'string') || body.manualNames.some(v => typeof v !== 'string')) throw new RaffleError('INVALID_PARTICIPANTS')
      const memberIds = [...new Set(body.memberIds.map(requireUuid))].sort()
      const manualNames = body.manualNames.map(v => clean(v).normalize('NFKC'))
      if (manualNames.some(n => !n || n.length > 80 || /[\x00-\x1f]/.test(n))) throw new RaffleError('INVALID_MANUAL_NAME')
      if (new Set(manualNames.map(lower)).size !== manualNames.length) throw new RaffleError('DUPLICATE_MANUAL_NAME')
      if (!memberIds.length && !manualNames.length) throw new RaffleError('PARTICIPANT_LIMIT')
      if (typeof body.memberSnapshotHash !== 'string' || !/^[0-9a-f]{64}$/.test(body.memberSnapshotHash)) throw new RaffleError('MEMBER_SNAPSHOT_REQUIRED')
      selection = { memberIds, manualNames, memberSnapshotHash: body.memberSnapshotHash }
    }
    const payloadHash = hash({ action, version: body.expectedVersion, automatic: body.automatic === true, ...(selection ? { selection } : {}) })
    const response = await transaction(async (db) => {
      const ctx = await access(db, user, kind, roomId)
      const raffle = (await db.query('SELECT * FROM fallback_raffles WHERE id=$1 AND room_kind=$2 AND room_id=$3 FOR UPDATE', [id, kind, roomId])).rows[0]
      if (!raffle) throw new RaffleError('RAFFLE_NOT_FOUND', 404)
      if (!canManageRaffle(ctx, raffle)) throw new RaffleError('RAFFLE_MANAGEMENT_REQUIRED', 403)
      const previous = (await db.query('SELECT * FROM fallback_raffle_commands WHERE raffle_id=$1 AND request_id=$2', [id, requestId])).rows[0]
      if (previous) {
        if (previous.actor_id !== ctx.identity.displayUserId || previous.payload_hash !== payloadHash) throw new RaffleError('IDEMPOTENCY_CONFLICT', 409)
        return previous.response
      }
      if (raffle.version !== body.expectedVersion) throw new RaffleError('RAFFLE_STATE_CHANGED', 409)
      if (raffle.status === 'cancelled') throw new RaffleError('RAFFLE_CANCELLED', 409)
      const round = (await db.query('SELECT * FROM fallback_raffle_rounds WHERE raffle_id=$1 ORDER BY number DESC LIMIT 1', [id])).rows[0]
      let result = { action }
      if (action === 'spin') {
        if (round?.status !== 'running') throw new RaffleError('ROUND_NOT_RUNNING', 409)
        if (round.automatic) throw new RaffleError('AUTOMATIC_RUNNING', 409)
        if (round.next_draw_at && new Date(round.next_draw_at) > new Date()) throw new RaffleError('SPIN_NOT_READY', 409)
        result = { action, ...await select(db, raffle, round) }
      } else {
        if (action === 'start' || action === 'new-round') {
          if ((action === 'start' && raffle.status !== 'prepared') || (action === 'new-round' && raffle.status !== 'completed')) throw new RaffleError('ROUND_STATE_INVALID', 409)
          let participants
          if (action === 'new-round') {
            const { participants: available } = await eligible(db, user, kind, roomId)
            const membershipHash = crypto.createHash('sha256').update(available.map(p => p.userId).sort().join('\n')).digest('hex')
            if (selection && selection.memberSnapshotHash !== membershipHash) throw new RaffleError('MEMBERS_CHANGED', 409)
            const byUser = new Map(available.map(p => [p.userId, p]))
            const memberIds = selection?.memberIds || available.map(p => p.userId)
            if (memberIds.some(memberId => !byUser.has(memberId))) throw new RaffleError('MEMBERS_CHANGED', 409)
            const manualNames = selection?.manualNames || (round?.participants || []).filter(p => p.kind === 'MANUAL_PARTICIPANT').map(p => p.name)
            if (memberIds.length + manualNames.length < 1 || memberIds.length + manualNames.length > MAX_PARTICIPANTS) throw new RaffleError('PARTICIPANT_LIMIT')
            // Only identities selected for this round are loaded. The historical
            // archive may outlive many rosters without growing this response/loop.
            const old = (await db.query(`SELECT * FROM fallback_raffle_participants WHERE raffle_id=$1 AND
              (user_id=ANY($2::text[]) OR (kind='MANUAL_PARTICIPANT' AND name=ANY($3::text[]))) ORDER BY ordinal`, [id, memberIds, manualNames])).rows
            const oldByUser = new Map(old.filter(p => p.user_id).map(p => [p.user_id, p]))
            const oldByManual = new Map()
            for (const p of old) if (p.kind === 'MANUAL_PARTICIPANT' && !oldByManual.has(p.name)) oldByManual.set(p.name, p)
            let ordinal = (await db.query('SELECT COALESCE(MAX(ordinal),-1)+1 AS next_ordinal FROM fallback_raffle_participants WHERE raffle_id=$1', [id])).rows[0].next_ordinal
            participants = []
            for (const source of [...memberIds.map(memberId => byUser.get(memberId)), ...manualNames.map(name => ({ kind: 'MANUAL_PARTICIPANT', userId: null, name, avatarUrl: '' }))]) {
              const existing = source.userId ? oldByUser.get(source.userId) : oldByManual.get(source.name)
              const participant = { ...source, id: existing?.id || crypto.randomUUID() }
              if (!existing) await db.query('INSERT INTO fallback_raffle_participants(id,raffle_id,kind,user_id,name,avatar_url,ordinal) VALUES($1,$2,$3,$4,$5,$6,$7)',
                [participant.id, id, participant.kind, participant.userId, participant.name, participant.avatarUrl, ordinal++])
              participants.push(participant)
            }
            // Old rows/results are never deleted: round JSON preserves the historical names and membership.
            await db.query('UPDATE fallback_raffles SET participant_count=$2 WHERE id=$1', [id, participants.length])
            raffle.participant_count = participants.length
          } else participants = (await db.query('SELECT * FROM fallback_raffle_participants WHERE raffle_id=$1 ORDER BY ordinal', [id])).rows.map(mapParticipant)
          // A new round is ready with zero results. A separate spin/automatic command is required.
          await beginRound(db, id, ctx.identity, participants, action === 'new-round' ? false : body.automatic === true)
          await updateCard(db, raffle, 'running')
        } else if (action === 'automatic') {
          if (round?.status !== 'running') throw new RaffleError('ROUND_NOT_RUNNING', 409)
          await db.query(`UPDATE fallback_raffle_rounds SET automatic=$2,initiated_by=$3,initiator_local_id=$4 WHERE id=$1`,
            [round.id, body.automatic === true, ctx.identity.displayUserId, ctx.identity.localUserId])
        } else if (action === 'cancel') {
          if (raffle.status === 'completed') throw new RaffleError('ROUND_COMPLETED', 409)
          await db.query("UPDATE fallback_raffles SET status='cancelled' WHERE id=$1", [id])
          await db.query("UPDATE fallback_raffle_rounds SET status='cancelled',finished_at=NOW(),automatic=FALSE WHERE raffle_id=$1 AND status='running'", [id])
          await updateCard(db, raffle, 'cancelled')
        }
        await db.query('UPDATE fallback_raffles SET version=version+1,updated_at=NOW() WHERE id=$1', [id])
      }
      await db.query('INSERT INTO fallback_raffle_commands(raffle_id,request_id,actor_id,payload_hash,response) VALUES($1,$2,$3,$4,$5)',
        [id, requestId, ctx.identity.displayUserId, payloadHash, JSON.stringify(result)])
      return result
    })
    await notify(kind, roomId, id, response.completed ? 'completed' : action)
    return { ...await detail(pool, user, kind, roomId, id), operation: response }
  }
  async function processDue() {
    const due = (await pool.query(`SELECT DISTINCT raffle_id FROM fallback_raffle_rounds
      WHERE status='running' AND automatic=TRUE AND next_draw_at<=NOW() LIMIT 25`)).rows
    for (const item of due) {
      const changed = await transaction(async (db) => {
        // SKIP LOCKED allows several API replicas to run workers safely.
        const raffle = (await db.query('SELECT * FROM fallback_raffles WHERE id=$1 FOR UPDATE SKIP LOCKED', [item.raffle_id])).rows[0]
        if (!raffle || raffle.status !== 'running') return null
        const round = (await db.query("SELECT * FROM fallback_raffle_rounds WHERE raffle_id=$1 AND status='running' AND automatic=TRUE AND next_draw_at<=NOW()", [raffle.id])).rows[0]
        if (!round) return null
        const actor = await getCurrentUser(db, { auth: { sub: round.initiator_local_id } })
        try {
          if (!actor || await isSuspended(db, round.initiator_local_id)) throw new RaffleError('RAFFLE_ACCESS_DENIED', 403)
          const ctx = await access(db, actor, raffle.room_kind, raffle.room_id)
          if (!canManageRaffle(ctx, raffle)) throw new RaffleError('RAFFLE_MANAGEMENT_REQUIRED', 403)
        } catch (error) {
          if (!(error instanceof RaffleError)) throw error
          await db.query('UPDATE fallback_raffle_rounds SET automatic=FALSE WHERE id=$1', [round.id])
          await db.query('UPDATE fallback_raffles SET version=version+1,updated_at=NOW() WHERE id=$1', [raffle.id])
          return { raffle, action: 'paused' }
        }
        const result = await select(db, raffle, round)
        return { raffle, action: result.completed ? 'completed' : 'selected' }
      })
      if (changed) await notify(changed.raffle.room_kind, changed.raffle.room_id, changed.raffle.id, changed.action)
    }
  }
  return { access, eligible, detail, create, command, processDue, mapRaffle }
}
module.exports = { createRaffleService, ensureRaffleTables, RaffleError, requireUuid, validateTarget, activeMember, MAX_PARTICIPANTS, INTERVAL_MS }
