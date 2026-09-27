# Conectate Ubuntu Backend

Fecha de ultima revision documental: 2026-06-18.

Servidor local para mantener Conectate operando desde infraestructura propia
Ubuntu. Originalmente nacio como fallback ante restricciones de Supabase, pero
ahora tambien puede usarse como backend principal para autenticacion, datos
basicos, storage local y pantallas web/Android ya migradas.

El puente con Supabase queda como herramienta opcional de importacion/sync y
esta apagado por defecto. El servidor puede arrancar con
`LEGACY_SUPABASE_IMPORT_ENABLED=false`, sin `SUPABASE_URL`, sin
`SUPABASE_SERVICE_ROLE_KEY` y sin el SDK de Supabase.

## Servicios

- `api`: API Node en `http://127.0.0.1:3001`
- `db`: Postgres privado en `127.0.0.1:5433`
- `storage`: volumen local Docker `conectate-fallback-storage` montado en `/data/storage`
- `signaling`: Socket.IO para voz, llamadas y lives, autenticado contra la API Ubuntu
- `signaling-redis`: Redis Streams privado para difusión de Socket.IO,
  presencia entre procesos y futura recuperación controlada; no publica puerto
  al host y persiste AOF en `novvin-signaling-redis`
- `edge`: proxy local en `http://127.0.0.1:3080` para exponer API, storage y Socket.IO mediante un único origen HTTPS estable

## Modo backend principal

Para web, configura solo:

```env
VITE_FALLBACK_API_URL=https://api.novvin.example
```

Para Android, en `secrets.properties`:

```properties
FALLBACK_API_URL=https://api.novvin.example
SUPABASE_URL=
SUPABASE_ANON_KEY=
```

Voz, llamadas y audio usan el mismo origen HTTPS cuando el Cloudflare Tunnel
nombrado apunta al puerto `3080`.

## Copiar desde Windows a la VM

Desde CMD o PowerShell, en la raiz del repo:

```powershell
ssh -p 2222 alvaro@127.0.0.1 "mkdir -p ~/conectate-local-fallback"
scp -P 2222 -r infrastructure/local-fallback/* alvaro@127.0.0.1:~/conectate-local-fallback/
scp -P 2222 -r signaling-server alvaro@127.0.0.1:~/signaling-server
```

La cuenta de servicio de Google Play no se copia con ese comando. Transfiérela
por separado a una ruta temporal y muévela al directorio privado ignorado por
Git:

```powershell
scp -P 2222 "C:\ruta\nueva-red-social-c9d04-6b94a68481fe.json" `
  alvaro@127.0.0.1:/tmp/novvin-play-billing.json
ssh -p 2222 alvaro@127.0.0.1 "umask 077; install -d -m 700 ~/conectate-local-fallback/secrets; install -m 600 /tmp/novvin-play-billing.json ~/conectate-local-fallback/secrets/google-play-service-account.json; rm -f /tmp/novvin-play-billing.json"
```

El contenedor `api` sólo recibe ese archivo como montaje de lectura. No se
guarda en `.env`, Kotlin, APK, logs ni commits.

Si solo quieres actualizar rapido el backend despues de un cambio:

```powershell
scp -P 2222 infrastructure/local-fallback/api/src/data-routes.js alvaro@127.0.0.1:~/conectate-local-fallback/api/src/data-routes.js
scp -P 2222 infrastructure/local-fallback/api/src/storage-routes.js alvaro@127.0.0.1:~/conectate-local-fallback/api/src/storage-routes.js
scp -P 2222 infrastructure/local-fallback/api/src/server.js alvaro@127.0.0.1:~/conectate-local-fallback/api/src/server.js
scp -P 2222 infrastructure/local-fallback/nginx/default.conf alvaro@127.0.0.1:~/conectate-local-fallback/nginx/default.conf
scp -P 2222 -r infrastructure/local-fallback/scripts alvaro@127.0.0.1:~/conectate-local-fallback/
```

## Levantar en Ubuntu

Dentro del SSH de Ubuntu:

```bash
cd ~/conectate-local-fallback
bash scripts/sync-env-from-example.sh
PUBLIC_BASE_URL=https://api.novvin.example bash scripts/repair-ubuntu-primary.sh
```

Si todavía no tienes dominio HTTPS estable, no ejecutes un despliegue de
producción. Configura primero el Cloudflare Tunnel nombrado siguiendo
[`docs/NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md`](../../docs/NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md).

`sync-env-from-example.sh` agrega claves nuevas de `config.example` a `.env`
sin borrar tus valores actuales, contrasenas ni tokens.

`deploy-stack-safely.sh` crea, si falta, el secreto dedicado de Redis en
`secrets/signaling-redis-password.txt` con permisos `600`; no lo imprime ni lo
guarda en `.env`. Signaling falla cerrado en producción si Redis no puede
conectarse. Verifica el bus sin exponer credenciales:

```bash
curl -fsS http://127.0.0.1:3080/health | jq '.realtimeBus,.stateConsistency'
docker compose exec -T signaling-redis sh -ec \
  'REDISCLI_AUTH="$(cat /run/secrets/signaling_redis_password)" redis-cli ping'
```

Mantén `SIGNALING_REPLICAS=1`. Redis, DNS dinámico y afinidad ya están
preparados, pero el estado avanzado de Live (batallas, invitados y timers)
todavía requiere una prueba de rehidratación/failover antes de autorizar más de
una réplica. `SIGNALING_CONNECTION_RECOVERY_ENABLED=false` es deliberado por la
misma razón.

Si `api` queda en `unhealthy` o `Restarting`, ejecuta el diagnostico:

```bash
cd ~/conectate-local-fallback
bash scripts/diagnose-api.sh
```

El diagnostico valida si quedo codigo viejo con rutas incompatibles de Express,
revisa sintaxis dentro de Docker, imprime estado de Postgres y muestra los logs
exactos del contenedor `api`.

## Verificar modulos principales

Despues de copiar los cambios a la VM y levantar servicios, ejecuta esta prueba
de humo completa:

```bash
cd ~/conectate-local-fallback
API_URL="http://127.0.0.1:3080" bash scripts/verify-ubuntu-modules.sh
API_URL="http://127.0.0.1:3080" STRICT_PRODUCTION=true bash scripts/verify-ubuntu-production.sh
PUBLIC_BASE_URL="https://api.novvin.example" bash scripts/verify-ubuntu-storage.sh
docker compose ps
docker compose logs --tail=120 api edge signaling realtime-gateway
```

### Consultar usuarios reales de forma segura

El contador de usuarios debe salir del backend, no de las descargas de Google Play.
Con una sesión JWT de un administrador ya iniciada, ejecuta la consulta de solo lectura
sin guardar el token en el repositorio:

```bash
API_URL="https://tu-dominio-publico" \
ADMIN_TOKEN="<JWT_DE_SESION_ADMIN>" \
bash scripts/fetch-admin-stats.sh
```

La salida incluye `total_users` (usuarios canónicos no eliminados), `total_videos` y
los contadores de moderación. Si falta `ADMIN_TOKEN`, el script termina sin intentar
crear usuarios ni alterar datos.

Para medir la captación sin contar cuentas técnicas, ejecuta desde la misma VM:

```bash
SINCE_DATE="2026-07-01T00:00:00Z" bash scripts/fetch-acquisition-metrics.sh
```

La consulta excluye patrones de pruebas conocidos y cuenta solo cuentas locales con
actividad en chats, publicaciones, videos, comunidades u otras funciones de usuario.
No expone correos ni crea registros.

`verify-ubuntu-modules.sh` valida en una sola pasada:

- registro/login local y sesion JWT;
- subida a storage local de avatar, audio y video;
- perfil, editar perfil y busqueda de personas;
- publicaciones, comentarios y comentario con audio;
- subida/listado/busqueda de videos, reproduccion por URL, vistas y comentario
  de video con audio;
- momentos y feed de momentos;
- reto activo, participacion, voto y listado de submissions.

`verify-ubuntu-production.sh` es mas estricto: valida Docker, puertos, logs,
Postgres, permisos de storage, proxy, realtime/signaling, dos usuarios de
prueba, notificaciones, FCM tokens, DM privado local, grupos, comunidades,
videos, momentos, retos, rankings y genera `production-readiness-report.md` con
la matriz endpoint/Android/Postgres/storage.

Resultado esperado:

```text
LISTO: smoke test Ubuntu completo.
```

Si falla, el mensaje incluye el endpoint exacto, codigo HTTP y respuesta del
servidor. Revisa inmediatamente:

```bash
cd ~/conectate-local-fallback
docker compose ps
docker compose logs --tail=200 api
docker compose logs --tail=120 edge
docker compose exec -T db psql -U conectate -d conectate_fallback -c "\dt"
docker compose exec -T api node --check src/data-routes.js
```

Antes de produccion no uses los secretos de ejemplo:

```bash
openssl rand -hex 32
nano ~/conectate-local-fallback/.env
docker compose up -d --build
```

Cambia `POSTGRES_PASSWORD`, `JWT_SECRET`, `TOTP_ENCRYPTION_KEY`, `ADMIN_SYNC_TOKEN` y
`PUBLIC_BASE_URL`. El resultado esperado es que `docker compose ps` muestre los
servicios estables y `GET /ready` responda correctamente:

```bash
curl -sS http://127.0.0.1:3080/ready
```

`TOTP_ENCRYPTION_KEY` debe generarse de forma independiente y conservarse en las
copias de seguridad de secretos. Cambiarla después de activar 2FA dejaría los
autenticadores existentes sin posibilidad de validación. El backend usa
`JWT_SECRET` como compatibilidad temporal si la variable falta; configura la
clave estable antes de que se active la primera cuenta.

## Probar login local

```bash
curl -sS -X POST http://127.0.0.1:3001/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alvaro@example.local","password":"Conectate123!","displayName":"Alvaro"}'

curl -sS -X POST http://127.0.0.1:3001/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alvaro@example.local","password":"Conectate123!"}'
```

## Exponer el backend de producción

Usa un **Cloudflare Tunnel nombrado** con un hostname propio, por ejemplo
`https://api.novvin.example`, dirigido al puerto `3080`. No uses ngrok ni
Quick Tunnels de Cloudflare en producción: los hostnames son efímeros, no
ofrecen el contrato operativo requerido y pueden cortar multimedia/Socket.IO.

El procedimiento, manejo del token, unidad systemd, verificación y rollback
están documentados en
[`docs/NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md`](../../docs/NAMED_CLOUDFLARE_TUNNEL_RUNBOOK_2026.md).

## Conectar sincronizacion con Supabase

Necesitas una llave secreta de servidor de Supabase. No la pegues en chats, no la
subas a Git y no la pongas en Android/web.

En Supabase Dashboard:

1. Abre tu proyecto.
2. Ve a `Project Settings` > `API Keys`.
3. Copia una `secret key` (`sb_secret_...`) si tu proyecto ya usa las llaves
   nuevas, o la llave legacy `service_role` si solo ves llaves legacy.
4. Pegala solo en `.env` dentro de Ubuntu:

```bash
nano ~/conectate-local-fallback/.env
```

Usa una de estas variables:

```env
LEGACY_SUPABASE_IMPORT_ENABLED=true
SUPABASE_SECRET_KEY=sb_secret_xxx
# o, si usas llave legacy:
SUPABASE_SERVICE_ROLE_KEY=eyJ...
```

Tambien puedes poner `SUPABASE_URL` y `SUPABASE_ANON_KEY` en ese mismo `.env`
para que el servidor pueda probar el estado del upstream.

Despues reinicia la API. Cuando termines la importacion, vuelve a dejar
`LEGACY_SUPABASE_IMPORT_ENABLED=false` y reinicia otra vez:

```bash
cd ~/conectate-local-fallback
docker compose up -d --build
```

Aplica migraciones en una base ya creada:

```bash
docker compose exec -T db psql -U conectate -d conectate_fallback < db/migrations/002_sync_support.sql
```

Guarda tu token admin local:

```bash
grep ADMIN_SYNC_TOKEN .env
```

Importar usuarios de Supabase hacia Ubuntu:

```bash
ADMIN_SYNC_TOKEN="pega_aqui_el_valor_de_ADMIN_SYNC_TOKEN"
curl -sS -X POST http://127.0.0.1:3001/v1/admin/sync/import-supabase-users \
  -H "x-admin-token: $ADMIN_SYNC_TOKEN"
```

Ver estado de sincronizacion:

```bash
curl -sS http://127.0.0.1:3001/v1/admin/sync/status \
  -H "x-admin-token: $ADMIN_SYNC_TOKEN"
```

La ruta heredada `set-local-password` queda reservada para mantenimiento de importaciones.
Para recuperar una cuenta sin contraseña, correo, teléfono ni códigos, el usuario
abre una solicitud desde «Olvidé mi contraseña» → «Perdí también el correo,
teléfono y códigos». Recibe un identificador de caso. El formulario solo toma
un medio de contacto alternativo, su tipo, una pista opcional del perfil y una
descripción; nunca contraseñas ni documentos. La API sugiere una cuenta solo
cuando el correo, usuario o ID tiene una coincidencia exacta y única. La
sugerencia se muestra únicamente a administradores.

La pestaña «Recuperaciones» del panel Android muestra la cola, la sugerencia,
el contacto y el relato. La sesión de administrador da lectura; emitir códigos,
aprobar o rechazar requiere ser propietario administrativo. Las rutas
`/v1/data/admin/account-recovery/...` usan esa sesión; las rutas técnicas
`/v1/admin/account-recovery/...` conservan `ADMIN_SYNC_TOKEN` para operación
desde la mini PC.

El administrador consulta la cola y busca la cuenta probable:

```bash
curl -sS http://127.0.0.1:3001/v1/admin/account-recovery/cases \
  -H "x-admin-token: $ADMIN_SYNC_TOKEN"
curl -sS 'http://127.0.0.1:3001/v1/admin/account-recovery/accounts?q=usuario' \
  -H "x-admin-token: $ADMIN_SYNC_TOKEN"
```

Antes de aprobar, comprueba la propiedad con señales independientes ligadas a
la cuenta y registra la referencia de la revisión. Conocer solo el correo,
nombre de usuario o datos públicos no basta. Si no hay pruebas suficientes,
rechaza el caso. Nunca envíes el código de restablecimiento al contacto alternativo sin verificar
que pertenece al titular. Si el teléfono con el autenticador se perdió,
indica `resetTwoFactor: true` tras verificarlo también.

Primero, genera un código para confirmar el contacto nuevo y compártelo
manualmente por WhatsApp o correo indicado. El solicitante introduce el número
de caso y el código en la APK. El código vence en 30 minutos y admite hasta
cinco intentos. Esto solo demuestra que controla el contacto nuevo; la prueba
de propiedad de la cuenta debe verificarse aparte.

```bash
curl -sS -X POST http://127.0.0.1:3001/v1/admin/account-recovery/cases/ID_DEL_CASO/contact-code \
  -H "x-admin-token: $ADMIN_SYNC_TOKEN"
```

Tras la revisión, emite un código de un solo uso:

```bash
curl -sS -X POST http://127.0.0.1:3001/v1/admin/account-recovery/cases/ID_DEL_CASO/issue-code \
  -H 'Content-Type: application/json' \
  -H "x-admin-token: $ADMIN_SYNC_TOKEN" \
  -d '{"userId":"ID_DE_LA_CUENTA","reviewedBy":"operador","verificationRef":"referencia-interna-de-revision","resetTwoFactor":true}'
```

La respuesta muestra `resetToken` una sola vez; entrégalo por el canal ya
verificado. Expira según `PASSWORD_RESET_TOKEN_TTL_MINUTES` (30 minutos por
defecto). La emisión invalida la contraseña anterior y todas las sesiones.
No se asigna una contraseña que el administrador conozca; el titular elige
la suya al canjear el código. No hay entrega automática porque no hay proveedor
de correo o WhatsApp configurado.
Si expira, la misma ruta puede emitir otro código para ese caso abierto. El
titular usa «Ya tengo un código del administrador» y escoge una contraseña
nueva. El autenticador se restablece al canjear el código solo si la revisión
lo autorizó. Sin pruebas verificables, la cuenta no debe transferirse.

Para rechazar una solicitud sin pruebas suficientes:

```bash
curl -sS -X POST http://127.0.0.1:3001/v1/admin/account-recovery/cases/ID_DEL_CASO/reject \
  -H 'Content-Type: application/json' \
  -H "x-admin-token: $ADMIN_SYNC_TOKEN" \
  -d '{"reviewedBy":"operador","verificationRef":"motivo-interno-del-rechazo"}'
```

Activar password local manualmente para un usuario importado (mantenimiento):

```bash
curl -sS -X POST http://127.0.0.1:3001/v1/admin/users/set-local-password \
  -H "Content-Type: application/json" \
  -H "x-admin-token: $ADMIN_SYNC_TOKEN" \
  -d '{"email":"usuario@example.com","password":"NuevaClaveTemporal123!"}'
```

## Importar multimedia de Supabase Storage

Cuando Supabase permita acceder a los buckets, usa el procedimiento completo de
`docs/SUPABASE_STORAGE_TO_UBUNTU.md`. El flujo recomendado es:

```bash
docker compose run --rm api npm run import:supabase-storage -- --dry-run
docker compose run --rm api npm run import:supabase-storage
docker compose run --rm api npm run rewrite:supabase-storage-urls -- --dry-run
docker compose run --rm api npm run rewrite:supabase-storage-urls -- --apply
```

## Importar usuarios/perfiles por CSV

Si Supabase esta restringido por API pero puedes exportar CSV desde el
Dashboard, pon los CSV en `imports/` antes de copiar el modulo a Ubuntu.

Despues de copiar los archivos a Ubuntu:

```bash
cd ~/conectate-local-fallback
docker compose up -d --build
docker compose exec -T api node src/import-all-csv.js /imports
docker compose exec -T api node src/import-csv.js auth-users /imports/auth_users.csv
docker compose exec -T api node src/import-csv.js profiles /imports/profiles.csv
```

Notas:

- `auth_users.csv` debe tener al menos `email`. Si trae `id`, se guarda como
  `supabase_user_id`.
- `profiles.csv` puede enriquecer `display_name` y `avatar_url`.
- Las contrasenas de Supabase no se pueden exportar; esos usuarios quedaran en
  `needs_local_password`.
- `import-all-csv.js` crea una copia cruda en el schema `supabase_import`.
  Esta copia conserva todas las columnas como texto para reconstruir APIs
  compatibles despues.

## Endpoints iniciales

- `GET /health`: API viva, sin tocar base de datos.
- `GET /ready`: API + Postgres disponibles.
- `GET /v1/upstream/supabase`: prueba opcional del proveedor anterior solo si
  `LEGACY_SUPABASE_IMPORT_ENABLED=true` y se llenan sus llaves en `.env`.
- `POST /v1/auth/register`: crea usuario local de respaldo.
- `POST /v1/auth/login`: inicia sesion local de respaldo.
- `GET /v1/auth/me`: valida token local.
- `POST /v1/auth/web-login-pairing`: crea, consulta, aprueba, rechaza y
  consume login web por QR.
- `GET /v1/auth/web-sessions`: lista sesiones web activas del usuario.
- `DELETE /v1/auth/web-sessions/:sessionId`: revoca una sesion web especifica.
- `DELETE /v1/auth/web-sessions`: revoca todas las sesiones web activas.
- `POST /v1/auth/change-password`: cambia password con sesion local.
- `POST /v1/auth/password-reset/request`: genera token local de recuperacion.
- `POST /v1/auth/password-reset/confirm`: confirma token y crea password nueva.
- `POST /v1/auth/activate-local-password`: usuario logueado en Supabase activa
  su password local.
- `POST /v1/auth/sync-to-supabase`: usuario local reintenta crear su cuenta en
  Supabase cuando el upstream vuelve.
- `POST /v1/admin/sync/import-supabase-users`: importa usuarios Auth de Supabase.
- `GET /v1/admin/sync/status`: resumen de estados de sincronizacion.

## Recuperacion de contrasena local

En producción el modo predeterminado es `disabled`, que impide prometer un correo
que el servidor no puede enviar. Para activar la recuperación, configura en el
`.env` privado `PASSWORD_RESET_DELIVERY=smtp`, `SMTP_HOST`, `SMTP_PORT` (587 con
STARTTLS o 465 con TLS directo), `SMTP_FROM`, `SMTP_USER` y `SMTP_PASS`. El
servidor envía el código al correo registrado; la respuesta pública nunca
incluye el código. No registres credenciales ni tokens en Git.

En desarrollo, `PASSWORD_RESET_DELIVERY=console` imprime el token en logs de la
API para pruebas manuales:

```bash
docker compose logs -f api
```

Para entornos de prueba donde quieras que la web reciba el token directamente:

```env
PASSWORD_RESET_DELIVERY=response
```

No uses `response` en produccion publica.

## Notas

- No guardes `.env` en Git.
- Cambia `POSTGRES_PASSWORD` si este servidor va a quedar prendido mucho tiempo.
- La base persiste en el volumen Docker `conectate-fallback-db`.
- Documentacion oficial de operacion: `docs/CONECTATE_UBUNTU_OFFICIAL_RUNBOOK_2026.md`.
- Handoff para equipo nuevo: `docs/TEAM_HANDOFF_UBUNTU_2026.md`.
