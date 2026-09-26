'use strict'

const MIN_COMMUNITY_MUSIC_DURATION_MS = 5000
const MAX_COMMUNITY_MUSIC_DURATION_MS = 270000

function requireCommunityMusicDuration(value) {
  const durationMs = typeof value === 'number' || typeof value === 'string' ? Number(value) : NaN
  if (!Number.isFinite(durationMs) || durationMs < MIN_COMMUNITY_MUSIC_DURATION_MS) {
    throw Object.assign(new Error('VALID_AUDIO_DURATION_REQUIRED'), { status: 400 })
  }
  if (durationMs > MAX_COMMUNITY_MUSIC_DURATION_MS) {
    throw Object.assign(new Error('MUSIC_TRIM_TO_4_30_REQUIRED'), { status: 400 })
  }
  return Math.round(durationMs)
}

module.exports = { MIN_COMMUNITY_MUSIC_DURATION_MS, MAX_COMMUNITY_MUSIC_DURATION_MS, requireCommunityMusicDuration }
