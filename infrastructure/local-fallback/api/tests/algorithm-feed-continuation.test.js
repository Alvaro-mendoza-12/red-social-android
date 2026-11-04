'use strict';
const assert = require('node:assert/strict');
const { rankAlgorithmCandidates } = require('../src/algorithm-ranking');
const { registerAlgorithmRecommendationRoutes } = require('../src/algorithm-recommendation-routes');
const NOW = 1700000000000;
const candidate = (i, changes = {}) => ({ id: 'video-' + i, creatorId: 'creator-one', topics: ['nature'],
  subtopics: ['wildlife'], item: { id: 'video-' + i, video_url: 'https://media.example.test/' + i }, ...changes });
const cases = [];
function checked(id, fn) { fn(); cases.push({ id, passed: true }); }
checked('strict-isolated-mode-remains-two', () => {
  const result = rankAlgorithmCandidates({ candidates: Array.from({ length: 45 }, (_, i) => candidate(i)),
    surface: 'video', limit: 200, now: NOW });
  assert.equal(result.ranked.length, 2);
});
checked('continued-feed-keeps-forty-five-same-topic-creators', () => {
  const result = rankAlgorithmCandidates({ candidates: Array.from({ length: 45 }, (_, i) => candidate(i)),
    surface: 'video', limit: 200, now: NOW, diversityMode: 'spread' });
  assert.equal(result.ranked.length, 45);
  assert.equal(new Set(result.ranked.map(x => x.candidateId)).size, 45);
});
checked('spread-still-denies-duplicates-unsafe-and-explicitly-blocked', () => {
  const candidates = Array.from({ length: 45 }, (_, i) => candidate(i));
  candidates.push(candidate('duplicate', { duplicateKey: 'video-0' }));
  candidates.push(candidate('removed', { safety: { status: 'removed' } }));
  candidates.push(candidate('blocked', { topics: ['blocked-topic'] }));
  const result = rankAlgorithmCandidates({ candidates, surface: 'video', limit: 200, now: NOW,
    diversityMode: 'spread', preferences: [{ surface: 'video', dimension: 'topic', subjectKey: 'blocked-topic', score: -100 }] });
  assert.equal(result.ranked.length, 45);
  assert.deepEqual(new Set(result.excluded.map(x => x.reasonCode)), new Set([
    'DIVERSITY_DUPLICATE_LIMIT', 'SAFETY_MODERATION_OR_POLICY', 'EXPLICIT_PREFERENCE_BLOCKED']));
});
async function routeCase() {
  let handler;
  registerAlgorithmRecommendationRoutes({ get: (_route, _auth, fn) => { handler = fn; } }, {
    hydrateItems: async ({ items }) => items,
    pool: {}, authRequired: () => {}, getCurrentUser: async () => ({ id: 'owner' }),
    userIdentity: user => ({ localUserId: user.id }),
    algorithmFeatureFlags: () => ({ core: true, explanations: true }),
    findOwnedProfile: async () => ({ id: 'profile', revision: 1, is_enabled: true, mode: 'manual' }),
    loadAlgorithmConfiguration: async () => ({ preferences: [], feedMix: {}, dataPermissions: {} }),
    loadCandidates: async () => ({ snapshotId: 'same-snapshot', candidates: Array.from({ length: 45 }, (_, i) => candidate(i)) }),
    cursorSecret: 'synthetic-local-cursor-test-secret-at-least-32-bytes', now: () => NOW,
  });
  async function invoke(query) {
    const response = { code: 200, status(code) { this.code = code; return this; }, setHeader() {}, json(body) { this.body = body; } };
    await handler({ query }, response, error => { throw error; });
    assert.equal(response.code, 200);
    return response.body;
  }
  const first = await invoke({ surface: 'video', limit: 20 });
  assert.equal(first.items.length, 20); assert.equal(first.page.hasMore, true);
  const second = await invoke({ surface: 'video', limit: 20, cursor: first.page.nextCursor });
  assert.equal(second.items.length, 20); assert.equal(second.page.hasMore, true);
  const third = await invoke({ surface: 'video', limit: 20, cursor: second.page.nextCursor });
  assert.equal(third.items.length, 5); assert.equal(third.page.hasMore, false);
  assert.equal(new Set([...first.items, ...second.items, ...third.items].map(x => x.id)).size, 45);
  cases.push({ id: 'actual-route-pages-twenty-twenty-five-without-duplicates', passed: true });
  const variable = await invoke({ surface: 'video', limit: 7, cursor: first.page.nextCursor });
  assert.deepEqual(variable.items.map(x => x.id), second.items.slice(0, 7).map(x => x.id));
  cases.push({ id: 'cursor-keeps-ranking-when-page-limit-changes', passed: true });
}
routeCase().then(() => console.log('feed continuation: five regression cases passed')).catch(error => { console.error(error); process.exitCode = 1; });
