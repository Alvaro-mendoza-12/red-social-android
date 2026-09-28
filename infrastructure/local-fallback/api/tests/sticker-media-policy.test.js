'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { catalogAllowsStickerMedia, stickerStorageKey } = require('../src/sticker-media-policy')
const { canReuseCatalogStickerMedia, _test } = require('../src/postgrest-compat-routes')
const { currentUserMayReferenceMessageMedia, userIdentity } = require('../src/data-routes')

const mediaUrl = 'https://current.example/storage/v1/object/public/chat-media/author/hola%20%2B.png'
const oldUrl = mediaUrl.replace('current.example', 'previous.example')
const user = { id: 'viewer', supabase_user_id: 'old-viewer' }
const identity = { ...userIdentity(user), localUserId: 'viewer', preferredUserId: 'old-viewer' }
const pack = { id: 'pack', owner_id: 'author', is_public: true }
const item = { id: 'item', sticker_id: 'sticker', pack_id: 'pack', media_url: oldUrl }
const allowed = (overrides = {}) => catalogAllowsStickerMedia({
  items: [item], packs: [pack], viewerIds: identity.ids, mediaUrl, ...overrides,
})

function catalogPool({ importedPacks = [pack], importedItems = [item], packs = [], items = [], owned = false, stored = true } = {}) {
  return { query: async (sql, params) => {
    if (sql.includes('to_regclass')) return { rows: [{ oid: 1 }] }
    if (sql.includes('FROM "supabase_import"."sticker_packs"')) return { rows: importedPacks.map(row => ({ row })) }
    if (sql.includes('FROM "supabase_import"."sticker_pack_items"')) return { rows: importedItems.map(row => ({ row })) }
    if (sql.includes('FROM fallback_compat_rows')) {
      const rows = params[0] === 'sticker_packs' ? packs : items
      return { rows: rows.map(row => ({ row })) }
    }
    if (sql.includes('SELECT id::text AS id FROM fallback_messages')) return { rows: [] }
    if (sql.includes('FROM fallback_storage_objects')) {
      const permitted = sql.includes('owner_id') ? owned : stored
      return { rows: permitted && params[0] === 'chat-media' ? [{}] : [] }
    }
    if (sql.includes('fallback_profile_moderation')) return { rows: [] }
    throw new Error(`Unexpected catalog query: ${sql.slice(0, 80)}`)
  } }
}

test('catalog sticker identity survives tunnel rotation, encoded names and signed URL refresh', () => {
  assert.equal(allowed(), true)
  assert.equal(stickerStorageKey(mediaUrl), 'chat-media/author/hola +.png')
  assert.equal(allowed({ mediaUrl: mediaUrl.replace('/public/', '/sign/') + '?token=expired' }), true)
  assert.equal(allowed({ mediaUrl: mediaUrl.replace('author/', 'other/') }), false)
  assert.equal(allowed({ mediaUrl: mediaUrl.replace('chat-media', 'avatars') }), false)
  assert.equal(allowed({ stickerId: 'different-sticker' }), false)
  assert.equal(allowed({ stickerId: 'STICKER' }), true)
})

test('private packs require ownership, including the migrated account identity', () => {
  assert.equal(allowed({ packs: [{ ...pack, is_public: false }] }), false)
  assert.equal(allowed({ packs: [{ ...pack, is_public: false, owner_id: 'OLD-VIEWER' }] }), true)
  assert.equal(allowed({ viewerIds: new Set() }), false)
  assert.equal(allowed({ packs: [] }), false)
  assert.equal(allowed({ items: [] }), false)
  assert.equal(allowed({ packs: [{ ...pack, is_public: 'true' }] }), false)
})

test('removed packs and items cannot grant reuse even to their owner', () => {
  for (const deleted of [{ is_deleted: true }, { deleted: 'true' }, { deleted_for_all: 1 },
    { deleted_at: '2026-09-17T12:00:00Z' }]) {
    assert.equal(allowed({ packs: [{ ...pack, owner_id: 'viewer', ...deleted }] }), false)
    assert.equal(allowed({ items: [{ ...item, ...deleted }] }), false)
  }
})

test('storage identity rejects malformed or ambiguous names and preserves literal plus/percent', () => {
  for (const url of ['file:///storage/v1/object/public/chat-media/file',
    'https://actor:secret@host/storage/v1/object/public/chat-media/file',
    mediaUrl.replace('hola%20%2B.png', '%ZZ'), mediaUrl.replace('hola%20%2B.png', '%00'),
    mediaUrl.replace('hola%20%2B.png', 'a%2Fb'), mediaUrl.replace('hola%20%2B.png', '%5C')]) {
    assert.equal(stickerStorageKey(url), '')
  }
  assert.equal(stickerStorageKey(mediaUrl.replace('hola%20%2B.png', 'a+b%252F.png')), 'chat-media/author/a+b%2F.png')
})

test('externally hosted catalog stickers must match the exact HTTP URL', () => {
  const external = 'https://cdn.example/sticker.gif'
  assert.equal(allowed({ items: [{ ...item, media_url: external }], mediaUrl: external }), true)
  assert.equal(allowed({ items: [{ ...item, media_url: external }], mediaUrl: `${external}?different` }), false)
  assert.equal(allowed({ items: [{ ...item, media_url: 'file:///private' }], mediaUrl: 'file:///private' }), false)
})

test('accessible external catalog stickers can be sent without a local storage row', async () => {
  const external = 'https://cdn.example/sticker.gif'
  const pool = catalogPool({ importedItems: [{ ...item, media_url: external }] })
  const request = { mediaUrl: external, contentType: 'sticker', allowedOwnedBuckets: ['chat-media'] }
  assert.equal(await currentUserMayReferenceMessageMedia(pool, user, identity, request), true)
  assert.equal(await currentUserMayReferenceMessageMedia(pool, user, identity,
    { ...request, mediaUrl: `${external}?different` }), false)
})

test('merged catalog revokes imported public permission when a local pack override is private or deleted', async () => {
  assert.equal(await canReuseCatalogStickerMedia(catalogPool(), identity, mediaUrl), true)
  for (const override of [{ is_public: false }, { is_deleted: true }]) {
    const pool = catalogPool({ packs: [{ ...pack, ...override }] })
    assert.equal(await canReuseCatalogStickerMedia(pool, identity, mediaUrl), false)
  }
  assert.equal(await canReuseCatalogStickerMedia(catalogPool({ items: [{ ...item, deleted: true }] }), identity, mediaUrl), false)
})

for (const [destination, allowedOwnedBuckets] of [
  ['chat', ['chat-media']], ['group', ['group-media', 'groups', 'group-audio']],
  ['community', ['community-media', 'communities']], ['video comment', ['video-comments-media']],
]) {
  test(`${destination}: owned uploads and public pack stickers work, strangers' private packs do not`, async () => {
    const request = { mediaUrl, contentType: 'sticker', allowedOwnedBuckets }
    assert.equal(await currentUserMayReferenceMessageMedia(catalogPool(), user, identity, request), true)
    assert.equal(await currentUserMayReferenceMessageMedia(catalogPool({ owned: true, importedItems: [], importedPacks: [] }), user, identity, request), true)
    assert.equal(await currentUserMayReferenceMessageMedia(catalogPool({ packs: [{ ...pack, is_public: false }] }), user, identity, request), false)
    assert.equal(await currentUserMayReferenceMessageMedia(catalogPool({ importedItems: [], importedPacks: [] }), user, identity, request), false)
    assert.equal(await currentUserMayReferenceMessageMedia(catalogPool({ stored: false }), user, identity, request), false)
  })
}

test('pack insertion uses the same revocation policy; forged media is not laundered through a new pack', async () => {
  const destinationPack = { id: 'my-pack', owner_id: 'viewer', is_public: false }
  const source = { id: 'copy', sticker_id: 'sticker', pack_id: 'my-pack', media_url: mediaUrl }
  const accessible = catalogPool({ packs: [destinationPack] })
  assert.equal((await _test.prepareInsertRow(accessible, 'sticker_pack_items', identity, source)).pack_id, 'my-pack')
  const revoked = catalogPool({ packs: [destinationPack, { ...pack, is_public: false }] })
  await assert.rejects(_test.prepareInsertRow(revoked, 'sticker_pack_items', identity, source), /STICKER_MEDIA_NOT_OWNED/)
})
