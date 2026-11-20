const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '../../../..')
test('all first-party Node container services use the supported pinned LTS and patched system zlib', () => {
  for (const relative of ['signaling-server/Dockerfile','infrastructure/local-fallback/api/Dockerfile',
    'infrastructure/local-fallback/realtime-gateway/Dockerfile','infrastructure/local-fallback/ai-chatbot/Dockerfile',
    'infrastructure/local-fallback/survey-antivirus/Dockerfile','infrastructure/local-fallback/storage-antivirus/Dockerfile']) {
    const dockerfile = fs.readFileSync(path.join(root,relative),'utf8')
    const base = dockerfile.match(/^FROM node:22\.(\d+)\.(\d+)-alpine@sha256:[a-f0-9]{64}$/m)
    assert.ok(base, relative)
    assert.ok(Number(base[1]) > 23 || (Number(base[1]) === 23 && Number(base[2]) >= 5), relative)
    assert.match(dockerfile,/RUN apk add --no-cache --upgrade 'zlib>=1\.4-r0' 'libssl3>=3\.5\.9-r1' 'libcrypto3>=3\.5\.9-r1'/,relative)
  }
})
