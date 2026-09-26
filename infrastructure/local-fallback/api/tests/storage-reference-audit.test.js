const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('path')
const {
  auditStorageReferences,
  buildStorageAliasIndex,
  classifyStorageObject,
  collectDatabaseReferences,
  extractStorageObjectKeys,
  isPathWithin,
  normalizeObjectName,
  quoteIdentifier,
  referenceCellQuery,
  resolveStoragePath,
  storageKeyFromCandidate,
  storageObjectKey,
  summarizeFindings,
} = require('../src/storage-reference-audit')

const objects = [
  {
    id: '11111111-1111-4111-8111-111111111111',
    bucket_id: 'videos',
    name: 'owner/video 1.mp4',
    public_url: 'https://novvin.example/storage/v1/object/public/videos/owner/video%201.mp4?v=2',
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    bucket_id: 'community-music',
    name: 'owner/track.m4a',
    public_url: 'https://novvin.example/storage/v1/object/public/community-music/owner/track.m4a',
  },
]

assert.equal(storageObjectKey('VIDEOS', 'owner/video%201.mp4'), 'videos/owner/video 1.mp4')
assert.equal(storageObjectKey('../videos', 'owner/video.mp4'), '')
assert.equal(normalizeObjectName('owner/../secret.mp4'), '')
assert.equal(normalizeObjectName('owner/%2F..%2Favatars%2Fsecret.png'), '')
assert.equal(
  storageKeyFromCandidate('https://old.example/storage/v1/object/public/videos/owner/video%201.mp4?token=x'),
  'videos/owner/video 1.mp4',
)
assert.equal(
  storageKeyFromCandidate('/storage/v1/object/sign/community-music/owner/track.m4a?token=secret'),
  'community-music/owner/track.m4a',
)
assert.equal(storageKeyFromCandidate('https://cdn.example/video.mp4'), '')

const aliases = buildStorageAliasIndex(objects)
assert.deepEqual(
  Array.from(extractStorageObjectKeys({
    media: objects[0].public_url,
    nested: [`Texto ${objects[1].public_url}`],
  }, aliases)).sort(),
  ['community-music/owner/track.m4a', 'videos/owner/video 1.mp4'],
)
assert.deepEqual(
  Array.from(extractStorageObjectKeys('owner/video 1.mp4', aliases)),
  ['videos/owner/video 1.mp4'],
)
assert.deepEqual(
  Array.from(extractStorageObjectKeys('11111111-1111-4111-8111-111111111111', aliases)),
  ['videos/owner/video 1.mp4'],
)

const root = path.resolve('D:/storage-audit-root')
assert.equal(isPathWithin(root, path.join(root, 'videos', 'a.mp4')), true)
assert.equal(isPathWithin(root, path.resolve(root, '..', 'escape.mp4')), false)
assert.equal(quoteIdentifier('table"name'), '"table""name"')
assert.match(
  referenceCellQuery({ table_schema: 'public', table_name: 'fallback_videos', column_name: 'video_url' }),
  /btrim\("video_url"::text\) <> ''/,
)
assert.match(
  referenceCellQuery({ table_schema: 'public', table_name: 'fallback_rows', column_name: 'payload' }),
  /position\('\/storage\/' in lower\("payload"::text\)\) > 0/,
)

const safePath = resolveStoragePath(root, {
  bucket_id: 'videos',
  name: 'owner/a.mp4',
  local_path: path.join(root, 'videos', 'owner', 'a.mp4'),
})
assert.equal(safePath.safe, true)
assert.equal(safePath.mismatch, false)
assert.equal(resolveStoragePath(root, {
  bucket_id: 'videos',
  name: 'owner/a.mp4',
  local_path: path.resolve(root, '..', 'outside.mp4'),
}).safe, false)

const nowMs = Date.parse('2026-08-24T12:00:00Z')
const baseObject = {
  bucket_id: 'videos',
  name: 'owner/a.mp4',
  size_bytes: 100,
  created_at: '2026-08-01T00:00:00Z',
  updated_at: '2026-08-01T00:00:00Z',
}
const classificationInput = {
  object: baseObject,
  pathResolution: { safe: true, mismatch: false },
  nowMs,
  graceMs: 7 * 24 * 3_600_000,
}
assert.equal(classifyStorageObject({
  ...classificationInput,
  references: [{ table: 'fallback_videos' }],
  file: { exists: false },
}).status, 'referenced-file-missing')
assert.equal(classifyStorageObject({
  ...classificationInput,
  references: [],
  file: { exists: true, size: 100 },
}).status, 'orphan-candidate')
assert.equal(classifyStorageObject({
  ...classificationInput,
  object: { ...baseObject, updated_at: '2026-08-24T11:00:00Z' },
  references: [],
  file: { exists: true, size: 100 },
}).status, 'protected-recent-unreferenced')
assert.equal(classifyStorageObject({
  ...classificationInput,
  references: [],
  file: { exists: true, size: 100 },
  pathResolution: { safe: true, mismatch: true },
}).status, 'protected-path-anomaly')

const summary = summarizeFindings(
  [
    { status: 'referenced', severity: 'ok', sizeBytes: 100, actualSizeBytes: 100 },
    { status: 'orphan-candidate', severity: 'warning', sizeBytes: 50, actualSizeBytes: 50 },
    { status: 'referenced-file-missing', severity: 'critical', sizeBytes: 25, actualSizeBytes: null },
  ],
  [{ status: 'filesystem-orphan-candidate', severity: 'warning', sizeBytes: 10 }],
  { columns: [1, 2], cellsScanned: 9 },
)
assert.equal(summary.critical, 1)
assert.equal(summary.candidates, 2)
assert.equal(summary.bytesByStatus['orphan-candidate'], 50)
assert.equal(summary.referenceColumns, 2)

const destructiveAttempt = spawnSync(
  process.execPath,
  [path.resolve(__dirname, '../scripts/audit-storage-references.js'), '--delete=true'],
  { encoding: 'utf8', env: { ...process.env, DATABASE_URL: '' } },
)
assert.notEqual(destructiveAttempt.status, 0)
assert.match(destructiveAttempt.stderr, /STORAGE_REFERENCE_AUDIT_IS_READ_ONLY/)

async function testDatabaseReferenceCollection() {
  const client = {
    async query(sql) {
      const text = String(sql)
      if (text.includes('information_schema.columns')) {
        return {
          rows: [{
            table_schema: 'public',
            table_name: 'fallback_videos',
            column_name: 'video_url',
            udt_name: 'text',
          }],
        }
      }
      return {
        rows: [{
          row_locator: '(0,1)',
          value: [
            objects[0].public_url,
            'https://novvin.example/storage/v1/object/public/videos/missing/file.mp4',
          ].join(' '),
        }],
      }
    },
  }
  const scan = await collectDatabaseReferences(client, objects, { schemas: ['public'], pageSize: 10 })
  assert.equal(scan.references.get('videos/owner/video 1.mp4').length, 1)
  assert.equal(scan.danglingReferences.get('videos/missing/file.mp4').length, 1)
  assert.equal(scan.cellsScanned, 1)

  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-storage-audit-'))
  try {
    const videoRoot = path.join(temporaryRoot, 'videos')
    fs.mkdirSync(videoRoot, { recursive: true })
    const referencedPath = path.join(videoRoot, 'referenced.mp4')
    const orphanPath = path.join(videoRoot, 'orphan.mp4')
    const untrackedPath = path.join(videoRoot, 'untracked.mp4')
    fs.writeFileSync(referencedPath, Buffer.alloc(10))
    fs.writeFileSync(orphanPath, Buffer.alloc(20))
    fs.writeFileSync(untrackedPath, Buffer.alloc(30))
    const oldDate = new Date('2026-08-01T00:00:00Z')
    fs.utimesSync(untrackedPath, oldDate, oldDate)
    const auditObjects = [
      {
        id: '33333333-3333-4333-8333-333333333333',
        bucket_id: 'videos',
        name: 'referenced.mp4',
        local_path: referencedPath,
        public_url: 'https://novvin.example/storage/v1/object/public/videos/referenced.mp4',
        size_bytes: 10,
        created_at: oldDate.toISOString(),
        updated_at: oldDate.toISOString(),
      },
      {
        id: '44444444-4444-4444-8444-444444444444',
        bucket_id: 'videos',
        name: 'orphan.mp4',
        local_path: orphanPath,
        public_url: 'https://novvin.example/storage/v1/object/public/videos/orphan.mp4',
        size_bytes: 20,
        created_at: oldDate.toISOString(),
        updated_at: oldDate.toISOString(),
      },
    ]
    const auditClient = {
      async query(sql) {
        const text = String(sql)
        if (text.includes('FROM fallback_storage_objects')) return { rows: auditObjects }
        if (text.includes('information_schema.columns')) {
          return {
            rows: [{
              table_schema: 'public',
              table_name: 'fallback_videos',
              column_name: 'video_url',
              udt_name: 'text',
            }],
          }
        }
        if (text.includes('SELECT ctid::text')) {
          return {
            rows: [{
              row_locator: '(0,1)',
              value: auditObjects[0].public_url,
            }],
          }
        }
        if (text.includes('FROM fallback_storage_buckets')) return { rows: [{ id: 'videos' }] }
        throw new Error(`Unexpected audit query: ${text}`)
      },
    }
    const report = await auditStorageReferences(auditClient, {
      storageRoot: temporaryRoot,
      schemas: ['public'],
      pageSize: 10,
      nowMs,
      graceHours: 7 * 24,
    })
    assert.equal(report.mode, 'read-only')
    assert.equal(report.destructiveActions, false)
    assert.equal(report.objects.find((item) => item.name === 'referenced.mp4').status, 'referenced')
    assert.equal(report.objects.find((item) => item.name === 'orphan.mp4').status, 'orphan-candidate')
    assert.equal(report.filesystemOnly[0].status, 'filesystem-orphan-candidate')
    assert.equal(report.summary.candidates, 2)
    assert.equal(report.summary.critical, 0)
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true })
  }
}

testDatabaseReferenceCollection()
  .then(() => console.log('storage reference audit tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
