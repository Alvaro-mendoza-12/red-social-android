# Perfiles compartidos y @usuarios legados — 28/09/2026

Rama de trabajo: `feature/live-gifts` en `D:\red social - live-gifts`. La carpeta `D:\red social - copia` no se usó para estos cambios.

## Cambios

- Android 3.3.24 (código 86) comparte una sola URL HTTPS para permitir que la app receptora solicite la vista previa. El QR interno sigue abriendo el perfil en Novvin.
- La página pública entrega título, descripción y una imagen Open Graph de 1200 × 630 con foto, nombre y @usuario. Si la foto no es pública o no se puede leer, aparece la inicial. Se usa un origen de imagen permitido y límite de tamaño para evitar solicitudes a direcciones arbitrarias.
- Los identificadores importados sin origen `chosen` o `migrated_name` dejaron de mostrarse como @usuarios en la vista pública. Esto cubre los antiguos derivados del correo, incluso con sufijos.
- Se corrigió el contador de solicitudes de amistad: cuenta solicitudes pendientes no vistas, por cuenta, y se limpia al entrar tras una carga correcta. Antes contaba cuatro solicitudes ya confirmadas.

## Migración de cuentas existentes

- Antes de escribir se comprobó: 70 cuentas locales, 24 perfiles con otros ajustes, cero @usuarios permanentes, 54 perfiles importados. Se ejecutó el modo de simulación: 70 asignaciones, una coincidencia con sufijo y un perfil importado sin cuenta local (`Test User`).
- Respaldo PostgreSQL previo: `/home/alvaro/conectate-local-fallback/backups/legacy-usernames-2026-09-28-before.dump`, SHA-256 `878d665e144ee3f5b0f899010882dcd1bbf52e16031ac8ad400d48421486b0ac`. Se comprobó con `pg_restore -l` que contiene cuentas y perfiles.
- La transacción de migración asignó 70 identificadores únicos a 70 cuentas; la cuenta oficial conservó `@novvin`. Se comprobó que los 70 tienen origen `migrated_name`, que no existen duplicados y que el disparador de inmutabilidad sigue habilitado. El perfil importado sin cuenta local no se convirtió en cuenta nueva.
- La migración reproducible está en `api/scripts/migrate-legacy-usernames.js`: por defecto simula; `--apply` escribe después de un respaldo. El identificador oficial reservado está limitado al ID conocido de la cuenta oficial.

## Comprobaciones

- Android: `:app:assembleDebug :app:testDebugUnitTest`: 874 pruebas, 192 suites, cero fallos, errores o saltos. APK `Novvin-3.3.24-debug-v86-99d4239.apk`, SHA-256 `138E553BA5D8F1CA5CD8B8B23ADD312EF67FC99995E5692D3056906849E36A81`, instalada con actualización en Motorola `ZY22JSH8CF`, sin borrar datos. Mensajes abrió al iniciar. En Social ya no apareció el falso 4 de solicitudes confirmadas.
- Backend: `npm test`: 253 correctas, una saltada, cero fallos. La prueba real de registro, invitado, perfil y recuperación pasó 78 comprobaciones y retiró sus cuentas sintéticas; quedaron 70 cuentas y 70 identificadores únicos. API reconstruida y saludable. `/ready` público respondió con acceso a PostgreSQL; `/v1/public/profiles/novvin?preview=2` respondió con título `Novvin (@novvin)` y la imagen JPEG pública respondió 200. La URL antigua por ID también muestra `@novvin` sin identificador derivado del correo; el identificador anterior derivado del correo responde 404. La búsqueda pública de `keiver` devolvió nombre `keiver joya` y `@keiverjoya`.
- Se inspeccionó visualmente la tarjeta renderizada. La primera imagen mostró caracteres cuadrados por falta de fuentes en el contenedor; se instaló `font-dejavu`, se reconstruyó API y la segunda imagen mostró texto y foto correctamente.

## Límite de la comprobación

WhatsApp decide si muestra la vista previa y mantiene su propia caché. La tarjeta, la URL y la imagen respondieron desde Internet en esta PC, pero no se envió un mensaje real desde el Motorola para confirmar la renderización final de WhatsApp. El servicio de correo para recuperación sigue sin credenciales de remitente configuradas; se mantiene la recuperación manual.

