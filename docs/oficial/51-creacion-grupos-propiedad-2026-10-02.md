# Creación de grupos y comunidades: propiedad y reintentos

Fecha: 2026-10-02. Estado: desplegada en la API; PostgreSQL comprobado. Validación de grupos desde Android pendiente.

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

Las siete pruebas iniciales usan un pool simulado y comprueban la consulta parametrizada y sus resultados. Tras la comprobación de Tailscale autorizada por el usuario, se respaldaron PostgreSQL, storage, estado de IA y Compose; sus checksums y estructura fueron verificados. Respaldo: `/home/alvaro/conectate-backups/20261003T032837Z`. No se ejecutó una restauración completa en este bloque.

Se comprobó la revisión anterior de `data-routes.js` antes de sustituirla y se guardaron sus fuentes e imagen para reversión. Se desplegaron la ruta y el helper juntos y se recreó únicamente la API. `/ready` devolvió `ok=true`; los nueve contenedores estaban saludables antes del cambio. La comparación posterior confirmó **99 de 99 fuentes JavaScript de la API**, sin diferencias ni archivos adicionales. El marcador de release se actualizó al commit de backend `f2909d736c051ba37fbc5c8dd07025e62d6ddab3` y su huella de fuentes.

Contra PostgreSQL real pasaron **seis comprobaciones adicionales**: creación de grupo; reintento del propietario conservando ID, creador y reglas; rechazo de otro propietario; rechazo de otro tipo de sala; rechazo de sala eliminada; y creación de comunidad. Se usaron IDs aleatorios dentro de una transacción revertida: las filas de esa prueba no se conservaron. Estos resultados prueban el helper desplegado y su consulta real; sigue pendiente probar creación y reintento desde Android y la interfaz completa de grupos.

La evidencia sanitizada está en [resultados del despliegue](../evidencias-grupos-propiedad-2026-10-02/resultados.json).

## Siguiente validación de grupos

Crear un grupo ficticio, enviar adjuntos y stickers, responder, revisar el resumen del último mensaje y probar encuestas desde dos cuentas. La interfaz ya expone cambiar o quitar el voto y abrir votantes; falta confirmar la actualización cuando vota otra cuenta. Continúan pendientes las opciones de miembros, permisos, búsqueda, archivos, marcadores y llamadas del grupo.

## Despliegue y reversión

Con acceso SSH disponible: respaldar los archivos actuales y el estado de datos, desplegar `data-routes.js` junto con `collection-room-create-store.js`, verificar salud y ejecutar las comprobaciones con salas ficticias. Para revertir, restaurar ambos archivos de la revisión anterior y reiniciar únicamente la API. No se debe publicar una versión de la ruta que importe el helper sin desplegarlo también.
