# 03 — Backend Ubuntu / servidor mini PC

El backend Ubuntu es el servidor principal de Conéctate. Corre sobre una mini PC con Ubuntu Server, Docker Compose y servicios systemd/watchdog.

## Ubicación

En Windows:

```text
D:\red social - live-gifts\infrastructure\local-fallback
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

El público entra por un Cloudflare Tunnel **nombrado** hacia nginx edge. El
servicio esperado es `novvin-cloudflared.service`; los Quick Tunnels manuales
son una transición temporal que no se debe usar como base de producción.

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
- `SCHEMA_INITIALIZATION_TIMEOUT_MS` (180000 recomendado)
- `WEB_JWT_EXPIRES_IN` (`15m` recomendado; máximo operativo sin revisión: `30m`)
- `WEB_SESSION_TTL_DAYS` (`30` recomendado)
- `CORS_ORIGIN` (lista explícita de orígenes HTTPS web; nunca `*` en producción)

El arranque de esquema se serializa con un advisory lock de PostgreSQL y usa una sola conexión durante toda la inicialización. Esto impide que dos réplicas ejecuten DDL incompatible al mismo tiempo; si el lock o el DDL excede el timeout, la API falla cerrada y no anuncia readiness parcial.

Los límites de autenticación y subida sensible se almacenan en `fallback_rate_limit_windows` mediante un incremento atómico. Por ello no se reinician al reiniciar Node ni se multiplican al añadir réplicas. La limitación en PostgreSQL es una defensa de aplicación; producción todavía debe conservar rate limiting en edge/CDN para absorber tráfico volumétrico antes de llegar a la base de datos.

`fallback_web_sessions` conserva sólo el hash del secreto opaco HttpOnly, su
expiración y metadatos operativos. El bearer web se firma por 15 minutos y no se
usa como refresh token. Las columnas e índices de esta sesión se agregan bajo el
mismo lock de inicialización de esquema, por lo que se debe desplegar la API
antes que la web que deja de persistir bearers.

La imagen API crea `novvin:novvin` con UID/GID 10001. El entrypoint conserva
privilegios únicamente para actualizar firmas ClamAV y verificar los permisos
de `STORAGE_ROOT`, `.tmp`, `_variants` y cada bucket de primer nivel. Si falta
el marcador `.novvin-runtime-owner-v2`, la raíz no coincide o detecta un bucket
heredado con otro propietario, ejecuta una reparación recursiva única y valida
el resultado antes de iniciar Node. Esto cubre restauraciones y directorios
creados por versiones antiguas como `root` aunque la raíz del volumen ya
perteneciera a Novvin. Después ejecuta Node mediante `su-exec` con ese usuario.
El contenedor usa filesystem de sólo lectura, `/tmp` acotado y
`no-new-privileges`; cualquier cambio de volumen debe conservar escritura para
UID/GID 10001.

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

## Entrega de video y multimedia

La API y Android comparten una política de admisión para que el archivo pueda
reproducirse con el caudal público real del servidor:

- máximo 1920 × 1080;
- máximo 31 fps;
- máximo 4.5 Mb/s;
- máximo 45 segundos;
- máximo 32 MiB;
- MP4 con metadatos `faststart` cuando se normaliza.

El cliente valida antes de subir y el backend vuelve a inspeccionar con
`ffprobe`; un cliente desactualizado no puede saltarse la política. Los videos
históricos se revisan primero con `npm run normalize:video-delivery` y solo se
modifican al repetirlo con `-- --apply`.

### Auditoría referencial de storage

`npm run audit:storage-references` inspecciona un snapshot PostgreSQL
`REPEATABLE READ READ ONLY` y cruza `fallback_storage_objects` con:

- todas las columnas URL/URI/path/media/audio/video/imagen/archivo;
- columnas JSON/JSONB y arreglos de texto;
- referencias por URL pública, URL firmada, clave `bucket/name` o UUID del
  objeto;
- archivos reales de cada bucket del volumen `/data/storage`.

El reporte diferencia objetos referenciados, uploads recientes protegidos,
candidatos huérfanos, metadata sin archivo, archivos sin metadata, rutas fuera
del volumen y referencias de base de datos sin objeto de storage. Una
referencia rota o una ruta anómala es crítica. La herramienta nunca ejecuta
`DELETE`, `UPDATE`, `unlink` ni mueve archivos; rechaza explícitamente
`--apply`, `--delete`, `--purge` y `--quarantine`.

La ventana de protección predeterminada es de siete días. Un objeto no puede
considerarse eliminable solo por aparecer como candidato: debe sobrevivir dos
auditorías separadas, no tener referencias y contar con backup verificado.

Nginx/Express deben conservar `Range`/`206`, `Accept-Ranges`, ETag y caché para
objetos inmutables. `Server-Timing` permite separar tiempo de aplicación y
tiempo de transferencia. Para archivos locales de al menos 6 MiB, la app y el
fallback Ubuntu usan TUS en `/storage/v1/upload/resumable`: `POST` crea la
sesión, `HEAD` confirma el offset y `PATCH` envía fragmentos de 6 MiB con
bloqueo transaccional. Cada sesión pertenece al usuario autenticado, caduca en
24 horas, se valida contra el límite del bucket y solo se publica cuando el
archivo completo pasa la validación de tipo/firma. Las instalaciones antiguas
que no anuncien TUS conservan compatibilidad con el `PUT` existente; no se
reintenta automáticamente un `PUT` que pudo haberse confirmado.

El endpoint TUS deja sesiones completadas durante una hora para que una pérdida
de respuesta pueda reconciliarse con `HEAD` sin duplicar bytes. Las sesiones
abandonadas se eliminan junto con su fragmento temporal mediante una limpieza
al iniciar y cada 15 minutos (además de la limpieza al crear sesiones). La prueba HTTP
correspondiente está en `api/tests/storage-routes.test.js`; la app registra
`media_upload_resumable` sin rutas, nombres ni identificadores personales.
Android persiste la URL opaca de sesión antes del primer fragmento, bajo una
clave SHA-256 ligada a cuenta/objeto, y la reutiliza durante un máximo de 23
horas sólo si esquema, host, puerto y prefijo TUS siguen coincidiendo. No se
persisten tokens ni rutas/nombres en claro y el archivo de preferencias queda
fuera de backup/transferencia de dispositivo.

Las miniaturas WebP son artefactos reconstruibles en `_variants`, no la fuente
de verdad. Si Sharp o el caché derivado fallan, la ruta pública conserva estado
`200` y entrega el objeto original con su tipo real; se registra el error sin
exponer paths ni identificadores. Así una avería de optimización no convierte
avatares, portadas o imágenes del feed en iniciales o cargas infinitas.

La transición `finalizing → completed` usa un lock asesor PostgreSQL por UUID
de subida. Si una réplica cae después de confirmar el último offset, cualquier
`HEAD` posterior puede tomar el lock y terminar la publicación; un fallo
transitorio mantiene el estado `finalizing` en vez de producir la combinación
inválida `uploading + offset completo`. Esto hace segura la recuperación entre
reinicios y evita que dos réplicas muevan el mismo archivo simultáneamente.

El feed se pagina con consultas acotadas e índices, conserva el ranking del
servidor y añade exploración por sesión/cuenta. Creación y eliminación se
publican en realtime; la eliminación debe retirar inmediatamente el elemento
de clientes conectados.

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
- límite por conexión y por paquetes Socket.IO;
- payload WebRTC acotado (256 KiB por mensaje, SDP/ICE validados);
- logs operativos con referencias efímeras seudonimizadas, sin correo, IP ni fragmentos SDP;
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
- `HTTP_RATE_LIMIT_PER_MINUTE`
- `SOCKET_RATE_LIMIT_PER_MINUTE`
- `SOCKET_EVENT_RATE_LIMIT_PER_MINUTE`

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

## Origen público estable y descubrimiento firmado

La configuración primaria es un hostname HTTPS fijo asociado a un Cloudflare
Tunnel nombrado. El procedimiento seguro de alta, validación, rollback y
rotación de token está en
[`NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md`](../NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md).

El descubrimiento firmado es compatibilidad temporal: si excepcionalmente se
activa, las rotaciones no deben publicar una URL plana. Configure en
`~/conectate-local-fallback/.env`:

```dotenv
DUCKDNS_DOMAIN=conectate-alvaro.duckdns.org
DUCKDNS_TOKEN=VALOR_SOLO_EN_SERVIDOR
BACKEND_DISCOVERY_PRIVATE_KEY_FILE=/home/alvaro/.config/novvin/backend-discovery-private.pem
BACKEND_DISCOVERY_TTL_SECONDS=86400
```

La clave se prepara desde la laptop con
`scripts/configure-backend-discovery-signing.ps1`; el script genera el secreto
en Ubuntu, recupera sólo el SPKI público y actualiza los clientes. Los servicios
de túnel firman `novvin1:<exp>:<url-b64url>:<firma-b64url>`. Si falta la clave,
no publican un TXT inseguro.

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
sudo systemctl status novvin-cloudflared.service --no-pager
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
infrastructure/local-fallback/scripts/install-named-cloudflare-tunnel.sh
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

## Perfil de recursos desplegado — 2026-09-02

El release activo `conectate-resource-budgets-20260902T020157Z` aplica límites
por servicio para evitar que una fuga o transcodificación bloquee al resto:

| Servicio | Memoria | CPU | PID |
|---|---:|---:|---:|
| PostgreSQL | 2 GiB | 2 | 256 |
| Redis de signaling | 512 MiB | 0.5 | 64 |
| API | 4 GiB | 3 | 384 |
| IA | 512 MiB | 0.75 | 64 |
| Signaling | 256 MiB | 1 | 128 |
| Edge | 256 MiB | 0.5 | 64 |
| Realtime gateway | 192 MiB | 0.75 | 128 |
| Coturn | 512 MiB | 1 | 128 |

La huella desplegada es
`af355ece55d29c7b3815a19017ea3ede191e3cd674e3ff67dc499463c2009d3e`.
`docker compose config --quiet`, el estado saludable de los ocho servicios y
`/ready` directo/edge se comprobaron después de recrearlos. No hace falta
reiniciar todo el host para aplicar un cambio de un único módulo.

