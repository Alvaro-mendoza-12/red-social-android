'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { buildMomentsFeed } = require('../src/moments-routes')

function feedPool(extra) {
  return {
    async query(sql) {
      return {
        rows: /FROM fallback_moments\b/.test(sql) ? [{
          id: 'fixture-moment', user_id: 'owner', media_url: 'https://example.invalid/fixture.mp4',
          media_type: 'video', visibility: 'all', visibility_type: 'public', ...extra,
        }] : [],
      }
    },
  }
}

test('moment feed emits ISO timestamps for PostgreSQL Date objects, not JavaScript display strings', async () => {
  const created = new Date('2026-09-17T12:34:56.789Z')
  const expires = new Date(Date.now() + 86_400_000)
  const feed = await buildMomentsFeed(feedPool({ created_at: created, expires_at: expires }), { ids: new Set(['viewer']) })
  assert.equal(feed.length, 1)
  assert.equal(feed[0].created_at, created.toISOString())
  assert.equal(feed[0].expires_at, expires.toISOString())
})

test('missing publication date is not falsely replaced by the current time', async () => {
  const feed = await buildMomentsFeed(feedPool({}), { ids: new Set(['viewer']) })
  assert.equal(feed.length, 1)
  assert.equal(feed[0].created_at, '')
})

test('expired and malformed expirations do not become permanently visible moments', async () => {
  for (const expires_at of [new Date(0), 'not-a-date', new Date(NaN), '2026-02-30T10:00:00Z']) {
    const feed = await buildMomentsFeed(feedPool({ expires_at }), { ids: new Set(['viewer']) })
    assert.deepEqual(feed, [])
  }
})

test('offset publication timestamps preserve the instant', async () => {
  const feed = await buildMomentsFeed(feedPool({ created_at: '2026-09-17 07:34:56-05' }), { ids: new Set(['viewer']) })
  assert.equal(feed[0].created_at, '2026-09-17T12:34:56.000Z')
})
