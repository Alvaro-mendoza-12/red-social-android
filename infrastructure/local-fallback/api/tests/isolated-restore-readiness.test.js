'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const test = require('node:test')

const script = path.resolve(__dirname, '../../scripts/restore-drill-isolated.sh')
test('isolated restore waits for the permanent postgres process and retains failure diagnostics', {
  skip: process.platform === 'win32' ? 'bash fixture runs on the Linux target' : false,
}, () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-restore-fixture-'))
  const diagnostics = []
  try {
    const bin = path.join(root, 'bin'); fs.mkdirSync(bin)
    fs.writeFileSync(path.join(root, 'postgres.dump'), 'fixture')
    fs.writeFileSync(path.join(bin, 'docker'), `#!/bin/sh
case "$*" in
  *"--entrypoint id"*) echo 70 ;;
  *"run --detach"*)
    while [ "$1" != "--name" ]; do shift; done
    shift; printf '%s' "$1" > "$FAKE_STATE/name"; echo container ;;
  *"/proc/1/comm"*)
    n=$(cat "$FAKE_STATE/ready" 2>/dev/null || echo 0)
    n=$((n+1)); echo "$n" > "$FAKE_STATE/ready"
    [ "$n" -ge 2 ] ;;
  *"pg_isready"*) echo 1 > "$FAKE_STATE/ready" ;;
  *"pg_restore"*)
    [ "$(cat "$FAKE_STATE/ready")" -ge 2 ] && [ "\${FAKE_RESTORE_FAIL:-0}" = 0 ] ;;
  *"psql"*) echo 216 ;;
  *"inspect --format"*) cat "$FAKE_STATE/name" ;;
  *"rm -f"*) echo yes > "$FAKE_STATE/removed" ;;
  logs*) echo fixture-startup-diagnostic ;;
  *) exit 0 ;;
esac
`)
    fs.chmodSync(path.join(bin, 'docker'), 0o755)
    for (const fail of [false, true]) {
      fs.rmSync(path.join(root, 'ready'), { force: true })
      fs.rmSync(path.join(root, 'removed'), { force: true })
      const run = spawnSync('bash', [script, root], { encoding: 'utf8', env: {
        ...process.env, PATH: `${bin}:${process.env.PATH}`, FAKE_STATE: root,
        FAKE_RESTORE_FAIL: fail ? '1' : '0',
      } })
      const folder = run.stdout.match(/RESTORE_DIAGNOSTIC_DIR=(.+)/)?.[1]
      assert.ok(folder, run.stderr); diagnostics.push(folder)
      assert.equal(fs.readFileSync(path.join(root, 'ready'), 'utf8').trim(), '2')
      assert.equal(fs.readFileSync(path.join(root, 'removed'), 'utf8').trim(), 'yes')
      assert.equal(run.status, fail ? 1 : 0, run.stdout + run.stderr)
      if (fail) assert.match(fs.readFileSync(path.join(folder, 'startup.log'), 'utf8'), /fixture-startup-diagnostic/)
      else assert.match(run.stdout, /RESTORE_ISOLATED_OK/)
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
    for (const folder of diagnostics) {
      assert.ok(folder.startsWith('/tmp/novvin-restore-check.'))
      fs.rmSync(folder, { recursive: true, force: true })
    }
  }
})
