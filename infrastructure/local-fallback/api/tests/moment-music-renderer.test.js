'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const {
  isPublicIpAddress,
  isPublicHttpsUrl,
  momentMusicTranscodeArgs,
  resolvePublicAddresses,
} = require('../src/moment-music-renderer')

test('Moment music renderer always targets baseline AAC-LC stereo in M4A', () => {
  const args = momentMusicTranscodeArgs({
    inputPath: '/tmp/source.ogg',
    outputPath: '/tmp/result.m4a',
    startMs: 12_345,
    durationMs: 6_000,
  })
  assert.deepEqual(args.slice(args.indexOf('-ss'), args.indexOf('-ss') + 2), ['-ss', '12.345'])
  assert.deepEqual(args.slice(args.indexOf('-t'), args.indexOf('-t') + 2), ['-t', '6.000'])
  assert.deepEqual(args.slice(args.indexOf('-stream_loop'), args.indexOf('-stream_loop') + 2), ['-stream_loop', '-1'])
  assert.deepEqual(args.slice(args.indexOf('-af'), args.indexOf('-af') + 2), ['-af', 'apad'])
  assert.equal(args[args.indexOf('-c:a') + 1], 'aac')
  assert.equal(args[args.indexOf('-profile:a') + 1], 'aac_low')
  assert.equal(args[args.indexOf('-ar') + 1], '44100')
  assert.equal(args[args.indexOf('-ac') + 1], '2')
  assert.equal(args[args.indexOf('-f') + 1], 'ipod')
})

test('remote renderer input rejects local and non-HTTPS targets', () => {
  assert.equal(isPublicHttpsUrl('https://upload.wikimedia.org/file.ogg'), true)
  assert.equal(isPublicHttpsUrl('http://upload.wikimedia.org/file.ogg'), false)
  assert.equal(isPublicHttpsUrl('https://localhost/file.ogg'), false)
  assert.equal(isPublicHttpsUrl('https://127.0.0.1/file.ogg'), false)
  assert.equal(isPublicHttpsUrl('https://192.168.1.8/file.ogg'), false)
  assert.equal(isPublicHttpsUrl('https://[::1]/file.ogg'), false)
  assert.equal(isPublicHttpsUrl('https://100.64.0.1/file.ogg'), false)
  assert.equal(isPublicHttpsUrl('https://[::ffff:127.0.0.1]/file.ogg'), false)
})

test('DNS pinning accepts only globally routable unicast addresses', async () => {
  assert.equal(isPublicIpAddress('8.8.8.8'), true)
  assert.equal(isPublicIpAddress('2606:4700:4700::1111'), true)
  for (const address of [
    '0.0.0.0',
    '10.0.0.1',
    '100.64.0.1',
    '127.0.0.1',
    '169.254.169.254',
    '172.16.0.1',
    '192.168.0.1',
    '::1',
    'fc00::1',
    'fe80::1',
    '::ffff:127.0.0.1',
  ]) {
    assert.equal(isPublicIpAddress(address), false, `${address} must not be downloadable`)
  }
  await assert.rejects(
    resolvePublicAddresses('127.0.0.1'),
    (error) => error?.statusCode === 400 && error?.message === 'MOMENT_MUSIC_SOURCE_DNS_PRIVATE',
  )
})
