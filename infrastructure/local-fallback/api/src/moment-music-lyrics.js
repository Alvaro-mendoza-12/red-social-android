'use strict'

// LRC supplied with a community track; plain text never implies timing.
function timedMusicLyrics(raw, durationMs = Infinity) {
  if (typeof raw !== 'string' || !raw.trim() || raw.length > 12000) return []
  const offsetTag = /^\s*\[offset:([+-]?\d+)]\s*$/im.exec(raw)
  const offset = offsetTag ? Number(offsetTag[1]) : 0
  if (!Number.isSafeInteger(offset) || Math.abs(offset) > 60000) return []
  const limit = Number.isFinite(durationMs) && durationMs > 0 ? durationMs : Infinity
  const entries = new Map()
  let count = 0
  for (const line of raw.split(/\r?\n/)) {
    let rest = line.trim()
    const times = []
    let match
    while ((match = /^\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?]/.exec(rest))) {
      times.push(Math.max(0, (Number(match[1]) * 60 + Number(match[2])) * 1000 +
        Number((match[3] || '').padEnd(3, '0')) + offset))
      rest = rest.slice(match[0].length).trimStart()
    }
    const text = rest.replace(/[\x00-\x08\x0a-\x1f]/g, '').trim().slice(0, 280)
    for (const time of times) {
      if (time >= limit) continue
      if (++count > 1000) return []
      if (!entries.has(time)) entries.set(time, [])
      if (text && !entries.get(time).includes(text)) entries.get(time).push(text)
    }
  }
  const sorted = [...entries].sort((a, b) => a[0] - b[0])
  if (!sorted.some(([, lines]) => lines.length)) return []
  return sorted.map(([startMs, lines], i) => ({ startMs,
    endMs: sorted[i + 1]?.[0] ?? limit, text: lines.join('\n').slice(0, 280) }))
}

function approvedSyncedLyrics(raw) {
  return timedMusicLyrics(raw).length ? raw : null
}

// Rendered AAC starts at zero. Carry the active line at the trim point and
// preserve every real transition, including blank cues and instrumental gaps.
function clippedMusicLyrics(raw, startMs, durationMs) {
  const cues = timedMusicLyrics(raw)
  if (!cues.length || !Number.isFinite(startMs) || !Number.isFinite(durationMs) || durationMs <= 0) return null
  const start = Math.max(0, Math.round(startMs))
  const end = start + Math.round(durationMs)
  const selected = cues.filter(c => c.startMs < end && c.endMs > start)
  if (!selected.some(c => c.text)) return null
  const format = time => {
    const ms = Math.max(0, time - start)
    return `[${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}.${String(ms % 1000).padStart(3, '0')}]`
  }
  const result = selected.flatMap(c => (c.text.split('\n')).map(text => `${format(c.startMs)}${text}`)).join('\n')
  return result.length <= 12000 ? result : null
}

module.exports = { timedMusicLyrics, approvedSyncedLyrics, clippedMusicLyrics }
