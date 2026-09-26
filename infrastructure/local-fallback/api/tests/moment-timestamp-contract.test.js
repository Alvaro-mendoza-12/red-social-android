'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { timestampSummary } = require('../scripts/verify-moment-feed-timestamps')

test('timestamp diagnostic distinguishes missing data, invalid strings and UTC timestamps without logging content', () => {
  assert.deepEqual(timestampSummary([
    { created_at: '2026-09-17T12:34:56.789Z', expires_at: '2026-09-18T12:34:56.789Z' },
    { created_at: '', expires_at: '' },
    { created_at: 'Thu Sep 17 2026', expires_at: '2026-02-30T00:00:00.000Z' },
  ]), { items: 3, publicationMissing: 1, malformedPublication: 1, malformedExpiry: 1 })
})

test('empty feed is counted as zero checked rows, not fabricated publication dates', () => {
  assert.deepEqual(timestampSummary([]), { items: 0, publicationMissing: 0, malformedPublication: 0, malformedExpiry: 0 })
})
