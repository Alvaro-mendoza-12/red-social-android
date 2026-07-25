#!/usr/bin/env bash
set -euo pipefail

: "${TURN_HOST:?TURN_HOST is required}"
: "${TURN_EXTERNAL_IP:?TURN_EXTERNAL_IP is required}"

TURN_MIN_PORT="${TURN_MIN_PORT:-49160}"
TURN_MAX_PORT="${TURN_MAX_PORT:-49200}"
TURN_TLS_PORT="${TURN_TLS_PORT:-443}"
TURN_SECRET_FILE="${TURN_SECRET_FILE:-/etc/conectate-turn.secret}"
TURN_CERT_FILE="${TURN_CERT_FILE:-}"
TURN_KEY_FILE="${TURN_KEY_FILE:-}"

if [[ ! "$TURN_MIN_PORT" =~ ^[0-9]+$ ]] || [[ ! "$TURN_MAX_PORT" =~ ^[0-9]+$ ]]; then
  echo "TURN relay ports must be numeric" >&2
  exit 1
fi

if (( TURN_MIN_PORT < 1024 || TURN_MAX_PORT > 65535 || TURN_MIN_PORT > TURN_MAX_PORT )); then
  echo "TURN relay port range is invalid" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates \
  coturn \
  openssl \
  ufw \
  unattended-upgrades

if [[ ! -s "$TURN_SECRET_FILE" ]]; then
  umask 077
  openssl rand -hex 32 > "$TURN_SECRET_FILE"
fi
chown root:root "$TURN_SECRET_FILE"
chmod 600 "$TURN_SECRET_FILE"
turn_secret="$(<"$TURN_SECRET_FILE")"

install -d -m 0750 -o root -g turnserver /etc/turnserver

tls_config="no-tls
no-dtls"
if [[ -n "$TURN_CERT_FILE" || -n "$TURN_KEY_FILE" ]]; then
  if [[ -z "$TURN_CERT_FILE" || -z "$TURN_KEY_FILE" || ! -r "$TURN_CERT_FILE" || ! -r "$TURN_KEY_FILE" ]]; then
    echo "Both readable TURN_CERT_FILE and TURN_KEY_FILE are required for TLS" >&2
    exit 1
  fi
  install -m 0640 -o root -g turnserver "$TURN_CERT_FILE" /etc/turnserver/tls-fullchain.pem
  install -m 0640 -o root -g turnserver "$TURN_KEY_FILE" /etc/turnserver/tls-privkey.pem
  install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
  cat > /etc/letsencrypt/renewal-hooks/deploy/conectate-coturn <<EOF
#!/usr/bin/env bash
set -euo pipefail
install -m 0640 -o root -g turnserver "${TURN_CERT_FILE}" /etc/turnserver/tls-fullchain.pem
install -m 0640 -o root -g turnserver "${TURN_KEY_FILE}" /etc/turnserver/tls-privkey.pem
systemctl restart coturn
EOF
  chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/conectate-coturn
  tls_config="cert=/etc/turnserver/tls-fullchain.pem
pkey=/etc/turnserver/tls-privkey.pem"
fi

cat > /etc/turnserver.conf <<EOF
listening-port=3478
tls-listening-port=${TURN_TLS_PORT}
listening-ip=0.0.0.0
external-ip=${TURN_EXTERNAL_IP}
realm=${TURN_HOST}
server-name=${TURN_HOST}

fingerprint
use-auth-secret
static-auth-secret=${turn_secret}
stale-nonce=600

min-port=${TURN_MIN_PORT}
max-port=${TURN_MAX_PORT}
relay-threads=2
user-quota=64
total-quota=2000

no-multicast-peers
no-cli
no-tlsv1
no-tlsv1_1
no-software-attribute

denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.0.0.0-192.0.0.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=224.0.0.0-255.255.255.255

syslog
simple-log

${tls_config}
EOF

chown root:turnserver /etc/turnserver.conf
chmod 0640 /etc/turnserver.conf

if grep -q '^#\?TURNSERVER_ENABLED=' /etc/default/coturn; then
  sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
else
  printf '\nTURNSERVER_ENABLED=1\n' >> /etc/default/coturn
fi

install -d -m 0755 /etc/systemd/system/coturn.service.d
cat > /etc/systemd/system/coturn.service.d/10-low-port.conf <<'EOF'
[Service]
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
EOF

rm -f /etc/ssh/sshd_config.d/60-conectate-hardening.conf
cat > /etc/ssh/sshd_config.d/00-conectate-hardening.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
PubkeyAuthentication yes
MaxAuthTries 3
X11Forwarding no
ClientAliveInterval 300
ClientAliveCountMax 2
EOF

sshd -t

ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH key only'
ufw allow 3478/tcp comment 'TURN TCP'
ufw allow 3478/udp comment 'TURN UDP'
ufw allow "${TURN_TLS_PORT}/tcp" comment 'TURN TLS'
ufw allow "${TURN_TLS_PORT}/udp" comment 'TURN DTLS reserved'
ufw allow "${TURN_MIN_PORT}:${TURN_MAX_PORT}/udp" comment 'TURN relay UDP'
ufw allow "${TURN_MIN_PORT}:${TURN_MAX_PORT}/tcp" comment 'TURN relay TCP'
ufw --force enable

systemctl daemon-reload
systemctl enable coturn
systemctl restart coturn
systemctl enable --now unattended-upgrades
systemctl reload ssh

systemctl is-active --quiet coturn
systemctl is-active --quiet ssh
ss -lntup | grep -E ":(22|3478|${TURN_TLS_PORT})\\b"
ufw status numbered

echo "TURN_PROVISIONED=true"
echo "TURN_HOST=${TURN_HOST}"
echo "TURN_EXTERNAL_IP=${TURN_EXTERNAL_IP}"
echo "TURN_TLS_ENABLED=$([[ -n "$TURN_CERT_FILE" ]] && echo true || echo false)"
