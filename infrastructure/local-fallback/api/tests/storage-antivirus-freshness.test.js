'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { recentSignature, dailyFileRecent } = require('../../storage-antivirus/healthcheck')
const published = Date.parse('2026-10-18T06:24:12Z')
const version = 'ClamAV 1.4.6/28157/Sun Oct 18 06:24:12 2026| COMMANDS: INSTREAM VERSIONCOMMANDS'

test('daemon health accepts current official signatures through 48 hours, fails closed afterwards', () => {
  for (const hours of [0, 23, 25, 47.9, 48, 48.001, 72]) {
    assert.equal(recentSignature(version, published + hours * 3600000), hours <= 48)
  }
  assert.equal(recentSignature(version, published - 60001), false)
  assert.equal(recentSignature(version.replace(' INSTREAM', ''), published), false)
  assert.equal(recentSignature(version.replace(' VERSIONCOMMANDS', ''), published), false)
  assert.equal(recentSignature('PONG', published), false)
})

test('updater health uses database build time and the same 48 hour bound', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-av-freshness-'))
  const file = path.join(directory, 'daily.cld')
  try {
    // CVD header: magic, build time, version, signatures, functionality, MD5, signature, builder, timestamp.
    fs.writeFileSync(file, 'ClamAV-VDB:18 Oct 2026:28157:355765:90:md5:signature:builder:' + published / 1000)
    for (const hours of [25, 48, 48.001, 72]) assert.equal(dailyFileRecent(file, published + hours * 3600000), hours <= 48)
    assert.equal(dailyFileRecent(file, published - 60001), false)
    fs.writeFileSync(file, 'malformed database')
    assert.equal(dailyFileRecent(file, published), false)
    assert.equal(dailyFileRecent(path.join(directory, 'missing'), published), false)
  } finally {
    fs.unlinkSync(file)
    fs.rmdirSync(directory)
  }
})
