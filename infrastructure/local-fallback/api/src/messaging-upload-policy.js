const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const runProcess = promisify(execFile)
const MAX_MESSAGE_UPLOAD_BYTES = 500 * 1024 * 1024
const MAX_MESSAGE_VIDEO_SECONDS = 600
function fail(code, status) { const error = new Error(code); error.status = status; throw error }
function assertMessageVideoDuration(duration) {
  if (!Number.isFinite(duration) || duration <= 0) fail('VIDEO_DECODE_FAILED', 415)
  if (duration > MAX_MESSAGE_VIDEO_SECONDS + 0.25) fail('MESSAGE_VIDEO_DURATION_TOO_LONG', 413)
}
async function validateMessagingUpload({ contentType, sizeBytes, sourcePath }, run = runProcess) {
  if (sizeBytes > MAX_MESSAGE_UPLOAD_BYTES || (/^(image|audio)\//.test(contentType) && sizeBytes > 60 * 1024 * 1024)) fail('UPLOAD_TOO_LARGE', 413)
  if (!contentType.startsWith('video/') || !sourcePath) return
  let metadata
  try {
    const result = await run('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'json', sourcePath], { timeout: 20000, maxBuffer: 1024 * 1024 })
    metadata = JSON.parse(result.stdout)
  } catch { fail('VIDEO_DECODE_FAILED', 415) }
  assertMessageVideoDuration(Number(metadata?.format?.duration))
}
module.exports = { MAX_MESSAGE_UPLOAD_BYTES, MAX_MESSAGE_VIDEO_SECONDS, assertMessageVideoDuration, validateMessagingUpload }
