'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFileSync, spawnSync } = require('node:child_process')

const script = path.resolve(__dirname, '../../scripts/verify-backup.sh')
const linux = { skip: process.platform !== 'linux' && 'uses the Ubuntu backup utilities' }

function backup(t, version) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-backup-verify-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const bin = path.join(root, 'bin')
  const archive = path.join(root, 'backup')
  const payload = path.join(root, 'payload')
  for (const dir of [bin, archive, payload]) fs.mkdirSync(dir)
  fs.writeFileSync(path.join(bin, 'docker'), '#!/bin/sh\n[ "$*" = "compose exec -T db pg_restore --list" ] && cat >/dev/null\n', { mode: 0o700 })
  fs.writeFileSync(path.join(payload, 'example.txt'), 'synthetic contents')
  const files = ['postgres.dump', 'storage.tar.gz']
  const write = (name, content) => fs.writeFileSync(path.join(archive, name), content)
  const bytes = name => fs.statSync(path.join(archive, name)).size
  const tar = name => execFileSync('tar', ['-C', payload, '-czf', path.join(archive, name), '.'])
  write('postgres.dump', 'fixture for pg_restore invocation')
  tar('storage.tar.gz')
  const metadata = { schemaVersion: version, databaseBytes: bytes('postgres.dump'), storageBytes: bytes('storage.tar.gz'), verification: 'passed' }
  if (version >= 1) {
    files.push('docker-compose.yml')
    write('docker-compose.yml', 'services: {}\n')
  }
  if (version >= 2) {
    files.push('ai-chatbot-data.tar.gz')
    tar('ai-chatbot-data.tar.gz')
    metadata.aiDataBytes = bytes('ai-chatbot-data.tar.gz')
  }
  if (version >= 3) {
    files.push('roles-no-passwords.sql')
    write('roles-no-passwords.sql', 'CREATE ROLE fixture;\n')
    metadata.rolesBytes = bytes('roles-no-passwords.sql')
  }
  if (version) write('metadata.json', JSON.stringify(metadata))
  function checksums(names) {
    write('SHA256SUMS', names.map(name => {
      const digest = crypto.createHash('sha256').update(fs.readFileSync(path.join(archive, name))).digest('hex')
      return digest + '  ' + name + '\n'
    }).join(''))
  }
  checksums(files)
  function verify(ok, filesOnly = false) {
    const args = filesOnly ? [script, '--files-only', archive] : [script, archive]
    const r = spawnSync('bash', args, { env: { ...process.env, PATH: bin + ':' + process.env.PATH }, encoding: 'utf8' })
    assert.equal(r.error, undefined)
    assert.equal(r.status === 0, ok, r.stdout + r.stderr)
    assert.equal(r.stdout.includes('BACKUP_VERIFIED='), ok && !filesOnly)
    assert.equal(r.stdout.includes('BACKUP_FILES_VERIFIED='), ok && filesOnly)
  }
  return { files, metadata, write, checksums, verify, archive, bin }
}

for (const version of [0, 1, 2, 3]) {
  test('accepts a complete backup in format ' + (version || 'legacy without metadata'), linux, t => backup(t, version).verify(true))
}
for (const [version, file] of [[1, 'docker-compose.yml'], [2, 'ai-chatbot-data.tar.gz'], [3, 'roles-no-passwords.sql']]) {
  test('rejects a format ' + version + ' copy missing ' + file + ' from both the manifest and directory', linux, t => {
    const a = backup(t, version)
    fs.unlinkSync(path.join(a.archive, file))
    a.checksums(a.files.filter(name => name !== file))
    a.verify(false)
  })
}
for (const [reason,value] of [['truncated JSON','{"schemaVersion":'], ['a missing version','{}'], ['an unknown version','{"schemaVersion":44}']]) {
  test('rejects backup metadata with ' + reason, linux, t => {
    const a = backup(t, 3)
    a.write('metadata.json', value)
    a.verify(false)
  })
}
test('rejects a valid checksum when the copied size differs from the producer receipt', linux, t => {
  const a = backup(t, 2)
  a.write('metadata.json', JSON.stringify({ ...a.metadata, databaseBytes: a.metadata.databaseBytes + 1 }))
  a.verify(false)
})
test('rejects an unverified optional archive accompanying a legacy copy', linux, t => {
  const a = backup(t, 2)
  fs.unlinkSync(path.join(a.archive, 'metadata.json'))
  a.checksums(a.files.filter(name => name !== 'ai-chatbot-data.tar.gz'))
  a.verify(false)
})
test('requires the full role-backup artifact set even after metadata is lost', linux, t => {
  const a = backup(t, 3)
  fs.unlinkSync(path.join(a.archive, 'metadata.json'))
  a.verify(true)
  fs.unlinkSync(path.join(a.archive, 'ai-chatbot-data.tar.gz'))
  a.checksums(a.files.filter(name => name !== 'ai-chatbot-data.tar.gz'))
  a.verify(false)
})
test('checks files with Compose unavailable and distinguishes the full verification result', linux, t => {
  const a = backup(t, 3)
  fs.writeFileSync(path.join(a.bin, 'docker'), '#!/bin/sh\nexit 1\n', { mode: 0o700 })
  a.verify(true, true)
  a.verify(false)
  a.write('postgres.dump', 'incomplete')
  a.verify(false, true)
})

test('the isolated role drill preserves grants and stops before restoring data on a role error', linux, t => {
  const a = backup(t, 3)
  const drill = path.join(path.dirname(script), 'restore-drill-isolated.sh')
  const roles = path.join(a.bin, 'applied.sql')
  const restoreArgs = path.join(a.bin, 'restore.args')
  const otherStatements = 'ALTER ROLE conectate SUPERUSER;\nCREATE ROLE fixture;\n'
  fs.writeFileSync(path.join(a.bin, 'docker'), `#!/bin/sh
case "$*" in
  "image inspect"*) echo image ;;
  *"--entrypoint id"*) echo 70 ;;
  "run --detach"*)
    while [ "$1" != "--name" ]; do shift; done
    echo "$2" > "$TRACE/name" ;;
  *"/proc/1/comm"*) exit 0 ;;
  *"--single-transaction"*) cat > "$TRACE/applied.sql"; test "$ROLE_ERROR" = 0 ;;
  *"pg_restore"*) echo "$*" > "$TRACE/restore.args"; cat >/dev/null ;;
  *"psql"*) echo 8 ;;
  "inspect --format"*) cat "$TRACE/name" ;;
  *) exit 0 ;;
esac
`, { mode: 0o700 })
  for (const failure of ['none', 'missing bootstrap entry', 'role SQL error']) {
    const sql = (failure === 'missing bootstrap entry' ? '' : 'CREATE ROLE conectate;\n') + otherStatements
    a.write('roles-no-passwords.sql', sql)
    a.write('metadata.json', JSON.stringify({ ...a.metadata, rolesBytes: Buffer.byteLength(sql) }))
    a.checksums(a.files)
    fs.rmSync(restoreArgs, { force: true })
    const r = spawnSync('bash', [drill, '--with-roles', a.archive], { encoding: 'utf8', env: {
      ...process.env, PATH: a.bin + ':' + process.env.PATH, TRACE: a.bin, ROLE_ERROR: failure === 'role SQL error' ? '1' : '0',
    } })
    const diagnostic = r.stdout.match(/^RESTORE_DIAGNOSTIC_DIR=(.+)$/m)?.[1]
    assert.ok(diagnostic && path.dirname(diagnostic) === '/tmp' && path.basename(diagnostic).startsWith('novvin-restore-check.'))
    t.after(() => fs.rmSync(diagnostic, { recursive: true, force: true }))
    assert.equal(fs.readFileSync(roles, 'utf8'), otherStatements)
    assert.equal(r.status === 0, failure === 'none', r.stdout + r.stderr)
    assert.equal(fs.existsSync(restoreArgs), failure === 'none')
    if (failure === 'none') {
      const args = fs.readFileSync(restoreArgs, 'utf8')
      assert.match(args, /--exit-on-error .*--create/)
      assert.doesNotMatch(args, /--no-owner|--no-privileges/)
    }
  }
})
