# Mensajería: permisos de stickers y cierre de destacados

Fecha: 18 de septiembre de 2026. Rama: `feature/live-gifts`, repositorio `D:\red social - live-gifts`. Continuación sin dispositivo; no se publicaron mensajes, canciones ni contenido de usuarios durante las pruebas.

## Resultado Android

Se cerró la validación pendiente de destacados/Momentos. Compilación exitosa, 720 pruebas en 159 suites sin fallos/errores/omitidas, Lint sin errores y con 196 advertencias. Firma del APK debug verificada. Evidencia, hash y límites en [31](31-perfil-destacados-y-diferenciales-2026-09-17.md). No equivale a validar físicamente el decoder, LIVE, llamadas o toda la aplicación en producción.

## Defecto de autorización reproducido

`currentUserMayReferenceMessageMedia` permitía reutilizar un archivo ajeno de `chat-media` si el solicitante indicaba `contentType=sticker` y el objeto existía. No exigía que estuviera en un catálogo accesible. La prueba sintética «labelling another user's stored photo as a sticker does not authorize reuse» devolvió `true` en vez de `false` antes del cambio. No fue necesario leer una foto privada real ni enviar mensajes para demostrarlo.

El control es compartido por chats privados, grupos, comunidades y comentarios de videos. Se cerró esa excepción indiscriminada y se comprobó la compatibilidad de los cuatro destinos.

## Implementación por módulos

- `api/src/sticker-media-policy.js`: política pura de permisos y comparación de la identidad de almacenamiento. Distingue packs públicos/propios, alias de cuentas migradas y marcas de eliminación de packs/items. Compara bucket y ruta exacta, conservando mayúsculas, espacios, `+` y escapes literales; una rotación del hostname temporal no convierte un sticker autorizado en un archivo distinto. Los externos ya catalogados requieren URL HTTP(S) idéntica.
- `postgrest-compat-routes.js`: expone `canReuseCatalogStickerMedia`, que reutiliza el catálogo combinado existente (importados y overrides locales). Una actualización local de privacidad/eliminación prevalece sobre el registro importado. La inserción/copia en packs, la búsqueda y las lecturas de items comparten las mismas restricciones de borrado. No se crean relaciones de autorización a partir de la URL proporcionada por el cliente.
- `data-routes.js`: conserva el camino de subidas propias y los reenvíos autorizados por mensaje; para un sticker ajeno exige objeto existente y catálogo permitido. Los stickers propios se suben a `chat-media`, también cuando se envían a un grupo, comunidad o comentario; se verificó ese caso expresamente para no romperlo. La dependencia de compatibilidad se resuelve dentro del camino sticker para evitar el ciclo de importación inicial entre las rutas existentes.
- `npm run test:message-media`: agrupa las pruebas de reenvío y catálogo. No se añadió un botón nuevo ni se presenta esta corrección de seguridad como una de las quince funciones diferenciales solicitadas.

## Pruebas

Se añadieron 12 casos (el caso de regresión y 11 pruebas de catálogo/política/integración). Cubren archivos ajenos falsamente etiquetados, packs públicos/privados/eliminados, propiedad y alias, objetos ausentes, overrides de importación, cambios del origen temporal, rutas diferentes, escapes y copia a otro pack. Se mantuvieron los 13 casos previos de reenvío, bloqueos y contenido efímero.

`tmp/message-sticker-security-validation-20260918.log`: 28 entradas TAP satisfactorias, cero fallos/omitidas: las 25 pruebas de mensajería/stickers más tres archivos de regresión existentes (`data-routes`, `postgrest-security`, `storage-routes`). No interpretar esos tres archivos antiguos como si cada uno sólo contuviera una aserción. Las mismas 28 entradas pasaron en Linux en la imagen candidata, con comprobación de sintaxis y dependencias.

## Actualización acotada del servidor

Se verificó que, al retirar exclusivamente el parche de stickers y normalizar finales de línea, los dos módulos existentes coinciden con los desplegados. Comparación de sólo lectura: `tmp/verify-sticker-patch-scope-20260918.js`. Hashes LF previos: data `4e45d2ec1454d0f0ab98fbf4b9d21ec28f1b9c2f52cde8a5622adc68167e797c`, compat `82f3498b736c762369eea180de0689ad9be958b08097c7b0394b9b06ffc56420`. Los originales del servidor mezclaban CRLF y LF; no confundir una diferencia de fin de línea con un cambio funcional.

Se construyó una capa de tres módulos sobre la imagen de fechas verificada, sin migraciones ni reconstrucción desde toda la copia de trabajo. A las 00:10:59 se recreó API; los otros siete contenedores conservaron su ejecución. Se verificaron salud, imagen, archivos del contenedor y reconciliación de fuentes del host.

- Imagen activa: `conectate-api-sticker-media:20260918`, ID `sha256:0f314bcd01f9c53bee6854008350c3750a22e5a842950a64ab6c7db8b4adcda7`.
- Reversión: `conectate-api-before-sticker-media:20260918`, ID `sha256:4770d6cf6bcaaac3c04bd0282ce41861b59767efc735e10ef099d881a886c8b7`.
- Respaldo previo verificado: `/home/alvaro/conectate-releases/api-sticker-media-20260918/host-before.62zrdR/` (`data-routes.js` y `postgrest-compat-routes.js`). No se borraron archivos de usuarios ni bases de datos.
- Hashes desplegados: data `18dca0f8d7d1774f2a5ec1753f036ea1515e9e2b9976e0f59b8b190c71b5dfb6`; compat `aad676b4c1a367b0137d01217c5f6e5981543100a5160ad762bd6a936e5e1a47`; política `a1ebdd0f300013ae5e9a86b3c05cdc3239027ce14c057abdcc202ff36c2521d2`.
- Momentos conserva `c1654100abf377f7c19988c7daa2fe7901a7b97f99eb23d519812576c1a77f0a`; música `255e421e29097f1631e07e18e2495f1efda64f4ba2093924e3767a3cce958a9e`.
- Evidencia: `tmp/sticker-media-rollout-20260918.log`. Script de rollout con bloqueo de Compose, backup comprobado y rollback automático si falla imagen/readiness/sincronización; no hizo falta revertir.

`verify-sticker-catalog-read.js` se ejecutó por stdin sin copiarse a la imagen. Consulta de identidad en transacción read-only y búsqueda autenticada sin mutaciones: HTTP 200 interno/115 ms y público/340 ms. El usuario de diagnóstico no tenía items visibles (0): se valida disponibilidad y contrato, **no se afirma haber probado stickers reales con un catálogo vacío**. Evidencia: `tmp/sticker-catalog-read-20260918.log`.

Después del despliegue se ejecutó una ronda del monitor de lecturas existente. Terminó satisfactoriamente el 18 a las **00:14:49 Bogotá / 05:14:49 UTC**, con Social/Videos y Mensajes en `status=ok` y cero fallos consecutivos. Evidencia: `tmp/post-sticker-public-read-paths-20260918.log`. Las duraciones totales de los grupos de pruebas (10.722 y 6.995 ms) incluyen múltiples peticiones y arranque del verificador; no son tiempos de carga de una pantalla ni prueba de reproducción física. No se creó otra automatización ni se reiniciaron servicios para ejecutar la lectura.

Para una reversión manual, adquirir el mismo bloqueo de Compose, comprobar que la imagen actual es la de esta etapa, restaurar la etiqueta `conectate-local-fallback-api:latest` a la imagen de reversión, recrear únicamente API sin dependencias/build y restaurar los dos fuentes del respaldo; mover la política nueva al directorio de respaldo. Comprobar `/ready`, monitor público e integridad de archivos. Revertir reabre la vulnerabilidad de reutilización descrita; no usar como una solución de privacidad.

## Pendientes que no quedan resueltos por este parche

- **Descarga sin autenticación de multimedia de chats:** `verify-messaging-media-privacy.js` volvió a fallar los ocho casos sintéticos (cuatro buckets, rutas pública y antigua). HTTP 200 donde se esperaba denegación. Evidencia: `tmp/messaging-media-privacy-20260918.log`. La barrera de publicación de producción permanece activa; no se relajó ni se certifica privacidad completa. Hay que coordinar permisos de destinatario, enlaces con vencimiento, rangos MP4, contenido de una sola vista y clientes antiguos antes de cerrar estas rutas.
- El catálogo conserva el lector combinado existente con límite de 10.000 filas por fuente. No se añadió caché de permisos que retrase revocaciones. Falta optimizar búsquedas dirigidas/indexadas para gran volumen y verificar contra PostgreSQL real con catálogo no vacío; las pruebas actuales del catálogo usan pools sintéticos.
- Detectado al revisar Android: `MessageMediaResolver` conserva enlaces resueltos por 50 minutos, mientras el servidor limita firmas a 15 minutos; también contiene caminos de respaldo a URL pública. Revisar vencimiento/renovación y propagar los permisos antes de hacer privados los buckets; **no modificado en esta etapa**.
- Quince funciones diferenciales todavía pendientes, además de dominio estable y validación física/multiparte de reproducción, LIVE, regalos y llamadas. No se gastaron regalos ni se contactó a terceros.
