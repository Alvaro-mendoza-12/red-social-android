#!/usr/bin/env bash
# Scope: API and its private PDF scanner. Does not recreate DB, Redis or signaling.
set -Eeuo pipefail
umask 077

mode="${1:?prepare, deploy or rollback}"
root="${2:?existing absolute stack directory}"
release="${3:?absolute prepared release directory}"
backup="${4:?absolute verified database/storage backup directory}"
revision="2011e322af879b42867cee1f43ac8a26bcb24b7b"
api_image="sha256:28d4f0e6c06bb6b3794b9df71da50794b0f6463840e9e79f17e32024aa7eb6a2"
scanner_image="sha256:8cecbe651670849f42784cf24fa1b943be63da23cf41b52d16478dc5f4cbec54"
validator="$(dirname "$(realpath -e "$0")")/validate-survey-release-archive.py"
test -f "$validator"

case "$mode" in prepare|deploy|rollback) ;; *) exit 2 ;; esac
[[ "$root" == /home/alvaro/conectate-local-fallback ]]
[[ "$release" == /home/alvaro/conectate-releases/surveys-api-2011e32-* ]]
[[ "$backup" == /home/alvaro/conectate-backups/20*T*Z ]]
[[ "$(realpath -e "$root")" == "$root" ]]
[[ "$(realpath -e "$release")" == "$release" ]]
[[ "$(realpath -e "$backup")" == "$backup" ]]
[[ ! -L "$root/docker-compose.override.yml" ]]
[[ ! -L "$root/.conectate-release" && -f "$root/.conectate-release" ]]
test -s "$release/candidate-files.tar"
test -s "$backup/SHA256SUMS"

mkdir -p /home/alvaro/.cache
exec 9>/home/alvaro/.cache/conectate-compose.lock
flock --wait 120 9
cd "$root"

verify_image() {
  [[ "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$1")" == "$revision" ]]
}
wait_ready() {
  local deadline=$((SECONDS + 300))
  while ((SECONDS < deadline)); do
    if docker exec conectate-local-fallback-api-main node -e \
      "fetch('http://127.0.0.1:3001/ready',{signal:AbortSignal.timeout(3000)}).then(r=>{if(!r.ok)process.exit(1);return r.json()}).then(d=>process.exit(d.ok?0:1)).catch(()=>process.exit(1))"; then
      return 0
    fi
    sleep 3 || return 1
  done
  return 1
}

if [[ "$mode" == prepare ]]; then
  # Refuse to replace an unknown override or overwrite rollback material.
  test ! -e "$root/docker-compose.override.yml"
  test ! -e "$root/survey-antivirus"
  scanner_existing="$(docker ps -aq --filter label=com.docker.compose.project=conectate-local-fallback --filter label=com.docker.compose.service=survey-antivirus)"
  [[ -z "$scanner_existing" ]]
  test ! -e "$release/predeploy-files.tar"
  chmod 700 "$release"
  python3 "$validator" candidate "$root" "$release/candidate-files.tar"
  bash "$root/scripts/verify-backup.sh" "$backup"
  verify_image "$api_image"
  verify_image "$scanner_image"
  docker inspect --format '{{.Image}}' conectate-local-fallback-api-main >"$release/previous-api-image"
  tar -cf "$release/predeploy-files.tar" api/Dockerfile api/package.json api/package-lock.json api/src api/scripts docker-compose.yml .conectate-release
  python3 "$validator" snapshot "$root" "$release/predeploy-files.tar"
  sha256sum "$release/predeploy-files.tar" >"$release/predeploy-files.sha256"
  sha256sum "$release/candidate-files.tar" >"$release/candidate-files.sha256"
  cat >"$release/candidate.override.yml" <<EOF
services:
  api:
    image: "$api_image"
    environment:
      NOVVIN_SURVEYS_ENABLED: "false"
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
  docker compose -f docker-compose.yml -f "$release/candidate.override.yml" config --quiet
  chmod 600 "$release/previous-api-image" "$release/predeploy-files.tar" "$release/predeploy-files.sha256" "$release/candidate-files.tar" "$release/candidate-files.sha256" "$release/candidate.override.yml"
  touch "$release/PREPARED"
  echo "CANDIDATE_PREPARED_WITHOUT_RESTART"
  exit 0
fi

test -f "$release/PREPARED"
sha256sum --check "$release/predeploy-files.sha256"
sha256sum --check "$release/candidate-files.sha256"
previous_image="$(cat "$release/previous-api-image")"
[[ "$previous_image" =~ ^sha256:[0-9a-f]{64}$ ]]
docker image inspect "$previous_image" >/dev/null
python3 "$validator" candidate "$root" "$release/candidate-files.tar"
python3 "$validator" snapshot "$root" "$release/predeploy-files.tar"

rollback() {
  # Preserve additive SQL tables and writes made after the backup.
  # Restore files and the original marker, then pin the exact former image.
  # Explicit guards are needed: callers may invoke this function conditionally,
  # which disables Bash errexit inside the whole function.
  test ! -L "$root/docker-compose.override.yml" || return 1
  if [[ -e "$root/docker-compose.override.yml" ]]; then
    cmp "$release/candidate.override.yml" "$root/docker-compose.override.yml" || return 1
  fi
  local current_image current_id
  current_id="$(docker ps -aq --filter 'name=^/conectate-local-fallback-api-main$')" || return 1
  # A failed force-recreate can remove the old container before creating its
  # replacement. Absence is recoverable; an unknown replacement image is not.
  if [[ -n "$current_id" ]]; then
    current_image="$(docker inspect --format '{{.Image}}' "$current_id")" || return 1
    [[ "$current_image" == "$api_image" || "$current_image" == "$previous_image" ]] || return 1
  fi
  python3 "$validator" remove-added "$root" "$release/predeploy-files.tar" "$release/candidate-files.tar" || return 1
  tar -xf "$release/predeploy-files.tar" -C "$root" || return 1
  cat >"$release/rollback.override.yml" <<EOF || return 1
services:
  api:
    image: "$previous_image"
EOF
  test -s "$release/rollback.override.yml" || return 1
  docker compose -f docker-compose.yml -f "$release/candidate.override.yml" stop survey-antivirus || return 1
  if [[ -f "$root/docker-compose.override.yml" ]]; then
    rm -- "$root/docker-compose.override.yml" || return 1
  fi
  docker compose -f docker-compose.yml -f "$release/rollback.override.yml" config --quiet || return 1
  docker compose -f docker-compose.yml -f "$release/rollback.override.yml" up -d --no-deps --no-build --force-recreate api || return 1
  wait_ready || return 1
  echo "API_ROLLBACK_READY_WITHOUT_DATABASE_RESTORE"
}
if [[ "$mode" == rollback ]]; then rollback; exit 0; fi

test ! -e "$release/DEPLOYED"
test ! -e "$root/docker-compose.override.yml"
verify_image "$api_image"
verify_image "$scanner_image"
[[ "$(docker inspect --format '{{.Image}}' conectate-local-fallback-api-main)" == "$previous_image" ]]
python3 "$validator" unchanged "$root" "$release/predeploy-files.tar"
bash "$root/scripts/verify-backup.sh" "$backup"
failure() {
  trap - ERR
  if ! rollback; then
    echo "ROLLBACK_FAILED: preserve release artifacts and inspect API immediately" >&2
  fi
  exit 1
}
trap failure ERR
tar -xf "$release/candidate-files.tar" -C "$root"
install -m 644 "$release/candidate.override.yml" "$root/docker-compose.override.yml"
# Keep the old inode visible to the old container until its planned recreation.
# The replacement container mounts the complete new marker atomically.
marker_tmp="$(mktemp "$root/.conectate-release.tmp.XXXXXX")"
cat >"$marker_tmp" <<EOF
release=$(basename "$release")
commit=$revision
deployed_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
source=deploy-surveys-api-candidate.sh
EOF
chmod 644 "$marker_tmp"
mv -T -- "$marker_tmp" "$root/.conectate-release"
docker compose config --quiet
docker compose up -d --no-deps --no-build survey-antivirus
scanner_id="$(docker compose ps -q survey-antivirus)"
deadline=$((SECONDS + 120))
while [[ "$(docker inspect --format '{{.State.Health.Status}}' "$scanner_id")" != healthy ]]; do
  ((SECONDS < deadline))
  sleep 3
done
docker compose up -d --no-deps --no-build --force-recreate api
wait_ready
touch "$release/DEPLOYED"
trap - ERR
echo "API_AND_SCANNER_READY_SURVEYS_DISABLED"
