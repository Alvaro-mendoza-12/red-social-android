'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { timedMusicLyrics, approvedSyncedLyrics, clippedMusicLyrics } = require('../src/moment-music-lyrics')

test('plain or malformed text never acquires invented timestamps', () => {
  for (const raw of [null, {}, 'Our untimed text', '[00:70]Invalid', '[999999:00]Invalid', '[00:01.1234]Invalid',
    '[offset:999999999999999999999]\n[00:01]Invalid', 'a'.repeat(12001)]) {
    assert.deepEqual(timedMusicLyrics(raw), [])
    assert.equal(approvedSyncedLyrics(raw), null)
  }
})
test('multiple tags, duplicates, offset and instrumental blanks preserve source transitions', () => {
  const result = timedMusicLyrics('[offset:-500]\n[00:01.20][00:03]First\n[00:01.20]First\n[00:02]\n[00:04]Last', 4000)
  assert.deepEqual(result, [
    {startMs:700,endMs:1500,text:'First'}, {startMs:1500,endMs:2500,text:''},
    {startMs:2500,endMs:3500,text:'First'}, {startMs:3500,endMs:4000,text:'Last'},
  ])
})
test('audio trim shifts actual lyric transitions and carries active line at start', () => {
  const raw = '[00:58]Earlier\n[01:02]Next\n[01:04]\n[01:05]Final\n[01:06]Outside'
  const clip = clippedMusicLyrics(raw, 60000, 6000)
  assert.deepEqual(timedMusicLyrics(clip, 6000), [
    {startMs:0,endMs:2000,text:'Earlier'}, {startMs:2000,endMs:4000,text:'Next'},
    {startMs:4000,endMs:5000,text:''}, {startMs:5000,endMs:6000,text:'Final'},
  ])
})
test('trim before first cue, within blank and outside clip does not create singing', () => {
  const raw = '[00:02]First\n[00:04]\n[00:08]Next'
  assert.equal(timedMusicLyrics(clippedMusicLyrics(raw, 0, 3000),3000)[0].startMs,2000)
  assert.equal(clippedMusicLyrics(raw,4000,3000),null)
  const afterGap = timedMusicLyrics(clippedMusicLyrics(raw,5000,4000),4000)
  assert.equal(afterGap[0].text,''); assert.equal(afterGap[1].startMs,3000)
  assert.equal(clippedMusicLyrics(raw,0,1000),null)
})
