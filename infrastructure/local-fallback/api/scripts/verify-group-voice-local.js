'use strict'
// Integration fixture only. Never run this against a user database or public API.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Pool } = require('pg')
const { retireEmptyGroupVoiceRooms, deliverGroupVoiceInvitations } = require('../src/group-voice-rooms')

async function verify() {
  const base = new URL(process.env.GROUP_VOICE_QA_API_URL || 'invalid:')
  const database = new URL(process.env.GROUP_VOICE_QA_DATABASE_URL || 'invalid:')
  for (const value of [base, database]) assert.ok(['127.0.0.1', 'localhost'].includes(value.hostname), 'Isolated loopback fixture required')
  assert.equal(process.env.GROUP_VOICE_QA_DISPOSABLE, 'true', 'Disposable database must be explicit')
  const pool = new Pool({ connectionString: database.toString(), max: 8 })
  const checks = []
  async function call(label, path, expected, token, body, method = body ? 'POST' : 'GET') {
    const response = await fetch(new URL(path, base), { method, signal: AbortSignal.timeout(15000),
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
      ...(body ? { body: JSON.stringify(body) } : {}) })
    const result = await response.json()
    assert.equal(response.status, expected, `${label}: ${JSON.stringify(result).slice(0, 350)}`)
    checks.push({ label, status: response.status })
    return result
  }
  try {
    const people = []
    for (const name of ['Creadora', 'Invitada', 'Otro integrante', 'Ajena']) {
      const response = await call(`register ${name}`, '/v1/auth/register', 201, null,
        { email: `voice-${crypto.randomUUID()}@example.test`, password: crypto.randomBytes(18).toString('hex'), displayName: name })
      people.push({ token: response.accessToken, id: response.user.supabaseUserId || response.user.supabase_user_id || response.user.id })
    }
    const [owner, alice, bob, outside] = people
    const groupId = crypto.randomUUID()
    await call('create group', '/v1/data/messages/groups', 201, owner.token,
      { id: groupId, name: 'Grupo de prueba de voz', memberIds: [alice.id, bob.id], createOnly: true })
    const root = `/v1/data/messages/groups/${groupId}`
    const roster = await call('authoritative roster', root + '/voice-room-invitees', 200, owner.token)
    assert.deepEqual(roster.members.map(member => member.id).sort(), [alice.id, bob.id].sort())
    assert.ok(roster.members.every(member => member.name && 'avatarUrl' in member))
    await call('anonymous cannot list invitees', root + '/voice-room-invitees', 401)
    await call('outsider cannot list invitees', root + '/voice-room-invitees', 403, outside.token)
    const request = { requestId: crypto.randomUUID(), mode: 'all', recipientIds: roster.members.map(member => member.id) }
    const responses = await Promise.all(Array.from({ length: 6 }, () => call('concurrent all replay', root + '/voice-rooms', 200, owner.token, request)))
    const roomId = responses[0].roomId
    assert.ok(responses.every(result => result.roomId === roomId && result.invitedCount === 2))
    const persisted = await pool.query('SELECT * FROM fallback_notifications WHERE type=$1 AND target_id=$2', ['group_voice_room_invite', roomId])
    assert.equal(persisted.rows.length, 2)
    assert.ok(persisted.rows.every(row => row.actor_id === owner.id && row.content.includes('Creadora') && row.content.includes('Grupo de prueba de voz')))
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_voice_room_participants WHERE room_id=$1', [roomId])).rows[0].n, 0)
    assert.equal((await pool.query("SELECT COUNT(*)::int AS n FROM fallback_notifications WHERE type LIKE 'call_%'")).rows[0].n, 0)
    checks.push({ label: 'exactly two normal invitations; ignoring creates no presence and no calls', status: 'pass' })
    await call('changed intent cannot reuse ID', root + '/voice-rooms', 409, owner.token, { ...request, mode: 'selected', recipientIds: [alice.id] })
    await call('partial all snapshot rejected', root + '/voice-rooms', 409, owner.token, { ...request, requestId: crypto.randomUUID(), recipientIds: [alice.id] })
    await call('outside recipient rejected', root + '/voice-rooms', 409, owner.token, { requestId: crypto.randomUUID(), mode: 'selected', recipientIds: [outside.id] })
    await call('empty selection rejected', root + '/voice-rooms', 400, owner.token, { requestId: crypto.randomUUID(), mode: 'selected', recipientIds: [] })
    const path = `/v1/data/voice/rooms/${roomId}`
    const notices = await call('recipient receives activity notification', '/v1/data/notifications', 200, alice.token)
    assert.ok(JSON.stringify(notices).includes(roomId))
    const detail = await call('notification opens authorized room', path, 200, alice.token)
    assert.equal(detail.room.group_id, groupId)
    assert.equal(detail.room.password_required, false)
    await call('outsider cannot read private group room', path, 403, outside.token)
    await call('outsider cannot join group room', path + '/join', 403, outside.token, { socketId: 'outside', isMuted: true })
    await call('voluntary creator join muted', path + '/join', 200, owner.token, { socketId: 'creator-socket', isMuted: true })
    const joined = await call('voluntary invitee join muted', path + '/join', 200, alice.token, { socketId: 'alice-socket', isMuted: true })
    assert.equal(joined.participants.find(participant => participant.user_id === alice.id).is_muted, true)
    const mic = await call('microphone enabled by participant', path + '/join', 200, alice.token, { socketId: 'alice-socket', isMuted: false })
    assert.equal(mic.participants.find(participant => participant.user_id === alice.id).is_muted, false)
    const muted = await call('microphone muted by participant', path + '/join', 200, alice.token, { socketId: 'alice-socket', isMuted: true })
    assert.equal(muted.participants.find(participant => participant.user_id === alice.id).is_muted, true)
    await call('mute update without a socket preserves disconnect identity', path + '/join', 200, owner.token, { isMuted: true })
    const beforeExit = await pool.query('SELECT socket_id FROM fallback_voice_room_participants WHERE room_id=$1 AND user_id=$2',[roomId,owner.id])
    assert.equal(beforeExit.rows[0].socket_id,'creator-socket')
    await call('creator leaves without ending room', path + '/leave', 200, owner.token, { socketId: 'creator-socket' })
    const continuing = await call('room continues with invitee', path, 200, alice.token)
    assert.equal(continuing.room.creator_id, owner.id)
    assert.ok(continuing.participants.some(participant => participant.user_id === alice.id))
    const directory = await call('public directory', '/v1/data/voice/rooms', 200, outside.token)
    assert.ok(!JSON.stringify(directory).includes(roomId))
    for (const table of ['voice_rooms', 'voice_room_participants']) {
      const compat = await call(`compatibility hides private ${table}`, '/rest/v1/' + table, 200, outside.token)
      assert.ok(!JSON.stringify(compat).includes(roomId))
    }
    const selected = { requestId: crypto.randomUUID(), mode: 'selected', recipientIds: [alice.id, owner.id, alice.id] }
    const subset = await call('select some excludes creator and duplicates', root + '/voice-rooms', 200, owner.token, selected)
    assert.equal(subset.invitedCount, 1)
    await call('uninvited member cannot read selected room', `/v1/data/voice/rooms/${subset.roomId}`, 403, bob.token)
    await call('uninvited member cannot join selected room', `/v1/data/voice/rooms/${subset.roomId}/join`, 403, bob.token, { isMuted: true })
    await pool.query("UPDATE fallback_collection_members SET is_deleted=TRUE WHERE room_kind='group' AND room_id=$1 AND lower(user_id)=$2", [groupId, alice.id])
    await call('removed invitee cannot read room', path, 403, alice.token)
    await call('removed invitee cannot rejoin even with old socket', path + '/join', 403, alice.token, { socketId: 'alice-socket', isMuted: true })
    const replay = await call('creator replay after roster change retains original invitations', root + '/voice-rooms', 200, owner.token, request)
    assert.equal(replay.roomId, roomId)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_notifications WHERE target_id=$1', [roomId])).rows[0].n, 2)
    // Make the room old while retaining one live participant: creator absence never retires it.
    await pool.query("UPDATE fallback_voice_rooms SET created_at=NOW()-INTERVAL '10 minutes',updated_at=NOW()-INTERVAL '5 minutes' WHERE id=$1", [roomId])
    await retireEmptyGroupVoiceRooms(pool)
    assert.equal((await pool.query('SELECT is_active FROM fallback_voice_rooms WHERE id=$1', [roomId])).rows[0].is_active, true)
    await pool.query("UPDATE fallback_voice_room_participants SET updated_at=NOW()-INTERVAL '5 minutes' WHERE room_id=$1", [roomId])
    await retireEmptyGroupVoiceRooms(pool)
    assert.equal((await pool.query('SELECT is_active FROM fallback_voice_rooms WHERE id=$1', [roomId])).rows[0].is_active, false)
    await call('empty expired room unavailable', path, 404, owner.token)
    await call('replay cannot resurrect expired room', root + '/voice-rooms', 410, owner.token, request)
    checks.push({ label: 'room survives creator exit and retires after empty presence expiry', status: 'pass' })
    // Delivery resumes from committed rows with the same ID after a transport failure.
    await pool.query("UPDATE fallback_collection_members SET is_deleted=FALSE WHERE room_kind='group' AND room_id=$1 AND lower(user_id)=$2", [groupId, alice.id])
    await pool.query('UPDATE fallback_group_voice_invites SET delivered_at=NULL,retry_at=NOW(),claimed_until=NULL WHERE room_id=$1', [subset.roomId])
    const delivered = []
    await deliverGroupVoiceInvitations(pool, async notice => { delivered.push(notice.id); throw new Error('fixture transport interruption') })
    await pool.query('UPDATE fallback_group_voice_invites SET retry_at=NOW() WHERE room_id=$1', [subset.roomId])
    await deliverGroupVoiceInvitations(pool, async notice => { delivered.push(notice.id); return true })
    assert.equal(delivered.length, 2)
    assert.equal(delivered[0], delivered[1])
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_notifications WHERE target_id=$1', [subset.roomId])).rows[0].n, 1)
    checks.push({ label: 'durable notification outbox retries the same notification ID', status: 'pass' })
    const legacyId = crypto.randomUUID()
    const membershipId = crypto.randomUUID()
    await pool.query(`INSERT INTO fallback_compat_rows(table_name,row_id,row)
      VALUES('group_members',$1,$2::jsonb),('profiles',$3,$4::jsonb)`,
      [membershipId, JSON.stringify({id:membershipId,group_id:groupId,user_id:legacyId,role:'member'}),
        legacyId, JSON.stringify({id:legacyId,display_name:'Integrante importada',avatar_url:''})])
    const complete = await call('all includes an imported member without a local login record', root + '/voice-room-invitees', 200, owner.token)
    assert.ok(complete.members.some(member => member.id === legacyId && member.name === 'Integrante importada'))
    const withLegacy = await call('all persists notification for the imported member too', root + '/voice-rooms', 200, owner.token,
      { requestId: crypto.randomUUID(), mode:'all',recipientIds:complete.members.map(member => member.id) })
    assert.equal(withLegacy.invitedCount,3)
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_notifications WHERE target_id=$1 AND recipient_id=$2', [withLegacy.roomId,legacyId])).rows[0].n,1)
    console.log(JSON.stringify({ ok: true, checks }, null, 2))
  } finally { await pool.end() }
}
verify().catch(error => { console.error(error); process.exitCode = 1 })
