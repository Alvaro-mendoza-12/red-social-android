const assert = require('assert')
const {
  FORBIDDEN_RANKING_INPUTS,
  rankAlgorithmCandidates,
} = require('../src/algorithm-ranking')

function candidate(id, overrides = {}) {
  return {
    id,
    creatorId: `creator-${id}`,
    topics: ['nature'],
    subtopics: ['wildlife'],
    feedSignals: {
      following: 0,
      interest: 0,
      community: 0,
      new_content: 0,
      trend: 0,
      exploration: 0,
    },
    ...overrides,
  }
}

function rank(input) {
  return rankAlgorithmCandidates({
    now: 1_700_000_000_000,
    limit: 20,
    ...input,
  })
}

function rankedIds(result) {
  return result.ranked.map((entry) => entry.candidateId)
}

function testSafetyPrecedesEveryOtherSignal() {
  const result = rank({
    candidates: [
      candidate('safe', { trendScore: 0.1 }),
      candidate('removed', {
        creatorId: 'creator-favourite',
        safety: { status: 'removed' },
        isFollowedCreator: true,
        trendScore: 1,
      }),
    ],
    preferences: [{ surface: 'video', dimension: 'creator', subjectKey: 'creator-favourite', score: 100 }],
    surface: 'video',
  })

  assert.deepStrictEqual(rankedIds(result), ['safe'])
  assert.deepStrictEqual(
    result.excluded.map((entry) => [entry.candidateId, entry.reasonCode]),
    [['removed', 'SAFETY_MODERATION_OR_POLICY']],
  )
}

function testExplicitDolphinBlockDefeatsCreatorAndFollow() {
  const result = rank({
    surface: 'live',
    candidates: [
      candidate('dolphins', {
        creatorId: 'creator-alice',
        topics: ['dolphins'],
        isFollowedCreator: true,
      }),
      candidate('forest', {
        creatorId: 'creator-alice',
        topics: ['forest'],
        isFollowedCreator: true,
      }),
    ],
    preferences: [
      { surface: 'live', dimension: 'creator', subjectKey: 'creator-alice', score: 90 },
      { surface: 'live', dimension: 'topic', subjectKey: 'dolphins', score: -100 },
    ],
  })

  assert.deepStrictEqual(rankedIds(result), ['forest'])
  const exclusion = result.excluded.find((entry) => entry.candidateId === 'dolphins')
  assert.ok(exclusion)
  assert.strictEqual(exclusion.reasonCode, 'EXPLICIT_PREFERENCE_BLOCKED')
}

function testExplicitCreatorPreferenceRanksAboveFollowOnlyCandidate() {
  const result = rank({
    surface: 'video',
    candidates: [
      candidate('followed', {
        creatorId: 'creator-followed',
        isFollowedCreator: true,
        feedSignals: { trend: 1 },
        trendScore: 1,
      }),
      candidate('preferred', {
        creatorId: 'creator-preferred',
        feedSignals: { trend: 0 },
      }),
    ],
    preferences: [{ surface: 'video', dimension: 'creator', subjectKey: 'creator-preferred', score: 10 }],
  })

  assert.deepStrictEqual(rankedIds(result), ['preferred', 'followed'])
  assert.strictEqual(result.ranked[0].score.explicit, 10)
}

function testOnlyPermittedOptedInInferenceCanChangeRanking() {
  const input = {
    surface: 'social',
    candidates: [
      candidate('dolphin-video', { topics: ['dolphins'] }),
      candidate('forest-video', { topics: ['forest'] }),
    ],
    inferredSignals: {
      likes: { topic: { dolphins: 0.8 } },
      direct_messages: { topic: { forest: 1 } },
      precise_location: { topic: { forest: 1 } },
      age: { topic: { forest: 1 } },
      gender: { topic: { forest: 1 } },
    },
  }
  const withoutPermission = rank({ ...input, dataPermissions: {} })
  assert.deepStrictEqual(rankedIds(withoutPermission), ['dolphin-video', 'forest-video'])

  const withLikesPermission = rank({
    ...input,
    dataPermissions: { use_likes: true },
  })
  assert.deepStrictEqual(rankedIds(withLikesPermission), ['dolphin-video', 'forest-video'])
  assert.strictEqual(withLikesPermission.ranked[0].score.permittedInference, 80)
  assert.deepStrictEqual(
    withLikesPermission.diagnostics.ignoredInferenceSources.sort(),
    ['age', 'direct_messages', 'gender', 'precise_location'],
  )
  assert.deepStrictEqual(FORBIDDEN_RANKING_INPUTS.includes('private_messages'), true)
}

function testApiFeedMixAliasesControlRanking() {
  const result = rank({
    surface: 'video',
    feedMix: {
      following: 0,
      interests: 100,
      communities: 0,
      newContent: 0,
      trends: 0,
      exploration: 0,
    },
    candidates: [
      candidate('followed', { feedSignals: { following: 1 } }),
      candidate('interest-match', { feedSignals: { interest: 1 } }),
    ],
  })

  assert.deepStrictEqual(rankedIds(result), ['interest-match', 'followed'])
  assert.deepStrictEqual(result.diagnostics.feedMix, {
    following: 0,
    interest: 100,
    community: 0,
    new_content: 0,
    trend: 0,
    exploration: 0,
  })
}

function testApiPermissionAliasesPermitOnlyTheirOptedInSource() {
  const result = rank({
    surface: 'social',
    dataPermissions: { watchTime: true },
    inferredSignals: {
      watch_time: { topic: { dolphins: 0.8 } },
      profile_visits: { topic: { forest: 1 } },
    },
    candidates: [
      candidate('dolphin-video', { topics: ['dolphins'] }),
      candidate('forest-video', { topics: ['forest'] }),
    ],
  })

  assert.deepStrictEqual(rankedIds(result), ['dolphin-video', 'forest-video'])
  assert.equal(result.ranked[0].score.permittedInference, 80)
  assert.deepStrictEqual(result.diagnostics.permittedInferenceSources, ['watch_time'])
}

function testZeroTrendAndExplorationWeightsAreRespected() {
  const result = rank({
    surface: 'video',
    limit: 2,
    feedMix: {
      following: 100,
      interests: 0,
      communities: 0,
      newContent: 0,
      trends: 0,
      exploration: 0,
    },
    candidates: [
      candidate('negative-trend-first', { feedSignals: { following: 1 }, trendScore: -1, explorationScore: -1 }),
      candidate('positive-trend-second', { feedSignals: { following: 1 }, trendScore: 1, explorationScore: 1 }),
    ],
  })

  assert.deepStrictEqual(rankedIds(result), ['negative-trend-first', 'positive-trend-second'])
  assert.equal(result.ranked[0].score.trends, 0)
  assert.equal(result.ranked[1].score.trends, 0)
  assert.equal(result.ranked[0].score.exploration, 0)
  assert.equal(result.ranked[1].score.exploration, 0)
}

function testPrivateDataFieldsNeverAffectResult() {
  const base = {
    surface: 'video',
    candidates: [
      candidate('one', { topics: ['dolphins'] }),
      candidate('two', { topics: ['forest'] }),
    ],
    inferredSignals: {
      private_messages: { topic: { forest: 1 } },
      exact_location: { topic: { forest: 1 } },
    },
    dataPermissions: { private_messages: true, exact_location: true },
  }
  const first = rank(base)
  const second = rank({
    ...base,
    candidates: [
      candidate('one', { topics: ['dolphins'], privateMessages: 'secret-a', age: 17, gender: 'x' }),
      candidate('two', { topics: ['forest'], privateMessages: 'secret-b', age: 80, gender: 'y' }),
    ],
  })
  assert.deepStrictEqual(rankedIds(first), rankedIds(second))
  assert.strictEqual(second.ranked[0].score.permittedInference, 0)
  assert.ok(second.diagnostics.ignoredInferenceSources.includes('private_messages'))
  assert.ok(second.diagnostics.ignoredInferenceSources.includes('exact_location'))
}

function testDiversityLimitsCreatorSubtopicAndDuplicate() {
  const result = rank({
    surface: 'video',
    limit: 10,
    diversityLimits: { maxPerCreator: 1, maxPerSubtopic: 1, maxPerDuplicate: 1 },
    candidates: [
      candidate('first', { creatorId: 'creator-a', subtopics: ['ocean'], trendScore: 1 }),
      candidate('same-creator', { creatorId: 'creator-a', subtopics: ['rainforest'], trendScore: 0.9 }),
      candidate('same-subtopic', { creatorId: 'creator-b', subtopics: ['ocean'], trendScore: 0.8 }),
      candidate('duplicate', { creatorId: 'creator-c', subtopics: ['mountain'], duplicateKey: 'first', trendScore: 0.7 }),
      candidate('alternative', { creatorId: 'creator-d', subtopics: ['desert'], trendScore: 0.1 }),
    ],
  })

  assert.deepStrictEqual(rankedIds(result), ['first', 'alternative'])
  const exclusionReasons = Object.fromEntries(result.excluded.map((entry) => [entry.candidateId, entry.reasonCode]))
  assert.strictEqual(exclusionReasons['same-creator'], 'DIVERSITY_CREATOR_LIMIT')
  assert.strictEqual(exclusionReasons['same-subtopic'], 'DIVERSITY_SUBTOPIC_LIMIT')
  assert.strictEqual(exclusionReasons.duplicate, 'DIVERSITY_DUPLICATE_LIMIT')
}

function testExplicitPreferencePrecedesCreatorDiversityLimit() {
  const result = rank({
    surface: 'video',
    diversityLimits: { maxPerCreator: 1, maxPerSubtopic: 5, maxPerDuplicate: 1 },
    candidates: [
      candidate('first', { creatorId: 'creator-a', topics: ['nature'], trendScore: 1 }),
      candidate('explicit-second', { creatorId: 'creator-a', topics: ['space'], trendScore: 0 }),
      candidate('alternative', { creatorId: 'creator-b', topics: ['desert'], trendScore: 0.8 }),
    ],
    preferences: [
      { surface: 'video', dimension: 'topic', subjectKey: 'nature', score: 50 },
      { surface: 'video', dimension: 'topic', subjectKey: 'space', score: 30 },
    ],
  })

  assert.deepStrictEqual(rankedIds(result), ['first', 'explicit-second', 'alternative'])
  const preferred = result.ranked.find((entry) => entry.candidateId === 'explicit-second')
  const diversityStage = preferred.explanation.stages.find((stage) => stage.stage === 'diversity')
  // The first direct preference occupies the creator slot. The second direct
  // preference is still allowed because the direct preference outranks the
  // diversity soft cap.
  assert.strictEqual(diversityStage.reasonCode, 'DIVERSITY_EXPLICIT_PREFERENCE_OVERRIDE')
}

function testStructuredExplanationIsTruthfulAndDoesNotInventSignals() {
  const result = rank({
    surface: 'live',
    candidates: [candidate('one', {
      creatorId: 'creator-a',
      topics: ['music'],
      isFollowedCreator: true,
      trendScore: 0.4,
    })],
    preferences: [{ surface: 'live', dimension: 'topic', subjectKey: 'music', score: 50 }],
  })
  const explanation = result.ranked[0].explanation
  const explicitStage = explanation.stages.find((stage) => stage.stage === 'explicit_preferences')
  const inferredStage = explanation.stages.find((stage) => stage.stage === 'permitted_inference')
  assert.strictEqual(explanation.truthful, true)
  assert.strictEqual(explicitStage.contribution, 50)
  assert.deepStrictEqual(explicitStage.matches, [{
    dimension: 'topic',
    subjectKey: 'music',
    score: 50,
    surface: 'live',
  }])
  assert.strictEqual(inferredStage.outcome, 'neutral')
  assert.deepStrictEqual(inferredStage.signals, [])
  assert.ok(explanation.privacy.neverUsed.includes('private_messages'))
}

Promise.resolve()
  .then(testSafetyPrecedesEveryOtherSignal)
  .then(testExplicitDolphinBlockDefeatsCreatorAndFollow)
  .then(testExplicitCreatorPreferenceRanksAboveFollowOnlyCandidate)
  .then(testOnlyPermittedOptedInInferenceCanChangeRanking)
  .then(testApiFeedMixAliasesControlRanking)
  .then(testApiPermissionAliasesPermitOnlyTheirOptedInSource)
  .then(testZeroTrendAndExplorationWeightsAreRespected)
  .then(testPrivateDataFieldsNeverAffectResult)
  .then(testDiversityLimitsCreatorSubtopicAndDuplicate)
  .then(testExplicitPreferencePrecedesCreatorDiversityLimit)
  .then(testStructuredExplanationIsTruthfulAndDoesNotInventSignals)
  .then(() => console.log('algorithm ranking tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
