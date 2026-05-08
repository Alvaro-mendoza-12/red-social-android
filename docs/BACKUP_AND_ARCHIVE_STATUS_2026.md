# Backup And Archive Status 2026

Fecha de revision: 2026-05-08.

## Estado activo

La fuente activa de producto es el arbol principal `app/`, `sql/release-manifest.txt`, `signaling-server/`, `tools/` y la documentacion viva bajo `docs/`.

Los snapshots y cuarentenas locales no forman parte del build, del deploy SQL ni del release Android. Se conservan solo para recuperacion manual o comparacion historica.

## Backups detectados

- `backup/20260508_0055_cross_platform_connectivity_hardening`: snapshot previo a endurecer mensajes del puente APK↔web, agregar pruebas del mapper Android y documentar la conectividad cruzada.
- `backup/20260508_0031_auth_viewport_fit`: snapshot previo a compactar login/registro para que el acceso entre mejor a 100% en ventanas de altura media.
- `backup/20260508_0021_login_brand_identity`: snapshot previo a integrar el logo real de Conectate y copy de marca en login/registro web.
- `backup/20260508_0012_login_access_cleanup`: snapshot previo a simplificar la pantalla de login web a un solo panel de acceso y a humanizar errores técnicos de Supabase.
- `backup/20260507_2358_web_subdir_vercel_config_hardening`: snapshot previo a fijar `vercel.json` dentro de `Conectate-web` para que Vercel aplique rewrites y headers en el subproyecto real.
- `backup/20260507_2358_web_subdir_vercel_script_fix`: snapshot previo a alinear los scripts de `Conectate-web` con el deploy real de Vercel usando `Root Directory = Conectate-web`.
- `backup/20260507_2333_main_deploy_install_fix`: snapshot previo a endurecer la instalacion web para un checkout limpio de `main` antes de promover el deploy.
- `backup/20260507_2257_web_vercel_settings_hardening`: snapshot previo a preparar el repo raíz para Vercel y a reordenar `AccountSettingsPage` con mejor jerarquía visual.
- `backup/20260507_2234_conectate_web_messages_profile_refine`: snapshot previo a reordenar `Conectate-web` en `MessagesPage`, `ProfilePage` y `index.css` para quitar el aspecto de maqueta en las vistas internas.
- `backup/20260507_1923_supabase_restriction_runtime_hardening`: snapshot previo a endurecer mensajes persistentes y gating del acceso rapido/QR cuando Supabase restringe el proyecto por cuota o facturacion.
- `backup/20260507_1827_call_flow_hardening`: snapshot previo a endurecer llamadas entrantes, manejo de permiso de microfono y eliminacion de fallos silenciosos en `CallRepository`/`WebRTCManager`.
- `backup/20260507_1750_apk_theme_runtime_sync`: snapshot previo a sincronizar apariencia runtime del APK con `ThemePreferences` y a reemplazar el stub de `ConectateDesignSystem`.
- `.backup/groups_communities_20260212`: snapshot historico de grupos/comunidades.
- `app_backup_before_restore`: copia previa a restauracion del app Android, con codigo viejo que no debe importarse sin comparar.
- `tmp/app_java_untracked_quarantine_20260429_120405`: cuarentena de Java/Kotlin no trackeado.
- `tmp/app_recovery_snapshot_20260429_120341`: snapshot de recuperacion.
- `tmp/app_tests_untracked_quarantine_20260429_124501`: cuarentena de tests.
- `tmp/current_working_app_backup_20260429_132711`: copia local de trabajo.
- `tmp/recovery_quarantine_20260429_*`: cuarentenas de recuperacion adicionales.

## Regla operativa

- No se deben actualizar masivamente estos backups para "ponerlos al dia"; eso destruye su valor como snapshot.
- Si una pieza de un backup se recupera, debe copiarse al arbol activo con revision manual y luego verificarse con build/tests.
- Si un backup queda obsoleto y ya no se necesita, debe eliminarse en una tarea explicita aprobada por el owner del repo.
- La documentacion y los manifiestos activos deben apuntar al codigo vigente, no a snapshots.

## Validacion relacionada

La pasada del `2026-05-06` actualizo la documentacion activa, wallet/gifts, premium/ads, comentarios con audio y el hardening SQL del manifest. No se modificaron contenidos internos de snapshots para evitar mezclar historial con codigo vigente.

El cierre local del `2026-05-06` confirmo:

- `compileDebugKotlin` sin errores.
- `testDebugUnitTest` con 66 tests, 0 fallos, 0 errores y 0 omitidos.
- `assembleRelease --no-daemon --console=plain --max-workers=1` exitoso con cuatro APKs regenerados.
- Auditoria SQL del manifest sin violaciones nuevas.
- Presupuesto de performance/tamano de APK en verde para los cuatro artefactos.
- La siguiente pasada compilo `compileDebugKotlin`, `testDebugUnitTest` y `assembleRelease --max-workers=1` despues de agregar favoritos persistentes de chat en Room v40 y SQL `20260506_chat_favorites_preferences_2026.sql`.
- La verificacion previa a Android Studio del `2026-05-07` ejecuto `:app:qualityGateDebug`, `:app:assembleDebug`, `:app:assembleRelease`, auditoria SQL y performance budgets en verde.
- La pasada del `2026-05-07 17:50` creo `backup/20260507_1750_apk_theme_runtime_sync` antes de tocar `MainActivity`, `IncomingCallActivity`, `Theme.kt` y `ConectateDesignSystem.kt` para cerrar una deuda real de apariencia: las preferencias de fuente/escala estaban guardadas pero no se aplicaban globalmente en runtime.
- La pasada del `2026-05-07 18:27` creo `backup/20260507_1827_call_flow_hardening` antes de tocar `IncomingCallActivity`, `CallRepository` y `WebRTCManager` para cerrar deuda silenciosa del flujo de llamadas: permiso de microfono, aceptacion/rechazo sin validar resultado y catches vacios durante persistencia/cleanup.
- En la validacion posterior del `2026-05-07` si hubo smoke real en dispositivo `moto_g04` (`ZY22JSH8CF`): el login abrio bien, el flujo QR de Conectate Web se mostro y rechazo un QR invalido con mensaje defensivo correcto, y el acceso rapido con cuenta guardada llego al backend pero fallo por una restriccion real del proyecto Supabase (`exceed_storage_size_quota`).
- La pasada del `2026-05-07 19:23` creo `backup/20260507_1923_supabase_restriction_runtime_hardening` antes de tocar `SupabaseServiceRestrictionSupport`, `LoginScreen`, `SettingsScreen` y `AdvancedSettingsScreens` para dejar persistente el aviso de restriccion del backend y pausar accesos rapidos/QR que solo generaban reintentos engañosos.
- En esa misma pasada se verifico `compileDebugKotlin`, `app:testDebugUnitTest`, `assembleDebug` y `:app:qualityGateDebug` con exito. El barrido unitario quedo en 77 tests, 0 fallos, 0 errores y 0 omitidos, y el lint report debug se regenero en `app/build/reports/lint-results-debug.html`.
- Se intento revalidar esta ultima pasada otra vez en hardware real, pero `adb` perdio el handshake aunque Windows siguio viendo la interfaz USB/ADB del mismo telefono. No hubo nueva instalacion ni captura post-cambio porque `adb devices -l` dejo de enumerar un dispositivo utilizable.
- La pasada web del `2026-05-07 22:34` creo `backup/20260507_2234_conectate_web_messages_profile_refine` antes de reordenar `Conectate-web/src/features/messages/MessagesPage.tsx`, `Conectate-web/src/features/profile/ProfilePage.tsx` y `Conectate-web/src/index.css`. Se verifico `npm run lint` y `npm run build` con exito. La validacion visual autenticada de esas rutas sigue pendiente porque en este entorno no se entrego una sesion web activa para recorrer `messages`/`profile` en navegador real.
- La pasada web del `2026-05-07 22:57` creo `backup/20260507_2257_web_vercel_settings_hardening` antes de agregar `package.json` y `vercel.json` en la raiz para desplegar `Conectate-web` en Vercel desde este mismo repo, y antes de reordenar `AccountSettingsPage` como dashboard mas navegable. La verificacion honesta incluye `npm run lint`, `npm run build` en `Conectate-web` y `npm run vercel:build` desde la raiz. El CLI global `vercel` no estaba instalado en este entorno.
- La pasada del `2026-05-07 23:33` creo `backup/20260507_2333_main_deploy_install_fix` antes de promover la web a `main`. En un worktree limpio se detecto que `npm ci` fallaba por conflicto de peer dependencies entre React 19 y tooling de testing. Se endurecio la instalacion con `Conectate-web/.npmrc` (`legacy-peer-deps=true`) y `package.json` raiz usando `npm --prefix Conectate-web ci --legacy-peer-deps`. Despues de eso, `npm ci`, `npm run build` y `npm run vercel:build` pasaron en el worktree limpio de `main`.
- La pasada del `2026-05-07 23:58` creo `backup/20260507_2358_web_subdir_vercel_script_fix` despues de ver un fallo remoto real en Vercel: el proyecto estaba configurado con `Root Directory = Conectate-web`, pero el install command seguia llamando `npm run vercel:install` dentro de esa carpeta y el script no existia alli. Se agregaron `vercel:install` y `vercel:build` a `Conectate-web/package.json` para que el mismo repo soporte tanto deploy desde raiz como deploy directo del subproyecto.
- La pasada final del `2026-05-07 23:58` creo `backup/20260507_2358_web_subdir_vercel_config_hardening` para cerrar otro hueco de deploy: la documentacion oficial de Vite en Vercel exige `vercel.json` en la raiz del proyecto desplegado para soportar deep linking SPA. Como el proyecto en Vercel usara `Root Directory = Conectate-web`, se agrego `Conectate-web/vercel.json` con `rewrites`, `headers`, `installCommand`, `buildCommand` y `outputDirectory` propios del subproyecto. En la misma pasada se fijo `engines.node = 22.x` en `Conectate-web/package.json` para alinear el runtime del deploy con el runtime validado localmente.
- La pasada del `2026-05-08 00:12` creo `backup/20260508_0012_login_access_cleanup` antes de simplificar el login web. Se retiro la columna editorial izquierda para que el usuario vea solo el panel `Acceso`, se reforzo el centrado/jerarquia visual del card y `friendlyError` empezo a traducir restricciones de servicio, credenciales invalidas y fallos de red a mensajes humanos en vez de exponer respuestas crudas de Supabase.
- La pasada del `2026-05-08 00:21` creo `backup/20260508_0021_login_brand_identity` antes de reforzar la marca en acceso web. Se integro `/conectate-logo.svg` en login y registro, se cambio el encabezado del login a `Inicia sesión en Conectate` y se alineo el fondo/lockup visual con la identidad principal de la app para que el acceso deje de sentirse genérico.
- La pasada del `2026-05-08 00:31` creo `backup/20260508_0031_auth_viewport_fit` para compactar el acceso web cuando la altura visible del navegador es más limitada. Se redujo densidad vertical bajo `@media (max-height: 920px)` y se priorizo que el panel de login entre mejor al 100% sin obligar al usuario a bajar zoom. El bloqueo de acceso visto en ese momento seguia siendo el mismo de Supabase restringido por cuota, no un fallo nuevo del formulario.
- La pasada del `2026-05-08 00:55` creo `backup/20260508_0055_cross_platform_connectivity_hardening` para auditar y endurecer la conectividad entre APK y web. Se documentó el contrato compartido de QR/recuperación/Supabase en `docs/CROSS_PLATFORM_CONNECTIVITY_AUDIT_2026.md`, se reforzó el filtrado de mensajes técnicos en `Conectate-web/src/core/utils.ts`, `Conectate-web/src/core/auth/webLoginPairing.ts`, `Conectate-web/src/components/QrCodeTile.tsx` y `app/src/main/java/conectate/com/presentation/common/UserFacingErrorMessage.kt`, y se agregaron pruebas unitarias Android para el mapper de errores.
- En esa misma auditoría cruzada del `2026-05-08 00:55` la web volvió a pasar `npm run lint` y `npm run vercel:build` en un worktree limpio después de restaurar variables locales, y además se endurecieron `AppErrorBoundary`, `ErrorBoundary` y `ErrorBoundarySimple` para no filtrar mensajes técnicos crudos al usuario final. La validación Android avanzó más que antes al restaurar `local.properties` y `app/google-services.json`, pero dejó una verdad importante: `main` hoy arrastra errores de compilación heredados en varios módulos Android que deben sanearse antes de volver a hablar de APK lista para producción.

Los backups historicos siguen congelados a proposito. La referencia vigente queda en `README.md`, `docs/`, `sql/release-manifest.txt` y los reportes generados bajo `build/reports/`.
