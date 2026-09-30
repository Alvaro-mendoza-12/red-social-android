#!/usr/bin/env sh
set -eu

# Keep existing Funnel handlers; route profile pages and crawler images to the API.
EDGE_URL="${NOVVIN_EDGE_URL:-http://127.0.0.1:3080}"
tailscale funnel --bg --yes --https=443 --set-path=/p "${EDGE_URL%/}/p"
tailscale funnel status
