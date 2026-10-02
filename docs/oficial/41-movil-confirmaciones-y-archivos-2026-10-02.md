# Confirmaciones de lectura y archivos entre redes

Fecha: 2026-10-02. Continúa la revisión física de conversaciones individuales de la [revisión 40](40-movil-pruebas-fisicas-mensajes-2026-10-01.md). Android mantiene 117 / 9.0.20.

## Fallo de lectura reproducido

El sticker enviado por la tablet ya estaba leído en PostgreSQL, pero seguía mostrando enviado en la pantalla abierta. Una nueva actualización de historial acababa corrigiéndolo. La ruta `read-visible` guardaba las lecturas del área visible, pero no publicaba esos cambios por realtime.

Ahora, después de confirmar la escritura, publica actualizaciones de mensajes completos y normalizados a los participantes autorizados. Se mantienen la autorización del chat, los IDs seleccionados, el límite de 200 IDs y la exclusión de mensajes propios/eliminados. No se avanza el cursor global de lectura de toda la conversación. Enviar solo un ID y un estado podría borrar campos multimedia en el consumidor Android; por eso se conserva el mensaje completo.

El envío por realtime se ejecuta en segundo plano y un fallo de publicación no convierte una lectura ya guardada en error ni hace esperar al cliente por el gateway. La consulta de publicación también excluye mensajes eliminados mientras tanto.

La prueba específica falló antes de corregirlo por ausencia de publicación. Después pasaron nueve pruebas específicas. La suite completa de API terminó con **355 pruebas, 353 aprobadas, dos omitidas por plataforma y cero fallos**.

## Despliegue y comprobación física

- Respaldo previo creado y verificado: `/home/alvaro/conectate-backups/20261002T050154Z`, con PostgreSQL, storage y datos de IA. Todos los checksums y listas de archivos se comprobaron.
- Se compararon los archivos de origen del servidor antes de sustituirlos. Se desplegaron los dos archivos modificados y el nuevo módulo; se reconstruyó únicamente el contenedor API.
- Los nueve servicios terminaron saludables y `/ready` respondió correctamente.
- La tablet envió texto desde la interfaz nativa; el Motorola lo recibió y leyó por LTE. Un cliente autenticado observó `event: message`, `action: updated`, `status: read`, y la tablet pasó a leído manteniendo abierto el chat.
- No se calculó un tiempo de creación a lectura porque el observador no recibió el evento de creación de ese mensaje. No se informa «0 ms» como latencia de lectura.

Resultados y hashes: [evidencias sanitizadas](../evidencias-movil-confirmaciones-2026-10-02/). No se publican credenciales, tokens ni contenido de conversaciones personales.

## Archivos y respuestas

Se eligió un TXT ficticio de 4.194.306 bytes mediante el selector de Android en la tablet. La interfaz mostró «Subiendo 0 %» y estado pendiente durante la carga. Se entregó al Motorola y quedó leído; el tamaño guardado en el servidor coincide con el original. Esta muestra comprueba presencia del indicador y entrega, pero no mide cada incremento de porcentaje ni todos los formatos de documento.

También se respondió desde el Motorola a un sticker recibido de la tablet. La respuesta llegó y conservó en servidor el ID del sticker citado. La cita mostraba un nombre genérico «Usuario» y el documento enviado solo mostraba «Archivo»: ambos quedan identificados para la siguiente corrección de presentación.

No se declara cerrado el módulo de mensajes: faltan el resto de sus opciones, la prueba de reintento de archivos/fotos, grupos y comunidades, y posteriormente llamadas, salas y LIVE. La captura de cierre de la app informada por el usuario sigue sin un stacktrace reproducido; el buffer de fallos del Motorola estaba vacío en esta comprobación.

## Reversión

Revertir esta revisión, copiar los módulos API anteriores y reconstruir únicamente API restaura el comportamiento previo. No hay migración de esquema. El respaldo verificado conserva los datos anteriores al despliegue; restaurarlo no es necesario para revertir este cambio de código.
