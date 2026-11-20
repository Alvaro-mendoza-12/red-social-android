# Precarga del feed tras recuperar el búfer

**CORREGIDO en Android.** Si el vídeo visible agotaba su reserva mientras se preparaba el siguiente, la precarga podía quedar abandonada aunque el usuario siguiera en la misma página y la reproducción se recuperase. En los perfiles, una caída de reserva entre dos vecinos también podía omitir el segundo.

El feed cancela la lectura cuando el reproductor visible necesita el ancho de banda. Espera a recuperar el umbral de inicio y permite un solo reintento por vídeo. Del plazo de 1.500 ms en red móvil o de 3.500 ms en Wi-Fi descuenta lo gastado en el intento anterior. El cambio de selección, sesión o elegibilidad cancela la tarea. El lector conserva el límite de prefijo por dispositivo y la comprobación de acceso del almacenamiento.

| Comprobación | Resultado |
| --- | --- |
| Bucle publicado | Tres fallos entre diez casos con reloj virtual. El siguiente vídeo recibió un intento en lugar de dos y el perfil omitió el segundo vecino. |
| Candidato | Diez pruebas aprobadas en la compilación Android. Cubren la recuperación, las oscilaciones repetidas, el plazo agotado y el cambio de selección. En el caso de 900 ms, se reanudó con 780 ms disponibles. |
| Caché en Android API 35 | Tras interrumpir la primera precarga después de 64 KiB, el segundo intento recibió 3249 ms de los 3.500 iniciales. Se retiró el MP4 de origen y el lector del feed recuperó de la caché los 393.216 bytes esperados. El ensayo controló la señal de búfer. |
| Decodificación | En un ensayo independiente, un único ExoPlayer presentó el primer fotograma de tres selecciones en 3447, 445 y 450 ms. Las fuentes H.264 eran locales. |
| Ubuntu en servicio | Las dos rutas de disponibilidad devolvieron HTTP 200 por TLS 1.3 y anunciaron 25 capacidades en la revisión cc6a022e. |

**PENDIENTE** medir en un teléfono físico el primer fotograma frente a la duración del gesto en Wi-Fi y red móvil. También siguen abiertas la validación física de llamadas y notificaciones, la capacidad del Ubuntu real y la recuperación completa en otro destino.

El commit de código es 014488ec83c9abf504f02e40c6378dca45923805. Las huellas de archivo, clases y mediciones por selección están en el [registro de pruebas](evidencias/234-precarga-del-feed-tras-recuperar-el-buffer.json).
