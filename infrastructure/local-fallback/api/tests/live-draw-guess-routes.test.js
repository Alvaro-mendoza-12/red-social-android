const assert = require('assert')
const fs = require('fs')
const path = require('path')
const {
  LIVE_DRAW_GUESS_SCHEMA_SQL,
  SAFE_PROMPTS,
  calculateGuessScore,
  chooseDrawer,
  choosePrompt,
  createDrawGuessRateLimiters,
  drawGuessEnabled,
  ensureLiveDrawGuessTables,
  evaluateGuess,
  identityOwns,
  publicRound,
  registerLiveDrawGuessRoutes,
  requireDrawGuessFeature,
  sanitizePublicEvent,
  serializeGameState,
  startLiveDrawGuessReconciler,
  validateDrawPacket,
  validateGuessText,
  validIdempotencyKey,
} = require('../src/live-draw-guess-routes')

const LIVE_ID = '11111111-1111-4111-8111-111111111111'
const GAME_ID = '22222222-2222-4222-8222-222222222222'
const ROUND_ID = '33333333-3333-4333-8333-333333333333'

function withFlag(value, callback) {
  const previous = process.env.LIVE_DRAW_GUESS_ENABLED
  if (value === undefined) delete process.env.LIVE_DRAW_GUESS_ENABLED
  else process.env.LIVE_DRAW_GUESS_ENABLED = value
  try {
    return callback()
  } finally {
    if (previous === undefined) delete process.env.LIVE_DRAW_GUESS_ENABLED
    else process.env.LIVE_DRAW_GUESS_ENABLED = previous
  }
}

function fakeResponse() {
  return {
    statusCode: 200,
    body: null,
    headers: {},
    status(code) {
      this.statusCode = code
      return this
    },
    json(body) {
      this.body = body
      return this
    },
    set(name, value) {
      this.headers[String(name).toLowerCase()] = String(value)
      return this
    },
    setHeader(name, value) {
      this.headers[String(name).toLowerCase()] = String(value)
    },
  }
}

async function testFeatureFlagIsFailClosed() {
  withFlag(undefined, () => assert.strictEqual(drawGuessEnabled(), false))
  withFlag('false', () => assert.strictEqual(drawGuessEnabled(), false))
  withFlag('garbage', () => assert.strictEqual(drawGuessEnabled(), false))
  withFlag('true', () => assert.strictEqual(drawGuessEnabled(), true))

  const response = fakeResponse()
  withFlag(undefined, () => assert.strictEqual(requireDrawGuessFeature(response), false))
  assert.strictEqual(response.statusCode, 404)
  assert.deepStrictEqual(response.body, { error: 'LIVE_DRAW_GUESS_DISABLED' })

  const disabledWorker = withFlag(undefined, () => startLiveDrawGuessReconciler({ pool: {} }))
  assert.strictEqual(disabledWorker.started, false)
  disabledWorker.stop()
  const enabledWorker = withFlag('true', () => startLiveDrawGuessReconciler({ pool: {}, intervalMs: 10_000 }))
  assert.strictEqual(enabledWorker.started, true)
  enabledWorker.stop()
}

async function testSchemaIsAdditiveIdempotentAndPrivacyPreserving() {
  const statements = []
  await ensureLiveDrawGuessTables({
    async query(sql) {
      statements.push(String(sql))
      return { rows: [] }
    },
  })
  assert.strictEqual(statements.length, 1)
  const schema = statements[0]
  for (const table of [
    'fallback_live_draw_games',
    'fallback_live_draw_players',
    'fallback_live_draw_rounds',
    'fallback_live_draw_guesses',
    'fallback_live_draw_audit',
  ]) {
    assert.match(schema, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}`))
  }
  assert.match(schema, /UNIQUE \(live_id, request_nonce\)/)
  assert.match(schema, /UNIQUE \(game_id, user_id, idempotency_key\)/)
  assert.match(schema, /idx_fallback_live_draw_one_correct_guess/)
  assert.match(schema, /WHERE correct = TRUE/)
  assert.match(schema, /FOREIGN KEY \(game_id, drawer_user_id\)/)
  assert.match(schema, /FOREIGN KEY \(game_id, round_id\)/)
  assert.match(schema, /FOREIGN KEY \(game_id, user_id\)/)
  assert.match(schema, /metadata \?\| ARRAY\['prompt', 'prompt_key', 'guess', 'answer'\]/)
  assert.doesNotMatch(schema, /guess_text|answer_text|draw_points|stroke_payload/)

  const migration = fs.readFileSync(
    path.resolve(__dirname, '../../db/migrations/007_live_draw_guess.sql'),
    'utf8',
  )
  assert.match(migration, /LIVE_DRAW_GUESS_ENABLED=true/)
  assert.match(migration, /raw guesses and[\s\S]*drawing points are intentionally not persisted/i)
  assert.match(migration, /CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_draw_one_open_game/)
  assert.match(migration, /CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_draw_one_active_round/)
  assert.match(migration, /CHECK \(NOT \(metadata \?\| ARRAY\['prompt', 'prompt_key', 'guess', 'answer'\]\)\)/)
}

async function testPromptCatalogIsServerOwnedAndSafe() {
  assert.ok(SAFE_PROMPTS.length >= 20)
  assert.strictEqual(new Set(SAFE_PROMPTS.map((prompt) => prompt.key)).size, SAFE_PROMPTS.length)
  for (const prompt of SAFE_PROMPTS) {
    assert.match(prompt.key, /^[a-z]+$/)
    assert.ok(prompt.label.length >= 2)
    assert.ok(prompt.answers.length >= 1)
    for (const answer of prompt.answers) {
      const validation = validateGuessText(answer)
      assert.strictEqual(validation.ok, true, `${prompt.key} must be a safe prompt`)
      assert.strictEqual(evaluateGuess(prompt.key, answer).correct, true)
    }
  }
  assert.strictEqual(evaluateGuess('colibri', 'COLIBRÍ').correct, true)
  assert.strictEqual(evaluateGuess('bicicleta', 'bici').correct, true)
  assert.strictEqual(evaluateGuess('ballena', 'tren').correct, false)
  assert.strictEqual(evaluateGuess('client-controlled-word', 'anything').error, 'LIVE_DRAW_PROMPT_INVALID')

  const deterministic = choosePrompt(['arcoiris'], () => 0)
  assert.notStrictEqual(deterministic.key, 'arcoiris')
}

async function testGuessModerationBlocksPiiAndUnsafeText() {
  assert.strictEqual(validateGuessText('').error, 'LIVE_DRAW_GUESS_TEXT_INVALID')
  assert.strictEqual(validateGuessText('x'.repeat(73)).error, 'LIVE_DRAW_GUESS_TEXT_INVALID')
  assert.strictEqual(validateGuessText('persona@example.test').error, 'LIVE_DRAW_GUESS_PII_BLOCKED')
  assert.strictEqual(validateGuessText('https://example.test/perfil').error, 'LIVE_DRAW_GUESS_PII_BLOCKED')
  assert.strictEqual(validateGuessText('@cuenta_privada').error, 'LIVE_DRAW_GUESS_PII_BLOCKED')
  assert.strictEqual(validateGuessText('987 654 321').error, 'LIVE_DRAW_GUESS_PII_BLOCKED')
  assert.strictEqual(validateGuessText('eres un idiota').error, 'LIVE_DRAW_GUESS_MODERATION_BLOCKED')
  assert.strictEqual(
    validateGuessText('un spoiler final', ['spoiler']).error,
    'LIVE_DRAW_GUESS_MODERATION_BLOCKED',
  )
  assert.strictEqual(validateGuessText('parasol', ['sol']).ok, true)
  assert.deepStrictEqual(
    Object.keys(validateGuessText('Ballena')).sort(),
    ['answerLength', 'normalized', 'ok'].sort(),
  )
}

async function testIdempotencyKeysAreBoundedAndOpaque() {
  assert.strictEqual(validIdempotencyKey('1234567890abcdef'), '1234567890abcdef')
  assert.strictEqual(validIdempotencyKey('request:game-0001'), 'request:game-0001')
  assert.strictEqual(validIdempotencyKey('short'), null)
  assert.strictEqual(validIdempotencyKey('x'.repeat(161)), null)
  assert.strictEqual(validIdempotencyKey('1234567890 abcdef'), null)
}

async function testLossyDrawPacketValidation() {
  const valid = validateDrawPacket({
    sequence: 42,
    tool: 'pen',
    color: '#1e88e5',
    width: 0.01234,
    points: [
      { x: 0, y: 1, pressure: 0.5, deltaMs: 0 },
      { x: 0.123456, y: 0.654321, pressure: 1, deltaMs: 16 },
    ],
  })
  assert.strictEqual(valid.ok, true)
  assert.strictEqual(valid.packet.sequence, 42)
  assert.strictEqual(valid.packet.points[1].x, 0.1235)
  assert.strictEqual(valid.packet.points[1].y, 0.6543)
  assert.strictEqual(valid.packet.width, 0.0123)

  assert.strictEqual(validateDrawPacket(null).error, 'LIVE_DRAW_PACKET_INVALID')
  assert.strictEqual(validateDrawPacket({ sequence: -1, points: [{ x: 0, y: 0 }] }).error, 'LIVE_DRAW_SEQUENCE_INVALID')
  assert.strictEqual(validateDrawPacket({ sequence: 1, tool: 'spray', points: [{ x: 0, y: 0 }] }).error, 'LIVE_DRAW_STYLE_INVALID')
  assert.strictEqual(validateDrawPacket({ sequence: 1, color: '#abcdef', points: [{ x: 0, y: 0 }] }).error, 'LIVE_DRAW_STYLE_INVALID')
  assert.strictEqual(validateDrawPacket({ sequence: 1, points: [{ x: 1.01, y: 0 }] }).error, 'LIVE_DRAW_POINT_INVALID')
  assert.strictEqual(validateDrawPacket({ sequence: 1, points: Array.from({ length: 65 }, () => ({ x: 0, y: 0 })) }).error, 'LIVE_DRAW_POINTS_INVALID')
}

async function testScoreIsServerComputedBoundedAndTimeSensitive() {
  const start = 1_700_000_000_000
  const ends = start + 75_000
  assert.strictEqual(calculateGuessScore({ nowMs: start, endsAtMs: ends, roundSeconds: 75 }), 475)
  assert.strictEqual(calculateGuessScore({ nowMs: start + 60_000, endsAtMs: ends, roundSeconds: 75 }), 175)
  assert.strictEqual(calculateGuessScore({ nowMs: ends + 5_000, endsAtMs: ends, roundSeconds: 75 }), 100)
  assert.ok(calculateGuessScore({ nowMs: start, endsAtMs: start + 180_000, roundSeconds: 180 }) <= 1000)
}

async function testDrawerRotationAndOwnership() {
  const players = [
    { user_id: 'host', seat_index: 0, status: 'accepted' },
    { user_id: 'guest-a', seat_index: 1, status: 'accepted' },
    { user_id: 'guest-b', seat_index: 2, status: 'accepted' },
    { user_id: 'pending', seat_index: 3, status: 'invited' },
  ]
  assert.strictEqual(chooseDrawer(players, 1, 1).user_id, 'guest-a')
  assert.strictEqual(chooseDrawer(players, 2, 1).user_id, 'guest-b')
  assert.strictEqual(chooseDrawer(players, 3, 1).user_id, 'host')
  assert.strictEqual(chooseDrawer(players, 4, 1).user_id, 'guest-a')
  assert.strictEqual(chooseDrawer(players.slice(0, 1), 1, 0), null)
  assert.strictEqual(identityOwns('HOST', { ids: new Set(['host']) }), true)
  assert.strictEqual(identityOwns('stranger', { values: ['host'] }), false)
}

async function testPromptIsVisibleOnlyToDrawer() {
  const game = {
    id: GAME_ID,
    live_id: LIVE_ID,
    host_user_id: 'host',
    status: 'active',
    max_players: 8,
    total_rounds: 3,
    round_seconds: 75,
    current_round_number: 1,
    started_at: '2026-08-08T00:00:00Z',
    completed_at: null,
  }
  const players = [
    { user_id: 'host', seat_index: 0, status: 'accepted', score: 20 },
    { user_id: 'drawer', seat_index: 1, status: 'accepted', score: 5 },
    { user_id: 'viewer', seat_index: 2, status: 'accepted', score: 100 },
    { user_id: 'pending', seat_index: 3, status: 'invited', score: 0 },
  ]
  const round = {
    id: ROUND_ID,
    game_id: GAME_ID,
    round_number: 1,
    drawer_user_id: 'drawer',
    prompt_key: 'ballena',
    prompt_version: 1,
    status: 'active',
    last_draw_sequence: 8,
    started_at: '2026-08-08T00:00:00Z',
    ends_at: '2026-08-08T00:01:15Z',
    completed_at: null,
  }
  const drawerState = serializeGameState({ game, players, round, viewerUserId: 'DRAWER' })
  const viewerState = serializeGameState({ game, players, round, viewerUserId: 'viewer' })
  const anonymousState = serializeGameState({ game, players, round, viewerUserId: '' })
  assert.deepStrictEqual(drawerState.private_prompt, { key: 'ballena', label: 'Ballena', version: 1 })
  assert.strictEqual(viewerState.private_prompt, null)
  assert.strictEqual(anonymousState.private_prompt, null)
  assert.strictEqual(Object.prototype.hasOwnProperty.call(publicRound(round), 'prompt_key'), false)
  assert.strictEqual(JSON.stringify(viewerState).includes('ballena'), false)
  assert.strictEqual(viewerState.players[0].user_id, 'viewer')
  assert.strictEqual(viewerState.players[0].rank, 1)
  assert.strictEqual(viewerState.players.at(-1).status, 'invited')
  assert.strictEqual(viewerState.players.at(-1).rank, null)
}

async function testRateLimitsAreServerSide() {
  let time = 1_700_000_000_000
  const limits = createDrawGuessRateLimiters(() => time)
  for (let index = 0; index < 12; index += 1) {
    assert.strictEqual(limits.guess('game:user').allowed, true)
  }
  const blocked = limits.guess('game:user')
  assert.strictEqual(blocked.allowed, false)
  assert.ok(blocked.retryAfterSeconds >= 1)
  assert.strictEqual(limits.guess('game:other-user').allowed, true)
  time += 10_001
  assert.strictEqual(limits.guess('game:user').allowed, true)
}

async function testEveryEndpointRequiresAuthenticationAndFlag() {
  const routes = []
  const app = {
    post(pathname, ...handlers) { routes.push({ method: 'POST', pathname, handlers }) },
    get(pathname, ...handlers) { routes.push({ method: 'GET', pathname, handlers }) },
  }
  const authRequired = function authRequired(_req, _res, next) { next() }
  registerLiveDrawGuessRoutes(app, {
    pool: { query: async () => ({ rows: [] }) },
    authRequired,
    getCurrentUser: async () => { throw new Error('must not run while disabled') },
    userIdentity: () => { throw new Error('must not run while disabled') },
  })
  assert.deepStrictEqual(routes.map((route) => `${route.method} ${route.pathname}`), [
    'POST /v1/data/live/:liveId/games/draw-guess',
    'POST /v1/data/live/:liveId/games/draw-guess/:gameId/accept',
    'POST /v1/data/live/:liveId/games/draw-guess/:gameId/start',
    'GET /v1/data/live/:liveId/games/draw-guess/current',
    'GET /v1/data/live/:liveId/games/draw-guess/:gameId',
    'POST /v1/data/live/:liveId/games/draw-guess/:gameId/draw',
    'POST /v1/data/live/:liveId/games/draw-guess/:gameId/guesses',
    'POST /v1/data/live/:liveId/games/draw-guess/:gameId/advance',
    'POST /v1/data/live/:liveId/games/draw-guess/:gameId/cancel',
  ])
  for (const route of routes) assert.strictEqual(route.handlers[0], authRequired)

  const response = fakeResponse()
  await withFlag(undefined, () => routes[0].handlers[1]({ params: {}, body: {} }, response, () => {}))
  assert.strictEqual(response.statusCode, 404)
  assert.deepStrictEqual(response.body, { error: 'LIVE_DRAW_GUESS_DISABLED' })
}

async function testSourceNeverPublishesOrPersistsSecretText() {
  const source = fs.readFileSync(require.resolve('../src/live-draw-guess-routes'), 'utf8')
  assert.deepStrictEqual(sanitizePublicEvent({
    type: 'round_started',
    prompt: 'secret',
    nested: { prompt_key: 'secret-key', guess: 'secret-guess', safe: true },
    entries: [{ private_prompt: { label: 'secret' }, score: 10 }],
  }), {
    type: 'round_started',
    nested: { safe: true },
    entries: [{ score: 10 }],
  })
  assert.match(source, /sanitizePublicEvent/)
  assert.match(source, /INSERT INTO fallback_live_draw_guesses\([\s\S]*answer_length, correct, awarded_points/)
  assert.doesNotMatch(source, /INSERT INTO fallback_live_draw_guesses\([\s\S]{0,250}(guess_text|answer_text)/)
  assert.match(source, /FOR UPDATE/)
  assert.match(source, /BEGIN/)
  assert.match(source, /ROLLBACK/)
  assert.match(source, /COMMIT/)
  assert.match(source, /last_draw_sequence < \$2/)
  assert.match(source, /LIVE_DRAW_DRAWER_REQUIRED/)
  assert.match(source, /LIVE_HOST_REQUIRED/)
  assert.match(source, /completed_at >= NOW\(\) - INTERVAL '10 minutes'/)
}

async function testProductionWiringRemainsFailClosed() {
  const apiRoot = path.resolve(__dirname, '..')
  const dataRoutes = fs.readFileSync(path.join(apiRoot, 'src/data-routes.js'), 'utf8')
  const liveRoutes = fs.readFileSync(path.join(apiRoot, 'src/live-routes.js'), 'utf8')
  const server = fs.readFileSync(path.join(apiRoot, 'src/server.js'), 'utf8')
  const configExample = fs.readFileSync(path.resolve(apiRoot, '../config.example'), 'utf8')
  const packageJson = JSON.parse(fs.readFileSync(path.join(apiRoot, 'package.json'), 'utf8'))

  assert.match(dataRoutes, /ensureLiveDrawGuessTables/)
  assert.match(dataRoutes, /registerLiveDrawGuessRoutes/)
  assert.match(liveRoutes, /drawGuessEnabled:\s*drawGuessEnabled\(\)/)
  assert.match(server, /startLiveDrawGuessReconciler\(\{ pool \}\)/)
  assert.match(server, /liveDrawGuessReconciler\?\.stop\(\)/)
  assert.match(configExample, /^LIVE_DRAW_GUESS_ENABLED=false$/m)
  assert.match(packageJson.scripts.check, /live-draw-guess-routes\.js/)
  assert.strictEqual(packageJson.scripts['test:live-draw-guess'], 'node tests/live-draw-guess-routes.test.js')
}

Promise.resolve()
  .then(testFeatureFlagIsFailClosed)
  .then(testSchemaIsAdditiveIdempotentAndPrivacyPreserving)
  .then(testPromptCatalogIsServerOwnedAndSafe)
  .then(testGuessModerationBlocksPiiAndUnsafeText)
  .then(testIdempotencyKeysAreBoundedAndOpaque)
  .then(testLossyDrawPacketValidation)
  .then(testScoreIsServerComputedBoundedAndTimeSensitive)
  .then(testDrawerRotationAndOwnership)
  .then(testPromptIsVisibleOnlyToDrawer)
  .then(testRateLimitsAreServerSide)
  .then(testEveryEndpointRequiresAuthenticationAndFlag)
  .then(testSourceNeverPublishesOrPersistsSecretText)
  .then(testProductionWiringRemainsFailClosed)
  .then(() => console.log('live draw/guess route tests passed (13 suites)'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
