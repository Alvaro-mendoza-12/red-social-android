# Migracion Ubuntu Laptop a Mini PC

Esta guia deja preparado el paso del backend Conectate desde el Ubuntu actual
en la laptop hacia la mini PC. La meta de produccion es que Supabase quede vacio
en runtime y que web/Android usen solo `FALLBACK_API_URL`.

## Estado objetivo

- Backend API: `infrastructure/local-fallback/api`
- Postgres: contenedor Docker privado
- Storage: volumen Docker local para uploads
- Realtime/voz/llamadas: `signaling-server` detras del proxy local
- Entrada publica temporal: ngrok apuntando al puerto `3080`
- Entrada publica final: dominio HTTPS apuntando al puerto `3080`

## Variables obligatorias

Crea `~/conectate-local-fallback/.env` en la mini PC con este formato:

```env
POSTGRES_PASSWORD=cambia-esto
FALLBACK_API_PORT=3001
FALLBACK_EDGE_PORT=3080
FALLBACK_REALTIME_PORT=3002
FALLBACK_DB_PORT=5433
PUBLIC_BASE_URL=https://tu-url-publica
SIGNALING_CONTEXT=../signaling-server
JWT_SECRET=pega-un-hex-de-32-bytes
JWT_ISSUER=conectate-local-fallback
JWT_EXPIRES_IN=7d
CORS_ORIGIN=*
SIGNALING_ALLOWED_ORIGINS=*
PASSWORD_RESET_DELIVERY=console
PASSWORD_RESET_TOKEN_TTL_MINUTES=30
LEGACY_SUPABASE_IMPORT_ENABLED=false
SUPABASE_URL=
SUPABASE_ANON_KEY=
SUPABASE_SERVICE_ROLE_KEY=
SUPABASE_SECRET_KEY=
ADMIN_SYNC_TOKEN=pega-un-hex-de-32-bytes
ADMIN_EMAILS=gamergamerff004@gmail.com,alvaromendozamarin004@gmail.com,gamergamerff017@gmail.com
```

Genera secretos en Ubuntu:

```bash
openssl rand -hex 32
```

## Preparar la mini PC

```bash
sudo apt update
sudo apt install -y git curl ca-certificates docker.io docker-compose-plugin nginx
sudo usermod -aG docker "$USER"
newgrp docker
```

Instala ngrok solo si seguira siendo la entrada publica temporal:

```bash
curl -sSL https://ngrok-agent.s3.amazonaws.com/ngrok.asc \
  | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null
echo "deb https://ngrok-agent.s3.amazonaws.com bookworm main" \
  | sudo tee /etc/apt/sources.list.d/ngrok.list
sudo apt update
sudo apt install -y ngrok
ngrok config add-authtoken "TU_TOKEN_DE_NGROK"
```

## Copiar codigo desde Windows

Desde PowerShell en la PC Windows, en la raiz del repo:

```powershell
ssh usuario@IP_MINI_PC "mkdir -p ~/conectate-local-fallback ~/signaling-server"
scp -r infrastructure/local-fallback/* usuario@IP_MINI_PC:~/conectate-local-fallback/
scp -r signaling-server/* usuario@IP_MINI_PC:~/signaling-server/
```

Si la mini PC esta en otra red, usa la IP local cuando estes alla o configura
Tailscale/ZeroTier para evitar exponer SSH directamente a internet.

## Levantar servicios

Dentro de la mini PC:

```bash
cd ~/conectate-local-fallback
cp config.example .env
nano .env
bash scripts/repair-ubuntu-primary.sh
docker compose ps
curl -sS http://127.0.0.1:3080/health
curl -sS http://127.0.0.1:3080/ready
```

Exponer con ngrok:

```bash
ngrok http 3080
```

La URL HTTPS resultante debe ponerse en:

- Android: `secrets.properties` como `FALLBACK_API_URL=...`
- Web/Vercel: variable `VITE_FALLBACK_API_URL=...`
- No llenar `SUPABASE_URL` ni `SUPABASE_ANON_KEY` en clientes.
- Mantener `LEGACY_SUPABASE_IMPORT_ENABLED=false` salvo durante una importacion puntual.

## Migrar datos desde la laptop Ubuntu

En la laptop Ubuntu actual:

```bash
cd ~/conectate-local-fallback
docker compose exec -T db pg_dump -U conectate conectate_fallback > conectate_fallback.sql
docker run --rm -v conectate-fallback-storage:/data/storage -v "$PWD":/backup alpine \
  tar -czf /backup/conectate-storage.tar.gz -C /data/storage .
```

Copia a la mini PC:

```bash
scp conectate_fallback.sql conectate-storage.tar.gz usuario@IP_MINI_PC:~/
```

Restaurar en la mini PC:

```bash
cd ~/conectate-local-fallback
docker compose up -d db
cat ~/conectate_fallback.sql | docker compose exec -T db psql -U conectate -d conectate_fallback
docker run --rm -v conectate-fallback-storage:/data/storage -v "$HOME":/backup alpine \
  sh -c "cd /data/storage && tar -xzf /backup/conectate-storage.tar.gz"
docker compose up -d --build
```

## Checklist final

- `docker compose ps` muestra `api`, `db`, `edge` y `signaling` arriba.
- `GET /health` responde OK por `http://127.0.0.1:3080`.
- `GET /ready` responde OK por `http://127.0.0.1:3080`.
- Registro/login funcionan contra `FALLBACK_API_URL`.
- Upload de imagen/video devuelve URL bajo tu backend, no bajo `supabase.co`.
- Android tiene `SUPABASE_URL=` y `SUPABASE_ANON_KEY=` vacios.
- Vercel tiene `VITE_FALLBACK_API_URL` apuntando a la URL publica activa.
- Si necesitas importar usuarios antiguos, habilita temporalmente `LEGACY_SUPABASE_IMPORT_ENABLED=true`,
  llena las llaves legacy solo en el servidor Ubuntu, importa, vuelve a dejarlo en `false`
  y reinicia los contenedores.
