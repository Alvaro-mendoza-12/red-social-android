# Android: envío de texto sin esperar al servidor

Proyecto: `D:\red social - live-gifts`, rama `feature/live-gifts`.

## Problema y cambio

El chat directo vaciaba el campo después de recibir el resultado HTTP y mantenía un bloqueo de envío durante toda la petición. Grupos y comunidades borraban el texto antes de guardarlo, bloqueaban el siguiente envío hasta la respuesta y, ante un error, restauraban el mensaje anterior encima del nuevo borrador.

Los tres repositorios notifican ahora la aceptación después de insertar el mensaje pendiente en Room y programar su sincronización. `QueuedMessageComposer` consume el borrador en ese punto y permite el siguiente envío aunque la petición anterior siga pendiente. El guardado local y las validaciones previas siguen siendo necesarios; esta señal no representa confirmación de entrega al destinatario.

El coordinador compartido conserva el borrador si falla la preparación o el guardado, protege las nuevas revisiones de texto y respuesta aunque tengan el mismo contenido, evita dobles pulsaciones durante el guardado y descarta callbacks de conversaciones o canales anteriores. Los resultados de entrega ya no restauran ni borran texto del campo. Los envíos de texto tampoco dependen del indicador de subida de archivos.

También se protege el resumen local del último mensaje frente a confirmaciones fuera de orden. Los envíos de grupos y comunidades usan una actualización de metadatos condicionada por fecha; las operaciones explícitas de borrado y recálculo de historial conservan su comportamiento. La lista de chats directos reutiliza `newestInboxMessage` para elegir el resumen más reciente.

## Verificación

- Nuevas pruebas del coordinador con guardado y servidor suspendidos, segundo envío antes de la confirmación, errores, cancelación, cambios de cuenta/canal y texto/respuestas idénticos.
- Nuevas pruebas con Room para confirmaciones fuera de orden en grupos y comunidades, incluyendo fechas equivalentes con zona horaria y recálculo explícito del historial.
- Compilación final correcta: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`. Evidencias en `build/audit-2026-09-21-composer/final-build.log`.
- **811 pruebas JVM, 0 fallos, 0 errores, 0 omitidas**. Incluyen 9 nuevas pruebas del coordinador y 2 del orden de los resúmenes; sustituyen una prueba del antiguo borrado al terminar el envío.
- APK verificada: firma debug habitual del proyecto; las cuatro clases de arranque y las nuevas clases del coordinador están definidas en los DEX finales.
- La primera compilación comenzó antes de terminar el ajuste de metadatos; sus pruebas no pudieron resolver el método nuevo contra las clases anteriores. Se inició una segunda compilación con todos los cambios finales, sin modificar fuentes durante esa ejecución.
- Comprobación pública de solo lectura: `/health` y `/ready` respondieron HTTP 200; `/v1/data/music/my` respondió HTTP 401 sin credenciales. Esto confirma disponibilidad y protección de esa ruta, no un envío o borrado autenticado.
- El Moto G04 no estuvo conectado por ADB. Se instaló la APK final en un emulador Android 15 limpio, sin tocar datos del usuario. La prueba `MainActivitySmokeTest#coldLaunchReachesUsableEntryScreen` pasó (1/1), alcanzó la pantalla de acceso y el búfer de crashes quedó vacío. Se usó `AndroidJUnitRunner`; el emulador se cerró al terminar.

APK de prueba: `build/audit-2026-09-21-composer/Novvin-3.3.6-composer-debug.apk` (269.612.215 bytes).

SHA-256: `073EC99737DC00DA924B7BE35838DC693FF81C0795D33638001E4390C6EB2244`.

## Alcance y reversión

No hay migración de base de datos ni despliegue del servidor en este cambio. Compilar y pasar estas pruebas no certifica toda la aplicación para producción. Queda pendiente validar los envíos con una cuenta autenticada en el dispositivo físico y bajo su conexión real.

Para revertir, retirar exclusivamente la integración de `QueuedMessageComposer`, los callbacks `onQueued` y las comprobaciones del resumen añadidas en esta sesión. No restaurar archivos completos desde HEAD: el proyecto contiene trabajo previo sin confirmar que debe conservarse.
