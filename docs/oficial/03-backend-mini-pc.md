# 03 — Backend Ubuntu / servidor mini PC

El backend Ubuntu es el servidor principal de Conéctate. Corre sobre una mini PC con Ubuntu Server, Docker Compose y servicios systemd/watchdog.

## Ubicación

En Windows:

```text
D:\red social - copia\infrastructure\local-fallback
```

En Ubuntu mini PC:

```text
/home/alvaro/conectate-local-fallback
```

## Servicios Docker

Archivo:

```text
infrastructure/local-fallback/docker-compose.yml
```

Servicios:

| Servicio | Contenedor | Función |
|---|---|---|
| `db` | `conectate-local-fallback-db-main` | PostgreSQL 16 |
| `api` | `conectate-local-fallback-api-main` | API principal Node/Express |
| `edge` | `conectate-local-fallback-edge-main` | nginx reverse proxy |
| `realtime-gateway` | `conectate-local-fallback-realtime-main` | realtime |
| `signaling` | `conectate-local-fallback-signaling-main` | WebRTC/Socket.IO |
| `coturn` | `conectate-local-fallback-coturn-main` | TURN opcional |

## Puertos internos esperados

| Servicio | Puerto local host | Puerto contenedor |
|---|---:|---:|
| API | `127.0.0.1:3001` | `3001` |
| Realtime | `127.0.0.1:3002` | `3002` |
| Signaling | `3003` interno | `3003` |
| Edge | `127.0.0.1:3080` | `8080` |
| PostgreSQL | `127.0.0.1:5433` | `5432` |

El público entra por Cloudflare Tunnel hacia nginx edge.

## nginx edge

Archivo:

```text
infrastructure/local-fallback/nginx/default.conf
```

Rutas relevantes:

- `/ready` → API.
- `/health` → API.
- `/socket.io/` → signaling.
- `/call/socket.io/` → signaling.
- `/live/socket.io/` → signaling.
- `/turn-credentials` → signaling.
- `/realtime/` → realtime gateway.
- resto `/` → API.

## API Node/Express

Ruta:

```text
infrastructure/local-fallback/api/
```

Archivo principal:

```text
src/server.js
```

Responsabilidades:

- autenticación;
- JWT;
- bcrypt;
- usuarios;
- perfiles;
- mensajes;
- grupos;
- comunidades;
- videos/posts;
- storage;
- notificaciones push;
- compatibilidad/migración Supabase;
- health/ready;
- admin endpoints.

Variables críticas:

- `DATABASE_URL`
- `JWT_SECRET`
- `STORAGE_ROOT`
- `PUBLIC_BASE_URL`
- `FIREBASE_SERVICE_ACCOUNT_FILE`
- `ADMIN_SYNC_TOKEN`
- `LEGACY_SUPABASE_IMPORT_ENABLED`

## Migración Supabase

La API contiene lógica para cuentas importadas.

Regla funcional:

- solo las cuentas importadas de Supabase, creadas antes del corte de migración y sin contraseña local confirmada, deben ver flujo de cambio/creación de contraseña;
- correos inexistentes no deben ver flujo de migración;
- usuarios Ubuntu con contraseña incorrecta deben ver error de contraseña incorrecta.

El corte observado en código:

```text
2026-05-25T00:00:00.000Z
```

## Signaling Server

Ruta:

```text
signaling-server/
```

Archivo:

```text
index.js
```

Responsabilidades:

- WebSocket/Socket.IO;
- señalización WebRTC;
- calls;
- live rooms;
- voice rooms;
- exchange de ICE candidates;
- entrega de credenciales TURN/STUN;
- rate limiting;
- allowed origins;
- health endpoint.

Variables TURN/STUN soportadas:

- `TURN_HOST`
- `TURN_SECRET`
- `TURN_REALM`
- `TURN_PORT`
- `TURN_TLS_PORT`
- `TURN_TTL_SECONDS`
- `TURN_STATIC_URLS`
- `TURN_URLS`
- `TURN_STATIC_USERNAME`
- `TURN_STATIC_CREDENTIAL`
- `STUN_STATIC_URLS`

## Realtime Gateway

Ruta:

```text
infrastructure/local-fallback/realtime-gateway/
```

Responsabilidades:

- eventos realtime de app;
- integración con API;
- notificaciones de cambios;
- estado online/presence cuando aplique.

## Operación 24/7

La mini PC debe estar configurada para no suspender:

- systemd sleep targets masked;
- logind ignorando suspensión;
- servicio inhibidor `conectate-inhibit-sleep.service`;
- Docker y stack habilitados;
- timers de health/backup habilitados;
- BIOS configurada para no ahorrar energía agresivamente.

El sistema operativo puede bloquear suspensión, pero algunos equipos Lenovo pueden tener ahorro o suspensión desde BIOS/firmware. Por eso también se documenta en el runbook.

## Comandos principales en Ubuntu

Estado:

```bash
~/bin/conectate-status
```

Reinicio seguro del stack:

```bash
~/bin/conectate-restart
```

Docker compose:

```bash
cd ~/conectate-local-fallback
docker compose ps
docker compose logs --tail=120 api
docker compose logs --tail=120 signaling
docker compose logs --tail=120 realtime-gateway
```

systemd:

```bash
systemctl status conectate-stack.service --no-pager
systemctl status conectate-backup.timer --no-pager
systemctl status conectate-health.timer --no-pager
systemctl --user status conectate-cloudflare-tunnel.service --no-pager
```

## Scripts relevantes

Windows:

```text
scripts/apply-mini-pc-24x7.ps1
scripts/configure-duckdns-discovery.ps1
scripts/configure-openrelay-turn.sh
scripts/deploy-openrelay-turn.ps1
scripts/prepare-mini-pc-migration.ps1
scripts/format-conectate-backup-disk-ext4.sh
```

Ubuntu/backend:

```text
infrastructure/local-fallback/scripts/backup-ubuntu-primary.sh
infrastructure/local-fallback/scripts/health-watchdog.sh
infrastructure/local-fallback/scripts/import-mini-pc-migration.sh
infrastructure/local-fallback/scripts/install-cloudflare-tunnel-24-7.sh
infrastructure/local-fallback/scripts/install-mini-pc-24-7.sh
infrastructure/local-fallback/scripts/install-ubuntu-operations.sh
infrastructure/local-fallback/scripts/repair-ubuntu-primary.sh
infrastructure/local-fallback/scripts/restore-drill.sh
infrastructure/local-fallback/scripts/verify-ubuntu-production.sh
```

## Principios de producción

- SSD para sistema, Docker, PostgreSQL y servicios críticos.
- Discos externos para backups/media, no para PostgreSQL principal.
- No depender de sesión SSH abierta.
- No depender de terminal visible.
- Todo servicio crítico debe iniciar con systemd.
- Todo cambio de endpoint público debe reflejarse en discovery.
- Todo backup debe verificarse, no solo generarse.

