#!/bin/sh
set -eu

runtime_user="${API_RUNTIME_USER:-novvin}"
runtime_group="${API_RUNTIME_GROUP:-novvin}"
storage_root="${STORAGE_ROOT:-/data/storage}"
image_variant_root="$storage_root/_variants"
runtime_owner_marker="$storage_root/.novvin-runtime-owner-v2"

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
  freshclam --stdout || echo "ClamAV signatures could not be refreshed; music uploads remain blocked."
fi

if [ "$(id -u)" = "0" ]; then
  expected_owner="$(id -u "$runtime_user"):$(id -g "$runtime_group")"

  # Hardened named volumes reject chmod from container root when the directory
  # already belongs to the runtime user. Create only missing paths and repair
  # ownership only when inspection proves it is necessary.
  if [ ! -d "$storage_root" ]; then
    mkdir -p "$storage_root"
  fi
  if [ ! -d "$storage_root/.tmp" ]; then
    mkdir -p "$storage_root/.tmp"
  fi
  if [ ! -d "$image_variant_root" ]; then
    mkdir -p "$image_variant_root"
  fi

  storage_owner="$(stat -c '%u:%g' "$storage_root")"
  temp_owner="$(stat -c '%u:%g' "$storage_root/.tmp")"
  top_level_owner_mismatch="$(
    find "$storage_root" -mindepth 1 -maxdepth 1 \
      \( ! -user "$runtime_user" -o ! -group "$runtime_group" \) \
      -print -quit
  )"
  if [ ! -f "$runtime_owner_marker" ] || \
     [ "$storage_owner" != "$expected_owner" ] || \
     [ "$temp_owner" != "$expected_owner" ] || \
     [ -n "$top_level_owner_mismatch" ]; then
    echo "Repairing NOVVIN storage ownership for the unprivileged API runtime."
    chown -R "$runtime_user:$runtime_group" "$storage_root"
  fi

  # Derived image previews may have been created by a legacy root-running API
  # or restored from a root-owned archive while the volume root itself already
  # has the correct owner. Inspect this rebuildable subtree independently so a
  # stale ownership mismatch cannot turn every optimized image request into 500.
  if [ -n "$(find "$image_variant_root" \( ! -user "$runtime_user" -o ! -group "$runtime_group" \) -print -quit)" ]; then
    chown -R "$runtime_user:$runtime_group" "$image_variant_root"
  fi
  touch "$runtime_owner_marker"
  chown "$runtime_user:$runtime_group" "$runtime_owner_marker"

  storage_owner="$(stat -c '%u:%g' "$storage_root")"
  temp_owner="$(stat -c '%u:%g' "$storage_root/.tmp")"
  top_level_owner_mismatch="$(
    find "$storage_root" -mindepth 1 -maxdepth 1 \
      \( ! -user "$runtime_user" -o ! -group "$runtime_group" \) \
      -print -quit
  )"
  if [ "$storage_owner" != "$expected_owner" ] || \
     [ "$temp_owner" != "$expected_owner" ] || \
     [ -n "$top_level_owner_mismatch" ]; then
    echo "API_STORAGE_OWNERSHIP_REPAIR_FAILED" >&2
    exit 1
  fi

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
