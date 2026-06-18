# Estado de migracion a Ubuntu

Fecha de creacion: 2026-06-15.
Fecha de ultima revision: 2026-06-18.

Este documento resume la auditoria tecnica actual de la migracion desde Supabase hacia el servidor Ubuntu propio.

## Estado general

La migracion no esta al 100%.

El proyecto ya puede funcionar con Ubuntu como backend primario cuando se configura `FALLBACK_API_URL` en Android y `VITE_FALLBACK_API_URL` en la web, pero todavia existen dependencias, nombres de clases, repositorios y rutas legacy de Supabase en codigo activo.

Estimacion actual: 90-92%.

La parte critica de los modulos pedidos ya tiene ruta Ubuntu en Android y API
local: autenticacion local, perfil, busqueda de personas, feed, comentarios,
momentos, videos, comentarios de video con audio, retos, votos y rankings. Aun
falta ejecutar la prueba completa contra la VM Ubuntu real y eliminar SDKs/
codigo legacy Supabase de forma definitiva.

## Cambios recientes validados

- Android ahora prioriza Ubuntu siempre que `FALLBACK_API_URL` exista.
- Android ya no empaqueta `SUPABASE_URL` ni `SUPABASE_ANON_KEY` desde `secrets.properties`; ambos quedan vacios en `BuildConfig`.
- Web desactiva la creacion del cliente Supabase cuando el backend Ubuntu/fallback esta configurado.
- CSP de Vercel ya no permite `*.supabase.co` en `connect-src`.
- Signaling server usa `FALLBACK_API_URL` para validar sesiones contra Ubuntu.
- La APK dejo de cerrarse al abrir en la prueba real con ADB.
- El cliente web compatible `getDataClient()` ahora sube archivos reales a storage Ubuntu mediante `/storage/v1/object/...`; ya no devuelve exitos falsos en `storage.upload`.
- El feed web usa `shouldUseUbuntuBackend`, asi que carga, publica, sube media y reacciona por Ubuntu cuando el backend propio es primario.
- La mensajeria web privada, de grupos y comunidades usa `shouldUseUbuntuBackend`; con Ubuntu primario carga/envia/sube adjuntos por el servidor propio y no intenta Realtime/RPC de Supabase.
- El build web exige `VITE_FALLBACK_API_URL`; Supabase legacy ya no es aceptado como backend valido para compilar.
- El build Android exige `FALLBACK_API_URL` o `LOCAL_FALLBACK_API_URL` en `secrets.properties`; se elimino la URL ngrok hardcodeada como fallback automatico.
- Se agrego importador reproducible de Supabase Storage hacia storage Ubuntu: `npm run import:supabase-storage`.
- Se agrego reescritor seguro de URLs multimedia Supabase -> Ubuntu con `--dry-run` y `--apply`: `npm run rewrite:supabase-storage-urls`.
- Se documento el procedimiento completo en `docs/SUPABASE_STORAGE_TO_UBUNTU.md`.
- Videos Android usan Ubuntu cuando `FALLBACK_API_URL` esta configurado:
  busqueda, listado, detalle, subida, vistas, likes y comentarios.
- Comentarios de video soportan `content_type`, `media_url` y audios servidos
  desde storage local Ubuntu.
- Retos Android usan Ubuntu para reto activo, retos creados por usuario,
  participaciones, votos, consulta de participacion y polling sin Realtime de
  Supabase.
- Rankings Android usan datos Ubuntu para usuarios, videos y publicaciones.
- Mensajes directos nuevos ya pueden crearse en Ubuntu con
  `POST /v1/data/messages/chats`; se agregaron tablas `fallback_chats` y
  `fallback_chat_participants`.
- Notificaciones Android ahora leen, marcan y crean notificaciones por Ubuntu;
  los tokens FCM se guardan/eliminan en `fallback_fcm_tokens`.
- `directPasswordReset` ya no usa una ruta insegura/inexistente; ahora solicita
  token local y confirma reset por las rutas reales de Ubuntu.
- Se agrego `infrastructure/local-fallback/scripts/verify-ubuntu-modules.sh`
  para probar auth, storage, perfil, busqueda, posts, comentarios, audios,
  videos, momentos, retos y votos desde la VM.
- Se agrego `infrastructure/local-fallback/scripts/verify-ubuntu-production.sh`
  para validar Docker, Postgres, storage, proxy, realtime/signaling y matriz
  endpoint/Android/Postgres/storage por modulo.
- Se agrego `docs/UBUNTU_PRODUCTION_READINESS.md` como matriz de produccion y
  limpieza controlada.
- Se agrego `docs/CONECTATE_UBUNTU_OFFICIAL_RUNBOOK_2026.md` como documento
  oficial de arquitectura, despliegue, verificacion y operacion.
- El login web por QR ya registra sesiones web reales en
  `fallback_web_sessions`; Android puede listar/cerrar una sesion web o cerrar
  todas desde `Perfil > Configuracion > Dispositivos vinculados`.
- Los JWT web nuevos incluyen `sid`; Ubuntu rechaza requests con sesiones web
  revocadas usando `WEB_SESSION_REVOKED` y la web limpia su sesion local.

## Validaciones locales recientes

- Backend Node: `npm run check` en `infrastructure/local-fallback/api` paso.
- Sintaxis Node: `server.js`, `storage-routes.js`, `moments-routes.js`,
  `postgrest-compat-routes.js`, `rewrite-supabase-storage-urls.js` y
  `data-routes.js` pasaron `node --check`.
- Android: `:app:compileDebugKotlin` paso usando JDK 17 local.
- Web: `npm run build` paso en `Conectate-web`.
- `git diff --check` no mostro errores de whitespace; solo avisos de cambio
  LF/CRLF propios del entorno Windows.
- No se pudo validar la VM viva desde esta sesion porque SSH a
  `127.0.0.1:2222` devolvio `Connection refused`.
- Docker CLI existe en Windows, pero el daemon Docker Desktop no estaba
  corriendo, asi que la prueba de contenedores local queda pendiente.

Comando Android validado:

```powershell
$env:JAVA_HOME='D:\red social - copia\tools\jdk-17\jdk-17.0.18+8'
.\gradlew.bat --no-daemon --console=plain "-Dorg.gradle.workers.max=1" "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin --stacktrace
```

## Crash Android corregido

El cierre al abrir era causado por ViewModels usados con `hiltViewModel()` sin anotacion Hilt o sin constructor inyectable.

Archivos corregidos:

- `app/src/main/java/conectate/com/presentation/common/ConnectivityViewModel.kt`
- `app/src/main/java/conectate/com/presentation/auth/AuthViewModel.kt`
- `app/src/main/java/conectate/com/presentation/common/NotificationAccountBridgeViewModel.kt`

Resultado de prueba:

- APK instalada con `adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- App lanzada con `adb shell am start -n conectate.com/.MainActivity`.
- Proceso vivo despues de abrir: `pidof conectate.com` devolvio un PID.
- Logcat mostro `Ubuntu is primary backend - Skipping Supabase client initialization`.
- No aparecio `AndroidRuntime FATAL EXCEPTION` en la prueba posterior al arreglo.

## Referencias Supabase que aun existen

Android:

- Dependencias `io.github.jan-tennert.supabase` en `gradle/libs.versions.toml` y `app/build.gradle.kts`.
- Paquete activo `app/src/main/java/conectate/com/data/supabase`.
- Repositorios aun con llamadas a `supabaseConfig.client`, `supabaseConfig.httpClient`, `postgrest`, `Realtime` y rutas `/rest/v1`.
- Storage, notificaciones, videos, perfil, wallet, llamadas y partes de mensajeria todavia conservan compatibilidad Supabase.

Web:

- Dependencia `@supabase/supabase-js` en `Conectate-web/package.json`.
- Cliente legacy en `Conectate-web/src/core/supabase.ts`.
- Muchas paginas aun tienen fallback o rutas directas a Supabase cuando Ubuntu no cubre una funcion.
- Realtime legacy Supabase sigue presente en varias pantallas.

Backend Ubuntu:

- `infrastructure/local-fallback` ya contiene API, Postgres, storage local, realtime gateway y scripts.
- `LEGACY_SUPABASE_IMPORT_ENABLED=false` mantiene desactivada la importacion legacy por defecto.
- Todavia hay compatibilidad PostgREST para reducir cambios en clientes, pero eso no significa dependencia externa de Supabase.
- La API Ubuntu crea/actualiza tablas locales de videos, comentarios de video,
  retos, participaciones, votos, chats privados, participantes, notificaciones
  y tokens FCM con `CREATE TABLE IF NOT EXISTS` y
  `ALTER TABLE ... IF NOT EXISTS`, para que una VM ya creada pueda actualizarse
  sin borrar datos.
- Los SQL Supabase de retos fueron revisados. No se aplican tal cual en Ubuntu
  porque contienen `auth.uid()`, RLS y publicaciones `supabase_realtime`, pero
  el backend local replica las piezas necesarias: catalogo, reto activo,
  participaciones, voto unico por reto y conteo de votos.

## Variables obligatorias

En Windows para Android:

```properties
FALLBACK_API_URL=https://tu-url-ngrok-o-dominio
SUPABASE_URL=
SUPABASE_ANON_KEY=
```

En Vercel para web:

```text
VITE_FALLBACK_API_URL=https://tu-url-ngrok-o-dominio
VITE_SUPABASE_URL=
VITE_SUPABASE_ANON_KEY=
```

Si `VITE_FALLBACK_API_URL` falta, el build web debe fallar. Si `SUPABASE_URL` o `SUPABASE_ANON_KEY` aparecen en `secrets.properties`, Android los ignora y empaqueta valores vacios.

En Ubuntu:

```text
POSTGRES_PASSWORD=...
JWT_SECRET=...
ADMIN_SYNC_TOKEN=...
PUBLIC_BASE_URL=https://tu-url-ngrok-o-dominio
LEGACY_SUPABASE_IMPORT_ENABLED=false
SUPABASE_URL=
SUPABASE_ANON_KEY=
SUPABASE_SERVICE_ROLE_KEY=
SUPABASE_SECRET_KEY=
SUPABASE_STORAGE_BUCKETS=
SUPABASE_STORAGE_DRY_RUN=false
SUPABASE_STORAGE_OVERWRITE=false
SUPABASE_STORAGE_PAGE_SIZE=100
SUPABASE_STORAGE_SOURCE_BASE_URLS=
SUPABASE_STORAGE_REWRITE_APPLY=false
```

## Pasos para mini PC

1. Instalar Ubuntu Server actualizado.
2. Instalar Docker, Docker Compose, Git, Node.js LTS, nginx y ngrok o dominio publico.
3. Copiar `infrastructure/local-fallback` y `signaling-server`.
4. Crear `.env` desde `infrastructure/local-fallback/config.example`.
5. Definir secretos nuevos para `POSTGRES_PASSWORD`, `JWT_SECRET` y `ADMIN_SYNC_TOKEN`.
6. Levantar servicios con `docker compose up -d --build`.
7. Ejecutar `bash scripts/repair-ubuntu-primary.sh`.
8. Exponer el puerto `3080` con ngrok o nginx HTTPS.
9. Actualizar `VITE_FALLBACK_API_URL` en Vercel y `FALLBACK_API_URL` en `secrets.properties`.
10. Recompilar e instalar Android.

## Prueba completa en Ubuntu

Cuando la VM responda por SSH, copia los cambios desde Windows:

```powershell
ssh -p 2222 alvaro@127.0.0.1 "mkdir -p ~/conectate-local-fallback ~/signaling-server"
scp -P 2222 -r infrastructure/local-fallback/* alvaro@127.0.0.1:~/conectate-local-fallback/
scp -P 2222 -r signaling-server/* alvaro@127.0.0.1:~/signaling-server/
```

Dentro de Ubuntu:

```bash
cd ~/conectate-local-fallback
bash scripts/sync-env-from-example.sh
PUBLIC_BASE_URL="https://tu-url-ngrok-o-dominio" bash scripts/repair-ubuntu-primary.sh
API_URL="http://127.0.0.1:3080" bash scripts/verify-ubuntu-modules.sh
API_URL="http://127.0.0.1:3080" STRICT_PRODUCTION=true bash scripts/verify-ubuntu-production.sh
PUBLIC_BASE_URL="https://tu-url-ngrok-o-dominio" bash scripts/verify-ubuntu-storage.sh
docker compose ps
docker compose logs --tail=120 api edge signaling realtime-gateway
```

Resultado esperado:

- `docker compose ps` muestra `api`, `db`, `edge`, `signaling` y servicios
  relacionados levantados.
- `verify-ubuntu-modules.sh` termina con `LISTO: smoke test Ubuntu completo.`
- `verify-ubuntu-production.sh` genera `production-readiness-report.md` sin
  filas `FAIL`.
- `verify-ubuntu-storage.sh` confirma que las URLs publicas apuntan a Ubuntu y
  no a Supabase.
- Los logs no muestran reinicios constantes, errores de migracion ni fallos de
  permisos en `/data/storage`.

Antes de produccion reemplaza secretos debiles:

```bash
openssl rand -hex 32
nano ~/conectate-local-fallback/.env
docker compose up -d --build
```

Valores obligatorios a cambiar: `POSTGRES_PASSWORD`, `JWT_SECRET`,
`ADMIN_SYNC_TOKEN` y `PUBLIC_BASE_URL`.

## Pendiente para 100%

- Remover SDKs Supabase de Android y web.
- Ejecutar en Ubuntu la importacion real de buckets Supabase Storage ahora que la cuota fue desbloqueada.
- Ejecutar el reescritor de URLs de storage despues de verificar que los archivos ya existen en Ubuntu.
- Sustituir las llamadas legacy `supabase.from`, `supabase.storage`,
  `supabase.auth`, `supabase.rpc` y `supabase.realtime` que queden fuera de los
  flujos ya migrados a Ubuntu.
- Ejecutar `verify-ubuntu-modules.sh` contra la VM real y despues contra la mini
  PC final.
- Completar endpoints Ubuntu de mensajeria avanzada: polls, reacciones, view-once, preferencias, traduccion y programacion de mensajes.
- Migrar repositorios Android todavia directos a Supabase: friends, advanced
  messaging, stickers, badges, creator/wallet/monetizacion, parte de calls y
  administracion avanzada.
- Migrar pantallas web restantes que aun usan `supabase.from`, `supabase.rpc`,
  `supabase.storage` o canales Realtime legacy.
- Probar con cuentas reales en Ubuntu: login, registro, reset de password,
  perfil, foto, posts, comentarios, likes, seguidores, mensajes, realtime,
  notificaciones, voz, video, grupos, reportes, busqueda, videos y storage.
- Eliminar o archivar backups/documentos legacy para que la busqueda global no mezcle codigo actual con historial.
