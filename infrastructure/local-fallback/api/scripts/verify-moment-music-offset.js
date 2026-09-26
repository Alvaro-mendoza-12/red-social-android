'use strict'

// Exercise the real renderer arguments with synthetic tones, no users or network.
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const exec = promisify(execFile)

async function main() {
  assert.equal(process.env.NODE_ENV, 'test')
  assert.ok(process.argv.includes('--isolated-fixtures'))
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-music-offset-'))
  const input = path.join(root, 'three-regions.wav')
  const digest = async () => crypto.createHash('sha256').update(await fs.readFile(input)).digest('hex')
  try {
    // storage-routes initializes a temporary upload directory when imported.
    // Confine that side effect to the fixture root too.
    process.env.STORAGE_ROOT = root
    const { momentMusicTranscodeArgs } = require('../src/moment-music-renderer')
    // First 2 minutes: 440Hz; middle: 880Hz; final 15 seconds: 1320Hz.
    await exec('ffmpeg', ['-nostdin', '-v', 'error', '-f', 'lavfi', '-i',
      'aevalsrc=0.2*sin(2*PI*if(lt(t\\,120)\\,440\\,if(lt(t\\,255)\\,880\\,1320))*t):s=16000:d=270',
      '-c:a', 'pcm_s16le', input], { timeout: 60000 })
    const beforeHash = await digest()
    for (const [startMs, durationMs, hz] of [[0, 15000, 440], [120000, 15000, 880], [240000, 6000, 880], [255000, 15000, 1320]]) {
      const output = path.join(root, `clip-${startMs}.m4a`)
      await exec('ffmpeg', momentMusicTranscodeArgs({ inputPath: input, outputPath: output, startMs, durationMs }), { timeout: 60000 })
      const probe = await exec('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'json', output])
      const actualDuration = Math.round(Number(JSON.parse(probe.stdout).format.duration) * 1000)
      assert.ok(Math.abs(actualDuration - durationMs) < 100, 'Clip has selected length')
      const { stdout: pcm } = await exec('ffmpeg', ['-nostdin', '-v', 'error', '-ss', '1', '-i', output,
        '-t', '1', '-ac', '1', '-ar', '16000', '-f', 's16le', '-'], { encoding: 'buffer', maxBuffer: 100000 })
      let crossings = 0
      for (let i = 2; i < pcm.length; i += 2) {
        if (pcm.readInt16LE(i - 2) <= 0 && pcm.readInt16LE(i) > 0) crossings++
      }
      assert.ok(Math.abs(crossings - hz) <= 5, `Expected region ${hz}Hz, got ${crossings}Hz`)
      console.log(`PASS start=${startMs} duration=${actualDuration}ms tone=${crossings}Hz`)
    }
    assert.equal(await digest(), beforeHash)
    console.log('PASS original unchanged; late clips contain selected region, not first 15 seconds')
  } finally {
    const actualRoot = await fs.realpath(root)
    assert.equal(path.dirname(actualRoot), await fs.realpath(os.tmpdir()))
    assert.ok(path.basename(actualRoot).startsWith('novvin-music-offset-'))
    await fs.rm(actualRoot, { recursive: true })
  }
}
main().catch((error) => { console.error(error); process.exitCode = 1 })
