# Web 9.0.43 · Android 9.0.36 (133)

## Cambios

- Menú de conversación vertical con información del contacto, búsqueda, selección,
  notificaciones, fijado, favoritos, archivo y cierre. Traducción, encuestas y
  programación se conservan en un panel secundario.
- Selección de mensajes con copia y eliminación para uno mismo; los fallos no se
  presentan como una eliminación completa. Se puede cancelar un envío pendiente.
- Descarga de audio desde las opciones del mensaje, sin un botón suelto junto al
  reproductor. Descarga de documentos conservada en su tarjeta.
- Barra de escritura y burbujas más compactas; fondo de dibujos original. Los
  álbumes conservan su presentación habitual.
- Android conserva su cabecera. El compositor integra emojis, adjuntos y cámara,
  con el micrófono/enviar a la derecha. El audio tiene una única pista de avance.
- Los stickers dejan de formar parte de la galería de fotos y videos de Android.
- Llamada web flotante con minimización y restauración; los nodos de reproducción
  se mantienen montados. Detalle de video con reproductor y comentarios en sus
  columnas correctas.
- La subida web indica bytes y fase de verificación. Los archivos usan una cola
  limitada separada de los mensajes listos para enviar y stickers del catálogo.
- Análisis de archivos con mayor presupuesto de tiempo, conservando los límites
  de tamaño, descompresión, profundidad y concurrencia. Un análisis incompleto
  sigue sin permitir publicar el archivo; tiene un error específico y reintento.
- FCM queda vinculado a la sesión nativa que lo registró. Un cierre no elimina
  registros que otra sesión haya vuelto a vincular. Android descarta avisos de
  cuentas que ya no están guardadas y conserva la revocación pendiente sin red.

## Comprobaciones registradas

- Web: compilación de producción y 506 pruebas en 85 suites.
- Interfaz en Chrome y Firefox: menú, preferencias, selección, galería y descarga
  de audio; tamaños 390×844, 844×390, 1440×960, 1920×1080 y 2866×1433.
- API candidata: 822 pruebas aprobadas y 6 omitidas; mismas fuentes verificadas
  en la imagen desplegada.
- Primer despliegue revertido al detectar que la API no podía leer los archivos
  de política con modo 0600. Se recuperó `/ready`, se corrigió el modo de estos
  dos archivos de configuración sin secretos a 0644 y se repitió el despliegue.
- Segundo despliegue del servidor correcto: copia PostgreSQL comprobada,
  analizador y API saludables, fuentes de imagen verificadas y otros servicios
  conservados. Imagen API `sha256:a8a36e150005272e55fa70a94c6bcd324e96807be153773fd5d592740e833bf8`.

El alcance anterior no constituye una certificación integral del producto.
La comprobación de la APK firmada, el envío real de la carpeta del usuario y la
publicación web se documentan en los recibos finales de esta versión.
El lint web general aún informa 14 errores y 10 advertencias en módulos previos;
la compilación y las pruebas anteriores no sustituyen esa comprobación.

No se garantiza una posición de búsqueda. Se conserva el SEO de la versión
anterior; la aparición y el orden en cada buscador siguen dependiendo del índice.
