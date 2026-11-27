# Compresión de la web y medición de carga en Ubuntu

27 de noviembre de 2026. Continúa la [verificación del origen web](269-web-activa-y-apk-arm64-2026-11-27.md) y el [ensayo de solicitudes de mensajes](266-indices-y-solicitudes-ubuntu-2026-11-27.md).

## Recursos estáticos

Se habilitó gzip para los archivos públicos JS, MJS y CSS en el origen web de Ubuntu. Se prepararon 263 archivos comprimidos. Sumaban 9.182.796 bytes antes de comprimir y 2.748.719 bytes comprimidos, una reducción del 70,1 %. Nginx también comprime estos tipos al vuelo cuando un recurso nuevo carece del archivo precomprimido. La prueba verificó las respuestas comprimidas y las respuestas sin comprimir, la igualdad del contenido y las cabeceras de caché y de seguridad.

La hoja de estilos principal pasó de 143.997 a 28.334 bytes transferidos. El módulo probado de sesiones en vivo pasó de 593.385 a 153.413 bytes. El trabajador PDF pasó de 1.264.342 a 375.247 bytes. Estas cifras corresponden a los recursos individuales, sin incluir el resto de las solicitudes de sus pantallas.

El tráfico web se cambió del puerto privado 4211 al 4212. Se conservaron las rutas de API, las respuestas de autorización de Capy y el origen anterior para revertir. WebKit abrió el formulario de inicio de sesión desde los dos dominios y recibió archivos gzip. Se comprobó que la ruta solicitada llega al formulario y que Privacidad responde en ambos dominios. `/health` devolvió 200. `/ready` registró 503 durante la activación.

## Ensayo aislado de peticiones y entregas

Cada ejecución planificó 12 peticiones por segundo durante 60 segundos, con diez cuentas sintéticas, cinco conexiones WebSocket y hasta 16 peticiones simultáneas. Se usaron una base PostgreSQL desechable y contenedores que compartían una red sin salida y sin puertos publicados. Las pruebas combinaron perfiles, feed, notificaciones y mensajes, y midieron la recepción por WebSocket.

| CPU del generador | CPU de API | CPU de tiempo real | CPU de PostgreSQL | Peticiones iniciadas de 720 | Entregas WebSocket recibidas |
| --- | --- | --- | --- | --- | --- |
| 0,40 | 1,25 | 0,35 | 0,75 | 684 · 663 | 174/174 · 169/169 |
| 0,90 | 1,25 | 0,35 | 0,75 | 695 · 658 | 170/170 · 167/167 |
| 0,90 | 3,00 | 0,35 | 0,75 | 720 · 171 | 180/180 · 44/47 |
| 0,90 | 3,00 | 0,75 | 0,75 | 657 · 612 | 158/160 · 147/149 |
| 0,90 | 3,00 | 0,75 | 1,50 | 717 · 700 | 179/179 · 175/175 |

Las 6.277 peticiones iniciadas devolvieron HTTP 2xx. Con 0,90 CPU para el generador hubo menos atrasos de programación. La mayoría de las ejecuciones alcanzó el límite de peticiones simultáneas. Con 3 CPU para la API y 0,35 CPU para tiempo real, el p95 HTTP de la ventana más lenta fue 90 ms en la primera ejecución y 3.536 ms en la segunda. Esta última se detuvo a los 18 segundos al alcanzar tres entregas sin confirmar dentro del plazo.

Al repetir la configuración de 3 CPU para la API y 0,75 para tiempo real con 1,50 CPU para PostgreSQL, se despacharon más peticiones y se confirmaron todas las entregas iniciadas dentro del plazo. El p95 HTTP de la ventana más lenta quedó en 1.770 y 2.650 ms. PostgreSQL de producción tiene un límite de 2 CPU. La capacidad sostenida de producción queda pendiente de una medición repetible.
