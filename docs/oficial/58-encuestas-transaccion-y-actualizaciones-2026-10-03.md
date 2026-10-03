# Encuestas: cambio de voto, consistencia y actualización

Fecha: 2026-10-03. Primera parte: API; la comprobación de la nueva interfaz Android se documenta por separado.

## Fallos corregidos en el servidor

El cambio de voto se ejecutaba mediante borrado e inserción separados. Dos dispositivos de una misma cuenta podían dejar selecciones incompatibles. Además, votos importados antiguos podían reaparecer después de cambiarlos o quitarlos.

La API ahora reserva una conexión PostgreSQL para toda la transacción y serializa los cambios de una cuenta en una encuesta. Recomprueba acceso, cierre y pertenencia de la opción; elimina únicamente votos de esa cuenta y esa encuesta; conserva votos de otras personas; y revierte el borrado si falla la nueva escritura. La selección múltiple conserva las otras opciones.

Se emplean [bloqueos de transacción de PostgreSQL](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS). No se modifica el esquema de producción.

Después de confirmar el voto, el servidor publica una invalidación en los canales de los participantes. Incluye el ID y el ámbito de la encuesta; cada cliente debe solicitar su estado con autorización. No se distribuye el estado privado «votado por mí». El envío al gateway admite como máximo 16 operaciones simultáneas y no bloquea la respuesta del voto. El cliente debe recuperar eventos perdidos mediante reconciliación.

## Pruebas completadas antes del despliegue

- 9 pruebas unitarias nuevas: cambio, eliminación, concurrencia, selección múltiple, votos importados, reversión, restricciones y publicación limitada a miembros.
- Suite completa de API: 371 pruebas; 369 correctas, 2 omitidas, 0 fallos.
- PostgreSQL real: 9 comprobaciones en un esquema temporal aislado, incluida una carrera de 20 selecciones de la misma cuenta, un usuario ajeno, una opción de otra encuesta y una escritura fallida. La limpieza del esquema fue comprobada. No se cambiaron votos reales.

## Despliegue comprobado

API desplegada con revisión `c204ad935502cfec36da7e25a481b56cb8d1f26b`. Respaldo de PostgreSQL, storage, estado de IA y Compose: `/home/alvaro/conectate-backups/20261003T062512Z`, con checksums y estructura verificados. No se realizó una restauración completa en esta etapa. Fuentes anteriores e imagen de API se conservaron para reversión. Se recreó únicamente la API; `/ready` fue correcto. Los nueve servicios quedaron saludables. La comparación posterior confirmó 100/100 fuentes JavaScript tanto en el host como en el contenedor, y se actualizó el marcador de release.

## Límites y siguiente etapa

La corrección Android y las comprobaciones posteriores desde dos cuentas se documentan en la [revisión 60](60-encuestas-audio-momentos-avisos-y-perfil-2026-10-03.md). Incluyen actualización de votos ajenos mientras la encuesta es visible, cancelación fuera de pantalla y descarte de respuestas anteriores a cambios de cuenta, sala o voto.

Siguen pendientes las lecturas con un historial grande y la concurrencia al cerrar una encuesta. Los resultados no implican cobertura completa de todos los módulos ni certificación de producción.
