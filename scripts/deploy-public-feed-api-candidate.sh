#!/usr/bin/env bash
# Fixed, reviewed upgrade from 2011e32 with surveys enabled to 4f1e40e.
# Only API is recreated; existing DB, scanner and signaling are preserved.
set -Eeuo pipefail
umask 077

mode="${1:?prepare, deploy or rollback}"
root="${2:?existing stack directory}"
release="${3:?prepared release directory}"
backup="${4:?verified backup directory}"
revision=4f1e40eb4c11b7a32c55da4c083494e6bb97da5d
previous_revision=2011e322af879b42867cee1f43ac8a26bcb24b7b
image=sha256:166eb29d92076e42583b12479e66335080d02d4cc08273f754bf9ad3b3f87130
previous_image=sha256:28d4f0e6c06bb6b3794b9df71da50794b0f6463840e9e79f17e32024aa7eb6a2
scanner_image=sha256:8cecbe651670849f42784cf24fa1b943be63da23cf41b52d16478dc5f4cbec54
validator="$(dirname "$(realpath -e "$0")")/validate-survey-release-archive.py"
case "$mode" in prepare|deploy|rollback) ;; *) exit 2 ;; esac
[[ "$root" == /home/alvaro/conectate-local-fallback ]]
[[ "$release" == /home/alvaro/conectate-releases/public-feed-api-4f1e40e-* ]]
[[ "$backup" == /home/alvaro/conectate-backups/20*T*Z ]]
for directory in "$root" "$release" "$backup"; do
  [[ "$(realpath -e "$directory")" == "$directory" ]]
done
[[ -f "$validator" && -f "$root/.conectate-release" && ! -L "$root/.conectate-release" ]]
[[ -f "$root/docker-compose.override.yml" && ! -L "$root/docker-compose.override.yml" ]]
test -s "$release/candidate-files.tar"
test -s "$backup/SHA256SUMS"
mkdir -p /home/alvaro/.cache
exec 9>/home/alvaro/.cache/conectate-compose.lock
flock --wait 120 9
cd "$root"

override_for() {
  local api_image="${1:?pinned API image}"
  [[ "$api_image" == "$image" || "$api_image" == "$previous_image" ]] || return 1
  cat <<EOF
services:
  api:
    image: "$api_image"
    environment:
      NOVVIN_SURVEYS_ENABLED: "true"
      SURVEY_CLAMD_HOST: survey-antivirus
      SURVEY_CLAMD_PORT: "3310"
  survey-antivirus:
    image: "$scanner_image"
    restart: unless-stopped
    mem_limit: 2g
    memswap_limit: 2g
    cpus: "1.0"
    pids_limit: 64
    read_only: true
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    tmpfs:
      - /tmp:rw,nosuid,nodev,noexec,size=64m,uid=10001,gid=10001,mode=0700
    volumes:
      - novvin-clamav-db:/var/lib/clamav:ro
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
EOF
}

approved_override() {
  [[ -f "$root/docker-compose.override.yml" && ! -L "$root/docker-compose.override.yml" ]] || return 1
  cmp -s <(override_for "$previous_image") "$root/docker-compose.override.yml" ||
    cmp -s <(override_for "$image") "$root/docker-compose.override.yml"
}
check_image() {
  [[ "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" == "$revision" ]]
}
check_candidate() {
  python3 "$validator" candidate "$root" "$release/candidate-files.tar"
  python3 - "$release/candidate-files.tar" <<'PY'
import sys,tarfile
with tarfile.open(sys.argv[1]) as archive:
    assert all(m.name == 'api' or m.name.startswith('api/') for m in archive.getmembers()), 'Upgrade scope must be API only'
PY
}
wait_ready() {
  local deadline=$((SECONDS + 300))
  while ((SECONDS < deadline)); do
    if docker exec conectate-local-fallback-api-main node -e \
      "fetch('http://127.0.0.1:3001/ready',{signal:AbortSignal.timeout(3000)}).then(r=>{if(!r.ok)process.exit(1);return r.json()}).then(d=>process.exit(d.ok?0:1)).catch(()=>process.exit(1))"; then return 0; fi
    sleep 3 || return 1
  done
  return 1
}
wait_edge_ready() {
  local deadline=$((SECONDS + 30))
  while ((SECONDS < deadline)); do
    if curl -fsS --max-time 5 http://127.0.0.1:3080/ready |
       python3 -c 'import json,sys;sys.exit(0 if json.load(sys.stdin).get("ok") is True else 1)'; then return 0; fi
    sleep 1 || return 1
  done
  return 1
}

if [[ "$mode" == prepare ]]; then
  test ! -e "$release/PREPARED"
  test ! -e "$release/predeploy-files.tar"
  cmp -s <(override_for "$previous_image") "$root/docker-compose.override.yml"
  [[ "$(docker inspect --format '{{.Image}}' conectate-local-fallback-api-main)" == "$previous_image" ]]
  grep -Fxq "commit=$previous_revision" "$root/.conectate-release"
  check_candidate
  check_image
  bash "$root/scripts/verify-backup.sh" "$backup"
  tar -cf "$release/predeploy-files.tar" api/Dockerfile api/package.json api/package-lock.json api/src api/scripts docker-compose.yml docker-compose.override.yml .conectate-release
  python3 "$validator" snapshot "$root" "$release/predeploy-files.tar"
  sha256sum "$release/predeploy-files.tar" >"$release/predeploy-files.sha256"
  sha256sum "$release/candidate-files.tar" >"$release/candidate-files.sha256"
  override_for "$image" >"$release/candidate.override.yml"
  docker compose -f docker-compose.yml -f "$release/candidate.override.yml" config --quiet
  chmod 600 "$release/predeploy-files.tar" "$release/predeploy-files.sha256" "$release/candidate-files.tar" "$release/candidate-files.sha256" "$release/candidate.override.yml"
  touch "$release/PREPARED"
  echo API_UPGRADE_PREPARED_SURVEYS_REMAIN_ENABLED
  exit 0
fi

test -f "$release/PREPARED"
sha256sum --check "$release/predeploy-files.sha256"
sha256sum --check "$release/candidate-files.sha256"
check_candidate
python3 "$validator" snapshot "$root" "$release/predeploy-files.tar"
docker image inspect "$previous_image" >/dev/null

rollback() {
  # Check every ownership boundary before restoring any source or settings.
  approved_override || return 1
  local current_id current_image
  current_id="$(docker ps -aq --filter 'name=^/conectate-local-fallback-api-main$')" || return 1
  if [[ -n "$current_id" ]]; then
    current_image="$(docker inspect --format '{{.Image}}' "$current_id")" || return 1
    [[ "$current_image" == "$image" || "$current_image" == "$previous_image" ]] || return 1
  fi
  python3 "$validator" remove-added "$root" "$release/predeploy-files.tar" "$release/candidate-files.tar" || return 1
  tar -xf "$release/predeploy-files.tar" -C "$root" || return 1
  # Restores the complete original override, including surveys=true and scanner.
  cmp -s <(override_for "$previous_image") "$root/docker-compose.override.yml" || return 1
  docker compose config --quiet || return 1
  docker compose up -d --no-deps --no-build --force-recreate api || return 1
  wait_ready || return 1
  wait_edge_ready || return 1
  echo API_UPGRADE_ROLLED_BACK_WITH_SURVEYS_ENABLED
}
if [[ "$mode" == rollback ]]; then rollback; exit 0; fi
failure() {
  trap - ERR
  if ! rollback; then echo 'ROLLBACK_FAILED: preserve release artifacts and inspect API' >&2; fi
  exit 1
}

test ! -e "$release/DEPLOYED"
cmp -s <(override_for "$previous_image") "$root/docker-compose.override.yml"
[[ "$(docker inspect --format '{{.Image}}' conectate-local-fallback-api-main)" == "$previous_image" ]]
python3 "$validator" unchanged "$root" "$release/predeploy-files.tar"
check_image
bash "$root/scripts/verify-backup.sh" "$backup"
trap failure ERR
tar -xf "$release/candidate-files.tar" -C "$root"
override_tmp="$(mktemp "$root/.public-feed-override.tmp.XXXXXX")"
override_for "$image" >"$override_tmp"
chmod 644 "$override_tmp"
mv -T -- "$override_tmp" "$root/docker-compose.override.yml"
marker_tmp="$(mktemp "$root/.conectate-release.tmp.XXXXXX")"
cat >"$marker_tmp" <<EOF
release=$(basename "$release")
commit=$revision
deployed_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
source=deploy-public-feed-api-candidate.sh
EOF
chmod 644 "$marker_tmp"
mv -T -- "$marker_tmp" "$root/.conectate-release"
docker compose config --quiet
docker compose up -d --no-deps --no-build --force-recreate api
wait_ready
wait_edge_ready
touch "$release/DEPLOYED"
trap - ERR
echo API_UPGRADE_READY_SURVEYS_REMAIN_ENABLED
