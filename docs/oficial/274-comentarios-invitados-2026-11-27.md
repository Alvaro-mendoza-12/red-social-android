# Comentarios en el modo invitado de Android

27 de noviembre de 2026. Continúa la [revisión de Android](273-avisos-android-2026-11-27.md).

El diálogo de comentarios compartía los indicadores de carga y error con las búsquedas de personas, las vistas previas y la actualización de Social y Videos. Una operación ajena podía ocultar un error de comentarios, mostrar uno que pertenecía al feed o mantener el indicador del diálogo después de recibir su respuesta.

El diálogo conserva su propio estado. Distingue la carga, una respuesta vacía y un fallo. Ofrece reintentar sobre el mismo tipo de contenido y el mismo identificador. Al cerrar el diálogo cancela la lectura. Al volver a abrir el mismo contenido, las respuestas y los botones de la visita anterior quedan invalidados. Android limita el listado a 100 comentarios distintos para evitar filas con la misma clave.

Pasaron 27 pruebas JVM de release en 4 clases. Los cinco casos nuevos comprobaron el reintento tras un fallo, el plazo de una lectura lenta, una respuesta tardía, el cambio entre una publicación y un video con el mismo identificador y las filas repetidas. La selección también cubrió la renovación de invitado, las secciones de Social y la navegación inicial. El ensayo visual del diálogo y la actualización real de Play siguen pendientes.
