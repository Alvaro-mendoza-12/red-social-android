'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { publicFeedPage } = require('../src/public-feed-page')
const order = rows => rows.sort((a, b) => b.rank - a.rank)
const visible = row => row.visible !== false
const candidates = Array.from({ length: 650 }, (_, i) => ({ id: `post-${i}`, rank: 650-i,
  visible: i >= 270 }))

test('privacy filtering does not strand discovery on a short candidate window', async () => {
  const windows = []
  const page = await publicFeedPage({ read: async limit => {
    windows.push(limit); return [candidates.slice(0, limit), []]
  }, visible, order, offset: 0, limit: 60 })
  assert.deepEqual(windows, [240, 480])
  assert.equal(page.items.length, 60)
  assert.equal(page.items[0].id, 'post-270')
  assert.equal(page.nextOffset, 60)
  assert.equal(page.hasMore, true)
})

test('a continuation returns the following visible rows without overlap', async () => {
  const page = await publicFeedPage({ read: async limit => [candidates.slice(0, limit), []],
    visible, order, offset: 60, limit: 60 })
  assert.equal(page.items[0].id, 'post-330')
  assert.equal(page.nextOffset, 120)
})

test('current local privacy supersedes duplicate imported public data', async () => {
  const page = await publicFeedPage({ read: async () => [
    [{ id: 'same', rank: 3, visible: true }, { id: 'other', rank: 1 }],
    [{ id: 'same', rank: 3, visible: false }]], visible, order, offset: 0, limit: 60 })
  assert.deepEqual(page.items.map(row => row.id), ['other'])
  assert.equal(page.nextOffset, null)
  assert.equal(page.hasMore, false)
})

test('duplicate sources do not consume page slots or produce duplicate row keys', async () => {
  const rows = [{ id: 'a', rank: 2 }, { id: 'b', rank: 1 }]
  const page = await publicFeedPage({ read: async () => [rows, rows], visible, order, offset: 0, limit: 60 })
  assert.deepEqual(page.items.map(row => row.id), ['a', 'b'])
  assert.equal(page.hasMore, false)
})

test('candidate reads stop at their bounded maximum', async () => {
  const windows = []
  const page = await publicFeedPage({ read: async limit => {
    windows.push(limit); return [candidates.slice(0, limit)]
  }, visible, order, offset: 0, limit: 60, maxCandidates: 300 })
  assert.deepEqual(windows, [240, 300])
  assert.equal(page.candidateLimitReached, true)
  assert.equal(page.items.length, 30)
})
