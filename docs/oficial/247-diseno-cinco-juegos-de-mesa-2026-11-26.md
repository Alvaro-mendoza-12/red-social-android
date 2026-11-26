# Rediseño de los cinco juegos de mesa

El selector distingue Ajedrez, Damas, Ludo, Tres en raya y Cuatro en línea con una vista ilustrada de cada juego. La mesa coloca los jugadores y el turno arriba del tablero y las reacciones debajo. El color del jugador corresponde al de sus fichas. Se conservan las entradas de perfil, chat y llamadas para las partidas con otras personas.

Ajedrez y Damas muestran coordenadas, giro desde el lado del segundo jugador, destinos legales, capturas y el último movimiento. En Ludo, las bases, carriles y meta siguen los colores del tablero. Las casillas compartidas muestran cuántas fichas contienen y las acciones grandes permiten elegir cualquiera de las fichas legales. El dado refleja la última tirada. Tres en raya y Cuatro en línea resaltan la secuencia que decide el resultado. Cuatro en línea conserva el toque en toda la columna y el indicador de caída.

El motor conserva las acciones y el formato de las partidas desplegadas. Registra la última jugada y las casillas ganadoras al reconstruir una partida con el historial existente. La prueba local de API valida turnos, persistencia, reacciones y clasificación. Pasaron 23 pruebas del motor e interfaz y 20 recorridos Chromium, con los cinco juegos en 320, 390 y 480 píxeles y cinco mesas en línea con transporte Android simulado. La prueba autenticada en teléfonos físicos sigue abierta.
