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

## Límites y siguiente etapa

La UI existente ya permite votar, cambiar, quitar y abrir votantes. Se está corrigiendo Android para que actualice también los votos ajenos mientras la encuesta está visible, se detenga fuera de pantalla y descarte respuestas antiguas durante cambios de cuenta, sala o voto. Esas comprobaciones todavía no forman parte de esta entrega de servidor.

Siguen pendientes la prueba desde dos cuentas Android con este servidor desplegado y la revisión de rendimiento de lecturas de encuestas con un historial grande. Los resultados no implican cobertura completa de todos los módulos ni certificación de producción.
