#!/bin/sh
set -eu
# Read-only signatures are supplied by the existing updater and shared volume.
exec clamd --foreground --config-file=/etc/clamav/novvin-surveys.conf
