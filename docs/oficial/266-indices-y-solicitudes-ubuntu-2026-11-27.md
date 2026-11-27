# Consultas de solicitudes de mensajes en Ubuntu

27 de noviembre de 2026 · Continúa el [diagnóstico de mensajería](264-lectura-visible-android-y-diagnostico-ubuntu-2026-11-27.md).

## Resolución de participantes

El estado de una solicitud resolvía las identidades de los participantes al comprobar la membresía, buscar a la otra persona y contar las cuentas distintas. PostgreSQL ahora resuelve esas identidades una vez y reutiliza el resultado en las tres comprobaciones. Varias identificaciones de la misma cuenta cuentan como una sola persona. Las entradas nulas no dan acceso. Las comprobaciones de bloqueo, aceptación y rechazo conservan sus condiciones y su orden. El disparador de primera introducción y sus bloqueos transaccionales siguen gestionando los envíos concurrentes.

La consulta de historial de mensajes nativos incluye ahora la expresión del índice `lower(chat_id)` y conserva la igualdad con el identificador exacto. `EXPLAIN` verificó que PostgreSQL 16.15 pasó de recorrer la tabla a usar el índice existente. El ajuste usa el esquema actual y se instala al inicializar el procedimiento en la API.

## Pruebas en PostgreSQL

La imagen candidata deriva de la API preparada con Node 22.23.5. Pasaron sus tres pruebas unitarias y 19 casos funcionales con PostgreSQL. Los casos incluyeron alias históricos y locales de la misma cuenta, listas con entradas nulas, grupos y participantes de chats directos, amistades, historial previo, menciones, reintentos y envíos concurrentes. Los ensayos usaron cuentas y datos sintéticos. Los servicios de producción conservaron sus imágenes durante los ensayos.

La comparación de consultas hizo seis pares de 160 evaluaciones en una misma base de datos sintética. Alternó el orden de las variantes y evaluó tanto conversaciones nuevas como aceptadas. La tabla tenía 12.000 mensajes pertenecientes a otra conversación. Las resoluciones de identidad bajaron de 2.480 a 1.840 por lote. La mediana del tiempo total pasó de 562,26 a 304,14 ms. Es una medición de SQL sobre esas condiciones. La latencia de las conversaciones de usuarios sigue pendiente de medición tras un despliegue efectivo.

La API también se probó a 12 llegadas por segundo durante 60 segundos, con diez cuentas sintéticas, cinco clientes WebSocket, Node 22.23.5 en ambas variantes y los mismos límites de CPU. La referencia despachó 680 de 720 peticiones y la candidata 697. Todas devolvieron HTTP 2xx. El p95 de la ventana más lenta fue de 3.403,72 ms en la referencia y de 2.855,31 ms en la candidata. Llegaron a tiempo 170 de 172 mensajes y 174 de 174, respectivamente. Ambos ensayos quedaron por debajo del objetivo de despachar todas las llegadas. Estas dos ejecuciones no certifican una capacidad sostenida para producción.

## Operación

El respaldo del 27 de noviembre terminó con estado 0 a las 03:33, hora de Bogotá. El último ensayo programado de restauración terminó con estado 0 el 22 de noviembre. Se comprobó el estado de los servicios de systemd en Ubuntu. La caché local de APT mostraba 38 paquetes actualizables y un reinicio pendiente para aplicar el kernel 6.8.0-151. El usuario de mantenimiento carece de `sudo` no interactivo para aplicar esos cambios por la conexión actual.

El monitor de salud continúa ejecutándose. `/health` responde 200 y `/ready` responde 503 por el antivirus de almacenamiento. La promoción de la imagen candidata sigue pendiente de firmas vigentes y de la aprobación de `/ready`. La revisión integral permanece abierta.
