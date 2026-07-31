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
- `edge`: proxy local en `http://127.0.0.1:3080` para exponer API, storage y Socket.IO con una sola URL de ngrok

## Modo backend principal

Para web, configura solo:

```env
VITE_FALLBACK_API_URL=https://tu-url-ngrok.ngrok-free.dev
```

Para Android, en `secrets.properties`:

```properties
FALLBACK_API_URL=https://tu-url-ngrok.ngrok-free.dev
SUPABASE_URL=
SUPABASE_ANON_KEY=
```

Voz, llamadas y audio usan la misma URL cuando ngrok apunta al puerto `3080`.

## Copiar desde Windows a la VM

Desde CMD o PowerShell, en la raiz del repo:

```powershell
ssh -p 2222 alvaro@127.0.0.1 "mkdir -p ~/conectate-local-fallback"
scp -P 2222 -r infrastructure/local-fallback/* alvaro@127.0.0.1:~/conectate-local-fallback/
scp -P 2222 -r signaling-server alvaro@127.0.0.1:~/signaling-server
```

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
PUBLIC_BASE_URL=https://tu-dominio.ngrok-free.dev bash scripts/repair-ubuntu-primary.sh
```

Si todavia no tienes dominio fijo, ejecuta:

```bash
cd ~/conectate-local-fallback
bash scripts/sync-env-from-example.sh
bash scripts/repair-ubuntu-primary.sh
```

`sync-env-from-example.sh` agrega claves nuevas de `config.example` a `.env`
sin borrar tus valores actuales, contrasenas ni tokens.

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
PUBLIC_BASE_URL="https://tu-dominio.ngrok-free.dev" bash scripts/verify-ubuntu-storage.sh
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

Cambia `POSTGRES_PASSWORD`, `JWT_SECRET`, `ADMIN_SYNC_TOKEN` y
`PUBLIC_BASE_URL`. El resultado esperado es que `docker compose ps` muestre los
servicios estables y `GET /ready` responda correctamente:

```bash
curl -sS http://127.0.0.1:3080/ready
```

## Probar login local

```bash
curl -sS -X POST http://127.0.0.1:3001/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alvaro@example.local","password":"Conectate123!","displayName":"Alvaro"}'

curl -sS -X POST http://127.0.0.1:3001/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alvaro@example.local","password":"Conectate123!"}'
```

## Exponer con ngrok

Instalacion oficial via apt:

```bash
curl -sSL https://ngrok-agent.s3.amazonaws.com/ngrok.asc \
  | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null \
  && echo "deb https://ngrok-agent.s3.amazonaws.com bookworm main" \
  | sudo tee /etc/apt/sources.list.d/ngrok.list \
  && sudo apt update \
  && sudo apt install -y ngrok
```

Luego agrega tu token de ngrok:

```bash
ngrok config add-authtoken "TU_TOKEN_DE_NGROK"
ngrok http 3080
```

La URL HTTPS que te entregue ngrok sera la `FALLBACK_API_URL` para web y Android.
Debe apuntar al puerto `3080`, no al `3001`, para que tambien funcionen voz,
llamadas, audio y WebSocket.

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

Activar password local manualmente para un usuario importado:

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

Por defecto `PASSWORD_RESET_DELIVERY=console`: el token se imprime en logs de la
API para uso manual durante la migracion.

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
