const assert = require('node:assert/strict')
const { test } = require('node:test')
const { registerDataRoutes } = require('../src/data-routes')
const { _test } = require('../src/postgrest-compat-routes')

test('compatibility clients cannot insert new audio comments through either table', async () => {
  const pool = { query() { throw new Error('Audio rejection must precede database writes') } }
  for (const table of ['post_comments', 'video_comments']) {
    for (const field of ['media_type', 'content_type']) {
      await assert.rejects(_test.prepareInsertRow(pool, table, {}, { [field]: 'Audio/mp4' }),
        { message: 'COMMENT_AUDIO_DISABLED', status: 400 })
    }
  }
})

test('compatibility updates cannot convert a comment to audio before writing', async () => {
  const pool = { query() { throw new Error('Audio rejection must precede database writes') } }
  for (const table of ['post_comments', 'video_comments']) {
    for (const field of ['media_type', 'content_type']) {
      await assert.rejects(_test.handlePatch(pool, table, {}, {}, { [field]: 'audio/mp4' }),
        { message: 'COMMENT_AUDIO_DISABLED', status: 400 })
    }
  }
})

test('historical audio permits text edits but cannot replace its audio attachment', () => {
  for (const table of ['post_comments', 'video_comments']) {
    const current = { content_type: 'audio', media_url: 'previous-audio' }
    assert.doesNotThrow(() => _test.rejectCommentAudioPatch(table, current, { content: 'Texto editado' }))
    assert.throws(() => _test.rejectCommentAudioPatch(table, current, { media_url: 'new-audio' }),
      { message: 'COMMENT_AUDIO_DISABLED', status: 400 })
    assert.doesNotThrow(() => _test.rejectCommentAudioPatch(table, current, { content_type: 'text', media_url: '' }))
  }
})

for (const [route, field, params] of [
  ['/v1/data/feed/posts/:postId/comments', 'mediaType', { postId: 'post' }],
  ['/v1/data/feed/posts/:postId/comments', 'media_type', { postId: 'post' }],
  ['/v1/data/videos/:videoId/comments', 'contentType', { videoId: 'video' }],
  ['/v1/data/videos/:videoId/comments', 'content_type', { videoId: 'video' }],
]) {
  test(`rejects new audio comments through ${route} (${field}) before writing`, async () => {
    const handlers = new Map()
    const app = Object.fromEntries(['get', 'post', 'patch', 'delete', 'put', 'use'].map(method =>
      [method, (path, ...chain) => handlers.set(`${method} ${path}`, chain.at(-1))]))
    let reads = 0
    const pool = { async query(sql) {
      assert.match(sql, /FROM fallback_users/)
      reads++
      return { rows: [{ id: 'commenter', display_name: 'Persona' }] }
    } }
    registerDataRoutes(app, { pool, authRequired() {} })
    for (const type of ['audio', 'AUDIO', 'audio/wav']) {
      const res = { statusCode: 200, status(code) { this.statusCode = code; return this },
        json(body) { this.body = body; return this } }
      await handlers.get(`post ${route}`)({ auth: { sub: 'commenter' }, params,
        body: { content: '[Audio]', mediaUrl: 'https://media.test/legacy.wav', [field]: type } },
      res, error => { throw error })
      assert.equal(res.statusCode, 400)
      assert.equal(res.body.error, 'COMMENT_AUDIO_DISABLED')
    }
    assert.equal(reads, 3)
  })
}
