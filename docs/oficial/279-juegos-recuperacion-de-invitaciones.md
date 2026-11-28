# Recuperación al aceptar invitaciones y reintentar búsquedas

28 de noviembre de 2026. Corrección en los recursos compartidos por Android y la web.

## Entrada a una mesa

La lista informa cuando está aceptando una invitación, entrando a una mesa o abriendo una partida existente. Mientras espera, bloquea los botones que iniciarían otra entrada. Si se pierde la respuesta al aceptar, consulta la mesa mediante la lectura autenticada que exige un asiento. Si el servidor registró la entrada, abre esa partida sin repetir la aceptación. Si el asiento no está confirmado, vuelve al listado y permite reintentar cuando la invitación siga disponible. Cambiar al juego contra Novvin descarta las confirmaciones tardías.

## Reintento de búsqueda pública

Si el servidor creó una mesa pública y se perdió la respuesta, el reintento usa el identificador de la solicitud original, que el servidor guardó como identificador de la mesa. La recuperación de una mesa ajena sigue utilizando el emparejamiento existente. Los identificadores pendientes se separan por visibilidad, juego, capacidad y reloj, y se descartan al confirmar la operación.

## Validación

Tres casos focalizados fallaron con el código anterior. Pasaron 52 pruebas de cliente, incluidas cuatro nuevas, y tres conjuntos de pruebas de API con datos de laboratorio. Chromium reprodujo la pérdida de la respuesta en 360 y 620 píxeles. Con la corrección, cada sesión hizo una aceptación y una lectura de confirmación, y abrió el tablero del rival correcto. Este ensayo simuló la caída del enlace y el estado confirmado en la API.

La APK 9.2.2 (195) es anterior a este cambio. Quedan pendientes la compilación de una APK con estos recursos, la comprobación en teléfonos y el despliegue.
