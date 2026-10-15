#!/bin/sh
set -eu
# Signatures are populated by the isolated updater, not downloaded by the daemon.
# Missing or invalid databases make clamd exit; stale loaded signatures fail health.
exec clamd --foreground --config-file=/policy/clamd-storage.conf
