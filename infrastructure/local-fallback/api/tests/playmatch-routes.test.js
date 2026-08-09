const assert = require('assert')
const fs = require('fs')
const path = require('path')

const {
  availabilityOverlapMinutes,
  candidateFitsCriteria,
  computeCompatibility,
  decodeCursor,
  encodeCursor,
  ensurePlayMatchTables,
  playMatchEnabledFor,
  registerPlayMatchRoutes,
} = require('../src/playmatch-routes')

function withEnv(values, work) {
  const previous = {}
  for (const [key, value] of Object.entries(values)) {
    previous[key] = process.env[key]
    if (value == null) delete process.env[key]
    else process.env[key] = value
  }
  try {
    return work()
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value == null) delete process.env[key]
      else process.env[key] = value
    }
  }
}

async function main() {
  withEnv({ PLAYMATCH_GAMES_ENABLED: 'false', PLAYMATCH_TESTER_IDS: 'User-A,user-b' }, () => {
    assert.equal(playMatchEnabledFor({ values: ['USER-A'] }), true)
    assert.equal(playMatchEnabledFor({ values: ['outsider'] }), false)
  })
  withEnv({ PLAYMATCH_GAMES_ENABLED: 'true', PLAYMATCH_TESTER_IDS: '' }, () => {
    assert.equal(playMatchEnabledFor({ values: ['any-user'] }), true)
  })

  withEnv({ PLAYMATCH_CURSOR_SECRET: 'test-only-secret-with-sufficient-entropy' }, () => {
    const cursor = encodeCursor(37)
    assert.equal(decodeCursor(cursor), 37)
    assert.throws(() => decodeCursor(`${cursor.slice(0, -1)}x`), /INVALID_CURSOR/)
  })

  const overlap = availabilityOverlapMinutes(
    [{ day_of_week: 1, start_minute: 1080, end_minute: 1200, timezone: 'UTC' }],
    [{ day_of_week: 1, start_minute: 780, end_minute: 840, timezone: 'America/Bogota' }],
  )
  assert.equal(overlap, 60, 'normaliza zonas horarias antes de calcular disponibilidad')
  assert.equal(
    availabilityOverlapMinutes(
      [{ day_of_week: 6, start_minute: 1380, end_minute: 60, timezone: 'UTC' }],
      [{ day_of_week: 0, start_minute: 30, end_minute: 90, timezone: 'UTC' }],
    ),
    30,
    'soporta intervalos que cruzan medianoche y fin de semana',
  )

  const viewer = {
    profile: { play_style: 'competitive', languages: ['es'], region_code: 'latam' },
    preferences: { styles: ['competitive'], languages: ['es'], regions: ['latam'] },
    games: [
      { game_id: 'valorant', game_name: 'Valorant', platform: 'PC', rank_key: 'gold', role_key: 'duelist', cross_play: false },
      { game_id: 'fortnite', platform: 'PlayStation', cross_play: true },
    ],
    availability: [{ day_of_week: 2, start_minute: 1080, end_minute: 1200, timezone: 'UTC' }],
    privateMessages: ['esta señal nunca debe utilizarse'],
    age: 19,
    gender: 'hidden',
  }
  const candidate = {
    profile: { play_style: 'competitive', languages: ['es'], region_code: 'latam' },
    games: [
      { game_id: 'valorant', platform: 'PC', rank_key: 'gold', role_key: 'support', cross_play: false },
      { game_id: 'minecraft', platform: 'PlayStation', cross_play: true },
    ],
    availability: [{ day_of_week: 2, start_minute: 1140, end_minute: 1260, timezone: 'UTC' }],
    privateMessages: ['otra señal prohibida'],
    age: 99,
    gender: 'other',
  }
  const compatibility = computeCompatibility(viewer, candidate)
  assert.equal(compatibility.score, 100)
  assert.deepEqual(
    compatibility.reasons.map((reason) => reason.code),
    ['COMMON_GAME', 'PLATFORM', 'RANK', 'ROLE', 'STYLE', 'AVAILABILITY', 'LANGUAGE', 'REGION'],
  )
  assert(!compatibility.reasons.some((reason) => ['PRIVATE_MESSAGES', 'AGE', 'GENDER', 'PRECISE_LOCATION'].includes(reason.code)))

  const unrelatedPlatform = computeCompatibility(
    { profile: {}, games: [{ game_id: 'a', platform: 'PC' }], availability: [] },
    { profile: {}, games: [{ game_id: 'b', platform: 'PC' }], availability: [] },
  )
  assert(!unrelatedPlatform.reasons.some((reason) => reason.code === 'PLATFORM'))
  assert.equal(candidateFitsCriteria(candidate, { gameIds: ['valorant'], platforms: ['PC'], roles: ['support'], voiceRequired: false }), true)
  assert.equal(candidateFitsCriteria(candidate, { gameIds: ['minecraft'], platforms: ['Mobile'] }), false)
  assert.equal(candidateFitsCriteria(candidate, { voiceRequired: true }), false)

  const registered = []
  const app = {}
  for (const verb of ['get', 'post', 'put', 'patch', 'delete']) {
    app[verb] = (path) => registered.push(`${verb.toUpperCase()} ${path}`)
  }
  registerPlayMatchRoutes(app, {
    pool: {},
    authRequired: () => {},
    getCurrentUser: async () => null,
    userIdentity: () => ({ values: [] }),
  })
  for (const route of [
    'GET /v1/data/playmatch/capabilities',
    'GET /v1/data/playmatch/catalog',
    'GET /v1/data/playmatch/profile',
    'POST /v1/data/playmatch/matchmaking/sessions',
    'POST /v1/data/playmatch/matches/:matchId/decision',
  ]) {
    assert(registered.includes(route), `falta registrar ${route}`)
  }

  const statements = []
  await ensurePlayMatchTables({ query: async (sql) => { statements.push(sql); return { rows: [] } } })
  const ddl = statements.join('\n')
  for (const table of [
    'fallback_game_catalog',
    'fallback_gaming_profiles',
    'fallback_user_games',
    'fallback_gaming_availability',
    'fallback_gaming_preferences',
    'fallback_gaming_connection_requests',
    'fallback_matchmaking_sessions',
    'fallback_gaming_matches',
    'fallback_gaming_match_decisions',
  ]) {
    assert(ddl.includes(table), `falta asegurar tabla ${table}`)
  }
  const incompatibleUserReference = /(?:^|\s)(?:user_id|requester_user_id|recipient_user_id|user_a_id|user_b_id)\s+TEXT[^,\n]*REFERENCES\s+fallback_users\(id\)/i
  assert(!incompatibleUserReference.test(ddl), 'DDL runtime debe usar UUID para FK hacia fallback_users.id')
  assert(/user_id\s+UUID\s+PRIMARY KEY REFERENCES fallback_users\(id\)/i.test(ddl))

  const migration = fs.readFileSync(
    path.resolve(__dirname, '../../db/migrations/012_playmatch_games_v1.sql'),
    'utf8',
  )
  assert(!incompatibleUserReference.test(migration), 'migración debe usar UUID para FK hacia fallback_users.id')
  assert(/requester_user_id\s+UUID\s+NOT NULL REFERENCES fallback_users\(id\)/i.test(migration))

  console.log('playmatch routes tests passed')
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
