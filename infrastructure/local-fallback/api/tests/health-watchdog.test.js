'use strict'

const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const watchdog = path.resolve(__dirname, '../../scripts/health-watchdog.sh')

function runWatchdog(t, settings = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-watchdog-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  const trace = path.join(directory, 'docker.log')
  fs.writeFileSync(trace, '')

  const probes = {
    curl: `case "\${*: -1}" in
  */ready)
    if [ -f "$RECOVERED" ]; then printf 200
    elif [ -f "$LOCKED" ]; then printf '%s' "$READY_AFTER_LOCK"
    else printf '%s' "$READY"; fi ;;
  */health) [ "$LIVE" = 200 ] ;;
  *) exit 10 ;;
esac`,
    docker: `printf '%s\\n' "$*" >> "$TRACE"
case "$*" in
  'compose exec -T db pg_isready -U conectate -d conectate_fallback')
    [ "$DATABASE" = ready ] || [ -f "$RECOVERED" ] ;;
  'compose ps --status running --services')
    printf '%s\\n' db api ai-chatbot signaling edge realtime-gateway storage-antivirus
    if [ "$UPDATER" != missing ]; then echo storage-antivirus-updater; fi ;;
  'compose ps -q ai-chatbot') echo ai-container ;;
  'compose ps -q storage-antivirus-updater') echo updater-container ;;
  'inspect --format {{.State.Health.Status}} ai-container') echo healthy ;;
  'inspect --format {{.State.Health.Status}} updater-container') echo "$UPDATER" ;;
  'compose up -d --remove-orphans') touch "$RECOVERED" ;;
  'compose exec -T edge nginx -t'|'compose exec -T edge nginx -s reload') exit 0 ;;
  *) exit 11 ;;
esac`,
    flock: 'touch "$LOCKED"',
    sleep: 'exit 0',
  }
  for (const [name, script] of Object.entries(probes)) {
    fs.writeFileSync(path.join(directory, name), `#!/usr/bin/env bash\n${script}\n`, { mode: 0o755 })
  }

  const result = spawnSync('bash', [watchdog], {
    encoding: 'utf8',
    timeout: 5000,
    env: {
      ...process.env,
      PATH: directory + path.delimiter + process.env.PATH,
      BACKUP_ROOT: directory,
      CONECTATE_COMPOSE_LOCK: path.join(directory, 'compose.lock'),
      TRACE: trace,
      LOCKED: path.join(directory, 'locked'),
      RECOVERED: path.join(directory, 'recovered'),
      READY: '503',
      READY_AFTER_LOCK: '503',
      LIVE: '200',
      DATABASE: 'ready',
      UPDATER: 'unhealthy',
      ...settings,
    },
  })
  assert.ifError(result.error)
  return { ...result, trace: fs.readFileSync(trace, 'utf8') }
}

test('readiness reports healthy without reconciling running services', { skip: process.platform === 'win32' }, t => {
  const result = runWatchdog(t, { READY: '200' })
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /HEALTH_OK=/)
  assert.doesNotMatch(result.trace, /compose up|nginx -s reload|compose restart/)
})

test('an unhealthy signature updater keeps readiness failed without reconciling live services', { skip: process.platform === 'win32' }, t => {
  const result = runWatchdog(t)
  assert.equal(result.status, 1)
  assert.match(result.stdout, /actualizador de firmas degradado/)
  assert.doesNotMatch(result.stdout, /HEALTH_OK=/)
  assert.doesNotMatch(result.trace, /compose up|nginx -s reload|compose restart/)
})

for (const settings of [{ LIVE: '500' }, { DATABASE: 'stopped' }, { UPDATER: 'missing' }]) {
  test(`an independent service failure still triggers recovery (${Object.keys(settings)[0]})`, { skip: process.platform === 'win32' }, t => {
    const result = runWatchdog(t, settings)
    assert.equal(result.status, 0, result.stderr)
    assert.match(result.trace, /compose up -d --remove-orphans/)
    assert.match(result.trace, /nginx -s reload/)
    assert.match(result.stdout, /HEALTH_OK=/)
  })
}

test('readiness can change to a signature incident while waiting for the lock', { skip: process.platform === 'win32' }, t => {
  const result = runWatchdog(t, { READY: '502' })
  assert.equal(result.status, 1)
  assert.match(result.stdout, /actualizador de firmas degradado/)
  assert.doesNotMatch(result.trace, /compose up|nginx -s reload|compose restart/)
})
