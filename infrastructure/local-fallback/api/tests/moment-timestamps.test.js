'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { momentTimestamp } = require('../src/moment-timestamps')

test('native database dates retain the exact UTC instant including milliseconds', () => {
  assert.equal(momentTimestamp(new Date('2026-09-17T12:34:56.789Z')), '2026-09-17T12:34:56.789Z')
})

test('ISO and PostgreSQL offset strings normalize without depending on server timezone', () => {
  for (const input of ['2026-09-17T12:34:56Z', '2026-09-17 12:34:56+00',
    '2026-09-17T07:34:56-05:00', '2026-09-17T07:34:56-0500']) {
    assert.equal(momentTimestamp(input), '2026-09-17T12:34:56.000Z')
  }
  assert.equal(momentTimestamp('2026-09-17T12:34:56.123456Z'), '2026-09-17T12:34:56.123Z')
})

test('unknown dates never fabricate a fresh publication time', () => {
  for (const input of [null, undefined, '', ' ', 0, false, {}, 'yesterday', new Date(NaN),
    '2026-09-17T12:34:56', '2026-09-17', '2026-02-30T12:00:00Z', '2026-09-17T12:00:00+25:00']) {
    assert.equal(momentTimestamp(input), '')
  }
})

test('offsets crossing a year boundary retain the actual date', () => {
  assert.equal(momentTimestamp('2026-01-01T00:30:00+01:00'), '2025-12-31T23:30:00.000Z')
})
