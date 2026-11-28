# Reacciones durante la partida y recuperación de la búsqueda

28 de noviembre de 2026. Corrección de los recursos de juegos de mesa que usan la vista integrada en Android y la web.

## Reacciones en la mesa

Con el código anterior una jugada pendiente podía descartar el toque de un emoji. Las reacciones tienen ahora un envío independiente y un mensaje de confirmación. Si la conexión se corta antes de que llegue la respuesta, el mensaje indica que la entrega quedó sin confirmar. Al salir de la mesa o terminar la partida, se limpian los mensajes y se descartan las confirmaciones tardías.

Las reacciones de otros jugadores aparecen con el nombre del remitente durante cinco segundos. La pantalla compara el historial de la mesa con el recibido antes y deja pasar las reacciones que llegan durante la partida, aunque el reloj local difiera del servidor. Al volver a una mesa, las reacciones que ya forman parte de su historial no vuelven a mostrarse.

## Búsqueda de partida

Los controles de búsqueda, invitación y actualización quedan inactivos mientras se abre una sala. El aviso de error se conserva al refrescarse automáticamente el listado. La web cancela tras doce segundos una solicitud HTTP que queda esperando, incluido el cuerpo de la respuesta, y libera el control para reintentar. La búsqueda pública sigue usando las reglas de emparejamiento del servidor.

## Validación

Pasaron 48 pruebas Node, cuatro de ellas nuevas. La prueba de la web simuló una respuesta HTTP cuyo cuerpo no llegaba y comprobó que se pudiera reintentar y volver al estado de espera de rival. En Chromium, dos sesiones de 360 y 620 píxeles conectadas a una API simulada comprobaron el defecto anterior y la corrección. Se mantuvo pendiente e intacta la jugada hasta su confirmación y la otra sesión recibió la reacción. El ajedrez local conservó su cuadrícula durante el jaque mate y el bot respondió en cuatro anchos de 320 a 620 píxeles.

Los ensayos se realizaron en navegador y con un transporte Android simulado. Quedan pendientes una APK que incluya estos recursos, dos dispositivos físicos y el despliegue.
