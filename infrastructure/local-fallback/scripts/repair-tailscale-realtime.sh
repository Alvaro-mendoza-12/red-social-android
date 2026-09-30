#!/usr/bin/env bash
set -Eeuo pipefail

# Preserve existing web/API mounts. Route only realtime through the edge,
# which blocks /realtime/internal and authorizes WebSocket subscriptions.
EDGE_URL="${EDGE_URL:-http://127.0.0.1:3080}"
tailscale funnel --bg --yes --https=443 --set-path=/realtime "${EDGE_URL%/}/realtime"
tailscale funnel status
