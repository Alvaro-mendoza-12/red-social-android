'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')
const { historyPageOptions, conversationHistoryPage, compareHistory } = require('../src/conversation-history-page')

const row = (id, at, extra = {}) => ({ id, created_at: at, content: id, ...extra })
function reader(sources, calls = []) {
  return async (before, limit) => {
    calls.push({ before, limit })
    return sources.map(source => source
      .filter(item => !before || compareHistory(item, row(before.id, before.at)) < 0)
      .sort((a, b) => compareHistory(b, a)).slice(0, limit))
  }
}

test('chronological pages retain exact microseconds and same-time ID ties without gaps', async () => {
  const sources = [[], []]
  for (let index = 0; index < 241; index++) sources[index % 2].push(row(`m-${String(index).padStart(3, '0')}`,
    `2026-10-24T00:00:00.${String(Math.floor(index / 3)).padStart(6, '0')}Z`))
  let options = historyPageOptions({ limit: '80' })
  const received = []
  do {
    const result = await conversationHistoryPage({ read: reader(sources), options, visible: () => true })
    assert.ok(result.messages.length <= 80)
    assert.deepEqual(result.messages, [...result.messages].sort(compareHistory))
    received.unshift(...result.messages)
    options = result.historyPage.hasMore ? historyPageOptions({ limit: '80', before: result.historyPage.nextCursor }) : null
  } while (options)
  assert.deepEqual(received.map(item => item.id), sources.flat().sort(compareHistory).map(item => item.id))
  assert.equal(new Set(received.map(item => item.id)).size, 241)
})

test('hidden rows in an uneven import/local stream cannot skip another source unread interval', async () => {
  const imported = Array.from({ length: 120 }, (_, index) => row(`i-${index}`, new Date(1_000_000 + index * 1000).toISOString(), { hidden: index % 3 !== 0 }))
  const local = [row('ancient', '1970-01-01T00:00:01Z')]
  const received = []
  let options = historyPageOptions({ limit: 8 })
  do {
    const result = await conversationHistoryPage({ read: reader([imported, local]), visible: item => !item.hidden, options })
    received.unshift(...result.messages)
    options = result.historyPage.hasMore ? historyPageOptions({ limit: 8, before: result.historyPage.nextCursor }) : null
  } while (options)
  assert.deepEqual(received, [...imported, ...local].filter(item => !item.hidden).sort(compareHistory))
})

test('local hides and recall tombstones override imported content before visibility and counting', async () => {
  const at = '2026-10-24T00:00:00.123456Z'
  const imported = [row('a', at), row('b', at, { deleted_for_all: true }), row('c', at)]
  const local = [row('a', at, { deleted_by: ['me'] }), row('b', at, { content: 'stale confidential body' })]
  const result = await conversationHistoryPage({ read: reader([imported, local]), options: historyPageOptions({ limit: 2 }),
    visible: item => !item.deleted_by?.includes('me') })
  assert.deepEqual(result.messages.map(item => item.id), ['b', 'c'])
  assert.equal(result.messages[0].content, '')
  assert.equal(result.messages[0].deleted_for_all, true)
  assert.equal(result.historyPage.hasMore, false)
})

test('retained membership ranges are applied before a page is returned', async () => {
  const source = Array.from({ length: 40 }, (_, index) => row(`id-${index}`, new Date(1_000_000 + index * 1000).toISOString()))
  const visible = item => Date.parse(item.created_at) >= 1_010_000 && Date.parse(item.created_at) <= 1_020_000
  const result = await conversationHistoryPage({ read: reader([source]), options: historyPageOptions({ limit: 80 }), visible })
  assert.equal(result.messages.length, 11)
  assert.ok(result.messages.every(visible))
})

test('legacy clients retain their full-history contract and malformed pagination fails closed', () => {
  assert.equal(historyPageOptions({}), null)
  for (const query of [{ limit: 0 }, { limit: 201 }, { limit: 1.5 }, { limit: ['80', '40'] }, { before: 'invalid' }, { before: ['x'] }]) {
    assert.throws(() => historyPageOptions(query), error => error.status === 400)
  }
})
