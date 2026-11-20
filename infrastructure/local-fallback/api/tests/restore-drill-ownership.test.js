'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const test = require('node:test')

test('restore drill cleans up only its own database and propagates restore and cleanup failures', {
  skip: process.platform === 'win32' && 'requires Bash',
}, async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-restore-owner-'))
  t.after(() => fs.rm(dir, { recursive: true, force: true }))
  const script = path.resolve(__dirname, '../../scripts/restore-drill.sh')
  await fs.writeFile(path.join(dir, 'postgres.dump'), 'synthetic dump')
  await fs.writeFile(path.join(dir, 'docker'), `#!/bin/sh
set -eu
printf '%s\\n' "$*" >> "$DRILL_LOG"
shift 4
case "$1" in
  createdb) [ "$DRILL_MODE" != create-fails ] ;;
  pg_restore) [ "$DRILL_MODE" != restore-fails ] ;;
  psql) echo 3 ;;
  dropdb) [ "$DRILL_MODE" != cleanup-fails ] ;;
  *) exit 18 ;;
esac
`, { mode: 0o700 })
  for (const mode of ['create-fails', 'restore-fails', 'cleanup-fails', 'success']) {
    const log = path.join(dir, mode)
    const run = spawnSync('bash', [script, dir], {
      encoding: 'utf8',
      env: { ...process.env, PATH: dir + ':' + process.env.PATH, DRILL_MODE: mode, DRILL_LOG: log },
    })
    const commands = (await fs.readFile(log, 'utf8')).trim().split('\n')
    const drops = commands.filter(line => line.includes(' dropdb '))
    assert.equal(drops.length, mode === 'create-fails' ? 0 : 1, mode)
    if (drops.length) {
      const name = commands.find(line => line.includes(' createdb ')).split(' ').at(-1)
      assert.equal(drops[0].split(' ').at(-1), name)
      assert.ok(commands.find(line => line.includes(' pg_restore ')).includes('--exit-on-error'))
    }
    assert.equal(run.status === 0, mode === 'success', mode)
    assert.equal(run.stdout.includes('RESTORE_DRILL_OK='), mode === 'success', mode)
  }
})
