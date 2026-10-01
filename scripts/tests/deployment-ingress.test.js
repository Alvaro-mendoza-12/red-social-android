const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const source = fs.readFileSync(path.resolve(__dirname, '../deploy-mini-pc-current.ps1'), 'utf8')

test('deployment accepts canonical HTTPS Funnel ports and rejects unrelated origins', () => {
  const pattern = source.match(/\$StablePublicBaseUrl -notmatch '([^']+)'/)[1]
  const allowed = new RegExp(pattern)
  for (const url of ['https://conectate.tail9b267c.ts.net', 'https://conectate.tail9b267c.ts.net:443', 'https://conectate.tail9b267c.ts.net:8443']) assert.ok(allowed.test(url))
  for (const url of ['http://node.ts.net', 'https://node.ts.net:8080', 'https://node.ts.net/ready', 'https://node.ts.net.evil.test', 'https://user:secret@node.ts.net']) assert.ok(!allowed.test(url))
})

test('deployment backs up before preflight writes and keeps Funnel independent of Cloudflare', () => {
  assert.ok(source.indexOf('./scripts/backup-ubuntu-primary.sh') < source.indexOf('ensure_secret STORAGE_SIGNING_SECRET'))
  assert.match(source, /if \[\[ "\$public_origin"[\s\S]*?curl -4 -fsS[\s\S]*?else\s+systemctl --user restart conectate-cloudflare-tunnel.service/)
  assert.match(source, /GetDirectoryName\(\$resolvedTemporary\)/)
  assert.match(source, /Remove-Item -LiteralPath \$resolvedTemporary/)
})
