#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
EXAMPLE_FILE="${EXAMPLE_FILE:-$ROOT_DIR/config.example}"

if [ ! -f "$EXAMPLE_FILE" ]; then
  echo "ERROR: no existe $EXAMPLE_FILE" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  cp "$EXAMPLE_FILE" "$ENV_FILE"
  echo "OK creado $ENV_FILE desde config.example"
  exit 0
fi

added=0
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|\#*)
      continue
      ;;
    *=*)
      key="${line%%=*}"
      if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        continue
      fi
      if ! grep -qE "^${key}=" "$ENV_FILE"; then
        if [ "$added" -eq 0 ]; then
          {
            echo
            echo "# Variables agregadas automaticamente desde config.example"
          } >> "$ENV_FILE"
        fi
        printf '%s\n' "$line" >> "$ENV_FILE"
        added=$((added + 1))
        echo "ADD $key"
      fi
      ;;
  esac
done < "$EXAMPLE_FILE"

if [ "$added" -eq 0 ]; then
  echo "OK $ENV_FILE ya tiene todas las claves de config.example"
else
  echo "OK agregadas $added claves faltantes a $ENV_FILE"
fi
