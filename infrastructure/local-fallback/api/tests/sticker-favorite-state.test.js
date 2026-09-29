const test = require('node:test')
const assert = require('node:assert/strict')
const { _test } = require('../src/postgrest-compat-routes')

test('explicit sticker removal stays removed when the request is repeated', () => {
  assert.equal(_test.desiredStickerFavoriteState({ id: 'saved' }, false), false)
  assert.equal(_test.desiredStickerFavoriteState(null, false), false)
})

test('explicit sticker add stays saved when the request is repeated', () => {
  assert.equal(_test.desiredStickerFavoriteState(null, true), true)
  assert.equal(_test.desiredStickerFavoriteState({ id: 'saved' }, true), true)
})

test('older clients retain toggle behavior', () => {
  assert.equal(_test.desiredStickerFavoriteState(null, undefined), true)
  assert.equal(_test.desiredStickerFavoriteState({ id: 'saved' }, undefined), false)
})

function favoritePool({ owned = true } = {}) {
  const tables = new Map()
  const pool = { tables, query: async (sql, params = []) => {
    if (sql.includes('AS local_user_id')) return { rows: [{ local_user_id: params[0] }] }
    if (sql.includes('to_regclass')) return { rows: [] }
    if (sql.includes('INSERT INTO fallback_compat_rows')) {
      const rows = tables.get(params[0]) || new Map()
      rows.set(params[1], params[2]); tables.set(params[0], rows)
      return { rows: [] }
    }
    if (sql.includes('DELETE FROM fallback_compat_rows')) {
      tables.get('user_sticker_favorites')?.delete(params[0]); return { rows: [] }
    }
    if (sql.includes('FROM fallback_compat_rows')) return {
      rows: [...(tables.get(params[0])?.values() || [])].map(row => ({ row })),
    }
    if (sql.includes('FROM fallback_storage_objects')) return { rows: owned ? [{}] : [] }
    if (sql.includes('SELECT id::text AS id FROM fallback_messages')) return { rows: [] }
    throw new Error(`Unexpected favorite query: ${sql.slice(0, 90)}`)
  } }
  return pool
}

const favoriteRequest = {
  p_sticker_id: 'message_saved', p_favorite: true,
  p_media_url: 'https://current.example/storage/v1/object/public/chat-media/author/cat.webp',
  p_title: 'Gato', p_is_animated: true,
}

test('a saved message sticker is registered once in a private pack, then removed idempotently', async () => {
  const pool = favoritePool()
  for (let attempt = 0; attempt < 2; attempt++) {
    assert.equal((await _test.toggleStickerFavoriteRpc(pool, { sub: 'viewer' }, favoriteRequest)).is_favorite, true)
  }
  assert.equal(pool.tables.get('sticker_pack_items').size, 1)
  assert.equal(pool.tables.get('user_sticker_favorites').size, 1)
  assert.equal([...pool.tables.get('sticker_packs').values()][0].is_public, false)
  // Even losing access to the source must not prevent removal of one's saved choice.
  pool.tables.get('sticker_pack_items').clear()
  for (let attempt = 0; attempt < 2; attempt++) {
    assert.equal((await _test.toggleStickerFavoriteRpc(pool, { sub: 'viewer' }, {
      p_sticker_id: favoriteRequest.p_sticker_id, p_favorite: false,
    })).is_favorite, false)
  }
  assert.equal(pool.tables.get('user_sticker_favorites').size, 0)
})

test('two users saving the same sticker keep separate private catalog entries', async () => {
  const pool = favoritePool()
  await _test.toggleStickerFavoriteRpc(pool, { sub: 'viewer-a' }, favoriteRequest)
  await _test.toggleStickerFavoriteRpc(pool, { sub: 'viewer-b' }, favoriteRequest)
  assert.equal(pool.tables.get('sticker_pack_items').size, 2)
  assert.equal(pool.tables.get('user_sticker_favorites').size, 2)
})

test('an arbitrary media URL cannot be promoted to a saved sticker', async () => {
  const pool = favoritePool({ owned: false })
  await assert.rejects(_test.toggleStickerFavoriteRpc(pool, { sub: 'viewer' }, favoriteRequest), /STICKER_MEDIA_NOT_OWNED/)
  assert.equal(pool.tables.get('sticker_pack_items')?.size || 0, 0)
})
