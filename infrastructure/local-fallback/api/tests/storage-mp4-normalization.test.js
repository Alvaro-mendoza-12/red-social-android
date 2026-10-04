const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { test } = require('node:test')

process.env.STORAGE_SIGNING_SECRET = 'mp4-normalization-test-secret-'.repeat(3)
const {
  parseMp4AudioCodecs,
  probeMp4AudioCodecs,
  progressiveMp4RemuxArgs,
  optimizeMp4ForProgressiveDownload,
} = require('../src/storage-routes')

const probeResult = (codecs) => ({ stdout: JSON.stringify({ streams: codecs.map((codec_name) => ({ codec_name })) }) })
const deliveryMetadata = (overrides = {}) => ({
  streams: [{ codec_name: 'h264', width: 576, height: 1280, avg_frame_rate: '30/1', ...overrides.stream }],
  format: { duration: '3.913', ...overrides.format },
})

async function withUpload(callback) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-mp4-normalization-'))
  const input = path.join(directory, 'camera clip.mp4')
  const original = Buffer.from('original-owned-camera-upload')
  await fs.writeFile(input, original)
  try { await callback({ directory, input, original }) }
  finally { await fs.rm(directory, { recursive: true, force: true }) }
}

async function assertOriginalAndNoTemporaryFiles({ directory, input, original }) {
  assert.deepEqual(await fs.readFile(input), original)
  assert.deepEqual(await fs.readdir(directory), [path.basename(input)])
}

function fakeProcesses({ codecs = ['amr_nb'], output = Buffer.from('normalized-video-and-audio'), metadata = deliveryMetadata() } = {}) {
  const calls = []
  const runProcess = async (command, args, options) => {
    calls.push({ command, args, options })
    if (command === 'ffprobe' && args.includes('a')) return probeResult(codecs)
    if (command === 'ffprobe' && args.includes('v:0')) return { stdout: JSON.stringify(metadata) }
    assert.equal(command, 'ffmpeg')
    await fs.writeFile(args.at(-1), output)
    return { stdout: '' }
  }
  return { runProcess, calls, output }
}

test('audio metadata preserves stream order and normalizes names', () => {
  assert.deepEqual(parseMp4AudioCodecs({ streams: [{ codec_name: ' AAC ' }, { codec_name: 'amr_nb' }, { codec_name: 'pcm_s16le' }] }),
    ['aac', 'amr_nb', 'pcm_s16le'])
})

test('a confirmed silent video has no audio tracks to transcode', () => {
  assert.deepEqual(parseMp4AudioCodecs({ streams: [] }), [])
})

test('missing or malformed stream lists are not interpreted as silent video', () => {
  for (const metadata of [null, {}, { streams: null }, { streams: {} }]) {
    assert.throws(() => parseMp4AudioCodecs(metadata), { message: 'VIDEO_AUDIO_METADATA_INVALID', status: 415 })
  }
})

test('invalid audio codec metadata fails without dropping that track', () => {
  for (const codec_name of [undefined, null, '', ' ', 3, {}, 'aac;exit']) {
    assert.throws(() => parseMp4AudioCodecs({ streams: [{ codec_name }] }), { message: 'VIDEO_AUDIO_METADATA_INVALID', status: 415 })
  }
})

test('silent remux retains video stream-copy and faststart with optional audio mapping', () => {
  assert.deepEqual(progressiveMp4RemuxArgs('/owned/input.mp4', '/owned/output.mp4'), [
    '-hide_banner', '-loglevel', 'error', '-y', '-i', '/owned/input.mp4',
    '-map', '0:v:0', '-map', '0:a?', '-c', 'copy', '-movflags', '+faststart', '/owned/output.mp4',
  ])
})

test('AAC audio uses the same stream-copy arguments and is never re-encoded', () => {
  assert.deepEqual(progressiveMp4RemuxArgs('in', 'out', [' AAC ', 'aac']), progressiveMp4RemuxArgs('in', 'out'))
})

test('AMR is encoded as AAC LC without a video encoder, audio removal or duration truncation', () => {
  const args = progressiveMp4RemuxArgs('in', 'out', ['amr_nb'])
  assert.deepEqual(args, [
    '-hide_banner', '-loglevel', 'error', '-y', '-i', 'in', '-map', '0:v:0', '-map', '0:a?', '-c', 'copy',
    '-c:a:0', 'aac', '-profile:a:0', 'aac_low', '-b:a:0', '128k', '-movflags', '+faststart', 'out',
  ])
  for (const forbidden of ['-an', '-c:v', '-t', '-shortest']) assert.equal(args.includes(forbidden), false)
})

test('mixed audio tracks retain AAC and target each non-AAC ordinal independently', () => {
  const args = progressiveMp4RemuxArgs('in', 'out', ['aac', 'amr_nb', 'aac', 'pcm_s16le'])
  assert.equal(args.includes('-c:a:0'), false)
  assert.equal(args.includes('-c:a:2'), false)
  assert.deepEqual(args.slice(args.indexOf('-c:a:1'), -3), [
    '-c:a:1', 'aac', '-profile:a:1', 'aac_low', '-b:a:1', '128k',
    '-c:a:3', 'aac', '-profile:a:3', 'aac_low', '-b:a:3', '128k',
  ])
  assert.deepEqual(args.slice(-3), ['-movflags', '+faststart', 'out'])
})

test('invalid codec lists cannot bypass audio metadata validation', () => {
  for (const codecs of [null, 'aac', [undefined]]) {
    assert.throws(() => progressiveMp4RemuxArgs('in', 'out', codecs), /VIDEO_AUDIO_METADATA_INVALID/)
  }
})

test('ffprobe inspects all audio tracks through argv with bounded runtime and output', async () => {
  const source = '/owned/a clip with spaces.mp4'
  const result = await probeMp4AudioCodecs(source, async (command, args, options) => {
    assert.equal(command, 'ffprobe')
    assert.deepEqual(args, ['-v', 'error', '-select_streams', 'a', '-show_entries', 'stream=codec_name', '-of', 'json', source])
    assert.deepEqual(options, { timeout: 20_000, maxBuffer: 1024 * 1024 })
    return probeResult(['aac', 'amr_nb'])
  })
  assert.deepEqual(result, ['aac', 'amr_nb'])
})

for (const [name, runProcess] of [
  ['process failure', async () => { throw new Error('FFPROBE_TIMEOUT') }],
  ['invalid JSON', async () => ({ stdout: '{broken' })],
  ['missing stream list', async () => ({ stdout: '{}' })],
  ['unknown codec metadata', async () => ({ stdout: '{"streams":[{}]}' })],
]) test(`audio probe rejects ${name} with a decoding error`, async () => {
  await assert.rejects(probeMp4AudioCodecs('owned', runProcess), (error) => {
    assert.equal(error.message, 'VIDEO_DECODE_FAILED')
    assert.equal(error.status, 415)
    assert.ok(error.cause)
    return true
  })
})

for (const codecs of [['amr_nb'], ['aac'], []]) test(`successful chat normalization retains ${JSON.stringify(codecs)} audio policy and replaces only the validated file`, async () => {
  await withUpload(async ({ directory, input }) => {
    const fake = fakeProcesses({ codecs })
    const size = await optimizeMp4ForProgressiveDownload(input, { enforceFeedLimits: false, maxBytes: 1024, runProcess: fake.runProcess })
    assert.equal(size, fake.output.length)
    assert.deepEqual(await fs.readFile(input), fake.output)
    assert.deepEqual(await fs.readdir(directory), [path.basename(input)])
    assert.deepEqual(fake.calls.map((call) => call.command), ['ffprobe', 'ffmpeg'])
    assert.deepEqual(fake.calls[1].options, { timeout: 120_000, maxBuffer: 2 * 1024 * 1024 })
    assert.equal(fake.calls[1].args.includes('-c:a:0'), codecs[0] === 'amr_nb')
  })
})

test('probe failure preserves the original and never invokes ffmpeg or a silent fallback', async () => {
  await withUpload(async (upload) => {
    let calls = 0
    await assert.rejects(optimizeMp4ForProgressiveDownload(upload.input, { runProcess: async () => {
      calls++
      throw new Error('PROBE_FAILED')
    } }), { message: 'VIDEO_PROGRESSIVE_OPTIMIZATION_FAILED', status: 422 })
    assert.equal(calls, 1)
    await assertOriginalAndNoTemporaryFiles(upload)
  })
})

test('ffmpeg failure cleans a partial output and preserves the original without retrying silently', async () => {
  await withUpload(async (upload) => {
    let calls = 0
    await assert.rejects(optimizeMp4ForProgressiveDownload(upload.input, { runProcess: async (command, args) => {
      calls++
      if (command === 'ffprobe') return probeResult(['amr_nb'])
      await fs.writeFile(args.at(-1), 'partial')
      throw new Error('FFMPEG_TIMEOUT')
    } }), { message: 'VIDEO_PROGRESSIVE_OPTIMIZATION_FAILED', status: 422 })
    assert.equal(calls, 2)
    await assertOriginalAndNoTemporaryFiles(upload)
  })
})

for (const [name, output, maxBytes] of [
  ['oversized', Buffer.alloc(65), 64], ['empty', Buffer.alloc(0), 64],
]) test(`${name} normalized output keeps the upload size guard and cleanup`, async () => {
  await withUpload(async (upload) => {
    const fake = fakeProcesses({ output })
    await assert.rejects(optimizeMp4ForProgressiveDownload(upload.input, { maxBytes, runProcess: fake.runProcess }),
      { message: 'UPLOAD_TOO_LARGE', status: 413 })
    await assertOriginalAndNoTemporaryFiles(upload)
  })
})

test('missing ffmpeg output fails and leaves the original intact', async () => {
  await withUpload(async (upload) => {
    await assert.rejects(optimizeMp4ForProgressiveDownload(upload.input, { runProcess: async (command) =>
      command === 'ffprobe' ? probeResult(['amr_nb']) : { stdout: '' } }),
    { message: 'VIDEO_PROGRESSIVE_OPTIMIZATION_FAILED', status: 422 })
    await assertOriginalAndNoTemporaryFiles(upload)
  })
})

test('feed normalization validates the final file with the existing delivery policy', async () => {
  await withUpload(async ({ input }) => {
    const fake = fakeProcesses()
    assert.equal(await optimizeMp4ForProgressiveDownload(input, { runProcess: fake.runProcess }), fake.output.length)
    assert.deepEqual(fake.calls.map((call) => call.command), ['ffprobe', 'ffmpeg', 'ffprobe'])
    assert.ok(fake.calls[2].args.includes('v:0'))
  })
})

for (const [name, overrides, reason] of [
  ['duration', { format: { duration: '46' } }, 'VIDEO_DURATION_TOO_LONG'],
  ['minimum duration', { format: { duration: '2' } }, 'VIDEO_DURATION_TOO_SHORT'],
  ['resolution', { stream: { width: 3840, height: 2160 } }, 'VIDEO_RESOLUTION_REQUIRES_TRANSCODING'],
  ['frame rate', { stream: { avg_frame_rate: '60/1' } }, 'VIDEO_FRAME_RATE_REQUIRES_TRANSCODING'],
  ['video codec', { stream: { codec_name: 'hevc' } }, 'VIDEO_CODEC_REQUIRES_TRANSCODING'],
]) test(`normalization cannot bypass the feed ${name} limit`, async () => {
  await withUpload(async (upload) => {
    const fake = fakeProcesses({ metadata: deliveryMetadata(overrides) })
    await assert.rejects(optimizeMp4ForProgressiveDownload(upload.input, { runProcess: fake.runProcess }), (error) => {
      assert.equal(error.message, 'VIDEO_PROGRESSIVE_OPTIMIZATION_FAILED')
      assert.equal(error.status, 422)
      assert.equal(error.cause.message, reason)
      return true
    })
    await assertOriginalAndNoTemporaryFiles(upload)
  })
})

test('audio normalization retains the final feed bitrate limit', async () => {
  await withUpload(async (upload) => {
    const fake = fakeProcesses({ output: Buffer.alloc(3_000_000) })
    await assert.rejects(optimizeMp4ForProgressiveDownload(upload.input, { runProcess: fake.runProcess }), (error) => {
      assert.equal(error.message, 'VIDEO_PROGRESSIVE_OPTIMIZATION_FAILED')
      assert.equal(error.status, 422)
      assert.equal(error.cause.message, 'VIDEO_BITRATE_REQUIRES_TRANSCODING')
      return true
    })
    await assertOriginalAndNoTemporaryFiles(upload)
  })
})

test('failed final replacement preserves the original and removes the completed temporary output', async () => {
  await withUpload(async (upload) => {
    const fake = fakeProcesses()
    const originalRename = fs.rename
    fs.rename = async () => { throw new Error('RENAME_FAILED') }
    try {
      await assert.rejects(optimizeMp4ForProgressiveDownload(upload.input, { enforceFeedLimits: false, runProcess: fake.runProcess }),
        { message: 'VIDEO_PROGRESSIVE_OPTIMIZATION_FAILED', status: 422 })
    } finally { fs.rename = originalRename }
    await assertOriginalAndNoTemporaryFiles(upload)
  })
})
