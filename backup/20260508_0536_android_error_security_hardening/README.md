# 2026-05-08 05:36 - Android error and security hardening

## Objetivo

Hacer una pasada adicional de producción sobre Android después de recuperar el baseline, enfocada en:

- seguridad de red explícita
- mensajes de error más humanos
- endurecimiento de `StorageService`
- mejor manejo de fallos en el módulo `live`

## Cambios principales

- se agregó `app/src/main/res/xml/network_security_config.xml`
- `AndroidManifest.xml` ahora referencia `networkSecurityConfig`
- `AppFeedbackBus` ganó overload para `Throwable`
- `UserFacingErrorMessage` reconoce mejor errores de sesión, storage path, signed URL y media policy
- `StorageService` dejó de devolver varios mensajes técnicos crudos (`Invalid storage path`, `No access token available`, `No signedURL in response`)
- `LiveSessionViewModel` ahora transforma errores a mensajes user-facing y ya no ignora fallos al enviar comentarios

## Verificación

- `:app:qualityGateDebug` OK
- `:app:assembleRelease` OK
