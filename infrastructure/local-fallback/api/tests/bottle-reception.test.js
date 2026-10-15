const assert = require('node:assert/strict')
const test = require('node:test')
const { bottleCandidateAllowed, registerProximityRoutes } = require('../src/proximity-routes')

function allowed(candidate, overrides = {}) {
  return bottleCandidateAllowed({
    senderId: 'sender', candidate,
    attemptedIds: new Set(), blockedIds: new Set(), existingPeerIds: new Set(),
    busyRecipientIds: new Set(), saturatedUserIds: new Set(), ...overrides,
  })
}

test('bottles ignore gender preferences left by old clients', () => {
  for (const gender of ['man', 'woman', 'unspecified']) {
    for (const preference of ['man', 'woman', 'any']) {
      assert.equal(allowed({ user_id: 'recipient', gender, looking_for: preference,
        sender_gender: 'unspecified', sender_looking_for: 'woman' }), true)
    }
  }
})

for (const field of ['attemptedIds', 'blockedIds', 'existingPeerIds', 'busyRecipientIds', 'saturatedUserIds']) {
  test(`bottles still exclude ${field}`, () => {
    assert.equal(allowed({ user_id: 'recipient' }, { [field]: new Set(['recipient']) }), false)
  })
}

test('a bottle cannot go to its sender or an empty identity', () => {
  assert.equal(allowed({ user_id: 'sender' }), false)
  assert.equal(allowed({ user_id: '' }), false)
})

async function routeFixture(path, body, offered = false) {
  const handlers = new Map()
  const statements = []
  const row = { id: '00000000-0000-4000-8000-000000000001', sender_id: 'sender',
    content: 'Mensaje de prueba', status: offered ? 'offered' : 'searching',
    current_recipient_id: offered ? 'recipient' : null, attempted_recipient_ids: [] }
  const client = {
    async query(sql, values) {
      statements.push({ sql: String(sql), values })
      if (/INSERT INTO fallback_random_chat_bottles/.test(sql)) return { rows: [row] }
      if (offered && /FROM fallback_random_chat_bottles/.test(sql) && /lower\(current_recipient_id/.test(sql)) {
        return { rows: [row] }
      }
      return { rows: [] }
    },
    release() {},
  }
  const app = Object.fromEntries(['get', 'post', 'put', 'patch', 'delete'].map((verb) => [verb,
    (route, ...middleware) => handlers.set(`${verb} ${route}`, middleware.at(-1))]))
  registerProximityRoutes(app, {
    pool: { connect: async () => client }, authRequired() {}, getCurrentUser: async () => ({}),
    userIdentity: () => ({ displayUserId: 'recipient', values: ['recipient'], ids: new Set(['recipient']) }),
  })
  let payload
  let status = 200
  const response = { status(code) { status = code; return this }, json(value) { payload = value } }
  await handlers.get(`post ${path}`)({ body }, response, (error) => { throw error })
  return { statements, payload, status }
}

test('a receiver joins with no message and no gender selection', async () => {
  const result = await routeFixture('/v1/data/proximity/random/availability', {})
  assert.equal(result.status, 200)
  assert.deepEqual(result.payload, { status: 'available', bottle: null })
  const insert = result.statements.find(({ sql }) => /INSERT INTO fallback_random_bottle_availability/.test(sql))
  assert.deepEqual(insert.values, ['recipient', 'unspecified', 'any'])
})

test('old gender fields are ignored when registering reception', async () => {
  const result = await routeFixture('/v1/data/proximity/random/availability', { gender: 'woman', lookingFor: 'man' })
  const insert = result.statements.find(({ sql }) => /INSERT INTO fallback_random_bottle_availability/.test(sql))
  assert.deepEqual(insert.values, ['recipient', 'unspecified', 'any'])
})

test('a received bottle includes text and keeps the sender anonymous', async () => {
  const result = await routeFixture('/v1/data/proximity/random/availability', {}, true)
  assert.equal(result.payload.status, 'offered')
  assert.equal(result.payload.bottle.content, 'Mensaje de prueba')
  assert.equal(result.payload.bottle.perspective, 'recipient')
  assert.equal(Object.hasOwn(result.payload.bottle, 'sender_id'), false)
  assert.equal(Object.hasOwn(result.payload.bottle, 'senderId'), false)
})

test('a new bottle stores neutral matching even if an old client sends preferences', async () => {
  const result = await routeFixture('/v1/data/proximity/random/bottles', {
    content: 'Mensaje de prueba', gender: 'man', lookingFor: 'woman',
  })
  assert.equal(result.status, 201)
  const insert = result.statements.find(({ sql }) => /INSERT INTO fallback_random_chat_bottles/.test(sql))
  assert.deepEqual(insert.values.slice(1), ['Mensaje de prueba', 'unspecified', 'any'])
})
