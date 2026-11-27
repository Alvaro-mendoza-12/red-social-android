const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const { createHash } = require('node:crypto')
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

test('upload scanners and signature updaters require the patched ClamAV family', () => {
  const requiredPackages = {
    'api/Dockerfile': ['clamav'],
    'survey-antivirus/Dockerfile': ['clamav-daemon'],
    'storage-antivirus/Dockerfile': ['clamav-daemon', 'clamav'],
  }
  for (const [relative, packages] of Object.entries(requiredPackages)) {
    const dockerfile = fs.readFileSync(path.join(root, 'infrastructure/local-fallback', relative), 'utf8')
    for (const name of packages) {
      assert.ok(dockerfile.includes("'" + name + ">=1.4.7-r0'"), relative + ': ' + name)
    }
  }
})

test('scanner config and storage policy match the hashes used for staging attestation', () => {
  const fallback = path.join(root, 'infrastructure/local-fallback')
  const policy = fs.readFileSync(path.join(fallback, 'storage-antivirus/storage-scan-policy.json'))
  const config = fs.readFileSync(path.join(fallback, 'storage-antivirus/clamd.conf'))
  const compose = fs.readFileSync(path.join(fallback, 'docker-compose.storage-antivirus.yml'), 'utf8')
  const digest = file => createHash('sha256').update(file).digest('hex')
  assert.equal(digest(config), JSON.parse(policy).configSha256)
  assert.equal(digest(policy), compose.match(/STORAGE_CLAMD_POLICY_SHA256: ([a-f0-9]{64})/)[1])
})
