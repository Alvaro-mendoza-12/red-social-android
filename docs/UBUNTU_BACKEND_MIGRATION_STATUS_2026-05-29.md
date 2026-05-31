# Ubuntu Backend Migration Status - 2026-05-29

## Objetivo

Migrar Conectate desde Supabase Cloud hacia infraestructura propia Ubuntu, sin
bloquear web ni Android cuando no existan variables `SUPABASE_*`.

## Estado de esta etapa

Esta etapa deja una base operativa para usar el backend Ubuntu como principal en
web y como fuente de tokens para voz/video:

- `infrastructure/local-fallback` pasa a documentarse como backend Ubuntu.
- La API Node arranca sin SDK de Supabase y sin credenciales Supabase.
- Auth local cubre registro, login, sesion actual, cambio de password y reset de
  password por token local.
- Storage local mantiene subida/descarga por buckets propios.
- La web permite login/registro con solo `VITE_FALLBACK_API_URL`.
- El signaling server acepta tokens locales validandolos contra
  `/v1/auth/me`.

## Brechas reales pendientes

No se puede marcar la migracion completa todavia. Persisten dependencias directas
a Supabase/PostgREST/RPC/Realtime en:

- Android `app/src/main/java`: repositorios de feed, posts, comments, videos,
  notificaciones, monetizacion, follows, grupos, comunidades, retos, lives y
  varias piezas realtime.
- Web `Conectate-web/src`: pantallas de administracion, notificaciones,
  social/follows, busqueda, retos, momentos, videos y varias rutas de detalle.
- `signaling-server`: ya acepta auth local, pero aun mantiene puente Supabase
  opcional para sincronizar conteos/metadatos cuando se configuran credenciales.
- `sql/` conserva scripts historicos Supabase. Deben convertirse a migraciones
  Postgres propias antes de retirar por completo el contrato antiguo.

## Ruta recomendada siguiente

1. Consolidar `infrastructure/local-fallback/api/src/data-routes.js` como API
   publica del backend principal: posts, comments, reactions, follows, search,
   reports, notifications, moderation, groups, communities, videos y live rooms.
2. Cambiar Android y web para consumir esas rutas por repositorio/pantalla, no
   con llamadas directas a `rest/v1`/RPC.
3. Reemplazar Supabase Realtime por Socket.io/eventos propios:
   mensajes privados, notificaciones, salas de voz, llamadas, grupos y
   comunidades.
4. Convertir `sql/release-manifest.txt` a migraciones Postgres nativas del
   backend Ubuntu.
5. Ejecutar pruebas funcionales reales con dos usuarios, web + APK, usando la
   URL publica de ngrok o dominio final.

## Verificaciones de esta etapa

- `npm --prefix infrastructure/local-fallback/api run check`: PASS.
- `node --check signaling-server/index.js`: PASS.
- `npm --prefix Conectate-web run build`: PASS.

## Comandos SSH base

```bash
cd ~/conectate-local-fallback
cp config.example .env
JWT_SECRET=$(openssl rand -hex 32)
ADMIN_SYNC_TOKEN=$(openssl rand -hex 32)
sed -i "s|JWT_SECRET=.*|JWT_SECRET=$JWT_SECRET|" .env
sed -i "s|ADMIN_SYNC_TOKEN=.*|ADMIN_SYNC_TOKEN=$ADMIN_SYNC_TOKEN|" .env
sed -i "s|PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=https://TU-NGROK.ngrok-free.dev|" .env
docker compose up -d --build
curl http://127.0.0.1:3001/ready
```

Para reset de password manual:

```bash
docker compose logs -f api
```

Para signaling:

```bash
cd ~/conectate-signaling-server
printf '\nLOCAL_FALLBACK_API_URL=https://TU-NGROK.ngrok-free.dev\n' >> .env
npm install
npm start
```
