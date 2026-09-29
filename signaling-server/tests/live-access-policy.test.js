const test = require('node:test');
const assert = require('node:assert/strict');
const { classifyLiveLookupFailure } = require('../live-access-policy');

test('confirmed ban keeps its moderation result on rejoin', () => {
    assert.equal(classifyLiveLookupFailure({ status: 403, message: 'LIVE_BANNED' }), 'banned');
});
test('only 404 means a missing stream', () => {
    assert.equal(classifyLiveLookupFailure({ status: 404 }), 'missing');
    assert.equal(classifyLiveLookupFailure({ status: 503 }), 'unavailable');
    assert.equal(classifyLiveLookupFailure(new Error('Timeout')), 'unavailable');
});
test('other authorization failures never claim a confirmed ban', () => {
    assert.equal(classifyLiveLookupFailure({ status: 403, message: 'LIVE_PRIVATE' }), 'unavailable');
    assert.equal(classifyLiveLookupFailure({ status: 401 }), 'unavailable');
});
