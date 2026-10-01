const { test } = require('node:test')
const assert = require('node:assert/strict')
const { rankPublicProfiles } = require('../src/data-routes')

test('own position can be computed outside top 100 while the published list remains bounded', () => {
  const profiles = Array.from({ length: 301 }, (_, index) => ({ id: `user-${String(index).padStart(3, '0')}`, username: `person${index}`, full_name: 'Persona', updated_at: '2026-09-30T00:00:00Z' }))
  const args = { profiles, follows: [], posts: [], limit: 100 }
  assert.equal(rankPublicProfiles(args).length, 100)
  const all = rankPublicProfiles({ ...args, includeAll: true })
  assert.equal(all.length, 301)
  assert.equal(all.findIndex((item) => item.id === 'user-300') + 1, 301)
})

test('hidden profiles cannot appear in rankings or inflate a public position', () => {
  const all = rankPublicProfiles({ profiles: [{ id: 'hidden', break_mode: 'hidden' }, { id: 'visible' }], follows: [], posts: [], includeAll: true })
  assert.deepEqual(all.map((item) => item.id), ['visible'])
})
