# Conectate Ubuntu Backend

Servidor local para mantener Conectate operando desde infraestructura propia
Ubuntu. Originalmente nacio como fallback ante restricciones de Supabase, pero
ahora tambien puede usarse como backend principal para autenticacion, datos
basicos, storage local y pantallas web/Android ya migradas.

El puente con Supabase queda como herramienta opcional de importacion/sync. El
servidor puede arrancar sin `SUPABASE_URL`, sin `SUPABASE_SERVICE_ROLE_KEY` y
sin el SDK de Supabase.

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
PUBLIC_BASE_URL=https://tu-dominio.ngrok-free.dev bash scripts/repair-ubuntu-primary.sh
```

Si todavia no tienes dominio fijo, ejecuta:

```bash
cd ~/conectate-local-fallback
bash scripts/repair-ubuntu-primary.sh
```

Si `api` queda en `unhealthy` o `Restarting`, ejecuta el diagnostico:

```bash
cd ~/conectate-local-fallback
bash scripts/diagnose-api.sh
```

El diagnostico valida si quedo codigo viejo con rutas incompatibles de Express,
revisa sintaxis dentro de Docker, imprime estado de Postgres y muestra los logs
exactos del contenedor `api`.

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
SUPABASE_SECRET_KEY=sb_secret_xxx
# o, si usas llave legacy:
SUPABASE_SERVICE_ROLE_KEY=eyJ...
```

Tambien puedes poner `SUPABASE_URL` y `SUPABASE_ANON_KEY` en ese mismo `.env`
para que el servidor pueda probar el estado del upstream.

Despues reinicia la API:

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
- `GET /v1/upstream/supabase`: prueba opcional del Supabase original si se
  llenan `SUPABASE_URL` y `SUPABASE_ANON_KEY` en `.env`.
- `POST /v1/auth/register`: crea usuario local de respaldo.
- `POST /v1/auth/login`: inicia sesion local de respaldo.
- `GET /v1/auth/me`: valida token local.
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
