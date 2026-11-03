'use strict'
const crypto = require('node:crypto')
const { S3Client, HeadObjectCommand, GetObjectCommand, DeleteObjectCommand } = require('@aws-sdk/client-s3')
const { Upload } = require('@aws-sdk/lib-storage')

function createRemote(config) {
  const client = new S3Client({ region: 'auto', endpoint: config.endpoint, forcePathStyle: true,
    credentials: { accessKeyId: config.accessKeyId, secretAccessKey: config.secretAccessKey },
    maxAttempts: 3, requestChecksumCalculation: 'WHEN_REQUIRED', responseChecksumValidation: 'WHEN_REQUIRED',
    requestHandler: { connectionTimeout: 10_000, requestTimeout: 120_000 } })
  function target(key) { return { Bucket: config.bucket, Key: key } }
  async function send(command) { return client.send(command, { abortSignal: AbortSignal.timeout(120_000) }) }
  return {
    async putVerified({ key, source, size, sha256, contentType }) {
      let existing = null
      try { existing = await send(new HeadObjectCommand(target(key))) } catch (error) {
        if (error?.$metadata?.httpStatusCode !== 404) throw error
      }
      if (existing && (existing.ContentLength !== size || existing.Metadata?.sha256 !== sha256 || existing.ContentType !== contentType)) {
        throw Object.assign(new Error('R2_REMOTE_IDENTITY_MISMATCH'), { code: 'R2_REMOTE_IDENTITY_MISMATCH' })
      }
      if (!existing) {
        const body = source.createReadStream({ start: 0, autoClose: false })
        const upload = new Upload({ client, queueSize: 1, partSize: 8 * 1024 * 1024, leavePartsOnError: false,
          params: { ...target(key), Body: body, ContentLength: size, ContentType: contentType,
            CacheControl: 'private, no-store', Metadata: { sha256 } } })
        const timeout = setTimeout(() => { upload.abort().catch(() => {}); body.destroy() }, 10 * 60_000)
        try { await upload.done() } finally { clearTimeout(timeout); body.destroy() }
      }
      // Verify actual remote bytes, not merely user-supplied metadata or multipart ETag.
      const result = await send(new GetObjectCommand(target(key)))
      const hash = crypto.createHash('sha256'); let received = 0
      const timer = setTimeout(() => result.Body.destroy(), 10 * 60_000)
      try {
        for await (const chunk of result.Body) {
          received += chunk.length
          if (received > size) throw Object.assign(new Error('R2_REMOTE_SIZE_MISMATCH'), { code: 'R2_REMOTE_SIZE_MISMATCH' })
          hash.update(chunk)
        }
        if (received !== size || hash.digest('hex') !== sha256) throw Object.assign(new Error('R2_REMOTE_HASH_MISMATCH'), { code: 'R2_REMOTE_HASH_MISMATCH' })
      } finally { clearTimeout(timer); result.Body.destroy() }
    },
    async remove(key) { await send(new DeleteObjectCommand(target(key))) },
    close() { client.destroy() },
  }
}
module.exports = { createRemote }
