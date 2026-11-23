# Selección del catálogo de PlayMatch

Al elegir otro juego, una petición anterior podía sustituir la ficha visible. Una búsqueda nueva también podía recibir un error del detalle anterior. El ViewModel cancela el detalle obsoleto y da al detalle vigente un plazo total de ocho segundos. La expiración libera el indicador de carga y conserva la ficha elegida.

Las tres pruebas de Robolectric fallaron con las clases Android originales y aprobaron con el ViewModel corregido. Cubren el orden inverso de las respuestas, un error que llega después de cambiar la búsqueda y el tiempo agotado. La selección de contratos del API de PlayMatch aprobó 14 casos de catálogo, coincidencias y conexiones.

## Motor de ajedrez evaluado

El paquete de laboratorio chess.js 1.4.0 generó 8.902 posiciones legales a profundidad tres desde el tablero inicial. Pasó jaque mate, ahogado, enroque a través de una casilla atacada, captura al paso que expone al rey y subpromoción. El motor marcó el fin de la partida tras la tercera repetición y tras 50 movimientos por jugador. Para aplicar FIDE hay que permitir reclamar en esos umbrales y distinguir los finales automáticos de cinco repeticiones y 75 movimientos. La posición FEN por sí sola perdió el historial de repetición al recargar. [Biblioteca y licencia](https://github.com/jhlywa/chess.js/) · [Reglas FIDE](https://handbook.fide.com/chapter/e012023)

La repetición de ocho movimientos y la enumeración de los siguientes legales tardó 4822 μs en p95 en 100 muestras de escritorio. Es una medición del motor en un proceso local. Una partida nativa requiere persistir jugadas y revisión, validar cada turno en el servidor y recuperar el historial al reconectar. Stockfish es un motor separado con licencia GPLv3 y protocolo UCI. Siguen pendientes las integraciones de Ajedrez, Ludo y Damas, bots, chat, voz, invitaciones de partidas y los tipos restantes de Compartir. [Stockfish](https://github.com/official-stockfish/Stockfish) · [Garantías de Socket.IO](https://socket.io/docs/v4/delivery-guarantees/) · [Fases de Novvin](236-fase-0-novvin-compartir-juegos-api-2026-11-22.md)
