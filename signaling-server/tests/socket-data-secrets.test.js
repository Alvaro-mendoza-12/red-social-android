'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

test('bearer tokens are never stored in adapter-visible socket.data', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '..', 'index.js'), 'utf8');
    assert.doesNotMatch(source, /socket\.data\.(?:authToken|accessToken|bearerToken)\s*=/);
    assert.match(source, /const socketAuthTokens = new WeakMap\(\)/);
    assert.match(source, /socketAuthTokens\.set\(socket, accessToken\)/);
});

test('live gift broadcasts and battle scores require an authoritative committed receipt', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '..', 'index.js'), 'utf8');
    assert.doesNotMatch(source, /(?:socket\.on\(|safeSocketOn\(socket,\s*)['"]live-gift['"]/);
    assert.match(source, /safeSocketOn\(socket,\s*['"]gift-committed['"]/);
    assert.match(source, /get_virtual_gift_receipt/);
    assert.match(source, /session\.battle\.hostScore \+= committedAmount/);
});
