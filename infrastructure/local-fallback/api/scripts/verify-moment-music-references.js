'use strict'

const { Client } = require('pg')
const { verifyApprovedMomentMusic } = require('../src/moments-routes')
const { canonicalStorageUrl } = require('../src/storage-url-canonicalization')

// Read-only contract check against existing approved catalog metadata. Never
// publishes a Moment, downloads media, or prints a user's identifiers or URLs.
async function verifyMomentMusicReferences(client, currentBaseUrl) {
  const base = new URL(currentBaseUrl)
  if (base.protocol !== 'https:' || base.username || base.password || base.pathname !== '/') {
    throw new Error('HTTPS_ROOT_ORIGIN_REQUIRED')
  }
  await client.query('BEGIN READ ONLY')
  try {
    await client.query("SET LOCAL statement_timeout = '5000ms'")
    const { rows } = await client.query(`
      SELECT id,audio_url
      FROM fallback_music_tracks
      WHERE moderation_state='APPROVED'
        AND visibility IN ('PUBLIC_REUSABLE','PUBLIC_REMIXABLE')
        AND allow_reuse=TRUE AND removed_at IS NULL AND disabled_at IS NULL
      ORDER BY created_at DESC
      LIMIT 10`)
    let observedHostRotations = 0
    let verified = 0
    for (const track of rows) {
      const currentUrl = canonicalStorageUrl(track.audio_url, base.origin)
      const result = await verifyApprovedMomentMusic(client, {
        musicSource: 'novvin_community', musicId: `community:${track.id}`,
        musicAudioUrl: currentUrl, musicStartMs: 0, musicEndMs: 6000,
      })
      if (result.audioUrl !== track.audio_url || result.id !== `community:${track.id}`) {
        throw new Error('AUTHORITATIVE_CATALOG_REFERENCE_CHANGED')
      }
      if (currentUrl !== track.audio_url) observedHostRotations++
      verified++
    }
    return { ok: true, verifiedTracks: verified, observedHostRotations,
      skipped: verified === 0,
      scope: 'Read-only catalog validation; not upload, rendering, publication or phone playback' }
  } finally {
    await client.query('ROLLBACK')
  }
}

if (require.main === module || process.argv[1] === '-') {
  ;(async () => {
    if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL_REQUIRED')
    const client = new Client({ connectionString: process.env.DATABASE_URL, connectionTimeoutMillis: 5000 })
    await client.connect()
    try {
      console.log(JSON.stringify(await verifyMomentMusicReferences(client, process.env.SMOKE_API_BASE_URL), null, 2))
    } finally {
      await client.end()
    }
  })().catch(() => {
    console.error('Moment music reference verification failed; no sensitive metadata logged.')
    process.exitCode = 1
  })
}

module.exports = { verifyMomentMusicReferences }
