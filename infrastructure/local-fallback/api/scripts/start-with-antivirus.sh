#!/bin/sh
set -eu

runtime_user="${API_RUNTIME_USER:-novvin}"
runtime_group="${API_RUNTIME_GROUP:-novvin}"
storage_root="${STORAGE_ROOT:-/data/storage}"

stage_runtime_secret() {
  source_file="$1"
  target_file="$2"
  if [ -z "$source_file" ] || [ -z "$target_file" ] || [ ! -s "$source_file" ]; then
    return 0
  fi
  target_dir="$(dirname "$target_file")"
  mkdir -p "$target_dir"
  cp "$source_file" "$target_file"
  chmod 0400 "$target_file"
  chown "$runtime_user:$runtime_group" "$target_file"
}

# Refresh signatures before accepting community uploads. If the mirror is
# temporarily unavailable, the API still starts, but upload validation fails
# closed while MUSIC_ANTIVIRUS_REQUIRED=true until valid signatures exist.
if [ "${MUSIC_ANTIVIRUS_REQUIRED:-true}" = "true" ]; then
  sh scripts/refresh-antivirus.sh || echo "ClamAV refresh did not complete; uploads still require valid antivirus signatures."
fi

if [ "$(id -u)" = "0" ]; then
  # Never recursively chown the storage root: root-owned audit controls and
  # preserved originals must retain their ownership and metadata on restart.
  node scripts/prepare-runtime-storage.js "$storage_root" \
    "$(id -u "$runtime_user")" "$(id -g "$runtime_group")"

  # Host credentials remain mode 0600. Stage read-only copies in the container's
  # private tmpfs before dropping privileges so Node never needs host-level access.
  stage_runtime_secret \
    "${FIREBASE_SERVICE_ACCOUNT_SOURCE_FILE:-/run/secrets/firebase-service-account.json}" \
    "${FIREBASE_SERVICE_ACCOUNT_FILE:-/tmp/novvin-secrets/firebase-service-account.json}"
  stage_runtime_secret \
    "${GOOGLE_PLAY_SERVICE_ACCOUNT_SOURCE_FILE:-/run/novvin-secrets/google-play-service-account.json}" \
    "${GOOGLE_PLAY_SERVICE_ACCOUNT_FILE:-/tmp/novvin-secrets/google-play-service-account.json}"
  stage_runtime_secret \
    "${RELEASE_METADATA_SOURCE_FILE:-/run/novvin-release}" \
    "${RELEASE_METADATA_FILE:-/tmp/novvin-release}"

  exec su-exec "$runtime_user:$runtime_group" node src/server.js
fi

exec node src/server.js
