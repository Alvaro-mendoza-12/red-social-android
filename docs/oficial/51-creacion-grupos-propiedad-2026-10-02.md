# Creación de grupos y comunidades: propiedad y reintentos

Fecha: 2026-10-02. Estado: corrección local probada; despliegue pendiente.

## Hallazgo

La ruta de creación aceptaba un ID y hacía un `ON CONFLICT DO UPDATE` que reemplazaba también el creador y el tipo de sala. La consulta no exigía que la cuenta autenticada fuera propietaria de la sala existente. Además, repetir la creación sustituía los roles de miembros existentes por los del cuerpo nuevo. Este hallazgo se obtuvo mediante revisión del código; no se intentó sobrescribir salas de usuarios en el servidor público.

## Corrección

- PostgreSQL permite actualizar una colisión únicamente si coincide el tipo de sala, su creador pertenece a la identidad autenticada y la sala no está eliminada. La condición forma parte de la escritura, para cubrir colisiones concurrentes.
- Una actualización permitida conserva el creador, el tipo, el estado de eliminación, las reglas de conversación y la fecha de creación.
- Un ID perteneciente a grupos o comunidades importados produce un conflicto y no se crea una fila local que lo sustituya. Las salas importadas existentes continúan usando sus rutas de consulta y edición; no se recrean mediante esta ruta.
- Una colisión rechazada devuelve HTTP 409 / `ROOM_ID_CONFLICT`, sin devolver los datos de la sala ni continuar insertando miembros.
- Los miembros ya existentes conservan su rol cuando se repite la creación.

No hay migraciones de esquema. La creación normal sin ID sigue generándolo en PostgreSQL.

## Validación

Pasaron siete pruebas nuevas del almacén: condición de propietario y tipo, conflicto sin confirmación, protección de IDs importados en ambos tipos de sala, creador falsificado, creación con ID generado y propagación de fallos de base de datos. Pasaron también las 45 regresiones seleccionadas de mensajes, permisos, confirmaciones, marcadores, solicitudes de amistad y programación: **52 pruebas en total**, sin fallos ni omisiones. Node comprobó la sintaxis de `data-routes.js`.

Las pruebas usan un pool simulado y comprueban la consulta parametrizada y sus resultados. Falta ejecutarlas contra PostgreSQL y validar la creación y el reintento desde Android con la API actualizada. El último intento SSH a la mini PC no terminó dentro del plazo y se detuvo únicamente ese proceso. **La corrección no está desplegada en la mini PC; este documento no certifica que la ruta pública ya esté protegida por este cambio.**

## Siguiente validación de grupos

Crear un grupo ficticio, enviar adjuntos y stickers, responder, revisar el resumen del último mensaje y probar encuestas desde dos cuentas. La interfaz ya expone cambiar o quitar el voto y abrir votantes; falta confirmar la actualización cuando vota otra cuenta. Continúan pendientes las opciones de miembros, permisos, búsqueda, archivos, marcadores y llamadas del grupo.

## Despliegue y reversión

Con acceso SSH disponible: respaldar los archivos actuales y el estado de datos, desplegar `data-routes.js` junto con `collection-room-create-store.js`, verificar salud y ejecutar las comprobaciones con salas ficticias. Para revertir, restaurar ambos archivos de la revisión anterior y reiniciar únicamente la API. No se debe publicar una versión de la ruta que importe el helper sin desplegarlo también.
