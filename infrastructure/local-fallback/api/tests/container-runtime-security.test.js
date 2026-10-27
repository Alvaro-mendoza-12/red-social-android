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
    assert.match(dockerfile,/^FROM node:22\.23\.4-alpine$/m,relative)
    assert.match(dockerfile,/RUN apk add --no-cache --upgrade 'zlib>=1\.3\.2-r1'/,relative)
  }
})
