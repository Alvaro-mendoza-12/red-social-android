'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { candidateFitsCriteria, candidateBundles } = require('../src/playmatch-routes')
const { normalizeCandidateCriteria } = require('../src/playmatch-candidate-constraints')

const candidate = {
  profile: { languages: ['es'], region_code: 'latam', play_style: 'casual', voice_preference: false },
  games: [
    { game_id: 'valorant', platform: 'pc', role_key: 'duelist' },
    { game_id: 'fortnite', platform: 'playstation', role_key: 'support' },
  ],
}

test('game and platform must belong to the same user-game entry', () => {
  assert.equal(candidateFitsCriteria(candidate, { gameIds: ['valorant'], platforms: ['playstation'] }), false)
  assert.equal(candidateFitsCriteria(candidate, { gameIds: ['valorant'], platforms: ['pc'] }), true)
})

test('a role in another game cannot satisfy the selected game', () => {
  assert.equal(candidateFitsCriteria(candidate, { gameIds: ['valorant'], roles: ['support'] }), false)
  assert.equal(candidateFitsCriteria(candidate, { platforms: ['pc'], roles: ['support'] }), false)
  assert.equal(candidateFitsCriteria(candidate, { gameIds: ['valorant'], platforms: ['pc'], roles: ['duelist'] }), true)
})

test('discovery singular fields and session arrays express the same constraints', () => {
  assert.equal(candidateFitsCriteria(candidate, { gameId: 'valorant', platform: 'playstation' }), false)
  assert.equal(candidateFitsCriteria(candidate, { gameId: 'valorant', platform: 'PC' }), true)
  assert.equal(candidateFitsCriteria(candidate, { game_ids: ['VALORANT'], platforms: ['PC'] }), true)
})

test('multiple games are alternatives but every mandatory field must match one entry', () => {
  assert.equal(candidateFitsCriteria(candidate, {
    gameIds: ['valorant', 'fortnite'], platforms: ['playstation'], roles: ['support'],
  }), true)
  assert.equal(candidateFitsCriteria(candidate, {
    gameIds: ['valorant', 'fortnite'], platforms: ['playstation'], roles: ['duelist'],
  }), false)
})

test('language, region, play style and microphone are mandatory when requested', () => {
  assert.equal(candidateFitsCriteria(candidate, { languages: ['en'] }), false)
  assert.equal(candidateFitsCriteria(candidate, { region: 'europe' }), false)
  assert.equal(candidateFitsCriteria(candidate, { styles: ['competitive'] }), false)
  assert.equal(candidateFitsCriteria(candidate, { voiceRequired: true }), false)
  assert.equal(candidateFitsCriteria(candidate, { languages: ['ES'], regions: ['LATAM'], styles: ['CASUAL'] }), true)
})

test('explicit platform is not relaxed merely because a different game supports cross-play', () => {
  const crossPlay = { ...candidate, games: candidate.games.map((game) => ({ ...game, cross_play: true })) }
  assert.equal(candidateFitsCriteria(crossPlay, { gameIds: ['valorant'], platforms: ['playstation'] }), false)
})

test('unfiltered discovery does not require a game while a game constraint does', () => {
  assert.equal(candidateFitsCriteria({ profile: {}, games: [] }, {}), true)
  assert.equal(candidateFitsCriteria({ profile: {}, games: [] }, { platforms: ['pc'] }), false)
})

test('normalization is shared, bounded, case-insensitive and does not treat false text as true', () => {
  assert.deepEqual(normalizeCandidateCriteria({ gameId: ' VALORANT ', platform: 'PC', region: 'LATAM' }), {
    gameIds: ['valorant'], platforms: ['pc'], regions: ['latam'], roles: [], languages: [], styles: [], voiceRequired: false,
  })
  assert.equal(normalizeCandidateCriteria({ gameIds: Array.from({ length: 40 }, (_, i) => `game-${i}`) }).gameIds.length, 12)
  assert.deepEqual(normalizeCandidateCriteria({ roles: [' Support ', 'support', '', null] }).roles, ['support'])
  assert.equal(normalizeCandidateCriteria({ voiceRequired: 'false' }).voiceRequired, false)
  assert.equal(candidateFitsCriteria(candidate, { voice_required: true }), false)
})

test('SQL receives every normalized mandatory filter before its bounded candidate limit', async () => {
  let query
  const db = { query: async (sql, params) => { query = { sql, params }; return { rows: [] } } }
  await candidateBundles(db, { id: '11111111-1111-4111-8111-111111111111' }, {
    gameIds: ['VALORANT'], platforms: ['PC'], roles: ['Duelist'], languages: ['ES'],
    regions: ['LATAM'], styles: ['Competitive'], voiceRequired: true, excludeRecentMatches: true,
  })
  assert.deepEqual(query.params.slice(2), [
    ['latam'], ['11111111-1111-4111-8111-111111111111'], ['valorant'], ['pc'], true,
    ['duelist'], ['es'], ['competitive'], true,
  ])
  assert.match(query.sql, /JOIN fallback_game_catalog gc ON gc.id = ug.game_id AND gc.active = TRUE/)
  assert.match(query.sql, /ug.game_id[\s\S]+ug.platform[\s\S]+ug.role_key[\s\S]+LIMIT 250/)
  assert.match(query.sql, /gp.voice_preference = TRUE[\s\S]+LIMIT 250/)
  assert.match(query.sql, /pm.is_suspended = TRUE/)
  assert.match(query.sql, /lower\(ub.blocked_id\) = ANY\(\$4::text\[\]\)/)
})
