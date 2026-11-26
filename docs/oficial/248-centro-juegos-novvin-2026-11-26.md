# Reconstrucción del centro de juegos de Novvin

## 0. Auditoría de la entrega 497d1aee

Las cinco mesas autónomas son Ajedrez, Ludo, Damas inglesas, Tres en raya y Cuatro en línea. Android las carga desde recursos locales en `BoardGamesScreen`, con un puerto hacia la API autenticada. Vite distribuye la misma interfaz y el mismo motor. PlayMatch ofrece descubrimiento de personas y juegos externos. Dibuja y Adivina pertenece al directo, con otro anfitrión y ciclo de sesión. El plan experimental 105 trata los acuerdos de partida de PlayMatch.

El centro existente permite jugar localmente, contra Novvin y en salas públicas o privadas. Las invitaciones se guardan y aparecen al consultar salas. La API controla pertenencia, bloqueo, revisión y turno. El cliente vuelve a leer la partida y puede reanudarla. La interfaz anterior junta el selector y los modos, apenas distingue la actividad en curso y usa el chat general como salida. Los resultados aparecen en una línea de texto. Ajedrez carece de reloj, Ludo de trayectorias animadas y las salas carecen de chat integrado y revancha confirmada.

## 1. Referencias y selección técnica

- Board Game Arena separa la búsqueda automática de la lista de mesas y explica cuándo comenzará la partida. Novvin aprovecha la distinción y muestra la ocupación de la sala. [Ayuda de Board Game Arena](https://en.boardgamearena.com/faq)
- Lichess separa el tablero de sus controles, admite temas y muestra el tiempo como parte de la partida. Novvin conserva el foco en las piezas y hace visibles los estados de juego. [Lichess](https://lichess.org/features)
- Ludo King organiza las invitaciones desde amistades y destaca dado, fichas y reacciones. Novvin adopta la jerarquía con arte original. [Ludo King](https://ludoking.com/features)
- Papergames.io reúne juegos de dos en un catálogo breve. Abrir una partida casual requiere pocas decisiones. Novvin presenta la acción en el contexto del juego seleccionado. [Papergames.io](https://papergames.io/en//)

Las valoraciones de tamaño y mantenimiento son relativas al centro actual. Un motor visual tampoco aporta autorización, orden de turnos o persistencia por sí solo.

| Candidato | Renderizado y compatibilidad | Integración, tamaño y mantenimiento | Licencia y seguridad |
| --- | --- | --- | --- |
| DOM, CSS, Web Animations y motor existente | Aceleración de transformaciones, botones semánticos y mismo código para WebView y navegador | Conserva el puente Android y el servidor. Coste adicional de recursos y estilos | API de plataforma, CSP local y paquete sin motor nuevo. chess.js BSD-2-Clause, ya incluido |
| libGDX | Java, GPU 2D, Android y web por GWT | Integra bien un juego nativo. Duplicaría la presentación y requeriría pruebas de GWT, ciclo Android, accesibilidad y puente social. Tamaño mayor | Apache-2.0. Más binarios que revisar. Puede consumir la API actual |
| JavaFX | Grafo de escena Java en escritorio, destinos móviles por Gluon | Importante inversión en empaquetado y presentación web. Mantenimiento alto para la misma mesa | GPLv2 con Classpath Exception para OpenJFX. La cadena móvil suma herramientas y licencias propias |
| PixiJS | WebGL/WebGPU y capacidades de accesibilidad con módulo propio | Útil para muchas partículas o sprites. Suma runtime, pruebas de GPU y una capa de controles. Tamaño intermedio | MIT. El transporte seguiría en la API Novvin |
| Phaser | Escenas 2D, Canvas/WebGL en navegador móvil | Adecuado para minijuegos con escenas y física. Suma runtime, recursos y puntos de prueba a cinco tableros discretos | MIT para el framework. La validación del multijugador sigue siendo del servidor |
| Three.js | Escenas 3D por GPU | Coste de GPU, modelos, carga y controles alto. El arte estático cubre la necesidad de profundidad de este centro | MIT. Añade un renderizador y recursos que mantener |
| Socket.IO | Transporte bidireccional con reconexión | Útil para eventos de baja latencia. Tendría que integrarse en Android, con ACK y recuperación persistente. No altera el motor | MIT. Requiere autenticación por sala, versiones y reanudación propias |

[libGDX](https://libgdx.com/wiki/start/project-generation) · [OpenJFX](https://github.com/openjdk/jfx) · [Gluon](https://docs.gluonhq.com/) · [PixiJS](https://pixijs.com/8.x/guides/components/renderers) · [Phaser](https://docs.phaser.io/phaser/getting-started/what-is-phaser) · [Three.js](https://threejs.org/docs/) · [Socket.IO](https://socket.io/docs/v4/delivery-guarantees/) · [chess.js](https://github.com/jhlywa/chess.js)

## 2 y 3. Dirección artística y wireframes

Sala oscura `#100e1b`, superficie violeta `#1c1830`, marfil `#f5f0ff`, acento lila `#c6a9ff`, menta `#81edd1` y naranja `#f3a565`. Tipografía del sistema para controles y títulos de peso alto. Ajedrez usa marfil e índigo, Ludo esmaltes vivos, Damas madera teñida, Tres en raya piedra violeta y menta, y Cuatro en línea metal verde y fichas naranjas. Las portadas se generan como una lámina de fotografía de producto con cinco escenas originales. Los tableros se construyen con elementos táctiles y foco visible.

```text
Móvil                                     Pantalla ancha
┌ Biblioteca  Mesas  Torneo ┐            ┌ Biblioteca     Mesas     Torneo ┐
│ Portada       Juego       │            │ Portada grande │ Juego y modos │
│         Jugar   Invitar   │            │ Filtros   Portadas de catálogo │
│ Filtros y portadas        │            └─────────────────────────────────┘
└───────────────────────────┘            ┌ JUGADORES ───── ESTADO ────────┐
┌ Jugadores y turno         ┐            │ Tablero         │ Chat         │
│          Tablero          │            │                 │ Historial    │
│ Dado, fichas o resultado  │            │ Acción/resultado│ Reglas       │
│ Chat  Reacciones  Reglas  │            └─────────────────────────────────┘
└───────────────────────────┘
```

Se descartó una cuadrícula con acciones duplicadas en cada tarjeta. El juego seleccionado reúne sus acciones sobre una sola superficie. En mesa, el tablero retiene el espacio principal. La espera utiliza puestos reales y plazas disponibles. Las animaciones corresponden a eventos nuevos, y se omiten al reconstruir históricos o cuando el dispositivo solicita movimiento reducido.

## 4 a 7. Implementación

- Catálogo de siete juegos con filtros, descripción, cantidad de jugadores, partidas propias, salas visibles y clasificación semanal. Los números proceden de las salas que el servidor ha autorizado a mostrar. La sala privada distingue jugadores y puestos libres.
- Ajedrez muestra jaque, última jugada y captura. El reloj local y en línea ofrece tres, cinco y diez minutos, además de partidas sin límite. La API inicia los relojes cuando se completan los puestos y decide la pérdida por tiempo. La regla de la mesa adjudica la victoria al otro jugador cuando vence el reloj.
- Ludo anima el dado al recibir la tirada y desplaza la ficha al confirmar la jugada. Mantiene las estrellas, la meta y las fichas agrupadas por casilla. Damas anima desplazamientos, incluidas las capturas y coronaciones que acepta el motor.
- Tres en raya y Cuatro en línea animan las jugadas y muestran la línea ganadora. Reversi voltea discos encerrados, pasa automáticamente si corresponde y cuenta fichas al terminar. Gomoku usa líneas de cinco o más en un tablero de 15 × 15. Los siete admiten juego local, contra Novvin y con otras cuentas.
- El chat se despliega al lado del tablero cuando hay espacio y debajo en pantallas estrechas. Guarda los 24 mensajes recientes, evita duplicados de reintento y limita el ritmo por participante. La revancha requiere aceptación de todas las personas y crea una sala privada nueva con el orden de inicio rotado.
- Una búsqueda en la sala permite retar a cuentas por nombre. El resultado excluye bloqueos y puestos ocupados. La persona invitada puede aceptar desde las mesas. Para cada invitación nueva se registra un aviso en la bandeja existente. Las APK anteriores conservan los cinco juegos compatibles y las mesas sin reloj.

## 8 y 9. Validación prevista para la entrega

El lote focalizado cubre reglas, compatibilidad con APK anteriores, invitaciones a una cuenta fuera de amistades, bloqueo, relojes, chat y revanchas. Los registros de navegador y de empaquetado se guardan fuera de Git en la entrega. Los nombres de los ensayos son cuentas ficticias en una base de prueba. Los resultados de emulador, servidor productivo y Play se anotan únicamente cuando se completen.

Dos láminas WebP se cargan sin peticiones de arte a terceros. Las portadas se generaron con ImageGen a partir de objetos de tablero fotografiados en estudio, con colores y materiales propios de Novvin. No se añade un motor ni una biblioteca de ejecución. Los siguientes candidatos son Dominó y Mancala, con especial atención a la sincronización de fichas ocultas en Dominó.
