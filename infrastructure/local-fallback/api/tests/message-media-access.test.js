'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { mayReuseMessageMedia } = require('../src/message-media-access')
const { currentUserMayReferenceMessageMedia, userIdentity } = require('../src/data-routes')

const user = { id: 'viewer', supabase_user_id: 'old-viewer' }
const identity = userIdentity(user)
const mediaUrl = 'https://old.example/storage/v1/object/public/chat-media/sender/photo.jpg'
const request = { mediaUrl, contentType: 'image', allowedOwnedBuckets: ['chat-media'],
  isForwarded: true, forwardedFromMessageId: 'source' }

function fixture({ kind = 'chat', message = {}, member = true, blocked = false,
  publicRoom = false, deletedRoom = false, missing = false, storageOwner = false } = {}) {
  const queries = []
  const pool = { query: async (sql, params) => {
    queries.push(String(sql))
    if (sql.includes('to_jsonb(m) AS message')) return { rows: missing ? [] : [{
      kind, scope_id: 'scope', message: { id: 'source', sender_id: 'sender',
        media_url: mediaUrl, content_type: 'image', deleted_by: [], ...message },
    }] }
    if (sql.includes('information_schema')) return { rows: [] }
    if (sql.includes('FROM fallback_user_blocks')) return { rows: blocked
      ? [blocked === 'by-viewer' ? { blocker_id: 'viewer', blocked_id: 'sender' }
        : { blocker_id: 'sender', blocked_id: 'viewer' }] : [] }
    if (sql.includes('FROM fallback_chats')) return { rows: [{ id: 'scope',
      participants: member ? ['old-viewer', 'sender'] : ['stranger', 'sender'], is_group: false }] }
    if (sql.includes('FROM fallback_chat_participants')) return { rows: [] }
    if (sql.includes('FROM fallback_collection_rooms')) return { rows: [{ id: 'scope',
      creator_id: 'leader', is_public: publicRoom, is_deleted: deletedRoom }] }
    if (sql.includes('FROM fallback_collection_members')) return { rows: [{ room_id: 'scope',
      user_id: 'old-viewer', is_deleted: !member }] }
    if (sql.includes('FROM fallback_storage_objects')) return { rows: storageOwner ? [{ owner_id: user.id }] : [] }
    throw new Error(`Unexpected query: ${sql.slice(0, 100)} / ${params?.length}`)
  } }
  return { pool, queries }
}

test('reusable policy distinguishes tombstone visibility, view-once and account aliases', () => {
  assert.equal(mayReuseMessageMedia({}, identity.ids), true)
  for (const message of [null, { deleted_for_all: true }, { deletedForAll: 'true' },
    { content_type: 'view_once_image' }, { content_type: 'view_once_video' },
    { is_view_once: true }, { deleted_by: ['OLD-VIEWER'] },
    { deleted_by: '["viewer"]' }, { deleted_by: 'malformed' }, { deleted_by: {} },
    { expires_at: '2020-01-01T00:00:00Z' }, { expires_at: 'invalid' }]) {
    assert.equal(mayReuseMessageMedia(message, identity.ids), false, JSON.stringify(message))
  }
  assert.equal(mayReuseMessageMedia({ deleted_by: ['other'] }, identity.ids), true)
  assert.equal(mayReuseMessageMedia({}, new Set()), false)
})

for (const kind of ['chat', 'group', 'community']) {
  test(`${kind}: eligible recipient can forward without fetching the whole room history`, async () => {
    const { pool, queries } = fixture({ kind })
    assert.equal(await currentUserMayReferenceMessageMedia(pool, user, identity, request), true)
    assert.equal(queries.some((sql) => /ORDER BY created_at ASC|FROM fallback_profile_overrides/.test(sql)), false)
    assert.equal(queries.filter((sql) => sql.includes('FROM fallback_room_messages')).length, 1)
  })
  test(`${kind}: nonmember cannot forward private media`, async () => {
    const { pool } = fixture({ kind, member: false })
    assert.equal(await currentUserMayReferenceMessageMedia(pool, user, identity, request), false)
  })
  test(`${kind}: source blocked by either direction cannot grant a new copy`, async () => {
    for (const blocked of [true, 'by-viewer']) {
      const { pool } = fixture({ kind, blocked })
      assert.equal(await currentUserMayReferenceMessageMedia(pool, user, identity, request), false)
    }
  })
}

test('deleted and view-once forwards cannot bypass checks through storage ownership', async () => {
  for (const message of [{ deleted_for_all: true }, { deleted_by: ['viewer'] },
    { content_type: 'view_once_video' }, { media_url: `${mediaUrl}.different` }]) {
    const { pool, queries } = fixture({ message, storageOwner: true })
    assert.equal(await currentUserMayReferenceMessageMedia(pool, user, identity, request), false)
    assert.equal(queries.some((sql) => sql.includes('FROM fallback_storage_objects')), false)
  }
})

test('public community access still works but deletion of the room revokes it', async () => {
  const open = fixture({ kind: 'community', member: false, publicRoom: true })
  assert.equal(await currentUserMayReferenceMessageMedia(open.pool, user, identity, request), true)
  const deleted = fixture({ kind: 'community', publicRoom: true, deletedRoom: true })
  assert.equal(await currentUserMayReferenceMessageMedia(deleted.pool, user, identity, request), false)
})

test('non-forwarded owned upload remains supported', async () => {
  const { pool } = fixture({ storageOwner: true })
  assert.equal(await currentUserMayReferenceMessageMedia(pool, user, identity,
    { ...request, isForwarded: false }), true)
})

test('labelling another user\'s stored photo as a sticker does not authorize reuse', async () => {
  const pool = { query: async (sql) => {
    if (sql.includes('FROM fallback_storage_objects')) {
      return { rows: sql.includes('owner_id') ? [] : [{ exists: true }] }
    }
    if (sql.includes('to_regclass') || sql.includes('fallback_compat_rows')) return { rows: [] }
    throw new Error('Unexpected sticker authorization query')
  } }
  assert.equal(await currentUserMayReferenceMessageMedia(pool, user, identity,
    { ...request, contentType: 'sticker', isForwarded: false }), false)
})
