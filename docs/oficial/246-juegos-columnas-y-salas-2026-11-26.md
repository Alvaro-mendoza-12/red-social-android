# Cuatro en línea y salas privadas de Juegos

Cuatro en línea admite tocar cualquier parte de una columna jugable. La casilla con borde discontinuo indica dónde caerá la ficha. Cada columna tiene un botón con su número y las fichas que contiene. Al jugar mediante teclado, el foco permanece en la columna si sigue disponible. La posición enviada al servidor se obtiene de las jugadas legales del mismo motor que valida las partidas locales.

La lista de amistades se carga en paralelo con la actualización de una sala privada. Durante la carga, la partida puede empezar y el anfitrión puede salir. La búsqueda por nombre aparece al recibir la lista. Si falla, la sala ofrece reintentar la lista y sigue comprobando si la mesa ya puede abrirse. Una respuesta tardía solo se aplica a la sala de espera que la pidió.

Las dos pruebas de bloqueo fallaron sobre el código anterior. Pasaron 22 pruebas del motor y la interfaz tras la corrección. En Chromium se completó una partida táctil a 320, 360 y 480 píxeles sin desplazamiento horizontal. Un canal Android simulado abrió la mesa con la lista de amigos pendiente y transmitió la posición de caída correcta. Quedan pendientes la partida autenticada y la comprobación en un teléfono físico.
