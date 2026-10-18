'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { liveReactionPolicy } = require('../live-reaction-policy');
test('only hearts increment the heart total', () => {
  for (const emoji of ['❤️', '❤', '💜', '💗']) assert.equal(liveReactionPolicy(emoji).heartDelta, 1);
  for (const emoji of ['👏', '🔥', '😮']) assert.deepEqual(liveReactionPolicy(emoji), { emoji, kind: emoji, heartDelta: 0 });
});
test('legacy clients default to hearts and unsupported effects are rejected', () => {
  assert.equal(liveReactionPolicy().kind, 'heart');
  for (const input of ['', 'invalid', {}, '❤️<script>']) assert.equal(liveReactionPolicy(input), null);
});
