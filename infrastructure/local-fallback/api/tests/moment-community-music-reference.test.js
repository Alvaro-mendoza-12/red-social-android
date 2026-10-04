'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { verifyApprovedMomentMusic } = require('../src/moments-routes')

const id = 'e0323c78-89ee-41bc-bf19-ae0587a2b001'
const storedUrl = 'https://old-tunnel.example/storage/v1/object/public/community-music/owner/Canci%C3%B3n%20Uno.m4a'
const track = { id, owner_id: 'owner', title: 'Canción Uno', artist_name: 'Autora',
  audio_url: storedUrl, duration_ms: 30_000, license_name: 'Derechos del autor' }
const body = { musicId: `community:${id}`, musicSource: 'novvin_community',
  musicAudioUrl: storedUrl, musicStartMs: 2000, musicEndMs: 8000,
  musicVolume: 0.4, originalAudioVolume: 0.6 }
const pool = { query: async (sql, values) => {
  assert.match(sql, /moderation_state='APPROVED'/)
  assert.match(sql, /allow_reuse=TRUE/)
  assert.match(sql, /removed_at IS NULL AND disabled_at IS NULL/)
  assert.deepEqual(values, [id])
  return { rows: [track] }
} }

test('a fifteen second video can keep a chorus two minutes into the full community song', async () => {
  const fullSongPool = { query: async () => ({ rows: [{ ...track, duration_ms: 270000 }] }) }
  const verified = await verifyApprovedMomentMusic(fullSongPool, { ...body, musicStartMs: 120000, musicEndMs: 135000 })
  assert.equal(verified.audioUrl, storedUrl)
  assert.equal(verified.startMs, 120000)
  assert.equal(verified.endMs, 135000)
  assert.equal(verified.durationMs, 15000)
})

test('a late selection can end at song end but cannot start past it', async () => {
  const fullSongPool = { query: async () => ({ rows: [{ ...track, duration_ms: 270000 }] }) }
  const verified = await verifyApprovedMomentMusic(fullSongPool, { ...body, musicStartMs: 255000, musicEndMs: 270000 })
  assert.equal(verified.startMs, 255000)
  assert.equal(verified.endMs, 270000)
  await assert.rejects(verifyApprovedMomentMusic(fullSongPool, { ...body, musicStartMs: 270000, musicEndMs: 285000 }),
    { message: 'MUSIC_CLIP_INVALID', statusCode: 400 })
})

test('a community song survives backend hostname rotation without trusting client metadata', async () => {
  const verified = await verifyApprovedMomentMusic(pool, { ...body,
    musicAudioUrl: storedUrl.replace('old-tunnel.example', 'current-tunnel.example'),
    musicTitle: 'Forged title', musicArtist: 'Forged author' })
  assert.equal(verified.audioUrl, storedUrl)
  assert.equal(verified.title, track.title)
  assert.equal(verified.artist, track.artist_name)
  assert.equal(verified.durationMs, 6000)
  assert.equal(verified.musicVolume, 0.4)
  assert.equal(verified.originalAudioVolume, 0.6)
})

test('signed, relative and percent-encoding equivalents refer to the selected storage object', async () => {
  for (const musicAudioUrl of [
    '/storage/v1/object/public/community-music/owner/Canci%C3%B3n%20Uno.m4a',
    storedUrl.replace('/public/', '/sign/') + '?token=rotated',
    storedUrl.replace('%C3%B3', 'ó'),
  ]) {
    const verified = await verifyApprovedMomentMusic(pool, { ...body, musicAudioUrl })
    assert.equal(verified.audioUrl, storedUrl)
  }
})

test('another object, owner, bucket, path case or external URL is not the selected song', async () => {
  for (const musicAudioUrl of [
    storedUrl.replace('Uno', 'Dos'), storedUrl.replace('/owner/', '/someone/'),
    storedUrl.replace('/community-music/', '/chat-media/'), storedUrl.replace('Canci', 'canci'),
    'https://other.example/owner/Canci%C3%B3n%20Uno.m4a',
    storedUrl.replace('/owner/', '/owner%2F'),
    storedUrl.replace('/owner/', '/owner/%2E%2E/'),
    storedUrl.replace('https://', 'file://'),
    storedUrl.replace('https://', 'https://login:password@'),
    storedUrl.replace('/owner/', '/owner%00/'),
  ]) {
    await assert.rejects(verifyApprovedMomentMusic(pool, { ...body, musicAudioUrl }),
      { message: 'MUSIC_AUDIO_URL_MISMATCH', statusCode: 400 })
  }
})

test('catalog ID remains authoritative and removed or unapproved tracks stay rejected', async () => {
  await assert.rejects(verifyApprovedMomentMusic({ query: async () => ({ rows: [] }) }, body),
    { message: 'MUSIC_LICENSE_NOT_APPROVED', statusCode: 400 })
  const verified = await verifyApprovedMomentMusic(pool, { ...body, musicAudioUrl: undefined })
  assert.equal(verified.audioUrl, storedUrl)
})

test('community lyrics are resolved with the approved track, never overwritten by the client', async () => {
  const lyrics = '[00:00]QA first\n[00:03]QA next'
  const lyricsPool = { query: async sql => {
    assert.match(sql, /moderation_state,lyrics/)
    return { rows: [{ ...track, lyrics }] }
  } }
  const verified = await verifyApprovedMomentMusic(lyricsPool, { ...body, musicLyrics: '[00:00]Forged' })
  assert.equal(verified.lyrics, lyrics)
  const untimed = await verifyApprovedMomentMusic({ query: async () => ({ rows: [{ ...track, lyrics: 'Untimed' }] }) }, body)
  assert.equal(untimed.lyrics, null)
})
