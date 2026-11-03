'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const { boundedWorkQueue } = require('../src/bounded-work-queue');
const { normalizeImageVariant, WIDTHS, QUALITIES } = require('../src/image-variant-policy');
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
const immediate = () => new Promise(resolve => setImmediate(resolve));

test('every previously valid width/quality maps to exactly 36 profiles', () => {
  const profiles = new Set();
  for (let width = 64; width <= 1920; width++) for (let quality = 40; quality <= 90; quality++) {
    const variant = normalizeImageVariant({ width, quality }, 'image/jpeg');
    assert.ok(WIDTHS.includes(variant.width)); assert.ok(QUALITIES.includes(variant.quality)); assert.ok(variant.width >= width);
    profiles.add(`${variant.width}:${variant.quality}`);
  }
  assert.equal(profiles.size, 36);
});
test('rejects invalid profiles and preserves nonimage/original semantics', () => {
  assert.equal(normalizeImageVariant({}, 'image/png'), null);
  assert.equal(normalizeImageVariant({ width: 720 }, 'video/mp4'), null);
  for (const query of [{ width: 63 }, { width: 1921 }, { width: 'bad' }, { width: 720, quality: 91 }, { width: 720, quality: 59.5 }, { width: 720, format: 'jpeg' }])
    assert.throws(() => normalizeImageVariant(query, 'image/png'), { message: 'INVALID_IMAGE_VARIANT', status: 400 });
});

test('video posters require explicit admission and a bounded image profile', () => {
  const query = { width: 160, quality: 75, poster: '1' };
  assert.equal(normalizeImageVariant(query, 'video/mp4'), null);
  assert.equal(normalizeImageVariant({ width: 160 }, 'video/mp4', true), null);
  assert.deepEqual(normalizeImageVariant(query, 'video/mp4', true), { width: 160, quality: 78, format: 'webp', poster: true });
  assert.throws(() => normalizeImageVariant({ ...query, width: 9000 }, 'video/mp4', true), { message: 'INVALID_IMAGE_VARIANT' });
});
test('queue limits concurrency and queued entries; excess is rejected before work', async () => {
  const queue = boundedWorkQueue({ concurrency: 2, maxQueued: 2, queueWaitMs: 1000 });
  const gate = deferred(); let executed = 0;
  const tasks = Array.from({ length: 4 }, () => queue.run(async () => { executed++; await gate.promise; }));
  await immediate(); assert.equal(executed, 2); assert.equal(queue.snapshot().active, 2); assert.equal(queue.snapshot().queued, 2);
  await assert.rejects(queue.run(() => { executed++; }), { message: 'WORK_QUEUE_FULL', status: 503 });
  gate.resolve(); await Promise.all(tasks); await immediate(); assert.equal(executed, 4);
  assert.equal(queue.snapshot().active, 0); assert.equal(queue.snapshot().queued, 0);
});
test('queue expiry never starts expired work nor releases the still-active job', async () => {
  const queue = boundedWorkQueue({ concurrency: 1, maxQueued: 1, queueWaitMs: 15 });
  const gate = deferred(); const active = queue.run(() => gate.promise); let started = false;
  await assert.rejects(queue.run(() => { started = true; }), { message: 'WORK_QUEUE_WAIT_EXPIRED' });
  assert.equal(started, false); assert.equal(queue.snapshot().active, 1); assert.equal(queue.snapshot().queued, 0);
  gate.resolve(); await active; await immediate(); assert.equal(queue.snapshot().active, 0);
});
test('task failures free real capacity for the next queued task', async () => {
  const queue = boundedWorkQueue({ concurrency: 1, maxQueued: 1, queueWaitMs: 1000 });
  const failure = queue.run(() => { throw new Error('SYNTHETIC_WORK_FAILURE'); });
  const next = queue.run(() => 42); await assert.rejects(failure, { message: 'SYNTHETIC_WORK_FAILURE' });
  assert.equal(await next, 42); await immediate(); assert.equal(queue.snapshot().active, 0);
});
test('invalid queue settings and task are controlled', async () => {
  assert.throws(() => boundedWorkQueue({ concurrency: 0 }));
  assert.throws(() => boundedWorkQueue({ maxQueued: -1 }));
  await assert.rejects(boundedWorkQueue().run(null), { message: 'WORK_TASK_REQUIRED' });
});
test('proposed storage integration deduplicates and creates a harmless actual WebP', async () => {
  const fixture = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'novvin-variant-regression-'));
  const sourceFile = path.join(__dirname, '../src/storage-routes.js');
  process.env.NODE_ENV = 'test'; process.env.STORAGE_ROOT = fixture; process.env.STORAGE_ACCEL_PREFIX = '';
  process.env.STORAGE_SIGNING_SECRET = 'synthetic-only-derivative-test-key';
  const loaded = new Module(sourceFile, module); loaded.filename = sourceFile; loaded.paths = Module._nodeModulePaths(path.dirname(sourceFile));
  const originalRequire = loaded.require.bind(loaded);
  loaded._compile(fs.readFileSync(sourceFile, 'utf8'), sourceFile);
  try {
    assert.equal(loaded.exports.requestedImageVariant({ width: 160, poster: '1' }, 'video/mp4', 'chat-media'), null);
    assert.equal(loaded.exports.requestedImageVariant({ width: 160, poster: '1' }, 'video/mp4', 'avatars').poster, true);
    const sharp = originalRequire('sharp'); const input = path.join(fixture, 'input.png');
    await sharp({ create: { width: 80, height: 40, channels: 3, background: '#4080C0' } }).png().toFile(input);
    const stat = fs.statSync(input); const variant = loaded.exports.requestedImageVariant({ width: 65, quality: 76 }, 'image/png');
    assert.deepEqual(variant, { width: 160, quality: 78, format: 'webp' });
    const responses = await Promise.all(Array.from({ length: 10 }, () => loaded.exports.resolveImageVariantResponse({ bucket: 'avatars', objectPath: 'synthetic.png', filePath: input, stat, variant })));
    assert.equal(new Set(responses.map(response => response.responsePath)).size, 1);
    assert.equal(responses[0].variantError, null);
    const output = responses[0].responsePath; assert.equal(fs.readFileSync(output).subarray(8, 12).toString(), 'WEBP');
    const files = fs.readdirSync(path.dirname(output)); assert.equal(files.length, 1); assert.equal(files[0].endsWith('.webp'), true);
  } finally {
    assert.ok(path.resolve(fixture).startsWith(path.resolve(require('node:os').tmpdir()) + path.sep + 'novvin-variant-regression-'));
    fs.rmSync(fixture, { recursive: true, force: true });
  }
});
