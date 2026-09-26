const ATTEMPT_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const { normalizeVideoChapters } = require('./video-chapters')

function validVideoPublishAttemptId(value) {
  return ATTEMPT_ID_PATTERN.test(String(value || '').trim())
}

function sameVideoPublishPayload(existing, requested) {
  if (!existing) return false
  return existing.video_url === requested.videoUrl &&
    (existing.cover_url || null) === (requested.coverUrl || null) &&
    existing.title === requested.title &&
    existing.description === requested.description &&
    Number(existing.duration_seconds) === requested.durationSeconds &&
    Number(existing.trim_start_seconds) === requested.trimStart &&
    Number(existing.trim_end_seconds) === requested.trimEnd &&
    Boolean(existing.is_public) === requested.isPublic &&
    Boolean(existing.is_sensitive) === requested.isSensitive &&
    (existing.allow_clip_sharing !== false) === requested.allowClipSharing &&
    JSON.stringify(existing.tags || []) === JSON.stringify(requested.tags || []) &&
    JSON.stringify(normalizeVideoChapters(existing.chapters || [], requested.durationSeconds).chapters) ===
      JSON.stringify(requested.chapters || []) &&
    (existing.client_requested_sound_id || null) === (requested.soundId || null)
}

module.exports = { validVideoPublishAttemptId, sameVideoPublishPayload }
