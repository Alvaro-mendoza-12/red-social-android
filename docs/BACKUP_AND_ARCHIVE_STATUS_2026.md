# Backup And Archive Status 2026

Fecha de revision: 2026-05-07.

## Estado activo

La fuente activa de producto es el arbol principal `app/`, `sql/release-manifest.txt`, `signaling-server/`, `tools/` y la documentacion viva bajo `docs/`.

Los snapshots y cuarentenas locales no forman parte del build, del deploy SQL ni del release Android. Se conservan solo para recuperacion manual o comparacion historica.

## Backups detectados

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

Los backups historicos siguen congelados a proposito. La referencia vigente queda en `README.md`, `docs/`, `sql/release-manifest.txt` y los reportes generados bajo `build/reports/`.
