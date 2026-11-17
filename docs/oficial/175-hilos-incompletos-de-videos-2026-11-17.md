# 175 · Hilos incompletos de comentarios en Videos

La API y las lecturas locales pueden entregar una parte del hilo. Si faltaba un antepasado, cada descendiente se convertía en un comentario principal, aunque compartiera un padre visible. Al filtrar «Mis comentarios», esto podía quitar el contexto de la respuesta. Los identificadores duplicados con distinto uso de mayúsculas o espacios podían mostrar la misma respuesta dos veces. Un ciclo de referencias históricas también separaba el hilo.

La agrupación conserva el antepasado disponible, unifica identificadores equivalentes y elige una raíz estable para los ciclos. Reutiliza la raíz resuelta de cada tramo del hilo. Los filtros de autor y los órdenes de fecha y relevancia conservan su comportamiento en hilos completos.

La referencia reprodujo tres fallos en diez pruebas. El código corregido aprobó las diez pruebas de agrupación, orden, filtro y estado de comentarios con Kotlin y JUnit en la JVM. La ejecución de la interfaz en Android y la generación de un paquete quedan pendientes. Los registros y huellas de entrada están en `D:/Novvin-Entregas/evidencias/2026-11-17-video-comment-threads/`.
