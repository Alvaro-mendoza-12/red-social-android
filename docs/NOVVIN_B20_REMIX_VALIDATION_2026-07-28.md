# Novvin B20 — remixes con atribución

Fecha de validación: 2026-07-28

## Resultado

Se implementó el bloque B20 del benchmark sin eliminar funcionalidades existentes:

- permiso de remix por publicación, desactivado por defecto;
- audiencia configurable: todos o solo seguidores;
- atribución visible al autor y a la publicación original;
- cadena persistente con origen, raíz y profundidad;
- revocación que bloquea remixes futuros sin eliminar derivados existentes;
- conservación de la atribución histórica aunque se elimine la publicación original;
- controles Android para permitir/desactivar remixes y crear un remix con comentario;
- mensajes comprensibles para restricciones de permiso, audiencia y visibilidad.

## Causa encontrada

El modelo de publicación no guardaba permiso, audiencia ni origen de un remix. Por
ello no era posible aplicar permisos reales, revocar de forma segura ni mantener
una atribución verificable desde el servidor.

## Archivos principales

- `infrastructure/local-fallback/api/src/data-routes.js`
- `infrastructure/local-fallback/api/scripts/verify-post-remix-e2e.js`
- `app/src/main/java/conectate/com/data/model/Post.kt`
- `app/src/main/java/conectate/com/data/localfallback/LocalFallbackApiClient.kt`
- `app/src/main/java/conectate/com/data/repository/PostRepository.kt`
- `app/src/main/java/conectate/com/presentation/components/PostCard.kt`
- `app/src/main/java/conectate/com/presentation/social/CreatePostScreen.kt`
- `app/src/main/java/conectate/com/presentation/social/SocialViewModel.kt`

## Verificación

- API `npm run check`: aprobada.
- API `npm run test:data-routes`: aprobada.
- API `npm run test:security`: aprobada.
- Android `:app:compileDebugKotlin`: aprobada.
- Android `UserFacingErrorMessageTest`: aprobada.
- Android Debug: compilado.
- Android Release: compilado con R8 y `lintVitalRelease`; APK sin firma oficial.
- `git diff --check`: limpio.
- Canary público con tres cuentas ficticias: aprobado.
  - permiso por publicación;
  - audiencia de seguidores;
  - atribución y raíz persistentes;
  - revocación solo para remixes futuros;
  - derivado conservado tras borrar el original;
  - mutación de permiso ajena bloqueada.
- Limpieza posterior en PostgreSQL: `users=0`, `posts=0`.

## Entrega

- Commit: `3d181fbbfdea43a485648adab5467cceb822cf7e`
- Release servidor: `conectate-mini-pc-20260728T204257Z`
- Backup previo al despliegue final:
  `/home/alvaro/conectate-backups/20260728T204259Z`
- APK Debug:
  `app/build/outputs/apk/debug/app-debug.apk`
- APK Release sin firmar:
  `app/build/outputs/apk/release/app-release-unsigned.apk`

La interacción visual se compiló en Android, pero no se declara validada
físicamente en un teléfono porque este bloque no incluyó una sesión ADB.
