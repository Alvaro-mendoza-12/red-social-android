#!/bin/sh
set -eu

# A slow signature mirror must not hold every API module in startup indefinitely.
# The scanners still require usable signatures and reject uploads without them.
budget="${API_ANTIVIRUS_REFRESH_TIMEOUT_SECONDS:-20}"
case "$budget" in ''|*[!0-9]*) budget=20 ;; esac
if [ "$budget" -lt 1 ] || [ "$budget" -gt 60 ]; then budget=20; fi

if [ "$(id -u)" = "0" ]; then
  # With CAP_KILL dropped, a root watchdog cannot terminate freshclam after it
  # drops to clamav. Run both under the same UID instead of adding a capability.
  exec su-exec clamav:clamav timeout -k 5 "$budget" freshclam --stdout
fi
exec timeout -k 5 "$budget" freshclam --stdout
