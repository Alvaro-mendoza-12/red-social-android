'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const { agreementPageQuery, encodeAgreementCursor, readAgreementPage } = require('../src/chat-agreements-pages')
const chat = '11111111-1111-4111-8111-111111111111', other = '22222222-2222-4222-8222-222222222222'
const row = { id: other, position_time: '2026-11-04T08:00:00.123456Z', state: { id: other } }
const encode = object => Buffer.from(JSON.stringify(object)).toString('base64url')

test('page positions retain microseconds, direction and the chat/filter binding', () => {
  for (const archived of [false, true]) for (const direction of ['older', 'newer']) {
    const cursor = encodeAgreementCursor(chat, archived, row, direction)
    const parsed = agreementPageQuery({ archived: String(archived), limit: '25', cursor }, chat)
    assert.equal(parsed.boundary.at, row.position_time)
    assert.equal(parsed.boundary.direction, direction)
    assert.equal(parsed.limit, 25)
    assert.throws(() => agreementPageQuery({ archived: String(!archived), cursor }, chat), /AGREEMENT_INVALID_CURSOR/)
    assert.throws(() => agreementPageQuery({ archived: String(archived), cursor }, other), /AGREEMENT_INVALID_CURSOR/)
  }
  assert.equal(agreementPageQuery({}, chat).limit, 50)
})

test('malformed, ambiguous or oversized page parameters are rejected before SQL', () => {
  for (const query of [{ limit: '0' }, { limit: '51' }, { limit: ['25'] }, { archived: true }, { archived: 'TRUE' },
    { focus: 'bad' }, { extra: 'anything' }, { cursor: null }, { cursor: '' }, { cursor: 'x'.repeat(513) }, { cursor: 'e30=' }]) {
    assert.throws(() => agreementPageQuery(query, chat), /AGREEMENT_INVALID_(PAGE|CURSOR)/)
  }
  const valid = JSON.parse(Buffer.from(encodeAgreementCursor(chat, false, row, 'older'), 'base64url'))
  for (const change of [{ id: null }, { id: 'BAD' }, { at: '2026-02-30T08:00:00.123456Z' }, { at: '2026-11-04T08:00:00.123Z' },
    { at: '0000-11-04T08:00:00.123456Z' }, { v: 2 }, { direction: 'sideways' }, { extra: 1 }]) {
    assert.throws(() => agreementPageQuery({ cursor: encode({ ...valid, ...change }) }, chat), /AGREEMENT_INVALID_CURSOR/)
  }
})

test('a vanished boundary resets to the newest bounded page instead of trapping the reader', async () => {
  const queries = []
  const db = { query: async (sql, args) => {
    queries.push({ sql, args })
    if (sql.includes('EXISTS')) return { rows: [{ newer: false, older: false }] }
    return { rows: queries.length === 1 ? [] : [row] }
  } }
  const page = await readAgreementPage(db, chat, agreementPageQuery({ cursor: encodeAgreementCursor(chat, false, row, 'older'), limit: '25' }, chat))
  assert.equal(page.reset, true); assert.equal(page.cursor, null)
  assert.deepEqual(page.states, [row.state]); assert.equal(page.nextCursor, null)
  assert.equal(queries[0].args.at(-1), 26)
  assert.ok(queries[0].sql.includes('(created_at,id) <'))
  assert.ok(!queries[1].sql.includes('(created_at,id) <'))
})
