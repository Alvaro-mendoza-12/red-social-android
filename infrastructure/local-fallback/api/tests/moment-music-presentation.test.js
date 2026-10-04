'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
// This fixture tests route persistence, not ffmpeg or external audio providers.
const renderer = require('../src/moment-music-renderer')
const originalRender = renderer.renderCompatibleMomentMusic
renderer.renderCompatibleMomentMusic = async ({ approvedMusic }) => approvedMusic
const realtime = require('../src/realtime-publish')
const originalPublish = realtime.publishRealtimeEvent
realtime.publishRealtimeEvent = async () => {}
const { registerMomentsRoutes, buildMomentsFeed, normalizedMomentMusicForMedia,
  momentMusicPresentationFields, ensureMomentsTables } = require('../src/moments-routes')
renderer.renderCompatibleMomentMusic = originalRender
realtime.publishRealtimeEvent = originalPublish

const id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
const cover = 'https://catalog.example/cover.jpg'
const selection = { id: 'device:test', source: 'novvin_moment_local', startMs: 0,
  durationMs: 6000, endMs: 6000, audioUrl: 'https://media.example/audio.m4a' }

test('three presentation values survive image and video normalization; legacy defaults to card', () => {
  for (const presentation of ['card', 'label', 'audio']) for (const media of ['image', 'video']) {
    const result = normalizedMomentMusicForMedia(selection, media,
      { music_presentation: presentation, music_artwork_url: cover })
    assert.equal(result.presentation, presentation)
    assert.equal(result.artworkUrl, cover)
    assert.equal(result.durationMs, 6000)
  }
  assert.equal(normalizedMomentMusicForMedia(selection, 'video').presentation, 'card')
  assert.equal(momentMusicPresentationFields({ music_presentation: 'unknown' }, selection).presentation, 'card')
  assert.equal(normalizedMomentMusicForMedia(null, 'video'), null)
})

test('community cover is catalog data; unsafe artwork and absent music have no artwork', () => {
  const community = { ...selection, source: 'novvin_community', artworkUrl: cover }
  assert.equal(momentMusicPresentationFields({ music_artwork_url: 'https://forged.example/x' }, community).artworkUrl, cover)
  assert.equal(momentMusicPresentationFields({ music_artwork_url: cover }, { ...community, artworkUrl: null }).artworkUrl, null)
  for (const url of ['http://catalog.example/x', 'https://user:secret@catalog.example/x',
    'https://127.0.0.1/x', 'file:///x', 'https://localhost/x', 'x'.repeat(2049)]) {
    assert.equal(momentMusicPresentationFields({ music_artwork_url: url }, selection).artworkUrl, null)
  }
  assert.equal(momentMusicPresentationFields({ music_artwork_url: cover }).artworkUrl, null)
})

function fixture() {
  let moment
  const writes = []
  const pool = { async query(sql, params) {
    if (sql.includes('INSERT INTO fallback_moments(')) {
      const columns = sql.match(/INSERT INTO fallback_moments\(([\s\S]+?)\)\s*VALUES/)[1]
        .split(',').map(s => s.trim())
      assert.equal(columns.length, params.length)
      moment = { id, ...Object.fromEntries(columns.map((name, i) => [name, params[i]])) }
      writes.push(sql)
      return { rows: [moment] }
    }
    if (sql.includes('UPDATE fallback_moments SET')) {
      // Match parameter assignments to verify both field names AND placeholders.
      for (const match of sql.matchAll(/(\w+)\s*=\s*\$(\d+)/g)) moment[match[1]] = params[Number(match[2]) - 1]
      writes.push(sql)
      return { rows: [moment] }
    }
    if (sql.includes('music_presentation=\'card\',music_artwork_url=NULL')) {
      moment.music_presentation = 'card'; moment.music_artwork_url = null
      moment.music_overlay_x = .5; moment.music_overlay_y = .5
      moment.music_overlay_scale = 1; moment.music_overlay_rotation = 0; moment.music_lyrics = null
      writes.push(sql)
      return { rows: [{ id, music_audio_url: null }] }
    }
    if (sql.includes('FROM fallback_moments')) return { rows: moment ? [moment] : [] }
    return { rows: [] }
  } }
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'post', 'put', 'patch', 'delete']
    .map(verb => [verb, (path, ...stack) => handlers.set(`${verb} ${path}`, stack.at(-1))]))
  const identity = { ids: new Set(['owner']), values: ['owner'], displayUserId: 'owner' }
  registerMomentsRoutes(app, { pool, authRequired: () => {},
    getCurrentUser: async () => ({ id: 'owner' }), userIdentity: () => identity,
    currentUserOwnsStorageObject: async () => true })
  return { pool, writes, identity, async invoke(route, body) {
    const result = { status: 200 }
    const res = { status(value) { result.status = value; return res }, json(value) { result.body = value; return res } }
    await handlers.get(route)({ params: { momentId: id }, body }, res, error => { throw error })
    assert.equal(result.status, route === 'post /v1/data/moments' ? 201 : 200)
    return result.body
  } }
}

test('POST and feed round trip all three styles; replacing and removing music resets presentation', async () => {
  for (const presentation of ['card', 'label', 'audio']) {
    const f = fixture()
    const body = { media_type: 'video', media_url: 'https://media.example/video.mp4',
      music_id: 'device:test', music_source: 'novvin_moment_local', music_url: selection.audioUrl,
      music_title: 'Our song', music_artist: 'Our artist', music_embedded: true,
      music_presentation: presentation, music_artwork_url: cover }
    await f.invoke('post /v1/data/moments', body)
    const feed = await buildMomentsFeed(f.pool, f.identity)
    assert.equal(feed[0].music_presentation, presentation)
    assert.equal(feed[0].music_artwork_url, cover)
    await f.invoke('post /v1/data/moments/:momentId/music', { ...body, music_presentation: 'label' })
    assert.equal((await buildMomentsFeed(f.pool, f.identity))[0].music_presentation, 'label')
    await f.invoke('delete /v1/data/moments/:momentId/music', {})
    const removed = await buildMomentsFeed(f.pool, f.identity)
    assert.equal(removed[0].music_presentation, 'card')
    assert.equal(removed[0].music_artwork_url, null)
  }
})

test('existing databases migrate presentation with a non-null card default and nullable cover', async () => {
  const sql = []
  await ensureMomentsTables({ query: async statement => { sql.push(statement); return { rows: [] } } })
  assert.ok(sql.some(s => s.includes("ADD COLUMN IF NOT EXISTS music_presentation TEXT NOT NULL DEFAULT 'card'")))
  assert.ok(sql.some(s => s.includes('ADD COLUMN IF NOT EXISTS music_artwork_url TEXT')))
  for (const field of ['music_overlay_x REAL NOT NULL DEFAULT 0.5', 'music_overlay_y REAL NOT NULL DEFAULT 0.5',
    'music_overlay_scale REAL NOT NULL DEFAULT 1', 'music_overlay_rotation REAL NOT NULL DEFAULT 0', 'music_lyrics TEXT']) {
    assert.ok(sql.some(s => s.includes(`ADD COLUMN IF NOT EXISTS ${field}`)))
  }
})

test('lyrics are authoritative track metadata; plain lyrics fall back without fabricated timing', () => {
  const community = { ...selection, source: 'novvin_community', lyrics: '[00:01]Real cue' }
  const result = momentMusicPresentationFields({ musicPresentation: 'lyrics', musicLyrics: '[00:00]Forged' }, community)
  assert.equal(result.presentation, 'lyrics'); assert.equal(result.lyrics, community.lyrics)
  assert.equal(momentMusicPresentationFields({ musicPresentation: 'lyrics', musicLyrics: community.lyrics }, selection).presentation, 'card')
  assert.equal(momentMusicPresentationFields({ musicPresentation: 'lyrics' }, { ...community, lyrics: 'Untimed' }).presentation, 'card')
})

test('placement accepts camel and snake fields, normalizes hostile values, and defaults for old clients', () => {
  const value = momentMusicPresentationFields({ music_overlay_x: -3, musicOverlayY: 7,
    musicOverlayScale: Infinity, music_overlay_rotation: 450 }, selection)
  assert.deepEqual([value.overlayX, value.overlayY, value.overlayScale, value.overlayRotation], [0, 1, 1, 90])
  const legacy = momentMusicPresentationFields({}, selection)
  assert.deepEqual([legacy.overlayX, legacy.overlayY, legacy.overlayScale, legacy.overlayRotation], [.5,.5,1,0])
})

test('new moment feed preserves lyrics, separate text/music positions and replacement/removal', async () => {
  const f = fixture()
  const body = { media_type: 'video', media_url: 'https://media.example/video.mp4',
    music_id: 'device:test', music_source: 'novvin_moment_local', music_url: selection.audioUrl,
    music_title: 'Our song', music_artist: 'Our artist', music_embedded: true,
    music_presentation: 'lyrics', music_lyrics: '[00:00]First\n[00:02]Second',
    overlay_x: .1, overlay_y: .2, music_overlay_x: .8, music_overlay_y: .7,
    music_overlay_scale: 1.5, music_overlay_rotation: -35 }
  await f.invoke('post /v1/data/moments', body)
  const [created] = await buildMomentsFeed(f.pool, f.identity)
  assert.equal(created.music_presentation, 'lyrics'); assert.equal(created.music_lyrics, body.music_lyrics)
  assert.deepEqual([created.music_overlay_x,created.music_overlay_y,created.music_overlay_scale,created.music_overlay_rotation], [.8,.7,1.5,-35])
  assert.equal(created.overlay_x,.1); assert.equal(created.overlay_y,.2)
  assert.ok(f.writes[0].includes('music_overlay_rotation, music_lyrics'))
  await f.invoke('post /v1/data/moments/:momentId/music', { ...body, musicOverlayX: .3, musicOverlayRotation: 25 })
  const [updated] = await buildMomentsFeed(f.pool, f.identity)
  assert.equal(updated.music_overlay_x,.3); assert.equal(updated.music_overlay_rotation,25)
  await f.invoke('delete /v1/data/moments/:momentId/music', {})
  const [removed] = await buildMomentsFeed(f.pool, f.identity)
  assert.equal(removed.music_lyrics,null)
  assert.deepEqual([removed.music_overlay_x,removed.music_overlay_y,removed.music_overlay_scale,removed.music_overlay_rotation],[.5,.5,1,0])
})
