'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const repoRoot = path.resolve(__dirname, '..', '..', '..', '..')

test('edge keeps Socket.IO polling requests sticky while allowing signaling replicas', () => {
  const nginx = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'nginx', 'default.conf'),
    'utf8',
  )
  const compose = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'docker-compose.yml'),
    'utf8',
  )

  assert.match(nginx, /hash \$signaling_affinity_key consistent;/)
  assert.match(nginx, /server signaling:3003 resolve;/)
  assert.match(nginx, /proxy_pass http:\/\/novvin_signaling_cluster;/)
  assert.doesNotMatch(compose, /container_name:\s*conectate-local-fallback-signaling-main/)
  assert.match(compose, /replicas:\s*\$\{SIGNALING_REPLICAS:-1\}/)
})

test('edge preserves Cloudflare visitor IP and public protocol for downstream limits', () => {
  const nginx = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'nginx', 'default.conf'),
    'utf8',
  )

  assert.match(nginx, /map \$http_cf_connecting_ip \$novvin_client_ip/)
  assert.match(nginx, /proxy_set_header X-Forwarded-For \$novvin_client_ip;/)
  assert.match(nginx, /map \$http_x_forwarded_proto \$novvin_forwarded_proto/)
  assert.doesNotMatch(nginx, /proxy_set_header X-Forwarded-Proto \$scheme;/)
})

test('edge serves authorized storage bytes from a read-only internal mount', () => {
  const nginx = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'nginx', 'default.conf'),
    'utf8',
  )
  const compose = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'docker-compose.yml'),
    'utf8',
  )

  assert.match(nginx, /location \^~ \/_novvin_storage\/\s*\{[\s\S]*?internal;/)
  assert.match(nginx, /alias \/data\/storage\/;/)
  assert.match(nginx, /sendfile on;/)
  assert.match(compose, /STORAGE_ACCEL_PREFIX: \/_novvin_storage/)
  assert.match(compose, /conectate-fallback-storage:\/data\/storage:ro/)
})

test('edge compresses API metadata without compressing streamed media types', () => {
  const nginx = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'nginx', 'default.conf'),
    'utf8',
  )

  assert.match(nginx, /gzip on;/)
  assert.match(nginx, /gzip_vary on;/)
  assert.match(nginx, /gzip_min_length 1024;/)
  assert.match(nginx, /gzip_types[^;]*application\/json/)
  assert.doesNotMatch(nginx, /gzip_types[^;]*(video\/|audio\/|application\/octet-stream)/)
})

test('edge access logs never persist query tokens or direct client identifiers', () => {
  const nginx = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'nginx', 'default.conf'),
    'utf8',
  )
  const safeFormat = nginx.match(/log_format novvin_safe[\s\S]*?;/)?.[0] || ''

  assert.match(safeFormat, /\$uri/)
  assert.match(safeFormat, /\$request_id/)
  assert.doesNotMatch(safeFormat, /\$request_uri|\$args|\$remote_addr|\$http_referer|\$http_user_agent/)
  assert.match(nginx, /access_log \/var\/log\/nginx\/access\.log novvin_safe;/)
})

test('maintained production containers pin runtime patch versions', () => {
  const compose = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'docker-compose.yml'),
    'utf8',
  )
  const dockerfiles = [
    path.join(repoRoot, 'signaling-server', 'Dockerfile'),
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'api', 'Dockerfile'),
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'realtime-gateway', 'Dockerfile'),
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'ai-chatbot', 'Dockerfile'),
  ].map((file) => fs.readFileSync(file, 'utf8'))

  assert.match(compose, /image: postgres:\d+\.\d+-alpine/)
  assert.doesNotMatch(compose, /image: postgres:\d+-alpine/)
  for (const dockerfile of dockerfiles) {
    assert.match(dockerfile, /^FROM node:\d+\.\d+\.\d+-alpine/m)
    assert.doesNotMatch(dockerfile, /^FROM node:\d+-alpine/m)
  }
})

test('stateless Node services run non-root with read-only filesystems', () => {
  const compose = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'docker-compose.yml'),
    'utf8',
  )
  const signalingDockerfile = fs.readFileSync(path.join(repoRoot, 'signaling-server', 'Dockerfile'), 'utf8')
  const realtimeDockerfile = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'realtime-gateway', 'Dockerfile'),
    'utf8',
  )
  const aiDockerfile = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'ai-chatbot', 'Dockerfile'),
    'utf8',
  )
  const signalingService = compose.match(/\n  signaling:[\s\S]*?\n  edge:/)?.[0] || ''
  const realtimeService = compose.match(/\n  realtime-gateway:[\s\S]*?\n  # TURN/)?.[0] || ''
  const aiService = compose.match(/\n  ai-chatbot:[\s\S]*?\n  signaling:/)?.[0] || ''

  assert.match(signalingDockerfile, /^USER node$/m)
  assert.match(realtimeDockerfile, /^USER node$/m)
  assert.match(aiDockerfile, /ENTRYPOINT \["\/app\/scripts\/start-as-node\.sh"\]/)
  for (const service of [signalingService, realtimeService, aiService]) {
    assert.match(service, /read_only: true/)
    assert.match(service, /cap_drop:\s*\n\s+- ALL/)
    assert.match(service, /no-new-privileges:true/)
    assert.match(service, /pids_limit:/)
  }
})

test('signaling Redis is private, authenticated and durable', () => {
  const compose = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'docker-compose.yml'),
    'utf8',
  )
  const redisService = compose.match(/\n  signaling-redis:[\s\S]*?\n  api:/)?.[0] || ''

  assert.match(redisService, /requirepass %s/)
  assert.match(redisService, /appendonly yes/)
  assert.match(redisService, /novvin-signaling-redis:\/data/)
  assert.doesNotMatch(redisService, /\bchown\b/)
  assert.match(redisService, /chmod 0640 \/run\/redis\/redis\.conf/)
  assert.match(redisService, /user: "999:1000"/)
  assert.doesNotMatch(redisService, /chmod 0770 \/data/)
  assert.doesNotMatch(redisService, /su-exec/)
  assert.match(redisService, /\/run\/redis:size=1m,mode=0750,uid=999,gid=1000/)
  assert.doesNotMatch(redisService, /\n\s+ports:/)
  assert.match(compose, /SIGNALING_REDIS_PASSWORD_FILE: \/run\/secrets\/signaling_redis_password/)
})

test('safe deploy blocks unsupported signaling replica counts by default', () => {
  const deployScript = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'scripts', 'deploy-stack-safely.sh'),
    'utf8',
  )
  const example = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'config.example'),
    'utf8',
  )

  assert.match(deployScript, /SIGNALING_MULTI_REPLICA_APPROVED/)
  assert.match(deployScript, /signaling multi-replica sigue bloqueado/)
  assert.match(deployScript, /ALLOW_EPHEMERAL_PUBLIC_ORIGIN_OVERRIDE/)
  assert.match(deployScript, /chmod 700 "\$\(dirname "\$redis_secret_path"\)"/)
  assert.match(deployScript, /chmod 644 "\$redis_secret_path"/)
  assert.match(deployScript, /docker compose up -d --force-recreate signaling-redis/)
  assert.match(deployScript, /verify:social-read-paths/)
  assert.match(deployScript, /verify:messaging-read-paths/)
  assert.match(deployScript, /verify:admin-panel-e2e/)
  assert.match(deployScript, /DEPLOY_REQUIRE_MESSAGING_CONTENT/)
  assert.match(example, /SIGNALING_REPLICAS=1/)
  assert.match(example, /SIGNALING_MULTI_REPLICA_APPROVED=false/)
})

test('full deploy commits release metadata only after validation and can restore it', () => {
  const deployScript = fs.readFileSync(
    path.join(repoRoot, 'scripts', 'deploy-mini-pc-current.ps1'),
    'utf8',
  )
  const validationIndex = deployScript.indexOf('~/audit-mini-pc-drift.sh | sed')
  const markerIndex = deployScript.lastIndexOf('marker="$HOME/conectate-local-fallback/.conectate-release"')
  const installStart = deployScript.indexOf('Write-Host "==> Instalando fuentes sin borrar .env/secrets/datos"')
  const installEnd = deployScript.indexOf('if (-not $SkipBuild)', installStart)
  const installBlock = deployScript.slice(installStart, installEnd)

  assert.ok(validationIndex >= 0, 'el despliegue debe validar el estado remoto antes del marcador')
  assert.ok(markerIndex > validationIndex, 'el marcador no debe escribirse antes de los health checks')
  assert.match(installBlock, /Invoke-Remote @'\r?\n/)
  assert.doesNotMatch(installBlock, /Invoke-Remote @"/)
  assert.match(installBlock, /marker="\$HOME\/conectate-local-fallback\/\.conectate-release"/)
  assert.match(deployScript, /\[switch\]\$AllowEphemeralPublicOrigin/)
  assert.match(deployScript, /ALLOW_EPHEMERAL_PUBLIC_ORIGIN_OVERRIDE=true/)
  assert.match(deployScript, /marker\.next/)
  assert.match(deployScript, /restore_marker\(\)/)
  assert.match(deployScript, /force-recreate api/)
  assert.match(deployScript, /commit esperado despues de actualizar release metadata/)
  assert.match(deployScript, /sourceDigest/)
  assert.match(deployScript, /EXPECTED_SOURCE_DIGEST/)
})

test('production verifier checks release identity and API metadata', () => {
  const verifier = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'scripts', 'verify-ubuntu-production.sh'),
    'utf8',
  )

  assert.match(verifier, /EXPECTED_COMMIT=/)
  assert.match(verifier, /EXPECTED_RELEASE=/)
  assert.match(verifier, /EXPECTED_SOURCE_DIGEST=/)
  assert.match(verifier, /release_marker=.*\.conectate-release/)
  assert.match(verifier, /marcador de release valido/)
  assert.match(verifier, /commit de \/ready coincide con el marcador de release/)
  assert.match(verifier, /huella de \/ready coincide con el marcador de release/)
})

test('targeted API deploy records an exact artifact digest', () => {
  const deployScript = fs.readFileSync(
    path.join(repoRoot, 'scripts', 'deploy-targeted-api-release.sh'),
    'utf8',
  )
  assert.match(deployScript, /source_digest=/)
  assert.match(deployScript, /sha256sum/)
  assert.match(deployScript, /release_commit=/)
  assert.match(deployScript, /release_marker\.next/)
  assert.match(deployScript, /\.conectate-release no puede ser un enlace simbolico/)
})

test('quick tunnel rotation preserves a healthy endpoint and reloads edge after container changes', () => {
  const watcher = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'scripts', 'conectate-cloudflare-tunnel.sh'),
    'utf8',
  )

  assert.match(watcher, /current_public_url\(\)/)
  assert.match(watcher, /if public_url="\$\(current_public_url\)"/)
  assert.match(watcher, /docker compose restart edge/)
  assert.match(watcher, /reconcile_stack_public_url/)
  assert.match(watcher, /el stack no se recupero.*revirtiendo/)
  assert.match(watcher, /--protocol http2 --edge-ip-version 4/)
  assert.match(watcher, /If the connector that owns the published hostname is/)
})

test('health watchdog holds the compose lock through edge DNS reconciliation', () => {
  const watchdog = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'scripts', 'health-watchdog.sh'),
    'utf8',
  )

  assert.match(watchdog, /flock --wait 240 9/)
  assert.match(watchdog, /docker compose up -d --remove-orphans/)
  assert.match(watchdog, /docker compose restart edge/)
  assert.match(watchdog, /9>"\$COMPOSE_LOCK"/)
})

test('public read-path monitor records degradation without restarting healthy services', () => {
  const monitor = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'scripts', 'monitor-public-read-paths.sh'),
    'utf8',
  )
  const installer = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'scripts', 'install-ubuntu-operations.sh'),
    'utf8',
  )
  const userInstaller = fs.readFileSync(
    path.join(repoRoot, 'infrastructure', 'local-fallback', 'scripts', 'install-public-read-path-monitor-user.sh'),
    'utf8',
  )

  assert.match(monitor, /verify:social-read-paths/)
  assert.match(monitor, /verify:messaging-read-paths/)
  assert.match(monitor, /PUBLIC_MONITOR_FAILURE_THRESHOLD/)
  assert.match(monitor, /status=degraded/)
  assert.match(monitor, /--network host/)
  assert.match(monitor, /SMOKE_ACCESS_TOKEN/)
  assert.match(monitor, /dst=\/usr\/src\/app\/scripts/)
  assert.match(monitor, /public-\$name\.latest\.log/)
  assert.doesNotMatch(monitor, /docker compose (up|restart|down)/)
  assert.match(installer, /conectate-public-read-paths\.timer/)
  assert.match(installer, /OnUnitActiveSec=10m/)
  assert.match(installer, /INSTALL_OPERATIONS_SKIP_INITIAL_VALIDATION/)
  assert.match(userInstaller, /systemctl --user enable --now conectate-public-read-paths\.timer/)
  assert.doesNotMatch(userInstaller, /sudo/)
})

test('all maintained realtime clients send a bounded scope affinity key', () => {
  const androidFiles = [
    'VoiceRoomRepository.kt',
    'CallRepository.kt',
    'LiveRepository.kt',
  ].map((name) => fs.readFileSync(
    path.join(repoRoot, 'app', 'src', 'main', 'java', 'conectate', 'com', 'data', 'repository', name),
    'utf8',
  ))
  const webFiles = [
    path.join('features', 'voice', 'VoiceRoomPage.tsx'),
    path.join('features', 'calls', 'WebCallManager.ts'),
    path.join('features', 'live', 'LiveSessionPage.tsx'),
  ].map((relative) => fs.readFileSync(
    path.join(repoRoot, 'Conectate-web', 'src', relative),
    'utf8',
  ))

  for (const source of androidFiles) assert.match(source, /attachSignalingAffinity\(/)
  for (const source of webFiles) assert.match(source, /buildSignalingAffinity\(/)
})
