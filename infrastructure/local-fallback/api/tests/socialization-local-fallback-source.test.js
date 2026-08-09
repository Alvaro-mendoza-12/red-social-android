const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const { REQUIRED_TABLES, evaluateCandidateSafety, identityAliases, loadCandidates, publicCandidate } = require('../src/socialization-local-fallback-source')
const { createSocializationCardsLoader, registerSocializationCards } = require('../src/socialization-cards-bootstrap')

function identity() {
  return { displayUserId: 'viewer-1', ids: new Set(['viewer-1', 'viewer-local']) }
}

function safeAccount(overrides = {}) {
  return {
    local_id: 'candidate-local', external_id: 'candidate-public', public_id: 'candidate-public',
    display_name: 'Perfil público', avatar_url: 'https://cdn.example.test/avatar.png',
    is_public: true, is_private: false, break_mode: 'off', is_suspended: false, is_deleted: false,
    ...overrides,
  }
}

function database({ blockedByViewer = false, blocksViewer = false, missingTables = false } = {}) {
  return {
    async query(sql) {
      if (sql.includes('to_regclass')) return { rows: missingTables ? [{ table_name: REQUIRED_TABLES[0] }] : [] }
      if (sql.includes('FROM fallback_users account') && sql.includes('ORDER BY account.updated_at')) return { rows: [safeAccount()] }
      if (sql.includes('FROM fallback_collection_rooms') && sql.includes("room_kind = 'community'")) {
        if (sql.includes('ORDER BY updated_at')) return { rows: [{ target_id: 'community-1', subject_id: 'candidate-public', title: 'Comunidad', subtitle: 'Pública', image_url: null }] }
        return { rows: [{ subject_id: 'candidate-public', is_public: true, is_deleted: false }] }
      }
      if (sql.includes('FROM fallback_active_challenges') && sql.includes('ORDER BY active.created_at')) return { rows: [{ target_id: 'challenge-1', subject_id: 'candidate-public', title: 'Reto', subtitle: 'Activo', expires_at: '2026-08-09T00:00:00.000Z' }] }
      if (sql.includes('FROM fallback_live_streams') && sql.includes('ORDER BY started_at')) return { rows: [{ target_id: 'live-1', subject_id: 'candidate-public', title: 'En vivo', subtitle: 'Creador', image_url: null }] }
      if (sql.includes('FROM fallback_live_streams') && sql.includes('WHERE id::text')) return { rows: [{ subject_id: 'candidate-public', is_active: true }] }
      if (sql.includes('FROM fallback_users account') && sql.includes('LIMIT 1')) return { rows: [safeAccount()] }
      if (sql.includes('FROM fallback_user_blocks')) {
        const rows = []
        if (blockedByViewer) rows.push({ blocker_id: 'viewer-1', blocked_id: 'candidate-public' })
        if (blocksViewer) rows.push({ blocker_id: 'candidate-public', blocked_id: 'viewer-1' })
        return { rows }
      }
      throw new Error(`Unexpected SQL: ${sql.slice(0, 100)}`)
    },
  }
}

function testIdentityAndPublicContract() {
  assert.deepEqual(identityAliases(identity()), ['viewer-1', 'viewer-local'])
  assert.deepEqual(publicCandidate('person', safeAccount()), {
    type: 'person', targetId: 'candidate-public', subjectId: 'candidate-public',
    title: 'Perfil público', imageUrl: 'https://cdn.example.test/avatar.png',
  })
  assert.equal(publicCandidate('person', safeAccount({ display_name: 'test@example.test' })), null)
}

async function testAllPublicTypesLoadWithoutPrivateFields() {
  const result = await loadCandidates(database(), {
    identity: identity(), allowedTypes: ['person', 'community', 'challenge', 'live'], limit: 4,
  })
  assert.deepEqual(result.items.map((item) => item.type), ['person', 'community', 'challenge', 'live'])
  const serialized = JSON.stringify(result)
  assert.equal(serialized.includes('email'), false)
  assert.equal(serialized.includes('location'), false)
  assert.equal(serialized.includes('gender'), false)
  assert.equal(result.nextCursor, null)
}

async function testMissingSafetyTableFailsClosed() {
  const result = await loadCandidates(database({ missingTables: true }), {
    identity: identity(), allowedTypes: ['person'], limit: 4,
  })
  assert.deepEqual(result, { items: [], nextCursor: null })
}

async function testBidirectionalBlocksAndAccountSafety() {
  for (const option of [{ blockedByViewer: true }, { blocksViewer: true }]) {
    const result = await evaluateCandidateSafety(database(option), {
      viewerIdentity: identity(), candidate: { type: 'live', targetId: 'live-1', subjectId: 'candidate-public' },
    })
    assert.equal(result.viewerBlockedCandidate || result.candidateBlockedViewer, true)
  }
  const safe = await evaluateCandidateSafety(database(), {
    viewerIdentity: identity(), candidate: { type: 'live', targetId: 'live-1', subjectId: 'candidate-public' },
  })
  assert.deepEqual(safe, {
    isPublic: true, isDeleted: false, isHidden: false, isSuspended: false,
    viewerBlockedCandidate: false, candidateBlockedViewer: false,
  })
}

async function testComposedLoaderAndRegistration() {
  const pool = database()
  const result = await createSocializationCardsLoader(pool)({
    identity: identity(), surface: 'social', placement: 'after_12', limit: 4,
    cursor: null, allowedTypes: ['person', 'live'],
  })
  assert.deepEqual(result.items.map((item) => item.type), ['person', 'live'])
  const calls = []
  registerSocializationCards({ get() {} }, {
    pool, authRequired() {}, getCurrentUser() {}, userIdentity() {},
    createLoader: () => async () => ({ items: [] }),
    registerRoutes: (_app, dependencies) => calls.push(dependencies),
  })
  assert.equal(calls.length, 1)
  assert.equal(typeof calls[0].loadPreferences, 'function')
  assert.equal(typeof calls[0].loadCandidates, 'function')
}

function testSourceNeverReadsForbiddenStores() {
  const source = fs.readFileSync(path.join(__dirname, '../src/socialization-local-fallback-source.js'), 'utf8')
  assert.doesNotMatch(source, /fallback_messages|fallback_random_chat|fallback_proximity/i)
  assert.doesNotMatch(source, /account\.email|profile\.profile->>'(?:phone|latitude|longitude|gender|birth_date)'/i)
  assert.match(source, /fallback_user_blocks/)
  assert.match(source, /fallback_profile_moderation/)
}

Promise.resolve()
  .then(testIdentityAndPublicContract)
  .then(testAllPublicTypesLoadWithoutPrivateFields)
  .then(testMissingSafetyTableFailsClosed)
  .then(testBidirectionalBlocksAndAccountSafety)
  .then(testComposedLoaderAndRegistration)
  .then(testSourceNeverReadsForbiddenStores)
  .then(() => console.log('socialization local fallback source tests passed'))
  .catch((error) => { console.error(error); process.exitCode = 1 })
