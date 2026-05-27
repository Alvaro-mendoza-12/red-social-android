# Conectate Production Readiness Audit - 2026-05-14

## Executive Status

Conectate ya compila y tiene modulos reales para autenticacion, perfil, ajustes, feed social, videos, mensajes, llamadas, salas de voz, lives, notificaciones, wallet, monetizacion y SQL Supabase. No debe describirse todavia como "100% produccion global": el codigo es grande, el worktree tiene muchas ediciones historicas y el build aun muestra advertencias en modulos reales de mensajeria, social, voz y video.

## Referencias Usadas

- Android Architecture: UI layer + data layer, UDF, ViewModels, repositories, coroutines/flows, DI.
- Android Offline First: lectura desde fuente local, cola de escrituras, WorkManager y sincronizacion con red.
- Android Baseline Profiles: optimizacion de arranque y rutas criticas para reducir jank.
- Supabase RLS/Reatime: RLS por tabla y autorizacion explicita de canales realtime.
- TikTok/Instagram/WhatsApp/Discord: privacidad granular, estado/presencia, descarga de datos, cuenta privada/publica, mensajes, actividad, status y controles de perfil.

## Cambios Aplicados En Este Pase

- `app/src/main/res/xml/file_paths.xml`: se elimino `external-path "."`; el FileProvider queda limitado a cache. Esto reduce superficie de exposicion al compartir URIs.
- Se elimino la pantalla antigua `presentation.settings.SettingsScreen` y su `SettingsViewModel`; la ruta real queda consolidada en `presentation.profile.SettingsScreen`.
- Se retiraron stubs y fachadas no conectadas de tipo `Fix`, `Fixed`, `Refactored`, "enterprise", "production optimizer", "testing suite" y pseudo CI/CD que daban falsa confianza y ensuciaban Hilt/compilacion.
- `app/src/main/java/conectate/com/data/cache/UpstashCacheService.kt`: cuando Upstash no esta configurado, ahora actua como no-op y evita llamadas de red invalidas.
- `app/src/main/java/conectate/com/SocialApp.kt`: se quitaron imports/campos de optimizador de produccion desactivado.
- `sql/deploy_complete_unified.sql`: se agrego `NOTIFY pgrst, 'reload schema';` para que PostgREST refresque RPCs/tablas despues del deploy.
- `sql/deploy_complete_unified.sql` y `sql/hotfixes/supabase_hotfix_secure_notifications_read_rpc_2026.sql`: `secure_mark_notifications_read` ahora se elimina antes de recrearse y devuelve `INTEGER`.
- `app/src/main/AndroidManifest.xml`: se retiro `RECEIVE_BOOT_COMPLETED` y el `BootReceiver` exportado; era solo logging de arranque y aumentaba superficie de ataque sin aportar una funcion real al usuario.
- `app/src/main/java/conectate/com/auth/twoFactor/TwoFactorAuthManager.kt`: el 2FA quedo desacoplado de `AuthService` para pruebas, con almacenamiento por identidad activa y refresh de estado al cambiar sesion.
- `app/src/test/java/conectate/com/auth/twoFactor/TwoFactorAuthManagerTest.kt`: se reemplazaron pruebas obsoletas/no compilables por una suite Robolectric actual que cubre generacion, activacion, desactivacion, backup codes, lockout y ausencia de cuenta activa.
- `app/build.gradle.kts`: se elimino el exclude que ocultaba la compilacion de tests `auth/twoFactor`; la suite vuelve a participar en el pipeline.
- `app/src/main/java/conectate/com/presentation/theme/Type.kt`: se normalizo `letterSpacing` a `0.sp` en la tipografia base y se alineo el contrato runtime de fuentes.

## Estado Por Modulo

### Autenticacion y cuenta

Existe login, registro, sesion guardada, cambio de cuenta, QR/web login, 2FA, suspension backend y borrado de cuenta por RPC. Riesgos pendientes: pruebas E2E de recuperacion de sesion, expiracion de refresh token, borrado de cuenta con datos reales y auditoria de permisos de backup.

### Perfil y ajustes

El perfil ya contiene banner, avatar, estado, bio, intereses, juegos, musica, libro, deporte, carrera, horario, privacidad y edicion completa. Ajustes avanzados cubren cuenta, seguridad, privacidad, notificaciones, almacenamiento, apariencia, idioma, accesibilidad y ayuda. La pantalla de ajustes quedo consolidada en el modulo activo de perfil. Riesgos pendientes: E2E de cuenta/privacidad y validacion visual en telefono real.

### Social feed

Hay publicaciones, reacciones tipadas, comentarios, momentos, rankings, solicitudes de amistad, notificaciones y highlights. Riesgos pendientes: pruebas de comentarios anidados, control antispam, moderacion real, paginacion robusta y cache local canonica por Room.

### Videos

Hay feed, subida, editor, limite de duracion, detector de marca de agua, comentarios, reportes, likes, guardado/descarga, hashtags y piezas de duet/stitch. Riesgos pendientes: medir jank del feed vertical con Macrobenchmark, validar memoria en gama baja, centralizar prefetch/cache de video y hacer pruebas con archivos grandes/corruptos.

### Mensajes, llamadas y salas

Hay chats privados, grupos, comunidades, mensajes avanzados, reacciones, respuestas, busqueda, llamadas WebRTC, historial, servicios foreground, salas de voz y realtime. Riesgos pendientes: pruebas de reconexion, pruebas de llamadas con red degradada, permisos de Android 14/15, y cola offline para mensajes no enviados.

### Lives y monetizacion

Hay lives, discovery, host/viewer, chat, gifts, wallet, premium, ads, programa de creadores y centro de monetizacion. Riesgos pendientes: liquidacion contable, reconciliacion de transacciones, control antifraude, rate limit por RPC y panel operativo.

### Backend Supabase

El deploy unificado crea tablas base, RLS, RPCs, triggers, indices, notificaciones realtime, perfiles, wallet, gifts y hashtags. Riesgos pendientes: pruebas SQL automatizadas, migraciones ordenadas, seeds por ambiente, rollback, y no depender de ejecutar manualmente hotfixes sueltos.

## Riesgos Tecnicos Principales

1. Quedan advertencias en modulos reales conectados, especialmente `messages`, `social`, `voicerooms`, `videos` y algunos repositorios.
2. Falta una bateria E2E automatizada para flujos criticos: login, feed, publicar, comentar, mensaje, llamada, live, pago/gift, borrar cuenta.
3. El proyecto compila, pero las advertencias indican codigo muerto y callbacks no conectados; eso puede ocultar funcionalidades visualmente incompletas.
4. SQL y app aun deben tener una matriz de contrato: cada RPC/tabla usada por Kotlin debe existir en `deploy_complete_unified.sql`.
5. Falta una medicion formal de rendimiento: Macrobenchmark, Baseline Profiles, startup time, scroll jank, memoria y red.

## Verificacion Ejecutada En Este Pase

- `compileDebugKotlin --no-daemon --console=plain`: exitoso.
- `compileDebugUnitTestKotlin --offline --no-daemon --console=plain`: exitoso despues de reactivar la compilacion de tests.
- `testDebugUnitTest --tests conectate.com.auth.twoFactor.TwoFactorAuthManagerTest --no-daemon --console=plain`: exitoso, 5 pruebas.
- `testDebugUnitTest --offline --no-daemon --console=plain`: exitoso, 82 pruebas.
- `assembleDebug --offline --no-daemon --console=plain`: exitoso despues de los cambios finales, genera APKs debug por ABI en `app/build/outputs/apk/debug`.

Nota: el primer `assembleDebug` tardo cerca de 10 minutos por dex/merge y genero APKs, pero la salida quedo cortada por timeout. Se repitio el build en cache/offline y termino con `BUILD SUCCESSFUL`.

## Gate De Produccion Recomendado

- `compileDebugKotlin` y `assembleRelease`.
- `testDebugUnitTest`.
- `lintRelease`.
- Smoke test en dispositivo real: login, perfil, feed, video upload, chat, llamada, live, notificaciones.
- SQL deploy en base limpia y base parcial.
- Prueba de RLS con usuarios A/B para cada tabla sensible.
- Crashlytics activo sin logs sensibles.
- Macrobenchmark para startup, home/feed, video feed, chat y perfil.

## Siguiente Pase Recomendado

1. Crear matriz automatica Kotlin -> SQL para detectar RPC/tablas faltantes.
2. Empezar limpieza de warnings por modulos de alto trafico: `messages`, `videos`, `social`, `profile`.
3. Ejecutar `assembleRelease`, `lintRelease` y smoke test en dispositivo real.
4. Agregar baseline profile y macrobenchmark para rutas criticas.
5. Convertir feed, mensajes y notificaciones a offline-first real con Room como fuente canonica.
