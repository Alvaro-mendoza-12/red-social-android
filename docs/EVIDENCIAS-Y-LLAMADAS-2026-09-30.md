# Evidencias privadas y controles de llamadas

Fecha: 30/09/2026. Proyecto exclusivo: `D:\red social - live-gifts`, rama `feature/live-gifts`.

## Implementación

- La ayuda manual de recuperación permite adjuntar hasta cinco archivos, de hasta 8 MB cada uno: imágenes, PDF, texto, audio y video compatibles. Sigue separada de la recuperación automática por correo y puede iniciarse desde otra cuenta.
- Los archivos se envían junto con la solicitud. El servidor valida tamaño y firma del contenido, usa nombres aleatorios y guarda metadatos dentro de la misma transacción que crea la solicitud. Las cargas rechazadas se eliminan.
- Solo administradores autorizados pueden descargar evidencias, desde el detalle de la solicitud. No hay enlaces públicos. Las imágenes se ven en el panel; los demás formatos se abren con un visor compatible mediante un permiso temporal de lectura.
- Retención de evidencias: 30 días; limpieza periódica de vencidos y archivos huérfanos. Caché Android en un directorio específico, con limpieza al abrir evidencias posteriores. No se comparte la raíz de almacenamiento.
- Las llamadas entrantes usan `CallStyle`, acciones Contestar/Rechazar y pantalla de llamada. Se eliminó el modo silencioso de la notificación, que impedía alertas visibles. Si falla el servicio de aviso, no continúa un timbre sin interfaz de ese servicio.
- La pantalla entrante admite desplazamiento, márgenes del sistema y botones separados con texto blanco sobre rojo/verde oscuro. Ajustes, historial y notificaciones explican cómo activar los avisos o la pantalla completa cuando Android los bloquea.

## Verificación completada

- Servidor: 276 pruebas, 275 aprobadas, cero fallidas y una omitida. Incluye cuatro pruebas de archivos, límites, formatos y descarga privada desde el directorio oculto.
- Android: 891 pruebas unitarias, cero fallidas. La lista permitida de FileProvider incorpora únicamente el nuevo directorio específico de evidencias.
- HTTPS real: solicitud multipart con imagen y texto, listado administrativo y descarga exacta aprobados. Acceso anónimo 401, cuenta normal 403 y archivo de otra solicitud 404. Se conserva la solicitud JSON sin archivos (202). Se usaron cuentas y evidencias sintéticas.
- El ensayo real detectó un 404 causado por la exclusión predeterminada de directorios ocultos de Express. Se corrigió exclusivamente en la ruta autenticada de evidencias y se volvió a desplegar/probar.
- Dos pruebas instrumentadas aprobadas en Motorola moto g04: código y contraseña en etapas separadas; notificación con acciones y pantalla entrante. Las capturas de código, contraseña y botones entrantes no muestran superposiciones. La captura de la notificación no demuestra por sí sola que Android la muestre como aviso flotante.
- Pruebas API de LIVE de audio y roles de salas de voz aprobadas. Comprueban estado y permisos; no miden audio audible, imagen, FPS o retardo entre dos equipos.

## Entrega

- API desplegada en `/home/alvaro/conectate-local-fallback` tras comparar el código remoto y guardar respaldo; se reconstruyó únicamente ese servicio.
- APK `build/Novvin-9.0.3-live-gifts-debug.apk`, versión 9.0.3-debug, código 100, instalada con actualización sobre la app del Motorola, sin borrar datos.
- Compilación final: BUILD SUCCESSFUL en 2 min 57 s.
- SHA-256: `90FE8A1D05E92D245614AD0988AD38148C142F75F7BA2BAE625E58AEEB556340`.
- Capturas y registros locales en `build/communication-qa`, `build/motorola-communication-ui-test.log`, `build/android-full-unit-audit.log` y `infrastructure/local-fallback/api/audit-all-tests.log`.

## Alcance y siguientes verificaciones

La entrega inicial de esta sección corresponde a 9.0.3. La llamada real por
FCM contestada desde Android, la pantalla compartida y los medios entre
Motorola/emulador se comprobaron después y se documentan en
[la auditoría de medios](MEDIOS-Y-ENLACES-2026-09-30.md).

Android controla el permiso de pantalla completa, el canal de llamadas y la presentación del aviso. La app ofrece acceso a esos ajustes y no cambia las decisiones del usuario mediante ADB. Queda por verificar una llamada real recibida mediante push estando fuera de la app, así como transporte multimedia entre equipos y latencia de LIVE/salas/llamadas. Esta entrega no certifica toda la aplicación sin errores.

Referencias: [CallStyle y acciones de llamada](https://developer.android.com/develop/ui/compose/notifications/call-style), [permisos de notificación](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
