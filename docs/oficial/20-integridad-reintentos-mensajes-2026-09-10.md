# Integridad de los reintentos de mensajes

Fecha: 2026-09-10. Rama: `feature/live-gifts`.
Estado: implementado y probado localmente; pendiente de copia y despliegue en Ubuntu.

## Fallo comprobado

Los POST de mensajes de chats, grupos y comunidades usaban `ON CONFLICT (id)
DO UPDATE` sin exigir que la fila existente perteneciera al mismo autor y destino.
Reenviar el mismo ID podía reemplazar texto, adjuntos, autor y conversación;
un reintento legítimo podía deshacer una edición o cambiar `read` a `sent`.
El archivo activo del servidor coincide con la copia leída de su directorio
de despliegue. Comparado con esa copia, el cambio de rutas tiene cuatro hunks:
30 líneas añadidas y 139 retiradas. No hay cambios ajenos en esa comparación.

Además, el envío a grupos/comunidades usaba una lectura que admite visitantes
en salas públicas, pero no exigía pertenencia antes de escribir.

## Cambios

- `api/src/message-insert-store.js`: módulo de inserción de una sola vez,
  compartido por los tres POST. La clave única resuelve envíos simultáneos.
  Los duplicados nunca se actualizan. Una segunda consulta sólo devuelve una
  fila del autor autenticado, misma conversación, tipo de sala y canal.
  Acepta los alias de identidad propios ya resueltos por autenticación.
- `api/src/data-routes.js`: conserva los controles de acceso y de multimedia;
  devuelve HTTP 201 en la creación y HTTP 200 con el mensaje actual en el
  reintento autorizado. Un conflicto devuelve HTTP 409 `MESSAGE_ID_CONFLICT`
  sin datos del mensaje ajeno. El reintento no vuelve a publicar eventos,
  crear notificaciones ni modificar la vista previa del chat.
- Un visitante que no sea miembro ni creador recibe HTTP 403
  `ROOM_ACCESS_DENIED` al intentar enviar en un grupo/comunidad público.
- No requiere migración SQL ni modificación del esquema Room.

Se usa un SELECT posterior al INSERT, porque bajo READ COMMITTED un conflicto
puede proceder de otra transacción que todavía no era visible al comenzar
el INSERT. La consulta posterior ve su resultado confirmado.
Referencias primarias: [INSERT en PostgreSQL 16](https://www.postgresql.org/docs/16/sql-insert.html)
y [aislamiento de transacciones](https://www.postgresql.org/docs/16/transaction-iso.html).

## Evidencia

- Suite de API `node --test tests/*.test.js`: 111 resultados, 110 aprobados,
  cero fallos, uno omitido por ser exclusivo de Linux (monitor público).
  Incluye seis pruebas nuevas del módulo de inserción.
- PostgreSQL **16.9 local**, instancia desechable independiente de las bases
  existentes: 25 comprobaciones con tablas TEMP y ROLLBACK. Comprueba autor,
  alias propio, chat, tipo de sala, canal, texto arbitrario, ID generado,
  ediciones, reacciones, recibos y borrado preservados.
- Seis comprobaciones adicionales de concurrencia: en cada uno de los tres
  destinos, 12 inserciones simultáneas producen una sola fila; otra conexión
  queda realmente esperando un bloqueo hasta que se confirma la primera
  transacción, tras lo cual recibe la fila existente sin modificarla.
- 11 comprobaciones de los manejadores reales de las tres rutas con SQL
  PostgreSQL local. Las consultas auxiliares de identidad/perfiles/catálogos
  usan fixtures; los eventos se interceptan y no se usan cuentas reales.
  Se verifican creación, reintento, conservación de estado, conflicto de autor
  y rechazo de visitantes sin pertenencia.
- Servidor Ubuntu inspeccionado por SSH: PostgreSQL **16.15**, contenedores
  presentes. No se ejecutaron allí las nuevas pruebas ni el nuevo código.
- Android también fue corregido en este bloque: `MessageStatusNormalizer` y
  `PendingMessageSyncPolicy` conservan `delivered`/`read` después de un ACK,
  recuperan multimedia local si la respuesta es parcial y los caminos de
  grupos/comunidades aplican la misma regla. La suite completa posterior a
  estos cambios (`:app:testDebugUnitTest`, JDK 21) terminó `BUILD SUCCESSFUL`
  a las 04:30 -05:00: **607 pruebas, 139 suites, cero fallos, errores u
  omitidas**. El reporte es `build/reports/kotlin-build/red social - live-gifts-build-2026-09-10-04-30-05-0.txt`.
  El APK y su hash de la puerta anterior se documentan en
  [19](19-visibilidad-bandeja-2026-09-10.md); este bloque no generó un APK.

Para repetir las pruebas SQL, usar una instancia PostgreSQL local desechable
y una base llamada `nowi_message_insert_test`. Configurar únicamente
`MESSAGE_INSERT_TEST_DATABASE_URL` apuntando a loopback y ejecutar
`npm run verify:message-inserts:local`. El primer script crea las dos tablas
de prueba usando el DDL del archivo de rutas; el segundo ejercita las rutas.
No apuntar estas pruebas a una base con mensajes reales.

`scripts/verify-message-insert-store.js --temporary-tables` está preparado para
verificar un servidor con sus tablas existentes: copia sólo estructura a
tablas TEMP de la conexión y hace ROLLBACK. Su ejecución remota quedó pendiente.

## Despliegue pendiente y reversión

Destino comprobado: `alvaro@192.168.18.22`,
`/home/alvaro/conectate-local-fallback/api`, contenedor
`conectate-local-fallback-api-main`. Está documentado en el handoff y scripts
del proyecto. La revisión automática rechazó dos intentos de transferir los
archivos nuevos por SCP: exige autorización específica para copiar ese código,
aun después de verificar que el destino corresponde al servidor del usuario.
No se eludió el rechazo. Sólo se descargó el archivo actual para comparar.

Payload de ejecución pendiente: `src/data-routes.js` y
`src/message-insert-store.js`. Payload de verificación:
`tests/message-insert-store.test.js` y `scripts/verify-message-insert-store.js`.
Antes de transferir, volver a comprobar la base contra SHA-256
`355E425C30CFACFAA37607C84D79DAD4889C410D74FBC3324B776267945129BD`
y respaldar los archivos/imágenes activos. Si la base cambia, revisar el diff.
No desplegar toda la rama sucia mediante un archivo global del repositorio.
Después de aprobar la transferencia: probar en TEMP, construir únicamente la
API con estos archivos, renovar sólo ese servicio y verificar salud y lecturas
públicas de Mensajes/Social/Videos. Mantener una imagen anterior y el respaldo
de fuente para rollback; no borrar ni restaurar tablas de usuarios.

## Pendientes descubiertos

- La nueva regla Android conserva los estados confirmados al consumir respuestas;
  quedan por auditar las acciones RPC de encuestas y los caminos históricos que
  no devuelven el mensaje completo.
- Las rutas RPC existentes ya usan DO NOTHING, pero requieren auditoría de sus
  efectos repetidos, ámbito grupo/comunidad y vistas previas con fechas antiguas.
- La entrega de eventos tras un fallo entre INSERT y publicación necesita una
  cola transaccional durable; no se afirma entrega exactamente una vez.
- Auditar reintentos después de alcanzar cuotas, cambiar permisos o retirar
  adjuntos, y colisiones con IDs del historial importado.
- “Destacar mensaje”, persistencia offline de otras acciones y pruebas físicas
  de Videos/Social/Mensajes siguen pendientes. Este arreglo no demuestra que
  todos los errores visuales o de reproducción estén resueltos.
