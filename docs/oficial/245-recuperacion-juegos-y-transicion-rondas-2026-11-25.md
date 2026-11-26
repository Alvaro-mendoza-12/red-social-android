# Recuperación en Juegos y cambios de fase en Rondas

## Juegos de mesa

Una respuesta JSON incompleta podía retirar a la vez el temporizador y el único modo de resolver la operación. Sustituir el canal Android también dejaba solicitudes esperando hasta el vencimiento. Ahora cada solicitud termina con una respuesta, un error o su plazo. Al reconectar se resuelven las solicitudes pendientes del canal anterior como errores recuperables. Reintentar la apertura de una mesa privada conserva el identificador hasta recibir confirmación del servidor.

## Rondas en Android

El contador sigue actualizándose mientras se consultan las participaciones y las respuestas. Al llegar a cero, Android consulta la fase al servidor en el siguiente tick. Una consulta anterior de participaciones se cancela al entrar en votación. Las respuestas de una ronda anterior conservan las comprobaciones de identidad que protegen la ronda nueva. El sondeo de fases sigue limitado a la pantalla visible y a una solicitud en curso.

## Evidencia

Los ensayos comparan el transporte Android simulado, el cambio de fase con consultas detenidas y las regresiones del controlador. Los registros de Gradle, las firmas y los tamaños del paquete se conservan en la entrega, junto con el identificador del código compilado. La revisión integral y el recorrido físico permanecen abiertos.
