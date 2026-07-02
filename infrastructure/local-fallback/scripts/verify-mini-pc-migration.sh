#!/usr/bin/env bash
set -Eeuo pipefail

umask 077
BUNDLE_PATH="${1:-}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

[ -n "$BUNDLE_PATH" ] || { echo "Uso: $0 paquete.tar.gz.enc"; exit 2; }
BUNDLE_PATH="$(readlink -f "$BUNDLE_PATH")"
test -s "$BUNDLE_PATH"

if [ -n "${MIGRATION_PASSPHRASE:-}" ]; then
  passphrase="$MIGRATION_PASSPHRASE"
elif [ -t 0 ]; then
  read -r -s -p "Contraseña del paquete: " passphrase
  echo >&2
else
  echo "ERROR: define MIGRATION_PASSPHRASE." >&2
  exit 2
fi

MIGRATION_BUNDLE_PASSPHRASE="$passphrase" \
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 250000 \
    -pass env:MIGRATION_BUNDLE_PASSPHRASE \
    -in "$BUNDLE_PATH" -out "$WORK_DIR/bundle.tar.gz"
unset passphrase MIGRATION_BUNDLE_PASSPHRASE

if tar -tzf "$WORK_DIR/bundle.tar.gz" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "ERROR: rutas inseguras dentro del paquete."
  exit 2
fi
tar -xzf "$WORK_DIR/bundle.tar.gz" -C "$WORK_DIR"
PAYLOAD_DIR="$(find "$WORK_DIR" -mindepth 1 -maxdepth 1 -type d -name 'conectate-mini-pc-*' | head -n 1)"
test -d "$PAYLOAD_DIR"
(
  cd "$PAYLOAD_DIR"
  sha256sum --check SHA256SUMS
  test -s runtime.env
  test -s secrets/firebase-service-account.json
  test -s postgres.dump
  test -s storage.tar.gz
  test -s local-fallback-source.tar.gz
  test -s signaling-source.tar.gz
  cat metadata.json
)

echo "MIGRATION_BUNDLE_VERIFIED=$BUNDLE_PATH"
