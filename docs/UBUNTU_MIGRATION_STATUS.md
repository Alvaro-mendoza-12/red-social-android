# Estado de migracion a Ubuntu

Este documento resume la auditoria tecnica actual de la migracion desde Supabase hacia el servidor Ubuntu propio.

## Estado general

La migracion no esta al 100%.

El proyecto ya puede funcionar con Ubuntu como backend primario cuando se configura `FALLBACK_API_URL` en Android y `VITE_FALLBACK_API_URL` en la web, pero todavia existen dependencias, nombres de clases, repositorios y rutas legacy de Supabase en codigo activo.

Estimacion actual: 80-82%.

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

## Pendiente para 100%

- Remover SDKs Supabase de Android y web.
- Sustituir todas las llamadas `supabase.from`, `supabase.storage`, `supabase.auth`, `supabase.rpc` y `supabase.realtime` por APIs Ubuntu.
- Completar endpoints Ubuntu para cada modulo restante.
- Completar endpoints Ubuntu de mensajeria avanzada: polls, reacciones, view-once, preferencias, traduccion y programacion de mensajes.
- Probar login, registro, reset de password, perfil, foto, posts, comentarios, likes, seguidores, mensajes, realtime, notificaciones, voz, video, grupos, reportes, busqueda, videos y storage con cuentas reales en Ubuntu.
- Eliminar o archivar backups/documentos legacy para que la busqueda global no mezcle codigo actual con historial.
