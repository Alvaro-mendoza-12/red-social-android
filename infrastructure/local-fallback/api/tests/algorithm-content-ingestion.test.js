'use strict'

const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const {
  BACKFILL_FLAG,
  CLASSIFIER_VERSION,
  backfillTrustedPublicContentClassifications,
  deriveTrustedContentClassification,
  importedContentRows,
  ingestTrustedContentClassification,
  syncTrustedContentClassification,
} = require('../src/algorithm-content-ingestion')

function withAlgorithmFlags(values, callback) {
  const names = [
    'USER_ALGORITHM_ENABLED',
    'USER_ALGORITHM_CLASSIFICATION_ENABLED',
    BACKFILL_FLAG,
  ]
  const previous = new Map(names.map((name) => [name, process.env[name]]))
  for (const name of names) delete process.env[name]
  for (const [name, value] of Object.entries(values)) process.env[name] = value
  return Promise.resolve()
    .then(callback)
    .finally(() => {
      for (const [name, value] of previous) {
        if (value === undefined) delete process.env[name]
        else process.env[name] = value
      }
    })
}

function validPost(overrides = {}) {
  return {
    id: '17f79108-a31d-4b1a-a745-d241adae7bde',
    user_id: '2a1d9ce4-df54-465a-a1bc-1cd847f639cc',
    content: 'Tutorial de Kotlin para crear una app Android',
    media_type: 'image/jpeg',
    visibility: 'public',
    ...overrides,
  }
}

function successfulClassificationClient(statements) {
  return {
    async query(sql, values = []) {
      const normalized = String(sql).replace(/\s+/g, ' ').trim()
      statements.push({ sql: normalized, values })
      if (/INSERT INTO fallback_algorithm_content_classifications/.test(normalized)) {
        return { rows: [{ id: '37e57ed8-d3f0-43e2-a490-714532457f49' }], rowCount: 1 }
      }
      if (/UPDATE fallback_algorithm_content_classifications/.test(normalized)) {
        return { rows: [{ id: '37e57ed8-d3f0-43e2-a490-714532457f49' }], rowCount: 1 }
      }
      return { rows: [], rowCount: 1 }
    },
  }
}

function testServerDerivationIgnoresClientClassificationFields() {
  const derived = deriveTrustedContentClassification('post', validPost({
    classification: { topics: [{ topicKey: 'forged' }] },
    topics: [{ topicKey: 'forged' }],
    safetyState: 'blocked',
    classifierVersion: 'client-forged',
    latitude: '-12.0',
    longitude: '-77.0',
    age: 14,
    gender: 'private',
    private_messages: ['never read'],
  }))
  assert.ok(derived.value)
  assert.equal(derived.value.classifierVersion, CLASSIFIER_VERSION)
  assert.equal(derived.value.safetyState, 'allowed')
  assert.ok(derived.value.topics.some((topic) => topic.topicKey === 'technology'))
  assert.equal(derived.value.topics.some((topic) => topic.topicKey === 'forged'), false)
  assert.equal(Object.hasOwn(derived.value, 'latitude'), false)
  assert.equal(Object.hasOwn(derived.value, 'age'), false)
  assert.equal(Object.hasOwn(derived.value, 'gender'), false)
}

function testPrivateContentIsExcluded() {
  const post = deriveTrustedContentClassification('post', validPost({ visibility: 'followers' }))
  assert.equal(post.excluded, 'CONTENT_NOT_PUBLIC')
  const video = deriveTrustedContentClassification('video', {
    id: validPost().id,
    user_id: validPost().user_id,
    title: 'Video privado',
    is_public: false,
  })
  assert.equal(video.excluded, 'CONTENT_NOT_PUBLIC')
}

function testTrustedVideoSoundBecomesAnAudioAffinityKey() {
  const soundId = '5ab8436e-a457-4dd7-ae5f-14585b36be8b'
  const video = deriveTrustedContentClassification('video', {
    id: validPost().id,
    user_id: validPost().user_id,
    title: 'Video con sonido reutilizado',
    description: 'Música para descubrir',
    duration_seconds: 18,
    is_public: true,
    sound_id: soundId,
  })
  assert.equal(video.value.audioKey, soundId)
}

async function testCoreClassificationRemainsAvailableWithoutRolloutFlags() {
  await withAlgorithmFlags({}, async () => {
    const statements = []
    const result = await syncTrustedContentClassification(
      successfulClassificationClient(statements),
      'post',
      validPost(),
    )
    assert.ok(result.id)
    assert.ok(statements.some((entry) => /fallback_algorithm_content_classifications/.test(entry.sql)))
  })
}

async function testTrustedUpsertBecomesAllowedOnlyAfterTopics() {
  await withAlgorithmFlags({
    USER_ALGORITHM_ENABLED: 'true',
    USER_ALGORITHM_CLASSIFICATION_ENABLED: 'true',
  }, async () => {
    const statements = []
    const result = await syncTrustedContentClassification(
      successfulClassificationClient(statements),
      'post',
      validPost(),
    )
    assert.ok(result.id)
    const guardIndex = statements.findIndex((entry) => /INSERT INTO fallback_algorithm_content_classifications/.test(entry.sql))
    const finalIndex = statements.findIndex((entry) => /UPDATE fallback_algorithm_content_classifications/.test(entry.sql))
    assert.ok(guardIndex >= 0 && finalIndex > guardIndex)
    assert.match(statements[guardIndex].sql, /'unknown'/)
    assert.match(statements[finalIndex].sql, /locked_classification AS \( SELECT id[\s\S]*FOR UPDATE/)
    assert.equal((statements[finalIndex].sql.match(/UPDATE fallback_algorithm_content_classifications/g) || []).length, 1)
    assert.match(statements[finalIndex].sql, /deleted_topics[\s\S]*inserted_topics[\s\S]*SET creator_id[\s\S]*safety_state = \$9/)
    assert.equal(statements[finalIndex].values[8], 'allowed')
  })
}

async function testFailureDeletesClassificationWithoutLoggingContent() {
  await withAlgorithmFlags({
    USER_ALGORITHM_ENABLED: 'true',
    USER_ALGORITHM_CLASSIFICATION_ENABLED: 'true',
  }, async () => {
    const statements = []
    const client = successfulClassificationClient(statements)
    const original = client.query
    client.query = async (sql, values) => {
      if (/INSERT INTO fallback_algorithm_content_topics/.test(String(sql))) {
        statements.push({ sql: String(sql).replace(/\s+/g, ' ').trim(), values })
        throw new Error('simulated database failure')
      }
      return original(sql, values)
    }
    const result = await ingestTrustedContentClassification(client, 'post', validPost())
    assert.equal(result.error, 'TRUSTED_CLASSIFICATION_INGESTION_FAILED')
    assert.ok(statements.some((entry) => /DELETE FROM fallback_algorithm_content_classifications/.test(entry.sql)))
  })
}

async function testImportedReaderUsesExplicitPublicAllowList() {
  const statements = []
  const pool = {
    async query(sql) {
      const normalized = String(sql).replace(/\s+/g, ' ').trim()
      statements.push(normalized)
      if (/information_schema\.columns/.test(normalized)) {
        return { rows: [
          { column_name: 'id' },
          { column_name: 'user_id' },
          { column_name: 'content' },
          { column_name: 'visibility' },
          { column_name: 'latitude' },
          { column_name: 'gender' },
        ] }
      }
      return { rows: [] }
    },
  }
  await importedContentRows(pool, 'post', 20)
  const select = statements.find((sql) => /FROM supabase_import\.posts/.test(sql))
  assert.ok(select)
  assert.doesNotMatch(select, /SELECT \*/i)
  assert.doesNotMatch(select, /latitude|longitude|age|gender|message/i)
}

async function testBackfillRequiresIndependentGate() {
  await withAlgorithmFlags({
    USER_ALGORITHM_ENABLED: 'true',
    USER_ALGORITHM_CLASSIFICATION_ENABLED: 'true',
  }, async () => {
    let queried = false
    const result = await backfillTrustedPublicContentClassifications({
      async query() {
        queried = true
        throw new Error('must not query')
      },
    })
    assert.equal(result.skipped, 'USER_ALGORITHM_CLASSIFICATION_BACKFILL_DISABLED')
    assert.equal(queried, false)
  })
}

function testProductionWritePathsAreWired() {
  const root = path.resolve(__dirname, '..')
  const dataRoutes = fs.readFileSync(path.join(root, 'src/data-routes.js'), 'utf8')
  const compatRoutes = fs.readFileSync(path.join(root, 'src/postgrest-compat-routes.js'), 'utf8')
  const importer = fs.readFileSync(path.join(root, 'src/import-all-csv.js'), 'utf8')
  assert.match(dataRoutes, /ingestTrustedContentClassification\(pool, 'post'/)
  assert.match(dataRoutes, /ingestTrustedContentClassification\(pool, 'video'/)
  assert.match(compatRoutes, /ingestTrustedContentClassification\(pool, 'post'/)
  assert.match(compatRoutes, /tableName === 'posts' \? 'post' : 'video'/)
  assert.match(importer, /backfillTrustedPublicContentClassifications\(pool\)/)
}

Promise.resolve()
  .then(testServerDerivationIgnoresClientClassificationFields)
  .then(testPrivateContentIsExcluded)
  .then(testTrustedVideoSoundBecomesAnAudioAffinityKey)
  .then(testCoreClassificationRemainsAvailableWithoutRolloutFlags)
  .then(testTrustedUpsertBecomesAllowedOnlyAfterTopics)
  .then(testFailureDeletesClassificationWithoutLoggingContent)
  .then(testImportedReaderUsesExplicitPublicAllowList)
  .then(testBackfillRequiresIndependentGate)
  .then(testProductionWritePathsAreWired)
  .then(() => console.log('algorithm content ingestion tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
