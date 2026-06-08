# Coturn Production Setup

Para mantenimiento operativo completo del VPS, actualizacion de `index.js` y comandos de diagnostico, ver tambien:

- `docs/VOICE_VPS_RUNBOOK_2026.md`

Esta app ya puede pedir credenciales TURN temporales al signaling server en:

- `GET /turn-credentials`

Ese endpoint valida el `Bearer` emitido por tu backend Ubuntu en `FALLBACK_API_URL`
y firma credenciales tipo TURN REST con `TURN_SECRET`.

## 1. Instalar coturn en la VPS

```bash
sudo apt update
sudo apt install -y coturn
```

## 2. Abrir puertos en Hostinger y en el firewall del servidor

Abre como minimo:

- `3478/tcp`
- `3478/udp`
- `5349/tcp` si vas a usar TLS
- un rango UDP para relay, por ejemplo `49160-49200/udp`

Si usas `ufw`:

```bash
sudo ufw allow 3478/tcp
sudo ufw allow 3478/udp
sudo ufw allow 49160:49200/udp
sudo ufw allow 5349/tcp
sudo ufw reload
```

## 3. Configurar `/etc/turnserver.conf`

Ejemplo recomendado para una sola VPS:

```conf
use-auth-secret
static-auth-secret=REEMPLAZA_CON_TU_SECRET
realm=voice.srv1495648.hstgr.cloud

server-name=voice.srv1495648.hstgr.cloud
listening-port=3478
tls-listening-port=5349

fingerprint
lt-cred-mech
no-multicast-peers
no-cli

listening-ip=0.0.0.0
external-ip=76.13.160.177

min-port=49160
max-port=49200

cert=/etc/letsencrypt/live/voice.srv1495648.hstgr.cloud/fullchain.pem
pkey=/etc/letsencrypt/live/voice.srv1495648.hstgr.cloud/privkey.pem
```

Notas:

- `static-auth-secret` debe ser exactamente el mismo valor que `TURN_SECRET` en `signaling-server/.env`.
- `external-ip` debe ser la IP publica real de tu VPS.
- Si todavia no vas a usar TLS, puedes omitir `cert`, `pkey` y luego poner `TURN_ENABLE_TLS=false` en el signaling server.

## 4. Habilitar el servicio

En Debian/Ubuntu suele venir deshabilitado por defecto. Revisa `/etc/default/coturn`:

```bash
sudo sed -i 's/^#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn
sudo sed -i 's/^TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn
```

Luego reinicia:

```bash
sudo systemctl enable coturn
sudo systemctl restart coturn
sudo systemctl status coturn
```

## 5. Configurar el signaling server

Copia `signaling-server/.env.example` a `signaling-server/.env` y completa:

```env
FALLBACK_API_URL=https://tu-url-ngrok-o-dominio-api
SIGNALING_ALLOWED_ORIGINS=https://app.tudominio.com,https://admin.tudominio.com
HTTP_RATE_LIMIT_PER_MINUTE=180
SOCKET_RATE_LIMIT_PER_MINUTE=90
TURN_HOST=voice.srv1495648.hstgr.cloud
TURN_PORT=3478
TURN_TLS_PORT=5349
TURN_SECRET=EL_MISMO_SECRET_DE_COTURN
TURN_REALM=voice.srv1495648.hstgr.cloud
TURN_TTL_SECONDS=3600
TURN_ENABLE_UDP=true
TURN_ENABLE_TCP=true
TURN_ENABLE_TLS=false
```

Reinicia tu signaling server despues de guardar el `.env`.

Notas de hardening:

- No uses `*` en `SIGNALING_ALLOWED_ORIGINS` en produccion.
- `FALLBACK_API_URL` debe apuntar al backend Ubuntu publico que expone `/v1/auth/me`.
- No configures llaves heredadas del proveedor anterior en el signaling server para produccion Ubuntu-only.
- Si abres el panel admin en otro subdominio, agregalo explicitamente a `SIGNALING_ALLOWED_ORIGINS`.

### 5.1 Proxy HTTPS de Caddy

Si usas Caddy delante de Node, no proxies solo `socket.io`.
Tambien deben quedar publicados:

- `/`
- `/health`
- `/turn-credentials`

Puedes partir de `signaling-server/Caddyfile.example`.
La idea es que todo el host `voice.srv1495648.hstgr.cloud` haga reverse proxy a `127.0.0.1:3001`.

## 6. Probar el endpoint

Con un access token valido del backend Ubuntu:

```bash
curl -H "Authorization: Bearer TU_ACCESS_TOKEN" https://voice.srv1495648.hstgr.cloud/turn-credentials
```

Debes recibir un JSON con:

- `iceServers`
- `username`
- `credential`
- `ttlSeconds`

## 7. Recompilar Android

Con esta ruta autohospedada:

- no necesitas meter `XIRSYS_SECRET` en el APK
- no necesitas `OPENRELAY_*` para produccion
- el cliente pedira credenciales TURN temporales al signaling server

Compila:

```bash
./gradlew.bat :app:compileDebugKotlin
```
