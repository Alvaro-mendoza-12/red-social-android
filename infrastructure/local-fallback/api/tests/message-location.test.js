const test = require('node:test')
const assert = require('node:assert/strict')
const { isMessageLocationUrl } = require('../src/message-location')
test('coordinates in a Google Maps location are allowed without pretending to be a file', () => {
  assert.equal(isMessageLocationUrl('https://maps.google.com/?q=-12.04318,-77.02824'), true)
  assert.equal(isMessageLocationUrl('https://maps.google.com/?q=90,180'), true)
})
test('arbitrary URLs, script schemes, credentials, parameters and invalid coordinates are not locations', () => {
  for (const url of ['javascript:alert(1)', 'https://example.com/?q=12,13', 'https://maps.google.com/?q=91,0',
    'https://maps.google.com/?q=0,181', 'https://maps.google.com/?q=NaN,0', 'https://maps.google.com/?q=0,0&redirect=https://evil.test',
    'https://maps.google.com/?q=1,2&q=3,4', 'https://user:pass@maps.google.com/?q=1,2', 'http://maps.google.com/?q=1,2']) assert.equal(isMessageLocationUrl(url), false, url)
})
