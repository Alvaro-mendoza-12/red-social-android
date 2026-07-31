#!/usr/bin/env bash
set -euo pipefail

# Read-only production check. Pass an existing admin JWT through the
# environment; never commit or persist the token in this repository.
API_URL="${API_URL:-${PUBLIC_BASE_URL:-http://127.0.0.1:3080}}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"

if [[ -z "$ADMIN_TOKEN" ]]; then
  echo "Falta ADMIN_TOKEN. Usa una sesión administrativa existente; no se crea ninguna cuenta." >&2
  exit 2
fi

API_URL="${API_URL%/}"
response="$(curl --fail-with-body --silent --show-error \
  --connect-timeout 10 --max-time 30 \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H 'ngrok-skip-browser-warning: 1' \
  "${API_URL}/v1/data/admin/stats")"

if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "$response" | jq '{total_users: (.stats.total_users // 0), total_videos: (.stats.total_videos // 0), suspended_users: (.stats.suspended_users // 0), pending_user_reports: (.stats.pending_user_reports // 0), pending_video_reports: (.stats.pending_video_reports // 0)}'
else
  printf '%s\n' "$response"
fi
