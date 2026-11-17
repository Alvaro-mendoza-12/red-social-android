# 177 · Carga, búsqueda y borrado de la biblioteca de Videos

La revisión móvil solicitada el 17 de noviembre encontró fallos en Historial, Ver después y Mis colecciones. Las bases anteriores eran `main df40d1c0` y `feature/live-gifts 424da696`, con el mismo árbol. Se conservaron los cambios locales y las reservas del otro asistente; esta continuación no incorpora su ajuste de comentarios ni el diseño experimental de errores.

## Fallos y correcciones

- Una página pendiente podía volver a insertar un video después de confirmar su retirada del Historial o de Ver después. La confirmación invalida las lecturas anteriores, quita el elemento visible y recarga la primera página conservando los elementos restantes durante esa lectura.
- Borrar todo el historial no invalidaba una carga anterior, que podía volver a mostrarlo. El borrado confirmado invalida esas respuestas y deja el historial vacío.
- Actualizar el Historial liberaba el indicador de borrado aunque la operación siguiera pendiente. La actualización conserva esa exclusión hasta que termina el borrado, evitando enviarlo dos veces.
- Cambiar la búsqueda de una colección conservaba los videos y el desplazamiento de la consulta anterior durante los 250 ms de espera. «Cargar más» podía pedir directamente la segunda página del filtro nuevo y mezclarla con resultados anteriores. La edición vacía inmediatamente los resultados y el desplazamiento y espera la primera página del filtro nuevo.
- Volver rápidamente al mismo texto de búsqueda podía admitir una respuesta de la visita anterior a ese texto. Cada edición invalida la petición anterior antes de esperar, incluso en la secuencia A → B → A.
- Eliminar la colección abierta, o recibir un catálogo donde ya no existe, conservaba el desplazamiento y podía dejar activo el indicador de carga de videos. Se retira la selección mediante su transición completa: invalida las páginas, cancela la búsqueda pendiente y vacía videos, filtro, desplazamiento e indicador. Una eliminación confirmada también retira inmediatamente la colección del catálogo local, aunque falle la recarga posterior.

## Evidencia y límites

Se compilaron directamente los tres ViewModels reales y su prueba con Kotlin 2.2.10 y las dependencias Android/JVM del proyecto. La referencia anterior reprodujo **nueve fallos de aserción en quince pruebas**. La candidata aprobó esas quince pruebas. Después se añadieron cuatro controles de recarga fallida tras borrado confirmado y de navegación a otra colección durante una eliminación: la selección final aprobó **diecinueve pruebas**, sin fallos y con las huellas de entrada estables.

Los controles conservan los elementos cuando el servidor rechaza el borrado, liberan los indicadores tras los errores, mantienen la paginación normal y el orden elegido y permiten reintentar una búsqueda. Una recarga fallida posterior a una eliminación confirmada no restaura el elemento eliminado ni deja la interfaz cargando.

Los registros, fuentes de referencia y huellas están en `D:/Novvin-Entregas/evidencias/2026-11-17-video-library/`, en `baseline`, `candidate` y `final`. No se ejecutó una batería Gradle completa ni se generó una APK de esta continuación. Quedan pendientes el recorrido físico de estas hojas, las transiciones de sesión y las demás lecturas y escrituras de la biblioteca; esta selección no cierra la revisión integral móvil ni inicia la revisión integral web. No hay cambios de API ni un despliegue de servidor asociado.
