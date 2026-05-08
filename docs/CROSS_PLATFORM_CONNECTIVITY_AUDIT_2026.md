# Cross Platform Connectivity Audit 2026

Fecha: 2026-05-08.

## Objetivo

Verificar la base de integración entre APK Android y `Conectate-web`, endurecer el manejo de errores visibles al usuario y dejar documentado qué parte de la infraestructura ya está lista para producción y qué sigue bloqueado por la restricción actual de Supabase.

## Conectividad APK ↔ Web confirmada

- Backend compartido:
  - Android y web usan el mismo proyecto Supabase para Auth, Storage, Realtime y funciones.
- Login web por QR:
  - la web crea/consulta/consume pairings mediante `Conectate-web/src/core/auth/webLoginPairing.ts`
  - Android aprueba/rechaza pairings mediante `app/src/main/java/conectate/com/data/repository/WebLoginPairingRepository.kt`
  - el contrato SQL existe en `sql/hotfixes/supabase_hotfix_web_login_pairings_2026.sql`
  - el hotfix está incluido en `sql/release-manifest.txt`
- Recuperación de sesión:
  - la web soporta importación de `access_token` + `refresh_token`
  - Android soporta deep links `conectate://recovery` y parseo en `IntentRouter.kt`
- Restricción de servicio:
  - la web detecta restricciones de cuota/facturación en `Conectate-web/src/core/supabase.ts`
  - Android las traduce desde `SupabaseServiceRestrictionSupport.kt`

## Infraestructura verificada

- Web:
  - `Conectate-web/vercel.json` y scripts `vercel:install` / `vercel:build` ya permiten deploy directo del subproyecto.
  - `Node 22.x` fijado en `Conectate-web/package.json`.
  - build con preflight de variables para no desplegar un entorno roto sin `VITE_SUPABASE_URL` o `VITE_SUPABASE_ANON_KEY`.
  - `npm run lint` y `npm run vercel:build` pasaron en el worktree limpio una vez replicadas las variables locales de Supabase.
- Android:
  - `compileSdk 35`, `targetSdk 35`, Java/Kotlin 17 y release signing condicionado.
  - quality gate local con `testDebugUnitTest` + `lintDebug`.
- SQL:
  - manifest de release explícito, sin wildcards, con hotfix de pairings y de suspensión backend.

## Hardening aplicado en esta pasada

- Web:
  - `friendlyError` ahora oculta mejor mensajes técnicos de backend y pairing.
  - `QrCodeTile` ya no muestra errores crudos del generador QR.
  - `webLoginPairing.ts` ya pasa por el mapper central de errores.
  - `AppErrorBoundary`, `ErrorBoundary` y `ErrorBoundarySimple` ahora muestran mensajes entendibles para el usuario y dejan los detalles técnicos solo para desarrollo.
- Android:
  - `toUserFacingMessage` ahora detecta mejor fallos técnicos del bridge APK↔web y mensajes backend demasiado internos.
  - se agregaron pruebas unitarias para restricción de Supabase, credenciales inválidas y fallos técnicos de pairing.

## Estado honesto de producción

- Listo o casi listo sin depender de Supabase:
  - contrato base APK↔web
  - deploy web
  - manejo de mensajes de error al usuario
  - endurecimiento de configuración
  - estructura de build y release
- Bloqueadores técnicos reales encontrados en esta auditoría:
  - la APK no compila hoy en este estado de `main`, incluso después de restaurar `local.properties` y `google-services.json` locales
  - los errores visibles no apuntan al bridge web↔APK, sino a deuda heredada en módulos Android como `PredictiveAnalyticsSystem.kt`, `MainActivity.kt`, `MainActivityContent.kt`, `IncomingCallActivity.kt`, `HashtagComponents.kt` y varios componentes Compose
  - mientras esos errores sigan en `main`, no sería honesto marcar el APK como listo para producción aunque la infraestructura compartida con web esté mejor endurecida
- Todavía bloqueado por Supabase restringido:
  - login real extremo a extremo
  - validación runtime completa de QR y sesión real
  - smoke final de mensajes/realtime/storage

## Criterio profesional actual

La base compartida entre APK y web ya no está atada a mensajes crudos del backend para los flujos principales de acceso. La web sí quedó en un estado profesional mucho más cercano a producción. En Android, la próxima pasada seria no debe empezar por Supabase, sino por sanear la deuda de compilación actual y recién después retomar validación E2E real cuando el backend vuelva a estar operativo.
