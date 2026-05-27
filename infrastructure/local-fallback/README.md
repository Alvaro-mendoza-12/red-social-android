# Conectate Local Fallback Server

Servidor secundario local para mantener Conectate operando cuando Supabase
responde con restriccion de cuota, timeout o error temporal.

Este modulo no reemplaza Supabase ni modifica sus credenciales actuales. Es una
pieza aparte: Postgres local + API HTTP local. La app/web se adaptaran despues
para usarla solo cuando Supabase falle.

## Servicios

- `api`: API Node en `http://127.0.0.1:3001`
- `db`: Postgres privado en `127.0.0.1:5433`
- `storage`: volumen local Docker `conectate-fallback-storage` montado en `/data/storage`

## Copiar desde Windows a la VM

Desde CMD o PowerShell, en la raiz del repo:

```powershell
scp -P 2222 -r infrastructure/local-fallback alvaro@127.0.0.1:~/conectate-local-fallback
```

## Levantar en Ubuntu

Dentro del SSH de Ubuntu:

```bash
cd ~/conectate-local-fallback
cp config.example .env
JWT_SECRET=$(openssl rand -hex 32)
ADMIN_SYNC_TOKEN=$(openssl rand -hex 32)
sed -i "s|JWT_SECRET=.*|JWT_SECRET=$JWT_SECRET|" .env
sed -i "s|ADMIN_SYNC_TOKEN=.*|ADMIN_SYNC_TOKEN=$ADMIN_SYNC_TOKEN|" .env
# Opcional, recomendado si usas un dominio fijo de ngrok:
# sed -i "s|PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=https://tu-dominio.ngrok-free.dev|" .env
docker compose up -d --build
docker compose ps
curl http://127.0.0.1:3001/ready
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
ngrok http 3001
```

La URL HTTPS que te entregue ngrok sera la `FALLBACK_API_URL` para web y Android.

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
- `POST /v1/auth/activate-local-password`: usuario logueado en Supabase activa
  su password local.
- `POST /v1/auth/sync-to-supabase`: usuario local reintenta crear su cuenta en
  Supabase cuando el upstream vuelve.
- `POST /v1/admin/sync/import-supabase-users`: importa usuarios Auth de Supabase.
- `GET /v1/admin/sync/status`: resumen de estados de sincronizacion.

## Notas

- No guardes `.env` en Git.
- Cambia `POSTGRES_PASSWORD` si este servidor va a quedar prendido mucho tiempo.
- La base persiste en el volumen Docker `conectate-fallback-db`.
