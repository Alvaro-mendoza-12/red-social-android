'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

test('API startup remains bounded when the signature mirror hangs, under the actual dropped capabilities',
  { skip: process.platform !== 'linux' || process.env.NOVVIN_TEST_ANTIVIRUS_STARTUP !== '1' }, () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-av-start-'))
    fs.chmodSync(root, 0o755)
    try {
      // Only external programs are fixtures. Execute the actual entrypoint,
      // su-exec, timeout and user changes from the deployment image.
      fs.writeFileSync(path.join(root, 'freshclam'), '#!/bin/sh\necho QA_REFRESH_ATTEMPT\ncase "$QA_REFRESH_MODE" in hang) trap "" TERM; exec sleep 120 ;; fail) exit 1 ;; esac\n', { mode: 0o755 })
      fs.writeFileSync(path.join(root, 'node'), '#!/bin/sh\ncase "$1" in src/server.js) echo QA_SERVER_STARTED ;; scripts/prepare-runtime-storage.js) echo QA_STORAGE_PREPARED ;; *) exit 90 ;; esac\n', { mode: 0o755 })
      for (const mode of ['success', 'fail', 'hang']) {
        const before = performance.now()
        const result = spawnSync('/bin/sh', ['scripts/start-with-antivirus.sh'], { cwd: path.resolve(__dirname, '..'),
          env: { ...process.env, PATH: `${root}:${process.env.PATH}`, MUSIC_ANTIVIRUS_REQUIRED: 'true',
            API_ANTIVIRUS_REFRESH_TIMEOUT_SECONDS: '1', QA_REFRESH_MODE: mode,
            FIREBASE_SERVICE_ACCOUNT_SOURCE_FILE: '/nonexistent-qa', GOOGLE_PLAY_SERVICE_ACCOUNT_SOURCE_FILE: '/nonexistent-qa', RELEASE_METADATA_SOURCE_FILE: '/nonexistent-qa' },
          encoding: 'utf8', timeout: 15_000 })
        assert.equal(result.status, 0, `${mode}: ${result.stdout}\n${result.stderr}`)
        assert.match(result.stdout, /QA_REFRESH_ATTEMPT/)
        assert.match(result.stdout, /QA_SERVER_STARTED/)
        assert.ok(performance.now() - before < 12_000, `${mode} exceeded the startup bound`)
        if (mode !== 'success') assert.match(result.stdout, /uploads still require valid antivirus signatures/)
      }
    } finally { fs.rmSync(root, { recursive: true, force: true }) }
  })
