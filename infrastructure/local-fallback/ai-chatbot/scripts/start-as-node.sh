#!/bin/sh
set -eu

mkdir -p /data/ai

# Existing production data is already owned by the non-root Node identity and the
# directory is intentionally mode 0700. Container root has CHOWN but not the broad
# DAC_OVERRIDE capability, so a needless recursive traversal would be rejected.
# Only repair ownership when the mount itself proves that repair is required.
expected_owner="$(id -u node):$(id -g node)"
current_owner="$(stat -c '%u:%g' /data/ai)"
if [ "$current_owner" != "$expected_owner" ]; then
  chown -R node:node /data/ai
fi

exec su-exec node "$@"
