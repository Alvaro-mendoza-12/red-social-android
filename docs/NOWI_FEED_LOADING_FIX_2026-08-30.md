# NoWi — corrección de carga de feeds (2026-08-30)

## Síntoma

Al abrir Videos o Social, la pantalla podía quedar vacía o mostrando el indicador de carga indefinidamente.

## Causa

- La primera consulta esperaba el servicio de recomendaciones antes de intentar mostrar el catálogo local o el feed público.
- Videos llamaba a `getVideos` con `emitLocalSnapshotFirst = false`.
- Social no conservaba un snapshot local del feed por cuenta y modo.
- La UI no tenía un límite visual para una solicitud de red que no terminara.

## Comportamiento corregido

- El último catálogo de Videos y el último feed de Social se muestran desde almacenamiento local cuando existen.
- Las recomendaciones tienen un límite de 5 segundos; después se usa el feed público/legacy.
- Un watchdog de 12 segundos libera la UI si todos los endpoints están bloqueados. La solicitud puede terminar después y actualizar el feed si aún corresponde a la cuenta/sesión activa.
- Social muestra estado vacío, error o reintento en lugar de dejar una zona negra sin explicación.
- La caché se separa por cuenta para evitar mezclar información entre sesiones.

## Archivos involucrados

- `app/src/main/java/conectate/com/data/repository/PostRepository.kt`
- `app/src/main/java/conectate/com/data/repository/VideoRepository.kt`
- `app/src/main/java/conectate/com/presentation/social/SocialViewModel.kt`
- `app/src/main/java/conectate/com/presentation/social/SocialScreen.kt`
- `app/src/main/java/conectate/com/presentation/video/VideoFeedViewModel.kt`

## Validación

- `:app:compileDebugKotlin` — correcto con JDK 21.
- `:app:testDebugUnitTest` — correcto.
- `:app:assembleDebug` — correcto.
- APK generado: `app/build/outputs/apk/debug/app-debug.apk`.

La prueba visual en dispositivo queda pendiente porque este entorno no tiene `adb` disponible.
