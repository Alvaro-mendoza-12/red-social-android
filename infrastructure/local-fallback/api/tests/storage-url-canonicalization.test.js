const assert = require('node:assert/strict')
const {
  canonicalStorageUrl,
  canonicalizeStorageUrls,
} = require('../src/storage-url-canonicalization')
const { canonicalizeStoredUrls, URL_PREFIX_PATTERN } = require('../src/canonicalize-storage-urls')

const currentBase = 'https://current.example'
const oldAvatar = 'https://old-tunnel.example/storage/v1/object/public/avatars/user/avatar.jpg?v=1'

assert.equal(
  canonicalStorageUrl(oldAvatar, `${currentBase}/`),
  `${currentBase}/storage/v1/object/public/avatars/user/avatar.jpg?v=1`,
)
assert.equal(
  canonicalStorageUrl('/storage/v1/object/public/videos/user/video.mp4', currentBase),
  `${currentBase}/storage/v1/object/public/videos/user/video.mp4`,
)
assert.equal(canonicalStorageUrl('https://cdn.example/image.jpg', currentBase), 'https://cdn.example/image.jpg')
assert.equal(canonicalStorageUrl(`Texto ${oldAvatar}`, currentBase), `Texto ${oldAvatar}`)
assert.deepEqual(
  canonicalizeStorageUrls({ profile: { avatar_url: oldAvatar }, media_urls: [oldAvatar] }, currentBase),
  {
    profile: { avatar_url: `${currentBase}/storage/v1/object/public/avatars/user/avatar.jpg?v=1` },
    media_urls: [`${currentBase}/storage/v1/object/public/avatars/user/avatar.jpg?v=1`],
  },
)

async function run() {
  const queries = []
  const client = {
    async query(sql, params) {
      const text = String(sql)
      queries.push({ params, text })
      if (text.includes('information_schema.columns')) {
        return {
          rows: [
            { table_schema: 'public', table_name: 'fallback_users', column_name: 'avatar_url', udt_name: 'text' },
            { table_schema: 'public', table_name: 'fallback_compat_rows', column_name: 'row', udt_name: 'jsonb' },
          ],
        }
      }
      if (text.startsWith('SELECT COUNT')) return { rows: [{ count: 1 }] }
      return { rowCount: 1, rows: [] }
    },
  }
  const summary = await canonicalizeStoredUrls(client, {
    apply: true,
    baseUrl: currentBase,
    schemas: ['public'],
  })
  assert.equal(summary.length, 2)
  assert.equal(summary.every((item) => item.updated === 1), true)
  assert.equal(queries.some((query) => query.text.includes('regexp_replace') && query.text.includes('::jsonb')), true)
  assert.equal(queries.some((query) => query.text.includes("replace(") && query.params?.[1]?.startsWith(currentBase)), true)
  assert.equal(queries.every((query) => !query.params || !query.params.includes(undefined)), true)
  assert.match(URL_PREFIX_PATTERN, /storage/)
  console.log('storage URL canonicalization tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
