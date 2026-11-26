# Abrir una invitación de Juegos desde su aviso

La bandeja de Android enviaba a la pestaña Juegos al tocar un reto, aunque el aviso ya tenía el identificador de su mesa. La ruta nueva entrega ese identificador al tablero y queda bajo la cuenta que abrió el aviso. Volver desde el tablero devuelve a la bandeja. Si la cuenta cambia, se cierra la ruta. Un aviso sin identificador válido conserva el acceso general a Juegos.

El tablero espera a que el canal Android esté disponible y consulta las mesas de la cuenta. Destaca el reto que corresponde al aviso. La aceptación sigue siendo explícita. Si la persona ya pertenece a la partida, el mismo aviso ofrece volver a ella o consultar el resultado. Si la invitación venció, la lista de mesas sigue disponible. La actualización de una lista igual conserva el foco de teclado.

Las dos regresiones iniciales reprodujeron el fallo en la vista móvil. La suite Node aprobó 28 pruebas tras la corrección. Las pruebas JVM se ejecutan sobre el grafo de navegación que registra la ruta. El ensayo en un teléfono con dos cuentas sigue pendiente.
