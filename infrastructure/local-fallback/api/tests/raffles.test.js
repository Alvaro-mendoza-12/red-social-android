'use strict'

const assert = require('node:assert/strict')
const { test, before, after } = require('node:test')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const express = require('express')
const { createRaffleService, ensureRaffleTables, activeMember, validateTarget, RaffleError } = require('../src/raffles/service')
const { registerRaffleRoutes } = require('../src/raffles/routes')

test('raffle policy excludes removed/banned members and rejects invalid targets', () => {
  for (const status of ['left', 'removed', 'banned', 'blocked', 'deleted']) assert.equal(activeMember({ status }), false)
  assert.equal(activeMember({ role: 'member' }), true)
  assert.throws(() => validateTarget('chat', randomUUID()), RaffleError)
  assert.throws(() => validateTarget('group', 'bad'), RaffleError)
})

const connectionString = process.env.RAFFLE_TEST_DATABASE_URL
test('PostgreSQL persisted raffle workflow, HTTP policy and concurrent selections', { skip: !connectionString }, async (t) => {
  // This suite creates/drops ONLY its isolated randomly named schema on a local test DB.
  const url = new URL(connectionString)
  assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname), 'Only a local disposable test server may be used')
  const schema = `raffle_test_${randomUUID().replaceAll('-', '')}`
  const admin = new Pool({ connectionString })
  await admin.query(`CREATE SCHEMA ${schema}`)
  const pool = new Pool({ connectionString, max: 12, options: `-c search_path=${schema},public` })
  let server
  try {
    await pool.query(`
      CREATE TABLE fallback_users(id UUID PRIMARY KEY, display_name TEXT NOT NULL, avatar_url TEXT NOT NULL DEFAULT '', supabase_user_id TEXT);
      CREATE TABLE fallback_collection_rooms(id UUID PRIMARY KEY, room_kind TEXT NOT NULL, creator_id TEXT NOT NULL, is_deleted BOOLEAN DEFAULT FALSE);
      CREATE TABLE fallback_collection_members(room_kind TEXT NOT NULL,room_id TEXT NOT NULL,user_id TEXT NOT NULL,role TEXT NOT NULL DEFAULT 'member',status TEXT DEFAULT 'joined',is_deleted BOOLEAN DEFAULT FALSE);
      CREATE TABLE fallback_room_messages(id UUID PRIMARY KEY, room_kind TEXT,room_id TEXT,channel_id TEXT,sender_id TEXT,content TEXT,content_type TEXT,media_url TEXT,created_at TIMESTAMPTZ DEFAULT NOW(),updated_at TIMESTAMPTZ DEFAULT NOW());
    `)
    await ensureRaffleTables(pool)
    await ensureRaffleTables(pool)
    const owner = { id: randomUUID(), display_name: 'Álvaro' }, adminUser = { id: randomUUID(), display_name: 'Carlos' }
    const member = { id: randomUUID(), display_name: 'María' }, outsider = { id: randomUUID(), display_name: 'Andrea' }
    const moderator = { id: randomUUID(), display_name: 'Mod' }
    const users = [owner, adminUser, member, outsider, moderator]
    for (const user of users) await pool.query('INSERT INTO fallback_users(id,display_name) VALUES($1,$2)', [user.id, user.display_name])
    const notifications = [], cards = []
    const deps = {
      pool,
      getCurrentUser: async (db, req) => (await db.query('SELECT * FROM fallback_users WHERE id=$1', [req.auth.sub])).rows[0],
      userIdentity: (u) => ({ ids: new Set([u.id, u.supabase_user_id].filter(Boolean)), localUserId: u.id, displayUserId: u.supabase_user_id || u.id }),
      loadCollectionRoomAccess: async (db, user, kind, roomId) => {
        const room = (await db.query('SELECT * FROM fallback_collection_rooms WHERE id=$1 AND room_kind=$2 AND is_deleted=FALSE', [roomId, kind])).rows[0]
        if (!room) return null
        const roomMembers = (await db.query('SELECT * FROM fallback_collection_members WHERE room_kind=$1 AND room_id=$2', [kind, roomId])).rows
        return { room, roomMembers }
      },
      memberUserId: (m) => m.user_id,
      rowMatchesAny: (row, keys, ids) => keys.some((k) => ids.has(row[k])),
      publicProfilesByIds: async (db, ids) => (await db.query('SELECT * FROM fallback_users WHERE id::text=ANY($1) OR supabase_user_id=ANY($1)', [ids])).rows,
      publish: async (channel, payload) => { notifications.push({ channel, payload }); return true },
      publishRoomCard: async (card) => { cards.push(card) },
      ensureDefaultCommunityChannel: async () => ({ id: 'community-general' }),
    }
    const service = createRaffleService(deps)
    async function room(kind = 'group', roomUsers = [owner, adminUser, member]) {
      const id = randomUUID()
      await pool.query('INSERT INTO fallback_collection_rooms(id,room_kind,creator_id) VALUES($1,$2,$3)', [id, kind, owner.id])
      for (const u of roomUsers) await pool.query('INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role) VALUES($1,$2,$3,$4)', [kind, id, u.id,
        u === owner ? 'owner' : u === adminUser ? 'admin' : u === moderator ? 'moderator' : 'member'])
      return id
    }
    function creation(memberIds = [owner.id, adminUser.id, member.id], extra = {}) {
      return { requestId: randomUUID(), name: 'Elegir equipos', memberIds, manualNames: [], start: false, automatic: false, ...extra }
    }
    function cmd(detail, automatic = false) { return { requestId: randomUUID(), expectedVersion: detail.raffle.version, automatic } }
    async function ready(id) { await pool.query('UPDATE fallback_raffle_rounds SET next_draw_at=NOW()-INTERVAL \'1 second\' WHERE raffle_id=$1', [id]) }
    async function spinAll(user, kind, roomId, detail) {
      while (detail.raffle.status === 'running') {
        await ready(detail.raffle.id)
        detail = await service.command(user, kind, roomId, detail.raffle.id, 'spin', cmd(detail))
      }
      return detail
    }

    await t.test('member import, exclusion, manual snapshot, same-name users and real room card', async () => {
      const roomId = await room()
      const eligible = await service.eligible(pool, owner, 'group', roomId)
      assert.equal(eligible.participants.length, 3)
      const request = creation([owner.id, member.id], { manualNames: ['Pedro'] })
      const detail = await service.create(owner, 'group', roomId, request)
      assert.equal(detail.participants.length, 3)
      assert.equal(detail.participants.some((p) => p.userId === adminUser.id), false)
      assert.equal(detail.participants.at(-1).kind, 'MANUAL_PARTICIPANT')
      assert.equal(detail.participants.at(-1).userId, null)
      assert.equal((await pool.query('SELECT count(*)::int AS n FROM fallback_users')).rows[0].n, users.length)
      await pool.query('UPDATE fallback_users SET display_name=$2 WHERE id=$1', [member.id, owner.display_name])
      const historical = await service.detail(pool, member, 'group', roomId, detail.raffle.id)
      assert.equal(historical.participants.find((p) => p.userId === member.id).name, 'María')
      const next = await service.create(owner, 'group', roomId, creation())
      assert.equal(next.participants.filter((p) => p.name === owner.display_name).length, 2)
      assert.equal(new Set(next.participants.map((p) => p.id)).size, 3)
      await pool.query('UPDATE fallback_users SET display_name=$2 WHERE id=$1', [member.id, member.display_name])
      assert.equal(cards.at(-1).message.content_type, 'raffle')
      assert.equal(cards.at(-1).message.group_id, roomId)
      assert.ok(cards.at(-1).recipientIds.includes(member.id))
      assert.ok(notifications.every((n) => !('winner' in n.payload) && !('participants' in n.payload)))
    })
    await t.test('any active group member creates and manages their own raffle, including automatic rounds', async () => {
      const roomId = await room('group', [owner, adminUser, member, moderator])
      const permission = await service.access(pool, member, 'group', roomId)
      assert.equal(permission.canCreate, true)
      assert.equal(permission.canManage, false)
      const body = creation(undefined, { start: true, automatic: true })
      let detail = await service.create(member, 'group', roomId, body)
      assert.equal(detail.raffle.creatorId, member.id)
      assert.equal(detail.canManage, true)
      assert.equal((await service.create(member, 'group', roomId, body)).raffle.id, detail.raffle.id)
      assert.equal((await service.detail(pool, moderator, 'group', roomId, detail.raffle.id)).canManage, false)
      await assert.rejects(service.command(moderator, 'group', roomId, detail.raffle.id, 'cancel', cmd(detail)),
        { code: 'RAFFLE_MANAGEMENT_REQUIRED' })
      await assert.rejects(service.create(outsider, 'group', roomId, creation()), { code: 'RAFFLE_ACCESS_DENIED' })
      await ready(detail.raffle.id)
      await service.processDue()
      detail = await service.detail(pool, member, 'group', roomId, detail.raffle.id)
      assert.equal(detail.currentRound.results.length, 1)
      // Creator permissions do not survive removal from the group.
      await pool.query("UPDATE fallback_collection_members SET status='removed' WHERE room_id=$1 AND user_id=$2", [roomId, member.id])
      await assert.rejects(service.create(member, 'group', roomId, creation()), { code: 'RAFFLE_ACCESS_DENIED' })
      await assert.rejects(service.command(member, 'group', roomId, detail.raffle.id, 'cancel', cmd(detail)), { code: 'RAFFLE_ACCESS_DENIED' })
      await ready(detail.raffle.id)
      await service.processDue()
      detail = await service.detail(pool, owner, 'group', roomId, detail.raffle.id)
      assert.equal(detail.currentRound.automatic, false)
      assert.equal(detail.currentRound.results.length, 1)
      detail = await service.command(adminUser, 'group', roomId, detail.raffle.id, 'cancel', cmd(detail))
      assert.equal(detail.raffle.status, 'cancelled')
      const moderatorDraw = await service.create(moderator, 'group', roomId, creation([owner.id]))
      assert.equal(moderatorDraw.canManage, true)
    })
    await t.test('creation idempotency and manual duplicate/empty/foreign member validation', async () => {
      const roomId = await room(), body = creation()
      const [a, b] = await Promise.all([service.create(owner, 'group', roomId, body), service.create(owner, 'group', roomId, body)])
      assert.equal(a.raffle.id, b.raffle.id)
      await assert.rejects(service.create(owner, 'group', roomId, { ...body, name: 'Changed' }), { code: 'IDEMPOTENCY_CONFLICT' })
      await assert.rejects(service.create(owner, 'group', roomId, creation([], { manualNames: ['Pedro', ' pedro '] })), { code: 'DUPLICATE_MANUAL_NAME' })
      await assert.rejects(service.create(owner, 'group', roomId, creation([])), { code: 'PARTICIPANT_LIMIT' })
      await assert.rejects(service.create(owner, 'group', roomId, creation([outsider.id])), { code: 'MEMBERS_CHANGED' })
    })
    await t.test('two simultaneous admins create exactly one winner for the expected position', async () => {
      const roomId = await room()
      let detail = await service.create(owner, 'group', roomId, creation(undefined, { start: true }))
      await ready(detail.raffle.id)
      const settled = await Promise.allSettled([
        service.command(owner, 'group', roomId, detail.raffle.id, 'spin', cmd(detail)),
        service.command(adminUser, 'group', roomId, detail.raffle.id, 'spin', cmd(detail)),
      ])
      assert.equal(settled.filter((r) => r.status === 'fulfilled').length, 1)
      assert.equal(settled.find((r) => r.status === 'rejected').reason.code, 'RAFFLE_STATE_CHANGED')
      const rows = (await pool.query('SELECT * FROM fallback_raffle_results WHERE raffle_id=$1', [detail.raffle.id])).rows
      assert.equal(rows.length, 1); assert.equal(rows[0].position, 1)
      detail = await service.detail(pool, owner, 'group', roomId, detail.raffle.id)
      detail = await spinAll(owner, 'group', roomId, detail)
      assert.equal(detail.currentRound.results.length, 3)
      assert.equal(new Set(detail.currentRound.results.map((r) => r.participant.id)).size, 3)
      assert.equal(detail.raffle.status, 'completed')
    })
    await t.test('double request is replayed, client-supplied winner is ignored and DB constraints enforce uniqueness', async () => {
      const roomId = await room()
      const detail = await service.create(owner, 'group', roomId, creation(undefined, { start: true }))
      await ready(detail.raffle.id)
      const body = { ...cmd(detail), winnerIndex: 100000, winnerId: outsider.id }
      const [a, b] = await Promise.all([service.command(owner, 'group', roomId, detail.raffle.id, 'spin', body), service.command(owner, 'group', roomId, detail.raffle.id, 'spin', body)])
      assert.deepEqual(a.operation, b.operation)
      assert.equal(a.currentRound.results.length, 1)
      assert.ok(detail.participants.some((p) => p.id === a.operation.participantId))
      const winner = a.currentRound.results[0]
      await assert.rejects(pool.query('INSERT INTO fallback_raffle_results(raffle_id,round_id,participant_id,position) VALUES($1,$2,$3,$4)',
        [detail.raffle.id, a.currentRound.id, winner.participant.id, 2]), { code: '23505' })
    })
    await t.test('four members become five only after current membership review; no automatic first draw and history preserved', async () => {
      const roomId = await room('group', [owner, adminUser, member, moderator])
      let detail = await service.create(owner, 'group', roomId, creation([owner.id, adminUser.id, member.id, moderator.id], { start:true }))
      detail = await spinAll(owner, 'group', roomId, detail)
      const oldRound = structuredClone(detail.currentRound)
      await pool.query('INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role) VALUES($1,$2,$3,$4)', ['group',roomId,outsider.id,'member'])
      const available = (await service.eligible(pool, owner, 'group', roomId)).participants
      const snapshotHash = require('node:crypto').createHash('sha256').update(available.map(p=>p.userId).sort().join('\n')).digest('hex')
      const command = { ...cmd(detail,true), memberIds:available.map(p=>p.userId), manualNames:[], memberSnapshotHash:snapshotHash }
      await assert.rejects(service.command(owner,'group',roomId,detail.raffle.id,'new-round',{...command,memberSnapshotHash:'0'.repeat(64)}),{code:'MEMBERS_CHANGED'})
      await assert.rejects(service.command(owner,'group',roomId,detail.raffle.id,'new-round',{...command,memberIds:[require('node:crypto').randomUUID()]}),{code:'MEMBERS_CHANGED'})
      const next = await service.command(owner,'group',roomId,detail.raffle.id,'new-round',command)
      assert.equal(next.currentRound.participants.length,5);assert.equal(next.raffle.participantCount,5)
      assert.ok(next.currentRound.participants.some(p=>p.userId===outsider.id))
      assert.equal(next.currentRound.automatic,false);assert.equal(next.currentRound.results.length,0)
      assert.equal((await service.command(owner,'group',roomId,next.raffle.id,'new-round',command)).currentRound.id,next.currentRound.id)
      await assert.rejects(service.command(owner,'group',roomId,next.raffle.id,'new-round',{...command,memberIds:[owner.id]}),{code:'IDEMPOTENCY_CONFLICT'})
      await ready(next.raffle.id);await service.processDue()
      detail=await service.detail(pool,owner,'group',roomId,next.raffle.id);assert.equal(detail.currentRound.results.length,0)
      detail=await spinAll(owner,'group',roomId,detail);assert.equal(detail.currentRound.results.length,5)
      const history=detail.rounds.find(r=>r.id===oldRound.id)
      assert.deepEqual(history.participants,oldRound.participants);assert.deepEqual(history.results,oldRound.results)
    })
    await t.test('detail and new round stay bounded with fifteen thousand retained participant identities', async () => {
      const roomId = await room('group', [owner, adminUser, member, moderator])
      let detail = await service.create(owner, 'group', roomId, creation([owner.id, adminUser.id, member.id, moderator.id], { start:true }))
      detail = await spinAll(owner, 'group', roomId, detail)
      const oldRound = structuredClone(detail.currentRound)
      // Synthetic archive only: no production participant or personal data.
      await pool.query(`INSERT INTO fallback_raffle_participants(id,raffle_id,kind,user_id,name,avatar_url,ordinal)
        SELECT gen_random_uuid(),$1,'MANUAL_PARTICIPANT',NULL,'Historic '||n,'',n+3 FROM generate_series(1,15000) n`, [detail.raffle.id])
      const historic = (await pool.query("SELECT id FROM fallback_raffle_participants WHERE raffle_id=$1 AND name='Historic 14999'", [detail.raffle.id])).rows[0].id
      detail = await service.detail(pool,owner,'group',roomId,detail.raffle.id)
      assert.equal(detail.participants.length,4)
      assert.deepEqual(detail.currentRound,oldRound)
      const available = (await service.eligible(pool,owner,'group',roomId)).participants
      const snapshotHash = require('node:crypto').createHash('sha256').update(available.map(p=>p.userId).sort().join('\n')).digest('hex')
      const next = await service.command(owner,'group',roomId,detail.raffle.id,'new-round',{
        ...cmd(detail,true),memberIds:[owner.id,member.id],manualNames:['Historic 14999','Actual nuevo'],memberSnapshotHash:snapshotHash
      })
      assert.equal(next.participants.length,4)
      assert.equal(next.currentRound.participants.length,4)
      assert.equal(next.currentRound.results.length,0)
      assert.equal(next.currentRound.automatic,false)
      assert.equal(next.currentRound.participants.find(p=>p.name==='Historic 14999').id,historic)
      const retained = await pool.query('SELECT count(*)::int AS count,max(ordinal) AS ordinal FROM fallback_raffle_participants WHERE raffle_id=$1',[next.raffle.id])
      assert.deepEqual(retained.rows[0],{count:15005,ordinal:15004})
      detail = await spinAll(owner,'group',roomId,next)
      const history = detail.rounds.find(r=>r.id===oldRound.id)
      assert.deepEqual(history.participants,oldRound.participants)
      assert.deepEqual(history.results,oldRound.results)
      assert.equal(detail.participants.length,4)
    })
    await t.test('start, all results, simultaneous new rounds and preservation/pagination of old rounds', async () => {
      const roomId = await room()
      let detail = await service.create(owner, 'group', roomId, creation())
      detail = await service.command(owner, 'group', roomId, detail.raffle.id, 'start', cmd(detail))
      detail = await spinAll(owner, 'group', roomId, detail)
      const original = detail.currentRound.results
      const settled = await Promise.allSettled([
        service.command(owner, 'group', roomId, detail.raffle.id, 'new-round', cmd(detail)),
        service.command(adminUser, 'group', roomId, detail.raffle.id, 'new-round', cmd(detail)),
      ])
      assert.equal(settled.filter((r) => r.status === 'fulfilled').length, 1)
      detail = await service.detail(pool, owner, 'group', roomId, detail.raffle.id)
      assert.equal(detail.currentRound.number, 2)
      assert.deepEqual(detail.rounds.find((r) => r.number === 1).results, original)
      assert.equal(detail.currentRound.results.length, 0)
      for (let i = 0; i < 10; i++) {
        detail = await spinAll(owner, 'group', roomId, detail)
        detail = await service.command(owner, 'group', roomId, detail.raffle.id, 'new-round', cmd(detail))
      }
      assert.equal(detail.rounds.length, 10); assert.ok(detail.nextRound)
      const older = await service.detail(pool, owner, 'group', roomId, detail.raffle.id, detail.nextRound)
      assert.equal(older.rounds.at(-1).number, 1)
    })
    await t.test('only members read private/public-community draws, moderators cannot manage, lost role pauses auto worker', async () => {
      const roomId = await room('community', [owner, adminUser, member, moderator])
      await assert.rejects(service.create(member, 'community', roomId, creation()), { code: 'RAFFLE_ADMIN_REQUIRED' })
      await assert.rejects(service.create(moderator, 'community', roomId, creation()), { code: 'RAFFLE_ADMIN_REQUIRED' })
      let detail = await service.create(adminUser, 'community', roomId, creation(undefined, { start: true, automatic: true }))
      assert.equal(cards.at(-1).message.channel_id, 'community-general')
      await assert.rejects(service.detail(pool, outsider, 'community', roomId, detail.raffle.id), { code: 'RAFFLE_ACCESS_DENIED' })
      await pool.query("UPDATE fallback_collection_members SET role='member' WHERE room_id=$1 AND user_id=$2", [roomId, adminUser.id])
      await ready(detail.raffle.id); await service.processDue()
      detail = await service.detail(pool, member, 'community', roomId, detail.raffle.id)
      assert.equal(detail.currentRound.automatic, false); assert.equal(detail.currentRound.results.length, 0)
      await pool.query("UPDATE fallback_collection_members SET status='removed' WHERE room_id=$1 AND user_id=$2", [roomId, member.id])
      await assert.rejects(service.detail(pool, member, 'community', roomId, detail.raffle.id), { code: 'RAFFLE_ACCESS_DENIED' })
      assert.equal((await service.detail(pool, owner, 'community', roomId, detail.raffle.id)).participants.length, 3)
    })
    await t.test('server automatic worker survives service recreation and concurrent replicas without duplicate results', async () => {
      const roomId = await room()
      const detail = await service.create(owner, 'group', roomId, creation(undefined, { start: true, automatic: true }))
      const recreated = createRaffleService(deps)
      await ready(detail.raffle.id)
      await Promise.all([service.processDue(), recreated.processDue()])
      let state = await recreated.detail(pool, member, 'group', roomId, detail.raffle.id)
      assert.equal(state.currentRound.results.length, 1)
      for (let i = 0; i < 2; i++) { await ready(detail.raffle.id); await recreated.processDue() }
      state = await recreated.detail(pool, owner, 'group', roomId, detail.raffle.id)
      assert.equal(state.raffle.status, 'completed'); assert.equal(state.currentRound.results.length, 3)
      assert.equal(new Set(state.currentRound.results.map((r) => r.participant.id)).size, 3)
    })
    await t.test('cancellation preserves selected positions; deleting the room removes access and pauses automation', async () => {
      const roomId = await room()
      let detail = await service.create(owner, 'group', roomId, creation(undefined, { start: true }))
      await ready(detail.raffle.id)
      detail = await service.command(owner, 'group', roomId, detail.raffle.id, 'spin', cmd(detail))
      detail = await service.command(owner, 'group', roomId, detail.raffle.id, 'cancel', cmd(detail))
      assert.equal(detail.currentRound.status, 'cancelled'); assert.equal(detail.currentRound.results.length, 1)
      await assert.rejects(service.command(owner, 'group', roomId, detail.raffle.id, 'spin', cmd(detail)), { code: 'RAFFLE_CANCELLED' })
      await pool.query('UPDATE fallback_collection_rooms SET is_deleted=TRUE WHERE id=$1', [roomId])
      await assert.rejects(service.detail(pool, owner, 'group', roomId, detail.raffle.id), { code: 'RAFFLE_ACCESS_DENIED' })
    })
    await t.test('one participant and 100+ participants complete without repeated winners', async () => {
      const roomId = await room()
      let single = await service.create(owner, 'group', roomId, creation([member.id], { start: true }))
      single = await spinAll(owner, 'group', roomId, single)
      assert.equal(single.currentRound.results.length, 1)
      const manuals = Array.from({ length: 101 }, (_, i) => `Manual ${i}`)
      let big = await service.create(owner, 'group', roomId, creation([], { manualNames: manuals, start: true }))
      big = await spinAll(owner, 'group', roomId, big)
      assert.equal(big.currentRound.results.length, 101)
      assert.equal(new Set(big.currentRound.results.map((r) => r.participant.id)).size, 101)
    })
    await t.test('canonical aliases deduplicate members, 1000 participants are bounded and suspension pauses automation', async () => {
      const roomId = await room()
      const legacy = randomUUID()
      await pool.query('UPDATE fallback_users SET supabase_user_id=$2 WHERE id=$1', [member.id, legacy])
      await pool.query("INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role) VALUES('group',$1,$2,'member')", [roomId, legacy])
      const eligible = await service.eligible(pool, owner, 'group', roomId)
      assert.equal(eligible.participants.length, 3)
      assert.equal(eligible.participants.filter((p) => p.userId === legacy).length, 1)
      await pool.query('UPDATE fallback_users SET supabase_user_id=NULL WHERE id=$1', [member.id])
      const manuals = Array.from({ length: 1000 }, (_, i) => `Participante ${i}`)
      const detail = await service.create(owner, 'group', roomId, creation([], { manualNames: manuals, start: true, automatic: true }))
      assert.equal(detail.currentRound.participants.length, 1000)
      await assert.rejects(service.create(owner, 'group', roomId, creation([], { manualNames: [...manuals, 'Extra'] })), { code: 'PARTICIPANT_LIMIT' })
      await ready(detail.raffle.id)
      await createRaffleService({ ...deps, isSuspended: async () => true }).processDue()
      const paused = await service.detail(pool, owner, 'group', roomId, detail.raffle.id)
      assert.equal(paused.currentRound.automatic, false)
      assert.equal(paused.currentRound.results.length, 0)
    })
    await t.test('HTTP endpoints validate auth, scope, cursors, actions and snapshot persistence after account deletion', async () => {
      const app = express(); app.use(express.json())
      const authRequired = (req, res, next) => {
        const id = req.headers['x-test-user']
        if (!users.some((u) => u.id === id)) return res.status(401).json({ error: 'AUTH_REQUIRED' })
        req.auth = { sub: id }; next()
      }
      registerRaffleRoutes(app, { ...deps, authRequired })
      app.use((error, _req, res, _next) => res.status(500).json({ error: error.code || 'FAILED' }))
      server = await new Promise((resolve) => { const s = app.listen(0, '127.0.0.1', () => resolve(s)) })
      const roomId = await room(), base = `http://127.0.0.1:${server.address().port}/v1/data/raffles/group/${roomId}`
      const get = (suffix, user = owner) => fetch(base + suffix, { headers: user ? { 'x-test-user': user.id } : {} })
      assert.equal((await get('/members', null)).status, 401)
      assert.equal((await get('/members', outsider)).status, 403)
      const access = await (await get('/access', member)).json()
      assert.equal(access.canCreate, true)
      assert.equal(access.canManage, false)
      const memberList = await (await get('', member)).json()
      assert.equal(memberList.canCreate, true)
      const memberPage = await (await get('/members', member)).json()
      assert.equal(memberPage.canCreate, true)
      const memberCreated = await fetch(base, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-test-user': member.id }, body: JSON.stringify(creation()) })
      assert.equal(memberCreated.status, 200)
      const ownRaffle = await memberCreated.json()
      assert.equal(ownRaffle.canManage, true)
      assert.equal((await get('?cursor=bad')).status, 400)
      assert.equal((await get('/members?offset=NaN')).status, 400)
      const response = await fetch(base, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-test-user': owner.id }, body: JSON.stringify(creation()) })
      assert.equal(response.status, 200)
      const detail = await response.json()
      assert.equal((await get(`/${detail.raffle.id}/?beforeRound=0`)).status, 400)
      const recovered = await (await get(`/${detail.raffle.id}`, member)).json()
      assert.equal(recovered.raffle.id, detail.raffle.id)
      const otherRoom = await room()
      assert.equal((await fetch(base.replace(roomId, otherRoom) + '/' + detail.raffle.id, { headers: { 'x-test-user': owner.id } })).status, 404)
      await pool.query('DELETE FROM fallback_users WHERE id=$1', [member.id])
      const snapshot = await service.detail(pool, owner, 'group', roomId, detail.raffle.id)
      assert.equal(snapshot.participants.find((p) => p.userId === member.id).name, 'María')
      assert.equal((await service.eligible(pool, owner, 'group', roomId)).participants.some((p) => p.userId === member.id), false)
    })
  } finally {
    if (server) await new Promise((resolve) => server.close(resolve))
    await pool.end()
    assert.match(schema, /^raffle_test_[a-f0-9]{32}$/)
    await admin.query(`DROP SCHEMA ${schema} CASCADE`)
    await admin.end()
  }
})
