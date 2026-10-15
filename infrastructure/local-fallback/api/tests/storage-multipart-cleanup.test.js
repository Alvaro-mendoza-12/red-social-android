'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { Readable } = require('node:stream');
const Module = require('node:module');

const sourceFile = path.join(__dirname, '../src/storage-routes.js');
const sourceBytes = fs.readFileSync(sourceFile);
const sourceText = sourceBytes.toString().replace(/\r\n/g, '\n');
const sourceHash = crypto.createHash('sha256').update(sourceBytes).digest('hex');
const actor = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';
const safeText = Buffer.from('Harmless synthetic regression fixture.');
const results = [];

function res() {
  return { statusCode: 200, ended: false, status(code) { this.statusCode = code; return this; },
    json(body) { this.body = body; this.ended = true; return this; } };
}
function loadRoutes(text, root, mode) {
  process.env.NODE_ENV = 'test'; process.env.STORAGE_ROOT = root;
  process.env.STORAGE_ACCEL_PREFIX = ''; process.env.MAX_UPLOAD_BYTES = '128';
  process.env.PUBLIC_BASE_URL = 'https://audit.invalid';
  process.env.STORAGE_SIGNING_SECRET = 'synthetic-only-regression-key-20261007';
  const loaded = new Module(sourceFile, module);
  loaded.filename = sourceFile; loaded.paths = Module._nodeModulePaths(path.dirname(sourceFile));
  loaded._compile(text, sourceFile); // In memory: relative dependencies stay in the read-only source.
  const objects = new Map();
  const pool = { async query(sql, values = []) {
    const normalized = sql.replace(/\s+/g, ' ').trim();
    if (normalized.startsWith('SELECT owner_id FROM fallback_storage_objects')) {
      if (mode === 'db-read-error') throw new Error('SYNTHETIC_DB_READ_FAILURE');
      return { rows: mode === 'foreign-owner' ? [{ owner_id: other }] : [] };
    }
    if (normalized.startsWith('INSERT INTO fallback_storage_objects')) {
      if (mode === 'db-write-error') throw new Error('SYNTHETIC_DB_WRITE_FAILURE');
      const row = { id: crypto.randomUUID(), bucket_id: values[0], name: values[1], owner_id: values[2],
        content_type: values[3], size_bytes: values[4], public_url: values[5], local_path: values[6],
        metadata: values[7], created_at: new Date().toISOString(), updated_at: new Date().toISOString() };
      objects.set(row.id, row); return { rows: [row] };
    }
    throw new Error('UNEXPECTED_SYNTHETIC_QUERY');
  } };
  const routes = [];
  const app = Object.fromEntries(['get', 'post', 'put', 'delete', 'head', 'patch', 'options'].map(method =>
    [method, (matcher, ...handlers) => routes.push({ method, matcher, handlers })]));
  loaded.exports.registerStorageRoutes(app, { pool, storageUploadAdmission: { async reserve({ id = crypto.randomUUID(), ownerId }) { return { id, assertAlive() {}, async finish() {} } }, async claimTus(id) { return { id, assertAlive() {}, async finish() {} } } }, authRequired(req, response, next) { req.auth = { sub: actor }; next(); } });
  return { handlers: routes.find(route => route.method === 'post' && route.matcher === '/v1/storage/upload').handlers, objects };
}
async function execute({ bucket = 'chat-media', filename = 'safe.txt', type = 'text/plain', file = safeText,
  mode = 'normal', includeFile = true }) {
  const fixture = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'novvin-cleanup-regression-'));
  const root = path.join(fixture, 'storage');
  fs.mkdirSync(path.join(root, '.tmp'), { recursive: true });
  try {
    const { handlers, objects } = loadRoutes(sourceText, root, mode);
    const boundary = 'review-only-cleanup-boundary';
    const chunks = [Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="bucket"\r\n\r\n${bucket}\r\n`)];
    if (includeFile) chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${filename}"\r\nContent-Type: ${type}\r\n\r\n`), file, Buffer.from('\r\n'));
    chunks.push(Buffer.from(`--${boundary}--\r\n`));
    const body = Buffer.concat(chunks);
    const request = Readable.from([body]);
    Object.assign(request, { headers: { 'content-type': `multipart/form-data; boundary=${boundary}`, 'content-length': String(body.length) },
      method: 'POST', url: '/v1/storage/upload', protocol: 'https', get: () => 'audit.invalid' });
    const response = res();
    // Await Multer separately so the terminal handler's finally has completed before assertions.
    for (const handler of handlers.slice(0, -1)) await new Promise((resolve, reject) => handler(request, response, error => error ? reject(error) : resolve()));
    let handlerError;
    await handlers.at(-1)(request, response, error => { handlerError = error; });
    const files = fs.readdirSync(path.join(root, '.tmp'));
    const saved = [...objects.values()].map(row => ({ size: fs.statSync(row.local_path).size,
      contentEqualsInput: fs.readFileSync(row.local_path).equals(file) }));
    return { status: handlerError ? (handlerError.status || 500) : response.statusCode, code: response.body?.error || handlerError?.message || null,
      tempFiles: files.length, saved, success: !handlerError && response.statusCode === 201 };
  } finally {
    const resolved = path.resolve(fixture);
    assert.ok(resolved.startsWith(path.resolve(require('node:os').tmpdir()) + path.sep + 'novvin-cleanup-regression-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  }
}

test('multipart cleanup uses finally with real Multer and persistence, no sockets', async t => {
  const cases = [
    ['invalid-bucket', { bucket: '' }, 400, 'INVALID_BUCKET'],
    ['unknown-bucket', { bucket: 'unknown-bucket' }, 400, 'BUCKET_NOT_ALLOWED'],
    ['missing-file', { includeFile: false }, 400, 'FILE_REQUIRED'],
    ['type-mismatch', { filename: 'pretend.pdf', type: 'application/pdf' }, 415, 'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE'],
    ['foreign-owner', { mode: 'foreign-owner' }, 403, 'STORAGE_OBJECT_OWNED_BY_ANOTHER_USER'],
    ['db-read-error', { mode: 'db-read-error' }, 500, 'SYNTHETIC_DB_READ_FAILURE'],
    ['db-write-error', { mode: 'db-write-error' }, 500, 'SYNTHETIC_DB_WRITE_FAILURE'],
    ['successful-moved-file', {}, 201, null],
  ];
  for (const [name, options, status, code] of cases) await t.test(name, async () => {
    const actual = await execute(options);
    assert.equal(actual.tempFiles, 0); assert.equal(actual.status, status); assert.equal(actual.code, code);
    if (status === 201) { assert.equal(actual.saved.length, 1); assert.equal(actual.saved[0].contentEqualsInput, true); }
    else assert.equal(actual.saved.length, 0);
    results.push({ case: name, ...actual, expectedCleanupObserved: true });
  });
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(sourceFile)).digest('hex'), sourceHash);

});
