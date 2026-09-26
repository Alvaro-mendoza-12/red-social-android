#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only differential health audit for the NOVVIN Ubuntu host. It samples
# counters instead of treating lifetime totals as current incidents.

SAMPLE_SECONDS="${SAMPLE_SECONDS:-10}"
OUT_DIR="${OUT_DIR:-$HOME/conectate-health-audits}"
FAIL_ON_CRITICAL=false
SELF_TEST=false

usage() {
  cat <<'EOF'
Usage: audit-mini-pc-health.sh [--sample-seconds N] [--out-dir DIR] [--fail-on-critical] [--self-test]

Optional environment:
  MEDIA_URL   Public immutable media URL used for a 1 MiB HTTP Range probe.

The audit is read-only. It does not restart services, change network settings,
attach storage volumes, repair filesystems or delete data.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sample-seconds)
      [[ $# -ge 2 ]] || { echo "Missing value for --sample-seconds" >&2; exit 2; }
      SAMPLE_SECONDS="$2"
      shift 2
      ;;
    --out-dir)
      [[ $# -ge 2 ]] || { echo "Missing value for --out-dir" >&2; exit 2; }
      OUT_DIR="$2"
      shift 2
      ;;
    --fail-on-critical)
      FAIL_ON_CRITICAL=true
      shift
      ;;
    --self-test)
      SELF_TEST=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if ! [[ "$SAMPLE_SECONDS" =~ ^[0-9]+$ ]] || (( SAMPLE_SECONDS < 1 || SAMPLE_SECONDS > 60 )); then
  echo "--sample-seconds must be an integer from 1 to 60" >&2
  exit 2
fi

severity_for_percent() {
  local value="$1" warning="$2" critical="$3"
  awk -v value="$value" -v warning="$warning" -v critical="$critical" 'BEGIN {
    if (value >= critical) print "CRITICAL";
    else if (value >= warning) print "WARN";
    else print "OK";
  }'
}

severity_rank() {
  case "$1" in
    CRITICAL) echo 2 ;;
    WARN) echo 1 ;;
    *) echo 0 ;;
  esac
}

read_link_metric() {
  local value
  if { IFS= read -r value < "$1"; } 2>/dev/null && [[ -n "$value" ]]; then
    printf '%s\n' "$value"
  else
    printf 'unknown\n'
  fi
}

load_per_processor() {
  awk -v load_value="$1" -v cpus="$2" 'BEGIN { printf "%.3f", load_value / cpus }'
}

if [[ "$SELF_TEST" == "true" ]]; then
  [[ "$(severity_for_percent 84.9 85 95)" == "OK" ]]
  [[ "$(severity_for_percent 85 85 95)" == "WARN" ]]
  [[ "$(severity_for_percent 95 85 95)" == "CRITICAL" ]]
  [[ "$(severity_for_percent 0.099 0.1 1)" == "OK" ]]
  [[ "$(severity_for_percent 0.1 0.1 1)" == "WARN" ]]
  [[ "$(severity_for_percent 1 0.1 1)" == "CRITICAL" ]]
  [[ "$(read_link_metric <(printf '1\n'))" == "1" ]]
  [[ "$(read_link_metric <(printf '100\n'))" == "100" ]]
  [[ "$(read_link_metric <(printf 'full\n'))" == "full" ]]
  [[ "$(read_link_metric /dev/null)" == "unknown" ]]
  [[ "$(load_per_processor 1 4)" == "0.250" ]]
  echo "SELF_TEST_OK"
  exit 0
fi

for command_name in awk date df findmnt hostname ip lsblk; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command is missing: $command_name" >&2
    exit 2
  }
done

IFACE="$(ip -o -4 route show to default | awk '{print $5; exit}')"
if [[ -z "$IFACE" || ! "$IFACE" =~ ^[[:alnum:]_.:-]+$ || ! -d "/sys/class/net/$IFACE" ]]; then
  echo "Could not resolve a safe default network interface" >&2
  exit 2
fi

declare -A NET_BEFORE NET_AFTER DISK_BEFORE_READ DISK_BEFORE_WRITE DISK_BEFORE_IO DISK_BEFORE_WEIGHTED
declare -A DISK_AFTER_READ DISK_AFTER_WRITE DISK_AFTER_IO DISK_AFTER_WEIGHTED IS_DISK

snapshot_network() {
  local target_name="$1" metric
  declare -n target="$target_name"
  for metric in rx_bytes tx_bytes rx_packets tx_packets rx_errors tx_errors rx_dropped tx_dropped; do
    target["$metric"]="$(<"/sys/class/net/$IFACE/statistics/$metric")"
  done
}

while read -r disk_name disk_type; do
  [[ "$disk_type" == "disk" ]] && IS_DISK["$disk_name"]=1
done < <(lsblk -dn -o NAME,TYPE)

snapshot_disks() {
  local phase="$1"
  local major minor name reads reads_merged sectors_read read_ms writes writes_merged sectors_written write_ms inflight io_ms weighted rest
  while read -r major minor name reads reads_merged sectors_read read_ms writes writes_merged sectors_written write_ms inflight io_ms weighted rest; do
    [[ -n "${IS_DISK[$name]:-}" ]] || continue
    if [[ "$phase" == "before" ]]; then
      DISK_BEFORE_READ["$name"]="$sectors_read"
      DISK_BEFORE_WRITE["$name"]="$sectors_written"
      DISK_BEFORE_IO["$name"]="$io_ms"
      DISK_BEFORE_WEIGHTED["$name"]="$weighted"
    else
      DISK_AFTER_READ["$name"]="$sectors_read"
      DISK_AFTER_WRITE["$name"]="$sectors_written"
      DISK_AFTER_IO["$name"]="$io_ms"
      DISK_AFTER_WEIGHTED["$name"]="$weighted"
    fi
  done < /proc/diskstats
}

snapshot_network NET_BEFORE
snapshot_disks before
sleep "$SAMPLE_SECONDS"
snapshot_network NET_AFTER
snapshot_disks after

mkdir -p "$OUT_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$OUT_DIR/novvin-host-health-$STAMP.txt"
HIGHEST_SEVERITY=0

record() {
  local severity="$1" category="$2" message="$3" rank
  rank="$(severity_rank "$severity")"
  (( rank > HIGHEST_SEVERITY )) && HIGHEST_SEVERITY="$rank"
  printf 'FINDING severity=%s category=%s message=%q\n' "$severity" "$category" "$message"
}

delta() {
  local metric="$1"
  echo $(( NET_AFTER[$metric] - NET_BEFORE[$metric] ))
}

ratio_percent() {
  local numerator="$1" denominator="$2"
  awk -v numerator="$numerator" -v denominator="$denominator" 'BEGIN {
    if (denominator <= 0) print "0.000000";
    else printf "%.6f", (numerator / denominator) * 100;
  }'
}

{
  echo "NOVVIN_HOST_HEALTH_AUDIT_V1"
  echo "READ_ONLY=true"
  echo "TIMESTAMP_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "HOST=$(hostname)"
  echo "SAMPLE_SECONDS=$SAMPLE_SECONDS"
  echo "INTERFACE=$IFACE"

  carrier="$(read_link_metric "/sys/class/net/$IFACE/carrier")"
  speed="$(read_link_metric "/sys/class/net/$IFACE/speed")"
  duplex="$(read_link_metric "/sys/class/net/$IFACE/duplex")"
  echo "LINK carrier=$carrier speed_mbps=$speed duplex=$duplex"
  if [[ "$carrier" == "0" ]]; then
    record CRITICAL network "La interfaz principal no tiene enlace activo"
  elif [[ "$carrier" != "1" ]]; then
    record WARN network "No se pudo leer el estado del enlace; comprobar permisos e interfaz"
  elif [[ "$speed" =~ ^[0-9]+$ ]] && (( speed < 1000 )); then
    record WARN network "Enlace negociado a ${speed} Mbps; limita streaming y uploads frente a gigabit"
  else
    record OK network "Enlace activo a ${speed} Mbps (${duplex})"
  fi

  rx_packets="$(delta rx_packets)"
  tx_packets="$(delta tx_packets)"
  rx_dropped="$(delta rx_dropped)"
  tx_dropped="$(delta tx_dropped)"
  rx_errors="$(delta rx_errors)"
  tx_errors="$(delta tx_errors)"
  rx_bytes="$(delta rx_bytes)"
  tx_bytes="$(delta tx_bytes)"
  rx_drop_percent="$(ratio_percent "$rx_dropped" "$rx_packets")"
  tx_drop_percent="$(ratio_percent "$tx_dropped" "$tx_packets")"
  rx_mbps="$(awk -v bytes="$rx_bytes" -v seconds="$SAMPLE_SECONDS" 'BEGIN { printf "%.3f", (bytes * 8) / seconds / 1000000 }')"
  tx_mbps="$(awk -v bytes="$tx_bytes" -v seconds="$SAMPLE_SECONDS" 'BEGIN { printf "%.3f", (bytes * 8) / seconds / 1000000 }')"
  echo "NETWORK_DELTA rx_packets=$rx_packets tx_packets=$tx_packets rx_dropped=$rx_dropped tx_dropped=$tx_dropped rx_errors=$rx_errors tx_errors=$tx_errors rx_drop_percent=$rx_drop_percent tx_drop_percent=$tx_drop_percent rx_mbps=$rx_mbps tx_mbps=$tx_mbps"
  drop_severity="$(severity_for_percent "$rx_drop_percent" 0.1 1)"
  record "$drop_severity" network "Descartes RX de interfaz: ${rx_drop_percent}% (${rx_dropped}/${rx_packets}); correlacionar con HTTP/TCP"
  drop_severity="$(severity_for_percent "$tx_drop_percent" 0.1 1)"
  record "$drop_severity" network "Descartes TX de interfaz: ${tx_drop_percent}% (${tx_dropped}/${tx_packets}); correlacionar con HTTP/TCP"
  if (( rx_errors > 0 || tx_errors > 0 )); then
    record WARN network "Errores de interfaz durante la muestra: RX=${rx_errors}, TX=${tx_errors}"
  else
    record OK network "Sin errores de interfaz durante la muestra"
  fi

  echo "FILESYSTEMS_BEGIN"
  while read -r source fstype blocks used available capacity mountpoint; do
    [[ "$capacity" == "Capacity" ]] && continue
    percent="${capacity%%%}"
    [[ "$percent" =~ ^[0-9]+$ ]] || continue
    echo "FILESYSTEM source=$source fstype=$fstype used_percent=$percent available_kib=$available mount=$mountpoint"
    record "$(severity_for_percent "$percent" 85 95)" disk_space "${mountpoint} usa ${percent}% (${available} KiB disponibles)"
  done < <(df -PT -x tmpfs -x devtmpfs)
  echo "FILESYSTEMS_END"

  echo "DISK_IO_BEGIN"
  for name in "${!IS_DISK[@]}"; do
    [[ -n "${DISK_AFTER_IO[$name]:-}" ]] || continue
    read_delta=$(( DISK_AFTER_READ[$name] - DISK_BEFORE_READ[$name] ))
    write_delta=$(( DISK_AFTER_WRITE[$name] - DISK_BEFORE_WRITE[$name] ))
    io_delta=$(( DISK_AFTER_IO[$name] - DISK_BEFORE_IO[$name] ))
    weighted_delta=$(( DISK_AFTER_WEIGHTED[$name] - DISK_BEFORE_WEIGHTED[$name] ))
    util_percent="$(awk -v ms="$io_delta" -v seconds="$SAMPLE_SECONDS" 'BEGIN { printf "%.3f", (ms / (seconds * 1000)) * 100 }')"
    queue_depth="$(awk -v ms="$weighted_delta" -v seconds="$SAMPLE_SECONDS" 'BEGIN { printf "%.3f", ms / (seconds * 1000) }')"
    read_mib="$(awk -v sectors="$read_delta" 'BEGIN { printf "%.3f", sectors * 512 / 1048576 }')"
    write_mib="$(awk -v sectors="$write_delta" 'BEGIN { printf "%.3f", sectors * 512 / 1048576 }')"
    echo "DISK_IO device=$name util_percent=$util_percent avg_queue=$queue_depth read_mib=$read_mib write_mib=$write_mib"
    record "$(severity_for_percent "$util_percent" 80 95)" disk_io "${name}: utilización ${util_percent}%, cola media ${queue_depth}"
  done
  echo "DISK_IO_END"

  mem_total="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)"
  mem_available="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
  mem_used_percent="$(awk -v total="$mem_total" -v available="$mem_available" 'BEGIN { printf "%.3f", (1 - available / total) * 100 }')"
  load_one="$(awk '{print $1}' /proc/loadavg)"
  cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)"
  load_per_cpu="$(load_per_processor "$load_one" "$cpu_count")"
  echo "HOST_RESOURCES memory_used_percent=$mem_used_percent load_1m=$load_one cpu_count=$cpu_count load_per_cpu=$load_per_cpu"
  record "$(severity_for_percent "$mem_used_percent" 85 95)" memory "Memoria usada ${mem_used_percent}%"
  record "$(severity_for_percent "$load_per_cpu" 1.5 2)" cpu "Carga 1m por CPU: ${load_per_cpu}"

  if command -v curl >/dev/null 2>&1; then
    ready_metrics="$(curl --silent --show-error --output /dev/null --max-time 10 --write-out '%{http_code} %{time_starttransfer} %{time_total}' http://127.0.0.1:3080/ready 2>/dev/null || echo '000 10 10')"
    read -r ready_code ready_ttfb ready_total <<<"$ready_metrics"
    echo "HTTP_READY code=$ready_code ttfb_seconds=$ready_ttfb total_seconds=$ready_total"
    if [[ "$ready_code" =~ ^2[0-9][0-9]$ ]]; then
      ready_severity="$(severity_for_percent "$ready_ttfb" 0.5 1.5)"
      record "$ready_severity" api "Ready local HTTP ${ready_code}, TTFB ${ready_ttfb}s"
    else
      record CRITICAL api "Ready local no saludable: HTTP ${ready_code}"
    fi

    if [[ -n "${MEDIA_URL:-}" ]]; then
      media_metrics="$(curl --silent --show-error --location --proto '=https' --proto-redir '=https' --range 0-1048575 --output /dev/null --max-time 30 --write-out '%{http_code} %{size_download} %{time_starttransfer} %{time_total} %{speed_download}' "$MEDIA_URL" 2>/dev/null || echo '000 0 30 30 0')"
      read -r media_code media_bytes media_ttfb media_total media_speed <<<"$media_metrics"
      echo "HTTP_MEDIA_RANGE code=$media_code bytes=$media_bytes ttfb_seconds=$media_ttfb total_seconds=$media_total speed_bytes_second=$media_speed"
      if [[ "$media_code" == "206" && "$media_bytes" -gt 0 ]]; then
        record "$(severity_for_percent "$media_ttfb" 0.8 2)" media "Range HTTPS correcto, TTFB ${media_ttfb}s, velocidad ${media_speed} B/s"
      else
        record CRITICAL media "La prueba Range esperaba HTTP 206 y datos; recibió ${media_code}/${media_bytes}"
      fi
    fi
  else
    record WARN tooling "curl no está instalado; no se probaron endpoints HTTP"
  fi

  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "CONTAINERS_BEGIN"
    while IFS=$'\t' read -r container_name container_status; do
      [[ -n "$container_name" ]] || continue
      restart_count="$(docker inspect --format '{{.RestartCount}}' "$container_name" 2>/dev/null || echo unknown)"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_name" 2>/dev/null || echo unknown)"
      echo "CONTAINER name=$container_name status=$(printf '%q' "$container_status") health=$health restarts=$restart_count"
      if [[ "$container_status" != Up* || "$health" == "unhealthy" ]]; then
        record CRITICAL containers "${container_name}: ${container_status}, health=${health}"
      elif [[ "$restart_count" =~ ^[0-9]+$ ]] && (( restart_count > 0 )); then
        record WARN containers "${container_name}: ${restart_count} reinicios acumulados"
      fi
    done < <(docker ps -a --format '{{.Names}}\t{{.Status}}')
    echo "CONTAINERS_END"
  else
    record WARN tooling "Docker no está disponible para el usuario actual"
  fi

  case "$HIGHEST_SEVERITY" in
    2) echo "OVERALL_STATUS=CRITICAL" ;;
    1) echo "OVERALL_STATUS=WARN" ;;
    *) echo "OVERALL_STATUS=OK" ;;
  esac
} | tee "$REPORT"

echo "REPORT=$REPORT"
if [[ "$FAIL_ON_CRITICAL" == "true" ]] && grep -Fxq 'OVERALL_STATUS=CRITICAL' "$REPORT"; then
  exit 1
fi
