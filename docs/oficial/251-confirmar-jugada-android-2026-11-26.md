# Confirmar una jugada cuando se pierde la respuesta

La mesa muestra el envío mientras espera la respuesta. Si se corta el transporte o la revisión del tablero cambia, conserva la partida y consulta al servidor. Los controles de la jugada se deshabilitan durante la comprobación y conservan el foco. El cliente consulta la mesa sin repetir la acción. Cuando recibe el estado vigente, presenta el tablero actualizado y permite un nuevo intento si la revisión sigue igual.

Se reprodujeron cuatro fallos de confirmación y un control que aparentaba estar disponible mientras la jugada seguía pendiente. La prueba con dos cuentas en una API aislada perdió la respuesta de una tirada de Ludo ya guardada. Los toques posteriores se ignoraron y la mesa mostró el mismo dado para los dos jugadores. Queda pendiente el ensayo con dos teléfonos físicos.
