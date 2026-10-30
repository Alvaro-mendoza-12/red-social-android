# 92 — Miniaturas, portada y contadores inmediatos

Fecha: 29/10/2026, Bogotá. El propietario reporta en la web tarjetas de stickers con «Preparando archivo», una portada lenta en la ficha del contacto y un contador que aparece después de recibir el mensaje. Continúa la auditoría 90 y mantiene las preferencias de compra de 91.

## Qué se pudo atribuir al código

**Contador:** el evento del canal autenticado actualizaba la vista previa del mensaje, pero el contador dependía de otra lectura HTTP de la bandeja. Por tanto, el texto podía llegar antes del número aun con el mensaje realtime recibido correctamente. Tres casos nuevos fallaron con la lógica anterior y aprobaron con la corrección.

Ahora un evento `created` entrante válido actualiza la proyección local de no leídos de una conversación ya conocida. La fila, navegación y título usan ese mismo estado sin esperar otra consulta. Se conservan la reconciliación con servidor, los filtros de mensajes propios/leídos/borrados, el aislamiento de cuenta, la separación de archivados y la deduplicación por ID. Las respuestas de solicitudes iniciadas antes del evento no pueden revertir esa proyección. Una conversación desconocida, eventos históricos y eventos sin información suficiente siguen requiriendo la lectura autoritativa; no se inventan participantes ni pertenencias.

La reproducción anterior en navegador utilizó únicamente dos cuentas sintéticas existentes. Tras enviar un mensaje temporal entre ellas se retrasaron seis segundos **sólo las consultas posteriores de bandeja en ese navegador**. El evento realtime llegó, pero la pestaña todavía no mostraba el contador en la observación realizada unos 2,8 segundos después. La reproducción no alteró el servidor, su conexión ni los mensajes de otras personas. Al terminar se borró para todos el mensaje de prueba y se confirmó la lectura de la conversación propia.

**Portada:** la ficha del contacto solicitaba el archivo original completo. Ahora pide una variante de 640 píxeles para imágenes estáticas compatibles y reutiliza la caché de imágenes en memoria de la cuenta, incluido su recurso ya decodificado. Al ampliar se conserva la dirección original. El cambio de cuenta invalida la caché. Se reutiliza el componente de imagen de perfil ya probado para avatares, manteniendo sus recuperaciones y sin transformar GIF/WebP/APNG potencialmente animados.

En una portada JPEG de la cuenta sintética propia, el original pesaba **28.205 bytes**, la variante de 640 px **3.408 bytes** y la de 160 px **594 bytes**. Son **88 % menos bytes** para la portada pequeña de ese fixture. Las solicitudes terminaron en 1.015, 695 y 479 ms respectivamente; son muestras individuales, no percentiles ni una promesa de velocidad para las portadas de todos los usuarios. No se midió la portada privada mostrada en la captura del propietario.

**Stickers:** el selector usaba el archivo completo, incluso para miniaturas estáticas pequeñas. Ahora solicita una variante de 160 px para los declarados estáticos y conserva el original cuando son animados. Los mensajes y el sticker que se selecciona conservan la dirección original. El estado de carga de imágenes dice «Cargando imagen…»; no se ocultan los fallos reales ni se elevan los límites de concurrencia/descarga.

Las animaciones necesitan seguir transfiriendo sus bytes: no se promete eliminar toda espera por cambiar el texto. El catálogo de la cuenta sintética estaba vacío; por ello no se presenta su caso como reproducción de toda la biblioteca personal del propietario. La prueba de componente comprueba miniatura estática y conservación de la fuente animada; la verificación de interfaz usa un fixture declarado y limitado al navegador de QA.

Las decisiones de cargar imágenes cercanas y diferir recursos fuera de pantalla se contrastaron con [Intersection Observer](https://developer.mozilla.org/en-US/docs/Web/API/Intersection_Observer_API) y la [documentación de carga diferida](https://developer.mozilla.org/en-US/docs/Web/Performance/Guides/Lazy_loading). Se mantienen las lecturas protegidas, cancelación, límites y caché por identidad del proyecto.

## Validación y publicación

- **862 pruebas web en 159 suites**, todas aprobadas, incluidas las cinco regresiones nuevas.
- **96 pruebas Node** aprobadas; TypeScript y build aprobados; 19 páginas públicas sin JavaScript.
- **Lint: cero errores y siete advertencias existentes**. No se oculta el aviso de tamaño de chunks.
- Cambios funcionales comiteados y subidos en `38fbce8b` de `feature/live-gifts` y `2de47393` de `main`.
- Publicado en [Novvin](https://red-social-android.vercel.app), despliegue **dpl_9vtQpEPDVsqsg4fzPFDmizeGt1aX**, READY/Production, origen `red-social-android-cjw5t19gj-alvaro-s-projects1.vercel.app`. El cierre del dominio público aprobó **90/90 controles HTTP/SEO**.

Con el código nuevo publicado se repitió el envío entre las mismas dos cuentas sintéticas, reteniendo seis segundos las consultas posteriores de bandeja. En la observación unos 2,9 segundos después del evento, el contador de la fila y del título ya mostraba el mensaje no leído mientras esas consultas seguían retenidas. La prueba compara independencia de la consulta antes/después; los tiempos del controlador no son una medición de latencia hasta el primer pintado. Los mensajes propios de prueba se borraron y se confirmó la lectura; no se contactó con usuarios ajenos.

Para comprobar la portada y el selector, el navegador de QA sustituyó únicamente el campo de portada del contacto sintético y la respuesta de su catálogo vacío por la imagen ya existente de la propia cuenta. No se subieron archivos ni se guardaron favoritos. La portada se decodificó a 640 px y al reabrir la ficha se reutilizó exactamente el mismo recurso Blob de la caché de cuenta. El sticker estático se decodificó a 160 px y desapareció su tarjeta de carga. Se contrastó que el módulo principal de la sesión coincidía con el HTML público actualizado. Se cerró el navegador al terminar. Este fixture demuestra el recorrido de miniaturas, no el rendimiento de la biblioteca privada completa del propietario.

## Relación con la red y alcance

El enlace físico de 100 Mbps y los límites separados de Tailscale Funnel siguen siendo los observados en 90. Un switch gigabit y el cableado adecuado pueden levantar el límite físico si todos los tramos/puertos negocian gigabit. No corrigen por sí mismos una actualización tardía del contador ni una descarga innecesariamente grande. Tampoco garantizan multiplicar el caudal contratado o la velocidad de cada petición.

La continuación modifica la web, no la API, el esquema de datos ni Android. Los APK/AAB 170 firmados y sus pruebas conservan el alcance del cierre 90; no se reconstruyen ni publican de nuevo por este cambio web. No se cambia el proveedor de retiros ni se realiza ninguna compra.
