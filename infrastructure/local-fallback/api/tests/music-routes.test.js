'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const {
  RIGHTS_TERMS_VERSION,
  ensureMusicTables,
  mapTrack,
  musicPreviewLocation,
  rawTrackId,
  registerMusicRoutes,
  scanMusicFile,
} = require('../src/music-routes')

test('generated previews keep their database name and disk path aligned', () => {
  const userId = '4b312b9f-6a86-41d3-90a9-a29462367d4e'
  const previewId = '13f0a0e0-7fca-4bf1-8dde-c2a40c4093f5'
  const preview = musicPreviewLocation(userId, previewId)
  assert.equal(preview.previewName, `${userId}/previews/${previewId}.mp3`)
  assert.equal(preview.previewPath, path.resolve(preview.musicRoot, preview.previewName))
})

test('community catalog and publishing routes are mounted', () => {
  const registered = []
  const app = {}
  for (const verb of ['get', 'post', 'put', 'patch', 'delete']) {
    app[verb] = (path) => registered.push(`${verb.toUpperCase()} ${path}`)
  }
  registerMusicRoutes(app, {
    pool: {},
    authRequired: () => {},
    getCurrentUser: async () => null,
    userIdentity: () => ({ values: [] }),
  })
  for (const route of [
    'GET /v1/data/music/catalog/search',
    'GET /v1/data/music/catalog/categories',
    'POST /v1/data/music/upload',
    'POST /v1/data/music/:trackId/publish',
  ]) {
    assert.ok(registered.includes(route), `falta registrar ${route}`)
  }
})

test('community music identifiers never accept arbitrary URLs or non UUID values', () => {
  const id = '13f0a0e0-7fca-4bf1-8dde-c2a40c4093f5'
  assert.equal(rawTrackId(`community:${id}`), id)
  assert.equal(rawTrackId(id), id)
  assert.equal(rawTrackId('https://evil.example/audio.mp3'), null)
  assert.equal(rawTrackId('../community-music/file.mp3'), null)
})

test('public client mapping preserves authoritative moderation and reuse fields', () => {
  const mapped = mapTrack({
    id: '13f0a0e0-7fca-4bf1-8dde-c2a40c4093f5',
    owner_id: '4b312b9f-6a86-41d3-90a9-a29462367d4e',
    title: 'Original',
    artist_name: 'Autora',
    collaborators: [],
    audio_url: 'https://novvin.example/storage/community-music/original.ogg',
    preview_url: 'https://novvin.example/storage/community-music/previews/original-30s.mp3',
    duration_ms: 12000,
    tags: ['ambiental'],
    rights_type: 'ORIGINAL_OWNER',
    license_name: 'Derechos controlados por el autor',
    visibility: 'PUBLIC_REUSABLE',
    requested_visibility: 'PUBLIC_REUSABLE',
    allow_reuse: true,
    allow_remix: false,
    moderation_state: 'APPROVED',
    created_at: '2026-08-14T00:00:00Z',
    updated_at: '2026-08-14T00:00:00Z',
  })
  assert.equal(mapped.id, 'community:13f0a0e0-7fca-4bf1-8dde-c2a40c4093f5')
  assert.equal(mapped.source, 'novvin_community')
  assert.equal(mapped.audioUrl, 'https://novvin.example/storage/community-music/original.ogg')
  assert.equal(mapped.previewUrl, 'https://novvin.example/storage/community-music/previews/original-30s.mp3')
  assert.equal(mapped.allowReuse, true)
  assert.equal(mapped.moderationState, 'APPROVED')
})

test('schema includes rights audit, moderation, anti-abuse listens and reports', async () => {
  const statements = []
  await ensureMusicTables({ query: async (sql) => { statements.push(String(sql)); return { rows: [] } } })
  const schema = statements.join('\n')
  assert.match(schema, /fallback_music_rights_acceptances/)
  assert.match(schema, /fallback_music_moderation_events/)
  assert.match(schema, /fallback_music_listens/)
  assert.match(schema, /counted BOOLEAN/)
  assert.match(schema, /fallback_music_reports/)
  assert.match(schema, /fallback_music_retired_hashes/)
  assert.match(schema, /fallback_music_not_interested/)
  assert.match(schema, /fallback_moment_music/)
  assert.match(schema, /embedded_in_media BOOLEAN/)
  assert.match(schema, /track_owner_id TEXT/)
  assert.match(schema, /used_duration_ms BIGINT/)
  assert.match(schema, /fallback_music_play_events/)
  assert.match(schema, /fallback_music_recommendations/)
  assert.match(schema, /source_sha256/)
  assert.match(schema, /ALTER TABLE fallback_moment_music ALTER COLUMN source_url DROP NOT NULL/)
  assert.match(schema, /ALTER TABLE fallback_moment_music ALTER COLUMN license_url DROP NOT NULL/)
  assert.match(RIGHTS_TERMS_VERSION, /draft$/)
})

test('production upload fails closed when antivirus is required but unavailable', async () => {
  const previousRequired = process.env.MUSIC_ANTIVIRUS_REQUIRED
  const previousCommand = process.env.MUSIC_ANTIVIRUS_COMMAND
  process.env.MUSIC_ANTIVIRUS_REQUIRED = 'true'
  delete process.env.MUSIC_ANTIVIRUS_COMMAND
  try {
    await assert.rejects(scanMusicFile('/not/read/when/no/scanner'), /MUSIC_ANTIVIRUS_UNAVAILABLE/)
  } finally {
    if (previousRequired === undefined) delete process.env.MUSIC_ANTIVIRUS_REQUIRED
    else process.env.MUSIC_ANTIVIRUS_REQUIRED = previousRequired
    if (previousCommand === undefined) delete process.env.MUSIC_ANTIVIRUS_COMMAND
    else process.env.MUSIC_ANTIVIRUS_COMMAND = previousCommand
  }
})
