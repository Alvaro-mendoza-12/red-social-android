function normalizeVideoChapters(value, durationSeconds) {
  if (value == null) return { chapters: [] }
  if (!Array.isArray(value) || value.length > 50) return { error: 'INVALID_VIDEO_CHAPTERS' }
  const durationMs = Math.round(Number(durationSeconds) * 1000)
  if (!Number.isSafeInteger(durationMs) || durationMs <= 0) return { error: 'INVALID_VIDEO_CHAPTERS' }
  const chapters = []
  let previousStart = -1
  for (const raw of value) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return { error: 'INVALID_VIDEO_CHAPTERS' }
    const title = typeof raw.title === 'string' ? raw.title.trim() : ''
    const startMs = raw.start_ms ?? raw.startMs
    if (!title || title.length > 80 || /[\u0000-\u001f\u007f]/u.test(title) ||
        !Number.isSafeInteger(startMs) || startMs <= previousStart || startMs < 0 || startMs >= durationMs) {
      return { error: 'INVALID_VIDEO_CHAPTERS' }
    }
    chapters.push({ title, start_ms: startMs })
    previousStart = startMs
  }
  return { chapters }
}

module.exports = { normalizeVideoChapters }
