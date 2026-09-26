const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { execFile } = require('child_process')
const { promisify } = require('util')
const { Pool } = require('pg')
const { probeVideoDelivery } = require('../src/storage-routes')

const execFileAsync = promisify(execFile)
const storageRoot = path.resolve(process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'))
const apply = process.argv.includes('--apply') || String(process.env.VIDEO_NORMALIZE_APPLY || '').toLowerCase() === 'true'
const databaseUrl = String(process.env.DATABASE_URL || '').trim()

function storageNameFromUrl(rawUrl) {
  try {
    const pathname = new URL(String(rawUrl || '')).pathname
    const marker = '/storage/v1/object/public/videos/'
    const markerIndex = pathname.toLowerCase().indexOf(marker)
    if (markerIndex < 0) return ''
    return decodeURIComponent(pathname.slice(markerIndex + marker.length)).replace(/^\/+/, '')
  } catch {
    return ''
  }
}

function assertStoragePath(rawPath) {
  const resolved = path.resolve(String(rawPath || ''))
  const videosRoot = path.resolve(storageRoot, 'videos')
  if (resolved !== videosRoot && !resolved.startsWith(`${videosRoot}${path.sep}`)) {
    throw new Error('VIDEO_PATH_OUTSIDE_STORAGE_ROOT')
  }
  return resolved
}

async function transcodeForDelivery(inputPath) {
  const outputPath = `${inputPath}.normalize-${crypto.randomUUID()}.mp4`
  const scale = "scale=w='if(gte(iw,ih),min(1920,iw),min(1080,iw))':h='if(gte(iw,ih),min(1080,ih),min(1920,ih))':force_original_aspect_ratio=decrease:force_divisible_by=2"
  try {
    await execFileAsync(
      'ffmpeg',
      [
        '-hide_banner', '-loglevel', 'error', '-y',
        '-i', inputPath,
        '-map', '0:v:0', '-map', '0:a?',
        '-vf', `${scale},fps=30`,
        '-c:v', 'libx264', '-preset', 'veryfast', '-profile:v', 'high', '-level:v', '4.1',
        '-pix_fmt', 'yuv420p', '-b:v', '3200k', '-maxrate', '4200k', '-bufsize', '8400k',
        '-g', '60', '-keyint_min', '30', '-sc_threshold', '0',
        '-c:a', 'aac', '-b:a', '128k', '-ar', '48000', '-ac', '2',
        '-movflags', '+faststart',
        outputPath,
      ],
      { timeout: 15 * 60 * 1000, maxBuffer: 4 * 1024 * 1024 },
    )
    const stat = await fs.promises.stat(outputPath)
    await probeVideoDelivery(outputPath, stat.size)
    return { outputPath, sizeBytes: stat.size }
  } catch (error) {
    await fs.promises.rm(outputPath, { force: true }).catch(() => {})
    throw error
  }
}

async function replaceAtomically(inputPath, outputPath) {
  const backupPath = `${inputPath}.normalize-backup`
  await fs.promises.rename(inputPath, backupPath)
  try {
    await fs.promises.rename(outputPath, inputPath)
    await fs.promises.rm(backupPath, { force: true })
  } catch (error) {
    await fs.promises.rm(inputPath, { force: true }).catch(() => {})
    await fs.promises.rename(backupPath, inputPath).catch(() => {})
    throw error
  }
}

async function main() {
  if (!databaseUrl) throw new Error('DATABASE_URL is required')
  const pool = new Pool({ connectionString: databaseUrl })
  const summary = { mode: apply ? 'apply' : 'dry-run', checked: 0, compliant: 0, requiresNormalization: 0, normalized: 0, missing: 0, failed: 0 }
  try {
    const { rows: videos } = await pool.query(
      `SELECT id::text, title, video_url FROM fallback_videos WHERE COALESCE(video_url, '') <> '' ORDER BY created_at ASC`,
    )
    for (const video of videos) {
      summary.checked += 1
      const objectName = storageNameFromUrl(video.video_url)
      const { rows } = objectName
        ? await pool.query(
          `SELECT name, local_path, size_bytes FROM fallback_storage_objects WHERE bucket_id='videos' AND name=$1 LIMIT 1`,
          [objectName],
        )
        : { rows: [] }
      const row = rows[0]
      if (!row) {
        summary.missing += 1
        console.warn(JSON.stringify({ status: 'missing-storage-object', videoId: video.id, title: video.title }))
        continue
      }
      const inputPath = assertStoragePath(row.local_path || path.join(storageRoot, 'videos', row.name))
      const stat = await fs.promises.stat(inputPath).catch(() => null)
      if (!stat?.isFile()) {
        summary.missing += 1
        console.warn(JSON.stringify({ status: 'missing-file', videoId: video.id, path: inputPath }))
        continue
      }
      try {
        const metadata = await probeVideoDelivery(inputPath, stat.size)
        summary.compliant += 1
        console.log(JSON.stringify({ status: 'compliant', videoId: video.id, metadata }))
      } catch (policyError) {
        summary.requiresNormalization += 1
        console.log(JSON.stringify({ status: apply ? 'normalizing' : 'would-normalize', videoId: video.id, reason: policyError.message, sizeBytes: stat.size }))
        if (!apply) continue
        try {
          const normalized = await transcodeForDelivery(inputPath)
          await replaceAtomically(inputPath, normalized.outputPath)
          await pool.query(
            `UPDATE fallback_storage_objects SET size_bytes=$3, updated_at=NOW() WHERE bucket_id='videos' AND name=$1 AND local_path=$2`,
            [row.name, inputPath, normalized.sizeBytes],
          )
          summary.normalized += 1
        } catch (error) {
          summary.failed += 1
          console.error(JSON.stringify({ status: 'failed', videoId: video.id, error: error.message }))
        }
      }
    }
    console.log(JSON.stringify({ summary }))
    if (summary.failed > 0) process.exitCode = 1
  } finally {
    await pool.end()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
