#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLIC_URL_FILE="${PUBLIC_URL_FILE:-$HOME/conectate-public-url.txt}"
STATE_DIR="${PUBLIC_MONITOR_STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/conectate}"
STATE_FILE="$STATE_DIR/public-read-paths.status"
FAILURE_FILE="$STATE_DIR/public-read-paths.failures"
LOCK_FILE="${PUBLIC_MONITOR_LOCK:-$HOME/.cache/conectate-public-read-paths.lock}"
PROBE_TIMEOUT_SECONDS="${PUBLIC_MONITOR_PROBE_TIMEOUT_SECONDS:-90}"
FAILURE_THRESHOLD="${PUBLIC_MONITOR_FAILURE_THRESHOLD:-3}"

mkdir -p "$STATE_DIR" "$(dirname "$LOCK_FILE")"
exec 9>"$LOCK_FILE"
if ! flock --nonblock 9; then
  echo "PUBLIC_READ_PATHS_SKIPPED=already_running"
  exit 0
fi

cd "$ROOT_DIR"
# Monitor the same canonical origin the deployed API advertises to the app.
# A legacy quick-tunnel file can rotate independently of the mobile endpoint.
public_url="${PUBLIC_MONITOR_BASE_URL:-}"
if [ -z "$public_url" ]; then
  public_url="$(docker compose config --format json 2>/dev/null | python3 -c \
    'import json,sys; print(json.load(sys.stdin).get("services",{}).get("api",{}).get("environment",{}).get("PUBLIC_BASE_URL", ""), end="")' \
    2>/dev/null)" || public_url=""
fi
if [ -z "$public_url" ]; then
  public_url="$(tr -d '\r\n' < "$PUBLIC_URL_FILE" 2>/dev/null || true)"
fi
if [[ ! "$public_url" =~ ^https://[a-zA-Z0-9.-]+$ ]]; then
  echo "PUBLIC_READ_PATHS_ERROR=invalid_or_missing_public_url"
  exit 1
fi

if ! [[ "$PROBE_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || [ "$PROBE_TIMEOUT_SECONDS" -lt 20 ] || [ "$PROBE_TIMEOUT_SECONDS" -gt 300 ]; then
  echo "PUBLIC_READ_PATHS_ERROR=invalid_probe_timeout"
  exit 1
fi
if ! [[ "$FAILURE_THRESHOLD" =~ ^[0-9]+$ ]] || [ "$FAILURE_THRESHOLD" -lt 1 ] || [ "$FAILURE_THRESHOLD" -gt 12 ]; then
  echo "PUBLIC_READ_PATHS_ERROR=invalid_failure_threshold"
  exit 1
fi

cd "$ROOT_DIR"
probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
failed=()

# Public ingress must be checked even when the database cannot issue a token.
access_token=""
api_image="$(docker compose images -q api | head -n 1)"
if [ -z "$api_image" ]; then
  echo "PUBLIC_READ_PATHS_ERROR=api_image_unavailable"
  exit 1
fi

# The monitor itself owns descriptor9. A timeout watchdog may outlive its
# command on BusyBox; inheriting9 would keep the advisory lock after this
# script exits and suppress subsequent checks. Close9 in bounded children.
run_probe() {
  local name="$1" script="$2" output evidence_tmp started_ms elapsed_ms
  output="$probe_dir/$name.log"
  started_ms="$(date +%s%3N)"
  if timeout "$PROBE_TIMEOUT_SECONDS" \
    docker run --rm --pull never --network host --read-only \
      --cap-drop ALL --security-opt no-new-privileges \
      --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m \
      --mount type=bind,src="$ROOT_DIR/api/scripts",dst=/usr/src/app/scripts,readonly \
      --entrypoint node \
      -e SMOKE_API_BASE_URL="$public_url" \
      -e SMOKE_ACCESS_TOKEN="$access_token" \
      -e SMOKE_REQUIRE_CONTENT=true \
      "$api_image" "scripts/$script" 9>&- >"$output" 2>&1; then
    elapsed_ms=$(( $(date +%s%3N) - started_ms ))
    evidence_tmp="$STATE_DIR/public-$name.latest.log.tmp.$$"
    tail -n 160 "$output" > "$evidence_tmp"
    chmod 0600 "$evidence_tmp"
    mv -f "$evidence_tmp" "$STATE_DIR/public-$name.latest.log"
    echo "PUBLIC_READ_PATHS_PROBE=$name status=ok elapsed_ms=$elapsed_ms"
  else
    elapsed_ms=$(( $(date +%s%3N) - started_ms ))
    failed+=("$name")
    evidence_tmp="$STATE_DIR/public-$name.latest.log.tmp.$$"
    tail -n 160 "$output" > "$evidence_tmp" || true
    chmod 0600 "$evidence_tmp" 2>/dev/null || true
    mv -f "$evidence_tmp" "$STATE_DIR/public-$name.latest.log" 2>/dev/null || true
    echo "PUBLIC_READ_PATHS_PROBE=$name status=failed elapsed_ms=$elapsed_ms"
    tail -n 20 "$output" >&2 || true
    return 1
  fi
}

# This is independent of the host's MagicDNS/private Tailscale route.
if run_probe public-ingress verify-public-ingress.js; then
  # Bounded separately: a stalled API/DB must still leave a degraded state.
  access_token="$(timeout "$PROBE_TIMEOUT_SECONDS" docker compose exec -T api node -e \
    'require("./scripts/verify-social-read-paths").issueDiagnosticToken().then((token) => process.stdout.write(token))' \
    9>&- 2>"$probe_dir/token.log")" || access_token=""
  if [[ "$access_token" =~ ^[^.]+\.[^.]+\.[^.]+$ ]]; then
    run_probe social verify-social-read-paths.js || true
    run_probe messaging verify-messaging-read-paths.js || true
  else
    failed+=("diagnostic-token")
    echo "PUBLIC_READ_PATHS_PROBE=diagnostic-token status=failed"
  fi
else
  echo "PUBLIC_READ_PATHS_SKIPPED=application_reads_public_ingress_failed"
fi

timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [ "${#failed[@]}" -eq 0 ]; then
  printf '0\n' > "$FAILURE_FILE"
  state_tmp="$STATE_FILE.tmp.$$"
  printf 'status=ok\nchecked_at_utc=%s\nconsecutive_failures=0\n' "$timestamp" > "$state_tmp"
  mv -f "$state_tmp" "$STATE_FILE"
  echo "PUBLIC_READ_PATHS_OK=$timestamp"
  exit 0
fi

previous="$(cat "$FAILURE_FILE" 2>/dev/null || echo 0)"
[[ "$previous" =~ ^[0-9]+$ ]] || previous=0
consecutive=$((previous + 1))
printf '%s\n' "$consecutive" > "$FAILURE_FILE"
state_tmp="$STATE_FILE.tmp.$$"
printf 'status=degraded\nchecked_at_utc=%s\nconsecutive_failures=%s\nfailed_probes=%s\n' \
  "$timestamp" "$consecutive" "$(IFS=,; echo "${failed[*]}")" > "$state_tmp"
mv -f "$state_tmp" "$STATE_FILE"
echo "PUBLIC_READ_PATHS_DEGRADED=$timestamp consecutive_failures=$consecutive probes=$(IFS=,; echo "${failed[*]}")"

if [ "$consecutive" -ge "$FAILURE_THRESHOLD" ]; then
  exit 1
fi
