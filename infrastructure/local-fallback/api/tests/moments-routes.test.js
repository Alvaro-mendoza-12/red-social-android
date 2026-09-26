'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const {
  momentAudienceAllows,
  mergeMomentOwnerProfile,
  normalizedApprovedMusicLicense,
  normalizedMomentDisplayDurationMs,
  normalizedMomentMusicForMedia,
  isPlayableMomentMusicMime,
  verifyApprovedCommonsMusic,
  verifyOwnedLocalMomentMusic,
  momentNotificationExtraData,
  momentNotificationRecipientIds,
} = require('../src/moments-routes')

test('moment push expiry is an ISO string even when PostgreSQL returns a Date', () => {
  const expiresAt = new Date('2026-09-24T12:00:00.000Z')
  assert.deepEqual(
    momentNotificationExtraData({ id: 'moment-1', expires_at: expiresAt }),
    { moment_id: 'moment-1', expires_at: '2026-09-24T12:00:00.000Z' },
  )
})

test('selected moment viewers receive an alert only after opting in', async () => {
  const pool = {
    async query(sql, parameters) {
      assert.match(sql, /fallback_moment_notification_preferences/)
      assert.deepEqual(parameters, [['viewer-a', 'viewer-b']])
      return { rows: [{ user_id: 'viewer-b' }] }
    },
  }
  const recipients = await momentNotificationRecipientIds(pool, 'owner', {
    visibility: 'only_selected',
    audience_ids: ['viewer-a', 'viewer-b'],
  })
  assert.deepEqual(recipients, ['viewer-b'])
})

test('image moments never auto-advance before six seconds', () => {
  assert.equal(normalizedMomentDisplayDurationMs({}, 'image'), 6000)
  assert.equal(normalizedMomentDisplayDurationMs({ display_duration_ms: 2500 }, 'image'), 6000)
  assert.equal(normalizedMomentDisplayDurationMs({ displayDurationMs: 5500 }, 'image'), 6000)
  assert.equal(normalizedMomentDisplayDurationMs({ displayDurationMs: 120000 }, 'image'), 6000)
})

test('image moment soundtrack metadata is also clamped to the same six seconds', () => {
  const selected = { startMs: 12_000, endMs: 42_000, durationMs: 30_000 }
  const imageMusic = normalizedMomentMusicForMedia(selected, 'image')
  const videoMusic = normalizedMomentMusicForMedia(selected, 'video')

  assert.equal(imageMusic.durationMs, 6000)
  assert.equal(imageMusic.endMs, 18_000)
  assert.equal(videoMusic.durationMs, 30_000)
})

const viewer = new Set(['viewer'])
const follows = [
  { follower_id: 'viewer', following_id: 'owner' },
  { follower_id: 'owner', following_id: 'followed-by-owner' },
]
const chats = [{ participants: ['owner', 'viewer'], is_group: false }]
const row = (visibility, extra = {}) => ({ user_id: 'owner', visibility, visibility_type: 'public', ...extra })

test('moment audiences enforce follower, following and chat relationships', () => {
  assert.equal(momentAudienceAllows({ row: row('followers'), identityIds: viewer, followRows: follows }), true)
  assert.equal(momentAudienceAllows({ row: row('following'), identityIds: new Set(['followed-by-owner']), followRows: follows }), true)
  assert.equal(momentAudienceAllows({ row: row('chats'), identityIds: viewer, chatRows: chats }), true)
  assert.equal(momentAudienceAllows({ row: row('chats'), identityIds: new Set(['stranger']), chatRows: chats }), false)
})

test('selected and excluded audiences are applied exactly', () => {
  assert.equal(momentAudienceAllows({ row: row('only_selected', { audience_ids: ['viewer'] }), identityIds: viewer }), true)
  assert.equal(momentAudienceAllows({ row: row('only_selected', { audience_ids: ['someone-else'] }), identityIds: viewer }), false)
  assert.equal(momentAudienceAllows({ row: row('except_selected', { excluded_ids: ['viewer'] }), identityIds: viewer }), false)
  assert.equal(momentAudienceAllows({ row: row('except_selected', { excluded_ids: ['someone-else'] }), identityIds: viewer }), true)
})

test('owner always sees own moment and legacy private moments remain private', () => {
  assert.equal(momentAudienceAllows({ row: row('only_me'), identityIds: new Set(['owner']) }), true)
  assert.equal(momentAudienceAllows({ row: { user_id: 'owner', visibility_type: 'private' }, identityIds: viewer }), false)
})

test('moment owner metadata prefers the latest Novvin profile override', () => {
  const merged = mergeMomentOwnerProfile(
    {
      id: 'owner',
      display_name: 'Conectate',
      avatar_url: 'https://old.example/avatar.png',
      cover_url: 'https://old.example/cover.png',
    },
    {
      id: 'owner',
      display_name: 'Conectate',
      avatar_url: 'https://old.example/avatar.png',
      updated_at: '2025-01-01T00:00:00Z',
      profile_override_updated_at: '2026-08-12T00:00:00Z',
      profile_override: {
        display_name: 'Novvin',
        username: 'gamergamerff017',
        avatar_url: 'https://new.example/avatar.png',
      },
    },
  )

  assert.equal(merged.display_name, 'Novvin')
  assert.equal(merged.username, 'gamergamerff017')
  assert.equal(merged.avatar_url, 'https://new.example/avatar.png')
  assert.equal(merged.cover_url, 'https://old.example/cover.png')
  assert.equal(merged.updated_at, '2026-08-12T00:00:00Z')
})

test('moment music policy accepts only PD or CC0 and Android-playable containers', () => {
  assert.equal(normalizedApprovedMusicLicense('CC0'), 'CC0 1.0')
  assert.equal(normalizedApprovedMusicLicense('Creative Commons Zero, Public Domain Dedication'), 'CC0 1.0')
  assert.equal(normalizedApprovedMusicLicense('Public domain'), 'Public Domain')
  assert.equal(normalizedApprovedMusicLicense('CC BY 4.0'), null)
  assert.equal(normalizedApprovedMusicLicense('CC BY-SA 3.0'), null)

  assert.equal(isPlayableMomentMusicMime('audio/mpeg'), true)
  assert.equal(isPlayableMomentMusicMime('application/ogg'), true)
  assert.equal(isPlayableMomentMusicMime('audio/flac'), true)
  assert.equal(isPlayableMomentMusicMime('audio/midi'), false)
  assert.equal(isPlayableMomentMusicMime('text/html'), false)
})

test('server revalidates Commons metadata instead of trusting client music fields', async () => {
  const originalFetch = global.fetch
  global.fetch = async () => ({
    ok: true,
    async json() {
      return {
        query: {
          pages: [{
            pageid: 42,
            title: 'File:Verified track.ogg',
            canonicalurl: 'https://commons.wikimedia.org/wiki/File:Verified_track.ogg',
            imageinfo: [{
              mime: 'application/ogg',
              duration: 90,
              url: 'https://upload.wikimedia.org/wikipedia/commons/a/ab/Verified_track.ogg',
              descriptionurl: 'https://commons.wikimedia.org/wiki/File:Verified_track.ogg',
              extmetadata: {
                LicenseShortName: { value: 'CC0' },
                LicenseUrl: { value: 'https://creativecommons.org/publicdomain/zero/1.0/' },
                Artist: { value: '<b>Verified Artist</b>' },
              },
            }],
          }],
        },
      }
    },
  })

  try {
    const verified = await verifyApprovedCommonsMusic({
      musicId: 'commons:42',
      musicSource: 'wikimedia_commons',
      musicAudioUrl: 'https://upload.wikimedia.org/wikipedia/commons/a/ab/Verified_track.ogg',
      musicSourceUrl: 'https://commons.wikimedia.org/wiki/File:Verified_track.ogg',
      musicStartMs: 10_000,
      musicEndMs: 25_000,
      musicVolume: 0.7,
      originalAudioVolume: 0.4,
      // These two values must be ignored in favor of authoritative metadata.
      musicTitle: 'Client title',
      musicArtist: 'Client artist',
    })
    assert.equal(verified.title, 'Verified track')
    assert.equal(verified.artist, 'Verified Artist')
    assert.equal(verified.license, 'CC0 1.0')
    assert.equal(verified.durationMs, 15_000)

    await assert.rejects(
      verifyApprovedCommonsMusic({
        musicId: 'commons:42',
        musicSource: 'wikimedia_commons',
        musicAudioUrl: 'https://evil.example/track.ogg',
        musicSourceUrl: 'https://commons.wikimedia.org/wiki/File:Verified_track.ogg',
      }),
      /MUSIC_LICENSE_NOT_APPROVED/,
    )
  } finally {
    global.fetch = originalFetch
  }
})

test('local Moment music is accepted only when its storage object belongs to the author', async () => {
  const body = {
    musicId: 'device:abc123',
    musicSource: 'novvin_moment_local',
    musicAudioUrl: 'https://novvin.example/storage/v1/object/public/community-music/user/moments/audio.m4a',
    musicTitle: 'Canción detectada',
    musicArtist: 'Audio del dispositivo',
    musicStartMs: 0,
    musicDurationMs: 6000,
  }
  const user = { id: '11111111-1111-1111-1111-111111111111' }
  const owned = await verifyOwnedLocalMomentMusic({}, body, {
    user,
    currentUserOwnsStorageObject: async (_pool, userId, url, buckets) =>
      userId === user.id && url === body.musicAudioUrl && buckets.includes('community-music'),
  })

  assert.equal(owned.source, 'novvin_moment_local')
  assert.equal(owned.durationMs, 6000)
  assert.equal(owned.title, 'Canción detectada')

  const legacyOwned = await verifyOwnedLocalMomentMusic({}, {
    ...body,
    musicSource: undefined,
  }, {
    user,
    currentUserOwnsStorageObject: async (_pool, userId, url, buckets) =>
      userId === user.id && url === body.musicAudioUrl && buckets.includes('community-music'),
  })
  assert.equal(legacyOwned.source, 'novvin_moment_local')

  const providerLabelOwned = await verifyOwnedLocalMomentMusic({}, {
    ...body,
    musicSource: 'Mi dispositivo',
  }, {
    user,
    currentUserOwnsStorageObject: async (_pool, userId, url, buckets) =>
      userId === user.id && url === body.musicAudioUrl && buckets.includes('community-music'),
  })
  assert.equal(providerLabelOwned.source, 'novvin_moment_local')

  await assert.rejects(
    verifyOwnedLocalMomentMusic({}, body, {
      user,
      currentUserOwnsStorageObject: async () => false,
    }),
    /MOMENT_MUSIC_STORAGE_OWNERSHIP_REQUIRED/,
  )
})
