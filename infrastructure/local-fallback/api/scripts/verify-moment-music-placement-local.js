'use strict'

// SQL/HTTP contract fixture only: storage ownership and the transcoder are
// isolated. It does not certify real authentication, media uploads or ffmpeg.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const express = require('express')
const renderer = require('../src/moment-music-renderer')
const realtime = require('../src/realtime-publish')
const originalRender = renderer.renderCompatibleMomentMusic
const originalPublish = realtime.publishRealtimeEvent
renderer.renderCompatibleMomentMusic = async ({ approvedMusic }) => approvedMusic
realtime.publishRealtimeEvent = async () => {}
const { registerMomentsRoutes, ensureMomentsTables } = require('../src/moments-routes')
renderer.renderCompatibleMomentMusic = originalRender
realtime.publishRealtimeEvent = originalPublish

async function main() {
  const connectionString = process.env.MOMENT_TEST_DATABASE_URL
  assert.ok(connectionString, 'Set MOMENT_TEST_DATABASE_URL to the disposable local database')
  const uri = new URL(connectionString)
  assert.ok(['127.0.0.1', 'localhost'].includes(uri.hostname))
  assert.equal(uri.pathname, '/novvin_surveys_test')
  const schema = `moment_placement_qa_${randomUUID().replaceAll('-', '')}`
  assert.match(schema, /^moment_placement_qa_[a-f0-9]{32}$/)
  const admin = new Pool({ connectionString, max: 1 })
  const pool = new Pool({ connectionString, options: `-c search_path=${schema}`, max: 4 })
  let created = false
  let server
  try {
    await admin.query(`CREATE SCHEMA "${schema}"`)
    created = true
    await pool.query(`
      CREATE TABLE fallback_users(id uuid PRIMARY KEY,email text,display_name text,avatar_url text,supabase_user_id uuid,
        created_at timestamptz DEFAULT now(),updated_at timestamptz,last_login_at timestamptz);
      CREATE TABLE fallback_profile_overrides(user_id uuid PRIMARY KEY,profile jsonb,updated_at timestamptz);
      CREATE TABLE fallback_follows(follower_id text,following_id text);
      CREATE TABLE fallback_chats(participants jsonb,is_group boolean);
      CREATE TABLE fallback_moment_music(moment_id uuid PRIMARY KEY,track_key text,provider text,title text,artist text,
        track_owner_id text,attribution text,source_url text,license_name text,license_url text,start_ms int,end_ms int,
        used_duration_ms int,music_volume real,original_audio_volume real,fade_in_ms int,fade_out_ms int,
        duck_original boolean,embedded_in_media boolean);
      CREATE TABLE fallback_music_play_events(track_key text,user_id uuid,event_type text,listened_ms int,context text);
    `)
    // Calling twice also verifies an upgrade is idempotent on an existing DB.
    await ensureMomentsTables(pool)
    await ensureMomentsTables(pool)
    const owner = randomUUID()
    await pool.query('INSERT INTO fallback_users(id,display_name) VALUES($1,$2)', [owner, 'Synthetic QA owner'])
    const identity = { ids: new Set([owner]), values: [owner], displayUserId: owner }
    const app = express()
    app.use(express.json())
    registerMomentsRoutes(app, { pool, authRequired: (_req, _res, next) => next(),
      getCurrentUser: async () => ({ id: owner }), userIdentity: () => identity,
      currentUserOwnsStorageObject: async () => true })
    app.use((error, _req, res, _next) => res.status(error.statusCode || 500).json({ error: error.message }))
    server = await new Promise(resolve => {
      const instance = app.listen(0, '127.0.0.1', () => resolve(instance))
    })
    const origin = `http://127.0.0.1:${server.address().port}`
    async function request(path, method = 'GET', body, status = 200) {
      const response = await fetch(origin + path, { method, signal: AbortSignal.timeout(10000),
        headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
      const json = await response.json()
      assert.equal(response.status, status, JSON.stringify(json))
      return json
    }
    const payload = { mediaUrl: 'https://media.example/qa.mp4', mediaType: 'video',
      musicId: 'device:qa', musicSource: 'novvin_moment_local', musicAudioUrl: 'https://media.example/qa.m4a',
      musicTitle: 'Synthetic QA track', musicArtist: 'Synthetic QA author', musicEmbedded: true,
      musicPresentation: 'lyrics', musicLyrics: '[00:00]QA first\n[00:02]QA next',
      overlayX: .1, overlayY: .2, musicOverlayX: .8, musicOverlayY: .7, musicOverlayScale: 1.3,
      musicOverlayRotation: -35 }
    await request('/v1/data/moments', 'POST', payload, 201)
    let feed = await request('/v1/data/moments/feed')
    const moment = feed.items.find(m => m.user_id === owner)
    assert.ok(moment)
    assert.equal(moment.music_presentation, 'lyrics')
    assert.equal(moment.music_lyrics, payload.musicLyrics)
    assert.ok(Math.abs(moment.music_overlay_x - .8) < 1e-6)
    assert.ok(Math.abs(moment.music_overlay_y - .7) < 1e-6)
    assert.ok(Math.abs(moment.music_overlay_scale - 1.3) < 1e-6)
    assert.equal(moment.music_overlay_rotation, -35)
    assert.ok(Math.abs(moment.overlay_x - .1) < 1e-6)
    await request(`/v1/data/moments/${moment.id}/music`, 'POST', { ...payload,
      musicPresentation: 'label', musicOverlayX: .4, musicOverlayRotation: 450 })
    feed = await request('/v1/data/moments/feed')
    const updated = feed.items.find(m => m.id === moment.id)
    assert.equal(updated.music_presentation, 'label')
    assert.ok(Math.abs(updated.music_overlay_x - .4) < 1e-6)
    assert.equal(updated.music_overlay_rotation, 90)
    await request(`/v1/data/moments/${moment.id}/music`, 'DELETE')
    feed = await request('/v1/data/moments/feed')
    const removed = feed.items.find(m => m.id === moment.id)
    assert.equal(removed.music_presentation, 'card')
    assert.equal(removed.music_lyrics, null)
    assert.deepEqual([removed.music_overlay_x,removed.music_overlay_y,removed.music_overlay_scale,removed.music_overlay_rotation], [.5,.5,1,0])
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM fallback_moment_music')).rows[0].n, 0)
    console.log(JSON.stringify({ ok: true, fixture: 'local PostgreSQL/HTTP moment placement',
      checks: ['idempotent migration', 'create/feed placement and LRC', 'separate text placement',
        'replace/rotation normalization', 'remove/reset', 'music relation cleanup'],
      storageAndAuthenticationIsolated: true, ffmpegTested: false }))
  } finally {
    if (server) await new Promise(resolve => { server.close(resolve); server.closeAllConnections() })
    await pool.end()
    if (created) await admin.query(`DROP SCHEMA "${schema}" CASCADE`)
    await admin.end()
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
