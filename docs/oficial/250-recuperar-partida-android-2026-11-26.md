# Recuperar una partida de Juegos al volver a Android

Al regresar del perfil o el chat de un rival, la primera consulta podía fallar y dejar al jugador en la lista general. La vista conserva el identificador y vuelve a consultar la misma mesa. El botón «Actualizar salas» también reintenta esa consulta. Si el servidor confirma que la sala ya no existe, la vista vuelve a la lista con una explicación que se mantiene después de cargar las demás partidas.

Una partida abierta conserva su tablero durante una interrupción. El aviso se muestra una sola vez, dentro de la mesa. Cuando el navegador informa que volvió la conexión, el cliente consulta el estado de la mesa. La recuperación solo lee el estado. Un cambio de vista invalida respuestas pendientes para evitar que se reabra una mesa que el jugador decidió dejar.

Se reprodujeron tres fallos de recuperación y un aviso duplicado con la interfaz y el canal Android simulados. Las dos suites de Juegos cubren la recuperación temporal, la sala cerrada, la vuelta de la conexión y la respuesta tardía después de elegir juego local. El recorrido con dos teléfonos físicos sigue pendiente.
