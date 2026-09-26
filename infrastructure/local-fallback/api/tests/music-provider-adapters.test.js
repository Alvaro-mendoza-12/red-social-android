const assert = require('assert')
const {
  jamendoTrack,
  normalizedExternalLicense,
  openverseTrack,
} = require('../src/music-provider-adapters')

function test(name, block) {
  try {
    block()
    console.log(`PASS ${name}`)
  } catch (error) {
    console.error(`FAIL ${name}`)
    throw error
  }
}

test('Openverse only accepts matching CC0/Public Domain code and canonical URL', () => {
  assert.equal(normalizedExternalLicense('cc0', 'https://creativecommons.org/publicdomain/zero/1.0/').name, 'CC0 1.0')
  assert.equal(normalizedExternalLicense('pdm', 'https://creativecommons.org/publicdomain/mark/1.0/').name, 'Public Domain')
  assert.equal(normalizedExternalLicense('by', 'https://creativecommons.org/licenses/by/4.0/'), null)
  assert.equal(normalizedExternalLicense('cc0', 'https://attacker.invalid/publicdomain/zero/1.0/'), null)
})

test('Openverse adapter rejects unverified and unsafe tracks', () => {
  const base = {
    id: '36537842-b067-4ca0-ad67-e00ff2e06b2e',
    title: 'Legal track',
    creator: 'Artist',
    url: 'https://cdn.example.org/audio.mp3',
    foreign_landing_url: 'https://example.org/source',
    duration: 12000,
    license: 'cc0',
    license_url: 'https://creativecommons.org/publicdomain/zero/1.0/',
  }
  assert.equal(openverseTrack(base).allowUseInMoments, true)
  assert.equal(openverseTrack({ ...base, license: 'by' }), null)
  assert.equal(openverseTrack({ ...base, url: 'http://example.org/audio.mp3' }), null)
  assert.equal(openverseTrack({ ...base, duration: 2000 }), null)
})

test('Jamendo adapter does not admit ordinary commercial/CC-BY catalog licenses', () => {
  assert.equal(jamendoTrack({
    id: '1', name: 'Track', artist_name: 'Artist', duration: 120,
    audio: 'https://cdn.jamendo.example/track.mp3',
    shareurl: 'https://www.jamendo.com/track/1',
    license_ccurl: 'https://creativecommons.org/licenses/by-nc-sa/3.0/',
  }), null)
})

console.log('music provider adapter tests completed')
