'use strict'

const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const {
  BLOCK_EDGE_SQL,
  LocalFallbackCandidateSourceError,
  SOCIAL_CANDIDATE_SQL,
  VIDEO_CANDIDATE_SQL,
  createAlgorithmLocalFallbackCandidateSource,
  inferredSignalsFromRows,
} = require('../src/algorithm-local-fallback-candidate-source')
const { createAlgorithmCandidateProvider } = require('../src/algorithm-candidate-provider')

const NOW = Date.parse('2026-08-07T12:00:00.000Z')

function owner() {
  return {
    userId: 'viewer-public',
    identity: {
      displayUserId: 'viewer-public',
      localUserId: 'viewer-local',
      ids: new Set(['viewer-public', 'viewer-local']),
    },
  }
}

function socialRow(overrides = {}) {
  return {
    id: 'post-1',
    source_creator_id: 'creator-public',
    local_creator_id: 'creator-local',
    external_creator_id: 'creator-public',
    public_creator_id: 'creator-public',
    display_name: 'Creadora pública',
    avatar_url: 'https://cdn.example.test/avatars/creator.png',
    content: 'Una publicación visible para la audiencia.',
    media_url: 'https://cdn.example.test/posts/post-1.jpg',
    media_urls: [{ url: 'https://cdn.example.test/posts/post-1.jpg', media_type: 'image' }],
    media_type: 'image',
    created_at: '2026-08-06T12:00:00.000Z',
    updated_at: '2026-08-07T10:00:00.000Z',
    profile_updated_at: '2026-08-07T09:00:00.000Z',
    classification_updated_at: '2026-08-07T11:00:00.000Z',
    language_code: 'es',
    format_key: 'photo-post',
    audio_key: null,
    community_id: 'aves-peru',
    classification_topics: [
      { topic_key: 'aves', subtopic_key: 'colibri', confidence: 0.98 },
      { topic_key: 'naturaleza', subtopic_key: '', confidence: 0.87 },
    ],
    is_followed_creator: true,
    ...overrides,
  }
}

function videoRow(overrides = {}) {
  return {
    id: 'video-1',
    source_creator_id: 'creator-public',
    local_creator_id: 'creator-local',
    external_creator_id: 'creator-public',
    public_creator_id: 'creator-public',
    display_name: 'Creadora pública',
    avatar_url: 'https://cdn.example.test/avatars/creator.png',
    title: 'Colibrí al amanecer',
    description: 'Video público y apto.',
    video_url: 'https://cdn.example.test/videos/video-1.mp4',
    cover_url: 'https://cdn.example.test/covers/video-1.jpg',
    thumbnail_url: 'https://cdn.example.test/thumbs/video-1.jpg',
    duration_seconds: 24,
    views: 17,
    like_count: 5,
    comment_count: 2,
    share_count: 1,
    created_at: '2026-08-06T12:00:00.000Z',
    updated_at: '2026-08-07T10:00:00.000Z',
    profile_updated_at: '2026-08-07T09:00:00.000Z',
    classification_updated_at: '2026-08-07T11:00:00.000Z',
    language_code: 'es',
    format_key: 'short-video',
    audio_key: 'instrumental',
    community_id: 'aves-peru',
    classification_topics: [
      { topic_key: 'aves', subtopic_key: 'colibri', confidence: 0.98 },
      { topic_key: 'naturaleza', subtopic_key: '', confidence: 0.87 },
    ],
    is_followed_creator: false,
    ...overrides,
  }
}

function fakePool({ socialRows = [], videoRows = [], blockRows = [], inferredRows = [], missingTables = [], failRaw = false, failBlocks = false } = {}) {
  const calls = []
  return {
    calls,
    async query(sql, params) {
      calls.push({ sql, params })
      if (sql.includes('to_regclass')) {
        return { rows: missingTables.map((table_name) => ({ table_name })) }
      }
      if (sql.includes('FROM fallback_posts')) {
        if (failRaw) throw new Error('database unavailable')
        return { rows: socialRows }
      }
      if (sql.includes('FROM fallback_videos')) {
        if (failRaw) throw new Error('database unavailable')
        return { rows: videoRows }
      }
      if (sql.includes('FROM fallback_user_blocks')) {
        if (failBlocks) throw new Error('block table unavailable')
        return { rows: blockRows }
      }
      if (sql.includes('WITH interactions(source')) return { rows: inferredRows }
      throw new Error(`unexpected query: ${sql.slice(0, 72)}`)
    },
  }
}

function testInferredSignalsAreServerDerivedAndBounded() {
  const signals = inferredSignalsFromRows([
    { source: 'likes', topic_key: 'Delfines', subtopic_key: 'Mar', strength: 9 },
    { source: 'watch_time', topic_key: 'delfines', subtopic_key: null, strength: 2 },
    { source: 'private_messages', topic_key: 'secret', strength: 99 },
    { source: 'likes', topic_key: 'not valid spaces', strength: 4 },
  ])
  assert.deepEqual(signals, {
    sources: {
      likes: { topic: { delfines: 1 }, subtopic: { mar: 1 } },
      watch_time: { topic: { delfines: 0.4 } },
    },
  })
}

async function testSelectorsUseOnlyTheApprovedLocalFallbackBoundary() {
  const selectors = `${SOCIAL_CANDIDATE_SQL}\n${VIDEO_CANDIDATE_SQL}`
  assert.match(selectors, /fallback_profile_moderation/)
  assert.match(selectors, /fallback_algorithm_content_classifications/)
  assert.match(selectors, /fallback_algorithm_content_topics/)
  assert.match(selectors, /classification\.safety_state = 'allowed'/)
  assert.match(selectors, /fallback_deleted_identities/)
  assert.match(selectors, /fallback_video_deletions/)
  assert.match(selectors, /suspension_end_date/)
  assert.match(selectors, /profile_visibility/)
  assert.match(selectors, /break_mode/)
  assert.match(BLOCK_EDGE_SQL, /fallback_user_blocks/)
  assert.match(BLOCK_EDGE_SQL, /lower\(blocker_id\).*ANY\(\$1/s)
  assert.match(BLOCK_EDGE_SQL, /lower\(blocker_id\).*ANY\(\$2/s)
  assert.doesNotMatch(selectors, /\b(fallback_messages|fallback_proximity|fallback_random_chat|latitude|longitude|gender|birth_date|email|phone)\b/i)
  assert.doesNotMatch(selectors, /\bvideo\.tags\b/i)
  assert.doesNotMatch(BLOCK_EDGE_SQL, /\b(fallback_messages|fallback_proximity|fallback_random_chat)\b/i)
}

async function testSocialAndVideoCandidatesArePublicAndNarrow() {
  const pool = fakePool({
    socialRows: [socialRow()],
    videoRows: [videoRow()],
  })
  const source = createAlgorithmLocalFallbackCandidateSource({ pool, now: () => NOW })

  const social = await source.loadRawCandidates({ owner: owner(), surface: 'social', maxCandidates: 50, now: NOW })
  assert.equal(social.candidates.length, 1)
  assert.equal(social.candidates[0].surface, 'social')
  assert.deepEqual(social.candidates[0].creatorAliases, ['creator-public', 'creator-local'])
  assert.equal(social.candidates[0].isFollowedCreator, true)
  assert.deepEqual(social.candidates[0].safety, {
    contentPublic: true,
    contentEligible: true,
    creatorVisible: true,
    creatorHidden: false,
    creatorSuspended: false,
  })
  assert.deepEqual(Object.keys(social.candidates[0].item).sort(), [
    'content', 'created_at', 'id', 'media_type', 'media_url', 'media_urls',
    'updated_at', 'user_id', 'user_name', 'user_profile_image', 'visibility',
  ])
  assert.equal(social.candidates[0].item.visibility, 'public')
  assert.equal(social.candidates[0].item.user_id, 'creator-public')
  assert.deepEqual(social.candidates[0].topics, ['aves', 'naturaleza'])
  assert.deepEqual(social.candidates[0].subtopics, ['colibri'])
  assert.deepEqual(social.candidates[0].formats, ['post', 'photo-post'])
  assert.deepEqual(social.candidates[0].languages, ['es'])
  assert.deepEqual(social.candidates[0].communityKeys, ['aves-peru'])

  const video = await source.loadRawCandidates({ owner: owner(), surface: 'video', maxCandidates: 50, now: NOW })
  assert.equal(video.candidates.length, 1)
  assert.equal(video.candidates[0].surface, 'video')
  assert.deepEqual(video.candidates[0].topics, ['aves', 'naturaleza'])
  assert.deepEqual(video.candidates[0].subtopics, ['colibri'])
  assert.deepEqual(video.candidates[0].formats, ['video', 'short-video'])
  assert.deepEqual(video.candidates[0].audioKeys, ['instrumental'])
  assert.equal(video.candidates[0].item.video_url, 'https://cdn.example.test/videos/video-1.mp4')
  assert.equal(video.candidates[0].item.is_public, true)
  assert.deepEqual(Object.keys(video.candidates[0].item).sort(), [
    'comment_count', 'cover_url', 'created_at', 'description', 'duration_seconds',
    'id', 'is_public', 'like_count', 'share_count', 'tags', 'thumbnail_url',
    'title', 'updated_at', 'user_id', 'user_name', 'user_profile_image',
    'video_url', 'views',
  ])
  assert.match(social.snapshotId, /^lfc1-social-/)
  assert.match(video.snapshotId, /^lfc1-video-/)
}

async function testSourceScrubsUnsafeDisplayAndSignedMediaUrls() {
  const pool = fakePool({
    videoRows: [videoRow({
      display_name: 'private@example.test',
      avatar_url: 'https://cdn.example.test/avatar.png?token=never-expose',
      video_url: 'https://cdn.example.test/video.mp4?signature=never-expose',
      cover_url: 'http://insecure.example.test/cover.jpg',
      thumbnail_url: 'https://cdn.example.test/thumb.jpg',
    })],
  })
  const source = createAlgorithmLocalFallbackCandidateSource({ pool, now: () => NOW })
  const result = await source.loadRawCandidates({ owner: owner(), surface: 'video', now: NOW })
  assert.equal(result.candidates.length, 1)
  assert.equal(result.candidates[0].item.user_name, 'Usuario')
  assert.equal(result.candidates[0].item.user_profile_image, null)
  assert.equal(result.candidates[0].item.video_url, null)
  assert.equal(result.candidates[0].item.cover_url, null)
  assert.equal(result.candidates[0].item.thumbnail_url, 'https://cdn.example.test/thumb.jpg')
}

async function testBlockEdgesAreBidirectionalAndWorkWithCandidateProvider() {
  const pool = fakePool({
    videoRows: [videoRow()],
    blockRows: [{ blocker_id: 'creator-local', blocked_id: 'viewer-public' }],
  })
  const source = createAlgorithmLocalFallbackCandidateSource({ pool, now: () => NOW })
  const provider = createAlgorithmCandidateProvider({
    loadRawCandidates: source.loadRawCandidates,
    loadBlockEdges: source.loadBlockEdges,
  })
  const result = await provider({ owner: owner(), surface: 'video', maxCandidates: 50, now: NOW })
  assert.deepEqual(result.candidates, [])
  const blockCall = pool.calls.find((entry) => entry.sql.includes('FROM fallback_user_blocks'))
  assert.ok(blockCall)
  assert.deepEqual(blockCall.params[0].sort(), ['viewer-local', 'viewer-public'])
  assert.ok(blockCall.params[1].includes('creator-public'))
  assert.ok(blockCall.params[1].includes('creator-local'))
}

async function testMissingSafetyTablesAndDatabaseFailuresFailClosed() {
  const missingPool = fakePool({
    socialRows: [socialRow()],
    missingTables: ['fallback_algorithm_content_classifications'],
  })
  const missingSource = createAlgorithmLocalFallbackCandidateSource({ pool: missingPool, now: () => NOW })
  const missing = await missingSource.loadRawCandidates({ owner: owner(), surface: 'social', now: NOW })
  assert.deepEqual(missing.candidates, [])
  assert.match(missing.snapshotId, /^lfc1-social-/)
  assert.equal(missingPool.calls.some((entry) => entry.sql.includes('FROM fallback_posts')), false)

  const unavailablePool = fakePool({ socialRows: [socialRow()], failRaw: true })
  const unavailableSource = createAlgorithmLocalFallbackCandidateSource({ pool: unavailablePool, now: () => NOW })
  const unavailable = await unavailableSource.loadRawCandidates({ owner: owner(), surface: 'social', now: NOW })
  assert.deepEqual(unavailable.candidates, [])

  const blocksMissingPool = fakePool({ missingTables: ['fallback_user_blocks'] })
  const blocksMissingSource = createAlgorithmLocalFallbackCandidateSource({ pool: blocksMissingPool, now: () => NOW })
  await assert.rejects(
    () => blocksMissingSource.loadBlockEdges({ viewerIds: ['viewer-public'], creatorIds: ['creator-public'] }),
    (error) => error instanceof LocalFallbackCandidateSourceError
      && error.code === 'RECOMMENDATION_PROVIDER_BLOCKS_UNAVAILABLE',
  )
}

async function testRowsWithoutTrustedTopicsAreNotCandidates() {
  const pool = fakePool({
    socialRows: [socialRow({ classification_topics: [] })],
    videoRows: [videoRow({
      classification_topics: [{ topic_key: 'not valid spaces', subtopic_key: '', confidence: 0.9 }],
    })],
  })
  const source = createAlgorithmLocalFallbackCandidateSource({ pool, now: () => NOW })
  const social = await source.loadRawCandidates({ owner: owner(), surface: 'social', now: NOW })
  const video = await source.loadRawCandidates({ owner: owner(), surface: 'video', now: NOW })
  assert.deepEqual(social.candidates, [])
  assert.deepEqual(video.candidates, [])
}

function testSourceStaysIsolatedFromLegacyRouteImplementations() {
  const source = fs.readFileSync(path.join(__dirname, '../src/algorithm-local-fallback-candidate-source.js'), 'utf8')
  assert.doesNotMatch(source, /require\(['"]\.\/(?:data-routes|proximity-routes|socialization-routes)['"]\)/)
  assert.doesNotMatch(source, /app\.(?:get|post|put|patch|delete)\s*\(/)
}

Promise.resolve()
  .then(testInferredSignalsAreServerDerivedAndBounded)
  .then(testSelectorsUseOnlyTheApprovedLocalFallbackBoundary)
  .then(testSocialAndVideoCandidatesArePublicAndNarrow)
  .then(testSourceScrubsUnsafeDisplayAndSignedMediaUrls)
  .then(testBlockEdgesAreBidirectionalAndWorkWithCandidateProvider)
  .then(testMissingSafetyTablesAndDatabaseFailuresFailClosed)
  .then(testRowsWithoutTrustedTopicsAreNotCandidates)
  .then(testSourceStaysIsolatedFromLegacyRouteImplementations)
  .then(() => console.log('algorithm local fallback candidate source tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
