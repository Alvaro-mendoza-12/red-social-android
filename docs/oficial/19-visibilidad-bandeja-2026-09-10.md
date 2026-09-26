# Vistas previas de mensajes: visibilidad por cuenta y borrado

Fecha: 2026-09-10. Rama: `feature/live-gifts`.
Estado: 606 pruebas en 139 suites aprobadas, incluidas 26 con SQLite nativo;
assemble y lint aprobados. Validación final registrada el 10 de septiembre.

## Problema comprobado

El chat aplicaba las intenciones durables de borrado, pero la bandeja observaba
sólo las filas de mensajes referenciadas por `last_message_id`. Un borrado
local no cambiaba esa fila, por lo que el preview podía seguir mostrando texto.
La composición usaba el contenido/tipo sin comprobar `deleted_for_all`.
Pasar null a `ChatWithUserProfile.toDomainWithDetails` tampoco ocultaba el
mensaje: activaba el fallback del resumen almacenado en la tabla de chats.

## Cambio por módulos

- `InboxPreviewReader`: lectura sólo local, por cuenta autorizada, dentro de
  una transacción. Se activa con cambios de chats, perfiles, mensajes e
  intenciones de borrado. No espera token, servidor ni descarga de historial.
- `InboxPreviewPolicy`: aplica las mismas reglas de borrado del chat,
  respeta “vaciar chat”, controla JSON ilegible y sanea el resumen de la
  conversación además de su último mensaje.
- Consultas de `MessageDao`/`InboxCandidateQuery`: un último mensaje por chat,
  mediante lotes de hasta 100 búsquedas indexadas unidas con `UNION ALL`;
  los IDs se enlazan como parámetros, no se interpolan. Las intenciones se
  cargan en lotes de hasta 800 IDs. Sólo cuando el mensaje más reciente está
  oculto se recorren candidatos anteriores por cursor.
  No materializa todo el historial ni aplica un límite fijo que declare vacío
  un chat con mensajes visibles más antiguos. Se detiene al alcanzar el vaciado.
- `MessageDeletionDao.forChats`: carga las intenciones de las conversaciones
  visibles; las reglas “para mí” respetan al actor y “para todos” al autor.
- `ConversationComponents`/`InboxMessagePreview`: un tombstone dice
  “Mensaje eliminado”; no conserva el texto ni el icono del archivo anterior.
- `MessageRepository.getUserChatsFlow` y su exposición pública `userChats`
  usan la proyección local segura. Los cambios no destruyen la copia de
  historial que usan sincronización/reconciliación.

La observación usa el seguimiento de tablas de Room, disponible en la
dependencia 2.8.4 del proyecto; cambios de esas tablas provocan nuevas lecturas,
no un sondeo de red periódico.
Referencia: [InvalidationTracker, documentación oficial de Android](https://developer.android.com/reference/kotlin/androidx/room/InvalidationTracker).

## Casos de verificación

Pruebas con Room real: borrar para mí/todos, otra cuenta, remitente falso,
intención de otro chat, rechazo del servidor, respuesta tardía, marcas remotas,
JSON dañado, todos ocultos, vaciado/zonas horarias, resumen antes de descargar
historial, resumen sin ID, pendiente local, más de 90 ocultos, empate de fechas,
más de mil chats, membresía retirada, cierre de sesión, reapertura del archivo
y observador que cambia sólo por la intención (sin modificar la tabla de chats).
El presentador tiene pruebas separadas de texto eliminado, multimedia y etiquetas.

La primera corrida enfocada (55 casos incluyendo programados) detectó dos
fallos: mensaje nuevo posterior a vaciar y recorrido tras 90 mensajes ocultos.
Se confirmó que mensajes/chats existían: el SELECT directo devolvía el último,
pero la consulta correlacionada de la primera implementación retornaba vacío
en el motor SQLite del entorno Robolectric Windows. La versión actual usa
búsquedas directas con parámetros, conserva el índice por chat/fecha y mantiene
ambas pruebas, junto a tres casos de resumen sin ID. No se borraron datos,
no se cambió el resultado esperado y no se tomó el resumen antiguo como éxito.

La puerta enfocada corregida terminó a las 02:11 -05:00: 60 pruebas, cero
fallos/errores/omitidas. Desglose: 26 de bandeja con Room real, 3 de etiquetas,
20 del lector de programados y 11 de su controlador. La prueba de consulta
comprueba el índice de chat/fecha, parámetros no interpretados como SQL, IDs
duplicados y entrada vacía. Se agregó ejecución de los mismos 26 casos de
bandeja con SQLite nativo AOSP en la puerta completa; no se elimina la suite
del motor legado que detectó los fallos.

La suite completa terminó a las 02:12 -05:00: 606 pruebas, 139 suites,
cero fallos/errores/omitidas. Los mismos 26 recorridos Room pasaron también
con SQLite nativo AOSP (además de los 26 del motor legado). La validación final
`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` terminó a las
03:50 -05:00: `BUILD SUCCESSFUL in 9m 24s`. Gradle confirmó pruebas y assemble
actualizados y ejecutó lint: cero errores, 194 advertencias.

No cambia el esquema Room 55 ni borra archivos. El APK de programados de las
23:08 del 9 de septiembre no contiene este bloque. El APK validado está en
`app/build/outputs/apk/debug/app-debug.apk`, 273.927.848 bytes, generado a las
02:14:22 del 10 de septiembre. SHA-256 verificado después de la puerta final:
`E2CD64CB5492EE0085CE6097717CD32D4CD913B1F0D114345F8A53471957CB9F`.
El reporte es `build/reports/kotlin-build/red social - live-gifts-build-2026-09-10-03-50-00-0.txt`.

## Alcance pendiente

El contador de no leídos no se recalcula con este bloque; pertenece al recorrido
de recibos/no-leídos. Las cachés internas usadas por sincronización no equivalen
a una superficie segura de presentación: cualquier pantalla nueva debe consumir
la proyección autorizada, no esos campos internos.

Falta probar la interacción visual en teléfono y notificaciones en dos cuentas;
no afirmar que toda superficie multimedia o de Mensajes quedó cubierta por estas
pruebas. No se implementó todavía “Destacar mensaje”: los favoritos actuales de
chats/stickers no sustituyen ese requisito.
