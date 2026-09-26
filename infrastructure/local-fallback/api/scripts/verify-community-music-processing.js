'use strict'

// Real ffmpeg/ffprobe fixtures only: no database connection, network or user media.
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const { inspectAndCreateMusicPreview } = require('../src/music-routes')
const exec = promisify(execFile)

async function main() {
  assert.equal(process.env.NODE_ENV, 'test', 'Run only in an isolated test container')
  assert.ok(process.argv.includes('--isolated-fixtures'), 'Explicit fixture opt-in required')
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-music-fixtures-'))
  const originalEnv = { ...process.env }
  process.env.STORAGE_ROOT = root
  process.env.PUBLIC_BASE_URL = 'https://music-fixture.invalid'
  process.env.MUSIC_ANTIVIRUS_REQUIRED = 'false' // Generated local tones, never production uploads.
  delete process.env.MUSIC_ANTIVIRUS_COMMAND
  const owner = '00000000-0000-4000-8000-000000000001'
  const inserted = []
  const pool = { query: async (sql, args) => {
    if (sql.includes('SELECT 1 FROM fallback_music_retired_hashes')) return { rows: [], rowCount: 0 }
    if (sql.includes('INSERT INTO fallback_storage_objects')) { inserted.push(args); return { rows: [], rowCount: 1 } }
    throw new Error('Unexpected SQL in isolated fixture')
  } }
  const digest = async (file) => crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex')
  const duration = async (file) => {
    const result = await exec('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'json', file])
    return Math.round(Number(JSON.parse(result.stdout).format.duration) * 1000)
  }
  try {
    for (const seconds of [240, 270, 300]) {
      const name = `${owner}/fixture-${seconds}.m4a`
      const local = path.join(root, 'community-music', name)
      await fs.mkdir(path.dirname(local), { recursive: true })
      await exec('ffmpeg', ['-nostdin', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100',
        '-t', String(seconds), '-c:a', 'aac', '-b:a', '64k', '-threads', '1', local], { timeout: 60000 })
      const originalHash = await digest(local)
      const storage = { name, local_path: local }
      const beforeInserts = inserted.length
      if (seconds > 270) {
        // Actual stream duration wins even when an older/malicious client declares four minutes.
        for (const declared of [300000, 240000]) {
          await assert.rejects(inspectAndCreateMusicPreview(pool, {}, owner, storage, declared), {
            message: 'MUSIC_TRIM_TO_4_30_REQUIRED', status: 400,
          })
        }
        assert.equal(inserted.length, beforeInserts)
      } else {
        const result = await inspectAndCreateMusicPreview(pool, {}, owner, storage, seconds * 1000)
        assert.equal(result.durationMs, seconds * 1000)
        assert.equal(result.sourceSha256, originalHash)
        assert.equal(inserted.length, beforeInserts + 1)
        const preview = inserted.at(-1)
        assert.notEqual(preview[4], local)
        assert.equal(result.previewUrl, preview[3])
        assert.ok(Math.abs(await duration(preview[4]) - 30000) < 100, 'Preview is separate and ~30 seconds')
      }
      assert.equal(await digest(local), originalHash, 'Processing must never crop or replace the original')
      assert.equal(await duration(local), seconds * 1000)
      console.log(`PASS ${seconds}s original preserved; ${seconds <= 270 ? 'full duration accepted, separate preview' : 'oversized upload rejected, including false metadata'}`)
    }
    const corrupt = path.join(root, 'community-music', owner, 'invalid.mp3')
    await fs.writeFile(corrupt, 'invalid fixture audio')
    await assert.rejects(inspectAndCreateMusicPreview(pool, {}, owner,
      { name: `${owner}/invalid.mp3`, local_path: corrupt }, 240000), { message: 'MUSIC_AUDIO_DECODE_FAILED', status: 400 })
    console.log('PASS invalid audio rejected; fixture DB writes only separate previews')
  } finally {
    for (const key of ['STORAGE_ROOT', 'PUBLIC_BASE_URL', 'MUSIC_ANTIVIRUS_REQUIRED', 'MUSIC_ANTIVIRUS_COMMAND']) {
      if (originalEnv[key] === undefined) delete process.env[key]
      else process.env[key] = originalEnv[key]
    }
    const temporaryRoot = await fs.realpath(os.tmpdir())
    const actualRoot = await fs.realpath(root)
    assert.equal(path.dirname(actualRoot), temporaryRoot)
    assert.ok(path.basename(actualRoot).startsWith('novvin-music-fixtures-'))
    await fs.rm(actualRoot, { recursive: true })
  }
}
main().catch((error) => { console.error(error); process.exitCode = 1 })
