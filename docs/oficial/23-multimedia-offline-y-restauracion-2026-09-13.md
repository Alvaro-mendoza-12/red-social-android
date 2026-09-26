# Multimedia sin conexión y restauración aislada — 2026-09-13

## Estado y alcance

Continuación en `D:\red social - live-gifts`, rama `feature/live-gifts`. **No certifica producción ni equivalencia integral con WhatsApp/TikTok.** No se modificaron conversaciones, regalos, saldos o archivos de usuarios del servidor para realizar pruebas.

El bloque anterior cerró compilación, APK, 623 pruebas y Lint sin errores, con 195 advertencias de mantenimiento. Este nuevo bloque cerró a las **09:08:38** con `BUILD SUCCESSFUL in 16m 25s`: compilación, APK, **642 pruebas Android sin fallos, errores ni omitidas**, y **Lint sin errores, con 195 advertencias**. Se verificó el XML nuevo; no se reutilizó el resultado anterior.

**Bloqueo de seguridad adicional descubierto:** las rutas públicas/legadas de archivos de chat permiten leerlos sin autenticación si se conoce su URL. Se reprodujo localmente con archivos sintéticos; ver la sección de privacidad. Una auditoría de patrones estáticos que indique cero hallazgos no descarta este defecto de control de acceso.

## Persistencia multimedia

Se detectaron dos defectos: las copias abiertas podían ser expulsadas por el límite LRU de 192 MiB; las resoluciones atendidas desde memoria o desde una solicitud simultánea omitían el paso de guardar una copia offline.

- `ChatMediaStoragePolicy.kt`: separa copias conservadas y precarga especulativa. Un marcador privado junto a cada archivo conserva esa decisión entre reinicios. Sólo las precargas no abiertas compiten por el presupuesto LRU.
- `ChatMediaOfflineCache.kt`: una copia abierta se conserva; abrir una precarga completa la promueve sin otra descarga. Abrir durante una descarga en curso promueve esa misma operación. El guardado sigue separado por cuenta y no incluye contenido de ver una vez.
- Se mantienen límites de almacenamiento: 192 MiB de precarga, 64 MiB por archivo precargado, 512 MiB por archivo abierto y reserva mínima de 64 MiB libres. Las copias conservadas no se borran automáticamente para hacer lugar; si falta espacio, el guardado nuevo falla sin expulsarlas. No es una promesa de que cualquier archivo grande se descargue.
- Se rechazan cuerpos vacíos, respuestas parciales HTTP 206 y transferencias cuya longitud no coincide con Content-Length. El archivo sólo se publica mediante rename dentro del mismo directorio; no se expone un destino a medio copiar.
- La limpieza invalida las solicitudes anteriores, incluidas las que aún esperan red, para impedir que repongan lo que se acaba de borrar. El diálogo confirma la cuenta cuyos datos mostró, no una cuenta cambiada después.
- `MessageMediaResolver.kt`: las salidas de memoria, solicitudes compartidas y URLs públicas pasan por el mismo guardado y comprobación de cuenta. Buscar copias locales se ejecuta fuera del hilo principal.
- `ChatOfflineStorageSection.kt`: en Personalizar mensajes se puede consultar espacio conservado/precargado y eliminar **sólo las copias locales de esa cuenta**, con confirmación y aviso de que se perderá disponibilidad offline. No elimina mensajes del servidor, galería ni otras cuentas.
- `ChatMediaDownloads.kt` y `ChatMediaExport.kt`: el botón Descargar distingue URL remota y copia local. Esta última se exporta al destino elegido por el usuario mediante el selector de documentos, sin requerir internet. El archivo debe estar completo y pertenecer al directorio de la cuenta activa. Se reutiliza en chat, grupo y comunidad; no se muestra para ver una vez.
- `MediaViewerSource.kt` y el visor a pantalla completa: también se resuelven las URLs HTTP públicas para encontrar copias locales. Una resolución rechazada no vuelve silenciosamente a la URL original. La espera está acotada y hay reintento visible; cancelar el visor cancela la operación. Las rutas que ya son locales no se vuelven a resolver.

No son una copia de seguridad externa. Borrar los datos de la aplicación o desinstalarla elimina los archivos privados. Las precargas aún no abiertas siguen siendo temporales; falta completar almacenamiento selectivo/reanudable para archivos grandes y validar la experiencia real sin red. Esta distinción sigue las categorías de [almacenamiento específico de Android](https://developer.android.com/training/data-storage/app-specific).

## Respaldo del servidor

Respaldo verificado: `/home/alvaro/conectate-backups/20260913T081531Z`, terminado a las `2026-09-13T08:17:21Z`.

| Artefacto | Tamaño | Evidencia |
|---|---:|---|
| PostgreSQL | 2 959 031 bytes | SHA-256 correcto y restauración completa del dump |
| Storage | 2 055 759 666 bytes | SHA-256 y lectura íntegra del archivo tar |
| Estado del chatbot | 4 695 842 bytes | SHA-256 y lectura íntegra del archivo tar |
| Compose | — | SHA-256 correcto |

`restore-drill-isolated.sh` levantó PostgreSQL 16.15 en un contenedor sin red ni puertos, sin capacidades Linux, con raíz de sólo lectura, respaldo montado de sólo lectura y datos temporales en tmpfs. Limitado a 1 CPU y 1 GiB de RAM. Se restauró con `--exit-on-error`, sin usar credenciales ni conexiones de producción. La opción impide continuar silenciosamente después de un error de restauración: [referencia de pg_restore 16](https://www.postgresql.org/docs/16/app-pgrestore.html).

**Resultado: `RESTORE_ISOLATED_OK`, 202 tablas restauradas.** El contenedor y su tmpfs se eliminaron al terminar; se comprobó que no quedaban contenedores con la etiqueta de simulacro. Sólo permanecen diagnósticos privados en `/tmp/novvin-restore-check.egQvswXc`. Los ocho servicios reales continuaron saludables y `/ready` respondió 200 después.

Logs locales: `tmp/backup-integrity-20260913.log` y `tmp/restore-isolated-20260913.log`. El script se dejó en la carpeta de release del servidor; no se alteró el servicio de base de datos ni el procedimiento programado de backup.

**Límite:** esto demuestra integridad de artefactos y restauración del dump. No demuestra aún recuperación integral del servicio, arranque de aplicación sobre la copia, reposición del storage, objetivos RTO/RPO ni respaldo fuera de esa mini PC.

## APK de prueba y validación

APK generado a las 09:00:38: `app/build/outputs/apk/debug/app-debug.apk`, 272 537 244 bytes. SHA-256 `68A8B48F3695CAAB72360B6AA9C0E2536AE10C405E2796E88C14E625C92BD031`.

`apksigner verify --verbose` confirmó firma APK v2 válida. Paquete `conectate.com`, versión `3.3.4-debug`, código 66, min SDK 24, target SDK 36. Incluye la corrección del visor y la revisión final de cuenta. Es un artefacto de prueba, no un AAB productivo ni prueba de instalación en el teléfono del usuario. No desinstalar la versión actual para forzar una actualización con otra firma.

- Primera revisión del bloque: compilación, APK y 634 pruebas pasaron en `tmp/android-offline-retention-validated-20260913.log`. Después se detuvo exclusivamente ese daemon de validación antes de completar Lint, para incorporar el arreglo del visor y repetir la puerta con una versión consistente. No presentar la ejecución completa como aprobada.
- Compilación y APK finales correctos, **642 pruebas pasan**, incluidos visor y protección de cuenta; **Lint final sin errores y 195 advertencias**, terminado a las 09:08:38: `tmp/android-offline-viewer-final-20260913.log` y `app/build/reports/lint-results-debug.xml`. No se modificó la fuente Android durante esa ejecución.
- Herramientas de mantenimiento: 60 pruebas pasan también después de agregar la puerta de producción, `tmp/tools-offline-production-gate-20260913.log`.
- Auditoría runtime de patrones: pasa, cero hallazgos (`build/reports/security/runtime_security_audit.json`). No cubría el acceso público a multimedia que sí detectó la nueva prueba específica; esa puerta falla y se mantiene bloqueante.
- Se añadieron regresiones para retención frente a LRU, reinicio, espacio insuficiente, respuesta truncada, cambios de cuenta, limpieza con descarga pendiente, promoción de una descarga en curso y exportación segura.
- Las seis pruebas nuevas del visor pasan: URLs públicas, firmadas, rutas locales, respuesta rechazada, espera máxima y cancelación. Las 15 pruebas de almacenamiento y las 5 de exportación también pasan. Falta probar la interacción en dispositivo.
- Sigue sin haber validación en dos Android físicos de entrega/reproducción, audio/LIVE, red móvil y cambio Wi-Fi/LTE. El hostname público estable y las demás puertas de producción continúan en el [informe anterior](22-live-regalos-y-validacion-2026-09-12.md).

## Privacidad de los archivos de mensajería: puerta fallida

`api/src/storage-routes.js` declara sólo `report-evidence` como bucket de acceso firmado obligatorio. Las rutas GET pública y legada llaman a `serveStoredObject` sin autenticar al solicitante ni comprobar su pertenencia a la conversación. El POST de firma comprueba propietario, pero para los demás buckets devuelve una URL pública; no resuelve la autorización del destinatario.

Se verificó que la fuente auditada coincide con `src/storage-routes.js` dentro del contenedor activo: SHA-256 `b3d0ce7b5c0de20aacb7cc446879a20362a6b10513d8196e76573089762745a2`. No se descargaron adjuntos privados reales para corroborarlo.

`api/scripts/verify-messaging-media-privacy.js` reproduce el recorrido real de los handlers con Express local, archivos sintéticos en un directorio temporal y una base simulada. No conecta al servidor ni usa mensajes/personas reales. Para `chat-media`, `group-media`, `community-media` y `group-audio`, las dos variantes GET devolvieron HTTP 200 y el contenido sin autenticación: **8 de 8 comprobaciones de denegación fallaron**. El comando devuelve exit code 1 deliberadamente; no se agregó excepción ni baseline para ocultarlo. Log: `tmp/messaging-media-privacy-gate-20260913.log`.

La comprobación se agregó al workflow manual `android-production-release.yml`, antes de cargar secretos de firma y generar el candidato productivo. Bloqueará esa generación hasta que se deniegue realmente el acceso; la compilación debug y sus pruebas pueden seguir usándose para trabajar. No se lanzó ningún workflow remoto ni se publicó un release. Esta puerta verifica el código del repositorio, no sustituye la comprobación posterior del despliegue.

Antes de producción hace falta una migración coordinada:

1. Autorizar lectura y renovación de URL por participante/miembro vigente, además del propietario, respetando bloqueos, mensajes eliminados y vista única.
2. Adaptar Android/Web/descargas/rangos de video a URLs firmadas o lectura autenticada, sin fallback público para archivos privados.
3. Cerrar **ambas** rutas públicas/legadas y ajustar la caché del edge; contemplar los enlaces previamente distribuidos y una transición de versiones instaladas.
4. Probar propietario, destinatario, tercero, exmiembro, token vencido y rangos, con denegación comprobada antes de desplegar.

No se modificaron permisos del servidor durante esta investigación: hacerlo sin preparar todos los lectores podría romper las versiones que el usuario tiene instaladas. Este pendiente impide certificar privacidad de mensajería aunque pasen las pruebas de almacenamiento local.

## Reversión

El nuevo almacenamiento usa el directorio existente y agrega marcadores `.keep`; no se migraron ni borraron copias del dispositivo desde este entorno. Al revertir, preservar los archivos y marcadores: el algoritmo LRU anterior no comprende la retención y podría expulsar copias conservadas. No desinstalar la app para resolver conflictos de firma. El simulacro no necesita rollback de producción porque nunca escribió en ella.
