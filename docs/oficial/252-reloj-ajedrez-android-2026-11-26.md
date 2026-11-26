# Sincronización del reloj del ajedrez en línea

El reloj visible de una mesa de ajedrez se actualiza con las lecturas nuevas del servidor aunque el turno y la revisión de la partida sigan iguales. Una lectura repetida o anterior conserva el tiempo más reciente. Las actualizaciones del reloj conservan la pieza seleccionada y el foco. El servidor sigue resolviendo el resultado por tiempo agotado.

Antes del cambio, la mesa ignoraba el avance del reloj en el mismo turno. Se reprodujo en dos pruebas de la lógica del tablero y con dos cuentas en una API aislada al avanzar 45 segundos del lado del servidor. Tras la corrección, ambas vistas mostraron el tiempo actualizado y recibieron el resultado autorizado al vencer el turno. También se comprobó que una partida local comienza con su propio reloj. Sigue pendiente el ensayo con el teléfono real en segundo plano.
