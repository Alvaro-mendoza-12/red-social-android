const assert = require('assert')
const {
  isMeaningfulProfileValue,
  mergeProfileRows,
} = require('../src/profile-merge')

function run() {
  const imported = {
    id: 'profile-1',
    display_name: 'Nombre importado',
    avatar_url: 'https://cdn.example/avatar-real.webp',
    cover_url: 'https://cdn.example/cover-real.webp',
    avatar_media_type: 'video',
    avatar_duration_ms: 6400,
    media_metadata: { codec: 'h264' },
    is_private: true,
    score: 7,
  }
  const fallbackWithoutMedia = {
    id: 'local-1',
    display_name: 'Nombre actualizado',
    avatar_url: '   ',
    cover_url: null,
    avatar_media_type: '',
    avatar_duration_ms: 0,
    media_metadata: {},
    is_private: false,
    score: 0,
  }

  const importedSnapshot = structuredClone(imported)
  const fallbackSnapshot = structuredClone(fallbackWithoutMedia)
  const preserved = mergeProfileRows(imported, fallbackWithoutMedia)

  assert.strictEqual(preserved.display_name, 'Nombre actualizado')
  assert.strictEqual(preserved.avatar_url, imported.avatar_url)
  assert.strictEqual(preserved.cover_url, imported.cover_url)
  assert.strictEqual(preserved.avatar_media_type, imported.avatar_media_type)
  assert.strictEqual(preserved.avatar_duration_ms, imported.avatar_duration_ms)
  assert.deepStrictEqual(preserved.media_metadata, imported.media_metadata)
  assert.strictEqual(preserved.is_private, false, 'an explicit false remains meaningful')
  assert.strictEqual(preserved.score, 0, 'a non-media zero remains meaningful')
  assert.deepStrictEqual(imported, importedSnapshot, 'base profile must not be mutated')
  assert.deepStrictEqual(fallbackWithoutMedia, fallbackSnapshot, 'preferred profile must not be mutated')

  const fallbackWithRealMedia = {
    avatar_url: 'https://cdn.example/avatar-new.mp4',
    cover_url: 'https://cdn.example/cover-new.jpg',
    avatar_media_type: 'video/mp4',
    avatar_duration_ms: 8100,
    media_metadata: { codec: 'avc1', width: 720, height: 1280 },
    avatar_video_muted: false,
  }
  const replaced = mergeProfileRows(imported, fallbackWithRealMedia)
  assert.strictEqual(replaced.avatar_url, fallbackWithRealMedia.avatar_url)
  assert.strictEqual(replaced.cover_url, fallbackWithRealMedia.cover_url)
  assert.strictEqual(replaced.avatar_media_type, fallbackWithRealMedia.avatar_media_type)
  assert.strictEqual(replaced.avatar_duration_ms, fallbackWithRealMedia.avatar_duration_ms)
  assert.deepStrictEqual(replaced.media_metadata, fallbackWithRealMedia.media_metadata)
  assert.strictEqual(replaced.avatar_video_muted, false)

  const sentinelDefaults = mergeProfileRows(imported, {
    avatar_url: 'null',
    cover_url: 'none',
    avatar_media_type: 'unknown',
    avatar_duration_ms: '0',
  })
  assert.strictEqual(sentinelDefaults.avatar_url, imported.avatar_url)
  assert.strictEqual(sentinelDefaults.cover_url, imported.cover_url)
  assert.strictEqual(sentinelDefaults.avatar_media_type, imported.avatar_media_type)
  assert.strictEqual(sentinelDefaults.avatar_duration_ms, imported.avatar_duration_ms)

  assert.strictEqual(isMeaningfulProfileValue('avatar_url', ''), false)
  assert.strictEqual(isMeaningfulProfileValue('cover_url', 'default'), false)
  assert.strictEqual(isMeaningfulProfileValue('avatar_duration_ms', 0, 5000), false)
  assert.strictEqual(isMeaningfulProfileValue('avatar_duration_ms', 7000, 5000), true)

  const explicitlyCleared = mergeProfileRows(imported, {
    profile_welcome: '',
    avatar_url: '',
    __clear_fields: ['profile_welcome', 'avatar_url'],
  })
  assert.strictEqual(explicitlyCleared.profile_welcome, '')
  assert.strictEqual(explicitlyCleared.avatar_url, '')
  assert.strictEqual(Object.prototype.hasOwnProperty.call(explicitlyCleared, '__clear_fields'), false)
}

try {
  run()
  console.log('profile merge tests passed')
} catch (error) {
  console.error(error)
  process.exitCode = 1
}
