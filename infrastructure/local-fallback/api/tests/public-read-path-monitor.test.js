const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const test = require('node:test')

const script = path.resolve(__dirname, '../../scripts/monitor-public-read-paths.sh')

test('the monitor checks public TLS independently of private host routing', () => {
  const source = fs.readFileSync(script, 'utf8')
  assert.match(source, /run_probe public-ingress verify-public-ingress\.js/)
  assert.match(source, /--entrypoint node/)
  assert.match(source, /"scripts\/\$script"/)
})

function bashPath(value) {
  if (process.platform !== 'win32') return value
  const normalized = value.replaceAll('\\', '/')
  const match = normalized.match(/^([a-zA-Z]):\/(.*)$/)
  return match ? `/mnt/${match[1].toLowerCase()}/${match[2]}` : normalized
}

function runMonitor(fixture, dockerExit, failedScript = '') {
  const bashExecutable = process.platform === 'win32'
    ? path.join(process.env.WINDIR || 'C:\\Windows', 'System32', 'bash.exe')
    : 'bash'
  const environmentPath = process.platform === 'win32'
    ? `${bashPath(fixture.bin)}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`
    : `${fixture.bin}${path.delimiter}${process.env.PATH || ''}`
  return spawnSync(bashExecutable, [bashPath(script)], {
    cwd: path.resolve(__dirname, '../..'),
    encoding: 'utf8',
    env: {
      ...process.env,
      FAKE_DOCKER_EXIT: String(dockerExit),
      FAKE_DOCKER_FAIL_SCRIPT: failedScript,
      PATH: environmentPath,
      PUBLIC_MONITOR_FAILURE_THRESHOLD: '3',
      PUBLIC_MONITOR_LOCK: bashPath(path.join(fixture.root, 'monitor.lock')),
      PUBLIC_MONITOR_PROBE_TIMEOUT_SECONDS: '20',
      PUBLIC_MONITOR_STATE_DIR: bashPath(fixture.state),
      PUBLIC_URL_FILE: bashPath(fixture.url),
    },
  })
}

test('public read-path monitor tolerates isolated failures and alerts on the third consecutive failure', {
  skip: process.platform === 'win32' ? 'validated on the Linux production target' : false,
}, () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-public-monitor-'))
  try {
    const bin = path.join(root, 'bin')
    const state = path.join(root, 'state')
    const url = path.join(root, 'public-url.txt')
    fs.mkdirSync(bin)
    fs.writeFileSync(url, 'https://probe.example.test\n')
    const docker = path.join(bin, 'docker')
    fs.writeFileSync(docker, `#!/bin/sh
case "$*" in
  *"compose exec"*) printf 'header.payload.signature' ;;
  *"compose images"*) printf 'sha256:fake-api-image\\n' ;;
  run*)
    if [ -z "\${FAKE_DOCKER_FAIL_SCRIPT:-}" ]; then exit "\${FAKE_DOCKER_EXIT:-0}"; fi
    case "$*" in
      *"\${FAKE_DOCKER_FAIL_SCRIPT}"*) exit "\${FAKE_DOCKER_EXIT:-0}" ;;
      *) exit 0 ;;
    esac ;;
  *) exit 0 ;;
esac
`)
    fs.chmodSync(docker, 0o755)
    const fixture = { bin, root, state, url }

    const healthy = runMonitor(fixture, 0)
    assert.equal(healthy.status, 0, healthy.stderr)
    assert.match(healthy.stdout, /PUBLIC_READ_PATHS_OK=/)
    assert.equal((healthy.stdout.match(/PUBLIC_READ_PATHS_PROBE=/g) || []).length, 3)
    assert.match(fs.readFileSync(path.join(state, 'public-read-paths.status'), 'utf8'), /status=ok/)

    const first = runMonitor(fixture, 1)
    const second = runMonitor(fixture, 1)
    const third = runMonitor(fixture, 1)
    assert.equal(first.status, 0, first.stderr)
    assert.equal(second.status, 0, second.stderr)
    assert.equal(third.status, 1)
    assert.match(third.stdout, /consecutive_failures=3/)
    assert.match(fs.readFileSync(path.join(state, 'public-read-paths.status'), 'utf8'), /status=degraded/)

    // Private application reads succeeding cannot hide failed public ingress.
    assert.equal(runMonitor(fixture, 0).status, 0)
    assert.equal(runMonitor(fixture, 1, 'verify-public-ingress.js').status, 0)
    assert.equal(runMonitor(fixture, 1, 'verify-public-ingress.js').status, 0)
    const publicFailure = runMonitor(fixture, 1, 'verify-public-ingress.js')
    assert.equal(publicFailure.status, 1, publicFailure.stdout)
    assert.match(publicFailure.stdout, /probes=public-ingress/)
  } finally {
    fs.rmSync(root, { force: true, recursive: true })
  }
})
