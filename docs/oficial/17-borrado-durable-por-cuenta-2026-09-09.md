# Borrado de mensajes directo: durabilidad y aislamiento por cuenta

Fecha: 9 de septiembre de 2026. Rama de trabajo: `feature/live-gifts`.
Alcance de este bloque: conversaciones gestionadas por `MessageRepository`.
Los repositorios separados de grupos/comunidades y la edición offline no
quedan certificados por estas pruebas.

## Problemas comprobados

La antigua cola seleccionaba `pending_action = 'delete'` sin actor y antes de
filtrar cuenta. Borrar podía sobrescribir un envío pendiente en esa misma
columna. La llamada usaba la autenticación mutable del singleton y después
de recibir el mensaje canónico volvía a insertar una copia anterior con
estado `sent`, revirtiendo cambios concurrentes. El worker tampoco retenía
su trabajo durable por un fallo aislado de borrado.

## Implementación

- `MessageDeletionEntity` / `MessageDeletionDao`: tabla separada con mensaje,
  chat, actor, alcance, revisión y estado `pending/applied/failed`. El remitente
  no se usa como propietario de “eliminar para mí”.
- `MessageDeletionQueue`: valida pertenencia al chat y autoría de “para todos”
  antes de guardar. Persiste inmediatamente en una transacción local: no espera
  el bloqueo de un envío que esté esperando a la red. El reintento remoto sí
  usa la misma exclusión por ID que los envíos; revisa la sesión antes/después
  de cada suspensión y dentro de la transacción.
- La intención se guarda antes de intentar red. “Para mí” no cancela un envío;
  “para todos” impide subir/enviar contenido pendiente. Se pide igualmente el
  borrado remoto porque un envío anterior pudo confirmarse en el servidor y
  perder su respuesta.
  Los envíos revisan además esa cancelación antes y después de subir media y
  antes de enviar el mensaje, validando nuevamente la sesión después de leer
  la intención. No se puede retirar una petición que el servidor ya recibió;
  en ese caso se ejecuta el borrado después de su respuesta.
- `MessageDeletionTransport`: token explícito del lote, identificación de
  `MESSAGE_NOT_FOUND` diferenciada de un 404 de ruta/usuario, confirmación de
  IDs y de las marcas devueltas. Rechazos permanentes dejan un fallo visible;
  errores temporales conservan la cola. Cancelación no equivale a éxito.
- La confirmación actualiza sólo marcas de eliminación en Room, conservando
  lecturas, reacciones, edición y archivos que cambiaron durante la petición.
- `MessageDeletionPolicy`: se aplica después de combinar historial/Room/memoria;
  un resultado tardío no hace reaparecer un mensaje eliminado. Las citas
  afectadas no exponen el texto ni el enlace multimedia. No se borra el archivo
  ni la copia base como sustituto de mostrar una marca de eliminación.
- Los rechazos guardados se muestran al abrir el chat correspondiente con la
  cuenta afectada. La selección múltiple informa si algunas acciones fallan.
- `SyncManager` conserva los reintentos de borrado. La programación de acciones
  pendientes usa `APPEND_OR_REPLACE`, para no perder una acción que llegó después
  de la última consulta vacía del worker anterior.
- La lectura cacheada también aplica `cleared_at`; no debe mostrar mensajes
  anteriores a “vaciar chat” mientras llega el observador.

## Migración sin eliminar el historial

Room pasa de 54 a 55. La ruta 54→55 es aditiva: crea la tabla/índices nuevos,
sin reconstruir ni limpiar tablas existentes. Las otras rutas conservan el
normalizador histórico e incorporan la nueva tabla.

Un borrado antiguo “para mí” contiene los actores en `deleted_by`; se importa
por actor y se comprueba nuevamente la membresía antes de enviar. Un borrado
antiguo “para todos” no identifica quién pulsó la opción: **no se reproduce
automáticamente adivinando la cuenta**. Se retira su marca optimista, se guarda
un aviso para que el remitente lo confirme desde el menú y no se envía ninguna
mutación remota por esa inferencia. Filas antiguas con JSON ilegible se
conservan, no se destruyen ni se reasignan.

La prueba de migración abre un archivo SQLite con el esquema exportado real
de la versión 54, inserta historial, media y pendientes, y lo vuelve a abrir
con Room 55. No usa migración destructiva ni sólo una base vacía en memoria.

No instalar una APK anterior (Room 54) encima de estos datos: el proveedor
histórico tiene fallback destructivo en downgrade. Un rollback seguro exige
una compilación que conserve Room 55 o respaldo/restauración verificados;
no recomendar desinstalar la app como solución.

## Evidencia y pendientes

La primera corrida enfocada pasó las 17 pruebas nuevas de cola/migración.
Falló el verificador general porque el nuevo exportador omite `indices` cuando
la lista está vacía. Se adaptó la lectura de ese campo opcional; la comparación
exacta de tablas, SQL e índices permanece. La puerta de las 08:21 -05:00 pasó
520 pruebas en 134 suites (cero fallos/errores/omitidas) y generó el APK.
El ajuste posterior de persistencia inmediata pasó la puerta completa
test/assemble/lint a las 17:58 -05:00: 520 pruebas en 134 suites, sin fallos,
errores ni omitidas. El APK de las 17:48 pesa 269.038.775 bytes, SHA-256
`92131581D537BBADFC34A3AA28DDC88DBD103EAB8C4B15A03B835A38BEBA40EE`.
Es evidencia de este bloque, no de la corrección posterior del aviso de
mensajes programados. No atribuirlo al APK del 8 de septiembre.

Casos incluidos: actor destinatario, cuenta inactiva antes del límite, falta de
membresía, autoría, token fallido, sesión cambiada durante renovación/petición,
confirmación concurrente con lectura/reacción, red temporal, rechazo permanente,
cancelación, dos workers, envío en vuelo, reapertura de archivo y payload tardío.

Pendiente de validación física: dos cuentas/dispositivos, borrar con modo avión,
cerrar/reabrir, reconectar, confirmar en el otro dispositivo y recibir eventos
tardíos. No hay teléfono conectado a ADB; no se recrearon los emuladores que el
usuario pidió eliminar.

Trabajo que sigue abierto: edición durable, cola de encuestas con sus opciones,
equivalencia de grupos/comunidades, destacados de mensajes (aún no implementados),
otras superficies que usan DAO directamente, previews del inbox y borrados
simultáneos en diferentes dispositivos. El adaptador legado Supabase aún hace lectura/actualización de
`deleted_by`; necesita una operación atómica en servidor para excluir pérdida
de actualizaciones entre dispositivos. Las pruebas de la cola Android no
demuestran atomicidad de ese backend ni cobertura de todos los botones.

### Revisión adicional de superficies — 9 de septiembre, 23:12 -05:00

`getUserChatsFlow` observa `MessageDao.getMessagesByIdsFlow`, no las
intenciones de borrado. `ConversationItem` compone la vista previa con
`content_type`/`content`, sin aplicar las marcas de borrado. Además,
`ChatWithUserProfile.toDomainWithDetails(null)` vuelve al preview guardado;
pasarle null no equivale a ocultarlo. Por inspección del código, estas rutas
pueden conservar texto que ya se ocultó en el chat; falta implementar/probar
una política de visibilidad de bandeja por cuenta, incluyendo ese fallback.

En las pantallas/repositorio/modelo/DAO de Mensajes no se encontró una acción
de destacar mensajes ni su listado. “Favoritos” está implementado para chats
y stickers: no debe documentarse como si cubriera mensajes destacados.
